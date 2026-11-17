package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import kotlinx.serialization.json.JsonObject

/** APK-owned local, repeatable observations. Device input needs its independent owner protocol. */
interface ReadOnlyActionProvider {
    val contracts: List<CapabilityContract>
    /** Pure construction: foreign uses must start and finish inside perform(), including cancellation. */
    fun open(attempt: StepAttempt, bundle: TaskBundle): ReadOnlyActionSession
}
fun interface ReadOnlyActionSession {
    suspend fun perform(action: ActionDefinition, inputs: JsonObject): ReadOnlyActionResult
}
sealed interface ReadOnlyActionResult {
    data object Interrupted : ReadOnlyActionResult
    data class Produced(val outputs: JsonObject) : ReadOnlyActionResult
    data class Verified(val outputs: JsonObject, val evidence: String) : ReadOnlyActionResult
    data class Unavailable(val reason: String) : ReadOnlyActionResult
}
