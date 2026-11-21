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

/**
 * [publicUrl] is where this server is reached (GitHub calls back there); [consoleUrl] is where the console is, to
 * return to after signing in with GitHub; [allowedOrigins] are the sites (the console on another host of the same
 * site) whose pages may call this API with the session cookie.
 */
data class ServerConfig(val publicUrl: String, val secureCookies: Boolean, val consoleUrl: String = "/console/", val allowedOrigins: Set<String> = emptySet())
private const val COOKIE = "yys_session"
private const val OAUTH_COOKIE = "yys_oauth"
private const val CSRF_HEADER = "X-YYS-Request"
private const val DEVICE_HEADER = "X-YYS-Client"

private fun ApplicationCall.sessionKind() = if (request.headers[DEVICE_HEADER] == "device") SessionKind.DEVICE else SessionKind.WEB

/**
 * Cookie sessions must carry a header other sites cannot set, which stops cross-site form posts; and a write from a
 * page must come from this server's own origin or an allowed one.
 */
private fun ApplicationCall.caller(service: CloudService, config: ServerConfig? = null): Caller {
    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.takeIf { it != request.headers[HttpHeaders.Authorization] }
    val token = bearer ?: request.cookies[COOKIE]
    val caller = token?.let(service::authenticate) ?: throw ApiException(401, "invalid_session", "请先登录")
    if (bearer == null && request.httpMethod != HttpMethod.Get) {
        if (request.headers[CSRF_HEADER] != "1") throw ApiException(403, "csrf", "请求来源无效")
        val origin = request.headers[HttpHeaders.Origin]
        if (config != null && origin != null && origin != originOf(config.publicUrl) && origin !in config.allowedOrigins) throw ApiException(403, "csrf", "请求来源无效")
    }
    return caller
}
private fun ApplicationCall.setSessionCookie(config: ServerConfig, value: String, maxAge: Long) = response.cookies.append(Cookie(COOKIE, value, maxAge = maxAge.toInt(),
    path = "/", httpOnly = true, secure = config.secureCookies, extensions = mapOf("SameSite" to "Lax")))
private suspend fun ApplicationCall.respondSession(config: ServerConfig, session: IssuedSession) {
    if (session.refreshToken != null) respond(AuthResult(session.userId, session.email, session.accessToken, session.refreshToken, session.accessExpiresAt))
    else { setSessionCookie(config, session.accessToken, CloudService.WEB_LIFETIME / 1000); respond(AuthResult(session.userId, session.email)) }
}

@OptIn(ExperimentalSerializationApi::class)
fun Application.cloudModule(service: CloudService, config: ServerConfig, workbench: Workbench? = null) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
    install(StatusPages) {
        exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.status), ApiError(error.code, error.message)) }
        exception<MailFailed> { call, error ->
            // The provider's words go to the log; the user only needs to know to try again.
            call.application.environment.log.warn("Mail not sent: ${error.message}")
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("mail_failed", "邮件发送失败，请稍后再试"))
        }
        exception<MailUnavailable> { call, error -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError("mail_unavailable", error.message ?: "发信服务未配置")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "请求内容无效")) }
    }
    install(WebSockets) { pingPeriod = 20.seconds; timeout = 45.seconds; maxFrameSize = MAX_DEVICE_FRAME }
    // Plans fire, waiting jobs find apps, and leases of silent devices settle on this beat, even when no request comes in.
    launch { while (isActive) { delay(TICK_MILLIS); runCatching { service.tick() } } }
    // Notices go out on their own loop, off the request threads, so a slow channel never holds up a tick or a request.
    launch(kotlinx.coroutines.Dispatchers.IO) { while (isActive) { delay(DELIVERY_MILLIS); runCatching { service.channels.deliverDue() } } }
    intercept(ApplicationCallPipeline.Plugins) {
        // The console may live on another host of the same site (C42 as deployed): only the origins configured may
        // read answers and send the session cookie; a preflight is answered here.
        val origin = call.request.headers[HttpHeaders.Origin]
        // Answers differ by Origin, so a cache in front must keep them apart.
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
        if (origin != null && origin in config.allowedOrigins) {
            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
            call.response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
            if (call.request.httpMethod == HttpMethod.Options) {
                call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, PATCH, DELETE")
                call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "Content-Type, $CSRF_HEADER, Last-Event-ID")
                call.response.headers.append(HttpHeaders.AccessControlMaxAge, "600")
                call.respond(HttpStatusCode.NoContent); finish(); return@intercept
            }
        }
        call.response.headers.append("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "no-referrer")
    }
    val callback = "${config.publicUrl.trimEnd('/')}/api/auth/github/callback"
    routing {
        // The developer workbench: anyone signed in writes tasks for themselves; maintainers publish.
        val devRuns = workbench?.let { DevRuns(it, service) }
        route("/api/dev") {
            /**
             * Anyone signed in may write tasks for themselves (open-contribution.md): drafts, samples, templates, checks and
             * commands to their own phones. [kind] names the rate limit the request counts against.
             */
            fun ApplicationCall.author(kind: String = "read", limit: Int = Workbench.MAX_BUNDLE_CHARS): Caller {
                val caller = caller(service, config)
                // Bodies are read whole, so their size is checked before anything is read.
                // Only methods with a body; browsers send DELETE without a length.
                if (request.httpMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch) && request.contentLength() == null)
                    throw ApiException(411, "length_required", "请求需带长度")
                if ((request.contentLength() ?: 0) > limit) throw ApiException(413, "too_large", "请求太大")
                (workbench ?: throw ApiException(503, "workbench_unconfigured", "开发工作台不可用")).limits.take(caller.userId, kind)
                return caller
            }
            /** Publishing (and reviewing): a GitHub account that can write to the task repository. */
            fun ApplicationCall.maintainer(kind: String = "write"): Caller = author(kind).also { workbench!!.requireMaintainer(it) }
            get("/me") { val caller = call.author(); call.respond(workbench!!.me(caller)) }
            get("/catalog") { call.author(); call.respond(workbench!!.catalog()) }
            post("/check") { call.author("check"); call.respond(workbench!!.check(call.receiveText())) }
            get("/drafts") { val caller = call.author(); call.respond(workbench!!.drafts.list(caller.userId)) }
            route("/drafts/{target}") {
                // The newest published version, fetched by the server (the browser cannot reach GitHub's files directly).
                post("/import-latest") {
                    val caller = call.author("write")
                    call.respond(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        workbench!!.publisher.importLatest(caller.userId, call.parameters.getOrFail("target")) })
                }
                get { val caller = call.author(); call.respond(workbench!!.drafts.get(caller.userId, call.parameters.getOrFail("target"))) }
                put { val caller = call.author("write"); call.respond(workbench!!.drafts.import(caller.userId, call.parameters.getOrFail("target"), call.receive())) }
                // Patches are small edits; a whole draft goes through the import.
                post("/patches") {
                    val caller = call.author("write", 256 * 1024)
                    val result = workbench!!.drafts.patch(caller.userId, call.parameters.getOrFail("target"), call.receive())
                    call.respond(if (result.conflicts.isEmpty()) HttpStatusCode.OK else HttpStatusCode.Conflict, result)
                }
                get("/patches") {
                    val caller = call.author()
                    call.respond(workbench!!.drafts.since(caller.userId, call.parameters.getOrFail("target"), call.request.queryParameters["since"]?.toLongOrNull() ?: 0))
                }
                // Publishing waits on GitHub, so it runs off the request threads.
                post("/publish") {
                    val caller = call.maintainer(); val request = call.receive<PublishRequest>()
                    call.respond(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        workbench!!.publisher.publish(caller.userId, call.parameters.getOrFail("target"), request) })
                }
            }
            route("/runs") {
                post {
                    val caller = call.author(); val start = call.receive<DevStart>()
                    workbench!!.limits.take(caller.userId, devRuns!!.limitKind(start))
                    call.respond(HttpStatusCode.Created, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { devRuns.start(caller.userId, start) })
                }
                get { val caller = call.author(); call.respond(devRuns!!.list(caller.userId)) }
                get("/{id}") { val caller = call.author(); call.respond(devRuns!!.get(caller.userId, call.parameters.getOrFail("id"))) }
                // From the phone that ran it.
                // From the phone itself, with no rate limit: a run that started must always be able to report how it ended.
                post("/{id}/report") {
                    val caller = call.caller(service, config)
                    if ((call.request.contentLength() ?: 0) > Workbench.MAX_BUNDLE_CHARS) throw ApiException(413, "too_large", "请求太大")
                    val runs = devRuns ?: throw ApiException(503, "workbench_unconfigured", "开发工作台不可用")
                    call.respond(runs.report(caller, call.parameters.getOrFail("id"), call.receive()))
                }
            }
            route("/games/{target}") {
                // A template a phone needs for a trial before it is published, by digest.
                get("/assets/{sha}") {
                    val caller = call.author()
                    val file = workbench!!.assets.publishable(caller.userId, call.parameters.getOrFail("target"), call.parameters.getOrFail("sha"))
                        ?: throw ApiException(404, "no_asset", "没有这个素材")
                    call.respondBytes(file.readBytes(), ContentType.Image.PNG)
                }
                // A sample is the PNG itself; its name and device ride in the query.
                post("/samples") {
                    val caller = call.author("sample", Assets.MAX_SAMPLE_BYTES)
                    val q = call.request.queryParameters
                    call.respond(HttpStatusCode.Created, workbench!!.assets.addSample(caller.userId, call.parameters.getOrFail("target"),
                        q["name"].orEmpty(), q["device"].orEmpty(), call.receive<ByteArray>()))
                }
                get("/samples") { val caller = call.author(); call.respond(workbench!!.assets.samples(caller.userId, call.parameters.getOrFail("target"))) }
                get("/trials") {
                    val caller = call.author()
                    val runs = devRuns ?: throw ApiException(503, "workbench_unconfigured", "开发工作台不可用")
                    call.respond(runs.trials(caller.userId, call.parameters.getOrFail("target")))
                }
                get("/templates") { val caller = call.author(); call.respond(workbench!!.assets.templatesOf(caller.userId, call.parameters.getOrFail("target"))) }
                post("/templates") {
                    val caller = call.author("write")
                    call.respond(HttpStatusCode.Created, workbench!!.assets.cut(caller.userId, call.parameters.getOrFail("target"), call.receive()))
                }
                get("/templates/{id}/image") {
                    val caller = call.author()
                    call.respondBytes(workbench!!.assets.templateImage(caller.userId, call.parameters.getOrFail("target"), call.parameters.getOrFail("id")), ContentType.Image.PNG)
                }
                delete("/templates/{id}") {
                    val caller = call.author("write")
                    workbench!!.assets.deleteTemplate(caller.userId, call.parameters.getOrFail("target"), call.parameters.getOrFail("id"))
                    call.respond(mapOf("ok" to true))
                }
            }
            route("/samples/{id}") {
                get { val caller = call.author(); call.respond(workbench!!.assets.sample(caller.userId, call.parameters.getOrFail("id"))) }
                patch { val caller = call.author("write", 512 * 1024); call.respond(workbench!!.assets.changeSample(caller.userId, call.parameters.getOrFail("id"), call.receive())) }
                delete { val caller = call.author("write"); workbench!!.assets.deleteSample(caller.userId, call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
                get("/image") {
                    val caller = call.author()
                    call.respondBytes(workbench!!.assets.sampleImage(caller.userId, call.parameters.getOrFail("id")), ContentType.Image.PNG)
                }
            }
        }
        route("/api/auth") {
            get("/options") { call.respond(service.authOptions) }
            post("/register") { service.register(call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/verify-email") { call.respondSession(config, service.verifyEmail(call.receive(), call.sessionKind())) }
            post("/login") { call.respondSession(config, service.loginWithPassword(call.receive(), call.sessionKind())) }
            post("/code/request") { service.requestLoginCode(call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/code/login") { call.respondSession(config, service.loginWithCode(call.receive(), call.sessionKind())) }
            post("/password/request") { service.requestPasswordReset(call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/password/reset") { service.resetPassword(call.receive()); call.respond(mapOf("ok" to true)) }
            post("/refresh") { call.respondSession(config, service.refresh(call.receive())) }
            post("/github/token") { call.respondSession(config, service.loginWithGitHubToken(call.receive())) }
            // The state goes back to this browser in a cookie of its own: a GitHub link started by someone else (to sign
            // a victim in to the attacker's account, or to bind the victim's GitHub to it) has no matching cookie here.
            get("/github/start") {
                val link = runCatching { call.request.cookies[COOKIE]?.let(service::authenticate)?.userId }.getOrNull()
                val (address, state) = service.githubStart(callback, link.takeIf { call.request.queryParameters["link"] == "1" })
                call.response.cookies.append(Cookie(OAUTH_COOKIE, state, maxAge = (CloudService.CODE_LIFETIME / 1000).toInt(), path = "/api/auth/github",
                    httpOnly = true, secure = config.secureCookies, extensions = mapOf("SameSite" to "Lax")))
                call.respondRedirect(address)
            }
            get("/github/callback") {
                val code = call.request.queryParameters["code"]; val state = call.request.queryParameters["state"]
                val ours = call.request.cookies[OAUTH_COOKIE]
                call.response.cookies.append(Cookie(OAUTH_COOKIE, "", maxAge = 0, path = "/api/auth/github"))
                val outcome = if (code != null && state != null && ours != null && Secrets.same(ours, state))
                    runCatching { service.githubCallback(code, state, callback) }.getOrNull() else null
                when (outcome) {
                    null -> call.respondRedirect(withQuery(config.consoleUrl, "error=github"))
                    // Still signed in as before; the console shows the other account and asks to merge it.
                    is GitHubOutcome.Merge -> call.respondRedirect(withQuery(config.consoleUrl, "merge=${outcome.token}"))
                    is GitHubOutcome.SignedIn -> { call.setSessionCookie(config, outcome.session.accessToken, CloudService.WEB_LIFETIME / 1000); call.respondRedirect(config.consoleUrl) }
                }
            }
            post("/logout") { service.logout(call.caller(service, config)); call.response.cookies.append(Cookie(COOKIE, "", maxAge = 0, path = "/")); call.respond(mapOf("ok" to true)) }
            delete("/github") { service.unlinkGitHub(call.caller(service, config)); call.respond(mapOf("ok" to true)) }
        }
        get("/api/me") { call.respond(service.account(call.caller(service, config))) }
        route("/api/me") {
            post("/email/request") { service.requestEmailBind(call.caller(service, config), call.receive()); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/email/verify") { call.respond(service.verifyEmailBind(call.caller(service, config), call.receive())) }
            get("/merge/{token}") { call.respond(service.mergeOffer(call.caller(service, config), call.parameters.getOrFail("token"))) }
            post("/merge/{token}") {
                val caller = call.caller(service, config)
                service.merge(caller, call.parameters.getOrFail("token")).forEach { service.sockets.close(it) }
                call.respond(service.account(caller))
            }
            delete("/merge/{token}") { service.declineMerge(call.caller(service, config), call.parameters.getOrFail("token")); call.respond(mapOf("ok" to true)) }
            post("/delete/request") { service.requestDeletionCode(call.caller(service, config)); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
            post("/delete") {
                service.deleteAccount(call.caller(service, config), call.receive()).forEach { service.sockets.close(it) }
                call.response.cookies.append(Cookie(COOKIE, "", maxAge = 0, path = "/")); call.respond(mapOf("ok" to true))
            }
        }
        get("/api/devices") { call.respond(service.devices(call.caller(service, config))) }
        delete("/api/devices/{id}") {
            val id = call.parameters.getOrFail("id")
            service.revokeDevice(call.caller(service, config), id); service.sockets.close(id)
            call.respond(mapOf("ok" to true))
        }
        get("/api/jobs") { call.respond(service.jobs(call.caller(service, config))) }
        post("/api/jobs") { call.respond(HttpStatusCode.Created, service.createJob(call.caller(service, config), call.receive())) }
        put("/api/me/time-zone") { service.timeZone(call.caller(service, config), call.receive()); call.respond(mapOf("ok" to true)) }
        get("/api/games") { call.respond(service.games(call.caller(service, config))) }
        // Event rules and what they noticed; both belong to the signed-in user, never to a device.
        route("/api/notifications") {
            get("/rules") { call.respond(service.notices.rules(call.caller(service, config).userId)) }
            put("/rules/{ruleId}") {
                val caller = call.caller(service, config)
                val rules = service.notices.save(caller.userId, call.parameters.getOrFail("ruleId"), call.receive())
                service.rulesChanged(caller.userId)
                call.respond(rules)
            }
            get("/channels") { call.respond(service.channels.list(call.caller(service, config).userId)) }
            post("/channels") { call.respond(HttpStatusCode.Created, service.channels.create(call.caller(service, config).userId, call.receive())) }
            // A test waits for the other side (up to 15 seconds), so it runs off the request threads.
            post("/channels/test") {
                val caller = call.caller(service, config); val test = call.receive<ChannelTest>()
                call.respond(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { service.channels.test(caller.userId, test) })
            }
            put("/channels/{id}") { call.respond(service.channels.change(call.caller(service, config).userId, call.parameters.getOrFail("id"), call.receive())) }
            delete("/channels/{id}") { service.channels.delete(call.caller(service, config).userId, call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
            post("/deliveries/{id}/retry") { service.channels.retry(call.caller(service, config).userId, call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
            get("/events") {
                val caller = call.caller(service, config)
                val query = call.request.queryParameters
                call.respond(service.notices.list(caller.userId, query["rule"]?.takeIf { it.isNotBlank() }, query["after"]))
            }
        }
        route("/api/accounts") {
            get { call.respond(service.gameAccounts(call.caller(service, config))) }
            post { call.respond(HttpStatusCode.Created, service.createGameAccount(call.caller(service, config), call.receive())) }
            put("/{id}") { call.respond(service.updateGameAccount(call.caller(service, config), call.parameters.getOrFail("id"), call.receive())) }
            delete("/{id}") { service.deleteGameAccount(call.caller(service, config), call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
            get("/{id}/configs/{target}/{task}") {
                call.respond(service.config(call.caller(service, config), call.parameters.getOrFail("id"), call.parameters.getOrFail("target"), call.parameters.getOrFail("task")))
            }
            put("/{id}/configs/{target}/{task}") {
                call.respond(service.saveConfig(call.caller(service, config), call.parameters.getOrFail("id"), call.parameters.getOrFail("target"), call.parameters.getOrFail("task"), call.receive()))
            }
            post("/{id}/plans") { call.respond(HttpStatusCode.Created, service.createPlan(call.caller(service, config), call.parameters.getOrFail("id"), call.receive())) }
        }
        get("/api/plans") { call.respond(service.plans(call.caller(service, config))) }
        post("/api/plans/{id}/run") { service.runPlan(call.caller(service, config), call.parameters.getOrFail("id")); call.respond(HttpStatusCode.Accepted, mapOf("ok" to true)) }
        put("/api/plans/{id}") { call.respond(service.updatePlan(call.caller(service, config), call.parameters.getOrFail("id"), call.receive())) }
        delete("/api/plans/{id}") { service.deletePlan(call.caller(service, config), call.parameters.getOrFail("id")); call.respond(mapOf("ok" to true)) }
        put("/api/devices/{id}/apps/{key}") {
            service.setAppAccounts(call.caller(service, config), call.parameters.getOrFail("id"), call.parameters.getOrFail("key"), call.receive())
            call.respond(mapOf("ok" to true))
        }
        get("/api/jobs/{id}/events") { call.respond(service.events(call.caller(service, config), call.parameters.getOrFail("id"))) }
        post("/api/jobs/{id}/cancel") {
            service.cancelJob(call.caller(service, config), call.parameters.getOrFail("id"))?.let { (deviceId, message) -> service.sockets.send(deviceId, service.encode(message)) }
            call.respond(mapOf("ok" to true))
        }
        post("/api/jobs/{id}/pause") {
            val (deviceId, message) = service.pauseJob(call.caller(service, config), call.parameters.getOrFail("id"), true)
            service.sockets.send(deviceId, service.encode(message)); call.respond(mapOf("ok" to true))
        }
        post("/api/jobs/{id}/resume") {
            val (deviceId, message) = service.pauseJob(call.caller(service, config), call.parameters.getOrFail("id"), false)
            service.sockets.send(deviceId, service.encode(message)); call.respond(mapOf("ok" to true))
        }
        get("/api/events") { consoleStream(call, service) }
        route("/api/device") {
            post("/register") { call.respond(service.registerDevice(call.caller(service, config), call.receive())) }
            webSocket("/socket") { deviceSocket(this, service, devRuns) }
            post("/catalog") { service.uploadCatalog(call.caller(service, config), call.receive()); call.respond(mapOf("ok" to true)) }
            post("/jobs/{id}/events") { service.appendEvents(call.caller(service, config), call.parameters.getOrFail("id"), call.receive()); call.respond(mapOf("ok" to true)) }
            post("/jobs/{id}/report") { service.report(call.caller(service, config), call.parameters.getOrFail("id"), call.receive()); call.respond(mapOf("ok" to true)) }
            post("/notices") { service.deviceNotices(call.caller(service, config), call.receive()); call.respond(mapOf("ok" to true)) }
        }
    }
}
/** [url] with [query] added, whether or not it has a query already. */
private fun withQuery(url: String, query: String) = url + (if ('?' in url) "&" else "?") + query
/** "https://host:port" of a URL, as browsers send it in Origin. */
private fun originOf(url: String): String = runCatching { java.net.URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault(url)
private fun Parameters.getOrFail(name: String) = get(name) ?: throw ApiException(400, "bad_request", "请求内容无效")

private val socketLog = org.slf4j.LoggerFactory.getLogger("hgb.socket")

/**
 * A device's one connection. The handshake carries the device's bearer token; a bad one is closed with a code the app
 * understands, so it can renew and come back. Each text frame is a [DeviceMessage]; replies are [ServerMessage]s.
 */
private suspend fun deviceSocket(session: DefaultWebSocketServerSession, service: CloudService, devRuns: DevRuns? = null) = with(session) {
    val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
    val caller = token?.let(service::authenticate)
    if (caller == null) { close(CloseReason(SocketClose.INVALID_SESSION, "invalid_session")); return }
    val deviceId = caller.deviceId
    if (caller.kind != SessionKind.DEVICE || deviceId == null) { close(CloseReason(SocketClose.NOT_A_DEVICE, "device_only")); return }
    val (connection, replaced) = service.sockets.attach(caller.userId, deviceId,
        close = { runCatching { close(CloseReason(SocketClose.REPLACED, "closed")) } })
    replaced?.close?.invoke()
    val openedAt = System.currentTimeMillis()
    socketLog.info("device=${deviceId.take(8)} connected${if (replaced != null) ", replacing an older connection" else ""}")
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
                is DeviceMessage.Status -> {
                    service.status(caller, message.status).forEach { connection.outbox.trySend(service.encode(it)) }
                    // A workbench command sent into a connection that was already dead is sent again once the phone is back.
                    if (message.status.phase == "IDLE") devRuns?.unstarted(caller.userId, deviceId)?.forEach { connection.outbox.trySend(service.encode(it)) }
                }
            }
        }
    } catch (_: ApiException) {
        close(CloseReason(SocketClose.NOT_A_DEVICE, "device_only"))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        socketLog.info("device=${deviceId.take(8)} connection failed: ${failure.javaClass.simpleName} ${failure.message.orEmpty()}"); throw failure
    } finally {
        socketLog.info("device=${deviceId.take(8)} disconnected after ${(System.currentTimeMillis() - openedAt) / 1000}s")
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
private const val DELIVERY_MILLIS = 2_000L
private const val KEEPALIVE_MILLIS = 15_000L
private const val SESSION_RECHECK_MILLIS = 60_000L
private const val MAX_FRAMES_PER_SECOND = 5
private const val MAX_PENDING_EVENTS = 256
private const val MAX_DEVICE_FRAME = 1L shl 20
