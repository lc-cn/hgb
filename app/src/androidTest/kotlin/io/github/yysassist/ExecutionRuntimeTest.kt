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
    private fun inputIntent(runtime: ExecutionRuntime) = DeviceActionIntent("fixture-input-call", runtime.state.value.activeTicket!!,
        "fixture-input", "fixture.tap", 1, "fixture-observation")
    private fun actionWrite(result: RuntimeCommandResult): DeviceActionWriteResult =
        ((result as RuntimeCommandResult.Applied).reply as ExecutorReply.DeviceActionRecorded).result

    @Test fun deviceActionCannotBeAcknowledgedBeforeItsStateTransactionCommits(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val persistence = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.snapshot.deviceActions.isNotEmpty()) { entered.complete(Unit); release.await() }
                return store.persist(expectedRevision, prepared)
            }
        }
        val host = runtime(persistence = persistence); add(host, "one")
        val call = inputIntent(host)
        val response = async { host.submit(ExecutorCommand.RecordDeviceAction(call)).await() }
        try {
            withTimeout(5_000) { entered.await() }
            assertFalse(response.isCompleted)
            assertTrue(host.state.value.deviceActions.isEmpty())
            assertTrue(store.load().decode().state.value.deviceActions.isEmpty())
            release.complete(Unit)
            assertEquals(DeviceActionWriteResult.Recorded, withTimeout(5_000) { actionWrite(response.await()) })
            assertEquals(call, store.load().decode().state.value.deviceActions.single().intent)
            assertEquals(DeviceActionWriteResult.AlreadyRecorded, actionWrite(host.submit(ExecutorCommand.RecordDeviceAction(call)).await()))
            assertEquals(call.ticket, host.state.value.activeTicket)
        } finally { release.complete(Unit) }
    }

    @Test fun aFailedDeviceActionWriteCannotGrantAdmissionAndClosesInput(): Unit = runBlocking {
        val persistence = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.snapshot.deviceActions.isNotEmpty()) throw java.io.IOException("fixture storage failure")
                return store.persist(expectedRevision, prepared)
            }
        }
        val host = runtime(persistence = persistence); add(host, "one"); val call = inputIntent(host)
        assertTrue(host.submit(ExecutorCommand.RecordDeviceAction(call)).await() is RuntimeCommandResult.Unconfirmed)
        assertTrue(host.status.value is RuntimeStatus.Failure)
        assertFalse(host.permitsInput(call.ticket)); assertTrue(store.load().decode().state.value.deviceActions.isEmpty())
    }

    @Test fun lostDeviceActionCommitAcknowledgementIsReadBackAndDuplicateIsNeverANewInvocation(): Unit = runBlocking {
        val writes = AtomicInteger()
        val persistence = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                val revision = store.persist(expectedRevision, prepared)
                if (prepared.snapshot.deviceActions.isNotEmpty()) {
                    writes.incrementAndGet(); throw java.io.IOException("fixture acknowledgement lost after commit")
                }
                return revision
            }
        }
        val host = runtime(persistence = persistence); add(host, "one"); val call = inputIntent(host)
        assertEquals(DeviceActionWriteResult.Recorded, actionWrite(host.submit(ExecutorCommand.RecordDeviceAction(call)).await()))
        assertEquals(DeviceActionWriteResult.AlreadyRecorded, actionWrite(host.submit(ExecutorCommand.RecordDeviceAction(call)).await()))
        assertEquals(1, writes.get()); assertEquals(1, host.state.value.deviceActions.size)
        assertEquals(RuntimeStatus.Ready, host.status.value)
        assertTrue(host.submit(ExecutorCommand.RecordDeviceAction(call.copy(capability = "changed"))).await() is RuntimeCommandResult.Rejected)
        assertEquals(call, store.load().decode().state.value.deviceActions.single().intent)
    }

    @Test fun reopeningWithARecordedInputCannotUseTheNoInputFactoryProofToReplay(): Unit = runBlocking {
        val host = runtime(); add(host, "one"); val call = inputIntent(host)
        assertEquals(DeviceActionWriteResult.Recorded, actionWrite(host.submit(ExecutorCommand.RecordDeviceAction(call)).await()))
        scopes.last().let { it.cancel(); it.coroutineContext[Job]!!.join() }
        val count = AtomicInteger()
        val reloaded = runtime(counting(count, { awaitCancellation() }))
        assertTrue(reloaded.status.value is RuntimeStatus.RecoveryBlocked)
        assertEquals(0, count.get()); assertFalse(reloaded.permitsInput(call.ticket))
        assertEquals(call, reloaded.state.value.deviceActions.single().intent)
        assertEquals(call.ticket, reloaded.state.value.unresolved.single().attempt.ticket)
        assertEquals(1, library.snapshot().versions.single().referenceCount)
    }
    @Test fun candidateAssessmentIsReadOnlyAndDoesNotConflateReadErrorsWithAnUnavailableTask() = runBlocking {
        val runtime = runtime()
        val address = TaskAddress("fixture", "one")
        val before = store.load()
        assertEquals(TaskStartAvailability.READY, runtime.assessStart(address))
        assertEquals(TaskStartAvailability.BLOCKED, runtime.assessStart(address.copy(taskId = "absent")))
        assertEquals(before, store.load())
        assertEquals(0, library.snapshot().versions.single().referenceCount)
        // A malformed saved value must stay unknown, not become a proven unsupported candidate.
        database.taskConfigurations().save(TaskConfigurationEntity("fixture", "one", "{broken", "[]", 1, "v1", "fixture"))
        assertEquals(TaskStartAvailability.UNKNOWN, runtime.assessStart(address))
        assertEquals("{broken", database.taskConfigurations().get("fixture", "one")!!.valuesJson)
        assertEquals(before, store.load())
    }
    @Test fun missingRequiredConfigurationBlocksWhileUnverifiedReadinessStaysUnknown() = runBlocking {
        val original = bundle()
        val configured = original.copy(version = "v2", tasks = original.tasks.map { it.copy(fields = listOf(
            ConfigurationField("choice", "选择", FieldWidget.TEXT, true))) })
        val text = BundleCodec(factory.contracts).write(configured)
        library.install(text, "v2", TaskVersionLibrary.sha256(text)); library.activate("v2")
        val runtime = runtime(readiness = ExecutionContextReadiness { _, _ -> false })
        val address = TaskAddress("fixture", "one")
        assertEquals(TaskStartAvailability.BLOCKED, runtime.assessStart(address))
        val repository = TaskConfigurationRepository(database, library)
        assertTrue(repository.save(repository.open(address), JsonObject(mapOf("choice" to JsonPrimitive("value")))) is ConfigurationSaveResult.Saved)
        assertEquals(TaskStartAvailability.UNKNOWN, runtime.assessStart(address))
        assertNull(runtime.state.value.current)
    }
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

    @Test fun aQuietNoInputInterruptionReentersOnlyAfterIndependentQuietAndDoesNotReportBusinessCompletion(): Unit = runBlocking {
        val count = AtomicInteger(); val quiet = CompletableDeferred<Unit>()
        val port = counting(count, {
            if (count.get() == 1) StepOutcome.Interrupted else awaitCancellation()
        }, stop = { quiet.await(); InputStopObservation.Quiet(StepOutcome.Interrupted) })
        val runtime = runtime(port); add(runtime, "one")
        withTimeout(5_000) { while (count.get() != 1) delay(5) }
        val original = runtime.state.value.activeTicket
        assertNotNull(original); assertTrue(runtime.state.value.unresolved.isEmpty())
        quiet.complete(Unit)
        val reentered = wait(runtime) { it.activeTicket != original && it.facts.any { fact -> fact.type == "step-reentered" } }
        withTimeout(5_000) { while (count.get() != 2) delay(5) }
        assertTrue(reentered.unresolved.isEmpty())
        assertFalse(reentered.facts.any { it.type == "task-completed" || it.type == "task-failed" || it.type == "process-step-reentered" })
        assertEquals(RuntimeStatus.Ready, runtime.status.value)
    }

    @Test fun aWorkerInterruptionWithoutNoInputProofCannotAutomaticallyReplay(): Unit = runBlocking {
        val count = AtomicInteger()
        val port = counting(count, { StepOutcome.Interrupted }, safeRecovery = false)
        val runtime = runtime(port); add(runtime, "one")
        val state = wait(runtime) { it.unresolved.isNotEmpty() }
        assertEquals(ExecutorPhase.BLOCKED_UNKNOWN, state.phase); assertEquals(1, count.get())
        assertFalse(state.facts.any { it.type == "task-completed" || it.type == "task-failed" })
        assertEquals(RuntimeStatus.Ready, runtime.status.value)
    }
    @Test fun retainedBusinessProofCanSettleAnInterruptedAttemptWithoutAPendingHandoff(): Unit = runBlocking {
        val count = AtomicInteger()
        val port = counting(count, { StepOutcome.Interrupted },
            stop = { InputStopObservation.Quiet(StepOutcome.Completed("输入所有者保留的本次业务核对")) }, safeRecovery = false)
        val host = runtime(port); add(host, "one")
        val state = wait(host) { it.current == null || it.unresolved.isNotEmpty() }
        assertTrue(state.unresolved.isEmpty()); assertNull(state.activeTicket)
        assertEquals(1, count.get()); assertEquals(1, state.facts.count { it.type == "task-completed" })
        assertEquals(0, library.snapshot().versions.single().referenceCount)
    }

    @Test fun rejectedHigherPriorityAdmissionReentersTheInterruptedOldReadOnlyAttemptWithoutAdmittingTheNewTask(): Unit = runBlocking {
        val definition = bundle().copy(version = "rejected-priority", tasks = bundle().tasks.map {
            if (it.id == "two") it.copy(priority = 5, fields = listOf(ConfigurationField("amount", "测试值", FieldWidget.NUMBER, true))) else it
        })
        val text = BundleCodec(factory.contracts).write(definition)
        assertEquals(VersionChange.Applied, library.install(text, definition.version, TaskVersionLibrary.sha256(text)))
        assertEquals(VersionChange.Applied, library.activate(definition.version))
        val configurations = TaskConfigurationRepository(database, library)
        val high = TaskAddress("fixture", "two")
        assertTrue(configurations.save(configurations.open(high), buildJsonObject { put("amount", 1) }) is ConfigurationSaveResult.Saved)
        val started = CompletableDeferred<StepAttempt>(); val interrupt = CompletableDeferred<Unit>()
        val hintReturned = CompletableDeferred<Unit>(); val admissionEntered = CompletableDeferred<Unit>()
        val admissionRelease = CompletableDeferred<Unit>(); val count = AtomicInteger()
        lateinit var host: ExecutionRuntime
        val port = counting(count, { attempt ->
            if (count.get() == 1) {
                started.complete(attempt); interrupt.await()
                assertFalse(host.permitsInput(attempt.ticket))
                hintReturned.complete(Unit); StepOutcome.Interrupted
            } else awaitCancellation()
        })
        val gated = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.admittedRunId != null && prepared.snapshot.waiting.any { it.request.ref.id == "two" }) {
                    admissionEntered.complete(Unit); admissionRelease.await()
                }
                return store.persist(expectedRevision, prepared)
            }
        }
        host = runtime(port, gated)
        try {
            add(host, "one"); val original = withTimeout(5_000) { started.await() }
            val receipt = host.enqueueTask(high)
            withTimeout(5_000) { admissionEntered.await() }
            interrupt.complete(Unit); withTimeout(5_000) { hintReturned.await() }
            // The candidate was prepared from value 1; the real final transaction must reject it.
            assertTrue(configurations.save(configurations.open(high), buildJsonObject { put("amount", 2) }) is ConfigurationSaveResult.Saved)
            admissionRelease.complete(Unit)
            assertTrue(withTimeout(5_000) { receipt.await() } is RuntimeCommandResult.Rejected)
            val state = wait(host) { it.activeTicket != original.ticket && it.facts.any { fact -> fact.type == "step-reentered" } }
            withTimeout(5_000) { while (count.get() != 2) delay(5) }
            assertEquals("one", state.current!!.task.id); assertEquals(original.run.runId, state.current!!.runId)
            assertTrue(state.waiting.isEmpty()); assertTrue(state.unresolved.isEmpty()); assertFalse(state.manualHold)
            assertFalse(state.facts.any { it.taskId == "two" || it.type == "task-completed" || it.type == "task-failed" })
            assertEquals(1, library.snapshot().versions.single { it.version == definition.version }.referenceCount)
        } finally { interrupt.complete(Unit); admissionRelease.complete(Unit) }
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
    @Test fun aRejectedPreemptionRetainsOnlyTheCommittedRunsInputPermit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime(counting(AtomicInteger(), { entered.complete(Unit); awaitCancellation() }))
        add(runtime, "one"); withTimeout(5_000) { entered.await() }
        val ticket = runtime.state.value.activeTicket!!
        assertTrue(runtime.permitsInput(ticket))
        val saved = store.load()
        val task = bundle().tasks.last()
        // Core sees a higher priority; the real store rejects its mismatch with the locked content.
        val result = runtime.submit(ExecutorCommand.Enqueue("invalid-priority", ExecutionRequest(
            SubmissionRef(SubmissionKind.TASK, task.id), identity, "v1",
            listOf(TaskSnapshot(task.id, task.name, 10, listOf("wait")))))).await()
        assertTrue(result is RuntimeCommandResult.Rejected)
        assertEquals(ticket, runtime.state.value.activeTicket)
        assertTrue(runtime.permitsInput(ticket))
        assertEquals(saved, store.load())
    }
    @Test fun aFailedControlCommitClosesInputAndPreservesThePreviousDurableState() = runBlocking {
        val port = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.snapshot.manualHold) error("fixture: disk unavailable")
                return store.persist(expectedRevision, prepared)
            }
        }
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime(counting(AtomicInteger(), { entered.complete(Unit); awaitCancellation() }), port)
        add(runtime, "one"); withTimeout(5_000) { entered.await() }
        val ticket = runtime.state.value.activeTicket!!
        val before = store.load()
        assertTrue(runtime.submit(ExecutorCommand.Pause).await() is RuntimeCommandResult.Unconfirmed)
        assertTrue(runtime.status.value is RuntimeStatus.Failure)
        assertFalse(runtime.permitsInput(ticket))
        assertEquals(before, store.load())
    }
    @Test fun aCorruptPinnedVersionIsAStorageFaultAndCannotReopenInput() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime(counting(AtomicInteger(), { entered.complete(Unit); awaitCancellation() }))
        add(runtime, "one"); withTimeout(5_000) { entered.await() }
        val ticket = runtime.state.value.activeTicket!!
        val before = store.load()
        database.openHelper.writableDatabase.execSQL("UPDATE task_versions SET digest = 'broken' WHERE version = 'v1'")
        assertTrue("Corrupt execution dependencies are not ordinary rejected user commands",
            runtime.submit(ExecutorCommand.Pause).await() is RuntimeCommandResult.Unconfirmed)
        assertTrue(runtime.status.value is RuntimeStatus.Failure)
        assertFalse(runtime.permitsInput(ticket))
        assertEquals(before, store.load())
        assertEquals("broken", database.taskVersions().version("v1")!!.digest)
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
