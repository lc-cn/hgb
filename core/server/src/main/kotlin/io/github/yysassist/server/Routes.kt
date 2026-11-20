package io.github.yysassist.server

import io.github.yysassist.core.cloud.*
import io.github.yysassist.protocol.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

data class ServerConfig(val publicUrl: String, val secureCookies: Boolean)
private const val COOKIE = "yys_session"
private const val CSRF_HEADER = "X-YYS-Request"
private const val DEVICE_HEADER = "X-YYS-Client"

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
    install(WebSockets) { pingPeriod = 20.seconds; timeout = 45.seconds; maxFrameSize = MAX_DEVICE_FRAME }
    // Plans fire, waiting jobs find apps, and leases of silent devices settle on this beat, even when no request comes in.
    launch { while (isActive) { delay(TICK_MILLIS); runCatching { service.tick() } } }
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
                if (session == null) call.respondRedirect("/console/?error=github")
                else { call.setSessionCookie(config, session.accessToken, CloudService.WEB_LIFETIME / 1000); call.respondRedirect("/console/") }
            }
            post("/logout") { service.logout(call.caller(service)); call.response.cookies.append(Cookie(COOKIE, "", maxAge = 0, path = "/")); call.respond(mapOf("ok" to true)) }
            delete("/github") { service.unlinkGitHub(call.caller(service)); call.respond(mapOf("ok" to true)) }
        }
        get("/api/me") { call.respond(service.account(call.caller(service))) }
        get("/api/devices") { call.respond(service.devices(call.caller(service))) }
        delete("/api/devices/{id}") {
            val id = call.parameters.getOrFail("id")
            service.revokeDevice(call.caller(service), id); service.sockets.close(id)
            call.respond(mapOf("ok" to true))
        }
        get("/api/jobs") { call.respond(service.jobs(call.caller(service))) }
        post("/api/jobs") { call.respond(HttpStatusCode.Created, service.createJob(call.caller(service), call.receive())) }
        put("/api/me/time-zone") { service.timeZone(call.caller(service), call.receive()); call.respond(mapOf("ok" to true)) }
        get("/api/games") { call.respond(service.games(call.caller(service))) }
        // Event rules and what they noticed; both belong to the signed-in user, never to a device.
        route("/api/notifications") {
            get("/rules") { call.respond(service.notices.rules(call.caller(service).userId)) }
            put("/rules/{ruleId}") {
                val caller = call.caller(service)
                call.respond(service.notices.save(caller.userId, call.parameters.getOrFail("ruleId"), call.receive()))
            }
            get("/events") {
                val caller = call.caller(service)
                val query = call.request.queryParameters
                call.respond(service.notices.list(caller.userId, query["rule"]?.takeIf { it.isNotBlank() }, query["after"]))
            }
        }
        route("/api/accounts") {
            get { call.respond(service.gameAccounts(call.caller(service))) }
            post { call.respond(HttpStatusCode.Created, service.createGameAccount(call.caller(service), call.receive())) }
            put("/{id}") { call.respond(service.updateGameAccount(call.caller(service), call.parameters.getOrFail("id"), call.receive())) }
            delete("/{id}") { service.deleteGameAccount(call.caller(service), call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
            get("/{id}/configs/{target}/{task}") {
                call.respond(service.config(call.caller(service), call.parameters.getOrFail("id"), call.parameters.getOrFail("target"), call.parameters.getOrFail("task")))
            }
            put("/{id}/configs/{target}/{task}") {
                call.respond(service.saveConfig(call.caller(service), call.parameters.getOrFail("id"), call.parameters.getOrFail("target"), call.parameters.getOrFail("task"), call.receive()))
            }
            post("/{id}/plans") { call.respond(HttpStatusCode.Created, service.createPlan(call.caller(service), call.parameters.getOrFail("id"), call.receive())) }
        }
        get("/api/plans") { call.respond(service.plans(call.caller(service))) }
        post("/api/plans/{id}/run") { service.runPlan(call.caller(service), call.parameters.getOrFail("id")); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
        put("/api/plans/{id}") { call.respond(service.updatePlan(call.caller(service), call.parameters.getOrFail("id"), call.receive())) }
        delete("/api/plans/{id}") { service.deletePlan(call.caller(service), call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
        put("/api/devices/{id}/apps/{key}") {
            service.setAppAccounts(call.caller(service), call.parameters.getOrFail("id"), call.parameters.getOrFail("key"), call.receive())
            call.respond(mapOf("ok" to true))
        }
        get("/api/jobs/{id}/events") { call.respond(service.events(call.caller(service), call.parameters.getOrFail("id"))) }
        post("/api/jobs/{id}/cancel") {
            service.cancelJob(call.caller(service), call.parameters.getOrFail("id"))?.let { (deviceId, message) -> service.sockets.send(deviceId, service.encode(message)) }
            call.respond(mapOf("ok" to true))
        }
        post("/api/jobs/{id}/pause") {
            val (deviceId, message) = service.pauseJob(call.caller(service), call.parameters.getOrFail("id"), true)
            service.sockets.send(deviceId, service.encode(message)); call.respond(mapOf("ok" to true))
        }
        post("/api/jobs/{id}/resume") {
            val (deviceId, message) = service.pauseJob(call.caller(service), call.parameters.getOrFail("id"), false)
            service.sockets.send(deviceId, service.encode(message)); call.respond(mapOf("ok" to true))
        }
        get("/api/events") { consoleStream(call, service) }
        route("/api/device") {
            post("/register") { call.respond(service.registerDevice(call.caller(service), call.receive())) }
            webSocket("/socket") { deviceSocket(this, service) }
            post("/catalog") { service.uploadCatalog(call.caller(service), call.receive()); call.respond(mapOf("ok" to true)) }
            post("/jobs/{id}/events") { service.appendEvents(call.caller(service), call.parameters.getOrFail("id"), call.receive()); call.respond(mapOf("ok" to true)) }
            post("/jobs/{id}/report") { service.report(call.caller(service), call.parameters.getOrFail("id"), call.receive()); call.respond(mapOf("ok" to true)) }
        }
    }
}
private fun Parameters.getOrFail(name: String) = get(name) ?: throw ApiException(400, "bad_request", "请求内容无效")

/**
 * A device's one connection. The handshake carries the device's bearer token; a bad one is closed with a code the app
 * understands, so it can renew and come back. Each text frame is a [DeviceMessage]; replies are [ServerMessage]s.
 */
private suspend fun deviceSocket(session: DefaultWebSocketServerSession, service: CloudService) = with(session) {
    val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
    val caller = token?.let(service::authenticate)
    if (caller == null) { close(CloseReason(SocketClose.INVALID_SESSION, "invalid_session")); return }
    val deviceId = caller.deviceId
    if (caller.kind != SessionKind.DEVICE || deviceId == null) { close(CloseReason(SocketClose.NOT_A_DEVICE, "device_only")); return }
    val (connection, replaced) = service.sockets.attach(caller.userId, deviceId,
        close = { runCatching { close(CloseReason(SocketClose.REPLACED, "closed")) } })
    replaced?.close?.invoke()
    // Everything for this device goes through its outbox, so business code never waits on the network.
    val writer = launch { for (text in connection.outbox) outgoing.send(Frame.Text(text)) }
    service.connectionChanged(caller.userId, deviceId)
    var checkedAt = System.currentTimeMillis()
    var windowStart = 0L; var inWindow = 0
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val now = System.currentTimeMillis()
            // A session ended by sign-out, password reset or revocation also ends its socket.
            if (now - checkedAt > SESSION_RECHECK_MILLIS) {
                if (!service.sessionAlive(caller)) { close(CloseReason(SocketClose.INVALID_SESSION, "invalid_session")); break }
                checkedAt = now
            }
            // A device reports at most once a second; anything faster is dropped, and the next report catches up.
            if (now - windowStart > 1_000) { windowStart = now; inWindow = 0 }
            if (++inWindow > MAX_FRAMES_PER_SECOND) continue
            val message = runCatching { service.decodeDeviceMessage(frame.readText()) }.getOrNull() ?: continue
            when (message) {
                is DeviceMessage.Status -> service.status(caller, message.status).forEach { connection.outbox.trySend(service.encode(it)) }
            }
        }
    } catch (_: ApiException) {
        close(CloseReason(SocketClose.NOT_A_DEVICE, "device_only"))
    } finally {
        writer.cancel()
        if (service.sockets.detach(connection)) service.connectionChanged(caller.userId, deviceId)
    }
}

/**
 * The console's live view (Server-Sent Events). It starts with a snapshot, then sends each change as it happens. A
 * browser that reconnects names the last event it saw and gets only what it missed, or a new snapshot if that is gone.
 */
private suspend fun consoleStream(call: ApplicationCall, service: CloudService) {
    val caller = call.caller(service)
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append("X-Accel-Buffering", "no") // proxies must pass each event through at once
    call.respondBytesWriter(ContentType.Text.EventStream) {
        suspend fun write(text: String) { writeStringUtf8(text); flush() }
        suspend fun send(id: Long, name: String, data: String) = write("id: ${service.events.wireId(id)}\nevent: $name\ndata: $data\n\n")
        coroutineScope {
            // Subscribe before reading the snapshot, so nothing published in between is lost. A browser that stops
            // reading is cut off once its backlog is full; it reconnects and starts from a snapshot.
            val pending = Channel<ConsoleEvent>(MAX_PENDING_EVENTS)
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch {
                service.events.stream(caller.userId).onSubscription { subscribed.complete(Unit) }
                    .collect { if (!pending.trySend(it).isSuccess) pending.close() }
            }
            subscribed.await()
            val lastEventId = call.request.headers["Last-Event-ID"]
            val missed = service.events.since(caller.userId, lastEventId)
            var sentUpTo = if (missed != null) {
                missed.forEach { send(it.id, it.name, it.data) }
                missed.lastOrNull()?.id ?: checkNotNull(service.events.idOf(lastEventId))
            } else {
                // The snapshot and the id it covers are read together, so no later event can hold older data.
                val (snapshot, upTo) = service.consoleSnapshotUpTo(caller.userId)
                send(upTo, "snapshot", service.encode(snapshot)); upTo
            }
            write("retry: 3000\n\n")
            var checkedAt = System.currentTimeMillis()
            try {
                while (isActive) {
                    val event = withTimeoutOrNull(KEEPALIVE_MILLIS) { pending.receiveCatching() }
                    if (event != null && event.isClosed) break
                    if (System.currentTimeMillis() - checkedAt > KEEPALIVE_MILLIS) {
                        if (!service.sessionAlive(caller)) break // signed out, reset or expired: stop streaming
                        checkedAt = System.currentTimeMillis()
                    }
                    val next = event?.getOrNull()
                    if (next == null) { write(": keep-alive\n\n"); continue }
                    if (next.id <= sentUpTo) continue
                    send(next.id, next.name, next.data); sentUpTo = next.id
                }
            } finally { collector.cancel() }
        }
    }
}

/** Plans fire and waiting jobs are assigned on this beat. */
private const val TICK_MILLIS = 5_000L
private const val KEEPALIVE_MILLIS = 15_000L
private const val SESSION_RECHECK_MILLIS = 60_000L
private const val MAX_FRAMES_PER_SECOND = 5
private const val MAX_PENDING_EVENTS = 256
private const val MAX_DEVICE_FRAME = 1L shl 20
