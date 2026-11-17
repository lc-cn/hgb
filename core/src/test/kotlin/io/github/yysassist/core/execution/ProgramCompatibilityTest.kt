package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Uses only pre-existing public entry points so the same test can run against the old core. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgramCompatibilityTest {
    @Test fun handWrittenProgramLoadsAndRunsThroughTheExistingFactory() = runTest {
        val factory = BuiltInStepWorkFactory()
        val text = checkNotNull(javaClass.getResource("/task-bundle-program.json")).readText()
        val read = BundleCodec(factory.contracts).read(text)
        assertTrue(read.toString(), read is BundleReadResult.Valid)
        val bundle = (read as BundleReadResult.Valid).bundle
        val task = bundle.tasks.single()
        val snapshot = TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, "{\"count\":3}")
        val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), ExecutionContext(task.targetId, Principal.Anonymous),
            bundle.version, listOf(snapshot))
        val attempt = StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, request, task.priority))
        assertTrue(factory.canStartWithoutObservation(request, bundle))
        assertTrue(factory.restartHasNoDeviceInput(attempt, bundle))
        assertTrue(factory.create(attempt, bundle).execute() is StepOutcome.Completed)
        assertEquals(5L, currentTime)
    }
}
