package io.github.yysassist.device

import io.github.yysassist.StepStartCondition
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Dependency policy only; physical focus/power/connection have separate device tests. */
class DeviceExecutionReadinessTest {
    private val contracts = listOf(
        CapabilityContract("fixture.utility", 1, validateInputs = { null }),
        CapabilityContract("fixture.read", 1, role = ActionRole.OBSERVATION, validateInputs = { null }),
        CapabilityContract("fixture.input", 1, sendsDeviceInput = true, validateInputs = { null }),
    )
    private fun fixture(capability: String): Pair<StepAttempt, TaskBundle> {
        val context = ExecutionContext("fixture", Principal.Anonymous)
        val task = TaskDefinition("one", "fixture", "测试", "tools", 1, steps = listOf(
            StepDefinition("first", "首步", listOf(ActionDefinition("action", capability, 1, JsonObject(emptyMap()))))))
        val target = TargetProfile("fixture", "测试应用", listOf("fixture.primary", "fixture.channel"), IdentityPolicy.NONE,
            listOf(TaskCategory("tools", "工具")))
        val bundle = TaskBundle(1, "frozen-v1", "2026-11-17T00:00:00Z", "Fixture", listOf(task), listOf(target))
        val executor = TaskExecutor(context)
        val result = executor.enqueue("fixture", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), context, bundle.version,
            listOf(TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }))))
        return (result.effects.single() as ExecutorEffect.ExecuteStep).attempt to bundle
    }
    @Test fun utilityStepsDoNotDependOnAccessibilityWhileObservationAndInputUseTheFrozenTargets(): Unit = runBlocking {
        var checks = 0
        val readiness = DeviceExecutionReadiness(contracts, availability = { packages ->
            checks++; assertEquals(setOf("fixture.primary", "fixture.channel"), packages); DeviceAvailability.DISCONNECTED
        }, changes = emptyFlow())
        fixture("fixture.utility").let { (attempt, bundle) -> assertEquals(StepStartCondition.Ready, readiness.check(attempt, bundle)) }
        assertEquals(0, checks)
        for (id in listOf("fixture.read", "fixture.input")) {
            fixture(id).let { (attempt, bundle) -> assertTrue(readiness.check(attempt, bundle) is StepStartCondition.Waiting) }
        }
        assertEquals(2, checks)
    }
    @Test fun anUnconfirmedPlatformLookupDoesNotPretendTheTargetIsKnownToBeInTheBackground(): Unit = runBlocking {
        val readiness = DeviceExecutionReadiness(contracts, availability = { DeviceAvailability.UNCONFIRMED }, changes = emptyFlow())
        fixture("fixture.read").let { (attempt, bundle) ->
            val result = readiness.check(attempt, bundle)
            assertTrue(result is StepStartCondition.Unconfirmed)
            assertTrue((result as StepStartCondition.Unconfirmed).message.contains("暂无法确认"))
        }
    }
    @Test fun nestedAndZeroCountInputStillRequireTheFrozenDeviceBeforeWorkConstruction(): Unit = runBlocking {
        var checks = 0
        val readiness = DeviceExecutionReadiness(contracts, availability = { checks++; DeviceAvailability.DISCONNECTED }, changes = emptyFlow())
        val (attempt, original) = fixture("fixture.input")
        val value = original.copy(tasks = original.tasks.map { task -> task.copy(steps = task.steps.map { step ->
            step.copy(actions = emptyList(), program = listOf(ProgramNode.Repeat(JsonPrimitive(0),
                listOf(ProgramNode.Action(step.actions.single())))))
        }) })
        assertTrue(readiness.check(attempt, value) is StepStartCondition.Waiting)
        assertEquals(1, checks)
    }
}
