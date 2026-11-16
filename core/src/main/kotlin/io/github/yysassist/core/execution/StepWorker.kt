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
}

/** First real APK action. No Android bridge, network or account state is reachable from this runner. */
class BuiltInStepWorkFactory : StepWorkFactory {
    private fun duration(inputs: JsonObject): Long? = (inputs["durationMs"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
    override val contracts = listOf(CapabilityContract("control.wait", 1) { inputs ->
        if (inputs.keys != setOf("durationMs") || duration(inputs) == null) "等待动作需要非负整数 durationMs" else null
    })
    private fun actions(attempt: StepAttempt, bundle: TaskBundle): List<ActionDefinition> {
        require(bundle.version == attempt.run.request.packageVersion)
        val task = bundle.tasks.single { it.id == attempt.run.task.id && it.targetId == attempt.run.request.context.targetId }
        require(task.steps.map { it.id } == attempt.run.task.stepIds)
        return task.steps.single { it.id == attempt.run.stepId }.actions.onEach { action ->
            val contract = contracts.single { it.id == action.capability && it.version == action.contractVersion }
            require(contract.validateInputs(action.inputs) == null)
        }
    }
    override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork {
        val durations = actions(attempt, bundle).map { duration(it.inputs)!! }
        return object : StepWork {
            override suspend fun execute(): StepOutcome {
                durations.forEach { delay(it) }
                return StepOutcome.Completed("已完成等待步骤")
            }
            // This proof comes from the runner's absence of any input capability, not Job cancellation.
            override suspend fun revoke() = InputStopObservation.Quiet(StepOutcome.Interrupted)
        }
    }
    override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle): Boolean =
        runCatching { actions(attempt, bundle); true }.getOrDefault(false)
}
