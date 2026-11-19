package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyActionInterpreterTest {
    private val read = ActionDefinition("read", "fixture.read", 1, JsonObject(emptyMap()))
    private val verify = ActionDefinition("verify", "fixture.verify", 1, JsonObject(emptyMap()),
        mapOf("seen" to ActionInputBinding.Output("read", "seen")))
    private val task = TaskDefinition("read", "fixture", "只读核对", "tools", 1,
        steps = listOf(StepDefinition("step", "核对", listOf(read, verify))))
    private val bundle = TaskBundle(1, "v1", "2026-11-16T00:00:00Z", "", listOf(task),
        listOf(TargetProfile("fixture", "夹具", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))))
    private val attempt = StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id),
        ExecutionContext("fixture", Principal.Anonymous), "v1", listOf(TaskSnapshot(task.id, task.name, 1, listOf("step")))), 1))
    private fun provider(run: suspend (ActionDefinition, JsonObject) -> ActionResult) = object : ActionProvider {
        override val contracts = listOf(
            CapabilityContract("fixture.read", 1, outputTypes = mapOf("seen" to ActionValueType.BOOLEAN), role = ActionRole.OBSERVATION) {
                if (it.isEmpty()) null else "Unexpected read arguments"
            },
            CapabilityContract("fixture.verify", 1, inputTypes = mapOf("seen" to ActionValueType.BOOLEAN),
                outputTypes = mapOf("matched" to ActionValueType.BOOLEAN), role = ActionRole.VERIFICATION) { null })
        override fun open(attempt: StepAttempt, bundle: TaskBundle) = ActionSession { action, inputs -> run(action, inputs) }
    }
    private val seen = buildJsonObject { put("seen", true) }
    private val matched = buildJsonObject { put("matched", true) }

    @Test fun actualOutputReachesVerificationAndItsEvidenceCompletesTheStep() = runTest {
        val factory = BuiltInStepWorkFactory(provider { action, inputs ->
            if (action.id == "read") ActionResult.Produced(seen)
            else { assertEquals(seen, inputs); ActionResult.Verified(matched, "本次观察已核对") }
        })
        assertFalse(factory.canStartWithoutObservation(attempt.run.request, bundle))
        assertTrue(factory.restartHasNoDeviceInput(attempt, bundle))
        assertEquals(StepOutcome.Completed("本次观察已核对"), factory.create(attempt, bundle).execute())
    }

    @Test fun observationWithoutFinalVerificationCannotLoad() {
        val factory = BuiltInStepWorkFactory(provider { _, _ -> error("Never run") })
        val codec = BundleCodec(factory.contracts)
        val invalid = bundle.copy(tasks = listOf(task.copy(steps = listOf(task.steps.single().copy(actions = listOf(read))))))
        assertTrue(codec.validate(invalid).any { it.reason.contains("核对动作") })
        assertFalse(factory.restartHasNoDeviceInput(attempt, invalid))
    }

    @Test fun missingObservationStaysUnknownAndDoesNotRunVerification() = runTest {
        var calls = 0
        val factory = BuiltInStepWorkFactory(provider { _, _ -> calls++; ActionResult.Unavailable("未取得观察：进程中断") })
        assertEquals(StepOutcome.Unknown("未取得观察：进程中断"), factory.create(attempt, bundle).execute())
        assertEquals(1, calls)
    }
    @Test fun authorityInterruptionDoesNotBecomeUnknownOrRunAnotherAction() = runTest {
        var calls = 0
        val factory = BuiltInStepWorkFactory(provider { _, _ -> calls++; ActionResult.Interrupted })
        assertEquals(StepOutcome.Interrupted, factory.create(attempt, bundle).execute())
        assertEquals(1, calls)
    }

    @Test fun aVerificationLabelOrMalformedOutputCannotFabricateProof() = runTest {
        for (mode in 0..1) {
            val factory = BuiltInStepWorkFactory(provider { action, _ ->
                if (action.id == "read") ActionResult.Produced(if (mode == 0) JsonObject(emptyMap()) else seen)
                else ActionResult.Produced(matched) // No verified evidence.
            })
            val result = runCatching { factory.create(attempt, bundle).execute() }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun revocationWaitsForIndependentForeignQuietBeforeCompletingTheAttempt() = runTest {
        val entered = CompletableDeferred<Unit>(); val foreignQuiet = CompletableDeferred<Unit>()
        val factory = BuiltInStepWorkFactory(provider { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { foreignQuiet.await() }
            ActionResult.Produced(seen)
        })
        val work = factory.create(attempt, bundle)
        val execution = launch { work.execute() }; runCurrent(); entered.await()
        val drain = async { work.revoke() }; runCurrent()
        assertFalse(drain.isCompleted); assertFalse(execution.isCompleted)
        foreignQuiet.complete(Unit); runCurrent()
        assertEquals(InputStopObservation.Quiet(StepOutcome.Interrupted), drain.await())
        assertTrue(execution.isCancelled)
    }

    @Test fun revokeBeforeExecuteDoesNotOpenASessionAndCompletedScopeCannotCancelItsCaller() = runTest {
        var calls = 0
        val factory = BuiltInStepWorkFactory(provider { action, _ ->
            calls++
            if (action.id == "read") ActionResult.Produced(seen) else ActionResult.Verified(matched, "核对完成")
        })
        val unused = factory.create(attempt, bundle)
        assertEquals(InputStopObservation.Quiet(StepOutcome.Interrupted), unused.revoke())
        assertTrue(runCatching { unused.execute() }.isFailure); assertEquals(0, calls)
        val actual = factory.create(attempt, bundle)
        assertTrue(actual.execute() is StepOutcome.Completed)
        actual.revoke()
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun inputProgramsCannotClaimNoInputRecoveryOrDefaultQuiet() = runTest {
        val input = read.copy(capability = "fixture.input")
        val content = bundle.copy(tasks = listOf(task.copy(steps = listOf(task.steps.single().copy(actions = listOf(input, verify))))))
        val port = object : ActionProvider {
            override val contracts = listOf(
                CapabilityContract("fixture.input", 1, outputTypes = mapOf("seen" to ActionValueType.BOOLEAN), sendsDeviceInput = true) { null },
                provider { _, _ -> error("never") }.contracts.last())
            override fun open(attempt: StepAttempt, bundle: TaskBundle) = ActionSession { action, _ ->
                if (action.id == "read") ActionResult.Produced(seen) else ActionResult.Verified(matched, "业务证据")
            }
        }
        val factory = BuiltInStepWorkFactory(port)
        assertFalse(factory.restartHasNoDeviceInput(attempt, content))
        assertTrue(factory.canReenterAfterQuiet(attempt, content)) // Only meaningful with independent stop evidence.
        val work = factory.create(attempt, content)
        assertTrue(work.execute() is StepOutcome.Completed)
        assertTrue(work.revoke() is InputStopObservation.Unconfirmed)
        val missingProof = content.copy(tasks = listOf(task.copy(steps = listOf(task.steps.single().copy(actions = listOf(input))))))
        assertTrue(BundleCodec(factory.contracts).validate(missingProof).isNotEmpty())
    }

    @Test fun inputRevocationNeedsItsOwnQuietEvenAfterTheWorkerScopeHasDrained() = runTest {
        val input = read.copy(capability = "fixture.input")
        val content = bundle.copy(tasks = listOf(task.copy(steps = listOf(task.steps.single().copy(actions = listOf(input, verify))))))
        val entered = CompletableDeferred<Unit>(); val platformQuiet = CompletableDeferred<Unit>()
        val port = object : ActionProvider {
            override val contracts = listOf(
                CapabilityContract("fixture.input", 1, outputTypes = mapOf("seen" to ActionValueType.BOOLEAN), sendsDeviceInput = true) { null },
                provider { _, _ -> error("never") }.contracts.last())
            override fun open(attempt: StepAttempt, bundle: TaskBundle) = object : ActionSession {
                override suspend fun perform(action: ActionDefinition, inputs: JsonObject): ActionResult { entered.complete(Unit); awaitCancellation() }
                override suspend fun revoke(): InputStopObservation {
                    platformQuiet.await(); return InputStopObservation.Quiet(StepOutcome.Unknown("实际输入结果待核对"))
                }
            }
        }
        val work = BuiltInStepWorkFactory(port).create(attempt, content)
        val execute = launch { work.execute() }; runCurrent(); entered.await()
        val stop = async { work.revoke() }; runCurrent()
        assertTrue(execute.isCancelled); assertFalse(stop.isCompleted)
        platformQuiet.complete(Unit)
        assertEquals(InputStopObservation.Quiet(StepOutcome.Unknown("实际输入结果待核对")), stop.await())
    }
}
