package io.github.yysassist.core.execution

import io.github.yysassist.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ExecutorRecoveryTest {
    private val context = ExecutionContext("fixture", Principal.Anonymous)
    private fun running(): TaskExecutor = TaskExecutor(context).also {
        it.enqueue("one", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "one"), context, "v1",
            listOf(TaskSnapshot("one", "One", 1, listOf("a", "b")))))
    }
    private fun apply(executor: TaskExecutor, command: ExecutorCommand): PreparedExecutorTransition =
        executor.prepare(command).also { executor.commit(it) }
    @Test fun safeReentryKeepsCompletedStepsAndChangesTheAttemptIdentity() {
        val original = running(); original.stepFinished(original.state.value.activeTicket!!, StepOutcome.Completed("first done"))
        val restored = TaskExecutor.fromEncodedState(original.encodeState())
        val old = restored.state.value.activeTicket!!
        val next = apply(restored, ExecutorCommand.RecoverNoInput(old)).effects.single() as ExecutorEffect.ExecuteStep
        assertEquals("b", next.attempt.run.stepId)
        assertNotEquals(old, next.attempt.ticket)
        assertTrue(restored.permitsInput(next.attempt.ticket))
    }
    @Test fun recoveryCannotReleaseAnExistingManualHold() {
        val executor = running(); val ticket = executor.state.value.activeTicket!!; executor.pause()
        val recovered = TaskExecutor.fromEncodedState(executor.encodeState())
        assertTrue(apply(recovered, ExecutorCommand.RecoverNoInput(ticket)).effects.isEmpty())
        assertTrue(recovered.state.value.manualHold)
        assertEquals(ExecutorPhase.PAUSED, recovered.state.value.phase)
    }
    @Test fun missingInputProofPersistsUncertaintyAndCannotBeRemovedOrRestarted() {
        val executor = running(); val ticket = executor.state.value.activeTicket!!
        apply(executor, ExecutorCommand.InputUnconfirmed(ticket, "old input unconfirmed"))
        val recovered = TaskExecutor.fromEncodedState(executor.encodeState())
        assertEquals(ticket, recovered.state.value.unresolved.single().attempt.ticket)
        apply(recovered, ExecutorCommand.Remove(ticket.runId))
        assertNotNull(recovered.state.value.current)
        assertTrue(apply(recovered, ExecutorCommand.Resume(ResumeMode.RESTART_CURRENT_TASK)).effects.isEmpty())
        assertFalse(recovered.permitsInput(ticket))
    }
    @Test fun resultAndQuiescenceAreOnePreparedTransitionDuringPause() {
        val executor = running(); val ticket = executor.state.value.activeTicket!!; executor.pause()
        apply(executor, ExecutorCommand.StepSettled(ticket, StepOutcome.Completed("first done"), StepOutcome.Interrupted))
        assertEquals("b", executor.state.value.current!!.stepId)
        assertEquals(ExecutorPhase.PAUSED, executor.state.value.phase)
        assertEquals(executor.state.value, TaskExecutor.fromEncodedState(executor.encodeState()).state.value)
    }
    @Test fun staleRowControlsCannotPauseStopOrRestartAnotherRun() {
        val executor = running(); val oldId = executor.state.value.current!!.runId
        executor.enqueue("next", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "next"), context, "v1",
            listOf(TaskSnapshot("next", "Next", 1, listOf("a", "b")))))
        repeat(2) { executor.stepFinished(executor.state.value.activeTicket!!, StepOutcome.Completed("done")) }
        val before = executor.state.value
        listOf(ExecutorCommand.PauseCurrent(oldId), ExecutorCommand.StopCurrent(oldId),
            ExecutorCommand.ResumeCurrent(oldId, ResumeMode.RESTART_CURRENT_TASK)).forEach { command ->
            assertTrue(apply(executor, command).effects.isEmpty())
            assertEquals(before, executor.state.value)
        }
    }
}
