package io.github.yysassist.core.execution

import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import org.junit.Assert.*
import org.junit.Test

/** Persisted executor state must not grow with the number of finished runs (it is rewritten per transition). */
class ExecutorStateGrowthTest {
    private val context = ExecutionContext("fixture", Principal.Anonymous)
    private fun request(i: Int) = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "task"), context, "bundle-v1",
        listOf(TaskSnapshot("task", "任务", 1, listOf("first", "second"), "{}")))
    private fun drive(executor: TaskExecutor, effects: List<ExecutorEffect>) {
        var pending = effects
        while (true) {
            val step = pending.filterIsInstance<ExecutorEffect.ExecuteStep>().firstOrNull() ?: return
            pending = executor.commit(executor.prepare(ExecutorCommand.StepSettled(step.attempt.ticket, StepOutcome.Completed("ok"), StepOutcome.Interrupted)))
        }
    }
    @Test fun stateSizeIsBoundedByRecentHistoryNotByRunCount() {
        val executor = TaskExecutor(context) { 1234L }
        val sizes = mutableMapOf<Int, Int>()
        for (i in 1..600) {
            drive(executor, executor.commit(executor.prepare(ExecutorCommand.Enqueue("command-$i", request(i)))))
            if (i in setOf(1, 10, 100, 300, 600)) sizes[i] = executor.encodeState().length
        }
        println("executor state bytes by finished runs: $sizes")
        assertTrue(sizes.toString(), sizes.getValue(600) < sizes.getValue(100) * 2)
    }

    @Test fun compactionKeepsUnknownRunsRecentReceiptsAndIgnoresLateResultsOfPrunedRuns() {
        val executor = TaskExecutor(context) { 1234L }
        val first = executor.commit(executor.prepare(ExecutorCommand.Enqueue("first", request(0)))).filterIsInstance<ExecutorEffect.ExecuteStep>().single().attempt
        executor.commit(executor.prepare(ExecutorCommand.StepSettled(first.ticket, StepOutcome.Unknown("待核对"), StepOutcome.Interrupted)))
        assertEquals(1, executor.state.value.unresolved.size)
        executor.commit(executor.prepare(ExecutorCommand.ResolveUnknown(first.ticket, StepOutcome.Completed("核对完成"))))
        val old = executor.state.value
        for (i in 1..(TaskExecutor.RECENT_RUNS + 5)) drive(executor, executor.commit(executor.prepare(ExecutorCommand.Enqueue("c$i", request(i)))))
        // A late result for the long-finished first run is ignored, not an error, and changes nothing live.
        val before = executor.state.value
        executor.commit(executor.prepare(ExecutorCommand.StepFinished(first.ticket, StepOutcome.Completed("迟到"))))
        assertEquals(before.current, executor.state.value.current); assertTrue(executor.state.value.unresolved.isEmpty())
        // Fact ids keep increasing across compaction.
        val ids = executor.state.value.facts.map { it.id }
        assertEquals(ids.sorted(), ids); assertTrue(ids.first() > (old.facts.maxOfOrNull { it.id } ?: 0) - TaskExecutor.FACT_LIMIT)
        // Receipts are bounded; a recent duplicate command is still recognized as the same command.
        val again = executor.enqueue("c${TaskExecutor.RECENT_RUNS + 5}", request(0))
        assertTrue(again.effects.isEmpty())
        val restored = TaskExecutor.fromEncodedState(executor.encodeState()) { 1234L }
        assertEquals(executor.state.value, restored.state.value)
    }
    @Test fun anUnresolvedRunSurvivesManyLaterRuns() {
        val executor = TaskExecutor(context) { 1234L }
        val first = executor.commit(executor.prepare(ExecutorCommand.Enqueue("first", request(0)))).filterIsInstance<ExecutorEffect.ExecuteStep>().single().attempt
        executor.commit(executor.prepare(ExecutorCommand.StepSettled(first.ticket, StepOutcome.Unknown("待核对"), StepOutcome.Interrupted)))
        // Even with the queue blocked by the unknown, enqueued runs stay; the unknown attempt must remain resolvable.
        for (i in 1..30) executor.commit(executor.prepare(ExecutorCommand.Enqueue("c$i", request(i))))
        assertEquals(first.ticket, executor.state.value.unresolved.single().attempt.ticket)
        executor.commit(executor.prepare(ExecutorCommand.ResolveUnknown(first.ticket, StepOutcome.Completed("核对完成"))))
        assertTrue(executor.state.value.unresolved.isEmpty())
    }
}
