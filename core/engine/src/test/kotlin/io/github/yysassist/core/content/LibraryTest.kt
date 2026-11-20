package io.github.yysassist.core.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryTest {
    private val codec = BundleCodec(listOf(
        CapabilityContract("screen.awaitScreen", 1, inputTypes = mapOf("states" to ActionValueType.ARRAY, "timeoutMs" to ActionValueType.NUMBER,
            "referenceWidth" to ActionValueType.NUMBER), role = ActionRole.VERIFICATION) { null },
        CapabilityContract("control.wait", 1, inputTypes = mapOf("durationMs" to ActionValueType.NUMBER)) { null },
    ))
    private val library = TargetLibrary(
        regions = mapOf("TITLE" to listOf(180, 20, 340, 110), "SLOT" to listOf(1900, 40, 240, 200)),
        states = listOf(
            StateDefinition("training", "育成页", text = listOf(JsonPrimitive("式神育成@TITLE"))),
            StateDefinition("free", "寄养位空", base = "training", present = listOf(JsonPrimitive("foster-plus@SLOT"))),
            StateDefinition("cardMax", "这张卡满级", params = listOf(SnippetParam("x", SnippetParamType.NUMBER)),
                text = listOf(buildJsonObject { put("text", "满"); put("within", buildJsonArray { add(buildJsonObject { put("\$param", "x") }); add(700); add(90); add(75) }) })),
        ),
        defaults = mapOf("*" to buildJsonObject { put("referenceWidth", 2400) }, "screen.awaitScreen" to buildJsonObject { put("timeoutMs", 8000) },
            "text" to buildJsonObject { put("minimumConfidence", 0.8) }, "image" to buildJsonObject { put("minimumScore", 0.85) }),
    )
    private fun bundle(vararg nodes: ProgramNode) = TaskBundle(1, "v1", "now", "fixture",
        listOf(TaskDefinition("task", "fixture", "Task", "tools", 1, steps = listOf(StepDefinition("go", "Go", program = nodes.toList())))),
        listOf(TargetProfile("fixture", "Fixture", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")), library = library)))
    private fun await(vararg states: JsonElement) = ProgramNode.Action(ActionDefinition("wait", "screen.awaitScreen", 1,
        buildJsonObject { put("states", JsonArray(states.toList())) }))
    private fun read(bundle: TaskBundle) = codec.read(codec.write(bundle))

    @Test fun statesAreWrittenByNameAndExpandToTheirConditionsWithDefaults() {
        val read = read(bundle(await(JsonPrimitive("free"), buildJsonObject { put("state", "cardMax"); put("with", buildJsonObject { put("x", 323) }) })))
        assertTrue(read.toString(), read is BundleReadResult.Valid)
        val action = ((read as BundleReadResult.Valid).bundle.tasks.single().steps.single().program!!.single() as ProgramNode.Action).action
        assertEquals(JsonPrimitive(8000), action.inputs["timeoutMs"])
        assertEquals(JsonPrimitive(2400), action.inputs["referenceWidth"])
        val (free, max) = action.inputs.getValue("states").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("free"), free["id"])
        // The base's text condition comes first, the state's own template after, each with the default threshold.
        assertEquals(buildJsonObject { put("minimumConfidence", 0.8); put("text", "式神育成"); put("within", buildJsonArray { add(180); add(20); add(340); add(110) }) },
            free.getValue("text").jsonArray.single())
        assertEquals(JsonPrimitive("foster-plus"), free.getValue("present").jsonArray.single().jsonObject["template"])
        assertEquals(JsonPrimitive(0.85), free.getValue("present").jsonArray.single().jsonObject["minimumScore"])
        assertEquals(buildJsonArray { add(323); add(700); add(90); add(75) }, max.getValue("text").jsonArray.single().jsonObject["within"])
        assertNull(read.bundle.targets.single().library)
    }

    @Test fun defaultsFillOnlyDeclaredInputsTheActionLeftOut() {
        val wait = ProgramNode.Action(ActionDefinition("pause", "control.wait", 1, buildJsonObject { put("durationMs", 50) }))
        val read = read(bundle(wait, await(JsonPrimitive("training")))) as BundleReadResult.Valid
        val pause = (read.bundle.tasks.single().steps.single().program!!.first() as ProgramNode.Action).action
        // The wait keeps its own duration and gets no referenceWidth, which it does not declare.
        assertEquals(buildJsonObject { put("durationMs", 50) }, pause.inputs)
    }

    @Test fun unknownNamesAreReported() {
        fun reasons(bundle: TaskBundle) = (read(bundle) as BundleReadResult.Invalid).issues.map { it.reason }
        assertTrue(reasons(bundle(await(JsonPrimitive("nowhere")))).any { it.contains("未定义的状态") })
        val badRegion = await(buildJsonObject { put("id", "x"); put("text", buildJsonArray { add(buildJsonObject { put("text", "a"); put("within", "NOPE") }) }) })
        assertTrue(reasons(bundle(badRegion)).any { it.contains("未定义的区域") })
        assertTrue(reasons(bundle(await(JsonPrimitive("cardMax")))).any { it.contains("缺少参数") })
    }
}
