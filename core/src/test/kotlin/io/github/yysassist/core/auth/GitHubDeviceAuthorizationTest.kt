package io.github.yysassist.core.auth

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/** Real local HTTP calls; only the monotonic protocol clock is virtual. No real authorization. */
class GitHubDeviceAuthorizationTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private class Clock {
        var now = 0L
        val waits = mutableListOf<Long>()
        suspend fun wait(millis: Long) { waits += millis; now += millis; yield() }
    }
    @Before fun open() {
        server = MockWebServer(); server.start()
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    }
    @After fun close() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    private fun challenge(expires: Long = 900, interval: Long? = 5, uri: String = "https://github.com/login/device") =
        """{"device_code":"fixture_device_code","user_code":"TEST-CODE","verification_uri":"$uri","expires_in":$expires${interval?.let { ",\"interval\":$it" } ?: ""}}"""
    private fun granted(expiring: Boolean = true) = """{"access_token":"fixture_access","token_type":"bearer"${if (expiring) ",\"expires_in\":28800,\"refresh_token\":\"fixture_refresh\",\"refresh_token_expires_in\":15897600" else ""}}"""
    private fun auth(clock: Clock = Clock()) = GitHubDeviceAuthorization("fixture_public_client", http, server.url("/"), { clock.now }, clock::wait)
    private suspend fun failure(problem: GitHubAuthProblem, block: suspend () -> Unit): GitHubAuthException {
        try { block(); fail("Expected $problem") }
        catch (error: GitHubAuthException) { assertEquals(problem, error.problem); return error }
        error("unreachable")
    }
    @Test fun aDeviceLoginUsesOnlyClientIdShowsTheOfficialCodeAndReturnsRedactedCredentials(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge()))
        server.enqueue(MockResponse().setBody(granted()))
        val clock = Clock(); var prompt: GitHubDevicePrompt? = null
        val result = auth(clock).authorize { prompt = it }
        assertEquals("TEST-CODE", prompt!!.userCode)
        assertEquals("https://github.com/login/device", prompt!!.verificationUri.toString())
        assertEquals(listOf(5_000L), clock.waits)
        assertEquals("fixture_access", result.accessToken.value); assertEquals("fixture_refresh", result.refreshToken!!.value)
        assertEquals(28_800L, result.expiresInSeconds); assertEquals(15_897_600L, result.refreshExpiresInSeconds)
        assertFalse(result.toString().contains("fixture_")); assertFalse(result.accessToken.toString().contains("fixture_"))
        assertFalse(prompt.toString().contains("TEST-CODE"))
        val start = server.takeRequest(); val poll = server.takeRequest()
        assertEquals("/login/device/code", start.path); assertEquals("POST", start.method)
        assertEquals("client_id=fixture_public_client", start.body.readUtf8())
        val form = poll.body.readUtf8()
        assertEquals("/login/oauth/access_token", poll.path)
        assertTrue(form.contains("device_code=fixture_device_code")); assertTrue(form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        assertFalse(form.contains("client_secret")); assertFalse(form.contains("scope="))
        assertEquals("application/json", poll.getHeader("Accept")); assertNull(poll.getHeader("Authorization"))
    }
    @Test fun pendingAndSlowDownRespectTheServersNewMinimumAndNeverPollFaster(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge(interval = 1)))
        server.enqueue(MockResponse().setBody("""{"error":"slow_down","interval":7}"""))
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setBody(granted(false)))
        val clock = Clock(); val result = auth(clock).authorize {}
        assertEquals(listOf(1_000L, 7_000L, 7_000L), clock.waits)
        assertNull(result.expiresInSeconds); assertNull(result.refreshToken)
        assertEquals(4, server.requestCount)
    }
    @Test fun aMissingIntervalUsesTheProtocolDefaultAndADecreasingSlowDownStillAddsFiveSeconds(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge(interval = null)))
        server.enqueue(MockResponse().setBody("""{"error":"slow_down","interval":1}"""))
        server.enqueue(MockResponse().setBody(granted(false)))
        val clock = Clock(); auth(clock).authorize {}
        assertEquals(listOf(5_000L, 10_000L), clock.waits)
    }
    @Test fun localExpiryStopsWithoutAnotherRequestOrCreatingAnotherChallenge(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge(expires = 7)))
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        val clock = Clock()
        failure(GitHubAuthProblem.EXPIRED) { auth(clock).authorize {} }
        assertEquals(listOf(5_000L, 2_000L), clock.waits); assertEquals(2, server.requestCount)
    }
    @Test fun disabledFlowDenialAndServerExpiryKeepTheirMeaningAndDoNotRetry(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":"device_flow_disabled"}"""))
        failure(GitHubAuthProblem.CONFIGURATION) { auth().authorize {} }
        for ((remote, expected) in listOf("access_denied" to GitHubAuthProblem.DENIED,
            "expired_token" to GitHubAuthProblem.EXPIRED, "token_expired" to GitHubAuthProblem.EXPIRED)) {
            server.enqueue(MockResponse().setBody(challenge()))
            server.enqueue(MockResponse().setBody("""{"error":"$remote"}"""))
            failure(expected) { auth().authorize {} }
        }
        assertEquals(7, server.requestCount)
    }
    @Test fun malformedResponsesNeverLeakSecretsOrOpenAnUntrustedVerificationPage(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge(uri = "https://example.invalid/login/device")))
        failure(GitHubAuthProblem.INVALID_RESPONSE) { auth().authorize { fail("Untrusted prompt") } }
        for (body in listOf("""{"access_token":"fixture_private","token_type":bad}""",
            """{"access_token":"fixture_private","token_type":"basic"}""",
            """{"access_token":"fixture_私有","token_type":"bearer"}""",
            """{"access_token":"fixture_private","token_type":"bearer","expires_in":28800}""",
            """{"error":"authorization_pending","access_token":"fixture_private"}""")) {
            server.enqueue(MockResponse().setBody(challenge())); server.enqueue(MockResponse().setBody(body))
            val error = failure(GitHubAuthProblem.INVALID_RESPONSE) { auth().authorize {} }
            assertFalse(error.stackTraceToString().contains("fixture_private")); assertNull(error.cause)
        }
        assertEquals(11, server.requestCount)
    }
    @Test fun aRedirectCannotForwardDeviceCodesAndAnHttpFailureIsNotPending(): Unit = runBlocking {
        MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse().setBody(challenge()))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/untrusted")))
            assertEquals(307, failure(GitHubAuthProblem.HTTP) { auth().authorize {} }.httpCode)
            assertEquals(0, other.requestCount)
        }
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(503, failure(GitHubAuthProblem.HTTP) { auth().authorize {} }.httpCode)
        assertEquals(3, server.requestCount)
    }
    @Test fun cancellationStopsTheOutstandingHttpCallAndMakesNoFurtherPoll(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { auth().authorize {} }
        withContext(Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        job.cancelAndJoin()
        withTimeout(2_000) { while (http.dispatcher.runningCallsCount() != 0) delay(10) }
        assertEquals(2, server.requestCount)
    }
    @Test fun anIncompleteTokenResponseIsUnconfirmedWithoutAnAutomaticReplay(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge()))
        server.enqueue(MockResponse().setBody(granted()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        failure(GitHubAuthProblem.NETWORK) { auth().authorize {} }
        assertEquals(2, server.requestCount)
    }
    @Test fun aServerRetryAfterHeaderCannotAutomaticallyReplayATokenGrantPost(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(challenge()))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody(granted()))
        assertEquals(503, failure(GitHubAuthProblem.HTTP) { auth().authorize {} }.httpCode)
        assertEquals(2, server.requestCount)
    }
}
