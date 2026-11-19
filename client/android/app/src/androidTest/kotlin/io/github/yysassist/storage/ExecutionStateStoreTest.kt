package io.github.yysassist.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Actual SQLite transactions; step evidence is explicitly a test fixture, not a device worker. */
@RunWith(AndroidJUnit4::class)
class ExecutionStateStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var store: ExecutionStateStore
    private val codec = BundleCodec(listOf(CapabilityContract("test.observe", 1) { null }))
    private val identity = ExecutionContext("fixture-app", Principal.Account("fixture-account"))
    private fun bundle(version: String) = TaskBundle(1, version, "2026-11-16T00:00:00Z", "测试更新", listOf(
        TaskDefinition("daily", "fixture-app", "测试观察", "tools", 10,
            listOf(ConfigurationField("amount", "数量", FieldWidget.NUMBER, true, minimum = JsonPrimitive(0))),
            listOf(StepDefinition("observe", "观察", listOf(ActionDefinition("screen", "test.observe", 1, JsonObject(emptyMap()))))))) ,
        listOf(TargetProfile("fixture-app", "测试目标", listOf("io.github.yysassist"), IdentityPolicy.REQUIRED, listOf(TaskCategory("tools", "工具")))))
    private suspend fun install(version: String) {
        val text = codec.write(bundle(version))
        assertEquals(VersionChange.Applied, library.install(text, version, TaskVersionLibrary.sha256(text)))
    }
    private fun request(amount: Int = 1, version: String = "v1"): ExecutionRequest {
        val task = bundle(version).tasks.single()
        return ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), identity, version,
            listOf(TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, "{\"amount\":$amount}")))
    }
    @Before fun open() = runBlocking {
        name = "execution-${UUID.randomUUID()}.db"
        database = AssistDatabase.open(context, name)
        library = TaskVersionLibrary(database, codec)
        store = ExecutionStateStore(database, library)
        install("v1"); assertEquals(VersionChange.Applied, library.activate("v1"))
    }
    @After fun close() { database.close(); context.deleteDatabase(name) }
    private suspend fun commit(executor: TaskExecutor, command: ExecutorCommand): PreparedExecutorTransition {
        val loaded = store.load()
        val prepared = executor.prepare(command)
        store.persist(loaded.revision, prepared)
        executor.commit(prepared)
        return prepared
    }
    private suspend fun verified(): TaskExecutor = store.load().decode().also { commit(it, ExecutorCommand.VerifyContext(identity)) }

    @Test fun historyReceivesEachNewFactOnceInTheSameTransaction() = runBlocking {
        val executor = verified()
        commit(executor, ExecutorCommand.Enqueue("submit", request()))
        val step = executor.state.value.activeTicket!!
        commit(executor, ExecutorCommand.StepSettled(step, StepOutcome.Completed("ok"), StepOutcome.Interrupted))
        val expected = executor.state.value.facts.filter { it.id > 0 }
        val stored = database.executionFacts().page(100, 0).reversed()
        assertEquals(expected.map { it.id }, stored.map { it.factId })
        assertEquals(expected.map { it.type }, stored.map { it.type })
        // A transition without new facts adds nothing; reopening keeps history.
        commit(executor, ExecutorCommand.ReadinessChanged)
        database.close(); database = AssistDatabase.open(context, name)
        assertEquals(expected.size, database.executionFacts().count())
    }
    @Test fun progressAndVersionReferencesCommitTogetherAndSurviveDatabaseReopen() = runBlocking {
        val executor = verified()
        val prepared = executor.prepare(ExecutorCommand.Enqueue("submit", request()))
        val original = executor.state.value
        assertNull(original.current)
        val revision = store.persist(store.load().revision, prepared)
        assertEquals(original, executor.state.value) // persistence cannot dispatch input or publish UI
        executor.commit(prepared)
        val runId = executor.state.value.current!!.runId
        assertEquals("RUNNING", database.taskVersions().reference("executor-run/$runId")!!.reason)
        install("v2")
        assertEquals(VersionRejection.IN_USE, (library.activate("v2") as VersionChange.Rejected).code)
        database.close(); database = AssistDatabase.open(context, name)
        library = TaskVersionLibrary(database, codec); store = ExecutionStateStore(database, library)
        val loaded = store.load()
        assertEquals(revision, loaded.revision)
        assertEquals(executor.state.value, loaded.decode().state.value)
        assertEquals("{\"amount\":1}", loaded.decode().state.value.current!!.task.configurationJson)
    }
    @Test fun concurrentWritersCannotOverwriteACommittedRoundOrLeakTheirLocks() = runBlocking {
        val first = verified(); val loaded = store.load(); val second = loaded.decode()
        val accepted = first.prepare(ExecutorCommand.Enqueue("first", request()))
        val stale = second.prepare(ExecutorCommand.Enqueue("second", request()))
        store.persist(loaded.revision, accepted); first.commit(accepted)
        try { store.persist(loaded.revision, stale); fail("stale writer admitted") }
        catch (_: ExecutionStateConflict) { }
        assertEquals(accepted.encodeState(), store.load().encodedState)
        assertEquals(1, database.taskVersions().referenceCount())
        assertNull(second.state.value.current)
    }
    @Test fun unknownResultsRemainPinnedWhenRemovedAndReleaseOnlyAfterEvidence() = runBlocking {
        val executor = verified()
        val start = commit(executor, ExecutorCommand.Enqueue("submit", request()))
        val ticket = (start.effects.single() as ExecutorEffect.ExecuteStep).attempt.ticket
        commit(executor, ExecutorCommand.StepFinished(ticket, StepOutcome.Unknown("测试结果未确认")))
        commit(executor, ExecutorCommand.Remove(ticket.runId))
        assertEquals("UNRESOLVED", database.taskVersions().reference("executor-run/${ticket.runId}")!!.reason)
        val restored = store.load().decode()
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, restored.state.value.phase)
        commit(restored, ExecutorCommand.Resume(ResumeMode.RESTART_CURRENT_TASK))
        assertEquals(1, database.taskVersions().referenceCount())
        commit(restored, ExecutorCommand.ResolveUnknown(ticket, StepOutcome.Completed("测试夹具已核对")))
        assertNull(restored.state.value.current)
        assertEquals(0, database.taskVersions().referenceCount())
    }
    @Test fun completionReleasesItsOwnLockAndPreservesOtherOwners() = runBlocking {
        library.retain("v1", "debug-owner", VersionReferenceReason.UNRESOLVED)
        val executor = verified()
        val start = commit(executor, ExecutorCommand.Enqueue("submit", request()))
        val ticket = (start.effects.single() as ExecutorEffect.ExecuteStep).attempt.ticket
        commit(executor, ExecutorCommand.StepFinished(ticket, StepOutcome.Completed("测试夹具完成")))
        assertNull(executor.state.value.current)
        assertEquals("debug-owner", database.taskVersions().references().single().owner)
        val replay = commit(executor, ExecutorCommand.Enqueue("submit", request()))
        assertEquals(ExecutorReply.Enqueued(EnqueueResult.Accepted(ticket.runId)), replay.reply)
        assertTrue(replay.effects.isEmpty())
        assertEquals(1, database.taskVersions().referenceCount())
    }
    @Test fun lateUncertaintyCanBeRecordedAfterCompletedContentWasDeleted() = runBlocking {
        val executor = verified()
        val start = commit(executor, ExecutorCommand.Enqueue("submit", request()))
        val ticket = (start.effects.single() as ExecutorEffect.ExecuteStep).attempt.ticket
        commit(executor, ExecutorCommand.StepFinished(ticket, StepOutcome.Completed("测试夹具完成")))
        install("v2"); assertEquals(VersionChange.Applied, library.activate("v2"))
        assertEquals(VersionChange.Applied, library.delete("v1"))
        assertFalse(library.snapshot().versions.any { it.version == "v1" })
        commit(executor, ExecutorCommand.StepFinished(ticket, StepOutcome.Unknown("迟到的未确认结果")))
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, store.load().decode().state.value.phase)
        assertEquals("UNRESOLVED", database.taskVersions().reference("executor-run/${ticket.runId}")!!.reason)
        assertFalse(library.snapshot().versions.any { it.version == "v1" })
        install("v3")
        assertEquals(VersionRejection.IN_USE, (library.activate("v3") as VersionChange.Rejected).code)
        install("v1")
        assertEquals(VersionRejection.IN_USE, (library.delete("v1") as VersionChange.Rejected).code)
        commit(executor, ExecutorCommand.ResolveUnknown(ticket, StepOutcome.Completed("已核对旧结果")))
        assertEquals(VersionChange.Applied, library.delete("v1"))
    }
    @Test fun corruptStoredProgressIsNotTreatedAsAFirstLaunchOrOverwritten() = runBlocking {
        val executor = verified(); val before = database.executorState().get()!!
        val prepared = executor.prepare(ExecutorCommand.Pause)
        val corrupt = before.copy(payload = "broken")
        database.executorState().save(corrupt)
        try { store.load(); fail("corruption became absence") } catch (_: CorruptExecutionState) { }
        try { store.persist(before.revision, prepared); fail("corruption overwritten") } catch (_: CorruptExecutionState) { }
        assertEquals(corrupt, database.executorState().get())
        assertFalse(executor.state.value.manualHold)
    }
    @Test fun aMissingStateRowWithExecutionReferencesCannotBecomeAnEmptyFirstLaunch() = runBlocking {
        library.retainUnresolved("v1", "executor-run/88")
        try { store.load(); fail("missing execution state became an empty queue") }
        catch (_: CorruptExecutionState) { }
        assertEquals("UNRESOLVED", database.taskVersions().reference("executor-run/88")!!.reason)
        assertNull(database.executorState().get())
    }
}
