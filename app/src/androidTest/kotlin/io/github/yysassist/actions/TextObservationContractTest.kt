package io.github.yysassist.actions

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.vision.MaaTextBackend
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextObservationContractTest {
    private val factory = BuiltInStepWorkFactory(ScreenActions({ error("Static validation must not observe") }, { false }))
    private val codec = BundleCodec(factory.contracts)
    private val read = ActionDefinition("read", ScreenActions.READ, 2, buildJsonObject { put("profile", "ocr") })
    private val profile = VisionProfile("ocr", "fixture", "test.fixture", MaaTextBackend.CONTRACT,
        mapOf("detector" to "det", "recognizer" to "rec", "dictionary" to "keys"))
    private val verify = ActionDefinition("verify", ScreenActions.EXPECT, 1,
        buildJsonObject { put("text", "领取物资"); put("minimumConfidence", 0.75) },
        mapOf("observation" to ActionInputBinding.Output("read", "observation")))
    private val bundle = TaskBundle(1, "v1", "2026-11-16T00:00:00Z", "", listOf(TaskDefinition("read", "fixture", "只读核对", "tools", 1,
        steps = listOf(StepDefinition("step", "核对", listOf(read, verify))))),
        listOf(TargetProfile("fixture", "夹具", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))),
        listOf("det", "rec", "keys", "jade").map { TaskResource(it, "$it.bin", 1, "a".repeat(64)) }, listOf(profile))
    private fun actions(read: ActionDefinition = this.read, verify: ActionDefinition = this.verify) = bundle.copy(tasks = bundle.tasks.map {
        it.copy(steps = listOf(it.steps.single().copy(actions = listOf(read, verify))))
    })
    @Test fun completeVersionDefinesModelsPackageThresholdAndObservationProvenance() {
        assertEquals(BundleReadResult.Valid(bundle), codec.read(codec.write(bundle)))
    }
    @Test fun missingModelUnrelatedPackageOrUnknownProfileCannotBeEnabled() {
        assertTrue(codec.validate(bundle.copy(resources = emptyList())).isNotEmpty())
        assertTrue(codec.validate(bundle.copy(visionProfiles = listOf(profile.copy(packageName = "other.app")))).isNotEmpty())
        assertTrue(codec.validate(bundle.copy(visionProfiles = listOf(profile.copy(models = profile.models - "dictionary")))).isNotEmpty())
        assertTrue(codec.validate(bundle.copy(visionProfiles = listOf(profile.copy(recognizer = "other.v1")))).isNotEmpty())
        assertTrue(codec.validate(actions(read.copy(inputs = buildJsonObject { put("profile", "missing") }))).isNotEmpty())
        assertTrue(codec.validate(actions(read.copy(contractVersion = 1))).isNotEmpty())
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
            buildJsonObject { put("profile", "missing") }), finalProof)))).isNotEmpty())
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

    // Region actions: anchor -> region -> region text / image evidence, all from one fresh observation.
    private val derive = ActionDefinition("derive", ScreenActions.DERIVE, 1, buildJsonObject {
        put("dx", -120); put("dy", 195); put("width", 240); put("height", 55); put("referenceWidth", 2400); put("referenceHeight", 1080)
    }, mapOf("target" to ActionInputBinding.Output("locate", "target")))
    private val regionRead = ActionDefinition("regionRead", ScreenActions.READ_REGION, 1, JsonObject(emptyMap()),
        mapOf("region" to ActionInputBinding.Output("derive", "region")))
    private val regionProof = verify.copy(id = "regionProof", bindings = mapOf("observation" to ActionInputBinding.Output("regionRead", "observation")))
    private val match = ActionDefinition("match", ScreenActions.MATCH, 1, buildJsonObject { put("template", "jade"); put("minimumScore", 0.45) },
        mapOf("region" to ActionInputBinding.Output("derive", "region")))
    private fun regionClassify(states: JsonArray) = ActionDefinition("state", ScreenActions.CLASSIFY_REGION, 1, buildJsonObject { put("states", states) },
        mapOf("region" to ActionInputBinding.Output("derive", "region")))
    private val dailyStates = buildJsonArray {
        add(buildJsonObject { put("id", "claimable")
            put("text", buildJsonArray { add(buildJsonObject { put("text", "领取"); put("minimumConfidence", 0.8) }) })
            put("absent", buildJsonArray { add(buildJsonObject { put("template", "jade"); put("minimumScore", 0.45) }) }) })
        add(buildJsonObject { put("id", "paid")
            put("present", buildJsonArray { add(buildJsonObject { put("template", "jade"); put("minimumScore", 0.45) }) }) })
    }
    @Test fun regionEvidenceChainsFromOneFreshObservation() {
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, regionRead, regionProof))).toString(),
            codec.validate(programmed(nodes(read, locate, derive, regionRead, regionProof))).isEmpty())
        val classified = nodes(read, locate, derive, regionClassify(dailyStates)) + ProgramNode.Branch(ActionInputBinding.Output("state", "state"),
            listOf(ProgramCase(JsonPrimitive("claimable"), nodes(locate.copy(id = "button"), tap.copy(bindings = mapOf("target" to
                ActionInputBinding.Output("button", "target"))), after, finalProof)), ProgramCase(JsonPrimitive("paid"), nodes(verify.copy(id = "seen")))))
        assertTrue(codec.validate(programmed(classified)).toString(), codec.validate(programmed(classified)).isEmpty())
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, match, verify))).isEmpty())
    }
    @Test fun regionGeometryAndTemplatesAreExplicitAndDeclared() {
        for ((key, value) in listOf("width" to JsonPrimitive(0), "dx" to JsonPrimitive(1.5), "referenceHeight" to JsonPrimitive("1080"), "dy" to JsonPrimitive(200_000)))
            assertTrue(key, codec.validate(programmed(nodes(read, locate, derive.copy(inputs = JsonObject(derive.inputs + (key to value))), verify))).isNotEmpty())
        val configured = derive.copy(inputs = JsonObject(derive.inputs - "dx"), bindings = derive.bindings + ("dx" to ActionInputBinding.Configuration("dx")))
        assertTrue(codec.validate(programmed(nodes(read, locate, configured, verify))).isNotEmpty())
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, match.copy(inputs = buildJsonObject { put("template", "missing"); put("minimumScore", 0.5) }), verify))).isNotEmpty())
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, match.copy(inputs = buildJsonObject { put("template", "jade"); put("minimumScore", 1.5) }), verify))).isNotEmpty())
    }
    @Test fun regionStatesNeedExplicitConditionsAndDeclaredTemplates() {
        val empty = buildJsonArray { add(buildJsonObject { put("id", "x") }) }
        val duplicate = buildJsonArray { repeat(2) { add(dailyStates[1]) } }
        val missing = buildJsonArray { add(buildJsonObject { put("id", "x")
            put("present", buildJsonArray { add(buildJsonObject { put("template", "missing"); put("minimumScore", 0.5) }) }) }) }
        val unknownKey = buildJsonArray { add(JsonObject(dailyStates[1].jsonObject + ("else" to JsonPrimitive(true)))) }
        for (states in listOf(JsonArray(emptyList()), empty, duplicate, missing, unknownKey))
            assertTrue(states.toString(), codec.validate(programmed(nodes(read, locate, derive, regionClassify(states), verify))).isNotEmpty())
    }
    @Test fun regionHandlesCannotBeInventedReusedAfterInputOrClicked() {
        val invented = regionRead.copy(inputs = buildJsonObject { put("region", "invented") }, bindings = emptyMap())
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, invented, verify))).isNotEmpty())
        // A region from before a click is stale afterwards.
        val stale = nodes(read, locate, derive, tap, after, regionRead, regionProof, finalProof)
        assertTrue(codec.validate(programmed(stale)).any { it.reason.contains("失效") })
        // A target found inside a region cannot be clicked: input re-resolves on a new full frame.
        val inside = locate.copy(id = "inside", bindings = mapOf("observation" to ActionInputBinding.Output("regionRead", "observation")))
        val click = tap.copy(bindings = mapOf("target" to ActionInputBinding.Output("inside", "target")))
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, regionRead, inside, click, after, finalProof))).any { it.reason.contains("来源") })
        // An observation token is not a region.
        val wrong = regionRead.copy(bindings = mapOf("region" to ActionInputBinding.Output("read", "observation")))
        assertTrue(codec.validate(programmed(nodes(read, wrong, verify))).isNotEmpty())
    }

    @Test fun longPressAndSwipeAreInputsWithExplicitGeometryAndFullFrameTargets() {
        val press = tap.copy(id = "tap", capability = ScreenActions.LONG_PRESS, inputs = buildJsonObject { put("durationMs", 800) })
        assertTrue(codec.validate(input(listOf(read, locate, press, after, finalProof))).isEmpty())
        assertTrue(factory.contracts.single { it.id == ScreenActions.LONG_PRESS }.sendsDeviceInput)
        val swipe = tap.copy(id = "tap", capability = ScreenActions.SWIPE, inputs = buildJsonObject {
            put("durationMs", 300); put("dx", 0); put("dy", -400); put("referenceWidth", 2400); put("referenceHeight", 1080)
            put("variation", buildJsonObject { put("startJitter", 0.3); put("curve", 0.1); put("durationJitter", 0.2); put("points", 12) }) })
        assertTrue(codec.validate(input(listOf(read, locate, swipe, after, finalProof))).toString(), codec.validate(input(listOf(read, locate, swipe, after, finalProof))).isEmpty())
        // Both still need a fresh post-input proof.
        assertTrue(codec.validate(input(listOf(read, locate, swipe))).isNotEmpty())
        for ((key, value) in listOf("dy" to JsonPrimitive(0), "dx" to JsonPrimitive(1.5), "referenceWidth" to JsonPrimitive(0), "durationMs" to JsonPrimitive(0))) {
            val bad = swipe.copy(inputs = JsonObject(swipe.inputs + (key to value) + if (key == "dy") mapOf("dx" to JsonPrimitive(0)) else emptyMap()))
            assertTrue(key, codec.validate(input(listOf(read, locate, bad, after, finalProof))).isNotEmpty())
        }
        val variation = swipe.inputs.getValue("variation").jsonObject
        for (bad in listOf(JsonObject(variation - "curve"), JsonObject(variation + ("curve" to JsonPrimitive(0.8))),
            JsonObject(variation + ("points" to JsonPrimitive(1))), JsonObject(variation + ("seed" to JsonPrimitive(1.5))),
            JsonObject(variation + ("extra" to JsonPrimitive(1)))))
            assertTrue(bad.toString(), codec.validate(input(listOf(read, locate, swipe.copy(inputs = JsonObject(swipe.inputs + ("variation" to bad))), after, finalProof))).isNotEmpty())
        assertTrue(codec.validate(input(listOf(read, locate, swipe.copy(inputs = JsonObject(swipe.inputs - "variation")), after, finalProof))).isNotEmpty())
        val seeded = swipe.copy(inputs = JsonObject(swipe.inputs + ("variation" to JsonObject(variation + ("seed" to JsonPrimitive(42))))))
        assertTrue(codec.validate(input(listOf(read, locate, seeded, after, finalProof))).isEmpty())
        val configured = swipe.copy(inputs = JsonObject(swipe.inputs - "dy"), bindings = swipe.bindings + ("dy" to ActionInputBinding.Configuration("dy")))
        assertTrue(codec.validate(input(listOf(read, locate, configured, after, finalProof))).isNotEmpty())
        val inside = locate.copy(id = "inside", bindings = mapOf("observation" to ActionInputBinding.Output("regionRead", "observation")))
        for (gesture in listOf(press, swipe)) {
            val fromRegion = gesture.copy(bindings = mapOf("target" to ActionInputBinding.Output("inside", "target")))
            assertTrue(codec.validate(programmed(nodes(read, locate, derive, regionRead, inside, fromRegion, after, finalProof))).isNotEmpty())
        }
    }
}
