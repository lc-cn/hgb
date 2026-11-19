package io.github.yysassist.server

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class GitHubUser(val id: String, val login: String)
interface GitHubIdentity {
    val clientId: String?
    fun exchange(code: String, verifier: String, redirectUri: String): String?
    fun user(accessToken: String): GitHubUser?
}

/** Used only to learn who signed in: the token is discarded and never reads repositories. */
class GitHubIdentityClient(override val clientId: String?, private val clientSecret: String?) : GitHubIdentity {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private fun form(vararg values: Pair<String, String>) = values.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
    override fun exchange(code: String, verifier: String, redirectUri: String): String? {
        if (clientId == null || clientSecret == null) return null
        val request = HttpRequest.newBuilder(URI("https://github.com/login/oauth/access_token")).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form("client_id" to clientId, "client_secret" to clientSecret, "code" to code,
                "code_verifier" to verifier, "redirect_uri" to redirectUri))).build()
        val reply = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        return runCatching { Json.parseToJsonElement(reply.body()).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
    override fun user(accessToken: String): GitHubUser? {
        val request = HttpRequest.newBuilder(URI("https://api.github.com/user")).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json").header("Authorization", "Bearer $accessToken").header("User-Agent", "yys-assist-cloud").GET().build()
        val reply = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (reply.statusCode() != 200) return null
        return runCatching { Json.parseToJsonElement(reply.body()).jsonObject.let { GitHubUser(it.getValue("id").jsonPrimitive.content, it.getValue("login").jsonPrimitive.content) } }.getOrNull()
    }
}
