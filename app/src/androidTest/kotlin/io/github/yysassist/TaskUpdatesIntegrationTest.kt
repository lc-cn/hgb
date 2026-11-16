package io.github.yysassist

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local HTTPS + the real source/controller + real SQLite; no publishing or game actions. */
@RunWith(AndroidJUnit4::class)
class TaskUpdatesIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var updates: TaskUpdates
    private lateinit var scope: CoroutineScope
    private lateinit var name: String
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(5, TimeUnit.SECONDS).build()
        name = "updates-integration-${UUID.randomUUID()}.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.databaseBuilder(context, AssistDatabase::class.java, name).build()
        library = TaskVersionLibrary(database, BundleCodec(listOf(CapabilityContract("test.observe", 1) { null })))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        updates = TaskUpdates(library, GitHubTaskSource(TaskRepository("test", "tasks"), server.url("/"), client), scope)
    }
    @After fun close() = runBlocking {
        scope.cancel(); scope.coroutineContext[Job]!!.join()
        database.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    private fun content(version: String) = """{
      "formatVersion":1,"version":"$version","updatedAt":"2026-11-16T00:00:00Z","changelog":"$version 更新说明",
      "targets":[{"id":"utility","name":"测试应用","androidPackages":["test.utility"],"identityPolicy":"NONE","categories":[{"id":"tools","name":"工具"}]}],
      "tasks":[{"id":"inspect","targetId":"utility","name":"只读测试任务","category":"tools","priority":1,
        "steps":[{"id":"look","name":"观察","actions":[{"id":"screen","capability":"test.observe","contractVersion":1,"inputs":{}}]}]}]
    }"""
    private fun enqueue(version: String, delay: Boolean = false) {
        val body = content(version)
        server.enqueue(MockResponse().setBody("""{"tag_name":"$version","draft":false,"prerelease":false,"assets":[{"id":42,"name":"task-bundle.json","state":"uploaded","size":${body.toByteArray().size},"digest":"sha256:${TaskVersionLibrary.sha256(body)}"}]}"""))
        val response = MockResponse().setBody(body)
        if (delay) response.setBodyDelay(200, TimeUnit.MILLISECONDS)
        server.enqueue(response)
    }
    private suspend fun finished() = withTimeout(10_000) { updates.pull.filterIsInstance<TaskPullState.Finished>().first() }
    private suspend fun active(version: String) = withTimeout(10_000) {
        updates.catalog.filterIsInstance<VersionCatalogState.Ready>().first { it.catalog.activeVersion == version }
        updates.operation.first { it == null }
    }

    @Test fun sameAppPullsTwoVersionsWithoutSwitchingPinnedWorkAndMergesConcurrentPulls() = runBlocking {
        enqueue("one"); updates.pullLatest(); finished()
        assertNull(library.snapshot().activeVersion)
        updates.activate("one"); active("one")
        library.pinActive("run-1")
        enqueue("two", delay = true)
        updates.pullLatest(); updates.pullLatest(); finished()
        assertEquals(4, server.requestCount)
        assertEquals("one", library.snapshot().activeVersion)
        assertEquals(2, library.snapshot().versions.size)
        assertTrue(library.snapshot().hasKnownUpdate)
        assertEquals("one", (library.pinActive("run-1") as PinVersionResult.Pinned).content.bundle.version)
        assertTrue(library.snapshot().activeContent is ActiveTaskContent.Available)
        library.release("run-1"); updates.activate("two"); active("two")
        assertFalse(library.snapshot().hasKnownUpdate)
        updates.delete("one")
        withTimeout(10_000) {
            updates.catalog.filterIsInstance<VersionCatalogState.Ready>().first { it.catalog.versions.size == 1 }
        }
        assertEquals(listOf("two"), library.snapshot().versions.map { it.version })
    }

    @Test fun remoteFailureKeepsLocalSelectionAndUsableContent() = runBlocking {
        enqueue("one"); updates.pullLatest(); finished(); updates.activate("one"); active("one")
        server.enqueue(MockResponse().setResponseCode(403))
        updates.pullLatest()
        val result = withTimeout(10_000) { updates.pull.filterIsInstance<TaskPullState.Failure>().first() }
        assertTrue(result.message.contains("403"))
        assertEquals("one", library.snapshot().activeVersion)
        assertEquals("one", library.activeCatalog()!!.version)
        assertEquals(1, library.snapshot().versions.size)
    }
}
