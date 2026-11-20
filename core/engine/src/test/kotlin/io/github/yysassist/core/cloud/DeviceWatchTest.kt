package io.github.yysassist.core.cloud

import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import io.github.yysassist.protocol.DeviceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWatchTest {
    private val minute = 60_000L
    private val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "tour"), ExecutionContext("game", Principal.Anonymous), "v1",
        listOf(TaskSnapshot("tour", "日常巡游", 1, listOf("a", "b", "c"))))
    private fun snapshot(step: Int = 0, phase: ExecutorPhase = ExecutorPhase.RUNNING, hold: Boolean = false, running: Boolean = true) =
        TaskExecutor().state.value.copy(phase = phase, manualHold = hold, current = if (running) RunProgress(7, 1, request, 1, stepIndex = step) else null)
    private val rules = listOf(DeviceRule("no-progress", threshold = 5), DeviceRule("condition-lost"))

    @Test fun aRunStuckOnOneStepIsReportedOnceAfterTheThreshold() {
        val watch = DeviceWatch("手机")
        assertTrue(watch.observe(snapshot(), null, rules, 0).isEmpty())
        assertTrue(watch.observe(snapshot(), null, rules, 4 * minute).isEmpty())
        val stuck = watch.observe(snapshot(), null, rules, 5 * minute)
        assertEquals(listOf("no-progress"), stuck.map { it.ruleId })
        assertTrue(stuck.single().title.contains("日常巡游")); assertTrue(stuck.single().detail.contains("第 1/3 步"))
        assertTrue(watch.observe(snapshot(), null, rules, 9 * minute).isEmpty())
        // Moving on starts over; a pause does not count.
        assertTrue(watch.observe(snapshot(step = 1), null, rules, 10 * minute).isEmpty())
        watch.observe(snapshot(step = 1, phase = ExecutorPhase.PAUSED, hold = true), null, rules, 12 * minute)
        assertTrue(watch.observe(snapshot(step = 1), null, rules, 14 * minute).isEmpty())
    }
    @Test fun aDeviceUnableToWorkWithTasksWaitingIsReportedAfterAMinuteNotForABlip() {
        val watch = DeviceWatch("手机")
        watch.observe(snapshot(), "等待无障碍连接", rules, 0)
        watch.observe(snapshot(), null, rules, 30_000)
        assertTrue(watch.observe(snapshot(), "等待无障碍连接", rules, 40_000).isEmpty())
        assertTrue(watch.observe(snapshot(), "等待无障碍连接", rules, 90_000).isEmpty())
        val lost = watch.observe(snapshot(), "等待无障碍连接", rules, 100_000)
        assertEquals("condition-lost", lost.single().ruleId); assertEquals("等待无障碍连接", lost.single().detail)
        assertTrue(watch.observe(snapshot(), "等待无障碍连接", rules, 200_000).isEmpty())
        // Nothing to run: nothing is lost.
        val idle = DeviceWatch("手机")
        idle.observe(snapshot(running = false, phase = ExecutorPhase.IDLE), "等待屏幕可用", rules, 0)
        assertTrue(idle.observe(snapshot(running = false, phase = ExecutorPhase.IDLE), "等待屏幕可用", rules, 5 * minute).isEmpty())
    }
    @Test fun aRuleThatIsOffIsNotJudged() {
        val watch = DeviceWatch("手机")
        watch.observe(snapshot(), "等待屏幕可用", emptyList(), 0)
        assertTrue(watch.observe(snapshot(), "等待屏幕可用", emptyList(), 10 * minute).isEmpty())
    }
    @Test fun aRunThatCannotStartItsStepIsAnInterruptionNotALackOfProgress() {
        val watch = DeviceWatch("手机")
        val events = (0..10).flatMap { watch.observe(snapshot(), "等待屏幕可用", rules, it * minute) }
        assertEquals(listOf("condition-lost"), events.map { it.ruleId })
        // A fresh watch, as after a restart, names the same interruption the same way, so the server keeps one.
        val again = DeviceWatch("手机").let { fresh -> (0..2).flatMap { fresh.observe(snapshot(), "等待屏幕可用", rules, it * minute) } }
        assertEquals(events.single().key, again.single().key)
    }
}
