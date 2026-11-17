package io.github.yysassist.core.execution

import io.github.yysassist.core.model.ExecutionContext

enum class NoInputRecoveryCause { PROCESS_RESTART, STEP_INTERRUPTION }

/** Commands enter the same reducer; workers never acquire their own scheduling state. */
sealed interface ExecutorCommand {
    data class Enqueue(val commandId: String, val request: ExecutionRequest) : ExecutorCommand
    data object Pause : ExecutorCommand
    data object Stop : ExecutorCommand
    data class PauseCurrent(val runId: Long) : ExecutorCommand
    data class StopCurrent(val runId: Long) : ExecutorCommand
    data class ResumeCurrent(val runId: Long, val mode: ResumeMode) : ExecutorCommand
    data class Remove(val runId: Long) : ExecutorCommand
    data class Resume(val mode: ResumeMode) : ExecutorCommand
    data class StepFinished(val ticket: AttemptTicket, val outcome: StepOutcome) : ExecutorCommand
    data class InputQuiescent(val ticket: AttemptTicket, val outcome: StepOutcome) : ExecutorCommand
    data class StepSettled(val ticket: AttemptTicket, val result: StepOutcome, val quietOutcome: StepOutcome) : ExecutorCommand
    /** Supplied only when the APK runner proves the old step cannot have issued device input. */
    data class RecoverNoInput(val ticket: AttemptTicket, val cause: NoInputRecoveryCause = NoInputRecoveryCause.PROCESS_RESTART) : ExecutorCommand
    data class InputUnconfirmed(val ticket: AttemptTicket, val reason: String) : ExecutorCommand
    data class ResolveUnknown(val ticket: AttemptTicket, val outcome: StepOutcome) : ExecutorCommand
    data class VerifyContext(val context: ExecutionContext) : ExecutorCommand
    data object ReadinessChanged : ExecutorCommand
}
sealed interface ExecutorReply {
    data object Applied : ExecutorReply
    data class Enqueued(val result: EnqueueResult) : ExecutorReply
    data class ContextChanged(val result: ContextChangeResult) : ExecutorReply
}

/** No work is dispatched while preparing. The host persists this state before committing it. */
class PreparedExecutorTransition internal constructor(
    internal val owner: TaskExecutor,
    internal val baseRevision: Long,
    internal val draft: TaskExecutor,
    val baseEncodedState: String,
    val admittedRunId: Long?,
    val reply: ExecutorReply,
    val effects: List<ExecutorEffect>,
) {
    val snapshot: ExecutorSnapshot get() = draft.state.value
    /** Admission may close before persistence; new input may only open after commit. */
    fun permitsInput(ticket: AttemptTicket): Boolean = draft.permitsInput(ticket)
    fun encodeState(): String = draft.encodeState()
}
