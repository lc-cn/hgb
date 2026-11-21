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
        val fields = definition(attempt.run.request, attempt.run.task, bundle).first.fields
        /** "收取结界奖励 ≠ 开": the setting and the value the condition wanted, as the user knows them. */
        fun skipReason(condition: StepCondition): String {
            val field = fields.singleOrNull { it.id == condition.field }
            val wanted = condition.contains ?: condition.equals?.contentOrNull
            val label = field?.options?.singleOrNull { it.value == wanted }?.label
                ?: when (condition.equals?.booleanOrNull) { true -> "开"; false -> "关"; null -> wanted }
            if (field != null && (configuration[field.id] == null || configuration[field.id] == JsonNull) && field.widget != FieldWidget.SWITCH && field.widget != FieldWidget.MULTI_CHOICE)
                return "${field.label} 未设置"
            return "${field?.label ?: condition.field} ${if (condition.contains != null) (if (condition.not) "含" else "不含") else (if (condition.not) "=" else "≠")} $label"
        }
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
                        // A step whose run condition does not hold is skipped before it touches the screen.
                        step.runWhen?.takeIf { !it.holds(configuration, fields) }?.let { condition ->
                            return@coroutineScope StepOutcome.Completed("已跳过（${skipReason(condition)}）")
                        }
                        val activeSession = actions?.open(attempt, bundle).also { session = it }
                        var verification: String? = null
                        var requiresVerification = false
                        val results = mutableListOf<String>()
                        fun emptyRepeat(node: ProgramNode): Boolean = node is ProgramNode.Repeat &&
                            (repeatCount(node.count, configuration) == 0L || node.program.all(::emptyRepeat))
                        // A result line only reports; the check before it still settles the step.
                        fun reportOnly(node: ProgramNode): Boolean = emptyRepeat(node) ||
                            node is ProgramNode.Action && node.action.capability == TASK_RESULT
                        // How a block ended early: the step's outcome, or an exit to the loop named.
                        class Halt(val outcome: StepOutcome?, val loop: String? = null, val index: Int? = null)
                        fun loopOutputs(halt: Halt?) = buildJsonObject { put("exited", halt?.loop != null); put("exitIndex", halt?.index ?: -1) }
                        /**
                         * Runs one action and keeps its outputs; a Halt when the step must stop. [extraStates]: interruption pages a
                         * guarded wait also looks for, added to its own.
                         */
                        suspend fun perform(action: ActionDefinition, atEnd: Boolean, outputs: MutableMap<String, JsonObject>,
                            extraStates: List<JsonElement> = emptyList()): Halt? {
                            val contract = contracts.single { it.id == action.capability && it.version == action.contractVersion }
                            val inputs = resolveActionInputs(action, configuration, outputs).let { own ->
                                if (extraStates.isEmpty()) own else JsonObject(own + ("states" to JsonArray(own.getValue("states").jsonArray + extraStates)))
                            }
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
                                ActionResult.Interrupted -> return Halt(StepOutcome.Interrupted)
                                is ActionResult.Unavailable -> return Halt(StepOutcome.Unknown(result.reason))
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
                            return null
                        }
                        suspend fun executeBlock(nodes: List<ProgramNode>, outputs: MutableMap<String, JsonObject>, completesStep: Boolean): Halt? {
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
                                            ?: return Halt(StepOutcome.Unknown("本次输出未能匹配唯一分支"))
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
                                            val halt = executeBlock(node.program, local, false)
                                            if (halt != null && (halt.loop == null || halt.loop != node.id)) return halt
                                            if (halt != null || node.conditions.any { sameProgramValue(resolveProgramValue(it.value, configuration, local), it.equals) }) {
                                                reached = true; node.id?.let { outputs[it] = loopOutputs(halt) }; break
                                            }
                                            iteration++
                                        }
                                        if (!reached) return Halt(StepOutcome.Unknown("循环达到上限 $maximum 次，条件仍未成立"))
                                    }
                                    is ProgramNode.Repeat -> {
                                        val count = repeatCount(node.count, configuration)
                                        var index = 0L
                                        var ended: Halt? = null
                                        while (index < count) {
                                            yield()
                                            val halt = executeBlock(node.program, outputs.toMutableMap(), atEnd && index == count - 1 && node.id == null)
                                            if (halt != null && (halt.loop == null || halt.loop != node.id)) return halt
                                            if (halt != null) { ended = halt; break }
                                            index++
                                        }
                                        node.id?.let { outputs[it] = loopOutputs(ended) }
                                    }
                                    is ProgramNode.Block -> {
                                        // A block an exit can cut short never holds the step's last action: the check after it does.
                                        val halt = executeBlock(node.program, outputs.toMutableMap(), false)
                                        if (halt != null && halt.loop != node.id) return halt
                                        outputs[node.id] = loopOutputs(halt)
                                    }
                                    is ProgramNode.Exit -> return Halt(null, node.loop, node.index)
                                    is ProgramNode.UseSnippet, is ProgramNode.ForEach -> error("片段或逐项未展开")
                                    is ProgramNode.Action -> perform(node.action, atEnd, outputs)?.let { return it }
                                    is ProgramNode.Guarded -> {
                                        // Wait for the page or any interruption; clear an interruption and wait again.
                                        val cleared = mutableMapOf<Int, Int>()
                                        while (true) {
                                            yield()
                                            val pages = node.guards.map { it.state }
                                            perform(node.wait, atEnd, outputs, pages)?.let { return it }
                                            val state = (outputs[node.wait.id]?.get("state") as? JsonPrimitive)?.contentOrNull
                                            val index = node.guards.indexOfFirst { (it.state as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.content } == state }
                                            if (index < 0) break
                                            val times = (cleared[index] ?: 0) + 1
                                            if (times > node.guards[index].maximum)
                                                return Halt(StepOutcome.Unknown("干扰页 ${node.guards[index].name ?: state?.removePrefix(INTERRUPTION_STATE)} 处理了 ${times - 1} 次仍在"))
                                            cleared[index] = times
                                            outputs.remove(node.wait.id)
                                            executeBlock(node.guards[index].program, mutableMapOf(), false)?.let { return it }
                                        }
                                    }
                                }
                            }
                            return null
                        }
                        executeBlock(step.nodes(), mutableMapOf(), true)?.let { halt -> return@coroutineScope checkNotNull(halt.outcome) { "退出了不存在的循环" } }
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
