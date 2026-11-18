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

    @Test fun backKeyIsAnInputBoundToAFreshObservationAndNeedsLaterProof() {
        val back = ActionDefinition("back", ScreenActions.BACK, 1, JsonObject(emptyMap()), mapOf("observation" to ActionInputBinding.Output("read", "observation")))
        assertTrue(codec.validate(input(listOf(read, back, after, finalProof))).toString(), codec.validate(input(listOf(read, back, after, finalProof))).isEmpty())
        assertTrue(factory.contracts.single { it.id == ScreenActions.BACK }.sendsDeviceInput)
        assertTrue(codec.validate(input(listOf(read, back))).isNotEmpty())
        assertTrue(codec.validate(input(listOf(read, back.copy(inputs = buildJsonObject { put("observation", "invented") }, bindings = emptyMap()), after, finalProof))).isNotEmpty())
        // A second back reusing the observation from before the first input is stale.
        assertTrue(codec.validate(input(listOf(read, back, back.copy(id = "again"), after, finalProof))).any { it.reason.contains("失效") })
        assertTrue(codec.validate(input(listOf(read, back.copy(inputs = buildJsonObject { put("x", 1) }), after, finalProof))).isNotEmpty())
    }

    @Test fun tapRegionNeedsAFullFrameAnchorExplicitGuardAndLaterProof() {
        val press = ActionDefinition("press", ScreenActions.TAP_REGION, 1, buildJsonObject { put("durationMs", 80); put("requireNoText", true) },
            mapOf("region" to ActionInputBinding.Output("derive", "region")))
        assertTrue(codec.validate(input(listOf(read, locate, derive, press, after, finalProof))).toString(),
            codec.validate(input(listOf(read, locate, derive, press, after, finalProof))).isEmpty())
        assertTrue(factory.contracts.single { it.id == ScreenActions.TAP_REGION }.sendsDeviceInput)
        assertTrue(codec.validate(input(listOf(read, locate, derive, press))).isNotEmpty())
        for (bad in listOf(buildJsonObject { put("durationMs", 80) }, buildJsonObject { put("durationMs", 80); put("requireNoText", "true") },
            buildJsonObject { put("durationMs", 0); put("requireNoText", true) }))
            assertTrue(bad.toString(), codec.validate(input(listOf(read, locate, derive, press.copy(inputs = bad), after, finalProof))).isNotEmpty())
        val configured = press.copy(inputs = buildJsonObject { put("durationMs", 80) }, bindings = press.bindings + ("requireNoText" to ActionInputBinding.Configuration("guard")))
        assertTrue(codec.validate(input(listOf(read, locate, derive, configured, after, finalProof))).isNotEmpty())
        // A region whose anchor was found inside another region cannot be replayed on a fresh frame.
        val inside = locate.copy(id = "inside", bindings = mapOf("observation" to ActionInputBinding.Output("regionRead", "observation")))
        val nested = derive.copy(id = "nested", bindings = mapOf("target" to ActionInputBinding.Output("inside", "target")))
        val pressNested = press.copy(bindings = mapOf("region" to ActionInputBinding.Output("nested", "region")))
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, regionRead, inside, nested, pressNested, after, finalProof))).any { it.reason.contains("来源") })
        // A region from before an earlier input is stale.
        assertTrue(codec.validate(input(listOf(read, locate, derive, tap, press, after, finalProof))).any { it.reason.contains("失效") })
    }

    @Test fun imageTargetsAreUniqueTemplateMatchesOnAFullFrameAndCanBeClicked() {
        val icon = ActionDefinition("icon", ScreenActions.LOCATE_IMAGE, 1, buildJsonObject { put("template", "jade"); put("minimumScore", 0.9) },
            mapOf("observation" to ActionInputBinding.Output("read", "observation")))
        val press = tap.copy(bindings = mapOf("target" to ActionInputBinding.Output("icon", "target")))
        assertTrue(codec.validate(input(listOf(read, icon, press, after, finalProof))).toString(), codec.validate(input(listOf(read, icon, press, after, finalProof))).isEmpty())
        // An image target can anchor a region too.
        val near = derive.copy(bindings = mapOf("target" to ActionInputBinding.Output("icon", "target")))
        assertTrue(codec.validate(input(listOf(read, icon, near, regionRead, regionProof))).isEmpty())
        for (bad in listOf(buildJsonObject { put("template", "missing"); put("minimumScore", 0.9) }, buildJsonObject { put("template", "jade"); put("minimumScore", 1.2) },
            buildJsonObject { put("template", "jade") }))
            assertTrue(bad.toString(), codec.validate(input(listOf(read, icon.copy(inputs = bad), press, after, finalProof))).isNotEmpty())
        val fromRegion = icon.copy(bindings = mapOf("observation" to ActionInputBinding.Output("regionRead", "observation")))
        assertTrue(codec.validate(programmed(nodes(read, locate, derive, regionRead, fromRegion, verify))).any { it.reason.contains("来源") })
    }
    @Test fun classifyStatesMayRequireAbsentTextButStayExplicit() {
        val states = buildJsonArray {
            add(buildJsonObject { put("id", "dialog"); put("text", "下载"); put("minimumConfidence", 0.8) })
            add(buildJsonObject { put("id", "shop"); put("text", "礼包屋"); put("minimumConfidence", 0.8)
                put("absent", buildJsonArray { add(buildJsonObject { put("text", "下载"); put("minimumConfidence", 0.8) }) }) })
        }
        assertTrue(codec.validate(programmed(nodes(read, classify.copy(inputs = buildJsonObject { put("states", states) }), verify))).isEmpty())
        for (absent in listOf(JsonArray(emptyList()), buildJsonArray { add(buildJsonObject { put("text", "下载") }) }, JsonPrimitive("下载"))) {
            val bad = buildJsonArray { add(JsonObject(states[1].jsonObject + ("absent" to absent))) }
            assertTrue(absent.toString(), codec.validate(programmed(nodes(read, classify.copy(inputs = buildJsonObject { put("states", bad) }), verify))).isNotEmpty())
        }
    }

    @Test fun numbersSelectionColorAndComparisonAreExplicit() {
        val number = ActionDefinition("number", ScreenActions.READ_FRACTION, 1, buildJsonObject {
            put("minimumConfidence", 0.8); put("minimum", 0); put("maximum", 999) }, mapOf("observation" to ActionInputBinding.Output("read", "observation")))
        assertTrue(codec.validate(input(listOf(read, number, verify))).toString(), codec.validate(input(listOf(read, number, verify))).isEmpty())
        for (bad in listOf(buildJsonObject { put("minimumConfidence", 0.8); put("minimum", 5); put("maximum", 1) },
            buildJsonObject { put("minimumConfidence", 0.8); put("minimum", -1); put("maximum", 9) },
            buildJsonObject { put("minimumConfidence", 0.8); put("minimum", 0) }))
            assertTrue(bad.toString(), codec.validate(input(listOf(read, number.copy(inputs = bad), verify))).isNotEmpty())
        val pick = ActionDefinition("pick", ScreenActions.SELECT_TEXT, 1, buildJsonObject {
            put("text", "进攻"); put("minimumConfidence", 0.8); put("order", "top"); put("index", 1) }, mapOf("observation" to ActionInputBinding.Output("read", "observation")))
        val press = tap.copy(bindings = mapOf("target" to ActionInputBinding.Output("pick", "target")))
        assertTrue(codec.validate(input(listOf(read, pick, press, after, finalProof))).isEmpty())
        for ((key, value) in listOf("order" to JsonPrimitive("middle"), "index" to JsonPrimitive(-1), "index" to JsonPrimitive(50)))
            assertTrue(key, codec.validate(input(listOf(read, pick.copy(inputs = JsonObject(pick.inputs + (key to value))), press, after, finalProof))).isNotEmpty())
        val color = ActionDefinition("color", ScreenActions.MATCH_COLOR, 1, buildJsonObject {
            put("lower", buildJsonArray { add(200); add(0); add(0) }); put("upper", buildJsonArray { add(255); add(60); add(60) }); put("minimumRatio", 0.3)
        }, mapOf("region" to ActionInputBinding.Output("derive", "region")))
        assertTrue(codec.validate(input(listOf(read, locate, derive, color, verify))).isEmpty())
        for (bad in listOf(JsonObject(color.inputs + ("lower" to buildJsonArray { add(256); add(0); add(0) })),
            JsonObject(color.inputs + ("upper" to buildJsonArray { add(100); add(60); add(60) })), JsonObject(color.inputs - "minimumRatio")))
            assertTrue(bad.toString(), codec.validate(input(listOf(read, locate, derive, color.copy(inputs = bad), verify))).isNotEmpty())
        // Before/after comparison may reach back across the input; nothing else may.
        val afterLocate = locate.copy(id = "afterLocate", bindings = mapOf("observation" to ActionInputBinding.Output("after", "observation")))
        val afterRegion = derive.copy(id = "afterRegion", bindings = mapOf("target" to ActionInputBinding.Output("afterLocate", "target")))
        val diff = ActionDefinition("diff", ScreenActions.COMPARE, 1, buildJsonObject { put("minimumDifference", 0.05) }, mapOf(
            "before" to ActionInputBinding.Output("read", "observation"), "region" to ActionInputBinding.Output("afterRegion", "region")))
        assertTrue(codec.validate(input(listOf(read, locate, tap, after, afterLocate, afterRegion, diff, finalProof))).toString(),
            codec.validate(input(listOf(read, locate, tap, after, afterLocate, afterRegion, diff, finalProof))).isEmpty())
        val staleRegion = diff.copy(bindings = mapOf("before" to ActionInputBinding.Output("read", "observation"), "region" to ActionInputBinding.Output("derive", "region")))
        assertTrue(codec.validate(input(listOf(read, locate, derive, tap, after, staleRegion, finalProof))).any { it.reason.contains("失效") })
    }

    @Test fun launchNeedsALaterProofAndWaitsAndNotificationsAreBounded() {
        val launch = ActionDefinition("launch", ScreenActions.LAUNCH, 1, buildJsonObject { put("profile", "ocr") })
        val await = ActionDefinition("await", ScreenActions.AWAIT_FOREGROUND, 1, buildJsonObject { put("profile", "ocr"); put("timeoutMs", 30000) })
        assertTrue(codec.validate(input(listOf(launch, await, after, finalProof))).toString(), codec.validate(input(listOf(launch, await, after, finalProof))).isEmpty())
        val contract = factory.contracts.single { it.id == ScreenActions.LAUNCH }
        assertTrue(contract.sendsDeviceInput && contract.startsTarget)
        // Requesting a launch proves nothing by itself.
        assertTrue(codec.validate(input(listOf(launch, await))).isNotEmpty())
        assertTrue(codec.validate(input(listOf(launch.copy(inputs = buildJsonObject { put("profile", "missing") }), after, finalProof))).isNotEmpty())
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(120001), JsonPrimitive(1.5)))
            assertTrue(value.toString(), codec.validate(input(listOf(launch, await.copy(inputs = JsonObject(await.inputs + ("timeoutMs" to value))), after, finalProof))).isNotEmpty())
        val notify = ActionDefinition("notify", ScreenActions.NOTIFY, 1, buildJsonObject { put("title", "完成"); put("text", "今日礼包已领取") })
        assertTrue(codec.validate(input(listOf(read, notify, verify))).isEmpty())
        for (bad in listOf(buildJsonObject { put("title", ""); put("text", "x") }, buildJsonObject { put("title", "x".repeat(65)); put("text", "x") },
            buildJsonObject { put("title", "x"); put("text", "y".repeat(241)) }, buildJsonObject { put("title", "x") }))
            assertTrue(bad.toString(), codec.validate(input(listOf(read, notify.copy(inputs = bad), verify))).isNotEmpty())
    }
}
