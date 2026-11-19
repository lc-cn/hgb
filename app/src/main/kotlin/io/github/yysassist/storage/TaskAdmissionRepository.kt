package io.github.yysassist.storage

import androidx.room.withTransaction
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.ExecutionContext
import kotlinx.serialization.json.*

data class TaskAdmissionDraft(val target: TargetProfile, val version: String, val ref: SubmissionRef, val tasks: List<TaskSnapshot>, val label: String? = null) {
    fun request(context: ExecutionContext): ExecutionRequest {
        if (!target.acceptsContext(context)) throw ExecutionStateRejected("请先核对任务目标与当前账号")
        return ExecutionRequest(ref, context, version, tasks, label)
    }
}
class TaskAdmissionRepository(private val database: AssistDatabase, private val library: TaskVersionLibrary) {
    val changes = database.invalidationTracker.createFlow("task_configurations", "task_versions", "task_version_selection")
    /** Read a coherent candidate; the state-store transaction checks it again before admission. */
    suspend fun task(address: TaskAddress): TaskAdmissionDraft = database.withTransaction {
        val bundle = library.activeCatalog() ?: throw ExecutionStateRejected("请先启用任务版本")
        TaskAdmissionDraft(bundle.targets.single { it.id == address.targetId }.takeIf { bundle.tasks.any { task -> task.targetId == address.targetId && task.id == address.taskId } }
            ?: throw ExecutionStateRejected("当前版本没有此任务"), bundle.version, SubmissionRef(SubmissionKind.TASK, address.taskId), listOf(snapshot(bundle, address.targetId, address.taskId)))
    }
    /** Passive tasks the user has switched on for this target, from the active version. */
    suspend fun armedTriggers(targetId: String): List<TaskDefinition> = database.withTransaction {
        library.activeCatalog()?.tasks.orEmpty().filter { task ->
            val trigger = task.trigger
            if (trigger == null || task.targetId != targetId) return@filter false
            val saved = database.taskConfigurations().get(targetId, task.id) ?: return@filter false
            runCatching { trigger.enabled(Json.parseToJsonElement(saved.valuesJson).jsonObject) }.getOrDefault(false)
        }
    }
    /** All members are read from one version in one transaction, in the plan's order. */
    suspend fun plan(planId: String, name: String, targetId: String, taskIds: List<String>): TaskAdmissionDraft = database.withTransaction {
        val bundle = library.activeCatalog() ?: throw ExecutionStateRejected("请先启用任务版本")
        val target = bundle.targets.singleOrNull { it.id == targetId } ?: throw ExecutionStateRejected("当前版本没有此计划的目标")
        TaskAdmissionDraft(target, bundle.version, SubmissionRef(SubmissionKind.PLAN, planId), taskIds.map { snapshot(bundle, targetId, it) }, name)
    }
    private suspend fun snapshot(bundle: TaskBundle, targetId: String, taskId: String): TaskSnapshot {
        val definition = bundle.tasks.singleOrNull { it.targetId == targetId && it.id == taskId }
            ?: throw ExecutionStateRejected("当前版本没有此任务")
        val saved = database.taskConfigurations().get(targetId, taskId)
        val values = try { saved?.let { Json.parseToJsonElement(it.valuesJson).jsonObject } ?: JsonObject(emptyMap()) }
        catch (_: IllegalArgumentException) { throw CorruptExecutionState("任务配置读取失败，原数据保留") }
        if (library.configurationIssues(definition, values).isNotEmpty()) throw ExecutionStateRejected("请先保存「${definition.name}」的有效配置")
        return TaskSnapshot(definition.id, definition.name, definition.priority, definition.steps.map { it.id }, values.toString())
    }
}
