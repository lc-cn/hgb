package io.github.yysassist.server

import io.github.yysassist.core.cloud.*
import io.github.yysassist.protocol.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
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
    private fun api(block: suspend Api.() -> Unit) {
        val file = File.createTempFile("cloud", ".db")
        val db = Database(file.path)
        val service = CloudService(db, { to, subject, body -> mail += Triple(to, subject, body) }, github, ByteArray(32) { 7 }, { now })
        try { testApplication { application { cloudModule(service, ServerConfig("http://localhost", false)) }; Api(this).block() } }
        finally { db.close(); file.delete() }
    }
    private inner class Api(private val app: ApplicationTestBuilder) {
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
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/device/heartbeat", """{"phase":"IDLE","tasks":[{"targetId":"game","taskId":"gift","name":"每日礼包"}]}""", token).status)
            return device
        }
        suspend fun job(token: String, deviceId: String) = call(HttpMethod.Post, "/api/jobs", """{"deviceId":"$deviceId","targetId":"game","taskId":"gift"}""", token)
        suspend fun next(token: String) = call(HttpMethod.Post, "/api/device/jobs/next?waitMs=0", "{}", token)
        suspend fun lease(token: String) = json.decodeFromString(JobLease.serializer(), next(token).bodyAsText())
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
        assertEquals(HttpStatusCode.NoContent, next(bob.accessToken!!).status)
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
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/jobs", """{"deviceId":"${device.deviceId}","targetId":"game","taskId":"unknown"}""", session.accessToken).status)
    }
    @Test fun aJobRunsToCompletionAndStaleLeasesCannotReport() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        job(token, device.deviceId); val lease = lease(token)
        assertEquals(HttpStatusCode.NoContent, next(token).status)
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
        now += CloudService.LEASE_LIFETIME + 1
        assertEquals(HttpStatusCode.NoContent, next(token).status)
        assertEquals(JobState.UNKNOWN, jobs(token).single().state)
        assertEquals(HttpStatusCode.OK, report(token, second, JobState.COMPLETED).status)
    }
    @Test fun aLeaseLostWithItsConnectionReturnsToTheQueueDespiteHeartbeats() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        job(token, device.deviceId); val lost = lease(token)
        now += CloudService.ACK_LIFETIME - 1
        call(HttpMethod.Post, "/api/device/heartbeat", """{"phase":"IDLE"}""", token)
        now += 2
        assertEquals(lost.leaseNo + 1, lease(token).leaseNo)
    }
    @Test fun heartbeatsKeepARunningJobAliveAndDeliverCancelRequests() = api {
        val session = signUp("a@example.com"); val token = session.accessToken!!; val device = enroll(token)
        val created = json.decodeFromString(JobView.serializer(), job(token, device.deviceId).bodyAsText()); val lease = lease(token)
        report(token, lease, JobState.RUNNING)
        now += CloudService.LEASE_LIFETIME - 1
        call(HttpMethod.Post, "/api/jobs/${created.id}/cancel", "{}", token)
        val reply = json.decodeFromString(HeartbeatReply.serializer(), call(HttpMethod.Post, "/api/device/heartbeat", """{"phase":"RUNNING"}""", token).bodyAsText())
        assertEquals(listOf(created.id), reply.cancelJobs)
        now += CloudService.LEASE_LIFETIME - 1
        assertEquals(JobState.RUNNING, jobs(token).single().state)
    }
    @Test fun revokingADeviceSignsItOutAndClosesItsWork() = api {
        val session = signUp("a@example.com"); val web = call(HttpMethod.Post, "/api/auth/login", """{"email":"a@example.com","password":"correct horse"}""").bodyAsText().let { json.decodeFromString(AuthResult.serializer(), it) }
        val token = session.accessToken!!; val device = enroll(token); job(token, device.deviceId); report(token, lease(token), JobState.RUNNING); job(token, device.deviceId)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/devices/${device.deviceId}", token = web.accessToken).status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/device/heartbeat", """{"phase":"IDLE"}""", token).status)
        assertEquals(setOf(JobState.UNKNOWN, JobState.CANCELLED), jobs(web.accessToken!!).map { it.state }.toSet())
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
