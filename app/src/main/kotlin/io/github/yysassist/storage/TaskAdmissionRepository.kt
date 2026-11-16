package io.github.yysassist.storage

import androidx.room.withTransaction
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.ExecutionContext
import kotlinx.serialization.json.*

data class TaskAdmissionDraft(val target: TargetProfile, val version: String, val task: TaskSnapshot) {
    fun request(context: ExecutionContext): ExecutionRequest {
        if (!target.acceptsContext(context)) throw ExecutionStateRejected("请先核对任务目标与当前账号")
        return ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), context, version, listOf(task))
    }
}
class TaskAdmissionRepository(private val database: AssistDatabase, private val library: TaskVersionLibrary) {
    val changes = database.invalidationTracker.createFlow("task_configurations", "task_versions", "task_version_selection")
    /** Read a coherent candidate; the state-store transaction checks it again before admission. */
    suspend fun task(address: TaskAddress): TaskAdmissionDraft = database.withTransaction {
        val bundle = library.activeCatalog() ?: throw ExecutionStateRejected("请先启用任务版本")
        val definition = bundle.tasks.singleOrNull { it.targetId == address.targetId && it.id == address.taskId }
            ?: throw ExecutionStateRejected("当前版本没有此任务")
        val target = bundle.targets.single { it.id == address.targetId }
        val saved = database.taskConfigurations().get(address.targetId, address.taskId)
        val values = try { saved?.let { Json.parseToJsonElement(it.valuesJson).jsonObject } ?: JsonObject(emptyMap()) }
        catch (_: IllegalArgumentException) { throw CorruptExecutionState("任务配置读取失败，原数据保留") }
        if (validateConfiguration(definition, values).isNotEmpty()) throw ExecutionStateRejected("请先保存有效的任务配置")
        TaskAdmissionDraft(target, bundle.version, TaskSnapshot(definition.id, definition.name, definition.priority,
            definition.steps.map { it.id }, values.toString()))
    }
}
