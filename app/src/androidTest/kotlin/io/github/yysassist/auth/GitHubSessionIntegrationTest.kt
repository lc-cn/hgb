package io.github.yysassist.auth

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.auth.*
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*

/** Actual Android Keystore, atomic file and HTTPS. All credentials and accounts are fixtures. */
class GitHubSessionIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var file: File
    private lateinit var alias: String
    private lateinit var store: EncryptedGitHubSessionStore
    private val scopes = mutableListOf<CoroutineScope>()
    private var wall = 1_000_000L
    @Before fun open() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).callTimeout(5, TimeUnit.SECONDS).build()
        alias = "qa.github.${UUID.randomUUID()}"
        file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "$alias.bin")
        store = EncryptedGitHubSessionStore(file, alias)
    }
    @After fun close(): Unit = runBlocking {
        scopes.forEach { it.cancel() }; scopes.forEach { it.coroutineContext[Job]!!.join() }
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }
    private fun saved(expired: Boolean = false) = StoredGitHubSession("fixture-session", "fixture-client", "fixture_access",
        if (expired) wall - 1 else wall + 28_800_000, "fixture_refresh", wall + 158_976_000_00L, login = "fixture-owner")
    private suspend fun session(backing: GitHubSessionStore = store): GitHubSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        var monotonic = 0L
        val protocol = GitHubDeviceAuthorization("fixture-client", http, server.url("/"), { monotonic }, { monotonic += it })
        return GitHubSession("fixture-client", backing, protocol, GitHubUserApi(http, server.url("/")), scope) { wall }.also {
            withTimeout(5_000) { it.state.first { state -> state != GitHubSessionState.Loading } }
        }
    }
    private fun grant(access: String = "fixture_access", refresh: String = "fixture_refresh") =
        """{"access_token":"$access","token_type":"bearer","expires_in":28800,"refresh_token":"$refresh","refresh_token_expires_in":15897600}"""
    private fun loginResponses(access: String = "fixture_access", refresh: String = "fixture_refresh") {
        server.enqueue(MockResponse().setBody("""{"device_code":"fixture_device","user_code":"TEST-CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":1}"""))
        server.enqueue(MockResponse().setBody(grant(access, refresh)))
        server.enqueue(MockResponse().setBody("""{"id":1,"login":"fixture-owner"}"""))
    }
    private suspend fun connected(host: GitHubSession) = withTimeout(10_000) {
        host.state.first { it is GitHubSessionState.Connected && it.login == "fixture-owner" && !it.checking }
    }
    private suspend fun authFailure(problem: GitHubAuthProblem, block: suspend () -> Unit) {
        try { block(); fail("Expected $problem") } catch (error: GitHubAuthException) { assertEquals(problem, error.problem) }
    }
    @Test fun ciphertextReopensAndCorruptionOrKeyLossNeverBecomesAnEmptyLogin(): Unit = runBlocking {
        val record = saved(); store.write(record)
        val bytes = file.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("fixture_access"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("fixture_refresh"))
        assertEquals(record, EncryptedGitHubSessionStore(file, alias).read())
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupt)
        try { store.read(); fail("Corruption is not absence") } catch (_: IOException) { }
        assertArrayEquals(corrupt, file.readBytes())
        file.writeBytes(bytes)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        try { store.read(); fail("Key loss is not absence") } catch (_: IOException) { }
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }
    @Test fun loginPersistsBeforeConnectionAndAReopenedOwnerUsesTheSameCredentialWithoutAnotherLogin(): Unit = runBlocking {
        loginResponses(); val host = session(); host.signIn(); connected(host)
        assertEquals("fixture_access", store.read()!!.access)
        assertEquals("fixture-owner", store.read()!!.login)
        scopes.last().cancel(); scopes.last().coroutineContext[Job]!!.join()
        val reopened = session()
        assertEquals("fixture_access", reopened.token()!!.value)
        assertEquals("fixture-owner", (reopened.state.value as GitHubSessionState.Connected).login)
        assertEquals(3, server.requestCount)
        assertEquals("/login/device/code", server.takeRequest().path)
        assertEquals("/login/oauth/access_token", server.takeRequest().path)
        val user = server.takeRequest(); assertEquals("/user", user.path); assertEquals("Bearer fixture_access", user.getHeader("Authorization"))
    }
    @Test fun cancellingDeviceLoginStopsTheCallAndDoesNotCreateALocalSession(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"device_code":"fixture_device","user_code":"TEST-CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":1}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val host = session(); host.signIn()
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        host.cancelSignIn()
        withTimeout(5_000) { host.state.first { it is GitHubSessionState.SignedOut } }
        withTimeout(5_000) { while (http.dispatcher.runningCallsCount() != 0) delay(10) }
        assertNull(store.read()); assertEquals(2, server.requestCount)
    }
    @Test fun concurrentReadersAndCallerCancellationDoNotAbandonOrDuplicateARefreshRotation(): Unit = runBlocking {
        store.write(saved(expired = true))
        val host = session()
        val release = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(5, TimeUnit.SECONDS)); return MockResponse().setBody(grant("fixture_new_access", "fixture_new_refresh"))
            }
        }
        val first = async { host.token() }; val second = async { host.token() }
        try {
            val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            assertTrue(request.body.readUtf8().contains("grant_type=refresh_token"))
            assertTrue(store.read()!!.refreshPending)
            first.cancelAndJoin()
        } finally { release.countDown() }
        assertEquals("fixture_new_access", withTimeout(5_000) { second.await() }!!.value)
        val renewed = store.read()!!
        assertEquals("fixture_new_refresh", renewed.refresh); assertFalse(renewed.refreshPending)
        scopes.last().cancel(); scopes.last().coroutineContext[Job]!!.join()
        assertEquals("fixture_new_access", session().token()!!.value)
        assertEquals(1, server.requestCount)
    }
    @Test fun aLostRefreshResponseLeavesAnUnconfirmedMarkerAndReopeningCannotReplayIt(): Unit = runBlocking {
        store.write(saved(expired = true)); val host = session()
        server.enqueue(MockResponse().setBody(grant()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        authFailure(GitHubAuthProblem.NETWORK) { host.token() }
        assertTrue(store.read()!!.refreshPending)
        scopes.last().cancel(); scopes.last().coroutineContext[Job]!!.join()
        val reopened = session()
        assertTrue(reopened.state.value is GitHubSessionState.SignedOut)
        authFailure(GitHubAuthProblem.EXPIRED) { reopened.token() }
        assertEquals(1, server.requestCount)
    }
    @Test fun aLate401CannotInvalidateANewerLoginAndLogoutOnlyClearsTheLocalCredential(): Unit = runBlocking {
        store.write(saved()); val host = session(); val old = host.token()!!
        host.signOut(); withTimeout(5_000) { host.state.first { it is GitHubSessionState.SignedOut } }
        assertNull(store.read())
        // A grant is distinct even if an upstream fixture repeats the opaque token bytes.
        loginResponses("fixture_access", "fixture_new_refresh")
        host.signIn(); connected(host)
        host.rejected(old)
        assertFalse(store.read()!!.accessRejected)
        assertEquals("fixture_access", host.token()!!.value)
        assertEquals(3, server.requestCount)
    }
    @Test fun aCommittedWriteWithLostAcknowledgementIsReconciledWithoutRepeatingAuthorization(): Unit = runBlocking {
        var once = true
        val backing = object : GitHubSessionStore by store {
            override suspend fun write(record: StoredGitHubSession?) {
                store.write(record)
                if (once) { once = false; throw IOException("fixture acknowledgement lost") }
            }
        }
        loginResponses(); val host = session(backing); host.signIn(); connected(host)
        assertEquals("fixture_access", store.read()!!.access)
        assertEquals(3, server.requestCount)
    }
    @Test fun aLookupFailureIsNotAbsenceAndCancellingAnExplicitReloginRestoresTheStorageError(): Unit = runBlocking {
        store.write(saved()); val original = file.readBytes()
        val backing = object : GitHubSessionStore by store { override suspend fun read(): StoredGitHubSession? = throw IOException("fixture read unavailable") }
        val host = session(backing)
        assertTrue(host.state.value is GitHubSessionState.Failure)
        authFailure(GitHubAuthProblem.STORAGE) { host.token() }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        host.signIn()
        withTimeout(5_000) { host.state.first { it is GitHubSessionState.SigningIn } }
        host.cancelSignIn()
        withTimeout(5_000) { host.state.first { it is GitHubSessionState.Failure } }
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun anOlderRejectedRequestCannotReplaceTheActiveDevicePrompt(): Unit = runBlocking {
        store.write(saved().copy(accessExpiresAt = null, refresh = null, refreshExpiresAt = null))
        val host = session(); val old = host.token()!!
        host.rejected(old)
        assertTrue(host.state.value is GitHubSessionState.SignedOut)
        server.enqueue(MockResponse().setBody("""{"device_code":"fixture_device","user_code":"TEST-CODE","verification_uri":"https://github.com/login/device","expires_in":900,"interval":1}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        host.signIn()
        withTimeout(5_000) { host.state.first { it is GitHubSessionState.SigningIn && it.prompt != null } }
        host.rejected(old)
        assertTrue("A background credential result cannot hide an explicit login", host.state.value is GitHubSessionState.SigningIn)
        assertEquals("TEST-CODE", (host.state.value as GitHubSessionState.SigningIn).prompt!!.userCode)
        host.cancelSignIn()
        withTimeout(5_000) { host.state.first { it is GitHubSessionState.SignedOut } }
    }
    @Test fun aConfirmedExpiredRefreshRemainsKnownAndCannotBeRetriedAfterReopening(): Unit = runBlocking {
        store.write(saved(expired = true)); val host = session()
        server.enqueue(MockResponse().setBody("""{"error":"bad_refresh_token"}"""))
        authFailure(GitHubAuthProblem.EXPIRED) { host.token() }
        assertEquals(GitHubRenewal.REAUTHORIZE, store.read()!!.renewal)
        scopes.last().cancel(); scopes.last().coroutineContext[Job]!!.join()
        val reopened = session()
        val message = (reopened.state.value as GitHubSessionState.SignedOut).message!!
        assertTrue(message.contains("已失效")); assertFalse(message.contains("未确认"))
        authFailure(GitHubAuthProblem.EXPIRED) { reopened.token() }
        assertEquals(1, server.requestCount)
    }
}
