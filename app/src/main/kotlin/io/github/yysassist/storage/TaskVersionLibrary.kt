package io.github.yysassist.storage

import androidx.room.withTransaction
import io.github.yysassist.core.content.BundleCodec
import io.github.yysassist.core.content.BundleReadResult
import io.github.yysassist.core.content.TaskBundle
import kotlinx.coroutines.flow.map
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.taskVersions()
    val catalog = database.invalidationTracker.createFlow("task_versions", "task_version_selection", "task_version_references")
        .map { snapshot() }

    suspend fun snapshot(): TaskVersionCatalog = database.withTransaction {
        val selection = dao.selection()
        val active = selection?.activeVersion?.let { version ->
            when (val content = checked(dao.version(version))) {
                is Checked.Valid -> ActiveTaskContent.Available(content.bundle)
                is Checked.Rejected -> ActiveTaskContent.Invalid(content.reason.message)
            }
        } ?: ActiveTaskContent.None
        TaskVersionCatalog(dao.versions(), selection?.activeVersion, selection?.latestVersion, active)
    }

    suspend fun install(text: String, expectedVersion: String, expectedSha256: String): VersionChange {
        val digest = sha256(text)
        if (expectedVersion.isBlank() || !expectedSha256.matches(Regex("[0-9a-f]{64}")) || digest != expectedSha256)
            return reject(VersionRejection.INVALID_CONTENT, "任务内容摘要或版本身份不匹配")
        val parsed = codec.read(text)
        if (parsed !is BundleReadResult.Valid)
            return reject(VersionRejection.INVALID_CONTENT, (parsed as BundleReadResult.Invalid).issues.joinToString("；") { it.reason })
        if (parsed.bundle.version != expectedVersion)
            return reject(VersionRejection.INVALID_CONTENT, "任务版本与获取的版本不一致")
        return database.withTransaction {
            val existing = dao.version(expectedVersion)
            if (existing != null) {
                when {
                    existing.digest != digest -> reject(VersionRejection.IMMUTABLE_CONFLICT, "同一版本的内容发生变化，请发布新版本")
                    !existing.cached -> {
                        check(dao.restoreContents(expectedVersion, text, clock()) == 1)
                        VersionChange.Applied
                    }
                    sha256(existing.json) != existing.digest -> reject(VersionRejection.INVALID_CONTENT, "本地版本内容损坏")
                    else -> VersionChange.Unchanged
                }
            } else {
                dao.insertVersion(TaskVersionEntity(expectedVersion, digest, text, parsed.bundle.updatedAt, parsed.bundle.changelog, clock()))
                VersionChange.Applied
            }
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
        if (dao.referenceCount() != 0) return@withTransaction reject(VersionRejection.IN_USE, "执行结束后再切换任务版本")
        val content = checked(dao.version(version))
        if (content is Checked.Rejected) return@withTransaction content.reason
        dao.saveSelection(selection.copy(activeVersion = version))
        VersionChange.Applied
    }

    suspend fun delete(version: String): VersionChange = database.withTransaction {
        if (dao.selection()?.activeVersion == version) return@withTransaction reject(VersionRejection.ACTIVE, "当前启用版本需保留")
        if (dao.referenceCount(version) != 0) return@withTransaction reject(VersionRejection.IN_USE, "执行或未决结果仍依赖此版本")
        if (dao.evictContents(version) == 0) reject(VersionRejection.MISSING, "本地任务版本不存在") else VersionChange.Applied
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
        return when (val content = checked(dao.version(version))) {
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
    private fun checked(row: TaskVersionEntity?): Checked {
        if (row == null || !row.cached) return Checked.Rejected(reject(VersionRejection.MISSING, "本地任务版本不存在"))
        if (sha256(row.json) != row.digest) return Checked.Rejected(reject(VersionRejection.INVALID_CONTENT, "本地版本内容损坏"))
        val parsed = codec.read(row.json)
        if (parsed !is BundleReadResult.Valid || parsed.bundle.version != row.version)
            return Checked.Rejected(reject(VersionRejection.INVALID_CONTENT, "本地版本与当前容器不兼容或身份不一致"))
        return Checked.Valid(parsed.bundle, row.digest)
    }

    private fun reject(code: VersionRejection, message: String) = VersionChange.Rejected(code, message)
    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
class InvalidLocalTaskVersion(message: String) : IllegalStateException(message)
