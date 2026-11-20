package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

sealed interface InputStopObservation {
    data class Quiet(val outcome: StepOutcome) : InputStopObservation
    data class Unconfirmed(val reason: String) : InputStopObservation
}
interface StepWork {
    suspend fun execute(): StepOutcome
    /** Independent input-owner evidence; cancelling execute() is insufficient. */
    suspend fun revoke(): InputStopObservation
}
interface StepWorkFactory {
    val contracts: List<CapabilityContract>
    fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork
    fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle): Boolean
    /** Only after independent Quiet(Interrupted), with no contradictory input journal. Not a boot proof. */
    fun canReenterAfterQuiet(attempt: StepAttempt, bundle: TaskBundle): Boolean = restartHasNoDeviceInput(attempt, bundle)
    /** True only for APK-owned programs whose start has no screen-observation prerequisite. */
    fun canStartWithoutObservation(request: ExecutionRequest, bundle: TaskBundle): Boolean = false
}

/** Whether the user chose this option, so a passive task can answer only what was selected. */
const val SELECTION_CONTAINS = "control.selectionContains"
/**
 * What happened, in the player's words ("已领取每日礼包", "今天已领过"), written by the task on the branch it took. It
 * changes nothing on screen and does not count as verifying anything; a completed step reports these lines.
 */
const val TASK_RESULT = "task.result"
/** Marks a completed step's evidence that is the task's own result lines rather than how it was verified. */
const val RESULT_PREFIX = "结果："

/** One ordered interpreter for wait and explicitly registered APK-owned actions. */
class BuiltInStepWorkFactory(private val actions: ActionProvider? = null, private val traces: ActionTraceSink? = null) : StepWorkFactory {
    private fun duration(inputs: JsonObject): Long? {
        val number = (inputs["durationMs"] as? JsonPrimitive)?.decimalOrNull() ?: return null
        return try { number.longValueExact().takeIf { it >= 0 } }
        catch (_: ArithmeticException) { null }
    }
    override val contracts = listOf(CapabilityContract("control.wait", 1,
        inputTypes = mapOf("durationMs" to ActionValueType.NUMBER),
        outputTypes = mapOf("durationMs" to ActionValueType.NUMBER)) { inputs ->
        if (inputs.keys != setOf("durationMs") || duration(inputs) == null) "等待时长需为非负整数毫秒" else null
    }, CapabilityContract(SELECTION_CONTAINS, 1,
        inputTypes = mapOf("selection" to ActionValueType.ARRAY, "value" to ActionValueType.STRING),
        outputTypes = mapOf("selected" to ActionValueType.BOOLEAN)) { inputs ->
        if (inputs.keys != setOf("selection", "value") || inputs["selection"]?.jsonArray?.any { it !is JsonPrimitive || !it.isString } != false) "请提供多选结果和要判断的选项" else null
    }, CapabilityContract(TASK_RESULT, 1, inputTypes = mapOf("text" to ActionValueType.STRING),
        validateKnownInputs = { inputs -> if ("text" in inputs && (inputs["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.let { it.isNullOrBlank() || it.length > 60 || it.any { c -> c == '；' || c == '\n' || c == '\r' } })
            ContentIssue("text", "结果说明需为 1–60 个字符，不含“；”和换行") else null },
        validateDefinition = { _, _, _, action -> if ("text" in action.bindings) "结果说明必须由任务版本写明" else null }) { inputs ->
        if (inputs.keys != setOf("text") || (inputs["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank()) "请写明结果说明" else null
    }) + actions?.contracts.orEmpty()
    init { require(contracts.distinctBy { it.id to it.version }.size == contracts.size) { "动作合同身份重复" } }
    private val codec = BundleCodec(contracts)
    private fun definition(request: ExecutionRequest, snapshot: TaskSnapshot, bundle: TaskBundle): Pair<TaskDefinition, JsonObject> {
        require(bundle.version == request.packageVersion && codec.validate(bundle).isEmpty())
        val task = bundle.tasks.single { it.id == snapshot.id && it.targetId == request.context.targetId }
        require(task.steps.map { it.id } == snapshot.stepIds)
        val configuration = Json.parseToJsonElement(snapshot.configurationJson).jsonObject
        require(codec.configurationIssues(task, configuration).isEmpty())
        return task to configuration
    }
    private fun program(attempt: StepAttempt, bundle: TaskBundle): Pair<StepDefinition, JsonObject> {
        val (task, configuration) = definition(attempt.run.request, attempt.run.task, bundle)
        return task.steps.single { it.id == attempt.run.stepId } to configuration
    }
    override fun canStartWithoutObservation(request: ExecutionRequest, bundle: TaskBundle): Boolean = runCatching {
        request.tasks.forEach { task ->
            val (content, _) = definition(request, task, bundle)
            require(content.steps.flatMap { it.actionDefinitions() }.all { it.capability == "control.wait" })
        }
        require(request.tasks.isNotEmpty())
        true
    }.getOrDefault(false)
    override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork {
        val (step, configuration) = program(attempt, bundle)
        val usesInput = step.actionDefinitions().any { action -> contracts.single { it.id == action.capability && it.version == action.contractVersion }.sendsDeviceInput }
        return object : StepWork {
            private val lock = Any()
            private var revoked = false
            private var owner: Job? = null
            private val quiet = CompletableDeferred<Unit>()
            private var session: ActionSession? = null
            override suspend fun execute(): StepOutcome {
                var claimed = false
                try {
                    return coroutineScope {
                        val execution = currentCoroutineContext()[Job]!!
                        synchronized(lock) {
                            check(!revoked && owner == null) { "步骤尝试已结束或已开始" }
                            owner = execution; claimed = true
                        }
                        currentCoroutineContext().ensureActive()
                        val activeSession = actions?.open(attempt, bundle).also { session = it }
                        var verification: String? = null
                        var requiresVerification = false
                        val results = mutableListOf<String>()
                        fun emptyRepeat(node: ProgramNode): Boolean = node is ProgramNode.Repeat &&
                            (repeatCount(node.count, configuration) == 0L || node.program.all(::emptyRepeat))
                        // A result line only reports; the check before it still settles the step.
                        fun reportOnly(node: ProgramNode): Boolean = emptyRepeat(node) ||
                            node is ProgramNode.Action && node.action.capability == TASK_RESULT
                        suspend fun executeBlock(nodes: List<ProgramNode>, outputs: MutableMap<String, JsonObject>, completesStep: Boolean): StepOutcome? {
                            // Only skip provably empty repetition tails. A future branch must still
                            // actually resolve; never predict its outcome to retain completion.
                            val terminalSuffix = BooleanArray(nodes.size + 1)
                            terminalSuffix[nodes.size] = completesStep
                            for (index in nodes.indices.reversed()) terminalSuffix[index] = terminalSuffix[index + 1] && reportOnly(nodes[index])
                            for ((position, node) in nodes.withIndex()) {
                                // Even zero-duration actions and empty repeats must remain cancellable.
                                yield()
                                val atEnd = terminalSuffix[position + 1]
                                when (node) {
                                    is ProgramNode.Branch -> {
                                        val value = resolveProgramValue(node.value, configuration, outputs)
                                        val selected = node.cases.singleOrNull { sameProgramValue(it.equals, value) }
                                            ?: return StepOutcome.Unknown("本次输出未能匹配唯一分支")
                                        executeBlock(selected.program, outputs.toMutableMap(), atEnd)?.let { return it }
                                    }
                                    is ProgramNode.Until -> {
                                        val maximum = untilMaximum(node.maximum, configuration)
                                        var reached = false
                                        var iteration = 0L
                                        while (iteration < maximum) {
                                            yield()
                                            val local = outputs.toMutableMap()
                                            // The final iteration is unknown in advance; no iteration retains completion.
                                            executeBlock(node.program, local, false)?.let { return it }
                                            if (sameProgramValue(resolveProgramValue(node.value, configuration, local), node.equals)) { reached = true; break }
                                            iteration++
                                        }
                                        if (!reached) return StepOutcome.Unknown("循环达到上限 $maximum 次，条件仍未成立")
                                    }
                                    is ProgramNode.Repeat -> {
                                        val count = repeatCount(node.count, configuration)
                                        var index = 0L
                                        while (index < count) {
                                            yield()
                                            executeBlock(node.program, outputs.toMutableMap(), atEnd && index == count - 1)?.let { return it }
                                            index++
                                        }
                                    }
                                    is ProgramNode.Action -> {
                                        val action = node.action
                                        val contract = contracts.single { it.id == action.capability && it.version == action.contractVersion }
                                        val inputs = resolveActionInputs(action, configuration, outputs)
                                        require(contract.inputIssue(inputs) == null)
                                        activeSession?.prepareAction(action, atEnd)
                                        val started = System.nanoTime()
                                        val result = if (action.capability == "control.wait") {
                                            val milliseconds = checkNotNull(duration(inputs)); delay(milliseconds)
                                            ActionResult.Produced(buildJsonObject { put("durationMs", milliseconds) })
                                        } else if (action.capability == TASK_RESULT) {
                                            val line = (inputs.getValue("text") as JsonPrimitive).content
                                            if (line !in results) results += line
                                            ActionResult.Produced(buildJsonObject { })
                                        } else if (action.capability == SELECTION_CONTAINS) {
                                            ActionResult.Produced(buildJsonObject { put("selected", inputs.getValue("value") in inputs.getValue("selection").jsonArray) })
                                        } else checkNotNull(activeSession).perform(action, inputs)
                                        traces?.let { sink ->
                                            val (kind, reason, trace) = when (result) {
                                                ActionResult.Interrupted -> Triple("INTERRUPTED", null, null)
                                                is ActionResult.Unavailable -> Triple("UNAVAILABLE", result.reason, result.trace)
                                                is ActionResult.Produced -> Triple("PRODUCED", null, result.trace)
                                                is ActionResult.Verified -> Triple("VERIFIED", result.evidence, result.trace)
                                            }
                                            // Diagnostics only: a failing sink never changes the step.
                                            runCatching { sink.record(ActionTraceRecord(attempt.ticket, attempt.run.task.id, attempt.run.stepId,
                                                action.id, action.capability, action.contractVersion, kind, reason, trace,
                                                (System.nanoTime() - started) / 1_000_000)) }
                                        }
                                        currentCoroutineContext().ensureActive()
                                        val value = when (result) {
                                            ActionResult.Interrupted -> return StepOutcome.Interrupted
                                            is ActionResult.Unavailable -> return StepOutcome.Unknown(result.reason)
                                            is ActionResult.Produced -> {
                                                check(contract.role != ActionRole.VERIFICATION) { "核对动作未提供核对证据" }
                                                // A result line only records; what was verified before it still stands.
                                                if (action.capability != TASK_RESULT) verification = null
                                                result.outputs
                                            }
                                            is ActionResult.Verified -> {
                                                check(contract.role == ActionRole.VERIFICATION && result.evidence.isNotBlank())
                                                verification = result.evidence; result.outputs
                                            }
                                        }
                                        check(contract.outputIssue(value) == null)
                                        outputs[action.id] = value
                                        requiresVerification = requiresVerification || contract.role == ActionRole.OBSERVATION || contract.sendsDeviceInput
                                    }
                                }
                            }
                            return null
                        }
                        executeBlock(step.nodes(), mutableMapOf(), true)?.let { return@coroutineScope it }
                        if (requiresVerification && verification == null) StepOutcome.Unknown("观察结果尚未核对")
                        else {
                            // The check stays in the evidence for the record; the result lines follow it, after the prefix.
                            val evidence = listOfNotNull(verification ?: "已完成等待步骤".takeIf { results.isEmpty() },
                                results.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = RESULT_PREFIX)).joinToString("；")
                            verification?.let { activeSession?.verifiedStepCompleted(evidence) }
                            StepOutcome.Completed(evidence)
                        }
                    }
                } finally {
                    // Outside coroutineScope: it has drained its children. The native adapter must
                    // additionally wait for its independent foreign quiet evidence before returning.
                    if (claimed) quiet.complete(Unit)
                }
            }
            override suspend fun revoke(): InputStopObservation {
                val running = synchronized(lock) {
                    revoked = true
                    owner.also { if (it == null) quiet.complete(Unit) }
                }
                running?.cancel(); quiet.await()
                // A drained coroutine is insufficient for platform input. Read-only foreign uses
                // finish inside perform(); input sessions retain and settle their own channel.
                return if (usesInput) session?.revoke() ?: InputStopObservation.Quiet(StepOutcome.Interrupted)
                    else InputStopObservation.Quiet(StepOutcome.Interrupted)
            }
        }
    }
    override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle): Boolean =
        runCatching { program(attempt, bundle).first.actionDefinitions().none { action ->
            contracts.single { it.id == action.capability && it.version == action.contractVersion }.sendsDeviceInput
        } }.getOrDefault(false)
    override fun canReenterAfterQuiet(attempt: StepAttempt, bundle: TaskBundle): Boolean =
        runCatching { program(attempt, bundle); true }.getOrDefault(false)
}
