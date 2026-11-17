package io.github.yysassist.core.auth

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public account label only. It grants no developer or repository access. */
class GitHubUserApi(http: OkHttpClient, private val apiBase: HttpUrl = "https://api.github.com/".toHttpUrl()) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class User(val id: Long, val login: String)
    suspend fun login(token: GitHubToken): String {
        val text = suspendCancellableCoroutine<String> { continuation ->
            val request = Request.Builder().url(apiBase.newBuilder().addPathSegment("user").build())
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer ${token.value}").header("User-Agent", "YysAssist-Android").build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(GitHubAuthException(GitHubAuthProblem.NETWORK, "GitHub 账号资料暂无法读取"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (it.code != 200) throw GitHubAuthException(GitHubAuthProblem.HTTP, "GitHub 账号查询失败（HTTP ${it.code}）", it.code)
                        it.body.string()
                    } }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }, {
                        continuation.resumeWithException(if (it is GitHubAuthException) it else
                            GitHubAuthException(GitHubAuthProblem.NETWORK, "GitHub 账号响应未完整读取"))
                    })
                }
            })
        }
        val user = try { json.decodeFromString<User>(text) }
        catch (_: IllegalArgumentException) { throw GitHubAuthException(GitHubAuthProblem.INVALID_RESPONSE, "GitHub 账号响应无法确认") }
        if (user.id <= 0 || user.login.isBlank()) throw GitHubAuthException(GitHubAuthProblem.INVALID_RESPONSE, "GitHub 账号身份缺失")
        return user.login
    }
}
