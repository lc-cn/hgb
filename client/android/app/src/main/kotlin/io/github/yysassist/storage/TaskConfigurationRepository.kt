package io.github.yysassist.storage

import androidx.room.withTransaction
import io.github.yysassist.core.content.*
import kotlinx.serialization.json.*

data class TaskAddress(val targetId: String, val taskId: String)
data class TaskConfigurationSnapshot(
    val address: TaskAddress, val task: TaskDefinition, val version: String, val digest: String,
    val revision: Long, val values: JsonObject,
)
sealed interface ConfigurationSaveResult {
    data class Saved(val snapshot: TaskConfigurationSnapshot) : ConfigurationSaveResult
    data class Invalid(val issues: List<ContentIssue>) : ConfigurationSaveResult
    data object VersionChanged : ConfigurationSaveResult
    data object RevisionChanged : ConfigurationSaveResult
    data class Unavailable(val message: String) : ConfigurationSaveResult
}
class ConfigurationUnavailable(message: String) : IllegalStateException(message)

/** Configuration has no FK to cached content: deleting a version never deletes user rules. */
class TaskConfigurationRepository(private val database: AssistDatabase, private val library: TaskVersionLibrary) {
    suspend fun open(address: TaskAddress): TaskConfigurationSnapshot = database.withTransaction {
        val bundle = library.activeCatalog() ?: throw ConfigurationUnavailable("请先启用任务版本")
        val task = bundle.tasks.find { it.id == address.taskId && it.targetId == address.targetId }
            ?: throw ConfigurationUnavailable("当前任务版本中已没有此任务，原设置仍保留")
        val row = database.taskVersions().version(bundle.version) ?: throw ConfigurationUnavailable("任务版本内容不可用")
        val saved = database.taskConfigurations().get(address.targetId, address.taskId)
        val values = saved?.let {
            try { Json.parseToJsonElement(it.valuesJson).jsonObject }
            catch (_: IllegalArgumentException) { throw ConfigurationUnavailable("任务设置读取失败，原数据已保留") }
        } ?: JsonObject(emptyMap())
        TaskConfigurationSnapshot(address, task, bundle.version, row.digest, saved?.revision ?: 0, values)
    }

    suspend fun save(snapshot: TaskConfigurationSnapshot, draft: JsonObject): ConfigurationSaveResult = database.withTransaction {
        val version = database.taskVersions().selection()?.activeVersion
        if (version != snapshot.version) return@withTransaction ConfigurationSaveResult.VersionChanged
        val row = database.taskVersions().version(version)
        if (row?.digest != snapshot.digest) return@withTransaction ConfigurationSaveResult.VersionChanged
        val current = try { open(snapshot.address) }
        catch (error: ConfigurationUnavailable) { return@withTransaction ConfigurationSaveResult.Unavailable(error.message ?: "任务设置不可用") }
        catch (_: InvalidLocalTaskVersion) { return@withTransaction ConfigurationSaveResult.Unavailable("任务内容校验失败，设置未保存") }
        if (current.revision != snapshot.revision) return@withTransaction ConfigurationSaveResult.RevisionChanged
        val values = normalizeConfigurationDraft(current.task, draft)
        val issues = library.configurationIssues(current.task, values)
        if (issues.isNotEmpty()) return@withTransaction ConfigurationSaveResult.Invalid(issues)
        val revision = Math.addExact(current.revision, 1)
        database.taskConfigurations().save(TaskConfigurationEntity(current.address.targetId, current.address.taskId, values.toString(),
            configurationSchemaJson(current.task), revision, current.version, current.digest))
        ConfigurationSaveResult.Saved(current.copy(revision = revision, values = values))
    }
}
