package io.github.yysassist.server

import io.github.yysassist.core.cloud.*
import io.github.yysassist.protocol.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudApiTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var now = 1_000_000L
    private val mail = mutableListOf<Triple<String, String, String>>()
    private fun lastCode(to: String) = Regex("\\d{6}").find(mail.last { it.first == to }.third)!!.value
    private val github = object : GitHubIdentity {
        override val clientId = "client"
        override fun exchange(code: String, verifier: String, redirectUri: String) = code
        override fun user(accessToken: String) = accessToken.takeIf { it.startsWith("gh") }?.let { GitHubUser(it.removePrefix("gh"), "login$it") }
    }
    private val bundle = """{"formatVersion":1,"version":"v1","updatedAt":"2026-11-19T00:00:00Z","changelog":"","tasks":[
        {"id":"gift","targetId":"game","name":"每日礼包","category":"daily","priority":1,"steps":[{"id":"s","name":"步骤"}]},
        {"id":"tour","targetId":"game","name":"日常巡游","category":"daily","priority":1,"steps":[{"id":"s","name":"步骤"}],
         "fields":[{"id":"box","label":"礼盒","widget":"SINGLE_CHOICE","required":true,"options":[{"value":"none","label":"不选"},{"value":"currency","label":"货币"}]}]}],
        "targets":[{"id":"game","name":"游戏","androidPackages":["test.game"],"identityPolicy":"NONE","categories":[{"id":"daily","name":"日常"}]}]}"""
    private val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bundle.toByteArray()).joinToString("") { "%02x".format(it) }
    private val bothTasks = """[{"targetId":"game","taskId":"gift","name":"每日礼包"},{"targetId":"game","taskId":"tour","name":"日常巡游"}]"""
    private fun api(block: suspend Api.() -> Unit) {
        val file = File.createTempFile("cloud", ".db")
        val db = Database(file.path)
        val service = CloudService(db, { to, subject, body -> mail += Triple(to, subject, body) }, github, ByteArray(32) { 7 }, { now })
        try { testApplication { application { cloudModule(service, ServerConfig("http://localhost", false)) }; Api(this, service).block() } }
        finally { db.close(); file.delete() }
    }
    private val gift = """[{"targetId":"game","taskId":"gift","name":"每日礼包"}]"""
    private val gameApp = """[{"key":"test.game","gameId":"game","name":"游戏"}]"""
    private inner class Api(private val app: ApplicationTestBuilder, val service: CloudService) {
        suspend fun call(method: HttpMethod, path: String, body: String? = null, token: String? = null, device: Boolean = true, cookie: String? = null, csrf: Boolean = true) = app.client.request(path) {
            this.method = method
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            if (token != null) bearerAuth(token)
            if (device) header("X-YYS-Client", "device")
            if (cookie != null) header(HttpHeaders.Cookie, "yys_session=$cookie")
            if (csrf && cookie != null) header("X-YYS-Request", "1")
        }
        suspend fun signUp(email: String, password: String = "correct horse"): AuthResult {
            assertEquals(HttpStatusCode.Accepted, call(HttpMethod.Post, "/api/auth/register", """{"email":"$email","password":"$password"}""").status)
            val reply = call(HttpMethod.Post, "/api/auth/verify-email", """{"email":"$email","code":"${lastCode(email)}"}""")
            assertEquals(HttpStatusCode.OK, reply.status)
            return json.decodeFromString(AuthResult.serializer(), reply.bodyAsText())
        }
        suspend fun enroll(token: String, install: String = "install-1"): DeviceRegistered {
            val reply = call(HttpMethod.Post, "/api/device/register", """{"installId":"$install","name":"手机","platform":"android"}""", token)
            assertEquals(HttpStatusCode.OK, reply.status)
            val device = json.decodeFromString(DeviceRegistered.serializer(), reply.bodyAsText())
            beat(token)
            return device
        }
        private val sockets by lazy { app.createClient { install(WebSockets) } }
        /** Opens the device socket with [token] and runs [block] on it; the socket closes when the block ends. */
        suspend fun <T> socket(token: String, block: suspend DefaultClientWebSocketSession.() -> T): T {
            var result: T? = null
            sockets.webSocket(request = { url("/api/device/socket"); bearerAuth(token); header("X-YYS-Client", "device") }) { result = block() }
            @Suppress("UNCHECKED_CAST") return result as T
        }
        /** Messages that arrive within [waitMillis]. */
        suspend fun DefaultClientWebSocketSession.replies(waitMillis: Long = 300): List<ServerMessage> = buildList {
            while (true) {
                val frame = withTimeoutOrNull(waitMillis) { incoming.receive() } ?: break
                if (frame is Frame.Text) add(json.decodeFromString(ServerMessage.serializer(), frame.readText()))
            }
        }
        fun status(tasks: String = gift, phase: String = "IDLE", apps: String = gameApp) =
            """{"type":"status","status":{"phase":"$phase","tasks":$tasks,"catalog":{"version":"v1","digest":"$digest"},"apps":$apps}}"""
        /** One status report naming the task bundle, uploading it when the cloud asks. */
        suspend fun beat(token: String, tasks: String = gift, phase: String = "IDLE"): List<ServerMessage> {
            val replies = socket(token) { send(Frame.Text(status(tasks, phase))); replies() }
            if (ServerMessage.CatalogNeeded in replies) assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/device/catalog",
                json.encodeToString(CatalogUpload.serializer(), CatalogUpload("v1", digest, bundle)), token).status)
            return replies
        }
        suspend fun devices(token: String) = json.decodeFromString(ListSerializer(DeviceView.serializer()), call(HttpMethod.Get, "/api/devices", token = token).bodyAsText())
        /** Reads the console stream until [count] events have arrived, running [during] after the first one. */
        suspend fun stream(token: String, count: Int, lastEventId: String? = null, during: suspend () -> Unit = {}): List<Pair<String, String>> {
            val events = mutableListOf<Pair<String, String>>()
            app.client.prepareGet("/api/events") { bearerAuth(token); lastEventId?.let { header("Last-Event-ID", it) } }.execute { reply ->
                assertEquals(ContentType.Text.EventStream, reply.contentType()?.withoutParameters())
                val body = reply.bodyAsChannel()
                var id = ""; var name = ""
                while (events.size < count) {
                    val line = withTimeout(5_000) { body.readUTF8Line() } ?: break
                    when {
                        line.startsWith("id: ") -> id = line.removePrefix("id: ")
                        line.startsWith("event: ") -> name = line.removePrefix("event: ")
                        line.startsWith("data: ") -> { events += "$id:$name" to line.removePrefix("data: "); if (events.size == 1) during() }
                    }
                }
            }
            return events
        }
        private val mains = mutableMapOf<String, String>()
        /** The token's main game account, created on first use. */
        suspend fun main(token: String): String = mains[token] ?: createAccount(token, "主号").let { reply ->
            assertEquals(HttpStatusCode.Created, reply.status)
            json.decodeFromString(GameAccountView.serializer(), reply.bodyAsText()).id.also { mains[token] = it }
        }
        suspend fun createAccount(token: String, name: String, game: String = "game") = call(HttpMethod.Post, "/api/accounts", """{"gameId":"$game","name":"$name"}""", token)
        /** Marks the device's app as signed in to the account, then runs the task there. */
        suspend fun job(token: String, deviceId: String, task: String = "gift", account: String? = null): HttpResponse {
            val chosen = account ?: main(token)
            signedIn(token, deviceId, chosen)
            return call(HttpMethod.Post, "/api/jobs", """{"deviceId":"$deviceId","targetId":"game","taskId":"$task","accountId":"$chosen"}""", token)
        }
        suspend fun signedIn(token: String, deviceId: String, account: String) =
            call(HttpMethod.Put, "/api/devices/$deviceId/apps/test.game", """{"accountId":"$account"}""", token)
        fun plan(name: String = "每日", tasks: String = """[{"targetId":"game","taskId":"gift","name":"每日礼包"}]""", revision: Long = 0, days: String = "[1,2,3,4,5,6,7]") =
            """{"name":"$name","days":$days,"minuteOfDay":390,"tasks":$tasks,"revision":$revision}"""
        /** Jobs the server pushes to the device when it connects and reports. */
        suspend fun leases(token: String) = socket(token) { send(Frame.Text(status())); replies() }.filterIsInstance<ServerMessage.Job>().map { it.lease }
        suspend fun lease(token: String) = leases(token).single()
        suspend fun report(token: String, lease: JobLease, state: JobState, leaseNo: Long = lease.leaseNo) =
            call(HttpMethod.Post, "/api/device/jobs/${lease.jobId}/report", """{"leaseNo":$leaseNo,"state":"$state"}""", token)
        suspend fun jobs(token: String) = json.decodeFromString(ListSerializer(JobView.serializer()), call(HttpMethod.Get, "/api/jobs", token = token).bodyAsText())
    }

    @Test fun passwordAccountsMustVerifyTheirEmailBeforeSigningIn() = api {
        call(HttpMethod.Post, "/api/auth/register", """{"email":"A@Example.com","password":"correct horse"}""")
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/verify-email", """{"email":"a@example.com","code":"${lastCode("a@example.com")}"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"wrong password"}""").status)
    }
    @Test fun registeringATakenEmailLooksTheSameAndCannotChangeItsPassword() = api {
        signUp("a@example.com")
        assertEquals(HttpStatusCode.Accepted, call(HttpMethod.Post, "/api/auth/register", """{"email":"a@example.com","password":"attacker password"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"attacker password"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""").status)
    }
    @Test fun loginCodesWorkOnceExpireAndBurnAfterRepeatedGuesses() = api {
        signUp("a@example.com")
        call(HttpMethod.Post, "/api/auth/code/request", """{"email":"a@example.com"}""")
        val code = lastCode("a@example.com")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/code/login", """{"email":"a@example.com","code":"$code"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/code/login", """{"email":"a@example.com","code":"$code"}""").status)
        call(HttpMethod.Post, "/api/auth/code/request", """{"email":"a@example.com"}""")
        val expired = lastCode("a@example.com"); now += CloudService.CODE_LIFETIME + 1
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/code/login", """{"email":"a@example.com","code":"$expired"}""").status)
        call(HttpMethod.Post, "/api/auth/code/request", """{"email":"a@example.com"}""")
        val burned = lastCode("a@example.com")
        repeat(5) { call(HttpMethod.Post, "/api/auth/code/login", """{"email":"a@example.com","code":"${if (burned == "000000") "000001" else "000000"}"}""") }
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/code/login", """{"email":"a@example.com","code":"$burned"}""").status)
    }
    @Test fun repeatedPasswordGuessesAreLimited() = api {
        signUp("a@example.com")
        repeat(10) { call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"wrong password"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""").status)
    }
    @Test fun resettingThePasswordSignsEveryDeviceOut() = api {
        val session = signUp("a@example.com")
        call(HttpMethod.Post, "/api/auth/password/request", """{"email":"a@example.com"}""")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/password/reset", """{"email":"a@example.com","code":"${lastCode("a@example.com")}","password":"another password"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/me", token = session.accessToken).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"another password"}""").status)
    }
    @Test fun refreshTokensRotateAndAccessTokensExpire() = api {
        val session = signUp("a@example.com")
        now += CloudService.DEVICE_ACCESS_LIFETIME + 1
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/me", token = session.accessToken).status)
        val reply = call(HttpMethod.Post, "/api/auth/refresh", """{"refreshToken":"${session.refreshToken}"}""")
        val fresh = json.decodeFromString(AuthResult.serializer(), reply.bodyAsText())
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/me", token = fresh.accessToken).status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/refresh", """{"refreshToken":"${session.refreshToken}"}""").status)
    }
    @Test fun browserSessionsUseAnHttpOnlyCookieAndRejectCrossSiteWrites() = api {
        signUp("a@example.com")
        val reply = call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""", device = false)
        assertFalse(reply.bodyAsText().contains("accessToken"))
        val header = reply.headers[HttpHeaders.SetCookie]!!
        assertTrue(header.contains("HttpOnly", true)); assertTrue(header.contains("SameSite=Lax", true))
        val cookie = header.substringAfter("yys_session=").substringBefore(';')
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/me", cookie = cookie, device = false, csrf = false).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/auth/logout", "{}", cookie = cookie, device = false, csrf = false).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/auth/logout", "{}", cookie = cookie, device = false).status)
    }
    @Test fun oneUserCanNeverSeeOrUseAnotherUsersDevicesAndJobs() = api {
        val alice = signUp("alice@example.com"); val bob = signUp("bob@example.com")
        val device = enroll(alice.accessToken!!); val bobDevice = enroll(bob.accessToken!!)
        val created = json.decodeFromString(JobView.serializer(), job(alice.accessToken!!, device.deviceId).bodyAsText())
        assertEquals("[]", call(HttpMethod.Get, "/api/jobs", token = bob.accessToken).bodyAsText())
        assertFalse(call(HttpMethod.Get, "/api/devices", token = bob.accessToken).bodyAsText().contains(device.deviceId))
        assertEquals(HttpStatusCode.NotFound, job(bob.accessToken!!, device.deviceId).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/jobs/${created.id}/cancel", "{}", bob.accessToken).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/devices/${device.deviceId}", token = bob.accessToken).status)
        assertTrue(leases(bob.accessToken!!).isEmpty())
        val lease = lease(alice.accessToken!!)
        assertEquals(alice.userId, lease.userId)
        assertEquals(HttpStatusCode.NotFound, report(bob.accessToken!!, lease, JobState.RUNNING).status)
        assertNotEquals(device.deviceId, bobDevice.deviceId)
    }
    @Test fun aSameInstallSignedInAsAnotherUserBecomesASeparateDevice() = api {
        val alice = signUp("alice@example.com"); val bob = signUp("bob@example.com")
        assertNotEquals(enroll(alice.accessToken!!, "shared").deviceId, enroll(bob.accessToken!!, "shared").deviceId)
    }
    @Test fun jobsOnlyAcceptTasksTheDeviceOffers() = api {
        val session = signUp("a@example.com"); val device = enroll(session.accessToken!!)
        assertEquals(HttpStatusCode.BadRequest, job(session.accessToken!!, device.deviceId, "unknown").status)
    }
    @Test fun theCloudAsksForATaskVersionOnceAndRejectsAForgedOne() = api {
        val token = signUp("a@example.com").accessToken!!; enroll(token)
        assertFalse(ServerMessage.CatalogNeeded in beat(token, bothTasks))
        val forged = CatalogUpload("v1", digest, bundle.replace("每日礼包", "别的礼包"))
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/device/catalog", json.encodeToString(CatalogUpload.serializer(), forged), token).status)
    }
    @Test fun aJobNeedsValidSettingsOfItsAccountAndCarriesACopyOfThem() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); beat(token, bothTasks)
        val main = main(token)
        val refused = job(token, device.deviceId, "tour")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains("config_required"))
        val path = "/api/accounts/$main/configs/game/tour"
        val view = json.decodeFromString(ConfigView.serializer(), call(HttpMethod.Get, path, token = token).bodyAsText())
        assertEquals("box", (view.fields.single() as kotlinx.serialization.json.JsonObject)["id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(0L, view.revision)
        fun save(value: String, revision: Long) = """{"values":{"box":"$value"},"revision":$revision}"""
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, path, save("jade", 0), token).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, path, save("currency", 0), token).status)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, path, save("none", 0), token).status)
        assertEquals(HttpStatusCode.Created, job(token, device.deviceId, "tour").status)
        val lease = lease(token)
        assertEquals(digest, lease.digest); assertEquals("v1", lease.version)
        assertEquals("currency", (lease.configuration["box"] as kotlinx.serialization.json.JsonPrimitive).content)
        // Another account of the same game has its own settings.
        val alt = json.decodeFromString(GameAccountView.serializer(), createAccount(token, "小号").bodyAsText()).id
        assertEquals(HttpStatusCode.BadRequest, job(token, device.deviceId, "tour", alt).status)
        assertEquals("主号", jobs(token).single().accountName)
    }
    @Test fun gameAccountsBelongToAGameTheDevicesReportedAndCannotBeSeenByOthers() = api {
        val token = signUp("a@example.com").accessToken!!; enroll(token)
        assertEquals(HttpStatusCode.BadRequest, createAccount(token, "主号", game = "unknown").status)
        val main = main(token)
        assertEquals(HttpStatusCode.Conflict, createAccount(token, "主号").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/accounts/$main", """{"name":"大号","uid":"123"}""", token).status)
        val listed = json.decodeFromString(ListSerializer(GameAccountView.serializer()), call(HttpMethod.Get, "/api/accounts", token = token).bodyAsText()).single()
        assertEquals("大号" to "123", listed.name to listed.uid)
        val games = json.decodeFromString(ListSerializer(GameView.serializer()), call(HttpMethod.Get, "/api/games", token = token).bodyAsText())
        assertEquals(listOf("game"), games.map { it.id })
        val other = signUp("b@example.com").accessToken!!
        assertEquals("[]", call(HttpMethod.Get, "/api/accounts", token = other).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/accounts/$main/configs/game/gift", token = other).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/accounts/$main", token = other).status)
    }
    @Test fun plansBelongToAnAccountHoldOnlyItsGamesTasksAndRefuseStaleSaves() = api {
        val token = signUp("a@example.com").accessToken!!; enroll(token); beat(token, bothTasks)
        val main = main(token)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/accounts/$main/plans", plan(days = "[]"), token).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/accounts/$main/plans", plan(tasks = """[{"targetId":"game","taskId":"nope","name":"x"}]"""), token).status)
        val created = json.decodeFromString(PlanView.serializer(), call(HttpMethod.Post, "/api/accounts/$main/plans", plan(), token).bodyAsText())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), created.days); assertEquals(30, created.latestStartMinutes); assertEquals(1L, created.revision)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/plans/${created.id}", plan("周末", days = "[6,7]", revision = 1), token).status)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "/api/plans/${created.id}", plan("又改", revision = 1), token).status)
        val other = signUp("b@example.com").accessToken!!
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/plans/${created.id}", plan(revision = 2), other).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/accounts/$main", token = token).status)
        assertEquals("[]", call(HttpMethod.Get, "/api/plans", token = token).bodyAsText())
    }
    @Test fun anAppIsToldWhichAccountsItUses() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); val main = main(token)
        val app = """[{"key":"test.game","gameId":"game","name":"游戏"}]"""
        socket(token) { send(Frame.Text(status(apps = app))); replies() }
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/devices/${device.deviceId}/apps/test.game", """{"accountId":"$main","defaultAccountId":"$main"}""", token).status)
        val seen = devices(token).single().apps.single()
        assertEquals(main to main, seen.accountId to seen.defaultAccountId)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/devices/${device.deviceId}/apps/other", """{}""", token).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/accounts/$main", token = token).status)
        assertNull(devices(token).single().apps.single().accountId)
    }
    /** 1970-01-02 06:31 in Shanghai, one minute after a daily 06:30 plan's time. */
    private val justAfterPlan = 81_060_000L
    @Test fun aPlanFiresOnceAtItsTimeAndGoesToTheAppSignedInToItsAccount() = api {
        now = justAfterPlan - 10 * CloudService.MINUTE
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); val main = main(token)
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/api/accounts/$main/plans", plan(), token).status)
        signedIn(token, device.deviceId, main)
        val pushed = socket(token) {
            send(Frame.Text(status())); replies()
            now = justAfterPlan; service.tick(); val first = replies()
            service.tick(); first + replies()
        }
        val lease = pushed.filterIsInstance<ServerMessage.Job>().single().lease
        assertEquals("gift", lease.taskId)
        val job = jobs(token).single()
        assertEquals("每日" to "主号", job.planName to job.accountName)
        assertEquals(JobState.LEASED, job.state)
    }
    @Test fun aPlanRunNoAppCanTakeIsSkippedAfterItsLatestStart() = api {
        now = justAfterPlan - 10 * CloudService.MINUTE
        val token = signUp("a@example.com").accessToken!!; enroll(token); val main = main(token)
        call(HttpMethod.Post, "/api/accounts/$main/plans", plan(), token)
        now = justAfterPlan; service.tick()
        assertEquals(JobState.WAITING, jobs(token).single().state)
        now += 30 * CloudService.MINUTE; service.tick()
        assertEquals(JobState.SKIPPED, jobs(token).single().state)
    }
    @Test fun aPlanTimeMissedWhileTheServerWasDownIsRecordedAsSkipped() = api {
        now = justAfterPlan - 10 * CloudService.MINUTE
        val token = signUp("a@example.com").accessToken!!; enroll(token); val main = main(token)
        call(HttpMethod.Post, "/api/accounts/$main/plans", plan(), token)
        now = justAfterPlan + 35 * CloudService.MINUTE; service.tick()
        val job = jobs(token).single()
        assertEquals(JobState.SKIPPED, job.state); assertTrue(job.detail.contains("错过"))
    }
    @Test fun renamingAPlanJustAfterItsTimeKeepsThatRunButMovingItsTimeDoesNot() = api {
        now = justAfterPlan - 10 * CloudService.MINUTE
        val token = signUp("a@example.com").accessToken!!; enroll(token); val main = main(token)
        val created = json.decodeFromString(PlanView.serializer(), call(HttpMethod.Post, "/api/accounts/$main/plans", plan(), token).bodyAsText())
        now = justAfterPlan
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/plans/${created.id}", plan(name = "改名", revision = created.revision), token).status)
        service.tick()
        assertEquals("改名", jobs(token).single().planName)
    }
    @Test fun movingAPlanToATimeThatHasJustPassedWaitsForItsNextOne() = api {
        now = justAfterPlan - 10 * CloudService.MINUTE
        val token = signUp("a@example.com").accessToken!!; enroll(token); val main = main(token)
        val created = json.decodeFromString(PlanView.serializer(), call(HttpMethod.Post, "/api/accounts/$main/plans",
            plan().replace("\"minuteOfDay\":390", "\"minuteOfDay\":420"), token).bodyAsText())
        now = justAfterPlan
        call(HttpMethod.Put, "/api/plans/${created.id}", plan(revision = created.revision), token)
        service.tick()
        assertTrue(jobs(token).isEmpty())
    }
    @Test fun aDeviceHeldOnItsOwnIsNotSentJobs() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token)
        job(token, device.deviceId)
        val held = socket(token) { send(Frame.Text(status(phase = "PAUSED"))); replies() }
        assertTrue(held.none { it is ServerMessage.Job })
        assertEquals(1, leases(token).size)
    }
    @Test fun aCancelledLeaseThatWasNeverStartedIsNotOfferedAgain() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token)
        val created = json.decodeFromString(JobView.serializer(), job(token, device.deviceId).bodyAsText()); lease(token)
        call(HttpMethod.Post, "/api/jobs/${created.id}/cancel", "{}", token)
        now += CloudService.LEASE_LIFETIME + 1
        assertTrue(leases(token).isEmpty())
        assertEquals(JobState.CANCELLED, jobs(token).single().state)
    }
    @Test fun aJobWithoutADeviceGoesToAnAppSignedInToItsAccount() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); val main = main(token)
        signedIn(token, device.deviceId, main)
        val pushed = socket(token) {
            send(Frame.Text(status())); replies()
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/api/jobs", """{"targetId":"game","taskId":"gift","accountId":"$main"}""", token).status)
            replies()
        }
        assertEquals(device.deviceId, pushed.filterIsInstance<ServerMessage.Job>().single().lease.deviceId)
    }
    @Test fun aJobForAnAppSignedInToAnotherAccountIsRefused() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); main(token)
        val alt = json.decodeFromString(GameAccountView.serializer(), createAccount(token, "小号").bodyAsText()).id
        signedIn(token, device.deviceId, alt)
        val refused = call(HttpMethod.Post, "/api/jobs", """{"deviceId":"${device.deviceId}","targetId":"game","taskId":"gift","accountId":"${main(token)}"}""", token)
        assertEquals(HttpStatusCode.Conflict, refused.status)
    }
    @Test fun aPauseReachesTheDeviceRunningTheJob() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token)
        val created = json.decodeFromString(JobView.serializer(), job(token, device.deviceId).bodyAsText())
        report(token, lease(token), JobState.RUNNING)
        val pushed = socket(token) {
            send(Frame.Text(status(phase = "RUNNING"))); replies()
            call(HttpMethod.Post, "/api/jobs/${created.id}/pause", "{}", token)
            val direct = replies()
            // The wanted state is repeated with each report, so a pause sent before the run started still arrives.
            send(Frame.Text(status(phase = "RUNNING"))); direct + replies()
        }
        assertEquals(listOf(ServerMessage.Pause(created.id), ServerMessage.Pause(created.id)), pushed)
        assertTrue(jobs(token).single().pauseRequested)
    }
    @Test fun theTimeZoneIsCheckedAndKeptPerUser() = api {
        val token = signUp("a@example.com").accessToken!!
        assertEquals("Asia/Shanghai", json.decodeFromString(AccountView.serializer(), call(HttpMethod.Get, "/api/me", token = token).bodyAsText()).timeZone)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/me/time-zone", """{"timeZone":"Mars/Base"}""", token).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/me/time-zone", """{"timeZone":"Asia/Tokyo"}""", token).status)
        assertEquals("Asia/Tokyo", json.decodeFromString(AccountView.serializer(), call(HttpMethod.Get, "/api/me", token = token).bodyAsText()).timeZone)
    }
    @Test fun aRunLogIsStoredOnceAndNamesItsSteps() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token)
        job(token, device.deviceId); val lease = lease(token)
        fun batch(leaseNo: Long) = """{"leaseNo":$leaseNo,"events":[{"key":"fact:1","at":10,"kind":"step-started","step":"s"},
            {"key":"action:4","at":11,"kind":"action","step":"s","action":"tap","detail":"PRODUCED","elapsedMs":80}]}"""
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Post, "/api/device/jobs/${lease.jobId}/events", batch(lease.leaseNo + 1), token).status)
        repeat(2) { assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/device/jobs/${lease.jobId}/events", batch(lease.leaseNo), token).status) }
        val log = json.decodeFromString(ListSerializer(JobEventView.serializer()), call(HttpMethod.Get, "/api/jobs/${lease.jobId}/events", token = token).bodyAsText())
        assertEquals(listOf("step-started", "action"), log.map { it.kind })
        assertEquals("步骤", log.first().stepName)
        val other = signUp("b@example.com").accessToken!!
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/jobs/${lease.jobId}/events", token = other).status)
    }
    @Test fun aJobRunsToCompletionAndStaleLeasesCannotReport() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        job(token, device.deviceId); val lease = lease(token)
        assertTrue(leases(token).isEmpty())
        assertEquals(HttpStatusCode.Conflict, report(token, lease, JobState.RUNNING, lease.leaseNo + 1).status)
        assertEquals(HttpStatusCode.OK, report(token, lease, JobState.RUNNING).status)
        assertEquals(HttpStatusCode.OK, report(token, lease, JobState.RUNNING).status)
        assertEquals(HttpStatusCode.OK, report(token, lease, JobState.COMPLETED).status)
        assertEquals(HttpStatusCode.Conflict, report(token, lease, JobState.FAILED).status)
        assertEquals(JobState.COMPLETED, jobs(token).single().state)
    }
    @Test fun aLeaseNeverStartedIsOfferedAgainButAStartedJobIsNeverRetried() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        job(token, device.deviceId); val first = lease(token)
        now += CloudService.LEASE_LIFETIME + 1
        val second = lease(token)
        assertEquals(first.jobId, second.jobId); assertEquals(first.leaseNo + 1, second.leaseNo)
        assertEquals(HttpStatusCode.Conflict, report(token, first, JobState.RUNNING).status)
        report(token, second, JobState.RUNNING)
        // A device that keeps reporting keeps its running job alive; one that goes silent loses it.
        now += CloudService.LEASE_LIFETIME + 1; service.tick()
        assertEquals(JobState.UNKNOWN, jobs(token).single().state)
        assertEquals(HttpStatusCode.OK, report(token, second, JobState.COMPLETED).status)
    }
    @Test fun aLeaseLostWithItsConnectionReturnsToTheQueueDespiteStatusReports() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        job(token, device.deviceId); val lost = lease(token)
        now += CloudService.ACK_LIFETIME - 1
        beat(token)
        now += 2
        assertEquals(lost.leaseNo + 1, lease(token).leaseNo)
    }
    @Test fun statusReportsKeepARunningJobAliveAndDeliverCancelRequests() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        val created = json.decodeFromString(JobView.serializer(), job(token, device.deviceId).bodyAsText()); val lease = lease(token)
        report(token, lease, JobState.RUNNING)
        now += CloudService.LEASE_LIFETIME - 1
        call(HttpMethod.Post, "/api/jobs/${created.id}/cancel", "{}", token)
        assertEquals(listOf(ServerMessage.Cancel(listOf(created.id))), beat(token, phase = "RUNNING"))
        now += CloudService.LEASE_LIFETIME - 1
        assertEquals(JobState.RUNNING, jobs(token).single().state)
    }
    @Test fun aConnectedDeviceIsToldOfACancelAtOnce() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token)
        val created = json.decodeFromString(JobView.serializer(), job(token, device.deviceId).bodyAsText())
        report(token, lease(token), JobState.RUNNING)
        val pushed = socket(token) {
            send(Frame.Text(status(phase = "RUNNING"))); replies()
            call(HttpMethod.Post, "/api/jobs/${created.id}/cancel", "{}", token)
            replies()
        }
        assertEquals(listOf(ServerMessage.Cancel(listOf(created.id))), pushed)
    }
    @Test fun aDeviceIsOnlineOnlyWhileItsSocketIsOpenAndShowsItsApps() = api {
        val token = signUp("a@example.com").accessToken!!; enroll(token)
        assertFalse(devices(token).single().online)
        val app = """[{"key":"test.game","gameId":"game","name":"游戏","phase":"RUNNING","currentTask":"每日礼包","queued":1}]"""
        val seen = socket(token) { send(Frame.Text(status(apps = app))); replies(); devices(token).single() }
        assertTrue(seen.online)
        assertEquals(listOf(AppView("test.game", "game", "游戏", "RUNNING", "每日礼包", 1)), seen.apps)
        assertFalse(devices(token).single().online)
    }
    @Test fun aSocketWithoutAValidDeviceSessionIsRefusedWithACode() = api {
        val token = signUp("a@example.com").accessToken!!
        assertEquals(SocketClose.NOT_A_DEVICE, socket(token) { closeReason.await()?.code })
        assertEquals(SocketClose.INVALID_SESSION, socket("forged") { closeReason.await()?.code })
    }
    @Test fun revokingADeviceSignsItOutAndClosesItsWork() = api {
        val session = signUp("a@example.com"); val web = call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""").bodyAsText().let { json.decodeFromString(AuthResult.serializer(), it) }
        val token = session.accessToken!!; val device = enroll(token); job(token, device.deviceId); report(token, lease(token), JobState.RUNNING); job(token, device.deviceId)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/devices/${device.deviceId}", token = web.accessToken).status)
        assertEquals(SocketClose.INVALID_SESSION, socket(token) { closeReason.await()?.code })
        assertEquals(setOf(JobState.UNKNOWN, JobState.CANCELLED), jobs(web.accessToken!!).map { it.state }.toSet())
    }
    @Test fun theConsoleStreamStartsWithASnapshotThenSendsChanges() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); signedIn(token, device.deviceId, main(token))
        val events = stream(token, 3) { call(HttpMethod.Post, "/api/jobs", """{"deviceId":"${device.deviceId}","targetId":"game","taskId":"gift","accountId":"${main(token)}"}""", token); beat(token) }
        assertEquals("snapshot", events[0].first.substringAfterLast(':'))
        assertEquals(device.deviceId, json.decodeFromString(ConsoleSnapshot.serializer(), events[0].second).devices.single().id)
        assertEquals("jobs", events[1].first.substringAfterLast(':'))
        assertEquals("device", events[2].first.substringAfterLast(':'))
        val other = signUp("b@example.com").accessToken!!
        assertEquals("snapshot", stream(other, 1).single().first.substringAfterLast(':'))
        assertTrue(json.decodeFromString(ConsoleSnapshot.serializer(), stream(other, 1).single().second).devices.isEmpty())
    }
    @Test fun aReconnectingConsoleGetsOnlyWhatItMissedOrANewSnapshot() = api {
        val token = signUp("a@example.com").accessToken!!; val device = enroll(token); signedIn(token, device.deviceId, main(token))
        val first = stream(token, 1).single().first.substringBeforeLast(':')
        call(HttpMethod.Post, "/api/jobs", """{"deviceId":"${device.deviceId}","targetId":"game","taskId":"gift","accountId":"${main(token)}"}""", token)
        val missed = stream(token, 1, lastEventId = first).single().first
        assertEquals("jobs", missed.substringAfterLast(':'))
        assertEquals(first.substringAfter('.').toLong() + 1, missed.substringBeforeLast(':').substringAfter('.').toLong())
        // An id from before a restart, or one this server never sent, gets a fresh snapshot.
        assertEquals("snapshot", stream(token, 1, lastEventId = "earlier.${first.substringAfter('.')}").single().first.substringAfterLast(':'))
        assertEquals("snapshot", stream(token, 1, lastEventId = "${first.substringBefore('.')}.999999").single().first.substringAfterLast(':'))
    }
    @Test fun gitHubSignInNeverMergesIntoAnExistingEmailAccountAndKeepsOneLoginMethod() = api {
        val email = signUp("a@example.com")
        val gitHub = json.decodeFromString(AuthResult.serializer(), call(HttpMethod.Post, "/api/auth/github/token", """{"accessToken":"gh42"}""").bodyAsText())
        assertNotEquals(email.userId, gitHub.userId)
        assertEquals(gitHub.userId, json.decodeFromString(AuthResult.serializer(), call(HttpMethod.Post, "/api/auth/github/token", """{"accessToken":"gh42"}""").bodyAsText()).userId)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/auth/github/token", """{"accessToken":"bad"}""").status)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Delete, "/api/auth/github", token = gitHub.accessToken).status)
    }
    @Test fun storedSecretsAreNeverPlainText() = api {
        val session = signUp("a@example.com", "correct horse")
        assertTrue(Passwords.hash("correct horse").startsWith("argon2id$"))
        assertTrue(Passwords.verify("correct horse", Passwords.hash("correct horse")))
        assertFalse(Passwords.verify("correct horse", null))
        assertNotEquals(session.accessToken, Secrets.sha256(session.accessToken!!))
    }
}
