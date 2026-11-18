package io.github.yysassist.storage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(tableName = "task_configurations", primaryKeys = ["targetId", "taskId"])
data class TaskConfigurationEntity(
    val targetId: String, val taskId: String, val valuesJson: String, val schemaJson: String,
    val revision: Long, val validatedVersion: String, val validatedDigest: String,
)

@Entity(tableName = "executor_state")
data class ExecutorStateEntity(@PrimaryKey val id: Int = 1, val revision: Long, val payload: String, val digest: String)

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
interface TaskConfigurationDao {
    @Query("SELECT * FROM task_configurations WHERE targetId = :targetId AND taskId = :taskId")
    suspend fun get(targetId: String, taskId: String): TaskConfigurationEntity?
    @Upsert suspend fun save(configuration: TaskConfigurationEntity)
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

@Database(entities = [TaskVersionEntity::class, TaskVersionSelection::class, TaskVersionReference::class, TaskConfigurationEntity::class,
    ExecutorStateEntity::class, ActionTraceEntity::class], version = 5, exportSchema = true)
abstract class AssistDatabase : RoomDatabase() {
    abstract fun taskVersions(): TaskVersionDao
    abstract fun taskConfigurations(): TaskConfigurationDao
    abstract fun executorState(): ExecutorStateDao
    abstract fun actionTraces(): ActionTraceDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_configurations` (`targetId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `valuesJson` TEXT NOT NULL, `schemaJson` TEXT NOT NULL, `revision` INTEGER NOT NULL, `validatedVersion` TEXT NOT NULL, `validatedDigest` TEXT NOT NULL, PRIMARY KEY(`targetId`, `taskId`))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `executor_state` (`id` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `payload` TEXT NOT NULL, `digest` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `task_versions` ADD COLUMN `cached` INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `action_traces` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `taskId` TEXT NOT NULL, `stepId` TEXT NOT NULL, `actionId` TEXT NOT NULL, `capability` TEXT NOT NULL, `contractVersion` INTEGER NOT NULL, `result` TEXT NOT NULL, `reason` TEXT, `traceJson` TEXT, `elapsedMillis` INTEGER NOT NULL, `recordedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_traces_runId` ON `action_traces` (`runId`)")
            }
        }
        fun open(context: Context, name: String = "assist.db"): AssistDatabase =
            Room.databaseBuilder(context.applicationContext, AssistDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
