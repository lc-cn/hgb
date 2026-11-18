package io.github.yysassist.core.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Generic handle provenance and bundle-level vision profiles, independent of any screen backend. */
class HandleContractTest {
    private val ok: (JsonObject) -> String? = { null }
    private val observe = CapabilityContract("k.observe", 1, outputTypes = mapOf("observation" to ActionValueType.STRING),
        role = ActionRole.OBSERVATION, outputHandles = mapOf("observation" to "k.obs"), validateInputs = ok)
    private val derive = CapabilityContract("k.derive", 1, inputTypes = mapOf("target" to ActionValueType.STRING),
        outputTypes = mapOf("region" to ActionValueType.STRING), outputHandles = mapOf("region" to "k.region"),
        inputHandles = mapOf("target" to HandleRequirement(setOf("k.target"))), validateInputs = ok)
    private val readRegion = CapabilityContract("k.readRegion", 1, inputTypes = mapOf("region" to ActionValueType.STRING),
        outputTypes = mapOf("observation" to ActionValueType.STRING), role = ActionRole.OBSERVATION,
        outputHandles = mapOf("observation" to "k.obs"), inputHandles = mapOf("region" to HandleRequirement(setOf("k.region"))), validateInputs = ok)
    private val locate = CapabilityContract("k.locate", 1, inputTypes = mapOf("observation" to ActionValueType.STRING),
        outputTypes = mapOf("target" to ActionValueType.STRING), outputHandles = mapOf("target" to "k.target"),
        inputHandles = mapOf("observation" to HandleRequirement(setOf("k.obs"))), validateInputs = ok)
    private val tap = CapabilityContract("k.tap", 1, inputTypes = mapOf("target" to ActionValueType.STRING), sendsDeviceInput = true,
        inputHandles = mapOf("target" to HandleRequirement(setOf("k.target"), forbiddenAncestors = setOf("k.region"))), validateInputs = ok)
    private val verify = CapabilityContract("k.verify", 1, inputTypes = mapOf("observation" to ActionValueType.STRING),
        outputTypes = mapOf("matched" to ActionValueType.BOOLEAN), role = ActionRole.VERIFICATION,
        inputHandles = mapOf("observation" to HandleRequirement(setOf("k.obs"))), validateInputs = ok)
    private val plain = CapabilityContract("k.plain", 1, inputTypes = mapOf("text" to ActionValueType.STRING), validateInputs = ok)
    private val codec = BundleCodec(listOf(observe, derive, readRegion, locate, tap, verify, plain))

    private fun act(id: String, contract: CapabilityContract, vararg from: Pair<String, String>) =
        ActionDefinition(id, contract.id, 1, JsonObject(emptyMap()), from.associate { (parameter, source) ->
            val (action, field) = source.split('.'); parameter to ActionInputBinding.Output(action, field)
        })
    private fun bundle(vararg nodes: ProgramNode, profiles: List<VisionProfile> = emptyList(), resources: List<TaskResource> = emptyList()) =
        TaskBundle(1, "v1", "now", "", listOf(TaskDefinition("task", "fixture", "Task", "tools", 1,
            steps = listOf(StepDefinition("step", "Step", program = nodes.toList())))),
            listOf(TargetProfile("fixture", "Fixture", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")))),
            resources, profiles)
    private fun steps(vararg actions: ActionDefinition) = bundle(*actions.map(ProgramNode::Action).toTypedArray())
    private val read = act("read", observe)
    private val find = act("find", locate, "observation" to "read.observation")
    private val after = act("after", observe)
    private val check = act("check", verify, "observation" to "after.observation")

    @Test fun aHandleChainThroughInputAndFreshProofIsAccepted() {
        assertEquals(emptyList<ContentIssue>(), codec.validate(steps(read, find, act("tap", tap, "target" to "find.target"), after, check)))
    }
    @Test fun literalsAndConfigurationCannotSupplyAHandle() {
        val literal = ActionDefinition("tap", "k.tap", 1, buildJsonObject { put("target", "invented") })
        assertTrue(codec.validate(steps(read, literal, after, check)).isNotEmpty())
    }
    @Test fun aHandleOfTheWrongKindIsRejected() {
        val wrong = act("tap", tap, "target" to "read.observation")
        assertTrue(codec.validate(steps(read, wrong, after, check)).any { it.reason.contains("所需") })
    }
    @Test fun anyPossibleInputExpiresEarlierHandles() {
        val stale = act("check", verify, "observation" to "read.observation")
        assertTrue(codec.validate(steps(read, find, act("tap", tap, "target" to "find.target"), stale)).any { it.reason.contains("失效") })
    }
    @Test fun lineageIsCheckedAtAnyDepth() {
        val region = act("region", derive, "target" to "find.target")
        val inner = act("inner", readRegion, "region" to "region.region")
        val regionTarget = act("regionTarget", locate, "observation" to "inner.observation")
        val bad = steps(read, find, region, inner, regionTarget, act("tap", tap, "target" to "regionTarget.target"), after, check)
        assertTrue(codec.validate(bad).any { it.reason.contains("来源") })
        // The same region-derived observation is still fine for read-only verification.
        assertEquals(emptyList<ContentIssue>(), codec.validate(steps(read, find, region, inner, act("v", verify, "observation" to "inner.observation"))))
    }
    @Test fun ownKindIsNotLineageButDeeperRegionsAre() {
        val tapRegion = CapabilityContract("k.tapRegion", 1, inputTypes = mapOf("region" to ActionValueType.STRING), sendsDeviceInput = true,
            inputHandles = mapOf("region" to HandleRequirement(setOf("k.region"), forbiddenAncestors = setOf("k.region"))), validateInputs = ok)
        val codec = BundleCodec(listOf(observe, derive, readRegion, locate, verify, tapRegion))
        val region = act("region", derive, "target" to "find.target")
        val press = ActionDefinition("press", "k.tapRegion", 1, JsonObject(emptyMap()), mapOf("region" to ActionInputBinding.Output("region", "region")))
        assertEquals(emptyList<ContentIssue>(), codec.validate(steps(read, find, region, press, after, check)))
        val inner = act("inner", readRegion, "region" to "region.region")
        val nestedTarget = act("nestedTarget", locate, "observation" to "inner.observation")
        val nested = act("nested", derive, "target" to "nestedTarget.target")
        val pressNested = press.copy(bindings = mapOf("region" to ActionInputBinding.Output("nested", "region")))
        assertTrue(codec.validate(steps(read, find, region, inner, nestedTarget, nested, pressNested, after, check)).any { it.reason.contains("来源") })
    }
    @Test fun handlesNeverLeakIntoPlainParametersOrBranches() {
        assertTrue(codec.validate(steps(read, act("p", plain, "text" to "read.observation"), check.copy(id = "c2",
            bindings = mapOf("observation" to ActionInputBinding.Output("read", "observation"))))).any { it.reason.contains("普通参数") })
        val branch = ProgramNode.Branch(ActionInputBinding.Output("read", "observation"), listOf(ProgramCase(JsonPrimitive("x"), emptyList())))
        assertTrue(codec.validate(bundle(ProgramNode.Action(read), branch, ProgramNode.Action(act("c", verify, "observation" to "read.observation"))))
            .any { it.reason.contains("句柄") })
    }
    @Test fun handleDeclarationsMustBeStringTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            BundleCodec(listOf(observe.copy(outputTypes = mapOf("observation" to ActionValueType.NUMBER))))
        }
    }

    private val model = TaskResource("det", "det.bin", 1, "a".repeat(64))
    private fun profile(id: String = "ocr", target: String = "fixture", pkg: String = "test.fixture", models: Map<String, String> = mapOf("detector" to "det")) =
        VisionProfile(id, target, pkg, "fixture.ocr", models)
    @Test fun visionProfilesReferenceDeclaredTargetPackageAndResources() {
        val base = arrayOf<ProgramNode>(ProgramNode.Action(ActionDefinition("w", "k.plain", 1, buildJsonObject { put("text", "x") })))
        assertEquals(emptyList<ContentIssue>(), codec.validate(bundle(*base, profiles = listOf(profile()), resources = listOf(model))))
        for (bad in listOf(listOf(profile(target = "other")), listOf(profile(pkg = "other.app")), listOf(profile(models = mapOf("detector" to "missing"))),
            listOf(profile(models = emptyMap())), listOf(profile(), profile())))
            assertTrue(bad.toString(), codec.validate(bundle(*base, profiles = bad, resources = listOf(model))).isNotEmpty())
    }
    @Test fun bundlesWithoutProfilesKeepTheirSerializedForm() {
        val text = codec.write(steps(read, act("v", verify, "observation" to "read.observation")))
        assertFalse(text.contains("visionProfiles"))
    }
}
