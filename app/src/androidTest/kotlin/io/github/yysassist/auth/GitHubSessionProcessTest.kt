package io.github.yysassist.auth

import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.AssistApplication
import io.github.yysassist.core.auth.*
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*

/** Two explicit runs separated by ADB force-stop; dedicated fixture file/key only. */
class GitHubSessionProcessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "qa.github.process.v1"
    private val file get() = File(context.noBackupFilesDir, "$alias.bin")
    private val seed get() = File(context.filesDir, "qa-github-process-seed.json")
    private fun phase(value: String) = Assume.assumeTrue("Explicit emulator session process QA only", Build.HARDWARE == "ranchu" &&
        InstrumentationRegistry.getArguments().getString("githubSessionProcessPhase") == value)
    private fun transport(): Pair<MockWebServer, OkHttpClient> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        return server to OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).callTimeout(30, TimeUnit.SECONDS).build()
    }
    private fun scope() = CoroutineScope(SupervisorJob((context.applicationContext as AssistApplication).scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    @Test fun seedBeforeExternalProcessStop(): Unit = runBlocking {
        phase("seed")
        assertFalse(file.exists()); assertFalse(seed.exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        val store = EncryptedGitHubSessionStore(file, alias)
        val now = System.currentTimeMillis()
        store.write(StoredGitHubSession("fixture-process", "fixture-client", "fixture_access", now - 1, "fixture_refresh", now + 60_000))
        val (server, http) = transport()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val scope = scope()
        val host = GitHubSession("fixture-client", store, GitHubDeviceAuthorization("fixture-client", http, server.url("/")), GitHubUserApi(http, server.url("/")), scope)
        withTimeout(5_000) { host.state.first { it != GitHubSessionState.Loading } }
        scope.async { host.token() }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertTrue(store.read()!!.refreshPending)
        seed.writeText(buildJsonObject { put("pid", Process.myPid()); put("pending", true) }.toString())
        // Deliberately leave the real HTTP request/application child alive until the external stop.
    }
    @Test fun verifyAfterExternalProcessStop(): Unit = runBlocking {
        phase("verify")
        val old = Json.parseToJsonElement(seed.readText()).jsonObject.getValue("pid").jsonPrimitive.int
        assertNotEquals(old, Process.myPid())
        val store = EncryptedGitHubSessionStore(file, alias)
        assertTrue(store.read()!!.refreshPending)
        val (server, http) = transport(); val scope = scope()
        try {
            val host = GitHubSession("fixture-client", store, GitHubDeviceAuthorization("fixture-client", http, server.url("/")), GitHubUserApi(http, server.url("/")), scope)
            withTimeout(5_000) { host.state.first { it != GitHubSessionState.Loading } }
            assertTrue(host.state.value is GitHubSessionState.SignedOut)
            try { host.token(); fail("An interrupted refresh cannot replay") }
            catch (error: GitHubAuthException) { assertEquals(GitHubAuthProblem.EXPIRED, error.problem) }
            assertEquals(0, server.requestCount)
            context.filesDir.resolve("qa-github-process-result.json").writeText(buildJsonObject {
                put("oldPid", old); put("newPid", Process.myPid()); put("encryptedPendingSurvived", true); put("refreshReplayed", false)
            }.toString())
        } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            listOf(file, File(file.path + ".bak"), File(file.path + ".new"), seed).forEach { it.delete() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
