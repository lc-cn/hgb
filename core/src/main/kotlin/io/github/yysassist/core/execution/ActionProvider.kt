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
    /** Actual selected path/iteration, supplied before perform so cancellation cannot lose proof. */
    fun prepareAction(action: ActionDefinition, completesStep: Boolean) {}
    /** The interpreter has reached the actual path end with final verification evidence. */
    fun verifiedStepCompleted(evidence: String) {}
    /** Input programs must implement independent quiet and retained business evidence. */
    suspend fun revoke(): InputStopObservation = InputStopObservation.Unconfirmed("动作提供者未实现输入交接")
}
/**
 * trace is optional structured evidence about how a result was reached (regions, scores, resource
 * digests). It must not contain pixels, recognized private text or account values. The format is
 * defined by the producing capability; persistence is not yet wired and nothing may depend on it.
 */
sealed interface ActionResult {
    data object Interrupted : ActionResult
    data class Produced(val outputs: JsonObject, val trace: JsonObject? = null) : ActionResult
    data class Verified(val outputs: JsonObject, val evidence: String, val trace: JsonObject? = null) : ActionResult
    data class Unavailable(val reason: String, val trace: JsonObject? = null) : ActionResult
}

/**
 * Diagnostic record of one performed action: its result kind and the capability's structured trace.
 * Never authoritative for execution or recovery and may be lost on a crash. No pixels or account values.
 */
data class ActionTraceRecord(
    val ticket: AttemptTicket, val taskId: String, val stepId: String,
    val actionId: String, val capability: String, val contractVersion: Int,
    val result: String, val reason: String?, val trace: JsonObject?, val elapsedMillis: Long,
)
/** Must return quickly and never throw into the interpreter; persistence happens elsewhere. */
fun interface ActionTraceSink { fun record(record: ActionTraceRecord) }
