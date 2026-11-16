package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import kotlinx.coroutines.delay
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

/** First real APK action. No Android bridge, network or account state is reachable from this runner. */
class BuiltInStepWorkFactory : StepWorkFactory {
    private fun duration(inputs: JsonObject): Long? {
        val number = (inputs["durationMs"] as? JsonPrimitive)?.decimalOrNull() ?: return null
        return try { number.longValueExact().takeIf { it >= 0 } }
        catch (_: ArithmeticException) { null }
    }
    override val contracts = listOf(CapabilityContract("control.wait", 1,
        inputTypes = mapOf("durationMs" to ActionValueType.NUMBER),
        outputTypes = mapOf("durationMs" to ActionValueType.NUMBER)) { inputs ->
        if (inputs.keys != setOf("durationMs") || duration(inputs) == null) "等待时长需为非负整数毫秒" else null
    })
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
        request.tasks.forEach { task -> definition(request, task, bundle) }
        require(request.tasks.isNotEmpty())
        true
    }.getOrDefault(false)
    override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork {
        val (step, configuration) = program(attempt, bundle)
        return object : StepWork {
            override suspend fun execute(): StepOutcome {
                val outputs = mutableMapOf<String, JsonObject>()
                step.actions.forEach { action ->
                    val contract = contracts.single { it.id == action.capability && it.version == action.contractVersion }
                    val inputs = resolveActionInputs(action, configuration, outputs)
                    require(contract.inputIssue(inputs) == null)
                    val milliseconds = checkNotNull(duration(inputs))
                    delay(milliseconds)
                    val value = buildJsonObject { put("durationMs", milliseconds) }
                    check(contract.outputIssue(value) == null)
                    outputs[action.id] = value
                }
                return StepOutcome.Completed("已完成等待步骤")
            }
            // This proof comes from the runner's absence of any input capability, not Job cancellation.
            override suspend fun revoke() = InputStopObservation.Quiet(StepOutcome.Interrupted)
        }
    }
    override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle): Boolean =
        runCatching { program(attempt, bundle); true }.getOrDefault(false)
}
