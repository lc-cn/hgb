package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepProgramTest {
    private fun wait(id: String = "wait", duration: Long = 10) = ProgramNode.Action(ActionDefinition(id, "control.wait", 1,
        buildJsonObject { put("durationMs", duration) }))
    private fun bundle(nodes: List<ProgramNode>, fields: List<ConfigurationField> = emptyList()) = TaskBundle(1, "v1", "now", "fixture",
        listOf(TaskDefinition("task", "fixture", "Task", "tools", 1, fields, listOf(StepDefinition("step", "Step", program = nodes)))),
        listOf(TargetProfile("fixture", "Fixture", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")))))
    private fun attempt(bundle: TaskBundle, config: String = "{}"): StepAttempt {
        val task = bundle.tasks.single()
        val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), ExecutionContext(task.targetId, Principal.Anonymous),
            bundle.version, listOf(TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, config)))
        return StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, request, 1))
    }
    private val factory = BuiltInStepWorkFactory()
    private val codec = BundleCodec(factory.contracts)
    private val number = ActionInputBinding.Output("first", "durationMs")
    private fun select(vararg cases: ProgramCase) = ProgramNode.Branch(number, cases.toList())

    @Test fun branchValuesAreTypedExactNumbersAndUnknownValuesDoNotFallThrough() = runTest {
        assertTrue(sameProgramValue(JsonPrimitive(2), Json.parseToJsonElement("2.00")))
        assertFalse(sameProgramValue(JsonPrimitive(2), JsonPrimitive("2")))
        assertFalse(sameProgramValue(JsonNull, JsonNull))
        val task = bundle(listOf(wait("first", 2), select(ProgramCase(JsonPrimitive(3), listOf(wait("unselected"))))))
        assertTrue(codec.validate(task).isEmpty())
        assertTrue(factory.create(attempt(task), task).execute() is StepOutcome.Unknown)
        assertEquals(2L, currentTime)
        val duplicate = bundle(listOf(wait("first", 2), select(ProgramCase(JsonPrimitive(2), emptyList()),
            ProgramCase(Json.parseToJsonElement("2.0").jsonPrimitive, emptyList()))))
        assertTrue(codec.validate(duplicate).any { it.reason.contains("重复") })
        val wrongType = bundle(listOf(wait("first", 2), select(ProgramCase(JsonPrimitive("2"), emptyList()))))
        assertTrue(codec.validate(wrongType).any { it.reason.contains("类型") })
    }
    @Test fun booleanOutputSelectsOnlyTheMatchingCaseThroughTheFactory() = runTest {
        val flag = ActionDefinition("flag", "fixture.flag", 1, JsonObject(emptyMap()))
        val provider = object : ActionProvider {
            override val contracts = listOf(CapabilityContract("fixture.flag", 1,
                outputTypes = mapOf("value" to ActionValueType.BOOLEAN), validateInputs = { null }))
            override fun open(attempt: StepAttempt, bundle: TaskBundle) = ActionSession { _, _ ->
                ActionResult.Produced(buildJsonObject { put("value", true) })
            }
        }
        val value = bundle(listOf(ProgramNode.Action(flag), ProgramNode.Branch(ActionInputBinding.Output("flag", "value"), listOf(
            ProgramCase(JsonPrimitive(true), listOf(wait("yes", 1))), ProgramCase(JsonPrimitive(false), listOf(wait("no", 5)))))))
        assertTrue(BuiltInStepWorkFactory(provider).create(attempt(value), value).execute() is StepOutcome.Completed)
        assertEquals(1L, currentTime)
    }

    @Test fun allCasesAndZeroCountBodiesAreValidatedAndIdsAreStepWide() {
        val bad = wait("bad").let { it.copy(action = it.action.copy(capability = "unknown")) }
        for (nodes in listOf(
            listOf(wait("first", 2), select(ProgramCase(JsonPrimitive(2), listOf(wait())), ProgramCase(JsonPrimitive(3), listOf(bad)))),
            listOf(ProgramNode.Repeat(JsonPrimitive(0), listOf(bad))),
        )) assertTrue(codec.validate(bundle(nodes)).any { it.reason.contains("不支持") })
        val duplicate = bundle(listOf(wait("first", 2), select(ProgramCase(JsonPrimitive(2), listOf(wait("same"))),
            ProgramCase(JsonPrimitive(3), listOf(wait("same"))))))
        assertTrue(codec.validate(duplicate).any { it.reason.contains("身份重复") })
    }

    @Test fun childOutputsCannotEscapeAndIterationCannotReadItsPreviousLocals() {
        fun bound(id: String, source: String) = ProgramNode.Action(ActionDefinition(id, "control.wait", 1, JsonObject(emptyMap()),
            mapOf("durationMs" to ActionInputBinding.Output(source, "durationMs"))))
        val branch = bundle(listOf(wait("first", 2), select(ProgramCase(JsonPrimitive(2), listOf(wait("local")))), bound("after", "local")))
        val repeat = bundle(listOf(ProgramNode.Repeat(JsonPrimitive(2), listOf(wait("local"))), bound("after", "local")))
        val previousIteration = bundle(listOf(ProgramNode.Repeat(JsonPrimitive(2), listOf(bound("before", "local"), wait("local")))))
        for (value in listOf(branch, repeat, previousIteration)) assertTrue(codec.validate(value).any { it.reason.contains("前序") })
    }

    @Test fun repeatCountIsExplicitExactAndValidatedAtConfigurationSave() {
        val field = ConfigurationField("count", "Count", FieldWidget.NUMBER, true)
        val value = bundle(listOf(ProgramNode.Repeat(buildJsonObject { put("type", "configuration"); put("fieldId", "count") }, listOf(wait()))), listOf(field))
        assertTrue(codec.validate(value).isEmpty())
        for (bad in listOf("-1", "1.5", "9223372036854775808", "\"3\"", "null")) {
            assertTrue(bad, codec.configurationIssues(value.tasks.single(), Json.parseToJsonElement("{\"count\":$bad}").jsonObject).isNotEmpty())
        }
        assertTrue(codec.configurationIssues(value.tasks.single(), Json.parseToJsonElement("{\"count\":3.00}").jsonObject).isEmpty())
        for (bad in listOf("-1", "0.5", "9223372036854775808", "\"3\"", "null", "{\"type\":\"output\",\"actionId\":\"x\",\"field\":\"n\"}"))
            assertTrue(codec.validate(bundle(listOf(ProgramNode.Repeat(Json.parseToJsonElement(bad), emptyList())))).isNotEmpty())
    }

    @Test fun zeroDoesNoWorkAndPauseReentersTheEntireCountedBlock() = runTest {
        val zero = bundle(listOf(ProgramNode.Repeat(JsonPrimitive(0), listOf(wait()))))
        assertTrue(factory.create(attempt(zero), zero).execute() is StepOutcome.Completed)
        assertEquals(0L, currentTime)
        val value = bundle(listOf(ProgramNode.Repeat(JsonPrimitive(3), listOf(wait(duration = 30)))))
        val work = factory.create(attempt(value), value); val job = launch { work.execute() }
        runCurrent(); advanceTimeBy(45); job.cancelAndJoin()
        assertEquals(InputStopObservation.Quiet(StepOutcome.Interrupted), work.revoke())
        val restarted = async { factory.create(attempt(value), value).execute() }
        runCurrent(); advanceTimeBy(89); runCurrent(); assertFalse(restarted.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(restarted.await() is StepOutcome.Completed)
    }

    @Test fun enormousEmptyCountRemainsCancellableWithoutUnrollingAtValidation() = runTest {
        val value = bundle(listOf(ProgramNode.Repeat(JsonPrimitive(Long.MAX_VALUE), emptyList())))
        val opened = CompletableDeferred<Unit>()
        val provider = object : ActionProvider {
            override val contracts = emptyList<CapabilityContract>()
            override fun open(attempt: StepAttempt, bundle: TaskBundle): ActionSession {
                opened.complete(Unit); return ActionSession { _, _ -> error("No actions") }
            }
        }
        val local = BuiltInStepWorkFactory(provider); val work = local.create(attempt(value), value)
        val job = launch(Dispatchers.Default) { work.execute() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { opened.await(); work.revoke(); job.join() } }
        assertTrue(job.isCancelled)
    }

    @Test fun legacyEncodingStaysUnchangedAndMixedRepresentationsNeverLoad() {
        val legacyText = checkNotNull(javaClass.getResource("/task-bundle-bindings.json")).readText()
        val legacy = (codec.read(legacyText) as BundleReadResult.Valid).bundle
        val encoded = codec.write(legacy)
        assertFalse(encoded.contains("\"program\""))
        assertEquals(BundleReadResult.Valid(legacy), codec.read(encoded))
        val value = bundle(listOf(wait()))
        assertTrue(codec.read(codec.write(value).replace("\"program\":", "\"actions\":[],\"program\":")) is BundleReadResult.Invalid)
        assertTrue(codec.validate(value.copy(tasks = value.tasks.map { it.copy(steps = listOf(it.steps.single().copy(actions = listOf(wait().action)))) })).isNotEmpty())
    }
    @Test fun finalProofBeforeAnExplicitZeroCountTailSurvivesCancellationInsideTheProvider() = runTest {
        var terminal = false
        var proof: StepOutcome.Completed? = null
        val provider = object : ActionProvider {
            override val contracts = listOf(
                CapabilityContract("fixture.input", 1, sendsDeviceInput = true, validateInputs = { null }),
                CapabilityContract("fixture.proof", 1, role = ActionRole.VERIFICATION, validateInputs = { null }),
            )
            override fun open(attempt: StepAttempt, bundle: TaskBundle) = object : ActionSession {
                override fun prepareAction(action: ActionDefinition, completesStep: Boolean) { terminal = completesStep }
                override suspend fun perform(action: ActionDefinition, inputs: JsonObject): ActionResult {
                    if (action.capability == "fixture.input") return ActionResult.Produced(JsonObject(emptyMap()))
                    if (terminal) proof = StepOutcome.Completed("verified")
                    throw CancellationException("Cancel after provider acquired final proof")
                }
                override suspend fun revoke() = InputStopObservation.Quiet(proof ?: StepOutcome.Unknown("proof not retained"))
            }
        }
        fun node(id: String, capability: String) = ProgramNode.Action(ActionDefinition(id, capability, 1, JsonObject(emptyMap())))
        val value = bundle(listOf(node("input", "fixture.input"), node("proof", "fixture.proof"),
            ProgramNode.Repeat(JsonPrimitive(0), listOf(wait()))))
        val work = BuiltInStepWorkFactory(provider).create(attempt(value), value)
        val execution = launch { work.execute() }; execution.join()
        assertEquals(InputStopObservation.Quiet(StepOutcome.Completed("verified")), work.revoke())
    }
    @Test fun legacyVerificationWithoutObservationDoesNotInventAnObservationRequirement() {
        val proof = ProgramNode.Action(ActionDefinition("proof", "fixture.proof", 1, JsonObject(emptyMap())))
        val contracts = factory.contracts + CapabilityContract("fixture.proof", 1, role = ActionRole.VERIFICATION, validateInputs = { null })
        val value = bundle(listOf(proof, wait()))
        val legacy = value.copy(tasks = value.tasks.map { task -> task.copy(steps = task.steps.map { step ->
            step.copy(actions = step.actionDefinitions(), program = null)
        }) })
        for (task in listOf(value, legacy)) assertTrue(BundleCodec(contracts).validate(task).toString(), BundleCodec(contracts).validate(task).isEmpty())
    }
}
