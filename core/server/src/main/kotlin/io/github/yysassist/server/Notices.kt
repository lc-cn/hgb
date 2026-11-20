package io.github.yysassist.server

import io.github.yysassist.protocol.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** The rules the server can judge by itself, from jobs, plans and device connections. All are off until turned on. */
internal object RuleCatalog {
    data class Rule(val id: String, val name: String, val kind: RuleKind, val description: String, val suggested: Long? = null)
    const val TASK_STARTED = "task-started"; const val TASK_SUCCEEDED = "task-succeeded"; const val PLAN_STARTED = "plan-started"
    const val PLAN_COMPLETED = "plan-completed"; const val EXECUTION_FAILED = "execution-failed"; const val UNKNOWN_RESULT = "unknown-result"
    const val QUEUE_WAIT = "queue-wait"; const val DEVICE_OFFLINE = "device-offline"; const val PLAN_MISSED = "plan-missed"
    const val ACCOUNT_NOT_RESTORED = "account-not-restored"
    val all = listOf(
        Rule(TASK_STARTED, "任务开始", RuleKind.INSTANT, "作业在设备上真正开始运行时"),
        Rule(TASK_SUCCEEDED, "任务成功", RuleKind.INSTANT, "作业运行完成时"),
        Rule(PLAN_STARTED, "计划开始", RuleKind.INSTANT, "一次计划的第一个任务开始运行时"),
        Rule(PLAN_COMPLETED, "计划结束", RuleKind.RESULT, "一次计划的所有任务都结束时，按结果筛选"),
        Rule(EXECUTION_FAILED, "连续执行失败", RuleKind.COUNT, "同一账号的同一任务、或同一计划连续失败达到次数时；结果未知和取消不计", 1),
        Rule(UNKNOWN_RESULT, "结果待核对", RuleKind.INSTANT, "作业结果无法确认，需要看一眼游戏时"),
        Rule(QUEUE_WAIT, "排队等待过久", RuleKind.DURATION, "作业建好后过了这么久还没开始运行", 10),
        Rule(DEVICE_OFFLINE, "设备掉线", RuleKind.DURATION, "设备断开连接达到这么久", 10),
        Rule(PLAN_MISSED, "计划没执行", RuleKind.INSTANT, "计划到了最晚开始时间仍没有可用的应用"),
        Rule(ACCOUNT_NOT_RESTORED, "账号没切回", RuleKind.INSTANT, "切号运行后没能切回应用的默认账号"),
    )
    val byId = all.associateBy { it.id }
}

/**
 * Turns what happens to jobs, plans and devices into notices, by the user's rules. Job state changes come from the
 * `job_changes` log the database fills on every change, so no code path that moves a job can be missed; lasting
 * conditions (waiting, offline) are checked on every tick. A notice is recorded once per occurrence, whether or not
 * anything is subscribed to it, and a rule turned on later never reports what came before.
 */
class Notices(private val db: Database, private val clock: () -> Long, private val events: ConsoleEvents, private val channels: Channels,
    private val online: (String) -> Boolean) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outcomes = ListSerializer(PlanOutcome.serializer())
    /** Devices are not judged offline for the time before the server came up: they could not have been connected then. */
    private val startedAt = clock()

    private class Setting(val enabled: Boolean, val threshold: Long?, val results: List<PlanOutcome>, val enabledAt: Long)

    fun rules(userId: String): List<EventRuleView> = db.tx { ruleViews(userId) }
    private fun Database.ruleViews(userId: String): List<EventRuleView> {
        val saved = settings(userId)
        return RuleCatalog.all.map { rule ->
            val setting = saved[rule.id]
            EventRuleView(rule.id, rule.name, rule.kind, rule.description, setting?.enabled == true, setting?.threshold, rule.suggested,
                setting?.results ?: if (rule.kind == RuleKind.RESULT) PlanOutcome.entries else emptyList())
        }
    }
    private fun Database.settings(userId: String): Map<String, Setting> =
        query("SELECT rule_id, enabled, threshold, results_json, enabled_at FROM event_rules WHERE user_id = ?", userId) {
            it.getString(1) to Setting(it.getInt(2) == 1, it.getLong(3).takeUnless { _ -> it.wasNull() }, json.decodeFromString(outcomes, it.getString(4)),
                it.getLong(5)) }.toMap()

    /** Turning a rule on needs its threshold (minutes or a count) and, for a plan's end, at least one result. */
    fun save(userId: String, ruleId: String, save: EventRuleSave): List<EventRuleView> {
        val rule = RuleCatalog.byId[ruleId] ?: throw ApiException(404, "not_found", "没有这条规则")
        val threshold = save.threshold?.takeIf { rule.kind == RuleKind.COUNT || rule.kind == RuleKind.DURATION }
        if (save.enabled) when (rule.kind) {
            RuleKind.COUNT -> if (threshold == null || threshold !in 1..100) throw ApiException(400, "invalid_threshold", "连续次数需要在 1 到 100 之间")
            RuleKind.DURATION -> if (threshold == null || threshold !in 1..7 * 24 * 60) throw ApiException(400, "invalid_threshold", "时长需要在 1 分钟到 7 天之间")
            RuleKind.RESULT -> if (save.results.isEmpty()) throw ApiException(400, "invalid_results", "至少选一种计划结果")
            RuleKind.INSTANT -> Unit
        }
        val results = if (rule.kind == RuleKind.RESULT) save.results.distinct() else emptyList()
        val views = db.tx {
            val now = clock()
            // Only switching on starts a new period; changing a threshold keeps what is already being watched.
            update("""INSERT INTO event_rules (user_id, rule_id, enabled, threshold, results_json, enabled_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, rule_id) DO UPDATE SET enabled = excluded.enabled, threshold = excluded.threshold, results_json = excluded.results_json,
                enabled_at = CASE WHEN event_rules.enabled = 1 AND excluded.enabled = 1 THEN event_rules.enabled_at ELSE excluded.enabled_at END""",
                userId, ruleId, if (save.enabled) 1 else 0, threshold, json.encodeToString(outcomes, results), now)
            ruleViews(userId)
        }
        events.publish(userId, "rules", json.encodeToString(ListSerializer(EventRuleView.serializer()), views))
        return views
    }

    /**
     * Newest first, optionally of one rule. A page continues after the notice [after] names ("at:id" of the last one
     * shown), so notices recorded in the same instant are neither skipped nor shown twice.
     */
    fun list(userId: String, ruleId: String?, after: String?, limit: Int = 50): List<NoticeView> = db.tx {
        val at = after?.substringBefore(':')?.toLongOrNull(); val id = after?.substringAfter(':', "")
        query("""SELECT id, rule_id, at, title, detail, job_id, device_id, account_id, plan_id FROM notices WHERE user_id = ? AND (? IS NULL OR rule_id = ?)
            AND (? IS NULL OR at < ? OR (at = ? AND id > ?)) ORDER BY at DESC, id LIMIT ?""",
            userId, ruleId, ruleId, at, at, at, id, limit.coerceIn(1, 200)) { notice(it) }
            .map { notice -> notice.copy(deliveries = with(channels) { deliveriesOf(notice.id) }) }
    }
    private fun notice(row: java.sql.ResultSet): NoticeView {
        val ruleId = row.getString(2)
        return NoticeView(row.getString(1), ruleId, RuleCatalog.byId[ruleId]?.name ?: ruleId, row.getLong(3), row.getString(4), row.getString(5),
            row.getString(6), row.getString(7), row.getString(8), row.getString(9))
    }

    /**
     * Reads the job changes not yet judged and, when [lasting], checks the lasting conditions too (the tick does; a
     * request that changed jobs only needs its changes judged). Tells each user's consoles what it recorded. A change
     * that cannot be judged is logged and dropped, so one bad row never holds up the rest or the request that made it.
     */
    fun process(lasting: Boolean = false) {
        val recorded = db.tx {
            val out = mutableListOf<Pair<String, NoticeView>>()
            val rules = HashMap<String, Map<String, Setting>>()
            fun Database.rule(userId: String, ruleId: String) = rules.getOrPut(userId) { settings(userId) }[ruleId]?.takeIf { it.enabled }
            val changes = query("SELECT seq, job_id, state, at FROM job_changes ORDER BY seq LIMIT 500") {
                Triple(it.getLong(1), it.getString(2), JobState.valueOf(it.getString(3))) to it.getLong(4) }
            for ((change, at) in changes) {
                val (_, jobId, state) = change
                val job = jobOf(jobId) ?: continue
                try { judge(job, state, at, { rule(job.userId, it) }) { notice -> out += job.userId to notice } }
                catch (error: Exception) { System.err.println("notices: job $jobId to $state not judged: $error") }
            }
            changes.lastOrNull()?.let { update("DELETE FROM job_changes WHERE seq <= ?", it.first.first) }
            if (lasting) lasting(clock()) { userId, notice -> out += userId to notice }
            out
        }
        recorded.forEach { (userId, notice) -> events.publish(userId, "notice", json.encodeToString(NoticeView.serializer(), notice)) }
    }

    private class Job(val id: String, val userId: String, val taskName: String, val accountId: String?, val accountName: String?, val targetId: String,
        val taskId: String, val planId: String?, val planName: String?, val createdAt: Long, val deviceId: String?, val detail: String, val restoreIssue: String?)
    private fun Database.jobOf(id: String): Job? = single("""SELECT id, user_id, task_name, account_id, account_name, target_id, task_id, plan_id, plan_name,
        created_at, device_id, detail, restore_issue FROM jobs WHERE id = ?""", id) {
        Job(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8),
            it.getString(9), it.getLong(10), it.getString(11), it.getString(12), it.getString(13)) }

    private fun Job.label() = listOfNotNull(accountName, taskName).joinToString(" · ")
    private fun Job.planLabel() = listOfNotNull(accountName, planName?.let { "计划“$it”" }).joinToString(" · ")

    /** One change of [job] to [state]; discrete rules only report what happened after they were turned on. */
    private fun Database.judge(job: Job, state: JobState, at: Long, rule: Database.(String) -> Setting?, recorded: (NoticeView) -> Unit) {
        fun emit(ruleId: String, key: String, title: String, detail: String = "", planId: String? = null) {
            val setting = rule(ruleId) ?: return
            if (at < setting.enabledAt) return
            record(job.userId, ruleId, key, at, title, detail, job.id, job.deviceId, job.accountId, planId)?.let(recorded)
        }
        when (state) {
            JobState.RUNNING -> {
                emit(RuleCatalog.TASK_STARTED, "task-started:${job.id}", "任务开始：${job.label()}")
                // Only the run's first start: a later task starting after the rule was turned on is not the run starting.
                if (job.planId != null && single("""SELECT 1 FROM jobs WHERE plan_id = ? AND created_at = ? AND id <> ? AND started_at IS NOT NULL AND started_at <= ? LIMIT 1""",
                        job.planId, job.createdAt, job.id, at) { true } != true)
                    emit(RuleCatalog.PLAN_STARTED, "plan-started:${job.planId}:${job.createdAt}", "计划开始：${job.planLabel()}", planId = job.planId)
            }
            JobState.COMPLETED -> emit(RuleCatalog.TASK_SUCCEEDED, "task-succeeded:${job.id}", "任务成功：${job.label()}")
            JobState.UNKNOWN -> emit(RuleCatalog.UNKNOWN_RESULT, "unknown-result:${job.id}", "结果待核对：${job.label()}", job.detail)
            JobState.FAILED -> if (job.planId == null) taskFailures(job)?.let { (count, first) ->
                val setting = rule(RuleCatalog.EXECUTION_FAILED)
                if (setting != null && count >= (setting.threshold ?: 1))
                    emit(RuleCatalog.EXECUTION_FAILED, "execution-failed:job:$first", "连续执行失败：${job.label()} 已连续失败 $count 次", job.detail)
            }
            else -> Unit
        }
        if (state !in TERMINAL) return
        job.restoreIssue?.let { emit(RuleCatalog.ACCOUNT_NOT_RESTORED, "account-not-restored:${job.id}", "账号没切回：${job.label()}", it) }
        val planId = job.planId ?: return
        val run = query("SELECT state FROM jobs WHERE plan_id = ? AND created_at = ?", planId, job.createdAt) { JobState.valueOf(it.getString(1)) }
        // A run none of whose tasks got to start did not happen; one that partly ran ends below as partly failed.
        if (run.isNotEmpty() && run.all { it == JobState.SKIPPED })
            emit(RuleCatalog.PLAN_MISSED, "plan-missed:$planId:${job.createdAt}", "计划没执行：${job.planLabel()}", job.detail, planId)
        val outcome = outcomeOf(run) ?: return
        val setting = rule(RuleCatalog.PLAN_COMPLETED)
        if (setting != null && outcome in setting.results)
            emit(RuleCatalog.PLAN_COMPLETED, "plan-completed:$planId:${job.createdAt}", "计划结束：${job.planLabel()} · ${OUTCOME_NAMES.getValue(outcome)}", planId = planId)
        if (outcome == PlanOutcome.FAILED || outcome == PlanOutcome.PARTIAL_FAILED) planFailures(planId)?.let { (count, first) ->
            val failed = rule(RuleCatalog.EXECUTION_FAILED)
            if (failed != null && count >= (failed.threshold ?: 1))
                emit(RuleCatalog.EXECUTION_FAILED, "execution-failed:plan:$planId:$first", "连续执行失败：${job.planLabel()} 已连续 $count 次没有全部完成", planId = planId)
        }
    }

    /**
     * The failures in a row of this account's task run on its own (not by a plan), newest first, and the first job of
     * that streak, which names it: a success ends it, an unknown or cancelled run neither counts nor ends it.
     */
    private fun Database.taskFailures(job: Job): Pair<Int, String>? {
        val recent = query("""SELECT id, state FROM jobs WHERE user_id = ? AND account_id IS ? AND target_id = ? AND task_id = ? AND plan_id IS NULL
            AND state IN ('COMPLETED', 'FAILED') ORDER BY updated_at DESC, created_at DESC LIMIT 100""", job.userId, job.accountId, job.targetId, job.taskId) {
            it.getString(1) to it.getString(2) }
        val streak = recent.takeWhile { it.second == "FAILED" }
        return streak.lastOrNull()?.let { streak.size to it.first }
    }
    /** The same for a plan's runs: a run that had a failed task counts, one that completed ends the streak. */
    private fun Database.planFailures(planId: String): Pair<Int, Long>? {
        val runs = query("SELECT created_at, state FROM jobs WHERE plan_id = ? ORDER BY created_at DESC LIMIT 500", planId) { it.getLong(1) to it.getString(2) }
            .groupBy({ it.first }, { it.second }).entries.sortedByDescending { it.key }
            .mapNotNull { (at, states) -> outcomeOf(states.map(JobState::valueOf))?.let { at to it } }
            .filter { it.second != PlanOutcome.CANCELLED }
        val streak = runs.takeWhile { it.second != PlanOutcome.COMPLETED }
        return streak.lastOrNull()?.let { streak.size to it.first }
    }
    /**
     * A run is over when all its jobs are; one that never started any job (all skipped) did not run, which the missed
     * plan reports instead. Completed only when all completed; cancelled only when all were cancelled; failed when
     * none completed; otherwise partly failed.
     */
    private fun outcomeOf(states: List<JobState>): PlanOutcome? = when {
        states.isEmpty() || states.any { it !in TERMINAL } -> null
        states.all { it == JobState.SKIPPED } -> null
        states.all { it == JobState.COMPLETED } -> PlanOutcome.COMPLETED
        states.all { it == JobState.CANCELLED } -> PlanOutcome.CANCELLED
        states.none { it == JobState.COMPLETED } -> PlanOutcome.FAILED
        else -> PlanOutcome.PARTIAL_FAILED
    }

    /** Conditions that become notices by lasting: a job not yet started, a device not connected. */
    private fun Database.lasting(now: Long, recorded: (String, NoticeView) -> Unit) {
        val waitUsers = query("SELECT user_id, threshold FROM event_rules WHERE rule_id = ? AND enabled = 1", RuleCatalog.QUEUE_WAIT) { it.getString(1) to it.getLong(2) }
        for ((userId, minutes) in waitUsers) {
            // Only waits not yet recorded, so a long wait costs one notice, not a write attempt on every tick.
            val waiting = query("""SELECT id FROM jobs j WHERE user_id = ? AND state IN ('WAITING', 'QUEUED', 'LEASED') AND started_at IS NULL AND created_at <= ?
                AND NOT EXISTS (SELECT 1 FROM notices n WHERE n.user_id = j.user_id AND n.key = 'queue-wait:' || j.id)""",
                userId, now - minutes * MINUTE) { it.getString(1) }
            for (id in waiting) {
                val job = jobOf(id) ?: continue
                record(userId, RuleCatalog.QUEUE_WAIT, "queue-wait:${job.id}", now, "排队等待过久：${job.label()}",
                    "建好后 ${(now - job.createdAt) / MINUTE} 分钟还没开始", job.id, job.deviceId, job.accountId, job.planId)?.let { recorded(userId, it) }
            }
        }
        val offlineUsers = query("SELECT user_id, threshold FROM event_rules WHERE rule_id = ? AND enabled = 1", RuleCatalog.DEVICE_OFFLINE) { it.getString(1) to it.getLong(2) }
        for ((userId, minutes) in offlineUsers) {
            val devices = query("""SELECT id, name, last_seen_at FROM devices d WHERE user_id = ? AND revoked = 0 AND last_seen_at IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM notices n WHERE n.user_id = d.user_id AND n.key = 'device-offline:' || d.id || ':' || d.last_seen_at)""", userId) {
                Triple(it.getString(1), it.getString(2), it.getLong(3)) }
            for ((id, name, seen) in devices) {
                if (online(id) || maxOf(seen, startedAt) > now - minutes * MINUTE) continue
                // The spell is named by when the device was last seen: it reconnects, and the next spell is a new one.
                record(userId, RuleCatalog.DEVICE_OFFLINE, "device-offline:$id:$seen", now, "设备掉线：$name",
                    "已经 ${(now - seen) / MINUTE} 分钟没有连接", null, id, null, null)?.let { recorded(userId, it) }
            }
        }
    }

    /** Records one notice unless its occurrence was already recorded; keeps the newest [KEPT] per user. */
    private fun Database.record(userId: String, ruleId: String, key: String, at: Long, title: String, detail: String, jobId: String?, deviceId: String?,
        accountId: String?, planId: String?): NoticeView? {
        val id = UUID.randomUUID().toString()
        if (update("""INSERT OR IGNORE INTO notices (id, user_id, rule_id, key, at, title, detail, job_id, device_id, account_id, plan_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, userId, ruleId, key.take(200), at, title.take(200), detail.take(500), jobId, deviceId, accountId, planId) == 0) return null
        // Each channel subscribed to the rule gets the notice; sending happens apart, on the channels' own loop.
        with(channels) { enqueue(userId, id, ruleId, title, at) }
        update("""DELETE FROM notices WHERE user_id = ? AND id NOT IN (SELECT id FROM notices WHERE user_id = ? ORDER BY at DESC, id LIMIT $KEPT)""", userId, userId)
        return single("SELECT id, rule_id, at, title, detail, job_id, device_id, account_id, plan_id FROM notices WHERE id = ?", id) { notice(it) }
    }

    companion object {
        const val KEPT = 500
        private const val MINUTE = 60_000L
        private val TERMINAL = setOf(JobState.COMPLETED, JobState.FAILED, JobState.UNKNOWN, JobState.CANCELLED, JobState.SKIPPED)
        private val OUTCOME_NAMES = mapOf(PlanOutcome.COMPLETED to "全部完成", PlanOutcome.PARTIAL_FAILED to "部分失败",
            PlanOutcome.FAILED to "失败", PlanOutcome.CANCELLED to "已取消")
    }
}
