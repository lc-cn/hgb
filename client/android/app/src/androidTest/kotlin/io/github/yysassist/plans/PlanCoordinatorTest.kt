package io.github.yysassist.plans

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/** Real Room storage with a controlled clock; the alarm and executor sit behind their seams. */
@RunWith(AndroidJUnit4::class)
class PlanCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var database: AssistDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: PlanCoordinator
    private val submitted = mutableListOf<String>()
    private var alarmAt: Long? = null
    private var now = time(9, 0)
    private var answer: PlanSubmission = PlanSubmission.Accepted(7)
    private val owner = ExecutionContext("app", Principal.Anonymous)
    private val daily = PlanSchedule(DayOfWeek.entries.toSet(), 10 * 60)
    private fun time(hour: Int, minute: Int, day: Int = 18) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before fun open(): Unit = runBlocking {
        name = "plans-${UUID.randomUUID()}.db"; database = AssistDatabase.open(context, name)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = PlanCoordinator(PlanRepository(database), { _, command -> synchronized(submitted) { submitted += command }; answer },
            object : PlanAlarm { override fun set(atEpochMillis: Long) { alarmAt = atEpochMillis }; override fun cancel() { alarmAt = null } },
            scope, { now }, { ZoneOffset.UTC })
    }
    @After fun close(): Unit = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); database.close(); context.deleteDatabase(name) }
    private suspend fun saved(): PlanDefinition { coordinator.save(null, "每日", owner, daily, listOf("gift")); coordinator.wake(); return PlanRepository(database).all().single() }

    @Test fun savingOnlyRegistersTheNextRunAndDoesNotRunNow(): Unit = runBlocking {
        now = time(10, 5); saved()
        assertTrue(submitted.isEmpty()); assertEquals(time(10, 0, 19), alarmAt)
    }
    @Test fun aDueSlotSubmitsOnceEvenWhenTheAlarmRepeats(): Unit = runBlocking {
        saved(); now = time(10, 0); coordinator.wake(); coordinator.wake()
        assertEquals(1, submitted.size)
        assertEquals(7L, PlanRepository(database).all().single().lastRunId); assertEquals(time(10, 0, 19), alarmAt)
    }
    @Test fun aSlotMissedForTooLongIsRecordedAndNeverMadeUp(): Unit = runBlocking {
        saved(); now = time(10, 16); coordinator.wake()
        assertTrue(submitted.isEmpty())
        assertEquals(PlanCoordinator.MISSED, PlanRepository(database).all().single().lastOutcome)
    }
    @Test fun disabledPlansNeitherRunNorHoldAnAlarmAndEnablingDoesNotRunALateSlot(): Unit = runBlocking {
        val plan = saved(); coordinator.setEnabled(plan, false); coordinator.wake(); assertNull(alarmAt)
        now = time(10, 1); coordinator.wake(); assertTrue(submitted.isEmpty())
        coordinator.setEnabled(plan, true); coordinator.wake(); assertTrue(submitted.isEmpty())
    }
    @Test fun aRejectedSubmissionKeepsItsReasonAndARunNowDoesNotConsumeTheSchedule(): Unit = runBlocking {
        val plan = saved(); answer = PlanSubmission.Rejected("请先保存有效配置")
        coordinator.runNow(plan)
        assertEquals("请先保存有效配置", PlanRepository(database).all().single().lastOutcome)
        answer = PlanSubmission.Accepted(8); now = time(10, 0); coordinator.wake()
        assertEquals(2, submitted.size)
    }
}
