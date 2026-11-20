package io.github.yysassist.server

import io.github.yysassist.core.content.GitHubTaskSource
import io.github.yysassist.core.content.TaskResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

data class HostAsset(val name: String, val bytes: Long, val sha256: String?)
data class HostRelease(val id: Long, val tag: String, val draft: Boolean, val assets: List<HostAsset>)

/** Where task versions are published. A release is created as a draft, filled, then published, so none is seen half-made. */
interface ReleaseHost {
    /** Every release, newest first, drafts included. */
    fun releases(): List<HostRelease>
    fun create(tag: String, body: String): Long
    fun upload(release: Long, name: String, bytes: ByteArray, type: String)
    fun publish(release: Long, latest: Boolean)
    fun discard(release: Long)
}

/** The task repository's Releases, with the server's own token. Failures become 502s that say what GitHub answered. */
class GitHubReleaseHost(private val repository: String, private val token: String) : ReleaseHost {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private fun send(method: String, url: String, body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(), type: String = "application/json",
                     timeout: Long = 30): JsonElement? {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(timeout)).header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token").header("User-Agent", "hgb-workbench").header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", type).method(method, body).build()
        val reply = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw ApiException(502, "github_unavailable", "连不上 GitHub：${it.message}") }
        if (reply.statusCode() !in 200..299) throw ApiException(502, "github_failed", "GitHub 拒绝了请求（HTTP ${reply.statusCode()}）")
        return reply.body().takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement)
    }
    private val api = "https://api.github.com/repos/$repository"

    override fun releases(): List<HostRelease> = (1..10).asSequence().map { page ->
        (send("GET", "$api/releases?per_page=100&page=$page") as? JsonArray).orEmpty()
    }.takeWhile { it.isNotEmpty() }.flatten().map { release ->
        val o = release.jsonObject
        HostRelease(o.getValue("id").jsonPrimitive.long, o.getValue("tag_name").jsonPrimitive.content, o["draft"]?.jsonPrimitive?.boolean == true,
            (o["assets"] as? JsonArray).orEmpty().map { a -> a.jsonObject.let {
                HostAsset(it.getValue("name").jsonPrimitive.content, it.getValue("size").jsonPrimitive.long,
                    it["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.takeIf { d -> it["state"]?.jsonPrimitive?.content == "uploaded" && d.length == 64 })
            } })
    }.toList()

    override fun create(tag: String, body: String): Long = send("POST", "$api/releases", HttpRequest.BodyPublishers.ofString(buildJsonObject {
        put("tag_name", tag); put("name", tag); put("body", body); put("draft", true); put("make_latest", "false")
    }.toString()))!!.jsonObject.getValue("id").jsonPrimitive.long

    override fun upload(release: Long, name: String, bytes: ByteArray, type: String) {
        send("POST", "https://uploads.github.com/repos/$repository/releases/$release/assets?name=${URLEncoder.encode(name, Charsets.UTF_8)}",
            HttpRequest.BodyPublishers.ofByteArray(bytes), type, timeout = 120)
    }
    override fun publish(release: Long, latest: Boolean) {
        send("PATCH", "$api/releases/$release", HttpRequest.BodyPublishers.ofString(buildJsonObject {
            put("draft", false); put("make_latest", latest.toString()) }.toString()))
    }
    override fun discard(release: Long) { runCatching { send("DELETE", "$api/releases/$release") } }
}

/**
 * [minApp]: the oldest App that runs this version; Apps older than it keep the version they have. [latest]: also make
 * it GitHub's "latest" release, which Apps from before [GitHubTaskSource.MIN_APP_PREFIX] existed read; leave it off
 * while such Apps are still around and the version needs a newer one.
 */
@Serializable
data class PublishRequest(val version: String, val changelog: String, val minApp: String, val latest: Boolean = false, val dryRun: Boolean = false)
@Serializable
data class PublishedAsset(val id: String, val assetName: String, val bytes: Long, val release: String? = null)
@Serializable
data class PublishResult(val version: String, val published: Boolean, val bundleBytes: Int, val uploads: List<PublishedAsset>,
    val reused: List<PublishedAsset>, val uploadBytes: Long)

/** Publishes a developer's draft as a task version: checked exactly as the App reads it, with only new assets uploaded. */
class Publisher(private val workbench: Workbench, private val drafts: Drafts, private val assets: Assets, private val host: ReleaseHost?,
                private val clock: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Synchronized fun publish(userId: String, target: String, request: PublishRequest): PublishResult {
        val host = host ?: throw ApiException(503, "workbench_unconfigured", "服务端还没有配置任务仓库，不能发布")
        if (!request.version.matches(Regex("v?[0-9]+(\\.[0-9]+){1,3}"))) throw ApiException(400, "bad_version", "版本号形如 v1.2.0")
        if (!request.minApp.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) throw ApiException(400, "bad_min_app", "最低 App 版本形如 0.2.0")
        // The App reads the last such line, which is ours; one in the notes is dropped all the same.
        val changelog = request.changelog.lines().filterNot { it.trim().startsWith(GitHubTaskSource.MIN_APP_PREFIX) }.joinToString("\n").trim().take(2000)
            .ifEmpty { throw ApiException(400, "no_changelog", "请写更新说明") }
        var releases = host.releases()
        val taken = releases.filter { it.tag == request.version }
        if (taken.any { !it.draft }) throw ApiException(409, "version_taken", "版本 ${request.version} 已存在")
        // A draft of the same version is what an interrupted publish left; publishes run one at a time, so it is ours to clear.
        if (taken.isNotEmpty() && !request.dryRun) { taken.forEach { host.discard(it.id) }; releases = host.releases() }
        // Published copies of each asset by content, newest first.
        val published = releases.filter { !it.draft }.flatMap { r -> r.assets.filter { it.sha256 != null }.map { r.tag to it } }
            .groupBy { (_, a) -> a.sha256 to a.bytes }
        val claimed = mutableSetOf<String>()
        val body = drafts.get(userId, target).body
        val resources = (body["resources"] as? JsonArray)?.let { json.decodeFromJsonElement(ListSerializer(TaskResource.serializer()), it) }.orEmpty()
        val uploads = mutableListOf<Pair<TaskResource, java.io.File>>()
        val placed = resources.map { resource ->
            // Same content under the same name first; two resources never share one attachment name.
            published[resource.sha256 to resource.bytes].orEmpty().filter { (_, a) -> a.name !in claimed }
                .sortedByDescending { (_, a) -> a.name == resource.assetName }.firstOrNull()?.let { (tag, asset) ->
                    claimed += asset.name
                    return@map resource.copy(assetName = asset.name, release = tag)
                }
            claimed += resource.assetName
            val file = assets.publishable(userId, target, resource.sha256)?.takeIf { it.length() == resource.bytes }
                ?: throw ApiException(400, "missing_asset", "素材 ${resource.id} 既没有已发布的附件，也没有裁好的模板文件")
            uploads += resource.copy(release = null) to file
            resource.copy(release = null)
        }
        val bundle = JsonObject(body + mapOf("version" to JsonPrimitive(request.version), "updatedAt" to JsonPrimitive(Instant.ofEpochMilli(clock()).toString()),
            "changelog" to JsonPrimitive(changelog), "resources" to json.encodeToJsonElement(ListSerializer(TaskResource.serializer()), placed)))
        val text = Json.encodeToString(JsonObject.serializer(), bundle)
        // Apps from before resources could live in another release read GitHub's latest one and would reject this.
        if (request.latest && placed.any { it.release != null })
            throw ApiException(400, "latest_needs_own_assets", "设为最新版时旧 App 会读它，但它引用了别的版本的素材")
        val checked = workbench.check(text)
        if (!checked.ok) throw ApiException(400, "invalid_draft", "草稿没通过检查：" + checked.issues.take(5).joinToString("；") { "${it.location} ${it.reason}" })
        val result = PublishResult(request.version, false, text.toByteArray().size, uploads.map { (r, _) -> PublishedAsset(r.id, r.assetName, r.bytes) },
            placed.filter { it.release != null }.map { PublishedAsset(it.id, it.assetName, it.bytes, it.release) }, uploads.sumOf { it.first.bytes })
        if (request.dryRun) return result
        val release = host.create(request.version, "$changelog\n\n${GitHubTaskSource.MIN_APP_PREFIX} ${request.minApp}")
        try {
            uploads.forEach { (resource, file) -> host.upload(release, resource.assetName, file.readBytes(), "image/png") }
            host.upload(release, GitHubTaskSource.BUNDLE_ASSET, text.toByteArray(), "application/json")
            host.publish(release, request.latest)
        } catch (failure: Exception) {
            // Only while it is still a draft: a publish that timed out may have gone through, and Apps may be reading it.
            if (runCatching { host.releases().any { it.id == release && it.draft } }.getOrDefault(false)) host.discard(release)
            throw failure
        }
        return result.copy(published = true)
    }
}
