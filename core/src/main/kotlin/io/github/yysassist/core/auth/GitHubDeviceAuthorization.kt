package io.github.yysassist.core.auth

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit access only; diagnostics must never interpolate a credential's value. */
class GitHubToken(val value: String) {
    init { require(value.isNotEmpty() && value.all { it.code in 33..126 }) { "Invalid GitHub credential" } }
    override fun toString() = "GitHubToken(<redacted>)"
}
fun interface GitHubAuthorization {
    suspend fun token(): GitHubToken?
    /** An observed 401 concerns this exact credential, never a later login. No replay is implied. */
    suspend fun rejected(token: GitHubToken) = Unit
}
class GitHubUserTokens(val accessToken: GitHubToken, val expiresInSeconds: Long?,
    val refreshToken: GitHubToken?, val refreshExpiresInSeconds: Long?) {
    override fun toString() = "GitHubUserTokens(<redacted>)"
}
class GitHubDevicePrompt(val userCode: String, val verificationUri: HttpUrl, val expiresInSeconds: Long) {
    override fun toString() = "GitHubDevicePrompt(<redacted>)"
}
enum class GitHubAuthProblem { NETWORK, HTTP, INVALID_RESPONSE, CONFIGURATION, DENIED, EXPIRED, STORAGE }
/** No remote response body or decoding exception is retained: either can contain credentials. */
class GitHubAuthException(val problem: GitHubAuthProblem, message: String, val httpCode: Int? = null) : IOException(message)

/** One caller-owned login attempt. No registration, installation, storage or background login. */
class GitHubDeviceAuthorization(
    private val clientId: String,
    http: OkHttpClient,
    private val githubBase: HttpUrl = "https://github.com/".toHttpUrl(),
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    init { require(clientId.isNotBlank() && clientId.none { it.isWhitespace() || it.isISOControl() }) { "Missing GitHub Client ID" } }
    // These POSTs may issue credentials. Neither a redirect nor a lost response authorizes replay.
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Challenge(val device_code: String, val user_code: String, val verification_uri: String,
        val expires_in: Long, val interval: Long = 5)
    @Serializable private data class TokenReply(val error: String? = null, val interval: Long? = null,
        val access_token: String? = null, val token_type: String? = null, val expires_in: Long? = null,
        val refresh_token: String? = null, val refresh_token_expires_in: Long? = null)

    suspend fun authorize(beforeTokenRequest: () -> Unit = {}, showPrompt: suspend (GitHubDevicePrompt) -> Unit): GitHubUserTokens {
        val requestedAt = elapsedMillis()
        val challengeText = post("login/device/code", mapOf("client_id" to clientId))
        val challengeError = decode<TokenReply>(challengeText)
        if (challengeError.access_token != null || challengeError.refresh_token != null) invalid()
        challengeError.error?.let(::terminalError)
        val challenge = decode<Challenge>(challengeText)
        if (challenge.device_code.isBlank() || challenge.device_code.any { it.isWhitespace() || it.isISOControl() } ||
            challenge.user_code.isBlank() || challenge.user_code.any(Char::isISOControl) || challenge.verification_uri != VERIFICATION_URI.toString()) invalid()
        val lifetime = millis(challenge.expires_in)
        val deadline = try { Math.addExact(requestedAt, lifetime) } catch (_: ArithmeticException) { invalid() }
        var interval = millis(challenge.interval)
        showPrompt(GitHubDevicePrompt(challenge.user_code, VERIFICATION_URI, challenge.expires_in))
        while (true) {
            val remaining = deadline - elapsedMillis()
            if (remaining <= 0) expired()
            waitMillis(minOf(interval, remaining))
            if (elapsedMillis() >= deadline) expired()
            beforeTokenRequest()
            val reply = decode<TokenReply>(post("login/oauth/access_token", mapOf(
                "client_id" to clientId, "device_code" to challenge.device_code,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")))
            if (reply.error == null) return tokens(reply)
            if (reply.access_token != null || reply.refresh_token != null) invalid()
            when (reply.error) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    val increased = try { Math.addExact(interval, 5_000) } catch (_: ArithmeticException) { invalid() }
                    interval = maxOf(increased, reply.interval?.let(::millis) ?: increased)
                }
                else -> terminalError(reply.error)
            }
        }
    }
    /** Exactly one request. The application must retain and persist its outcome across caller cancellation. */
    suspend fun refresh(refreshToken: GitHubToken): GitHubUserTokens {
        val reply = decode<TokenReply>(post("login/oauth/access_token", mapOf("client_id" to clientId,
            "grant_type" to "refresh_token", "refresh_token" to refreshToken.value)))
        if (reply.error != null) {
            if (reply.access_token != null || reply.refresh_token != null) invalid()
            if (reply.error == "bad_refresh_token") throw GitHubAuthException(GitHubAuthProblem.EXPIRED, "GitHub 登录已失效，请重新登录")
            terminalError(reply.error)
        }
        return tokens(reply)
    }
    private fun tokens(reply: TokenReply): GitHubUserTokens {
        if (!reply.token_type.equals("bearer", ignoreCase = true)) invalid()
        val access = credential(reply.access_token)
        val expires = reply.expires_in?.also { millis(it) }
        val refresh = reply.refresh_token?.let(::credential)
        val refreshExpires = reply.refresh_token_expires_in?.also { millis(it) }
        if ((expires == null) != (refresh == null) || (refresh == null) != (refreshExpires == null)) invalid()
        return GitHubUserTokens(access, expires, refresh, refreshExpires)
    }
    private fun credential(value: String?): GitHubToken = try { GitHubToken(value ?: invalid()) }
        catch (_: IllegalArgumentException) { invalid() }
    private fun millis(seconds: Long): Long {
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1_000) invalid()
        return seconds * 1_000
    }
    private inline fun <reified T> decode(text: String): T = try { json.decodeFromString<T>(text) }
        catch (_: IllegalArgumentException) { invalid() }
    private fun invalid(): Nothing = throw GitHubAuthException(GitHubAuthProblem.INVALID_RESPONSE, "GitHub 授权响应无法确认，请重新登录")
    private fun expired(): Nothing = throw GitHubAuthException(GitHubAuthProblem.EXPIRED, "GitHub 设备码已过期，请重新登录")
    private fun terminalError(error: String): Nothing = when (error) {
        "expired_token", "token_expired" -> expired()
        "access_denied" -> throw GitHubAuthException(GitHubAuthProblem.DENIED, "GitHub 授权已取消")
        "device_flow_disabled" -> throw GitHubAuthException(GitHubAuthProblem.CONFIGURATION, "GitHub App 尚未开启 Device Flow")
        "incorrect_client_credentials", "unsupported_grant_type", "incorrect_device_code", "bad_verification_code" ->
            throw GitHubAuthException(GitHubAuthProblem.CONFIGURATION, "GitHub 授权配置或设备码无效，请重新检查")
        "unverified_user_email" -> throw GitHubAuthException(GitHubAuthProblem.DENIED, "请先在 GitHub 验证账号邮箱")
        else -> invalid()
    }

    private suspend fun post(path: String, fields: Map<String, String>): String = suspendCancellableCoroutine { continuation ->
        val form = FormBody.Builder().also { form -> fields.forEach { (key, value) -> form.add(key, value) } }.build()
        // retryOnConnectionFailure(false) alone does not disable OkHttp's 503 Retry-After follow-up.
        // A credential exchange body cannot be replayed, including by transport-level follow-ups.
        val body = object : RequestBody() {
            override fun contentType() = form.contentType()
            override fun contentLength() = form.contentLength()
            override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
            override fun isOneShot() = true
        }
        val request = Request.Builder().url(githubBase.newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json").header("User-Agent", "YysAssist-Android").post(body).build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    GitHubAuthException(GitHubAuthProblem.NETWORK, "GitHub 授权请求结果未确认，请检查网络后重新登录"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (it.code != 200) throw GitHubAuthException(GitHubAuthProblem.HTTP, "GitHub 授权请求失败（HTTP ${it.code}）", it.code)
                        it.body.string()
                    }
                }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { failure ->
                    continuation.resumeWithException(if (failure is GitHubAuthException) failure else
                        GitHubAuthException(GitHubAuthProblem.NETWORK, "GitHub 授权响应未完整读取，请重新登录"))
                })
            }
        })
    }
    companion object { val VERIFICATION_URI: HttpUrl = "https://github.com/login/device".toHttpUrl() }
}
