package io.github.yysassist.core.content

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import io.github.yysassist.core.auth.GitHubToken
import io.github.yysassist.core.auth.GitHubAuthorization
import io.github.yysassist.core.auth.GitHubAuthException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TaskRepository(val owner: String, val name: String) {
    init { require(owner.matches(Regex("[A-Za-z0-9-]+")) && name.matches(Regex("[A-Za-z0-9_.-]+"))) }
    val fullName: String get() = "$owner/$name"
}
data class RemoteReleaseAsset(val id: Long, val name: String, val state: String, val bytes: Long, val digest: String?)
data class RemoteTaskVersion(val version: String, val assetId: Long, val bytes: Long, val sha256: String,
    val assets: List<RemoteReleaseAsset> = emptyList())
enum class TaskSourceProblem { NETWORK, HTTP, REPOSITORY_UNAVAILABLE, INVALID_RESPONSE, INVALID_CONTENT, AUTHORIZATION }
class TaskSourceException(val problem: TaskSourceProblem, message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    IOException(message, cause)

/** Read-only Releases adapter. Credentials come from the client session, never task content or URLs. */
class GitHubTaskSource(
    private val repository: TaskRepository,
    private val apiBase: HttpUrl,
    private val calls: Call.Factory,
    private val authorization: GitHubAuthorization = GitHubAuthorization { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Repository(val full_name: String)
    @Serializable private data class Release(val tag_name: String, val draft: Boolean, val prerelease: Boolean, val assets: List<Asset>)
    @Serializable private data class Asset(val id: Long, val name: String, val state: String, val size: Long, val digest: String? = null)
    private enum class ResponseOrigin { API, REDIRECT, NO_RESPONSE }
    private data class Payload(val code: Int, val bytes: ByteArray, val origin: ResponseOrigin)
    private data class Transfer(val result: Result<Unit>, val origin: ResponseOrigin)
    private fun origin(response: Response, request: Request): ResponseOrigin =
        if (response.request.url.let { it.scheme == request.url.scheme && it.host == request.url.host && it.port == request.url.port })
            ResponseOrigin.API else ResponseOrigin.REDIRECT

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
        return RemoteTaskVersion(release.tag_name, asset.id, asset.size, digest,
            release.assets.map { RemoteReleaseAsset(it.id, it.name, it.state, it.size, it.digest) })
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

    /** Streams data into caller-owned staging. Cancellation waits for the callback to stop using the sink. */
    suspend fun downloadResource(version: RemoteTaskVersion, resource: TaskResource, sink: OutputStream) {
        val asset = version.assets.filter { it.name == resource.assetName }.singleOrNull()
            ?: invalidResponse("远端版本缺少唯一的资源附件：${resource.assetName}")
        if (asset.id <= 0 || asset.state != "uploaded" || asset.bytes != resource.bytes ||
            asset.digest != "sha256:${resource.sha256}") invalidResponse("资源附件与任务清单不一致：${resource.assetName}")
        val request = request("releases/assets/${asset.id}", accept = "application/octet-stream")
        val call = calls.newCall(request)
        val completed = CompletableDeferred<Transfer>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                completed.complete(Transfer(Result.failure(TaskSourceException(TaskSourceProblem.NETWORK, "资源下载连接失败", cause = e)), ResponseOrigin.NO_RESPONSE))
            }
            override fun onResponse(call: Call, response: Response) {
                val responseOrigin = origin(response, request)
                completed.complete(Transfer(runCatching {
                    response.use {
                        if (it.code != 200) throw TaskSourceException(TaskSourceProblem.HTTP, "资源下载失败（HTTP ${it.code}）", it.code)
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        it.body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = try { input.read(buffer) }
                                catch (failure: IOException) {
                                    throw TaskSourceException(TaskSourceProblem.NETWORK, "资源响应未完整读取", cause = failure)
                                }
                                if (read == -1) break
                                if (read.toLong() > resource.bytes - count)
                                    throw TaskSourceException(TaskSourceProblem.INVALID_CONTENT, "资源超过清单声明长度")
                                sink.write(buffer, 0, read)
                                hash.update(buffer, 0, read); count += read
                            }
                        }
                        val digest = hash.digest().joinToString("") { byte -> "%02x".format(byte) }
                        if (count != resource.bytes || digest != resource.sha256)
                            throw TaskSourceException(TaskSourceProblem.INVALID_CONTENT, "资源内容不完整或摘要不匹配")
                    }
                }, responseOrigin))
            }
        })
        try {
            val transfer = completed.await()
            val failed = transfer.result.exceptionOrNull()
            if (transfer.origin == ResponseOrigin.API && failed is TaskSourceException && failed.httpCode == 401)
                request.tag(GitHubToken::class.java)?.let { authorization.rejected(it) }
            transfer.result.getOrThrow()
        }
        catch (cancelled: CancellationException) { call.cancel(); throw cancelled }
        finally {
            // OkHttp cancel requests interruption; it does not itself prove a response callback is quiet.
            withContext(NonCancellable) { completed.await() }
        }
    }

    private suspend fun get(path: String, query: Pair<String, String>? = null, accept: String = "application/vnd.github+json"): Payload {
        val request = request(path, query, accept)
        val result = suspendCancellableCoroutine<Payload> { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        TaskSourceException(TaskSourceProblem.NETWORK, "无法连接任务源，请检查网络设置", cause = e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val payload = response.use { Payload(it.code, if (it.code == 200) it.body.bytes() else byteArrayOf(), origin(it, request)) }
                        if (continuation.isActive) continuation.resume(payload)
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            TaskSourceException(TaskSourceProblem.NETWORK, "任务源响应未完整读取", cause = error))
                    }
                }
            })
        }
        if (result.origin == ResponseOrigin.API && result.code == 401) request.tag(GitHubToken::class.java)?.let { authorization.rejected(it) }
        return result
    }
    private suspend fun request(path: String, query: Pair<String, String>? = null, accept: String): Request {
        val url = apiBase.newBuilder().addPathSegments("repos/${repository.owner}/${repository.name}")
        if (path.isNotEmpty()) url.addPathSegments(path)
        query?.let { url.addQueryParameter(it.first, it.second) }
        val token = try { authorization.token() }
        catch (error: GitHubAuthException) { throw TaskSourceException(TaskSourceProblem.AUTHORIZATION, error.message ?: "请检查 GitHub 登录", error.httpCode) }
        return Request.Builder().url(url.build()).header("Accept", accept)
            .apply { if (token != null) { header("Authorization", "Bearer ${token.value}"); tag(GitHubToken::class.java, token) } }
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "YysAssist-Android").get().build()
    }
    private inline fun <reified T> decode(bytes: ByteArray): T = try { json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) }
        catch (error: IllegalArgumentException) { throw TaskSourceException(TaskSourceProblem.INVALID_RESPONSE, "任务源返回了无法识别的数据", cause = error) }
    private fun ensureSuccess(payload: Payload) {
        if (payload.code != 200) throw TaskSourceException(TaskSourceProblem.HTTP, "任务源请求失败（HTTP ${payload.code}）", payload.code)
    }
    private fun invalidResponse(message: String): Nothing = throw TaskSourceException(TaskSourceProblem.INVALID_RESPONSE, message)
    companion object { const val BUNDLE_ASSET = "task-bundle.json" }
}
