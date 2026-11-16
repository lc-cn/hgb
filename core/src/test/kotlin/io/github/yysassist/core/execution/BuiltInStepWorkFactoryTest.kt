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
        val contract = factory.contracts.single()
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
}
