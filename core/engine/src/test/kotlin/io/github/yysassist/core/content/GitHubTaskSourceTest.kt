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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.github.yysassist.core.auth.GitHubToken
import io.github.yysassist.core.auth.GitHubAuthorization

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
    @Test fun authenticatedReadsApplyTheCurrentSessionHeaderWithoutChangingPrivate404Meaning(): Unit = runBlocking {
        var credential = GitHubToken("fixture_access_one")
        val authenticated = GitHubTaskSource(TaskRepository("test-owner", "test-repo"), server.url("/"), client) { credential }
        server.enqueue(MockResponse().setBody(release()))
        server.enqueue(MockResponse().setBody("内容"))
        val version = authenticated.latest()!!
        credential = GitHubToken("fixture_access_two")
        assertEquals("内容", authenticated.download(version))
        assertEquals("Bearer fixture_access_one", server.takeRequest().getHeader("Authorization"))
        val asset = server.takeRequest()
        assertEquals("Bearer fixture_access_two", asset.getHeader("Authorization")); assertNull(asset.requestUrl!!.query)
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(404))
        failure(TaskSourceProblem.REPOSITORY_UNAVAILABLE) { authenticated.latest() }
    }
    @Test fun aPrivateAssetRedirectDropsCredentialsAtTheOtherOriginAndStillVerifiesResourceBytes(): Unit = runBlocking {
        MockWebServer().use { other ->
            other.start()
            val authenticated = GitHubTaskSource(TaskRepository("test-owner", "test-repo"), server.url("/"), client) { GitHubToken("fixture_access") }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/signed-download?signature=fixture")))
            other.enqueue(MockResponse().setBody("model"))
            val sink = ByteArrayOutputStream()
            authenticated.downloadResource(withResource(), resource(), sink)
            assertEquals("model", sink.toString("UTF-8"))
            assertEquals("Bearer fixture_access", server.takeRequest().getHeader("Authorization"))
            val redirected = other.takeRequest()
            assertNull(redirected.getHeader("Authorization")); assertEquals("signature=fixture", redirected.requestUrl!!.query)
        }
    }
    @Test fun rejectedCredentialsAreNotMistakenForNoPublishedVersionOrAutomaticallyRetried(): Unit = runBlocking {
        val authenticated = GitHubTaskSource(TaskRepository("test-owner", "test-repo"), server.url("/"), client) { GitHubToken("fixture_access") }
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, failure(TaskSourceProblem.HTTP) { authenticated.latest() }.httpCode)
        assertEquals(1, server.requestCount)
        assertFalse(GitHubToken("fixture_access").toString().contains("fixture_access"))
    }
    @Test fun a401InvalidatesOnlyTheActualCredentialForJsonAndResourceWithoutReplayingEitherGet(): Unit = runBlocking {
        val used = GitHubToken("fixture_access"); val rejected = mutableListOf<GitHubToken>()
        val provider = object : GitHubAuthorization {
            override suspend fun token() = used
            override suspend fun rejected(token: GitHubToken) { rejected += token }
        }
        val authenticated = GitHubTaskSource(TaskRepository("test-owner", "test-repo"), server.url("/"), client, provider)
        server.enqueue(MockResponse().setResponseCode(401))
        failure(TaskSourceProblem.HTTP) { authenticated.latest() }
        server.enqueue(MockResponse().setResponseCode(401))
        failure(TaskSourceProblem.HTTP) { authenticated.downloadResource(withResource(), resource(), ByteArrayOutputStream()) }
        assertEquals(2, server.requestCount); assertEquals(2, rejected.size)
        assertTrue(rejected.all { it === used })
    }
    @Test fun aRedirectTargets401CannotInvalidateTheGitHubApiCredential(): Unit = runBlocking {
        MockWebServer().use { other ->
            other.start(); var rejected = 0
            val provider = object : GitHubAuthorization {
                override suspend fun token() = GitHubToken("fixture_access")
                override suspend fun rejected(token: GitHubToken) { rejected++ }
            }
            val authenticated = GitHubTaskSource(TaskRepository("test-owner", "test-repo"), server.url("/"), client, provider)
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/signed-download")))
                other.enqueue(MockResponse().setResponseCode(401))
            }
            assertEquals(401, failure(TaskSourceProblem.HTTP) { authenticated.latest() }.httpCode)
            assertEquals(401, failure(TaskSourceProblem.HTTP) { authenticated.downloadResource(withResource(), resource(), ByteArrayOutputStream()) }.httpCode)
            assertEquals("An external download failure is not a GitHub credential rejection", 0, rejected)
            assertEquals(2, server.requestCount); assertEquals(2, other.requestCount)
        }
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

    private fun withResource(content: String = "model") = RemoteTaskVersion("v-test", 42, 1, "a".repeat(64),
        listOf(RemoteReleaseAsset(43, "det.onnx", "uploaded", content.toByteArray().size.toLong(), "sha256:${hash(content)}")))
    private fun resource(content: String = "model") = TaskResource("det", "det.onnx", content.toByteArray().size.toLong(), hash(content))

    @Test fun resourcesUseSameReleaseAssetMetadataAndStreamValidatedBytes() = runBlocking {
        val sink = ByteArrayOutputStream()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/download")))
        server.enqueue(MockResponse().setBody("model"))
        source.downloadResource(withResource(), resource(), sink)
        assertEquals("model", sink.toString("UTF-8"))
        assertEquals("/repos/test-owner/test-repo/releases/assets/43", server.takeRequest().path)
        assertEquals("/download", server.takeRequest().path)
        for (assets in listOf(emptyList(), withResource().assets + withResource().assets,
            withResource().assets.map { it.copy(digest = "sha256:${"b".repeat(64)}") },
            withResource().assets.map { it.copy(bytes = 8) }, withResource().assets.map { it.copy(state = "new") })) {
            failure(TaskSourceProblem.INVALID_RESPONSE) { source.downloadResource(withResource().copy(assets = assets), resource(), sink) }
        }
        assertEquals(2, server.requestCount) // Invalid metadata never makes a guessed URL request.
    }

    @Test fun changedOversizedOrMissingResourceNeverReportsACompleteDownload(): Unit = runBlocking {
        for (body in listOf("wrong", "model extra", "mod")) {
            server.enqueue(MockResponse().setBody(body))
            val sink = ByteArrayOutputStream()
            failure(TaskSourceProblem.INVALID_CONTENT) { source.downloadResource(withResource(), resource(), sink) }
            assertTrue(sink.size() <= resource().bytes)
        }
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, failure(TaskSourceProblem.HTTP) { source.downloadResource(withResource(), resource(), ByteArrayOutputStream()) }.httpCode)
        server.enqueue(MockResponse().setBody("model").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        failure(TaskSourceProblem.NETWORK) { source.downloadResource(withResource(), resource(), ByteArrayOutputStream()) }
    }

    @Test fun cancellationWaitsForAStillWritingResponseBeforeReturningToTheStagingOwner() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val calls = AtomicInteger()
        val sink = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes expected")
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertFalse("Callback used sink after cancellation returned", returned.get())
                calls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        server.enqueue(MockResponse().setBody("model"))
        val job = launch(Dispatchers.Default) {
            try { source.downloadResource(withResource(), resource(), sink) } finally { returned.set(true) }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            job.cancel()
            yield()
            assertFalse(returned.get())
        } finally { release.countDown(); job.cancelAndJoin() }
        assertTrue(returned.get()); assertEquals(1, calls.get())
        withTimeout(2_000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
    }

    @Test fun anAppSkipsReleasesThatNeedANewerAppAndFetchesSharedAssetsFromTheirRelease() = runBlocking {
        val app = GitHubTaskSource({ TaskRepository("test-owner", "test-repo") }, server.url("/"), client, appVersion = "0.1.6")
        val icon = "图"
        val newer = release().replace("\"v-test\"", "\"v2\"").replace("\"draft\"", "\"body\":\"说明\\nmin-app: 0.2.0\",\"draft\"")
        val older = release().replace("\"v-test\"", "\"v1\"").replace("\"draft\"", "\"body\":\"min-app: 0.1.6\",\"draft\"")
        server.enqueue(MockResponse().setBody("[$newer,$older]"))
        val version = app.latest()!!
        assertEquals("v1", version.version)
        assertEquals("/repos/test-owner/test-repo/releases?per_page=30", server.takeRequest().path)
        // A resource kept in an earlier release is looked up there, once, and still checked against the manifest.
        val shared = """{"tag_name":"v0","draft":false,"prerelease":false,"assets":[{"name":"icon.png","id":7,"size":${icon.toByteArray().size},"state":"uploaded","digest":"sha256:${hash(icon)}"}]}"""
        server.enqueue(MockResponse().setBody(shared))
        server.enqueue(MockResponse().setBody(icon))
        server.enqueue(MockResponse().setBody(icon))
        val resource = TaskResource("icon", "icon.png", icon.toByteArray().size.toLong(), hash(icon), release = "v0")
        repeat(2) { ByteArrayOutputStream().also { app.downloadResource(version, resource, it); assertEquals(icon, it.toString(Charsets.UTF_8)) } }
        assertEquals("/repos/test-owner/test-repo/releases/tags/v0", server.takeRequest().path)
        assertEquals("/repos/test-owner/test-repo/releases/assets/7", server.takeRequest().path)
        assertEquals("/repos/test-owner/test-repo/releases/assets/7", server.takeRequest().path)
        // With nothing it can run, there is nothing new; an unreadable min-app line counts as "cannot run".
        val garbled = older.replace("min-app: 0.1.6", "min-app: 0.2")
        server.enqueue(MockResponse().setBody("[$newer,$garbled]"))
        assertNull(app.latest())
        server.takeRequest()
        // The highest version wins over the most recently made one (a later fix for older Apps), and the last min-app line counts.
        val fix = release().replace("\"v-test\"", "\"v1.1.1\"")
        val line = release().replace("\"v-test\"", "\"v1.2.0\"").replace("\"draft\"", "\"body\":\"min-app: 9.9.9\\nmin-app: 0.1.0\",\"draft\"")
        server.enqueue(MockResponse().setBody("[$fix,$line]"))
        assertEquals("v1.2.0", app.latest()!!.version)
    }
}
