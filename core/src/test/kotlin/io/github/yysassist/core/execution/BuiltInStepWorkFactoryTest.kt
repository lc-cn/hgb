package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInStepWorkFactoryTest {
    private val factory = BuiltInStepWorkFactory()
    private val action = ActionDefinition("delay", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(1000))))
    private val task = TaskDefinition("wait", "fixture", "Wait", "tools", 1, steps = listOf(StepDefinition("step", "Step", listOf(action))))
    private val bundle = TaskBundle(1, "v1", "2026-11-16T00:00:00Z", "", listOf(task),
        listOf(TargetProfile("fixture", "Fixture", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")))))
    private val attempt = StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "wait"),
        ExecutionContext("fixture", Principal.Anonymous), "v1", listOf(TaskSnapshot("wait", "Wait", 1, listOf("step")))), 1))
    @Test fun waitIsARealCancellableDurationAndRevocationHasNoDeviceInputToDrain() = runTest {
        val work = factory.create(attempt, bundle)
        val running = async { work.execute() }
        runCurrent(); advanceTimeBy(999)
        assertFalse(running.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertTrue(running.await() is StepOutcome.Completed)
        assertEquals(InputStopObservation.Quiet(StepOutcome.Interrupted), work.revoke())
        val second = launch { factory.create(attempt, bundle).execute() }
        runCurrent(); second.cancelAndJoin()
        assertTrue(second.isCancelled)
    }
    @Test fun contractRejectsUnknownInputsAndUnsupportedActionsNeverQualifyForSafeRecovery() {
        val contract = factory.contracts.single { it.id == "control.wait" }
        assertNotNull(contract.validateInputs(JsonObject(mapOf("durationMs" to JsonPrimitive("1000")))))
        assertNotNull(contract.validateInputs(JsonObject(mapOf("durationMs" to JsonPrimitive(-1)))))
        assertNotNull(contract.validateInputs(JsonObject(mapOf("durationMs" to JsonPrimitive(1), "script" to JsonPrimitive("anything")))))
        val unknown = bundle.copy(tasks = listOf(task.copy(steps = listOf(StepDefinition("step", "Step", listOf(action.copy(capability = "device.tap")))))))
        assertFalse(factory.restartHasNoDeviceInput(attempt, unknown))
        assertTrue(factory.restartHasNoDeviceInput(attempt, bundle))
    }
    @Test fun screenIndependentAdmissionRequiresTheWholeKnownProgramAndMatchingVersion() {
        val request = attempt.run.request
        assertTrue(factory.canStartWithoutObservation(request, bundle))
        assertFalse(factory.canStartWithoutObservation(request.copy(packageVersion = "other"), bundle))
        val unknown = bundle.copy(tasks = listOf(task.copy(steps = listOf(StepDefinition("step", "Step", listOf(action.copy(capability = "device.tap")))))))
        assertFalse(factory.canStartWithoutObservation(request, unknown))
        assertFalse(factory.canStartWithoutObservation(request.copy(tasks = listOf(request.tasks.single().copy(stepIds = listOf("missing")))), bundle))
    }

    private fun bound(): Pair<StepAttempt, TaskBundle> {
        val actions = listOf(action.copy(id = "first", inputs = JsonObject(emptyMap()), bindings =
            mapOf("durationMs" to ActionInputBinding.Configuration("milliseconds"))),
            action.copy(id = "second", inputs = JsonObject(emptyMap()), bindings =
                mapOf("durationMs" to ActionInputBinding.Output("first", "durationMs"))))
        val configured = task.copy(fields = listOf(ConfigurationField("milliseconds", "等待时长", FieldWidget.NUMBER, true)),
            steps = listOf(task.steps.single().copy(actions = actions)))
        val request = attempt.run.request.copy(tasks = listOf(attempt.run.task.copy(configurationJson = "{\"milliseconds\":120}")))
        return attempt.copy(run = attempt.run.copy(request = request)) to bundle.copy(tasks = listOf(configured))
    }

    @Test fun frozenConfigurationAndPriorOutputDriveTwoRealDurations() = runTest {
        val (bound, content) = bound()
        assertTrue(factory.canStartWithoutObservation(bound.run.request, content))
        val work = factory.create(bound, content)
        val running = async { work.execute() }
        runCurrent(); advanceTimeBy(119); runCurrent(); assertFalse(running.isCompleted)
        advanceTimeBy(1); runCurrent(); assertFalse(running.isCompleted)
        advanceTimeBy(119); runCurrent(); assertFalse(running.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(running.await() is StepOutcome.Completed)
    }

    @Test fun cancellationReentersTheWholeUnfinishedStepWithoutAnOutputCheckpoint() = runTest {
        val (bound, content) = bound(); val work = factory.create(bound, content)
        val firstRun = launch { work.execute() }
        runCurrent(); advanceTimeBy(180); runCurrent(); firstRun.cancelAndJoin()
        assertEquals(InputStopObservation.Quiet(StepOutcome.Interrupted), work.revoke())
        // Runtime creates a fresh work/session for each resumed step attempt.
        val resumed = async { factory.create(bound, content).execute() }
        runCurrent(); advanceTimeBy(239); runCurrent(); assertFalse(resumed.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(resumed.await() is StepOutcome.Completed)
        val invalid = bound.copy(run = bound.run.copy(request = bound.run.request.copy(tasks =
            listOf(bound.run.task.copy(configurationJson = "{\"milliseconds\":-1}")))))
        assertFalse(factory.canStartWithoutObservation(invalid.run.request, content))
        assertFalse(factory.restartHasNoDeviceInput(invalid, content))
        assertThrows(IllegalArgumentException::class.java) { factory.create(invalid, content) }
    }

    @Test fun completeHandAuthoredTaskBundleRunsThroughTheProductionWaitInterpreter() = runTest {
        val text = checkNotNull(javaClass.getResource("/task-bundle-bindings.json")).readText()
        val content = (BundleCodec(factory.contracts).read(text) as BundleReadResult.Valid).bundle
        val task = content.tasks.single()
        val snapshot = TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, "{\"milliseconds\":5.00}")
        val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), ExecutionContext(task.targetId, Principal.Anonymous),
            content.version, listOf(snapshot))
        val current = StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, request, task.priority))
        assertTrue(factory.create(current, content).execute() is StepOutcome.Completed)
        assertEquals(10L, currentTime)
    }
}
