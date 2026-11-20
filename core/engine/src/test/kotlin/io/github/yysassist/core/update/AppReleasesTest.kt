package io.github.yysassist.core.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppReleasesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var releases: AppReleases
    private lateinit var folder: File
    private val apk = "安装包内容".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(tag: String = "v0.2.0", prerelease: Boolean = false, digest: String? = hash(apk), name: String = "hgb-${tag.removePrefix("v")}.apk") =
        """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"body":"修了一些问题","assets":[
            {"id":7,"name":"server-0.2.0.tgz","state":"uploaded","size":3,"digest":"sha256:${"0".repeat(64)}"},
            {"id":9,"name":"$name","state":"uploaded","size":${apk.size}${digest?.let { ",\"digest\":\"sha256:$it\"" } ?: ""}}]}"""

    @Before fun open() {
        server = MockWebServer(); server.start()
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        releases = AppReleases({ "owner/hgb" }, server.url("/"), client)
        folder = Files.createTempDirectory("updates").toFile()
    }
    @After fun close() { server.shutdown(); client.dispatcher.executorService.shutdown(); folder.deleteRecursively() }

    @Test fun aNewerReleaseIsDownloadedAndCheckedBeforeItIsKept() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
        val found = releases.newer("0.1.2")!!
        assertEquals(AppRelease("0.2.0", 9, apk.size.toLong(), hash(apk), "修了一些问题"), found)
        val target = File(folder, "hgb.apk"); val seen = mutableListOf<Long>()
        releases.download(found, target) { seen += it }
        assertArrayEquals(apk, target.readBytes())
        assertEquals(apk.size.toLong(), seen.last())
        assertEquals("/repos/owner/hgb/releases/latest", server.takeRequest().path)
        val asset = server.takeRequest()
        assertEquals("/repos/owner/hgb/releases/assets/9", asset.path)
        assertEquals("application/octet-stream", asset.getHeader("Accept"))
    }

    @Test fun nothingIsOfferedUnlessItIsANewerFormalReleaseWithACheckableApk() = runBlocking {
        for (body in listOf(release("v0.1.2"), release("v0.1.1"), release(prerelease = true), release(name = "other.apk")))
            server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setResponseCode(404))
        repeat(5) { assertNull(releases.newer("0.1.2")) }
        // A development build never offers a release.
        server.enqueue(MockResponse().setBody(release()))
        assertNull(releases.newer("0.1.2-dev"))
    }

    @Test fun aNewerReleaseWhoseApkCannotBeCheckedIsReportedNotHidden() = runBlocking {
        server.enqueue(MockResponse().setBody(release(digest = null)))
        val failure = assertThrows(AppUpdateException::class.java) { runBlocking { releases.newer("0.1.2") } }
        assertTrue(failure.message!!.contains("0.2.0"))
    }

    @Test fun anAlteredDownloadIsRejectedAndLeavesNoFile() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        server.enqueue(MockResponse().setBody("别的内容！！"))
        val found = releases.newer("0.1.2")!!
        val target = File(folder, "hgb.apk")
        assertThrows(AppUpdateException::class.java) { runBlocking { releases.download(found, target) } }
        assertFalse(target.exists()); assertTrue(folder.listFiles().orEmpty().isEmpty())
    }

    @Test fun versionsCompareByNumberNotText() {
        assertTrue(AppReleases.isNewer("0.1.10", "0.1.9"))
        assertTrue(AppReleases.isNewer("1.0.0", "0.9.9"))
        assertFalse(AppReleases.isNewer("0.1.2", "0.1.2"))
        assertFalse(AppReleases.isNewer("0.1.1", "0.1.2"))
        assertFalse(AppReleases.isNewer("latest", "0.1.2"))
        assertFalse(AppReleases.isNewer("0.1.3", "0.1.2-dev"))
    }
}
