package io.github.yysassist.server

import io.github.yysassist.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the workbench asks a phone to do.
 * - `sample`: capture the game and add it to the samples as [name].
 * - `probe`: check [states] (library names or full states) on the screen as it is now.
 * - `judge`: the same on [sample], shown to the phone's recognizer as a still screen.
 * - `trial`: run [task] of the draft (only [step] when given) once with [configuration].
 */
@Serializable
data class DevStart(val kind: String, val target: String, val device: String, val name: String = "", val task: String? = null, val step: String? = null,
    val configuration: JsonObject = JsonObject(emptyMap()), val sample: String? = null, val states: JsonArray? = null)
@Serializable
data class DevRunView(val id: String, val kind: String, val device: String, val target: String, val state: String, val detail: String = "",
    val createdAt: Long, val updatedAt: Long, val sample: String? = null, val facts: List<DevFact> = emptyList(), val traces: List<DevTrace> = emptyList(),
    val task: String? = null, val step: String? = null)
/** The newest finished trial of one task: what the workbench's task list shows. [digest]: the task's node digest when it ran. */
@Serializable
data class TrialRecord(val task: String, val step: String? = null, val device: String, val state: String, val detail: String,
    val startedAt: Long, val endedAt: Long, val digest: String)

/**
 * Phone commands for the developer workbench. They travel on the job channel and exclude managed jobs: one is only sent
 * to a connected, idle phone, and while it runs the phone reports itself busy, so no job is leased to it. Runs are kept
 * in memory for a while: they are working results for the author, not records.
 */
class DevRuns(private val workbench: Workbench, private val service: CloudService, private val clock: () -> Long = System::currentTimeMillis) {
    private val db get() = workbench.db
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private class Run(val userId: String, @Volatile var view: DevRunView, @Volatile var command: DevCommand, val digest: String? = null) { @Volatile var sentAt = 0L }
    private val runs = ConcurrentHashMap<String, Run>()

    /** Which bucket a start counts against: trials are long, the rest are quick looks. */
    fun limitKind(start: DevStart) = if (start.kind == "trial") "trial" else "probe"

    fun start(userId: String, start: DevStart): DevRunView {
        val kind = start.kind
        if (kind !in setOf("sample", "recognize", "probe", "judge", "trial")) throw ApiException(400, "bad_kind", "不认识的命令")
        // The phone first: nothing is compiled for a phone that is not the caller's, offline or busy.
        ensureFree(userId, start.device, clock())
        var digest: String? = null
        val (devKind, bundle, task) = when (kind) {
            "sample" -> Triple(DevKind.SAMPLE, null, null)
            "recognize" -> {
                workbench.assets.sample(userId, start.sample ?: throw ApiException(400, "no_sample", "请选择样本"))
                Triple(DevKind.RECOGNIZE, null, null)
            }
            "probe", "judge" -> {
                val states = start.states?.takeIf { it.isNotEmpty() } ?: throw ApiException(400, "no_states", "请选择要判定的状态")
                if (kind == "judge") workbench.assets.sample(userId, start.sample ?: throw ApiException(400, "no_sample", "请选择样本"))
                Triple(DevKind.RUN, workbench.heavy { probe(userId, start.target, states) }, PROBE_TASK)
            }
            else -> {
                val id = start.task ?: throw ApiException(400, "no_task", "请选择任务")
                // One read of the draft: what runs and what its digest says ran are the same.
                val body = workbench.drafts.get(userId, start.target).body
                digest = trialDigest(body, id)
                Triple(DevKind.RUN, workbench.heavy { trial(userId, start.target, id, start.step, body) }, id)
            }
        }
        val now = clock()
        val id = UUID.randomUUID().toString()
        val withSample = kind == "judge" || kind == "recognize"
        val view = DevRunView(id, kind, start.device, start.target, "SENT", "", now, now, start.sample.takeIf { withSample },
            task = start.task.takeIf { kind == "trial" }, step = start.step.takeIf { kind == "trial" })
        val command = DevCommand(id, devKind, start.target, start.name.trim().take(64), bundle, task, start.configuration,
            start.sample.takeIf { withSample }, timeoutOf(kind))
        // Claiming the phone is the only part done one at a time, so two starts cannot both find it free.
        synchronized(this) {
            ensureFree(userId, start.device, now)
            prune(now)
            if (runs.size >= MAX_RUNS) throw ApiException(503, "busy", "服务器忙，请稍后再试")
            runs[id] = Run(userId, view, command, digest).also { it.sentAt = now }
            db.tx { update("UPDATE devices SET phase = 'DEVELOPING' WHERE id = ?", start.device) }
        }
        if (!service.sockets.send(start.device, service.encode(ServerMessage.Dev(command)))) {
            runs.remove(id); throw ApiException(409, "device_offline", "手机不在线")
        }
        publish(userId, view)
        return view
    }

    /** A trial runs a whole task; a sample uploads a full screenshot, which takes minutes on a weak link; a probe is quick. */
    private fun timeoutOf(kind: String) = when (kind) { "trial" -> 600_000L; "sample", "recognize" -> 300_000L; else -> 60_000L }

    private fun ensureFree(userId: String, device: String, now: Long) {
        db.tx {
            val phase = single("SELECT phase FROM devices WHERE id = ? AND user_id = ? AND revoked = 0", device, userId) { it.getString(1).orEmpty() }
                ?: throw ApiException(404, "no_device", "没有这台手机")
            if (!service.sockets.isOnline(device)) throw ApiException(409, "device_offline", "手机不在线")
            if (single("SELECT 1 FROM jobs WHERE device_id = ? AND state IN ('LEASED', 'RUNNING') LIMIT 1", device) { true } == true || phase != "IDLE")
                throw ApiException(409, "device_busy", "手机正在执行任务，等它空下来")
        }
        if (runs.values.any { it.view.device == device && active(it.view, now) }) throw ApiException(409, "device_busy", "手机上还有一个调试命令没结束")
    }

    /**
     * Still holding its phone: running within its time, or sent but not yet started for a short while (an App too old
     * to know the command never starts it).
     */
    private fun active(view: DevRunView, now: Long) = when (view.state) {
        "SENT" -> now - view.updatedAt < START_WITHIN
        "RUNNING" -> now - view.updatedAt < timeoutOf(view.kind) + GRACE
        else -> false
    }

    /**
     * Commands sent to [deviceId] that it has not started, to send again when it reports itself idle: the first send may
     * have gone into a connection that was already gone. The phone runs each command id once, so a resend is harmless.
     */
    fun unstarted(userId: String, deviceId: String): List<ServerMessage> {
        val now = clock()
        return runs.values.filter { it.userId == userId && it.view.device == deviceId && it.view.state == "SENT" && active(it.view, now) && now - it.sentAt >= RESEND_AFTER }
            .map { it.sentAt = now; ServerMessage.Dev(it.command) }
    }

    fun get(userId: String, id: String): DevRunView = runs[id]?.takeIf { it.userId == userId }?.let(::settled) ?: throw ApiException(404, "no_run", "没有这个调试命令")
    fun list(userId: String): List<DevRunView> = runs.values.filter { it.userId == userId }.map(::settled).sortedByDescending { it.createdAt }

    /** A command the phone never started, even after resending, is over: the author is told rather than left waiting. */
    private fun settled(run: Run): DevRunView = synchronized(run) {
        val view = run.view
        if (view.state != "SENT" || active(view, clock())) return view
        view.copy(state = "FAILED", detail = "手机没收到命令（连接可能刚断过），请重试", updatedAt = clock()).also { run.view = it }
    }

    /** From the phone the command was sent to; a finished run is not changed again. */
    fun report(caller: Caller, id: String, report: DevReport): DevRunView {
        if (caller.kind != SessionKind.DEVICE) throw ApiException(403, "device_only", "只有手机能上报")
        val run = runs[id]?.takeIf { it.userId == caller.userId && it.view.device == caller.deviceId } ?: throw ApiException(404, "no_run", "没有这个调试命令")
        val next = synchronized(run) {
            if (run.view.state !in ACTIVE) return run.view
            // Started: the bundle was only kept for resending.
            run.command = run.command.copy(bundle = null)
            run.view.copy(state = report.state.name, detail = report.detail.take(1000), updatedAt = clock(), sample = report.sample ?: run.view.sample,
                facts = report.facts.take(200).map { it.copy(type = it.type.take(60), detail = it.detail.take(400)) },
                traces = report.traces.takeLast(500).map { it.copy(reason = it.reason?.take(600)) }).also { run.view = it }
        }
        if (next.state !in ACTIVE && next.kind == "trial" && next.task != null && run.digest != null) remember(caller.userId, next, run.digest)
        publish(caller.userId, next)
        return next
    }

    /** A trial passed when the task reports itself completed; one that ran to its end without that did not. */
    private fun outcome(view: DevRunView) = if (view.state == "FINISHED" && view.facts.any { it.type == "task-completed" }) "FINISHED" else "FAILED"

    /** Keeps the finished trial as its task's newest one. */
    private fun remember(userId: String, view: DevRunView, digest: String) = db.tx {
        update("""INSERT INTO dev_trials (user_id, target_id, task_id, step_id, device_id, state, detail, started_at, ended_at, digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, target_id, task_id) DO UPDATE SET step_id = excluded.step_id, device_id = excluded.device_id, state = excluded.state,
            detail = excluded.detail, started_at = excluded.started_at, ended_at = excluded.ended_at, digest = excluded.digest
            WHERE excluded.started_at >= dev_trials.started_at""",
            userId, view.target, view.task, view.step, view.device, outcome(view), view.detail.take(300), view.createdAt, view.updatedAt, digest)
    }

    /** Each task's newest finished trial in [target]. */
    fun trials(userId: String, target: String): List<TrialRecord> = db.tx {
        query("SELECT task_id, step_id, device_id, state, detail, started_at, ended_at, digest FROM dev_trials WHERE user_id = ? AND target_id = ? ORDER BY ended_at DESC",
            userId, target) { TrialRecord(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6), it.getLong(7), it.getString(8)) }
    }

    private fun publish(userId: String, view: DevRunView) = service.events.publish(userId, "dev-run", json.encodeToString(DevRunView.serializer(), view))
    private fun prune(now: Long) {
        runs.entries.removeIf { now - it.value.view.updatedAt > KEEP }
        runs.values.groupBy { it.userId }.values.forEach { mine -> mine.sortedByDescending { it.view.createdAt }.drop(MAX_PER_USER).forEach { runs.remove(it.view.id) } }
    }

    // ---- Compiling what a phone runs ----

    /** [body] with its resources placed, a throwaway version, checked as the App reads it. */
    private fun finish(userId: String, target: String, body: JsonObject, tasks: JsonArray, snippets: JsonArray): String {
        // A trial carries only the task it runs; the game's account tasks are left out, and so is the need for them.
        val targets = (body["targets"] as? JsonArray).orEmpty().map { JsonObject(it.jsonObject - "accounts" - "accountTasks") }
        // Only the resources this run names (its templates, the models of the vision profiles), so a template not yet
        // cut or published elsewhere in the draft does not stop it.
        val named = mutableSetOf<String>()
        fun scan(e: JsonElement) { when (e) {
            is JsonObject -> e.values.forEach(::scan); is JsonArray -> e.forEach(::scan)
            is JsonPrimitive -> if (e.isString) e.content.split('@').firstOrNull()?.let(named::add)
            else -> Unit } }
        listOf(tasks, snippets, JsonArray(targets), body["visionProfiles"] ?: JsonArray(emptyList())).forEach(::scan)
        val used = JsonObject(body + ("resources" to JsonArray((body["resources"] as? JsonArray).orEmpty().filter {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull in named })))
        val (placed, _) = workbench.publisher.place(userId, target, used, workbench.publisher.releases())
        val bundle = JsonObject(body + mapOf("targets" to JsonArray(targets),"version" to JsonPrimitive("dev-${clock()}"), "updatedAt" to JsonPrimitive(java.time.Instant.ofEpochMilli(clock()).toString()),
            "changelog" to JsonPrimitive("调试"), "tasks" to tasks, "snippets" to snippets,
            "resources" to json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(io.github.yysassist.core.content.TaskResource.serializer()), placed)))
        val text = json.encodeToString(JsonObject.serializer(), bundle)
        val checked = workbench.checkNow(text)
        if (!checked.ok) throw ApiException(400, "invalid_draft", "草稿没通过检查：" + checked.issues.take(5).joinToString("；") { "${it.location} ${it.reason}" })
        return text
    }

    /** Only [taskId] (and only [stepId] of it when given) with the snippets it uses. */
    fun trial(userId: String, target: String, taskId: String, stepId: String?, body: JsonObject = workbench.drafts.get(userId, target).body): String {
        val task = (body["tasks"] as? JsonArray).orEmpty().map { it.jsonObject }.singleOrNull { it["id"]?.jsonPrimitive?.contentOrNull == taskId }
            ?: throw ApiException(404, "no_task", "草稿里没有任务 $taskId")
        val trimmed = if (stepId == null) task else {
            val step = (task["steps"] as? JsonArray).orEmpty().singleOrNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == stepId }
                ?: throw ApiException(404, "no_step", "任务里没有步骤 $stepId")
            JsonObject(task + ("steps" to JsonArray(listOf(step))))
        }
        // The game's interruption pages guard every page wait, so their handlers travel with the task.
        return finish(userId, target, body, JsonArray(listOf(trimmed)), used(body, JsonArray(listOf(trimmed, interruptions(body, target)))))
    }

    /** The interruption pages [target] declares in [body]. */
    private fun interruptions(body: JsonObject, target: String): JsonArray =
        (((body["targets"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == target }
            ?.get("library") as? JsonObject)?.get("interruptions") as? JsonArray) ?: JsonArray(emptyList())

    /**
     * What a trial of [taskId] ran: the task and every snippet it calls, by id (so their order does not matter). A
     * change to either makes an earlier trial stale. The workbench computes the same (model.ts trialDigest).
     */
    fun trialDigest(body: JsonObject, taskId: String): String? {
        val task = (body["tasks"] as? JsonArray).orEmpty().firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == taskId } ?: return null
        val pages = interruptions(body, task.jsonObject["targetId"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val snippets = used(body, JsonArray(listOf(task, pages))).associateBy { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return nodeDigest(buildJsonObject { put("task", task); put("snippets", JsonObject(snippets)); if (pages.isNotEmpty()) put("interruptions", pages) })
    }

    /** The snippets [root] calls, directly or through other snippets. */
    private fun used(body: JsonObject, root: JsonElement): JsonArray {
        val all = (body["snippets"] as? JsonArray).orEmpty().map { it.jsonObject }.associateBy { it["id"]?.jsonPrimitive?.contentOrNull }
        val seen = linkedSetOf<String>()
        fun scan(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (key, value) ->
                    val name = (value as? JsonPrimitive)?.takeIf { key == "use" && it.isString }?.content
                    if (name != null && seen.add(name)) all[name]?.let(::scan)
                    scan(value)
                }
                is JsonArray -> e.forEach(::scan)
                else -> Unit
            }
        }
        scan(root)
        return JsonArray(seen.mapNotNull { all[it] })
    }

    /** One step that waits briefly for any of [states]: it reports the one that held, or what each missed. */
    fun probe(userId: String, target: String, states: JsonArray): String {
        val body = workbench.drafts.get(userId, target).body
        val game = (body["targets"] as? JsonArray).orEmpty().map { it.jsonObject }.singleOrNull { it["id"]?.jsonPrimitive?.contentOrNull == target }
            ?: throw ApiException(404, "no_target", "草稿里没有游戏 $target")
        val category = (game["categories"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("id") ?: throw ApiException(400, "no_category", "游戏还没有分类")
        val profile = (body["visionProfiles"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull { it["targetId"]?.jsonPrimitive?.contentOrNull == target }
            ?.get("id") ?: throw ApiException(400, "no_profile", "游戏还没有视觉配置")
        val defaults = (game["library"] as? JsonObject)?.get("defaults") as? JsonObject
        val given = listOfNotNull(defaults?.get("*") as? JsonObject, defaults?.get("screen.awaitScreen") as? JsonObject).flatMap { it.keys }.toSet()
        val inputs = buildJsonObject {
            put("profile", profile); put("states", states); put("timeoutMs", 3000); put("intervalMs", 500); put("stableMs", 0)
            // The game's own reference size wins when its library sets one.
            if ("referenceWidth" !in given) put("referenceWidth", 2400)
            if ("referenceHeight" !in given) put("referenceHeight", 1080)
        }
        val task = buildJsonObject {
            put("id", PROBE_TASK); put("targetId", target); put("name", "判定"); put("category", category); put("priority", 1)
            putJsonArray("steps") { addJsonObject { put("id", "look"); put("name", "看画面"); putJsonArray("program") { addJsonObject {
                put("type", "action"); putJsonObject("action") { put("id", "look"); put("capability", "screen.awaitScreen"); put("contractVersion", 1); put("inputs", inputs) }
            } } } }
        }
        // A probe judges only the states it was given: without the game's interruption pages, whose handlers it does not carry.
        val plain = JsonObject(body + ("targets" to JsonArray((body["targets"] as? JsonArray).orEmpty().map { t ->
            val library = t.jsonObject["library"] as? JsonObject
            if (library == null) t else JsonObject(t.jsonObject + ("library" to JsonObject(library - "interruptions")))
        })))
        return finish(userId, target, plain, JsonArray(listOf(task)), JsonArray(emptyList()))
    }

    companion object {
        const val PROBE_TASK = "workbench-probe"
        private val ACTIVE = setOf("SENT", "RUNNING")
        private const val GRACE = 60_000L
        /** How long a sent command may wait for its phone to start it (resent meanwhile) before the phone counts as free. */
        /** How long an unstarted command holds its phone and is resent: on a weak link reconnecting alone can take a minute. */
        private const val START_WITHIN = 180_000L
        private const val RESEND_AFTER = 5_000L
        private const val KEEP = 6 * 60 * 60 * 1000L
        private const val MAX_PER_USER = 10
        private const val MAX_RUNS = 2_000
    }
}
