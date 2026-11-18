package io.github.yysassist.storage

import androidx.room.*
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.plan.PlanSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Definition plus the latest outcome only; full run history stays in the execution facts. */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String, val name: String, val contextJson: String, val daysMask: Int, val minuteOfDay: Int,
    val taskIdsJson: String, val enabled: Boolean, val updatedAt: Long,
    val lastFiredAt: Long? = null, val lastRunId: Long? = null, val lastOutcome: String? = null,
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY minuteOfDay, name") fun observe(): Flow<List<PlanEntity>>
    @Query("SELECT * FROM plans ORDER BY minuteOfDay, name") suspend fun all(): List<PlanEntity>
    @Query("SELECT * FROM plans WHERE id = :id") suspend fun get(id: String): PlanEntity?
    @Upsert suspend fun save(plan: PlanEntity)
    @Query("DELETE FROM plans WHERE id = :id") suspend fun delete(id: String)
}

data class PlanDefinition(
    val id: String, val name: String, val context: ExecutionContext, val schedule: PlanSchedule, val taskIds: List<String>,
    val enabled: Boolean, val updatedAt: Long, val lastFiredAt: Long? = null, val lastRunId: Long? = null, val lastOutcome: String? = null,
)

class PlanRepository(private val database: AssistDatabase) {
    private val tasks = ListSerializer(String.serializer())
    private fun PlanEntity.definition() = PlanDefinition(id, name, Json.decodeFromString(ExecutionContext.serializer(), contextJson),
        PlanSchedule(PlanSchedule.daysOf(daysMask), minuteOfDay), Json.decodeFromString(tasks, taskIdsJson), enabled, updatedAt, lastFiredAt, lastRunId, lastOutcome)
    private fun PlanDefinition.entity() = PlanEntity(id, name, Json.encodeToString(ExecutionContext.serializer(), context), schedule.daysMask,
        schedule.minuteOfDay, Json.encodeToString(tasks, taskIds), enabled, updatedAt, lastFiredAt, lastRunId, lastOutcome)

    val plans: Flow<List<PlanDefinition>> = database.plans().observe().map { rows -> rows.map { it.definition() } }
    suspend fun all(): List<PlanDefinition> = database.plans().all().map { it.definition() }
    suspend fun get(id: String): PlanDefinition? = database.plans().get(id)?.definition()
    suspend fun save(plan: PlanDefinition) = database.plans().save(plan.entity())
    suspend fun delete(id: String) = database.plans().delete(id)
}
