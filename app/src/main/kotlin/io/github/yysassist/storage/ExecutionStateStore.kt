package io.github.yysassist.storage

import androidx.room.withTransaction
import io.github.yysassist.core.content.validateConfiguration
import io.github.yysassist.core.execution.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class LoadedExecutorState(val revision: Long, val encodedState: String) {
    /** Restores recorded control state only; startup/recovery decisions belong to the host. */
    fun decode(): TaskExecutor = TaskExecutor.fromEncodedState(encodedState)
}
class ExecutionStateConflict(message: String) : IllegalStateException(message)
class ExecutionStateRejected(message: String) : IllegalStateException(message)
class CorruptExecutionState(message: String) : IllegalStateException(message)

/** Commits control progress and all live/unresolved version references in one SQLite transaction. */
interface ExecutionPersistence {
    suspend fun load(): LoadedExecutorState
    suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long
}
class ExecutionStateStore(private val database: AssistDatabase, private val library: TaskVersionLibrary) : ExecutionPersistence {
    private val prefix = "executor-run/"
    override suspend fun load(): LoadedExecutorState = database.withTransaction { read() }

    override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long = database.withTransaction {
        val before = read()
        if (before.revision != expectedRevision || before.encodedState != prepared.baseEncodedState)
            throw ExecutionStateConflict("执行状态已变化，请重新读取")
        val snapshot = prepared.snapshot
        val live = listOfNotNull(snapshot.current) + snapshot.waiting
        val required = (live + snapshot.unresolved.map { it.attempt.run }).groupBy { it.runId }
        val desired = required.map { (runId, records) ->
            val run = records.first()
            check(records.all { it.request == run.request }) { "同一轮执行绑定不一致" }
            if (runId == prepared.admittedRunId && database.taskVersions().selection()?.activeVersion != run.request.packageVersion)
                throw ExecutionStateRejected("任务版本已变化，请重新提交")
            val reason = when {
                snapshot.unresolved.any { it.attempt.ticket.runId == runId } -> VersionReferenceReason.UNRESOLVED
                snapshot.manualHold && snapshot.activeTicket == null -> VersionReferenceReason.PAUSED
                snapshot.current?.runId == runId -> VersionReferenceReason.RUNNING
                else -> VersionReferenceReason.QUEUED
            }
            val owner = "$prefix$runId"
            val pinned = if (runId != prepared.admittedRunId) {
                val retained = library.retainRecorded(run.request.packageVersion, owner, reason)
                if (retained is VersionChange.Rejected) throw ExecutionStateRejected(retained.message)
                null
            } else when (val result = library.retain(run.request.packageVersion, owner, reason)) {
                is PinVersionResult.Pinned -> result.content
                is PinVersionResult.Rejected -> throw ExecutionStateRejected(result.reason.message)
            }
            if (runId == prepared.admittedRunId) {
                check(pinned != null)
                val target = pinned.bundle.targets.singleOrNull { it.id == run.request.context.targetId }
                if (target?.acceptsContext(run.request.context) != true) throw ExecutionStateRejected("目标或账号身份不符合任务要求")
                run.request.tasks.forEach { task ->
                    val definition = pinned.bundle.tasks.singleOrNull { it.targetId == target.id && it.id == task.id }
                        ?: throw ExecutionStateRejected("当前版本没有此任务")
                    if (definition.name != task.name || definition.priority != task.priority || definition.steps.map { it.id } != task.stepIds)
                        throw ExecutionStateRejected("任务内容已变化，请重新提交")
                    val submitted = try { Json.parseToJsonElement(task.configurationJson).jsonObject }
                    catch (_: IllegalArgumentException) { throw ExecutionStateRejected("任务配置格式无效") }
                    val saved = database.taskConfigurations().get(target.id, task.id)
                    val values = try { saved?.let { Json.parseToJsonElement(it.valuesJson).jsonObject } ?: JsonObject(emptyMap()) }
                    catch (_: IllegalArgumentException) { throw CorruptExecutionState("已保存任务配置损坏，原数据保留") }
                    if (submitted != values) throw ExecutionStateRejected("任务配置已变化，请重新提交")
                    if (validateConfiguration(definition, submitted).isNotEmpty()) throw ExecutionStateRejected("请先完成有效的任务配置")
                }
            }
            owner
        }.toSet()
        database.taskVersions().references().filter { it.owner.startsWith(prefix) && it.owner !in desired }
            .forEach { library.release(it.owner) }
        val revision = Math.addExact(before.revision, 1)
        val payload = prepared.encodeState()
        database.executorState().save(ExecutorStateEntity(revision = revision, payload = payload, digest = TaskVersionLibrary.sha256(payload)))
        revision
    }

    private suspend fun read(): LoadedExecutorState {
        val row = database.executorState().get() ?: run {
            if (database.taskVersions().references().any { it.owner.startsWith(prefix) })
                throw CorruptExecutionState("执行状态缺失，但执行引用仍存在，原数据保留")
            return LoadedExecutorState(0, TaskExecutor().encodeState())
        }
        if (row.revision < 1 || row.digest != TaskVersionLibrary.sha256(row.payload))
            throw CorruptExecutionState("执行状态摘要不匹配，原数据保留")
        val restored = try { TaskExecutor.fromEncodedState(row.payload) }
        catch (_: IllegalArgumentException) { throw CorruptExecutionState("执行状态格式无效，原数据保留") }
        return LoadedExecutorState(row.revision, restored.encodeState())
    }
}
