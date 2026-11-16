package io.github.yysassist.core.execution

import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import org.junit.Assert.*
import org.junit.Test

class TaskExecutorTest {
    private fun scope(account: String, target: String = "onmyoji") = ExecutionContext(target, Principal.Account(account))
    private fun task(id: String, priority: Long = 10, steps: Int = 2) =
        TaskSnapshot(id, id, priority, (1..steps).map { "s$it" })
    private fun request(id: String, priority: Long = 10, account: String = "A", version: String = "v1") =
        ExecutionRequest(SubmissionRef(SubmissionKind.TASK, id), scope(account), version, listOf(task(id, priority)))
    private fun plan(vararg tasks: TaskSnapshot) =
        ExecutionRequest(SubmissionRef(SubmissionKind.PLAN, "plan"), scope("A"), "v1", tasks.toList())
    private fun TaskExecutor.add(id: String, priority: Long = 10) = enqueue(id, request(id, priority))
    private fun TaskExecutor.ticket() = state.value.activeTicket!!
    private fun TaskExecutor.complete() = stepFinished(ticket(), StepOutcome.Completed("observed-result"))
    private fun TaskExecutor.quiet(ticket: AttemptTicket = ticket()) = inputQuiescent(ticket, StepOutcome.Interrupted)

    @Test fun `plan priority remains sum after completed member`() {
        val e = TaskExecutor(scope("A"))
        e.enqueue("p", plan(task("one", 10, 1), task("two", 20)))
        e.add("other", 25)
        assertEquals(1, e.state.value.waiting.size)
        assertEquals(30L, e.state.value.current!!.priority)
        e.complete()
        assertEquals("two", e.state.value.current!!.task.id)
        assertEquals(30L, e.state.value.current!!.priority)
        assertEquals("other", e.state.value.waiting.single().task.id)
    }

    @Test fun `strict higher priority revokes input before dispatch and low resumes unfinished step`() {
        val e = TaskExecutor(scope("A"))
        e.add("low", 10)
        e.complete()
        val low = e.ticket()
        val response = e.add("high", 20)
        assertEquals(listOf(ExecutorEffect.RevokeInput(low)), response.effects)
        assertFalse(e.permitsInput(low))
        assertEquals("low", e.state.value.current!!.task.id)
        val effects = e.quiet(low)
        assertEquals("high", (effects.single() as ExecutorEffect.ExecuteStep).attempt.run.task.id)
        e.complete(); e.complete()
        assertEquals("low", e.state.value.current!!.task.id)
        assertEquals("s2", e.state.value.current!!.stepId)
    }

    @Test fun `equal priority keeps FIFO and cannot preempt`() {
        val e = TaskExecutor(scope("A"))
        e.add("first")
        assertTrue(e.add("second").effects.isEmpty())
        assertTrue(e.add("third").effects.isEmpty())
        e.complete(); e.complete()
        assertEquals("second", e.state.value.current!!.task.id)
        e.complete(); e.complete()
        assertEquals("third", e.state.value.current!!.task.id)
    }

    @Test fun `pause requires independent quiet and holds queued work`() {
        val e = TaskExecutor(scope("A"))
        e.add("one"); e.add("two")
        val ticket = e.ticket()
        assertEquals(listOf(ExecutorEffect.RevokeInput(ticket)), e.pause())
        assertEquals(ExecutorPhase.PAUSING, e.state.value.phase)
        assertFalse(e.permitsInput(ticket))
        assertTrue(e.resume(ResumeMode.CONTINUE).isEmpty())
        e.quiet(ticket)
        assertEquals(ExecutorPhase.PAUSED, e.state.value.phase)
        assertEquals(1, e.state.value.waiting.size)
        assertEquals("s1", (e.resume(ResumeMode.CONTINUE).single() as ExecutorEffect.ExecuteStep).attempt.run.stepId)
        assertNotEquals(ticket, e.ticket())
    }

    @Test fun `continue skips completed steps restart only resets current task`() {
        val e = TaskExecutor(scope("A"))
        e.enqueue("p", plan(task("done", 10, 1), task("current", 20, 3)))
        e.complete(); e.complete()
        assertEquals("s2", e.state.value.current!!.stepId)
        var ticket = e.ticket(); e.pause(); e.quiet(ticket)
        e.resume(ResumeMode.CONTINUE)
        assertEquals("s2", e.state.value.current!!.stepId)
        ticket = e.ticket(); e.pause(); e.quiet(ticket)
        e.resume(ResumeMode.RESTART_CURRENT_TASK)
        assertEquals("current", e.state.value.current!!.task.id)
        assertEquals(1, e.state.value.current!!.taskIndex)
        assertEquals("s1", e.state.value.current!!.stepId)
        assertEquals(30L, e.state.value.current!!.priority)
    }

    @Test fun `stop ends current but holds other work`() {
        val e = TaskExecutor(scope("A"))
        e.add("one"); e.add("two")
        val ticket = e.ticket(); e.stop()
        assertEquals(ExecutorPhase.PAUSING, e.state.value.phase)
        e.quiet(ticket)
        assertNull(e.state.value.current)
        assertTrue(e.state.value.manualHold)
        assertEquals("two", e.state.value.waiting.single().task.id)
        e.resume(ResumeMode.CONTINUE)
        assertEquals("two", e.state.value.current!!.task.id)
    }

    @Test fun `removing a hung current does not wait for worker and continues only after quiet`() {
        val e = TaskExecutor(scope("A"))
        val run = (e.add("hung").result as EnqueueResult.Accepted).runId
        e.add("next")
        val ticket = e.ticket()
        assertEquals(listOf(ExecutorEffect.RevokeInput(ticket)), e.remove(run))
        assertEquals(ExecutorPhase.REMOVING, e.state.value.phase)
        assertFalse(e.permitsInput(ticket))
        e.quiet(ticket)
        assertEquals("next", e.state.value.current!!.task.id)
        val next = e.ticket()
        e.stepFinished(ticket, StepOutcome.Completed("late"))
        assertEquals(next, e.ticket())
        assertEquals("s1", e.state.value.current!!.stepId)
    }

    @Test fun `removing while user holds does not resume queue`() {
        val e = TaskExecutor(scope("A"))
        val run = (e.add("one").result as EnqueueResult.Accepted).runId
        e.add("two")
        val ticket = e.ticket(); e.pause(); e.quiet(ticket)
        e.remove(run)
        assertEquals(ExecutorPhase.PAUSED, e.state.value.phase)
        assertNull(e.state.value.activeTicket)
        assertEquals("two", e.state.value.waiting.single().task.id)
    }

    @Test fun `duplicate command is idempotent and live ref cannot repeat`() {
        val e = TaskExecutor(scope("A"))
        val first = e.add("one")
        assertEquals(first.result, e.add("one").result)
        assertTrue(e.enqueue("another-command", request("one")).result is EnqueueResult.Duplicate)
        assertTrue(e.enqueue("one", request("changed")).result is EnqueueResult.Rejected)
        e.complete(); e.complete()
        assertEquals(first.result, e.add("one").result)
        assertNull(e.state.value.current)
        assertTrue(e.enqueue("new-command", request("one")).result is EnqueueResult.Accepted)
    }

    @Test fun `confirmed failure advances plan and emits partial result`() {
        val e = TaskExecutor(scope("A"))
        e.enqueue("p", plan(task("bad", 10), task("good", 20, 1)))
        e.stepFinished(e.ticket(), StepOutcome.Failed("confirmed failure"))
        assertEquals("good", e.state.value.current!!.task.id)
        e.complete()
        assertNull(e.state.value.current)
        assertEquals(RunResult.PARTIAL_FAILURE.name, e.state.value.facts.last().detail)
    }

    @Test fun `unknown blocks work and cannot be cleared by resume restart or removal`() {
        val e = TaskExecutor(scope("A"))
        val run = (e.add("one").result as EnqueueResult.Accepted).runId
        e.add("two")
        val ticket = e.ticket()
        e.stepFinished(ticket, StepOutcome.Unknown("may have committed"))
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, e.state.value.phase)
        assertTrue(e.resume(ResumeMode.RESTART_CURRENT_TASK).isEmpty())
        e.remove(run)
        assertEquals(1, e.state.value.unresolved.size)
        assertEquals("one", e.state.value.current!!.task.id)
        e.resolveUnknown(ticket, StepOutcome.Completed("balance verified"))
        assertEquals("two", e.state.value.current!!.task.id)
    }

    @Test fun `late unknown result revokes new input and retains old evidence`() {
        val e = TaskExecutor(scope("A"))
        e.add("old"); e.add("next")
        val old = e.ticket(); e.complete(); e.complete()
        val next = e.ticket()
        val effects = e.stepFinished(old, StepOutcome.Unknown("late unresolved action"))
        assertEquals(listOf(ExecutorEffect.RevokeInput(next)), effects)
        assertFalse(e.permitsInput(next))
        e.quiet(next)
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, e.state.value.phase)
        e.resolveUnknown(old, StepOutcome.Completed("old result checked"))
        assertEquals("next", e.state.value.current!!.task.id)
        assertEquals("s1", e.state.value.current!!.stepId)
    }

    @Test fun `pause during preemption preserves current task for restart`() {
        val e = TaskExecutor(scope("A"))
        e.add("low"); e.complete()
        val low = e.ticket(); e.add("high", 20); e.pause(); e.quiet(low)
        assertEquals("low", e.state.value.current!!.task.id)
        e.resume(ResumeMode.RESTART_CURRENT_TASK)
        assertEquals("high", e.state.value.current!!.task.id)
        assertEquals("s1", e.state.value.waiting.single().stepId)
    }

    @Test fun `other account high priority cannot preempt and verified switch clears old live items`() {
        val e = TaskExecutor(scope("A"))
        e.add("one")
        assertTrue(e.enqueue("B", request("other", 100, "B")).effects.isEmpty())
        assertEquals(ContextChangeResult.InputNotQuiet, e.setVerifiedContext(scope("B")))
        val ticket = e.ticket(); e.pause(); e.quiet(ticket)
        assertEquals(ContextChangeResult.Accepted, e.setVerifiedContext(scope("B")))
        assertNull(e.state.value.current)
        assertTrue(e.state.value.waiting.isEmpty())
        assertTrue(e.state.value.manualHold)
    }

    @Test fun `invalid schema priority overflow and blank outcome rejected without admission`() {
        val e = TaskExecutor(scope("A"))
        assertTrue(e.enqueue("p", plan(task("one", Long.MAX_VALUE), task("two", 1))).result is EnqueueResult.Rejected)
        assertTrue(e.enqueue("bad", request("one").copy(tasks = emptyList())).result is EnqueueResult.Rejected)
        e.add("one")
        val ticket = e.ticket()
        assertThrows(IllegalArgumentException::class.java) { e.stepFinished(ticket, StepOutcome.Completed("")) }
        assertThrows(IllegalArgumentException::class.java) { e.stepFinished(ticket, StepOutcome.Interrupted) }
        assertEquals(ticket, e.ticket())
    }

    @Test fun `caller mutation cannot alter queued snapshot or priority`() {
        val e = TaskExecutor(scope("A")); e.pause()
        val steps = mutableListOf("s1", "s2")
        val tasks = mutableListOf(task("one").copy(stepIds = steps))
        e.enqueue("one", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "one"), scope("A"), "v1", tasks))
        steps.clear(); tasks.clear()
        val run = e.state.value.waiting.single()
        assertEquals(listOf("s1", "s2"), run.task.stepIds)
        assertEquals(10L, run.priority)
    }

    @Test fun `identity readiness can admit waiting work but never releases a user hold`() {
        val e = TaskExecutor()
        e.add("one")
        assertNull(e.state.value.activeTicket)
        assertEquals(ContextChangeResult.Accepted, e.setVerifiedContext(scope("A")))
        assertEquals(1, e.readinessChanged().size)
        val ticket = e.ticket(); e.pause(); e.quiet(ticket)
        assertEquals(ContextChangeResult.Accepted, e.setVerifiedContext(scope("A")))
        assertTrue(e.readinessChanged().isEmpty())
        assertTrue(e.state.value.manualHold)
    }

    @Test fun `result facts retain original account version and display name after account change`() {
        val e = TaskExecutor(scope("A")) { 1234L }
        e.enqueue("one", request("one", version = "old-release").copy(tasks = listOf(task("one", steps = 1).copy(name = "原任务名称"))))
        e.complete()
        assertEquals(ContextChangeResult.Accepted, e.setVerifiedContext(scope("B")))
        val fact = e.state.value.facts.first { it.type == "task-completed" }
        assertEquals(scope("A"), fact.context)
        assertEquals("old-release", fact.packageVersion)
        assertEquals("原任务名称", fact.taskName)
        assertEquals(1234L, fact.occurredAtEpochMillis)
    }

    @Test fun `late revocation acknowledgment cannot discard an unknown result`() {
        val e = TaskExecutor(scope("A"))
        val run = (e.add("old").result as EnqueueResult.Accepted).runId
        e.add("next")
        val old = e.ticket(); e.remove(run); e.quiet(old)
        val next = e.ticket()
        val effects = e.inputQuiescent(old, StepOutcome.Unknown("late game result uncertainty"))
        assertEquals(listOf(ExecutorEffect.RevokeInput(next)), effects)
        assertFalse(e.permitsInput(next))
        assertEquals(old, e.state.value.unresolved.single().attempt.ticket)
    }

    @Test fun `replayed old uncertainty cannot undo explicit reconciliation`() {
        val e = TaskExecutor(scope("A"))
        e.add("one")
        val old = e.ticket()
        e.stepFinished(old, StepOutcome.Unknown("original callback"))
        e.resolveUnknown(old, StepOutcome.Interrupted)
        val next = e.ticket()
        assertTrue(e.stepFinished(old, StepOutcome.Unknown("original callback")).isEmpty())
        assertTrue(e.inputQuiescent(old, StepOutcome.Unknown("original callback")).isEmpty())
        assertTrue(e.state.value.unresolved.isEmpty())
        assertTrue(e.permitsInput(next))
    }

    @Test fun `same local task id in another target has its own live submission identity`() {
        val e = TaskExecutor(scope("A"))
        val first = e.add("same")
        val other = e.enqueue("other-app", request("same").copy(context = scope("A", "another-app")))
        assertTrue(first.result is EnqueueResult.Accepted)
        assertTrue(other.result is EnqueueResult.Accepted)
        assertTrue(other.effects.isEmpty())
        assertEquals("onmyoji", e.state.value.current!!.request.context.targetId)
        assertEquals("another-app", e.state.value.waiting.single().request.context.targetId)
        assertTrue(e.enqueue("same-again", request("same")).result is EnqueueResult.Duplicate)
    }

    @Test fun `accountless application uses an explicit anonymous context not a fake account`() {
        val anonymous = ExecutionContext("utility-app", Principal.Anonymous)
        val e = TaskExecutor()
        val response = e.enqueue("utility", request("one").copy(context = anonymous))
        assertTrue(response.result is EnqueueResult.Accepted)
        assertTrue(response.effects.isEmpty())
        assertNull(e.state.value.context)
        assertEquals(ContextChangeResult.Accepted, e.setVerifiedContext(anonymous))
        assertEquals(1, e.readinessChanged().size)
        assertEquals(Principal.Anonymous, e.state.value.current!!.request.context.principal)
    }

    @Test fun `cross-target context handoff cannot silently discard unfinished work`() {
        val e = TaskExecutor(scope("A"))
        e.add("one")
        val old = e.ticket(); e.pause(); e.quiet(old)
        assertEquals(ContextChangeResult.CrossTargetWorkPending, e.setVerifiedContext(scope("A", "another-app")))
        assertEquals("onmyoji", e.state.value.context!!.targetId)
        assertEquals("one", e.state.value.current!!.task.id)
        assertTrue(e.state.value.manualHold)
    }
}
