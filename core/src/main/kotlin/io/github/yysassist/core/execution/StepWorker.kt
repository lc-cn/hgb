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
    /** True only for APK-owned programs whose start has no screen-observation prerequisite. */
    fun canStartWithoutObservation(request: ExecutionRequest, bundle: TaskBundle): Boolean = false
}

/** One ordered interpreter for built-in wait and explicitly registered APK-owned read-only actions. */
class BuiltInStepWorkFactory(private val observations: ReadOnlyActionProvider? = null) : StepWorkFactory {
    private fun duration(inputs: JsonObject): Long? {
        val number = (inputs["durationMs"] as? JsonPrimitive)?.decimalOrNull() ?: return null
        return try { number.longValueExact().takeIf { it >= 0 } }
        catch (_: ArithmeticException) { null }
    }
    override val contracts = listOf(CapabilityContract("control.wait", 1,
        inputTypes = mapOf("durationMs" to ActionValueType.NUMBER),
        outputTypes = mapOf("durationMs" to ActionValueType.NUMBER)) { inputs ->
        if (inputs.keys != setOf("durationMs") || duration(inputs) == null) "等待时长需为非负整数毫秒" else null
    }) + observations?.contracts.orEmpty()
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
            require(content.steps.flatMap { it.actions }.all { it.capability == "control.wait" })
        }
        require(request.tasks.isNotEmpty())
        true
    }.getOrDefault(false)
    override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork {
        val (step, configuration) = program(attempt, bundle)
        return object : StepWork {
            private val lock = Any()
            private var revoked = false
            private var owner: Job? = null
            private val quiet = CompletableDeferred<Unit>()
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
                        val session = observations?.open(attempt, bundle)
                        val outputs = mutableMapOf<String, JsonObject>()
                        var verification: String? = null
                        var observed = false
                        step.actions.forEach { action ->
                            currentCoroutineContext().ensureActive()
                            val contract = contracts.single { it.id == action.capability && it.version == action.contractVersion }
                            val inputs = resolveActionInputs(action, configuration, outputs)
                            require(contract.inputIssue(inputs) == null)
                            val result = if (action.capability == "control.wait") {
                                val milliseconds = checkNotNull(duration(inputs)); delay(milliseconds)
                                ReadOnlyActionResult.Produced(buildJsonObject { put("durationMs", milliseconds) })
                            } else checkNotNull(session).perform(action, inputs)
                            currentCoroutineContext().ensureActive()
                            val value = when (result) {
                                ReadOnlyActionResult.Interrupted -> return@coroutineScope StepOutcome.Interrupted
                                is ReadOnlyActionResult.Unavailable -> return@coroutineScope StepOutcome.Unknown(result.reason)
                                is ReadOnlyActionResult.Produced -> {
                                    check(contract.role != ActionRole.VERIFICATION) { "核对动作未提供核对证据" }
                                    verification = null; result.outputs
                                }
                                is ReadOnlyActionResult.Verified -> {
                                    check(contract.role == ActionRole.VERIFICATION && result.evidence.isNotBlank())
                                    verification = result.evidence; result.outputs
                                }
                            }
                            check(contract.outputIssue(value) == null)
                            outputs[action.id] = value
                            observed = observed || contract.role == ActionRole.OBSERVATION
                        }
                        if (observed && verification == null) StepOutcome.Unknown("观察结果尚未核对")
                        else StepOutcome.Completed(verification ?: "已完成等待步骤")
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
                // No device input is registered here; native-use quiet is proved by its adapter.
                return InputStopObservation.Quiet(StepOutcome.Interrupted)
            }
        }
    }
    override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle): Boolean =
        runCatching { program(attempt, bundle); true }.getOrDefault(false)
}
