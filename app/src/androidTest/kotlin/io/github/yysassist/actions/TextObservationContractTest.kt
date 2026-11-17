package io.github.yysassist.actions

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextObservationContractTest {
    private val factory = BuiltInStepWorkFactory(ScreenActions({ error("Static validation must not observe") }, { false }))
    private val codec = BundleCodec(factory.contracts)
    private val read = ActionDefinition("read", ScreenActions.READ, 1, buildJsonObject {
        put("packageName", "test.fixture"); put("detector", "det"); put("recognizer", "rec"); put("dictionary", "keys")
    })
    private val verify = ActionDefinition("verify", ScreenActions.EXPECT, 1,
        buildJsonObject { put("text", "领取物资"); put("minimumConfidence", 0.75) },
        mapOf("observation" to ActionInputBinding.Output("read", "observation")))
    private val bundle = TaskBundle(1, "v1", "2026-11-16T00:00:00Z", "", listOf(TaskDefinition("read", "fixture", "只读核对", "tools", 1,
        steps = listOf(StepDefinition("step", "核对", listOf(read, verify))))),
        listOf(TargetProfile("fixture", "夹具", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))),
        listOf("det", "rec", "keys").map { TaskResource(it, "$it.bin", 1, "a".repeat(64)) })
    private fun actions(read: ActionDefinition = this.read, verify: ActionDefinition = this.verify) = bundle.copy(tasks = bundle.tasks.map {
        it.copy(steps = listOf(it.steps.single().copy(actions = listOf(read, verify))))
    })
    @Test fun completeVersionDefinesModelsPackageThresholdAndObservationProvenance() {
        assertEquals(BundleReadResult.Valid(bundle), codec.read(codec.write(bundle)))
    }
    @Test fun missingModelAndUnrelatedPackageCannotBeEnabled() {
        assertTrue(codec.validate(bundle.copy(resources = emptyList())).isNotEmpty())
        assertTrue(codec.validate(actions(read.copy(inputs = JsonObject(read.inputs + ("packageName" to JsonPrimitive("other.app")))))).isNotEmpty())
    }
    @Test fun aLiteralOrOtherActionCannotInventAnObservationToken() {
        val literal = verify.copy(inputs = JsonObject(verify.inputs + ("observation" to JsonPrimitive("invented"))), bindings = emptyMap())
        assertTrue(codec.validate(actions(verify = literal)).isNotEmpty())
        val invalid = verify.copy(bindings = mapOf("observation" to ActionInputBinding.Output("read", "unknown")))
        assertTrue(codec.validate(actions(verify = invalid)).isNotEmpty())
    }
    @Test fun confidenceIsExplicitAndNonFiniteOutOfRangeOrStringValuesNeverBecomeDefaults() {
        for (value in listOf(JsonPrimitive(-0.1), JsonPrimitive(1.1), JsonPrimitive("0.75"), JsonNull)) {
            val invalid = verify.copy(inputs = JsonObject(verify.inputs + ("minimumConfidence" to value)))
            assertTrue(codec.validate(actions(verify = invalid)).isNotEmpty())
        }
        assertTrue(codec.validate(actions(verify = verify.copy(inputs = JsonObject(verify.inputs - "minimumConfidence")))).isNotEmpty())
    }
    @Test fun confidenceConfigurationIsCheckedBeforeAnObservationOutputExists() {
        val dynamic = verify.copy(inputs = JsonObject(verify.inputs - "minimumConfidence"),
            bindings = verify.bindings + ("minimumConfidence" to ActionInputBinding.Configuration("confidence")))
        val configured = actions(verify = dynamic).copy(tasks = actions(verify = dynamic).tasks.map {
            it.copy(fields = listOf(ConfigurationField("confidence", "置信度", FieldWidget.NUMBER, true)))
        })
        assertTrue(codec.validate(configured).isEmpty())
        val task = configured.tasks.single()
        assertTrue(codec.configurationIssues(task, buildJsonObject { put("confidence", 0.75) }).isEmpty())
        val bad = codec.configurationIssues(task, buildJsonObject { put("confidence", -1) })
        assertEquals("confidence", bad.single().location)
    }
    private val locate = ActionDefinition("locate", ScreenActions.LOCATE, 1, verify.inputs,
        mapOf("observation" to ActionInputBinding.Output("read", "observation")))
    private val tap = ActionDefinition("tap", ScreenActions.TAP, 1, buildJsonObject { put("durationMs", 80) },
        mapOf("target" to ActionInputBinding.Output("locate", "target")))
    private val after = read.copy(id = "after")
    private val finalProof = verify.copy(bindings = mapOf("observation" to ActionInputBinding.Output("after", "observation")))
    private fun input(actions: List<ActionDefinition> = listOf(read, locate, tap, after, finalProof)) = bundle.copy(tasks = bundle.tasks.map {
        it.copy(steps = listOf(it.steps.single().copy(actions = actions)))
    })
    @Test fun semanticInputHasTypedProvenanceAndNeedsPostInputObservationAndVerification() {
        assertTrue(codec.validate(input()).toString(), codec.validate(input()).isEmpty())
        assertTrue(factory.contracts.single { it.id == ScreenActions.TAP }.sendsDeviceInput)
        assertTrue(codec.validate(input(listOf(read, locate, tap))).isNotEmpty())
        assertTrue(codec.validate(input(listOf(read, locate, tap, verify))).isNotEmpty())
    }
    @Test fun literalsAndTargetsCreatedBeforeAnEarlierInputCannotBeClicked() {
        val invented = tap.copy(inputs = JsonObject(tap.inputs + ("target" to JsonPrimitive("invented"))), bindings = emptyMap())
        assertTrue(codec.validate(input(listOf(read, locate, invented, after, finalProof))).isNotEmpty())
        assertTrue(codec.validate(input(listOf(read, locate, tap, tap.copy(id = "second-tap"), after, finalProof))).isNotEmpty())
        assertTrue(codec.validate(input(listOf(read, locate, tap, locate.copy(id = "stale-locate"), after, finalProof))).isNotEmpty())
    }
    @Test fun gestureDurationIsExplicitExactAndCheckedWhileItsTargetIsStillUnresolved() {
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("80"), JsonPrimitive(Long.MAX_VALUE), JsonNull)) {
            val bad = tap.copy(inputs = JsonObject(tap.inputs + ("durationMs" to value)))
            assertTrue(codec.validate(input(listOf(read, locate, bad, after, finalProof))).isNotEmpty())
        }
        val exact = tap.copy(inputs = JsonObject(mapOf("durationMs" to Json.parseToJsonElement("80.00"))))
        assertTrue(codec.validate(input(listOf(read, locate, exact, after, finalProof))).isEmpty())
        val bound = tap.copy(inputs = JsonObject(emptyMap()), bindings = tap.bindings + ("durationMs" to ActionInputBinding.Configuration("time")))
        val configured = input(listOf(read, locate, bound, after, finalProof)).let { value -> value.copy(tasks = value.tasks.map {
            it.copy(fields = listOf(ConfigurationField("time", "手势毫秒", FieldWidget.NUMBER, true)))
        }) }
        assertTrue(codec.validate(configured).isEmpty())
        assertEquals("time", codec.configurationIssues(configured.tasks.single(), buildJsonObject { put("time", 0) }).single().location)
    }

    private fun nodes(vararg actions: ActionDefinition) = actions.map(ProgramNode::Action)
    private fun programmed(program: List<ProgramNode>) = bundle.copy(tasks = bundle.tasks.map { task ->
        task.copy(steps = listOf(StepDefinition("step", "核对", program = program)))
    })
    private val classify = ActionDefinition("classify", ScreenActions.CLASSIFY, 1, buildJsonObject {
        put("states", buildJsonArray {
            for ((id, text) in listOf("ready" to "领取物资", "done" to "已领取")) add(buildJsonObject {
                put("id", id); put("text", text); put("minimumConfidence", 0.75)
            })
        })
    }, mapOf("observation" to ActionInputBinding.Output("read", "observation")))
    private fun branch(ready: List<ProgramNode>, done: List<ProgramNode>) = ProgramNode.Branch(ActionInputBinding.Output("classify", "state"),
        listOf(ProgramCase(JsonPrimitive("ready"), ready), ProgramCase(JsonPrimitive("done"), done)))

    @Test fun classifiedBranchChecksAllResourcesPathsAndPostInputProvenance() {
        val valid = nodes(read, classify) + branch(nodes(locate, tap), emptyList()) + nodes(after, finalProof)
        assertTrue(codec.validate(programmed(valid)).toString(), codec.validate(programmed(valid)).isEmpty())
        // A path that clicks invalidates the outer observation even when the other case does not.
        assertTrue(codec.validate(programmed(nodes(read, classify) + branch(nodes(locate, tap), emptyList()) + nodes(verify))).isNotEmpty())
        // A bad resource in either case is checked before the program is enabled.
        assertTrue(codec.validate(programmed(nodes(read, classify) + branch(nodes(verify), nodes(after.copy(inputs =
            JsonObject(after.inputs + ("detector" to JsonPrimitive("missing")))), finalProof)))).isNotEmpty())
        val inside = nodes(read, classify) + branch(nodes(locate, tap, after, finalProof), nodes(verify.copy(id = "already")))
        assertTrue(codec.validate(programmed(inside)).isEmpty())
        assertTrue(codec.validate(programmed(nodes(read, classify) + branch(nodes(locate, tap, after, finalProof), emptyList()))).isNotEmpty())
    }
    @Test fun aLoopCannotReuseOuterObservationAfterItsOwnInputOrTreatZeroAsProof() {
        val repeated = ProgramNode.Repeat(JsonPrimitive(2), nodes(locate, tap, after, finalProof))
        assertTrue(codec.validate(programmed(nodes(read) + repeated)).isNotEmpty())
        assertTrue(codec.validate(programmed(listOf(ProgramNode.Repeat(JsonPrimitive(2), nodes(read, locate, tap, after, finalProof))))).isEmpty())
        assertTrue(codec.validate(programmed(nodes(read) + ProgramNode.Repeat(JsonPrimitive(0), nodes(verify)))).isNotEmpty())
    }
    @Test fun classifyRequiresExplicitUniqueStateDefinitionsAndCurrentObservation() {
        for (states in listOf(JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive("ready"))),
            buildJsonArray { repeat(2) { add(buildJsonObject { put("id", "same"); put("text", "x"); put("minimumConfidence", 0.75) }) } },
            buildJsonArray { add(buildJsonObject { put("id", "one"); put("text", "x"); put("minimumConfidence", "0.75") }) })) {
            val bad = classify.copy(inputs = buildJsonObject { put("states", states) })
            assertTrue(codec.validate(programmed(nodes(read, bad, verify))).isNotEmpty())
        }
        assertTrue(codec.validate(programmed(nodes(read, classify, verify))).isEmpty())
        assertTrue(codec.validate(programmed(nodes(read, locate, tap, classify, after, finalProof))).isNotEmpty())
    }
}
