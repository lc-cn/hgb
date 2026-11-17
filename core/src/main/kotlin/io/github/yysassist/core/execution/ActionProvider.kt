package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import kotlinx.serialization.json.JsonObject

/** APK-owned actions. Queue and control ownership remain in the one executor. */
interface ActionProvider {
    val contracts: List<CapabilityContract>
    /** Pure construction: foreign uses must start and finish inside perform(), including cancellation. */
    fun open(attempt: StepAttempt, bundle: TaskBundle): ActionSession
}
fun interface ActionSession {
    suspend fun perform(action: ActionDefinition, inputs: JsonObject): ActionResult
    /** Input programs must implement independent quiet and retained business evidence. */
    suspend fun revoke(): InputStopObservation = InputStopObservation.Unconfirmed("动作提供者未实现输入交接")
}
sealed interface ActionResult {
    data object Interrupted : ActionResult
    data class Produced(val outputs: JsonObject) : ActionResult
    data class Verified(val outputs: JsonObject, val evidence: String) : ActionResult
    data class Unavailable(val reason: String) : ActionResult
}
