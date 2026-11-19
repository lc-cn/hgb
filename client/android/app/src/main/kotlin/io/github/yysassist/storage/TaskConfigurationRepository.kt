package io.github.yysassist.storage

import io.github.yysassist.cloud.CloudAccounts
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.core.content.*
import kotlinx.serialization.json.*
import java.io.IOException

data class TaskAddress(val targetId: String, val taskId: String)
/** A task's settings for the game account the app is signed in to; null [accountId] when none has been named yet. */
data class TaskConfigurationSnapshot(
    val address: TaskAddress, val task: TaskDefinition, val accountId: String?, val accountName: String?,
    val revision: Long, val values: JsonObject,
)
sealed interface ConfigurationSaveResult {
    data class Saved(val snapshot: TaskConfigurationSnapshot) : ConfigurationSaveResult
    data class Invalid(val issues: List<ContentIssue>) : ConfigurationSaveResult
    data object RevisionChanged : ConfigurationSaveResult
    data class Unavailable(val message: String) : ConfigurationSaveResult
}
class ConfigurationUnavailable(message: String) : IllegalStateException(message)

/** Settings belong to a game account and are kept on the server; this reads the copy the server sent and saves there. */
class TaskConfigurationRepository(private val library: TaskVersionLibrary, private val accounts: CloudAccounts) {
    suspend fun open(address: TaskAddress): TaskConfigurationSnapshot {
        val bundle = library.activeCatalog() ?: throw ConfigurationUnavailable("请先启用任务版本")
        val task = bundle.tasks.find { it.id == address.taskId && it.targetId == address.targetId }
            ?: throw ConfigurationUnavailable("当前任务版本中已没有此任务")
        val account = accounts.current(address.targetId)
        val saved = accounts.config(address.targetId, address.taskId)
        return TaskConfigurationSnapshot(address, task, account?.id, account?.name, saved?.revision ?: 0, saved?.values ?: JsonObject(emptyMap()))
    }

    suspend fun save(snapshot: TaskConfigurationSnapshot, draft: JsonObject): ConfigurationSaveResult {
        val accountId = snapshot.accountId ?: return ConfigurationSaveResult.Unavailable("先指定这台设备上的游戏当前登录的账号")
        if (!accounts.online) return ConfigurationSaveResult.Unavailable("没有连上服务器，设置只能查看")
        val values = normalizeConfigurationDraft(snapshot.task, draft)
        val issues = library.configurationIssues(snapshot.task, values)
        if (issues.isNotEmpty()) return ConfigurationSaveResult.Invalid(issues)
        return try {
            val saved = accounts.agent.saveConfig(accountId, snapshot.address.targetId, snapshot.address.taskId, values, snapshot.revision)
            ConfigurationSaveResult.Saved(snapshot.copy(revision = saved.revision, values = saved.values))
        } catch (rejected: CloudRequestException) {
            if (rejected.code == "revision_changed") ConfigurationSaveResult.RevisionChanged else ConfigurationSaveResult.Unavailable(rejected.message)
        } catch (_: IOException) { ConfigurationSaveResult.Unavailable("没有连上服务器，设置未保存") }
    }
}
