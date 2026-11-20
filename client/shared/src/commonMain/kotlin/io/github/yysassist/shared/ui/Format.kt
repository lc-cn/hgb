package io.github.yysassist.shared.ui

import io.github.yysassist.protocol.JobEventView
import io.github.yysassist.protocol.JobState
import io.github.yysassist.protocol.JobView
import io.github.yysassist.protocol.PlanView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How states, times and plans read, the same words as client/web/src/format.ts. */
object Format {
    val JOB_STATES = mapOf(
        JobState.WAITING to ("等可用应用" to Tone.INFO), JobState.QUEUED to ("排队中" to Tone.INFO), JobState.LEASED to ("已下发" to Tone.INFO),
        JobState.RUNNING to ("运行中" to Tone.INFO), JobState.COMPLETED to ("已完成" to Tone.OK), JobState.FAILED to ("失败" to Tone.BAD),
        JobState.UNKNOWN to ("结果未知" to Tone.WARN), JobState.CANCELLED to ("已取消" to Tone.NEUTRAL), JobState.SKIPPED to ("已跳过" to Tone.WARN),
    )
    val ACTIVE = setOf(JobState.WAITING, JobState.QUEUED, JobState.LEASED, JobState.RUNNING)
    val PHASES = mapOf("CONNECTING" to "连接中", "IDLE" to "空闲", "RUNNING" to "运行中", "PAUSING" to "正在暂停", "PAUSED" to "已暂停",
        "REMOVING" to "正在停止", "SWITCHING" to "切换中", "BLOCKED_UNKNOWN" to "需要处理")
    val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

    /** The job's state as the user should read it, taking pending pause and stop requests into account. */
    fun jobStatus(job: JobView): Pair<String, Tone> = when {
        job.cancelRequested && job.state in ACTIVE -> "正在停止" to Tone.WARN
        job.state == JobState.RUNNING && job.pauseRequested -> "已暂停" to Tone.WARN
        else -> JOB_STATES.getValue(job.state)
    }
    /** The plan a job came from; null for a run started by hand, which needs no saying. */
    fun jobSource(job: JobView): String? = job.planName?.let { "计划 · $it" }
    /** How an app is doing, as a pill. */
    fun appStatus(phase: String, online: Boolean): Pair<String, Tone> {
        if (!online) return "离线" to Tone.NEUTRAL
        val label = PHASES[phase] ?: phase
        return label to when (phase) {
            "RUNNING", "SWITCHING" -> Tone.INFO; "IDLE" -> Tone.OK; "BLOCKED_UNKNOWN" -> Tone.BAD
            "PAUSED", "PAUSING", "REMOVING" -> Tone.WARN; else -> Tone.NEUTRAL
        }
    }
    fun platformName(platform: String) = mapOf("android" to "Android", "windows" to "Windows 桌面端", "ios" to "iOS")[platform.lowercase()] ?: platform

    fun clockText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
    fun planRule(plan: PlanView): String {
        val days = if (plan.days.size == 7) "每天" else "每周" + plan.days.joinToString("、") { WEEK[it - 1] }
        return "$days ${clockText(plan.minuteOfDay)}"
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    /** A time in a list: today shows the clock, yesterday says so, older ones add the date. */
    fun shortTime(at: Long?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        if (at == null || at == 0L) return "—"
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(time.toLocalDate(), today)
        return when {
            days == 0L -> time.format(hm)
            days == 1L -> "昨天 ${time.format(hm)}"
            time.year == today.year -> "${time.monthValue}月${time.dayOfMonth}日 ${time.format(hm)}"
            else -> "${time.year}年${time.monthValue}月${time.dayOfMonth}日"
        }
    }
    fun dateTime(at: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        at?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss")) } ?: "—"
    fun seconds(ms: Long) = if (ms < 60_000) "${maxOf(1, Math.round(ms / 1000.0))} 秒" else "${ms / 60_000} 分 ${Math.round((ms % 60_000) / 1000.0)} 秒"
    fun shortDuration(ms: Long) = if (ms < 10_000) "%.1f 秒".format(ms / 1000.0) else seconds(ms)

    private val EVENT_KINDS = mapOf("enqueued" to "已排队", "step-started" to "开始步骤", "step-observed" to "步骤结果", "step-completed" to "步骤完成",
        "task-completed" to "任务完成", "task-failed" to "任务失败", "task-restarted" to "任务重来", "run-ended" to "运行结束", "cancelled" to "已取消",
        "input-unconfirmed" to "输入未确认", "result-resolved" to "结果已确认", "revocation-observed" to "停止确认", "action" to "动作")
    private val ACTION_RESULTS = mapOf("PRODUCED" to "完成", "VERIFIED" to "核对通过", "UNAVAILABLE" to "未通过", "INTERRUPTED" to "中断")
    private val RUN_ENDS = mapOf("COMPLETED" to "已完成", "FAILED" to "失败", "CANCELLED" to "已停止", "UNKNOWN" to "结果未知", "INTERRUPTED" to "已中断")
    fun eventKind(kind: String) = EVENT_KINDS[kind] ?: kind
    /** Makes the executor's wording readable without changing what it says. */
    fun eventDetail(event: JobEventView): String = when (event.kind) {
        "action" -> event.detail.split("：").let { parts -> listOf(ACTION_RESULTS[parts[0]] ?: parts[0], parts.drop(1).joinToString("：")).filter { it.isNotEmpty() }.joinToString("：") }
        "run-ended" -> RUN_ENDS[event.detail] ?: event.detail
        else -> event.detail.replace(Regex("^Completed\\(evidence=(.*)\\)$"), "完成：$1").replace(Regex("^Unknown\\(reason=(.*)\\)$"), "结果未知：$1")
            .replace(Regex("^Failed\\(reason=(.*)\\)$"), "失败：$1").replace(Regex("^Interrupted$"), "已中断")
    }
}
