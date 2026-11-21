package io.github.yysassist.cloud

import io.github.yysassist.core.cloud.CloudAgent
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.protocol.DevReport
import io.github.yysassist.protocol.DevState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URLEncoder

/** A page-wait in the draft: an awaitScreen action and the states it waits for. */
internal data class DraftWait(val label: String, val states: JsonArray)
/** A task in the draft with its steps, for trial runs. */
internal data class DraftTask(val id: String, val name: String, val steps: List<Pair<String, String>>)
/** One condition of a probed state, from the phone's own look. [pass] is null for text never read (ruled out earlier). */
internal data class ProbeCondition(val state: String, val kind: String, val expect: String, val within: List<Int>, val score: Double?,
    val minimum: Double?, val pass: Boolean?, val count: Int?)
internal data class ProbeResult(val matched: String?, val reason: String?, val conditions: List<ProbeCondition>,
    val referenceWidth: Int = 2400, val referenceHeight: Int = 1080)
internal data class DraftView(val revision: Long, val body: JsonObject)

/**
 * The phone's side of the developer workbench: whether this account may use it, what the draft holds, and commands
 * this phone sends itself through the server (so they go through the same checks and the same runner).
 */
internal class DevWorkbench(private val cloud: () -> CloudAgent, private val runner: () -> DevRunner?) {
    private val mutableDeveloper = MutableStateFlow<Boolean?>(null)
    /** True for a GitHub account with write access to the task repository; null until asked. */
    val developer: StateFlow<Boolean?> = mutableDeveloper.asStateFlow()
    private fun path(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    suspend fun refresh(): Boolean? {
        // Only a definite answer changes it: GitHub unreachable (503), rate limits and network trouble leave it as it was.
        val answer = try { cloud().request("GET", "/api/dev/catalog"); true }
        catch (refused: CloudRequestException) { if (refused.status == 403) false else null }
        catch (_: java.io.IOException) { null }
        if (answer != null) mutableDeveloper.value = answer
        return answer
    }

    fun forget() { mutableDeveloper.value = null }

    suspend fun draft(target: String): JsonObject? = draftView(target)?.body
    /** The draft with its revision; null when there is none yet. */
    suspend fun draftView(target: String): DraftView? = try {
        cloud().request("GET", "/api/dev/drafts/${path(target)}")?.let { Json.parseToJsonElement(it).jsonObject }?.let { o ->
            DraftView(o["revision"]?.jsonPrimitive?.longOrNull ?: 0, o["body"]?.jsonObject ?: return null) }
    } catch (refused: CloudRequestException) { if (refused.status == 404) null else throw refused }

    /** Every page-wait in the draft's tasks and snippets, labelled by where it is. */
    fun waits(draft: JsonObject): List<DraftWait> = buildList {
        fun scan(e: JsonElement, where: String) {
            when (e) {
                is JsonObject -> {
                    val action = e["action"] as? JsonObject
                    if (action?.get("capability")?.jsonPrimitive?.contentOrNull == "screen.awaitScreen") {
                        val states = (action["inputs"] as? JsonObject)?.get("states") as? JsonArray
                        // A wait inside a snippet or forEach may depend on parameters only known when it is called: not probed alone.
                        if (states != null && states.isNotEmpty() && !parameterized(states))
                            add(DraftWait(listOf(action["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), where.trim()).filter { it.isNotEmpty() }.joinToString(" · "), states))
                    }
                    val name = e["name"]?.jsonPrimitive?.contentOrNull?.takeIf { "steps" in e || "program" in e || "use" in e }
                    e.values.forEach { scan(it, name ?: where) }
                }
                is JsonArray -> e.forEach { scan(it, where) }
                else -> Unit
            }
        }
        (draft["tasks"] as? JsonArray)?.let { scan(it, "") }
        (draft["snippets"] as? JsonArray)?.forEach { snippet -> scan(snippet, "片段 " + snippet.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
    }.distinctBy { it.states }
    private fun parameterized(e: JsonElement): Boolean = when (e) {
        is JsonObject -> e.keys.any { it.startsWith("$") } || e.values.any(::parameterized)
        is JsonArray -> e.any(::parameterized)
        else -> false
    }

    fun tasks(draft: JsonObject): List<DraftTask> = (draft["tasks"] as? JsonArray).orEmpty().map { it.jsonObject }.map { task ->
        DraftTask(task["id"]?.jsonPrimitive?.content.orEmpty(), task["name"]?.jsonPrimitive?.contentOrNull ?: "",
            (task["steps"] as? JsonArray).orEmpty().map { s -> s.jsonObject.let { (it["id"]?.jsonPrimitive?.content.orEmpty()) to (it["name"]?.jsonPrimitive?.contentOrNull ?: "") } })
    }

    /** Library state names of the game, as name suggestions for samples. */
    fun stateNames(draft: JsonObject, target: String): List<String> = (draft["targets"] as? JsonArray).orEmpty().map { it.jsonObject }
        .firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == target }?.get("library")?.jsonObject?.get("states")?.jsonArray.orEmpty()
        .mapNotNull { s -> s.jsonObject.let { it["name"]?.jsonPrimitive?.contentOrNull ?: it["id"]?.jsonPrimitive?.contentOrNull } }

    /** Starts a command on this phone through the server and waits for this phone's runner to finish it. */
    suspend fun run(body: JsonObject, timeoutMs: Long): DevReport {
        val device = cloud().registeredDevice() ?: throw java.io.IOException("这台手机还没连上服务器")
        val started = cloud().request("POST", "/api/dev/runs", JsonObject(body + ("device" to JsonPrimitive(device))).toString())
            ?: throw java.io.IOException("服务器没有回应")
        val id = Json.parseToJsonElement(started).jsonObject.getValue("id").jsonPrimitive.content
        val dev = runner() ?: throw java.io.IOException("调试执行器还没准备好")
        // The server resends a command the phone has not started; if it still has not after a while, say so instead of waiting it out.
        kotlinx.coroutines.withTimeoutOrNull(START_WITHIN) { dev.last.first { it?.first == id } }
            ?: throw java.io.IOException("手机没收到命令，请检查网络后重试")
        return withTimeout(timeoutMs) { dev.last.first { it?.first == id && it.second.state != DevState.RUNNING }!!.second }
    }

    suspend fun sample(target: String, name: String): DevReport =
        run(buildJsonObject { put("kind", "sample"); put("target", target); put("name", name) }, 105_000)
    suspend fun rename(sample: String, name: String) {
        cloud().request("PATCH", "/api/dev/samples/${path(sample)}", buildJsonObject { put("name", name) }.toString())
    }
    suspend fun probe(target: String, states: JsonArray): ProbeResult = probeResult(
        run(buildJsonObject { put("kind", "probe"); put("target", target); put("states", states) }, 105_000))
    suspend fun trial(target: String, task: String, step: String?): DevReport =
        run(buildJsonObject { put("kind", "trial"); put("target", target); put("task", task); step?.let { put("step", it) } }, 11 * 60_000 + 15_000)

    companion object {
        private const val START_WITHIN = 75_000L
        /** Reads a probe run's single awaitScreen trace. */
        fun probeResult(report: DevReport): ProbeResult {
            val trace = report.traces.lastOrNull { it.capability == "screen.awaitScreen" }
            val conditions = (trace?.trace?.get("conditions") as? JsonArray).orEmpty().map { it.jsonObject }.map { c ->
                ProbeCondition(c["state"]?.jsonPrimitive?.content.orEmpty(), c["kind"]?.jsonPrimitive?.content.orEmpty(), c["expect"]?.jsonPrimitive?.content.orEmpty(),
                    (c["within"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }, c["score"]?.jsonPrimitive?.doubleOrNull,
                    c["minimum"]?.jsonPrimitive?.doubleOrNull, c["pass"]?.jsonPrimitive?.booleanOrNull, c["count"]?.jsonPrimitive?.intOrNull)
            }
            return ProbeResult(trace?.trace?.get("state")?.jsonPrimitive?.contentOrNull, trace?.reason ?: report.detail, conditions,
                trace?.trace?.get("referenceWidth")?.jsonPrimitive?.intOrNull ?: 2400, trace?.trace?.get("referenceHeight")?.jsonPrimitive?.intOrNull ?: 1080)
        }
    }
}
