package io.github.yysassist.plans

import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.storage.PlanDefinition
import io.github.yysassist.storage.PlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

sealed interface PlanSubmission {
    data class Accepted(val runId: Long) : PlanSubmission
    data class Rejected(val reason: String) : PlanSubmission
}
fun interface PlanSubmitter { suspend fun submit(plan: PlanDefinition, commandId: String): PlanSubmission }
interface PlanAlarm { fun set(atEpochMillis: Long); fun cancel() }

/**
 * Turns saved plans into executor submissions at their scheduled times. Time rules still undecided by the
 * product stay conservative: a run missed by more than [LATE_LIMIT_MILLIS] is recorded and never made up.
 */
class PlanCoordinator(private val repository: PlanRepository, private val submitter: PlanSubmitter, private val alarm: PlanAlarm,
    scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val mutex = Mutex()
    private val mutablePlans = MutableStateFlow<List<PlanDefinition>?>(null)
    /** Null until the first read finishes, so an unread list never looks like "no plans". */
    val plans = mutablePlans.asStateFlow()

    init { scope.launch { repository.plans.collect { mutablePlans.value = it; wake() } } }

    fun nextRun(plan: PlanDefinition, now: Long = clock()): Long =
        plan.schedule.next(Instant.ofEpochMilli(now).atZone(zone())).toInstant().toEpochMilli()

    /** Called on start, on every alarm and whenever the clock or the plans change. */
    suspend fun wake() = mutex.withLock {
        val plans = repository.all()
        plans.filter { it.enabled }.forEach { settle(it) }
        val next = repository.all().filter { it.enabled }.minOfOrNull { nextRun(it) }
        if (next == null) alarm.cancel() else alarm.set(next)
    }

    private suspend fun settle(plan: PlanDefinition) {
        val now = clock()
        val due = plan.schedule.previous(Instant.ofEpochMilli(now).atZone(zone())).toInstant().toEpochMilli()
        // Saving or enabling a plan at or after a slot means that slot is not this plan's to run.
        if (due <= maxOf(plan.updatedAt, plan.lastFiredAt ?: Long.MIN_VALUE)) return
        if (now - due > LATE_LIMIT_MILLIS) { repository.save(plan.copy(lastFiredAt = due, lastOutcome = MISSED)); return }
        // The command id makes a repeated alarm for the same slot return the original submission.
        val result = submitter.submit(plan, "plan/${plan.id}/$due")
        repository.save(plan.copy(lastFiredAt = due, lastRunId = (result as? PlanSubmission.Accepted)?.runId ?: plan.lastRunId,
            lastOutcome = (result as? PlanSubmission.Rejected)?.reason))
    }

    suspend fun save(existing: PlanDefinition?, name: String, context: ExecutionContext, schedule: PlanSchedule, taskIds: List<String>) = mutex.withLock {
        repository.save(existing?.copy(name = name.trim(), schedule = schedule, taskIds = taskIds, updatedAt = clock())
            ?: PlanDefinition(UUID.randomUUID().toString(), name.trim(), context, schedule, taskIds, true, clock()))
    }
    suspend fun setEnabled(plan: PlanDefinition, enabled: Boolean) = mutex.withLock {
        repository.get(plan.id)?.let { repository.save(it.copy(enabled = enabled, updatedAt = clock())) } ?: Unit
    }
    suspend fun delete(plan: PlanDefinition) = mutex.withLock { repository.delete(plan.id) }
    suspend fun addTask(plan: PlanDefinition, taskId: String) = mutex.withLock {
        repository.get(plan.id)?.takeIf { taskId !in it.taskIds }?.let { repository.save(it.copy(taskIds = it.taskIds + taskId, updatedAt = clock())) } ?: Unit
    }
    /** One extra round now; the schedule is unchanged. */
    suspend fun runNow(plan: PlanDefinition): PlanSubmission {
        val result = submitter.submit(plan, UUID.randomUUID().toString())
        mutex.withLock {
            repository.get(plan.id)?.let { current -> repository.save(current.copy(lastRunId = (result as? PlanSubmission.Accepted)?.runId ?: current.lastRunId,
                lastOutcome = (result as? PlanSubmission.Rejected)?.reason)) }
        }
        return result
    }

    companion object {
        const val LATE_LIMIT_MILLIS = 15 * 60 * 1000L
        const val MISSED = "错过运行时间，未补做"
    }
}
