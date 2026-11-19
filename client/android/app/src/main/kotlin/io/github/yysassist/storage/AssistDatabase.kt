package io.github.yysassist.storage

import androidx.room.*
import android.content.Context

/** Immutable JSON includes resource identities; verified blobs are published before this ready marker. */
@Entity(tableName = "task_versions")
data class TaskVersionEntity(
    @PrimaryKey val version: String,
    val digest: String,
    val json: String,
    val updatedAt: String,
    val changelog: String,
    val installedAt: Long,
    @ColumnInfo(defaultValue = "1") val cached: Boolean = true,
)

@Entity(tableName = "task_version_selection")
data class TaskVersionSelection(
    @PrimaryKey val id: Int = 1,
    val activeVersion: String? = null,
    val latestVersion: String? = null,
)

@Entity(tableName = "task_version_references", foreignKeys = [ForeignKey(
    entity = TaskVersionEntity::class, parentColumns = ["version"], childColumns = ["version"],
    onDelete = ForeignKey.RESTRICT,
)], indices = [Index("version")])
data class TaskVersionReference(@PrimaryKey val owner: String, val version: String, val reason: String)

data class TaskVersionRow(
    val version: String,
    val digest: String,
    val updatedAt: String,
    val changelog: String,
    val referenceCount: Int,
)

@Entity(tableName = "executor_state")
data class ExecutorStateEntity(@PrimaryKey val id: Int = 1, val revision: Long, val payload: String, val digest: String)

/**
 * Append-only execution history. The executor state keeps only recent facts; this keeps them all.
 * [factId] is the executor's id (restarts if the state is ever reset), so rows use their own key.
 */
@Entity(tableName = "execution_facts", indices = [Index("runId"), Index("occurredAt")])
data class ExecutionFactEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0, val factId: Long, val runId: Long, val type: String, val taskId: String?, val detail: String,
    val taskName: String, val contextJson: String, val packageVersion: String, val occurredAt: Long,
)

@Dao
interface ExecutionFactDao {
    @Insert suspend fun insert(facts: List<ExecutionFactEntity>)
    @Query("SELECT * FROM execution_facts ORDER BY seq DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ExecutionFactEntity>
    @Query("SELECT COUNT(*) FROM execution_facts") suspend fun count(): Int
}

/** Diagnostic action evidence (regions, scores, digests, seeds). Not part of execution state; pruned. */
@Entity(tableName = "action_traces", indices = [Index("runId")])
data class ActionTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long, val generation: Long, val taskId: String, val stepId: String,
    val actionId: String, val capability: String, val contractVersion: Int,
    val result: String, val reason: String?, val traceJson: String?, val elapsedMillis: Long, val recordedAt: Long,
)

@Dao
interface ActionTraceDao {
    @Insert suspend fun insert(trace: ActionTraceEntity)
    @Query("SELECT * FROM action_traces WHERE runId = :runId ORDER BY id")
    suspend fun forRun(runId: Long): List<ActionTraceEntity>
    @Query("SELECT * FROM action_traces ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActionTraceEntity>
    @Query("DELETE FROM action_traces WHERE id <= (SELECT MAX(id) FROM action_traces) - :keep")
    suspend fun prune(keep: Int): Int
}

@Dao
interface ExecutorStateDao {
    @Query("SELECT * FROM executor_state WHERE id = 1") suspend fun get(): ExecutorStateEntity?
    @Upsert suspend fun save(state: ExecutorStateEntity)
}

@Dao
interface TaskVersionDao {
    @Query("SELECT * FROM task_versions WHERE cached = 1")
    suspend fun cachedVersions(): List<TaskVersionEntity>

    @Query("SELECT version, digest, updatedAt, changelog, (SELECT COUNT(*) FROM task_version_references r WHERE r.version = v.version) AS referenceCount FROM task_versions v WHERE cached = 1 ORDER BY installedAt DESC, rowid DESC")
    suspend fun versions(): List<TaskVersionRow>

    @Query("SELECT * FROM task_version_selection WHERE id = 1")
    suspend fun selection(): TaskVersionSelection?

    @Query("SELECT * FROM task_versions WHERE version = :version")
    suspend fun version(version: String): TaskVersionEntity?

    @Query("SELECT * FROM task_version_references WHERE owner = :owner")
    suspend fun reference(owner: String): TaskVersionReference?

    @Query("SELECT * FROM task_version_references")
    suspend fun references(): List<TaskVersionReference>

    @Query("SELECT COUNT(*) FROM task_version_references")
    suspend fun referenceCount(): Int

    @Query("SELECT COUNT(*) FROM task_version_references WHERE version = :version")
    suspend fun referenceCount(version: String): Int

    @Insert
    suspend fun insertVersion(version: TaskVersionEntity)

    @Query("UPDATE task_versions SET json = :json, cached = 1, installedAt = :installedAt WHERE version = :version AND cached = 0")
    suspend fun restoreContents(version: String, json: String, installedAt: Long): Int

    @Upsert
    suspend fun saveSelection(selection: TaskVersionSelection)

    @Upsert
    suspend fun saveReference(reference: TaskVersionReference)

    @Query("DELETE FROM task_versions WHERE version = :version")
    suspend fun deleteVersion(version: String): Int

    @Query("UPDATE task_versions SET json = '', cached = 0 WHERE version = :version AND cached = 1")
    suspend fun evictContents(version: String): Int

    @Query("DELETE FROM task_version_references WHERE owner = :owner")
    suspend fun releaseReference(owner: String): Int
}

// Task settings and plans live on the server (the app keeps the copy it was sent); they are not stored here.
@Database(entities = [TaskVersionEntity::class, TaskVersionSelection::class, TaskVersionReference::class,
    ExecutorStateEntity::class, ActionTraceEntity::class, ExecutionFactEntity::class], version = 8, exportSchema = true)
abstract class AssistDatabase : RoomDatabase() {
    abstract fun taskVersions(): TaskVersionDao
    abstract fun executorState(): ExecutorStateDao
    abstract fun actionTraces(): ActionTraceDao
    abstract fun executionFacts(): ExecutionFactDao
    companion object {
        /**
         * Not released yet: a schema change rebuilds the database instead of migrating it (see project
         * convention). Task versions, configurations and the queue are re-created; the GitHub session
         * lives in its own encrypted file and is not affected.
         */
        fun open(context: Context, name: String = "assist.db"): AssistDatabase =
            Room.databaseBuilder(context.applicationContext, AssistDatabase::class.java, name)
                // Covers upgrades and downgrades. Do not also call fallbackToDestructiveMigrationOnDowngrade:
                // in Room 2.8 it turns "migration required" back on and an old database then crashes the app.
                .fallbackToDestructiveMigration(true).build()
    }
}
