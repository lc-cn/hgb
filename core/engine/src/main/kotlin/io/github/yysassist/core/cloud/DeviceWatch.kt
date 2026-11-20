package io.github.yysassist.core.cloud

import io.github.yysassist.core.execution.ExecutorPhase
import io.github.yysassist.core.execution.ExecutorSnapshot
import io.github.yysassist.protocol.DeviceEvent
import io.github.yysassist.protocol.DeviceRule

/**
 * Judges the rules only the device can see, from what the executor shows over time:
 * - no-progress: a run is running, not paused, and has stayed on one step for the rule's minutes;
 * - condition-lost: there is a run or a queue, and the device has been unable to start a step (no accessibility, no
 *   screen, game not in front) for [conditionGraceMillis], so a passing blip is not reported.
 * Each occurrence is reported once: a step that moves on, or a condition that clears, starts a new one.
 */
class DeviceWatch(private val deviceName: String, private val conditionGraceMillis: Long = 60_000) {
    private var stepKey: String? = null
    private var stepSince = 0L
    private var condition: String? = null
    private var conditionSince = 0L
    private val reported = LinkedHashSet<String>()

    fun observe(snapshot: ExecutorSnapshot, blocked: String?, rules: List<DeviceRule>, now: Long): List<DeviceEvent> {
        val out = mutableListOf<DeviceEvent>()
        val current = snapshot.current
        // A run that cannot start its step is the lost condition below, not a lack of progress: one cause, one notice.
        if (current != null && snapshot.phase == ExecutorPhase.RUNNING && !snapshot.manualHold && blocked == null) {
            val key = "${current.runId}:${current.taskIndex}:${current.stepIndex}"
            if (key != stepKey) { stepKey = key; stepSince = now }
            rules.firstOrNull { it.ruleId == NO_PROGRESS }?.threshold?.let { minutes ->
                val stayed = now - stepSince
                // Named by the run and step, not by when watching began, so a restart does not report the same stay again.
                if (stayed >= minutes * MINUTE) report(out, "no-progress:$key", NO_PROGRESS, now, "执行无进展：$deviceName · ${current.task.name}",
                    "第 ${current.stepIndex + 1}/${current.task.stepIds.size} 步已经停留 ${stayed / MINUTE} 分钟")
            }
        } else stepKey = null
        val run = current?.runId ?: snapshot.waiting.firstOrNull()?.runId
        if (run != null && blocked != null) {
            if (blocked != condition) { condition = blocked; conditionSince = now }
            // Named by the run held up and the cause, so a restart does not report the same interruption again.
            if (now - conditionSince >= conditionGraceMillis && rules.any { it.ruleId == CONDITION_LOST })
                report(out, "condition-lost:$run:$blocked", CONDITION_LOST, now, "运行条件中断：$deviceName", blocked)
        } else condition = null
        return out
    }
    private fun report(out: MutableList<DeviceEvent>, key: String, ruleId: String, now: Long, title: String, detail: String) {
        if (!reported.add(key)) return
        while (reported.size > 100) reported.remove(reported.first())
        out += DeviceEvent(key, ruleId, now, title, detail)
    }

    companion object {
        const val NO_PROGRESS = "no-progress"
        const val CONDITION_LOST = "condition-lost"
        private const val MINUTE = 60_000L
    }
}
