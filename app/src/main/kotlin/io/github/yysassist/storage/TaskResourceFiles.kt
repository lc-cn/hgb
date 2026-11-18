package io.github.yysassist.storage

import android.system.Os
import android.system.OsConstants
import io.github.yysassist.core.content.TaskResource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

class InvalidTaskResources(message: String) : IOException(message)

/** Caller releases only after every native/background user of these paths has reached a terminal state. */
class TaskResourceLease internal constructor(
    val version: String,
    val bundleDigest: String,
    private val files: Map<String, File>,
    private val descriptors: Map<String, TaskResource>,
    private val release: () -> Unit,
) : Closeable {
    private var closed = false
    @Synchronized fun file(id: String): File {
        check(!closed) { "资源租约已释放" }
        return files[id] ?: throw IllegalArgumentException("任务版本未声明此资源：$id")
    }
    @Synchronized fun descriptor(id: String): TaskResource {
        check(!closed) { "资源租约已释放" }
        return descriptors[id] ?: throw IllegalArgumentException("任务版本未声明此资源：$id")
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; release() }
    }
}

/**
 * Private, immutable, content-addressed bytes; no executable loading or version selection here.
 * publish/check/lease/collect run under the owning TaskVersionLibrary's database transaction.
 * Download staging runs outside it. A failed DB commit may leave orphan bytes, never a half-ready version.
 * One Application-owned instance is shared by the sole library and its native consumers.
 */
class TaskResourceFiles(private val root: File) {
    internal val changes = MutableStateFlow(0L)
    private val leases = mutableMapOf<String, Int>()
    private val leasedDigests = mutableMapOf<String, Int>()
    private val blobs get() = File(root, "blobs")
    private val staging get() = File(root, "staging")
    private fun blob(digest: String): File {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        return File(blobs, digest)
    }

    internal class Prepared(val directory: File, val resources: List<TaskResource>) : Closeable {
        override fun close() {
            if (!directory.deleteRecursively()) throw IOException("无法清理资源暂存文件")
        }
    }

    internal suspend fun prepare(resources: List<TaskResource>, fetch: suspend (TaskResource, OutputStream) -> Unit): Prepared {
        Files.createDirectories(staging.toPath())
        val prepared = Prepared(Files.createDirectory(File(staging, UUID.randomUUID().toString()).toPath()).toFile(), resources)
        try {
            val present = mutableSetOf<String>()
            resources.forEach { resource ->
                currentCoroutineContext().ensureActive()
                // Content-addressed: a blob already verified from an earlier version (e.g. the same OCR
                // models) is not downloaded again. publish() re-checks it before the version is committed.
                if (resource.sha256 in present || runCatching { checkFile(blob(resource.sha256), resource, hash = true) }.isSuccess) {
                    present += resource.sha256; return@forEach
                }
                val file = File(prepared.directory, resource.assetName)
                FileOutputStream(file).use { output ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val checked = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (length < 0 || offset < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
                            if (length.toLong() > resource.bytes - count) throw InvalidTaskResources("资源超过清单声明长度：${resource.id}")
                            output.write(bytes, offset, length); hash.update(bytes, offset, length); count += length
                        }
                        override fun flush() = output.flush()
                    }
                    fetch(resource, checked)
                    currentCoroutineContext().ensureActive()
                    if (count != resource.bytes || hex(hash.digest()) != resource.sha256)
                        throw InvalidTaskResources("资源内容不完整或摘要不匹配：${resource.id}")
                    output.fd.sync()
                }
                if (!file.setReadOnly()) throw IOException("资源暂存文件无法设为只读")
                present += resource.sha256
            }
            return prepared
        } catch (failure: Throwable) {
            try { prepared.close() } catch (cleanup: IOException) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    internal fun publish(prepared: Prepared) {
        Files.createDirectories(blobs.toPath())
        prepared.resources.distinctBy { it.sha256 }.forEach { resource ->
            val destination = blob(resource.sha256)
            try {
                checkFile(destination, resource, hash = true)
            } catch (_: NoSuchFileException) {
                // Same private filesystem. Do not fall back to a partial copy if atomic move is unsupported.
                Files.move(File(prepared.directory, resource.assetName).toPath(), destination.toPath(), ATOMIC_MOVE)
            }
        }
        syncDirectory(blobs)
        syncDirectory(root)
    }

    internal fun check(resources: List<TaskResource>, hash: Boolean) {
        resources.distinctBy { it.sha256 }.forEach { resource ->
            try { checkFile(blob(resource.sha256), resource, hash) }
            catch (_: NoSuchFileException) { throw InvalidTaskResources("任务资源缺失：${resource.id}") }
        }
    }

    private fun checkFile(file: File, resource: TaskResource, hash: Boolean) {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() != resource.bytes)
            throw InvalidTaskResources("任务资源长度或文件类型不符：${resource.id}")
        if (hash) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            if (hex(digest.digest()) != resource.sha256) throw InvalidTaskResources("任务资源摘要不符：${resource.id}")
        }
    }

    internal fun acquire(version: String, digest: String, resources: List<TaskResource>): TaskResourceLease {
        check(resources, hash = true)
        val digests = resources.map { it.sha256 }.distinct()
        synchronized(this) {
            leases[version] = Math.addExact(leases[version] ?: 0, 1)
            digests.forEach { leasedDigests[it] = Math.addExact(leasedDigests[it] ?: 0, 1) }
            changes.value += 1
        }
        return TaskResourceLease(version, digest, resources.associate { it.id to blob(it.sha256) }, resources.associateBy { it.id }) {
            synchronized(this) {
                decrement(leases, version)
                digests.forEach { decrement(leasedDigests, it) }
                changes.value += 1
            }
        }
    }

    @Synchronized internal fun leaseCount(version: String): Int = leases[version] ?: 0
    @Synchronized internal fun hasLeases(): Boolean = leases.isNotEmpty()

    /** Called only after content eviction has committed, with every remaining cached manifest checked. */
    internal fun collectUnused(retained: Set<String>) {
        val protected = synchronized(this) { retained + leasedDigests.keys }
        val paths = try { Files.newDirectoryStream(blobs.toPath()) }
        catch (_: NoSuchFileException) { return }
        paths.use { entries ->
            entries.forEach { path ->
                val digest = path.fileName.toString()
                if (digest.matches(Regex("[0-9a-f]{64}")) && digest !in protected) {
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (!attributes.isRegularFile) throw InvalidTaskResources("资源存储中存在非普通文件")
                    Files.delete(path)
                }
            }
        }
        syncDirectory(blobs)
    }

    private fun decrement(counts: MutableMap<String, Int>, id: String) {
        val count = checkNotNull(counts[id])
        if (count == 1) counts.remove(id) else counts[id] = count - 1
    }
    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IOException("资源存储路径不是目录")
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
