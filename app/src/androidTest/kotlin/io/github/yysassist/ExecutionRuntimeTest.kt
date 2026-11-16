package io.github.yysassist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real SQLite + application-owned host + actual coroutine runner; no game or device input. */
@RunWith(AndroidJUnit4::class)
class ExecutionRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var store: ExecutionStateStore
    private lateinit var name: String
    private val scopes = mutableListOf<CoroutineScope>()
    private val factory = BuiltInStepWorkFactory()
    private val identity = ExecutionContext("fixture", Principal.Anonymous)
    private fun bundle(duration: Long = 60_000) = TaskBundle(1, "v1", "2026-11-16T00:00:00Z", "Fixture", listOf(
        TaskDefinition("one", "fixture", "等待一", "tools", 1, steps = listOf(StepDefinition("wait", "等待", listOf(
            ActionDefinition("delay", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(duration)))))))),
        TaskDefinition("two", "fixture", "等待二", "tools", 1, steps = listOf(StepDefinition("wait", "等待", listOf(
            ActionDefinition("delay", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(duration))))))))),
        listOf(TargetProfile("fixture", "测试应用", listOf("io.github.yysassist"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))))
    @Before fun open(): Unit = runBlocking {
        name = "runtime-${UUID.randomUUID()}.db"; database = AssistDatabase.open(context, name)
        library = TaskVersionLibrary(database, BundleCodec(factory.contracts)); store = ExecutionStateStore(database, library)
        val text = BundleCodec(factory.contracts).write(bundle())
        library.install(text, "v1", TaskVersionLibrary.sha256(text)); library.activate("v1")
    }
    @After fun close(): Unit = runBlocking {
        scopes.forEach { it.cancel() }; scopes.forEach { it.coroutineContext[Job]!!.join() }
        database.close(); context.deleteDatabase(name)
    }
    private suspend fun runtime(port: StepWorkFactory = factory, persistence: ExecutionPersistence = store,
        readiness: ExecutionContextReadiness = ExecutionContextReadiness { _, _ -> true }): ExecutionRuntime {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        return ExecutionRuntime(persistence, library, TaskAdmissionRepository(database, library), port, scope, readiness).also {
            withTimeout(5_000) { it.status.first { value -> value !is RuntimeStatus.Loading } }
        }
    }
    private suspend fun wait(runtime: ExecutionRuntime, test: (ExecutorSnapshot) -> Boolean) =
        withTimeout(5_000) { runtime.state.first(test) }
    private suspend fun add(runtime: ExecutionRuntime, id: String) = runtime.enqueueTask(TaskAddress("fixture", id)).await()
    private fun counting(counter: AtomicInteger, execute: suspend (StepAttempt) -> StepOutcome,
        stop: suspend () -> InputStopObservation = { InputStopObservation.Quiet(StepOutcome.Interrupted) }, safeRecovery: Boolean = true) =
        object : StepWorkFactory {
            override val contracts = factory.contracts
            override fun create(attempt: StepAttempt, bundle: TaskBundle) = object : StepWork {
                override suspend fun execute(): StepOutcome { counter.incrementAndGet(); return execute(attempt) }
                override suspend fun revoke() = stop()
            }
            override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle) = safeRecovery
        }

    @Test fun enqueueDeduplicatesAndManualPauseHoldsTheWholeQueueUntilContinue() = runBlocking {
        val runtime = runtime()
        assertTrue(add(runtime, "one") is RuntimeCommandResult.Applied)
        val ticket = wait(runtime) { it.phase == ExecutorPhase.RUNNING }.activeTicket!!
        val duplicate = (add(runtime, "one") as RuntimeCommandResult.Applied).reply as ExecutorReply.Enqueued
        assertEquals(EnqueueResult.Duplicate(ticket.runId), duplicate.result)
        add(runtime, "two")
        wait(runtime) { it.waiting.size == 1 }
        runtime.submit(ExecutorCommand.Pause).await()
        wait(runtime) { it.phase == ExecutorPhase.PAUSED }
        assertEquals(1, runtime.state.value.waiting.size)
        runtime.submit(ExecutorCommand.Resume(ResumeMode.CONTINUE)).await()
        assertNotEquals(ticket, wait(runtime) { it.phase == ExecutorPhase.RUNNING }.activeTicket)
    }
    @Test fun removingCurrentContinuesTheNextAndDoesNotWaitForABlockedWorker() = runBlocking {
        val release = CompletableDeferred<Unit>(); val started = AtomicInteger()
        val port = counting(started, { attempt ->
            if (attempt.run.task.id == "one") withContext(NonCancellable) { release.await() }
            else awaitCancellation()
            StepOutcome.Completed("test evidence")
        })
        try {
            val runtime = runtime(port); add(runtime, "one")
            withTimeout(5_000) { while (started.get() < 1) delay(5) }
            add(runtime, "two")
            runtime.submit(ExecutorCommand.Remove(runtime.state.value.current!!.runId)).await()
            wait(runtime) { it.current?.task?.id == "two" && it.phase == ExecutorPhase.RUNNING }
            release.complete(Unit)
            delay(30)
            assertEquals("two", runtime.state.value.current!!.task.id)
        } finally { release.complete(Unit) }
    }
    @Test fun aCancelledCallerDoesNotDropAnAcceptedCommitOrItsEffects() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.admittedRunId != null) { entered.complete(Unit); release.await() }
                return store.persist(expectedRevision, prepared)
            }
        }
        val runtime = runtime(persistence = delayed)
        val receipt = runtime.enqueueTask(TaskAddress("fixture", "one"))
        val caller = launch { receipt.await() }
        entered.await(); caller.cancelAndJoin(); release.complete(Unit)
        wait(runtime) { it.current?.task?.id == "one" }
        assertNotNull(store.load().decode().state.value.current)
    }
    @Test fun anAcknowledgementLostAfterCommitIsReadBackWithoutExecutingTwice() = runBlocking {
        var throwOnce = true
        val uncertain = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                val revision = store.persist(expectedRevision, prepared)
                if (prepared.admittedRunId != null && throwOnce) { throwOnce = false; error("fixture: commit acknowledgement lost") }
                return revision
            }
        }
        val started = AtomicInteger()
        val runtime = runtime(counting(started, { awaitCancellation() }), uncertain)
        assertTrue(add(runtime, "one") is RuntimeCommandResult.Applied)
        withTimeout(5_000) { while (started.get() == 0) delay(5) }
        assertEquals(1, started.get())
        assertEquals(1, runtime.state.value.facts.count { it.type == "enqueued" })
    }
    @Test fun safeProcessReentryRestartsTheUnfinishedStepAndKeepsManualPause() = runBlocking {
        val first = runtime(); add(first, "one"); wait(first) { it.phase == ExecutorPhase.RUNNING }
        val ticket = first.state.value.activeTicket!!
        scopes.first().cancel(); scopes.first().coroutineContext[Job]!!.join()
        val recovered = runtime()
        assertNotEquals(ticket, wait(recovered) { it.phase == ExecutorPhase.RUNNING }.activeTicket)
        recovered.submit(ExecutorCommand.Pause).await(); wait(recovered) { it.phase == ExecutorPhase.PAUSED }
        scopes.last().cancel(); scopes.last().coroutineContext[Job]!!.join()
        val held = runtime()
        assertEquals(ExecutorPhase.PAUSED, held.state.value.phase)
        assertTrue(held.state.value.manualHold)
    }
    @Test fun unsafeProcessReentryPreservesUnknownInputAndDoesNotRunAWorker() = runBlocking {
        val first = runtime(); add(first, "one"); wait(first) { it.phase == ExecutorPhase.RUNNING }
        scopes.first().cancel(); scopes.first().coroutineContext[Job]!!.join()
        val count = AtomicInteger()
        val recovered = runtime(counting(count, { StepOutcome.Completed("must not run") }, safeRecovery = false))
        assertTrue(recovered.status.value is RuntimeStatus.RecoveryBlocked)
        assertEquals(1, recovered.state.value.unresolved.size)
        recovered.submit(ExecutorCommand.Remove(recovered.state.value.current!!.runId)).await()
        recovered.submit(ExecutorCommand.Resume(ResumeMode.RESTART_CURRENT_TASK)).await()
        assertNotNull(recovered.state.value.current)
        assertEquals(0, count.get())
        assertEquals(1, store.load().decode().state.value.unresolved.size)
    }
    @Test fun slowReadinessCannotBlockPauseAndItsLateReplyCannotReviveInput() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = runtime(readiness = ExecutionContextReadiness { _, _ -> entered.complete(Unit); release.await(); true })
        // Direct verified fixture submission bypasses admission readiness to exercise the step readiness boundary.
        runtime.submit(ExecutorCommand.VerifyContext(identity)).await()
        val task = bundle().tasks.first()
        runtime.submit(ExecutorCommand.Enqueue("fixture", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), identity, "v1",
            listOf(TaskSnapshot(task.id, task.name, task.priority, listOf("wait")))))).await()
        entered.await()
        runtime.submit(ExecutorCommand.Pause).await()
        wait(runtime) { it.phase == ExecutorPhase.PAUSED }
        release.complete(Unit); delay(30)
        assertEquals(ExecutorPhase.PAUSED, runtime.state.value.phase)
        assertFalse(runtime.permitsInput(runtime.state.value.activeTicket ?: AttemptTicket(-1, -1)))
    }
    @Test fun anOlderReadinessCheckCannotOverrideANewerCheckForTheSameAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val checks = AtomicInteger(); val started = AtomicInteger()
        try {
            val runtime = runtime(counting(started, { awaitCancellation() }), readiness = ExecutionContextReadiness { _, _ ->
                if (checks.incrementAndGet() == 1) withContext(NonCancellable) { entered.complete(Unit); release.await(); true } else false
            })
            runtime.submit(ExecutorCommand.VerifyContext(identity)).await()
            val task = bundle().tasks.first()
            runtime.submit(ExecutorCommand.Enqueue("fixture", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), identity, "v1",
                listOf(TaskSnapshot(task.id, task.name, task.priority, listOf("wait")))))).await()
            entered.await(); runtime.readinessChanged()
            withTimeout(5_000) { runtime.status.first { it is RuntimeStatus.RecoveryBlocked } }
            release.complete(Unit); delay(50)
            assertEquals("An old successful check must not revive a superseded attempt binding", 0, started.get())
        } finally { release.complete(Unit) }
    }
    @Test fun confirmedFailureContinuesButUnknownResultBlocksSubsequentWork() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val count = AtomicInteger()
        val runtime = runtime(counting(count, { attempt ->
            if (attempt.run.task.id == "one") { gate.await(); StepOutcome.Failed("fixture confirmed failure") }
            else StepOutcome.Unknown("fixture unknown")
        }))
        add(runtime, "one"); add(runtime, "two"); gate.complete(Unit)
        wait(runtime) { it.unresolved.isNotEmpty() }
        assertEquals(2, count.get())
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, runtime.state.value.phase)
        assertTrue(runtime.state.value.facts.any { it.type == "task-failed" })
    }
}
