package io.github.yysassist.core.execution

import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExecutorPersistenceTest {
    private val context = ExecutionContext("fixture", Principal.Account("account-a"))
    private fun engine() = TaskExecutor(context) { 1234L }
    private fun request(id: String = "daily", priority: Long = 10, steps: List<String> = listOf("first", "second")) = ExecutionRequest(
        SubmissionRef(SubmissionKind.TASK, id), context, "bundle-v1",
        listOf(TaskSnapshot(id, "测试任务 $id", priority, steps, "{\"amount\":9007199254740993}")),
    )
    private fun enqueue(executor: TaskExecutor, id: String = "daily", priority: Long = 10): StepAttempt {
        val response = executor.enqueue("command-$id", request(id, priority))
        assertTrue(response.result is EnqueueResult.Accepted)
        return (response.effects.single() as ExecutorEffect.ExecuteStep).attempt
    }
    private fun restore(executor: TaskExecutor): TaskExecutor = TaskExecutor.fromEncodedState(executor.encodeState()) { 1234L }.also {
        assertEquals(executor.state.value, it.state.value)
    }

    @Test fun preparingCannotChangeTheObservableExecutorOrIssueAnotherAttempt() {
        val executor = engine(); val initial = executor.state.value
        val draft = executor.prepare(ExecutorCommand.Enqueue("command", request()))
        assertEquals(initial, executor.state.value)
        assertEquals(context, draft.snapshot.context)
        assertEquals(ExecutorPhase.RUNNING, draft.snapshot.phase)
        assertEquals(1, draft.effects.size)
        val effects = executor.commit(draft)
        assertEquals(draft.snapshot, executor.state.value)
        assertEquals(draft.effects, effects)
        assertEquals(draft.encodeState(), executor.encodeState())
        assertThrows(IllegalStateException::class.java) { executor.commit(draft) }
    }
    @Test fun aFailedStoreCanDiscardAPreparedCommandWithoutPublishingItsPause() {
        val executor = engine(); enqueue(executor)
        val before = executor.encodeState()
        val draft = executor.prepare(ExecutorCommand.Pause)
        assertEquals(ExecutorPhase.PAUSING, draft.snapshot.phase)
        assertEquals(before, executor.encodeState())
        val retry = executor.prepare(ExecutorCommand.Pause)
        assertEquals(draft.encodeState(), retry.encodeState())
        executor.commit(retry)
        assertEquals(ExecutorPhase.PAUSING, executor.state.value.phase)
    }
    @Test fun aForeignOrStalePreparedCommandCannotOverwriteNewerState() {
        val executor = engine(); val first = executor.prepare(ExecutorCommand.Enqueue("one", request()))
        val second = executor.prepare(ExecutorCommand.Pause)
        assertThrows(IllegalStateException::class.java) { engine().commit(first) }
        executor.commit(second)
        assertThrows(IllegalStateException::class.java) { executor.commit(first) }
        assertTrue(executor.state.value.manualHold)
        assertNull(executor.state.value.current)
    }
    @Test fun activeAndQueuedProgressAndCommandReceiptsRoundTripWithoutGeneratingEffects() {
        val executor = engine(); val first = enqueue(executor)
        val waiting = executor.enqueue("waiting", request("next", 5))
        assertTrue(waiting.effects.isEmpty())
        val restored = restore(executor)
        assertEquals(listOf("next"), restored.state.value.waiting.map { it.task.id })
        assertEquals(first.ticket, restored.state.value.activeTicket)
        assertEquals(EnqueueResult.Accepted(first.ticket.runId), restored.enqueue("command-daily", request()).result)
        assertTrue(restored.enqueue("command-daily", request()).effects.isEmpty())
        val effect = restored.stepFinished(first.ticket, StepOutcome.Completed("已观察到完成")).single() as ExecutorEffect.ExecuteStep
        assertEquals("second", effect.attempt.run.stepId)
        assertTrue(effect.attempt.ticket.generation > first.ticket.generation)
        assertEquals("{\"amount\":9007199254740993}", effect.attempt.run.task.configurationJson)
    }
    @Test fun pauseHandoffAndObservedOutcomeRemainDistinctUntilQuietIsAcknowledged() {
        val executor = engine(); val attempt = enqueue(executor)
        executor.pause()
        executor.stepFinished(attempt.ticket, StepOutcome.Completed("已完成第一步"))
        val restored = restore(executor)
        assertEquals(ExecutorPhase.PAUSING, restored.state.value.phase)
        assertTrue(restored.resume(ResumeMode.CONTINUE).isEmpty())
        assertFalse(restored.permitsInput(attempt.ticket))
        assertTrue(restored.inputQuiescent(attempt.ticket, StepOutcome.Interrupted).isEmpty())
        assertEquals("second", restored.state.value.current!!.stepId)
        val next = restored.resume(ResumeMode.RESTART_CURRENT_TASK).single() as ExecutorEffect.ExecuteStep
        assertEquals("first", next.attempt.run.stepId)
    }
    @Test fun unknownEvidenceAndAnEndRequestSurviveTheRoundTripAndBlockRestart() {
        val executor = engine(); val attempt = enqueue(executor)
        executor.stepFinished(attempt.ticket, StepOutcome.Unknown("领取结果未确认"))
        executor.remove(attempt.ticket.runId)
        val restored = restore(executor)
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, restored.state.value.phase)
        assertTrue(restored.resume(ResumeMode.RESTART_CURRENT_TASK).isEmpty())
        assertEquals("领取结果未确认", restored.state.value.unresolved.single().reason)
        restored.resolveUnknown(attempt.ticket, StepOutcome.Completed("已核对到账"))
        assertNull(restored.state.value.current)
        assertTrue(restored.state.value.unresolved.isEmpty())
    }
    @Test fun reconciledAttemptEvidenceSurvivesAndRejectsAReplayOfOldUncertainty() {
        val executor = engine(); val attempt = enqueue(executor)
        executor.stepFinished(attempt.ticket, StepOutcome.Unknown("旧结果未知"))
        executor.resolveUnknown(attempt.ticket, StepOutcome.Completed("已核对"))
        val restored = restore(executor)
        restored.stepFinished(attempt.ticket, StepOutcome.Unknown("旧结果未知"))
        assertTrue(restored.state.value.unresolved.isEmpty())
    }
    @Test fun corruptionAndUnknownFormatsAreErrorsInsteadOfAnEmptyExecutor() {
        val executor = engine(); enqueue(executor)
        val json = Json.parseToJsonElement(executor.encodeState()).jsonObject
        fun changed(name: String, value: JsonElement) = JsonObject(json + (name to value)).toString()
        assertThrows(IllegalArgumentException::class.java) { TaskExecutor.fromEncodedState("broken") }
        assertThrows(IllegalArgumentException::class.java) { TaskExecutor.fromEncodedState(changed("format", JsonPrimitive(99))) }
        assertThrows(IllegalArgumentException::class.java) { TaskExecutor.fromEncodedState(JsonObject(json - "format").toString()) }
        assertThrows(IllegalArgumentException::class.java) { TaskExecutor.fromEncodedState(changed("serial", JsonPrimitive(0))) }
        assertThrows(IllegalArgumentException::class.java) { TaskExecutor.fromEncodedState(changed("waiting", JsonArray(listOf(json.getValue("current"))))) }
        assertThrows(IllegalArgumentException::class.java) { TaskExecutor.fromEncodedState(changed("unknownProperty", JsonPrimitive(true))) }
    }
    @Test fun theEarlierFormatRetainsPendingInputAndUncertaintyWhenUpgraded() {
        val legacy = javaClass.getResource("/executor-state-v1.json")!!.readText()
        val executor = TaskExecutor.fromEncodedState(legacy) { 1234L }
        assertEquals(ExecutorPhase.PAUSING, executor.state.value.phase)
        val ticket = executor.state.value.activeTicket!!
        executor.inputQuiescent(ticket, StepOutcome.Interrupted)
        assertEquals("legacy fixture uncertainty", executor.state.value.unresolved.single().reason)
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, executor.state.value.phase)
        executor.resolveUnknown(ticket, StepOutcome.Completed("已核对旧结果"))
        assertEquals(ExecutorPhase.PAUSED, executor.state.value.phase)
        assertEquals("second", executor.state.value.current!!.stepId)
        val next = executor.resume(ResumeMode.CONTINUE).single() as ExecutorEffect.ExecuteStep
        assertTrue(next.attempt.ticket.generation > ticket.generation)
        assertEquals(executor.state.value, restore(executor).state.value)
        assertEquals(2, Json.parseToJsonElement(executor.encodeState()).jsonObject.getValue("format").jsonPrimitive.int)
    }
    @Test fun completingStepsDoesNotReplicateTheWholePlanForEachAttempt() {
        val executor = engine()
        val tasks = (1..40).map { index -> TaskSnapshot("task-$index", "测试 $index", 1,
            (1..20).map { "step-$it" }, "{\"note\":\"${"x".repeat(4096)}\"}") }
        executor.enqueue("large-plan", ExecutionRequest(SubmissionRef(SubmissionKind.PLAN, "large-plan"), context, "v1", tasks))
        val initialSize = executor.encodeState().length
        repeat(100) { executor.stepFinished(executor.state.value.activeTicket!!, StepOutcome.Completed("测试完成")) }
        val encoded = executor.encodeState()
        assertTrue("History must not repeat a whole plan per completed step", encoded.length < initialSize * 2)
        assertEquals(executor.state.value, TaskExecutor.fromEncodedState(encoded).state.value)
    }
}
