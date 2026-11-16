package io.github.yysassist.core.content

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TaskRepository(val owner: String, val name: String) {
    init { require(owner.matches(Regex("[A-Za-z0-9-]+")) && name.matches(Regex("[A-Za-z0-9_.-]+"))) }
    val fullName: String get() = "$owner/$name"
}
data class RemoteTaskVersion(val version: String, val assetId: Long, val bytes: Long, val sha256: String)
enum class TaskSourceProblem { NETWORK, HTTP, REPOSITORY_UNAVAILABLE, INVALID_RESPONSE, INVALID_CONTENT }
class TaskSourceException(val problem: TaskSourceProblem, message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    IOException(message, cause)

/** Public, read-only Releases adapter. No tokens, repository writes, source-archive guesses or mirror fallback. */
class GitHubTaskSource(
    private val repository: TaskRepository,
    private val apiBase: HttpUrl,
    private val calls: Call.Factory,
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Repository(val full_name: String)
    @Serializable private data class Release(val tag_name: String, val draft: Boolean, val prerelease: Boolean, val assets: List<Asset>)
    @Serializable private data class Asset(val id: Long, val name: String, val state: String, val size: Long, val digest: String? = null)
    private data class Payload(val code: Int, val bytes: ByteArray)

    suspend fun latest(): RemoteTaskVersion? {
        val response = get("releases/latest")
        if (response.code == 404) {
            // GitHub also uses 404 for inaccessible repositories. Verify access before claiming absence.
            val repo = get("")
            if (repo.code == 404) throw TaskSourceException(TaskSourceProblem.REPOSITORY_UNAVAILABLE, "任务仓库不存在或当前不可访问", 404)
            ensureSuccess(repo)
            val identity = decode<Repository>(repo.bytes)
            if (!identity.full_name.equals(repository.fullName, ignoreCase = true)) invalidResponse("任务仓库身份不一致")
            val releases = get("releases", "per_page" to "1")
            ensureSuccess(releases)
            val found = decode<List<Release>>(releases.bytes)
            if (found.any { !it.draft && !it.prerelease }) invalidResponse("任务源的最新版本信息不一致，请重新拉取")
            return null
        }
        ensureSuccess(response)
        val release = decode<Release>(response.bytes)
        if (release.draft || release.prerelease || release.tag_name.isBlank()) invalidResponse("任务源没有返回有效的正式版本")
        val assets = release.assets.filter { it.name == BUNDLE_ASSET }
        if (assets.size != 1) invalidResponse("远端版本未提供完整任务文件")
        val asset = assets.single()
        val digest = asset.digest?.removePrefix("sha256:")
        if (asset.id <= 0 || asset.size <= 0 || asset.state != "uploaded" ||
            asset.digest?.startsWith("sha256:") != true || digest?.matches(Regex("[0-9a-f]{64}")) != true)
            invalidResponse("远端任务文件缺少有效摘要或尚未上传完成")
        return RemoteTaskVersion(release.tag_name, asset.id, asset.size, digest)
    }

    suspend fun download(version: RemoteTaskVersion): String {
        val response = get("releases/assets/${version.assetId}", accept = "application/octet-stream")
        ensureSuccess(response)
        val digest = MessageDigest.getInstance("SHA-256").digest(response.bytes).joinToString("") { "%02x".format(it) }
        if (response.bytes.size.toLong() != version.bytes || digest != version.sha256)
            throw TaskSourceException(TaskSourceProblem.INVALID_CONTENT, "下载的任务文件不完整或摘要不匹配")
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response.bytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw TaskSourceException(TaskSourceProblem.INVALID_CONTENT, "任务文件不是有效的 UTF-8 文本", cause = error)
        }
    }

    private suspend fun get(path: String, query: Pair<String, String>? = null, accept: String = "application/vnd.github+json"): Payload {
        val url = apiBase.newBuilder().addPathSegments("repos/${repository.owner}/${repository.name}")
        if (path.isNotEmpty()) url.addPathSegments(path)
        query?.let { url.addQueryParameter(it.first, it.second) }
        val request = Request.Builder().url(url.build()).header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "YysAssist-Android").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        TaskSourceException(TaskSourceProblem.NETWORK, "无法连接任务源，请检查网络设置", cause = e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val payload = response.use { Payload(it.code, if (it.code == 200) it.body.bytes() else byteArrayOf()) }
                        if (continuation.isActive) continuation.resume(payload)
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            TaskSourceException(TaskSourceProblem.NETWORK, "任务源响应未完整读取", cause = error))
                    }
                }
            })
        }
    }
    private inline fun <reified T> decode(bytes: ByteArray): T = try { json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) }
        catch (error: IllegalArgumentException) { throw TaskSourceException(TaskSourceProblem.INVALID_RESPONSE, "任务源返回了无法识别的数据", cause = error) }
    private fun ensureSuccess(payload: Payload) {
        if (payload.code != 200) throw TaskSourceException(TaskSourceProblem.HTTP, "任务源请求失败（HTTP ${payload.code}）", payload.code)
    }
    private fun invalidResponse(message: String): Nothing = throw TaskSourceException(TaskSourceProblem.INVALID_RESPONSE, message)
    companion object { const val BUNDLE_ASSET = "task-bundle.json" }
}
