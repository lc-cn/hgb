package io.github.yysassist.core.execution

import io.github.yysassist.core.model.ExecutionContext
import kotlinx.serialization.Serializable

/** Content snapshots contain data, never their own pause/resume controller. */
@Serializable
/** CLOUD_JOB is one task run with settings given by the cloud, which replace the locally saved ones. */
enum class SubmissionKind { TASK, PLAN, CLOUD_JOB }
@Serializable
data class SubmissionRef(val kind: SubmissionKind, val id: String)
@Serializable
data class TaskSnapshot(
    val id: String,
    val name: String,
    val priority: Long,
    val stepIds: List<String>,
    val configurationJson: String = "{}",
)
@Serializable
data class ExecutionRequest(
    val ref: SubmissionRef,
    val context: ExecutionContext,
    val packageVersion: String,
    val tasks: List<TaskSnapshot>,
    /** Display name of a plan round; a single task shows its own name. */
    val label: String? = null,
)
@Serializable
data class RunProgress(
    val runId: Long,
    val sequence: Long,
    val request: ExecutionRequest,
    val priority: Long,
    val taskIndex: Int = 0,
    val stepIndex: Int = 0,
    val failedTaskIds: List<String> = emptyList(),
) {
    val task: TaskSnapshot get() = request.tasks[taskIndex]
    val stepId: String get() = task.stepIds[stepIndex]
}

/** Ticket is checked by the input owner, not just by the worker coroutine. */
@Serializable
data class AttemptTicket(val runId: Long, val generation: Long)
@Serializable
data class StepAttempt(val ticket: AttemptTicket, val run: RunProgress)
@Serializable
enum class ResumeMode { CONTINUE, RESTART_CURRENT_TASK }
@Serializable
enum class ExecutorPhase { IDLE, RUNNING, PAUSING, PAUSED, REMOVING, SWITCHING, BLOCKED_UNKNOWN }
@Serializable
enum class RunResult { COMPLETED, PARTIAL_FAILURE, FAILED, CANCELLED }

@Serializable
sealed interface StepOutcome {
    @Serializable
    data class Completed(val evidence: String) : StepOutcome
    @Serializable
    data class Failed(val reason: String) : StepOutcome
    @Serializable
    data class Unknown(val reason: String) : StepOutcome
    /** Input owner proves quiet and no unresolved side effect; re-enter the step by observing. */
    @Serializable
    data object Interrupted : StepOutcome
}
@Serializable
data class UnresolvedResult(val attempt: StepAttempt, val reason: String)
@Serializable
data class ExecutionFact(
    val type: String,
    val runId: Long,
    val taskId: String? = null,
    val detail: String = "",
    val taskName: String,
    val context: ExecutionContext,
    val packageVersion: String,
    val occurredAtEpochMillis: Long,
    /** Monotonic within one executor; 0 for facts stored before ids existed. Used to append history once. */
    val id: Long = 0,
    /** The step the run was on, when the fact concerns one. */
    val stepId: String? = null,
)
@Serializable
data class ExecutorSnapshot(
    val context: ExecutionContext?,
    val phase: ExecutorPhase,
    val manualHold: Boolean,
    val current: RunProgress?,
    val waiting: List<RunProgress>,
    val activeTicket: AttemptTicket?,
    val unresolved: List<UnresolvedResult>,
    val facts: List<ExecutionFact>,
    val deviceActions: List<DeviceActionRecord> = emptyList(),
)
@Serializable
sealed interface ExecutorEffect {
    @Serializable
    data class ExecuteStep(val attempt: StepAttempt) : ExecutorEffect
    @Serializable
    data class RevokeInput(val ticket: AttemptTicket) : ExecutorEffect
}
@Serializable
sealed interface EnqueueResult {
    @Serializable
    data class Accepted(val runId: Long) : EnqueueResult
    @Serializable
    data class Duplicate(val runId: Long) : EnqueueResult
    @Serializable
    data class Rejected(val reason: String) : EnqueueResult
}
@Serializable
data class EnqueueResponse(val result: EnqueueResult, val effects: List<ExecutorEffect>)

@Serializable
sealed interface ContextChangeResult {
    @Serializable
    data object Accepted : ContextChangeResult
    @Serializable
    data object InputNotQuiet : ContextChangeResult
    @Serializable
    data object UnresolvedResult : ContextChangeResult
    /** Cross-target handoff policy is deliberately not inferred from same-app account switching. */
    @Serializable
    data object CrossTargetWorkPending : ContextChangeResult
}
