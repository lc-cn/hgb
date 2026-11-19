package io.github.yysassist.storage

import androidx.room.withTransaction
import io.github.yysassist.core.content.BundleCodec
import io.github.yysassist.core.content.BundleReadResult
import io.github.yysassist.core.content.TaskBundle
import io.github.yysassist.core.content.TaskResource
import io.github.yysassist.core.content.TaskDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.io.OutputStream
import java.security.MessageDigest

sealed interface ActiveTaskContent {
    data object None : ActiveTaskContent
    data class Available(val bundle: TaskBundle) : ActiveTaskContent
    data class Invalid(val message: String) : ActiveTaskContent
}
data class TaskVersionCatalog(val versions: List<TaskVersionRow>, val activeVersion: String?, val latestVersion: String?,
    val activeContent: ActiveTaskContent = ActiveTaskContent.None) {
    val hasKnownUpdate: Boolean get() = activeVersion != null && latestVersion != null && activeVersion != latestVersion
}

enum class VersionRejection { INVALID_CONTENT, IMMUTABLE_CONFLICT, MISSING, IN_USE, ACTIVE, OWNER_CONFLICT }
sealed interface VersionChange {
    data object Applied : VersionChange
    data object Unchanged : VersionChange
    data class Rejected(val code: VersionRejection, val message: String) : VersionChange
}
data class PinnedTaskVersion(val owner: String, val bundle: TaskBundle, val digest: String)
sealed interface PinVersionResult {
    data class Pinned(val content: PinnedTaskVersion) : PinVersionResult
    data class Rejected(val reason: VersionChange.Rejected) : PinVersionResult
}
enum class VersionReferenceReason { QUEUED, RUNNING, PAUSED, UNRESOLVED }

/**
 * One durable owner for install/selection/reference/delete. DB exceptions remain exceptions:
 * a failed observation or an uncertain commit is never translated to an empty catalog or success.
 * This validates content/digests; source authentication belongs to the transport, not to JSON.
 */
class TaskVersionLibrary(
    private val database: AssistDatabase,
    private val codec: BundleCodec,
    private val resourceFiles: TaskResourceFiles? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.taskVersions()
    fun configurationIssues(task: TaskDefinition, values: JsonObject) = codec.configurationIssues(task, values)
    val catalog = combine(database.invalidationTracker.createFlow("task_versions", "task_version_selection", "task_version_references"),
        resourceFiles?.changes ?: flowOf(0L)) { _, _ -> snapshot() }

    suspend fun snapshot(): TaskVersionCatalog = database.withTransaction {
        val selection = dao.selection()
        val active = selection?.activeVersion?.let { version ->
            when (val content = checked(dao.version(version))) {
                is Checked.Valid -> ActiveTaskContent.Available(content.bundle)
                is Checked.Rejected -> ActiveTaskContent.Invalid(content.reason.message)
            }
        } ?: ActiveTaskContent.None
        val rows = dao.versions().map { it.copy(referenceCount = Math.addExact(it.referenceCount, resourceFiles?.leaseCount(it.version) ?: 0)) }
        TaskVersionCatalog(rows, selection?.activeVersion, selection?.latestVersion, active)
    }

    suspend fun install(text: String, expectedVersion: String, expectedSha256: String,
        fetchResource: (suspend (TaskResource, OutputStream) -> Unit)? = null): VersionChange {
        val digest = sha256(text)
        if (expectedVersion.isBlank() || !expectedSha256.matches(Regex("[0-9a-f]{64}")) || digest != expectedSha256)
            return reject(VersionRejection.INVALID_CONTENT, "任务内容摘要或版本身份不匹配")
        val parsed = codec.read(text)
        if (parsed !is BundleReadResult.Valid)
            return reject(VersionRejection.INVALID_CONTENT, (parsed as BundleReadResult.Invalid).issues.joinToString("；") { it.reason })
        if (parsed.bundle.version != expectedVersion)
            return reject(VersionRejection.INVALID_CONTENT, "任务版本与获取的版本不一致")
        database.withTransaction { existingInstall(dao.version(expectedVersion), digest) }?.let { return it }
        if (parsed.bundle.resources.isNotEmpty() && (resourceFiles == null || fetchResource == null))
            return reject(VersionRejection.INVALID_CONTENT, "任务资源尚未完整获取")
        return try {
            val prepared = if (parsed.bundle.resources.isEmpty()) null else resourceFiles!!.prepare(parsed.bundle.resources, fetchResource!!)
            prepared.use {
                database.withTransaction {
                    val existing = dao.version(expectedVersion)
                    existingInstall(existing, digest)?.let { return@withTransaction it }
                    // Publish all verified immutable files before the only durable ready marker (cached JSON).
                    // If DB commit fails, leave orphan files; do not guess that it rolled back and delete them.
                    if (prepared != null) resourceFiles!!.publish(prepared)
                    if (existing != null) check(dao.restoreContents(expectedVersion, text, clock()) == 1)
                    else dao.insertVersion(TaskVersionEntity(expectedVersion, digest, text, parsed.bundle.updatedAt, parsed.bundle.changelog, clock()))
                    VersionChange.Applied
                }
            }
        } catch (invalid: InvalidTaskResources) {
            reject(VersionRejection.INVALID_CONTENT, invalid.message ?: "任务资源校验失败")
        }
    }

    private fun existingInstall(existing: TaskVersionEntity?, digest: String): VersionChange? {
        if (existing == null) return null
        if (existing.digest != digest) return reject(VersionRejection.IMMUTABLE_CONFLICT, "同一版本的内容发生变化，请发布新版本")
        if (!existing.cached) return null
        return when (val content = checked(existing, hashResources = true)) {
            is Checked.Rejected -> content.reason
            is Checked.Valid -> VersionChange.Unchanged
        }
    }

    /** Only an authoritative remote latest pointer calls this; a fetch failure must not clear it. */
    suspend fun observeLatest(version: String?): VersionChange {
        require(version == null || version.isNotBlank())
        return database.withTransaction {
            val selection = dao.selection() ?: TaskVersionSelection()
            if (selection.latestVersion == version) VersionChange.Unchanged
            else { dao.saveSelection(selection.copy(latestVersion = version)); VersionChange.Applied }
        }
    }

    suspend fun activate(version: String): VersionChange = database.withTransaction {
        val selection = dao.selection() ?: TaskVersionSelection()
        if (selection.activeVersion == version) return@withTransaction VersionChange.Unchanged
        if (dao.referenceCount() != 0 || resourceFiles?.hasLeases() == true)
            return@withTransaction reject(VersionRejection.IN_USE, "执行结束后再切换任务版本")
        val content = checked(dao.version(version), hashResources = true)
        if (content is Checked.Rejected) return@withTransaction content.reason
        dao.saveSelection(selection.copy(activeVersion = version))
        VersionChange.Applied
    }

    suspend fun delete(version: String): VersionChange {
        val result = database.withTransaction {
            if (dao.selection()?.activeVersion == version) return@withTransaction reject(VersionRejection.ACTIVE, "当前启用版本需保留")
            if (dao.referenceCount(version) != 0 || (resourceFiles?.leaseCount(version) ?: 0) != 0)
                return@withTransaction reject(VersionRejection.IN_USE, "执行或未决结果仍依赖此版本")
            if (dao.evictContents(version) == 0) reject(VersionRejection.MISSING, "本地任务版本不存在") else VersionChange.Applied
        }
        if (result == VersionChange.Applied && resourceFiles != null) database.withTransaction {
            // A separate transaction follows confirmed eviction. Cleanup never precedes a possibly rolled-back delete.
            val retained = dao.cachedVersions().flatMap { row ->
                when (val content = checked(row, checkFiles = false)) {
                    is Checked.Valid -> content.bundle.resources.map { it.sha256 }
                    is Checked.Rejected -> throw InvalidLocalTaskVersion(content.reason.message)
                }
            }.toSet()
            resourceFiles.collectUnused(retained)
        }
        return result
    }

    /** Resource lifetime is independent of a queue entry; close only after the last native use is quiet. */
    suspend fun acquireResources(version: String): TaskResourceLease {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        var acquired: TaskResourceLease? = null
        try {
            // Bracket creation through delivery: do not abandon a queued Room operation on cancellation.
            // A lease created on the DB thread stays ours until the caller can actually receive it.
            withContext(NonCancellable) {
                database.withTransaction {
                    acquired = when (val content = checked(dao.version(version), checkFiles = false)) {
                        is Checked.Rejected -> throw InvalidLocalTaskVersion(content.reason.message)
                        is Checked.Valid -> (resourceFiles ?: throw InvalidTaskResources("资源存储未配置"))
                            .acquire(version, content.digest, content.bundle.resources)
                    }
                }
            }
            caller.ensureActive()
            return checkNotNull(acquired)
        } catch (failure: Throwable) {
            acquired?.close()
            throw failure
        }
    }

    /** Evidence can arrive after completed content was evicted. Retain identity without resurrecting its payload. */
    suspend fun retainUnresolved(version: String, owner: String): VersionChange = database.withTransaction {
        retainRecorded(version, owner, VersionReferenceReason.UNRESOLVED)
    }

    /** Existing execution evidence must remain writable even when its cached content is unavailable. */
    suspend fun retainRecorded(version: String, owner: String, reason: VersionReferenceReason): VersionChange = database.withTransaction {
        require(owner.isNotBlank())
        val previous = dao.reference(owner)
        if (previous != null && previous.version != version)
            return@withTransaction reject(VersionRejection.OWNER_CONFLICT, "同一执行身份已绑定另一版本")
        val identity = dao.version(version) ?: return@withTransaction reject(VersionRejection.MISSING, "执行依赖的版本身份缺失")
        if (!identity.digest.matches(Regex("[0-9a-f]{64}")))
            return@withTransaction reject(VersionRejection.INVALID_CONTENT, "执行依赖的版本身份损坏")
        dao.saveReference(TaskVersionReference(owner, version, reason.name))
        VersionChange.Applied
    }

    /** Pin before admission, retain through pauses/unknowns; host releases only after safe completion. */
    suspend fun pinActive(owner: String): PinVersionResult = database.withTransaction {
        require(owner.isNotBlank())
        val previous = dao.reference(owner)
        // A replay resumes the original lock even if this process restarted.
        val version = previous?.version ?: dao.selection()?.activeVersion
            ?: return@withTransaction PinVersionResult.Rejected(reject(VersionRejection.MISSING, "尚未启用任务版本"))
        pin(version, owner, previous?.reason ?: VersionReferenceReason.QUEUED.name)
    }

    suspend fun retain(version: String, owner: String, reason: VersionReferenceReason): PinVersionResult = database.withTransaction {
        require(owner.isNotBlank())
        pin(version, owner, reason.name)
    }

    suspend fun release(owner: String): VersionChange = database.withTransaction {
        if (dao.releaseReference(owner) == 0) VersionChange.Unchanged else VersionChange.Applied
    }

    /** Read-only catalog projection; this is not an execution lock. */
    suspend fun activeCatalog(): TaskBundle? = database.withTransaction {
        val version = dao.selection()?.activeVersion ?: return@withTransaction null
        when (val content = checked(dao.version(version))) {
            is Checked.Valid -> content.bundle
            is Checked.Rejected -> throw InvalidLocalTaskVersion(content.reason.message)
        }
    }

    suspend fun readVersion(version: String): TaskBundle = database.withTransaction {
        when (val content = checked(dao.version(version))) {
            is Checked.Valid -> content.bundle
            is Checked.Rejected -> throw InvalidLocalTaskVersion(content.reason.message)
        }
    }

    private suspend fun pin(version: String, owner: String, reason: String): PinVersionResult {
        val previous = dao.reference(owner)
        if (previous != null && previous.version != version)
            return PinVersionResult.Rejected(reject(VersionRejection.OWNER_CONFLICT, "同一执行身份已绑定另一版本"))
        return when (val content = checked(dao.version(version), hashResources = true)) {
            is Checked.Rejected -> PinVersionResult.Rejected(content.reason)
            is Checked.Valid -> {
                dao.saveReference(TaskVersionReference(owner, version, reason))
                PinVersionResult.Pinned(PinnedTaskVersion(owner, content.bundle, content.digest))
            }
        }
    }

    private sealed interface Checked {
        data class Valid(val bundle: TaskBundle, val digest: String) : Checked
        data class Rejected(val reason: VersionChange.Rejected) : Checked
    }
    private fun checked(row: TaskVersionEntity?, checkFiles: Boolean = true, hashResources: Boolean = false): Checked {
        if (row == null || !row.cached) return Checked.Rejected(reject(VersionRejection.MISSING, "本地任务版本不存在"))
        if (sha256(row.json) != row.digest) return Checked.Rejected(reject(VersionRejection.INVALID_CONTENT, "本地版本内容损坏"))
        val parsed = codec.read(row.json)
        if (parsed !is BundleReadResult.Valid || parsed.bundle.version != row.version)
            return Checked.Rejected(reject(VersionRejection.INVALID_CONTENT, "本地版本与当前容器不兼容或身份不一致"))
        if (checkFiles && parsed.bundle.resources.isNotEmpty()) {
            if (resourceFiles == null) return Checked.Rejected(reject(VersionRejection.INVALID_CONTENT, "任务资源不可用"))
            try { resourceFiles.check(parsed.bundle.resources, hashResources) }
            catch (invalid: InvalidTaskResources) {
                return Checked.Rejected(reject(VersionRejection.INVALID_CONTENT, invalid.message ?: "任务资源不可用"))
            }
        }
        return Checked.Valid(parsed.bundle, row.digest)
    }

    private fun reject(code: VersionRejection, message: String) = VersionChange.Rejected(code, message)
    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
class InvalidLocalTaskVersion(message: String) : IllegalStateException(message)
