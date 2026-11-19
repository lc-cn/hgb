package io.github.yysassist.server

import io.github.yysassist.core.cloud.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

data class ServerConfig(val publicUrl: String, val secureCookies: Boolean)
private const val COOKIE = "yys_session"
private const val CSRF_HEADER = "X-YYS-Request"
private const val DEVICE_HEADER = "X-YYS-Client"
private const val LONG_POLL_MILLIS = 20_000L

private fun ApplicationCall.sessionKind() = if (request.headers[DEVICE_HEADER] == "device") SessionKind.DEVICE else SessionKind.WEB

/** Cookie sessions must carry a header other sites cannot set, which stops cross-site form posts. */
private fun ApplicationCall.caller(service: CloudService): Caller {
    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.takeIf { it != request.headers[HttpHeaders.Authorization] }
    val token = bearer ?: request.cookies[COOKIE]
    val caller = token?.let(service::authenticate) ?: throw ApiException(401, "invalid_session", "请先登录")
    if (bearer == null && request.httpMethod != HttpMethod.Get && request.headers[CSRF_HEADER] != "1") throw ApiException(403, "csrf", "请求来源无效")
    return caller
}
private fun ApplicationCall.setSessionCookie(config: ServerConfig, value: String, maxAge: Long) = response.cookies.append(Cookie(COOKIE, value, maxAge = maxAge.toInt(),
    path = "/", httpOnly = true, secure = config.secureCookies, extensions = mapOf("SameSite" to "Lax")))
private suspend fun ApplicationCall.respondSession(config: ServerConfig, session: IssuedSession) {
    if (session.refreshToken != null) respond(AuthResult(session.userId, session.email, session.accessToken, session.refreshToken, session.accessExpiresAt))
    else { setSessionCookie(config, session.accessToken, CloudService.WEB_LIFETIME / 1000); respond(AuthResult(session.userId, session.email)) }
}

@OptIn(ExperimentalSerializationApi::class)
fun Application.cloudModule(service: CloudService, config: ServerConfig) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    install(StatusPages) {
        exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.status), ApiError(error.code, error.message)) }
        exception<MailUnavailable> { call, error -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError("mail_unavailable", error.message ?: "发信服务未配置")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "请求内容无效")) }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "no-referrer")
    }
    val callback = "${config.publicUrl.trimEnd('/')}/api/auth/github/callback"
    routing {
        route("/api/auth") {
            post("/register") { service.register(call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/verify-email") { call.respondSession(config, service.verifyEmail(call.receive(), call.sessionKind())) }
            post("/login") { call.respondSession(config, service.loginWithPassword(call.receive(), call.sessionKind())) }
            post("/code/request") { service.requestLoginCode(call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/code/login") { call.respondSession(config, service.loginWithCode(call.receive(), call.sessionKind())) }
            post("/password/request") { service.requestPasswordReset(call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/password/reset") { service.resetPassword(call.receive()); call.respond(mapOf("ok" to true)) }
            post("/refresh") { call.respondSession(config, service.refresh(call.receive())) }
            post("/github/token") { call.respondSession(config, service.loginWithGitHubToken(call.receive())) }
            get("/github/start") {
                val link = runCatching { call.request.cookies[COOKIE]?.let(service::authenticate)?.userId }.getOrNull()
                call.respondRedirect(service.githubStart(callback, link.takeIf { call.request.queryParameters["link"] == "1" }))
            }
            get("/github/callback") {
                val code = call.request.queryParameters["code"]; val state = call.request.queryParameters["state"]
                val session = if (code != null && state != null) runCatching { service.githubCallback(code, state, callback) }.getOrNull() else null
                if (session == null) call.respondRedirect("/?error=github")
                else { call.setSessionCookie(config, session.accessToken, CloudService.WEB_LIFETIME / 1000); call.respondRedirect("/") }
            }
            post("/logout") { service.logout(call.caller(service)); call.response.cookies.append(Cookie(COOKIE, "", maxAge = 0, path = "/")); call.respond(mapOf("ok" to true)) }
            delete("/github") { service.unlinkGitHub(call.caller(service)); call.respond(mapOf("ok" to true)) }
        }
        get("/api/me") { call.respond(service.account(call.caller(service))) }
        get("/api/devices") { call.respond(service.devices(call.caller(service))) }
        delete("/api/devices/{id}") { service.revokeDevice(call.caller(service), call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
        get("/api/jobs") { call.respond(service.jobs(call.caller(service))) }
        post("/api/jobs") { call.respond(HttpStatusCode.Created, service.createJob(call.caller(service), call.receive())) }
        post("/api/jobs/{id}/cancel") { service.cancelJob(call.caller(service), call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
        route("/api/device") {
            post("/register") { call.respond(service.registerDevice(call.caller(service), call.receive())) }
            post("/heartbeat") { call.respond(service.heartbeat(call.caller(service), call.receive())) }
            post("/jobs/next") {
                val caller = call.caller(service)
                val waitMillis = (call.request.queryParameters["waitMs"]?.toLongOrNull() ?: LONG_POLL_MILLIS).coerceIn(0, LONG_POLL_MILLIS)
                val deadline = System.currentTimeMillis() + waitMillis
                var lease = service.nextJob(caller)
                while (lease == null && System.currentTimeMillis() < deadline) { delay(500); lease = service.nextJob(caller) }
                if (lease == null) call.respond(HttpStatusCode.NoContent) else call.respond(lease)
            }
            post("/jobs/{id}/report") { service.report(call.caller(service), call.parameters.getOrFail("id"), call.receive()); call.respond(mapOf("ok" to true)) }
        }
        staticResources("/", "web") { default("index.html") }
    }
}
private fun Parameters.getOrFail(name: String) = get(name) ?: throw ApiException(400, "bad_request", "请求内容无效")
