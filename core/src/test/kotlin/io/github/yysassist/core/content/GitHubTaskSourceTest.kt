package io.github.yysassist.core.content

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GitHubTaskSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: GitHubTaskSource
    private lateinit var client: OkHttpClient
    @Before fun open() {
        server = MockWebServer(); server.start()
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        source = GitHubTaskSource(TaskRepository("test-owner", "test-repo"), server.url("/"), client)
    }
    @After fun close() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun release(body: String = "内容") = """{"tag_name":"v-test","draft":false,"prerelease":false,"assets":[{"name":"task-bundle.json","id":42,"size":${body.toByteArray().size},"state":"uploaded","digest":"sha256:${hash(body)}"}],"new_github_field":true}"""
    private suspend fun failure(problem: TaskSourceProblem, action: suspend () -> Unit): TaskSourceException {
        try { action(); fail("Expected $problem") } catch (e: TaskSourceException) { assertEquals(problem, e.problem); return e }
        error("unreachable")
    }

    @Test fun canonicalLatestAndAssetDownloadUseHeadersAndVerifyBytes() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        server.enqueue(MockResponse().setBody("内容"))
        val version = source.latest()!!
        assertEquals("v-test", version.version)
        assertEquals("内容", source.download(version))
        val latest = server.takeRequest(); val asset = server.takeRequest()
        assertEquals("/repos/test-owner/test-repo/releases/latest", latest.path)
        assertEquals("2022-11-28", latest.getHeader("X-GitHub-Api-Version"))
        assertNull(latest.getHeader("Authorization"))
        assertEquals("/repos/test-owner/test-repo/releases/assets/42", asset.path)
        assertEquals("application/octet-stream", asset.getHeader("Accept"))
    }
    @Test fun inaccessibleRepositoryIsNotAnEmptyReleaseCatalog(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(404))
        failure(TaskSourceProblem.REPOSITORY_UNAVAILABLE) { source.latest() }
    }
    @Test fun emptyReleaseCatalogRequiresSuccessfulRepositoryAndListReads() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"full_name":"test-owner/test-repo"}"""))
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(source.latest())
        assertEquals(3, server.requestCount)
    }
    @Test fun failedEmptyCatalogConfirmationRemainsAnHttpFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"full_name":"test-owner/test-repo"}"""))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(403, failure(TaskSourceProblem.HTTP) { source.latest() }.httpCode)
    }
    @Test fun missingTaskAssetDigestAndMalformedResponsesAreNotInstallable() = runBlocking {
        for (body in listOf("{}", release().replace("task-bundle.json", "app.apk"), release().replace("sha256:", "sha1:"), "not json")) {
            server.enqueue(MockResponse().setBody(body))
            failure(TaskSourceProblem.INVALID_RESPONSE) { source.latest() }
        }
    }
    @Test fun truncatedOrChangedAssetNeverReturnsPartialContent(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        server.enqueue(MockResponse().setBody("改变"))
        val remote = source.latest()!!
        failure(TaskSourceProblem.INVALID_CONTENT) { source.download(remote) }
    }
    @Test fun cancelledRequestReleasesTheActualHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = launch { source.latest() }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        request.cancelAndJoin()
        withTimeout(2_000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        assertEquals(0, client.dispatcher.runningCallsCount())
    }
}
