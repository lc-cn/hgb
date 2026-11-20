package io.github.yysassist.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A newer App in the code repository's latest release: its APK asset, with the size and SHA-256 GitHub reports. */
data class AppRelease(val version: String, val assetId: Long, val bytes: Long, val sha256: String, val notes: String)

class AppUpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads the App's releases from the code repository (`owner/name`) through api.github.com, which mainland networks
 * reach; the APK itself is fetched through the API's asset address, which redirects to githubusercontent.com. Only a
 * formal release counts (never `server-edge`), and only its `hgb-<version>.apk`.
 */
class AppReleases(private val repository: () -> String, private val apiBase: HttpUrl, private val calls: Call.Factory) {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Release(val tag_name: String, val draft: Boolean, val prerelease: Boolean, val body: String? = null, val assets: List<Asset>)
    @Serializable private data class Asset(val id: Long, val name: String, val state: String, val size: Long, val digest: String? = null)

    /** The latest release when it is newer than [current] and carries a complete APK; null when there is nothing newer. */
    suspend fun newer(current: String): AppRelease? {
        val (code, text) = fetchText("releases/latest")
        if (code == 404) return null
        if (code !in 200..299) throw AppUpdateException("检查更新失败（GitHub 返回 $code）")
        val release = runCatching { json.decodeFromString(Release.serializer(), text) }.getOrElse { throw AppUpdateException("GitHub 的回复无法识别", it) }
        if (release.draft || release.prerelease) return null
        val version = release.tag_name.removePrefix("v")
        if (!isNewer(version, current)) return null
        val asset = release.assets.singleOrNull { it.name == "hgb-$version.apk" } ?: return null
        val digest = asset.digest?.removePrefix("sha256:")
        // A newer release whose APK cannot be checked is a broken release, not "up to date".
        if (asset.state != "uploaded" || asset.size <= 0 || digest == null || !digest.matches(Regex("[0-9a-f]{64}")))
            throw AppUpdateException("新版本 $version 的安装包还没上传完或缺少校验信息，请稍后再试")
        return AppRelease(version, asset.id, asset.size, digest, release.body.orEmpty().trim())
    }

    /**
     * Downloads [release]'s APK into [target], reporting bytes so far. Written beside it first and moved into place only
     * once its size and SHA-256 match, so [target] is never a partial or altered file.
     */
    suspend fun download(release: AppRelease, target: File, progress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        val request = request("releases/assets/${release.assetId}", "application/octet-stream")
        val call = calls.newCall(request)
        coroutineScope {
            // A blocked read does not see cancellation; cancelling the call ends it.
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw AppUpdateException("下载失败（${response.code}）")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > release.bytes) throw AppUpdateException("下载的安装包比发布记录的大")
                                digest.update(buffer, 0, read); output.write(buffer, 0, read); progress(total)
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (total != release.bytes || sha != release.sha256) throw AppUpdateException("下载的安装包不完整或已被改动，请重试")
                }
                if (!partial.renameTo(target)) { target.delete(); if (!partial.renameTo(target)) throw AppUpdateException("无法保存安装包") }
            } catch (failure: Throwable) {
                partial.delete(); throw failure
            } finally { watcher.cancel() }
        }
    }

    private fun request(path: String, accept: String): Request {
        val (owner, name) = repository().split('/', limit = 2).also { require(it.size == 2) { "repository must be owner/name" } }
        val url = apiBase.newBuilder().addPathSegments("repos").addPathSegment(owner).addPathSegment(name).addPathSegments(path).build()
        return Request.Builder().url(url).header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28").build()
    }
    private suspend fun fetchText(path: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val call = calls.newCall(request(path, "application/vnd.github+json"))
        coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { it.code to it.body.string() } } finally { watcher.cancel() }
        }
    }

    companion object {
        /** Whether [candidate] is a later x.y.z than [current]. Either not being x.y.z (a development build) means no. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.').takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }
            val a = parts(candidate) ?: return false
            val b = parts(current) ?: return false
            for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }
    }
}
