package io.github.yysassist.core.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SnippetsTest {
    // A tap with a literal offset and a check producing a boolean; enough to see expansion and data flow.
    private val codec = BundleCodec(listOf(
        CapabilityContract("tap", 1, inputTypes = mapOf("dx" to ActionValueType.NUMBER)) { null },
        CapabilityContract("check", 1, outputTypes = mapOf("ok" to ActionValueType.BOOLEAN), role = ActionRole.VERIFICATION) { null },
    ))
    private fun tap(id: String, dx: JsonElement) = ProgramNode.Action(ActionDefinition(id, "tap", 1, buildJsonObject { put("dx", dx) }))
    private fun check(id: String) = ProgramNode.Action(ActionDefinition(id, "check", 1, JsonObject(emptyMap())))
    private fun param(name: String) = buildJsonObject { put("\$param", name) }
    private fun item(field: String) = buildJsonObject { put("\$item", field) }
    private fun add(vararg operands: JsonElement) = buildJsonObject { put("\$add", JsonArray(operands.toList())) }
    private fun mul(vararg operands: JsonElement) = buildJsonObject { put("\$mul", JsonArray(operands.toList())) }
    private fun bundle(steps: List<StepDefinition>, snippets: List<Snippet> = emptyList()) = TaskBundle(1, "v1", "now", "fixture",
        listOf(TaskDefinition("task", "fixture", "Task", "tools", 1, steps = steps)),
        listOf(TargetProfile("fixture", "Fixture", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")))),
        snippets = snippets)
    private fun read(bundle: TaskBundle) = codec.read(codec.write(bundle))
    private fun program(bundle: TaskBundle): List<ProgramNode> = ((read(bundle) as BundleReadResult.Valid).bundle.tasks.single().steps.last().program!!)
    private fun reasons(bundle: TaskBundle) = (read(bundle) as BundleReadResult.Invalid).issues.map { it.reason }

    private val press = Snippet("press", params = listOf(SnippetParam("dx", SnippetParamType.NUMBER)),
        program = listOf(tap("tap", add(param("dx"), JsonPrimitive(30))), check("done")))

    @Test fun aStepCanBeASnippetWithArguments() {
        val nodes = program(bundle(listOf(StepDefinition("go", "Go", use = "press", with = buildJsonObject { put("dx", 10) })), listOf(press)))
        val tapped = (nodes.first() as ProgramNode.Action).action
        assertEquals("tap", tapped.id)
        assertEquals(JsonPrimitive(40), tapped.inputs["dx"])
    }

    @Test fun snippetsCalledInsideAProgramGetTheirOwnIdsAndExportOutputs() {
        val exporting = press.copy(exports = mapOf("ready" to SnippetExport("done", "ok")))
        val step = StepDefinition("go", "Go", program = listOf(
            ProgramNode.UseSnippet("a", "press", buildJsonObject { put("dx", 1) }),
            ProgramNode.UseSnippet("b", "press", buildJsonObject { put("dx", 2) }),
            ProgramNode.Branch(ActionInputBinding.Output("b", "ready"), listOf(ProgramCase(JsonPrimitive(true), listOf(check("end")))))))
        val nodes = program(bundle(listOf(step), listOf(exporting)))
        assertEquals(listOf("a.tap", "a.done", "b.tap", "b.done"), nodes.take(4).map { (it as ProgramNode.Action).action.id })
        assertEquals(JsonPrimitive(32), (nodes[2] as ProgramNode.Action).action.inputs["dx"])
        assertEquals(ActionInputBinding.Output("b.done", "ok"), (nodes[4] as ProgramNode.Branch).value)
    }

    @Test fun forEachBecomesANamedBlockOfCopiesWithItsItemsAsConstants() {
        val rows = ProgramNode.ForEach("rows", listOf(buildJsonObject { put("i", 0) }, buildJsonObject { put("i", 2) }),
            listOf(tap("tap", add(JsonPrimitive(438), mul(JsonPrimitive(202), item("i")))), check("seen")))
        val nodes = program(bundle(listOf(StepDefinition("go", "Go", program = listOf(rows)))))
        val block = nodes.single() as ProgramNode.Block
        assertEquals("rows", block.id)
        val taps = block.program.filterIsInstance<ProgramNode.Action>().filter { it.action.capability == "tap" }
        assertEquals(listOf("rows#0.tap", "rows#1.tap"), taps.map { it.action.id })
        assertEquals(listOf(JsonPrimitive(438), JsonPrimitive(842)), taps.map { it.action.inputs["dx"] })
    }

    @Test fun exitEndsANamedLoopAndItsOutputsTellFoundFromExhausted() {
        val rows = ProgramNode.ForEach("rows", listOf(buildJsonObject { put("i", 0) }, buildJsonObject { put("i", 1) }), listOf(
            check("look"),
            ProgramNode.Branch(ActionInputBinding.Output("look", "ok"), listOf(
                ProgramCase(JsonPrimitive(true), listOf(ProgramNode.Exit("rows"))),
                ProgramCase(JsonPrimitive(false), emptyList())))))
        val after = ProgramNode.Branch(ActionInputBinding.Output("rows", "exited"), listOf(
            ProgramCase(JsonPrimitive(true), listOf(check("found"))), ProgramCase(JsonPrimitive(false), listOf(check("none")))))
        val good = bundle(listOf(StepDefinition("go", "Go", program = listOf(rows, after))))
        assertTrue(read(good).toString(), read(good) is BundleReadResult.Valid)
        val exit = ((program(good).first() as ProgramNode.Block).program[1] as ProgramNode.Branch).cases.first().program.single() as ProgramNode.Exit
        assertEquals(ProgramNode.Exit("rows", 0), exit)
        // Exiting a loop that does not enclose it, or writing anything after an exit, is refused.
        val stray = bundle(listOf(StepDefinition("go", "Go", program = listOf(check("a"), ProgramNode.Exit("rows")))))
        assertTrue(reasons(stray).any { it.contains("不在外层") })
        val dead = ProgramNode.ForEach("rows", listOf(buildJsonObject { put("i", 0) }), listOf(ProgramNode.Exit("rows"), check("never")))
        assertTrue(reasons(bundle(listOf(StepDefinition("go", "Go", program = listOf(dead, check("end")))))).any { it.contains("不会执行") })
    }

    @Test fun snippetsAreClosedReExportAndKeepTheirInsidesPrivate() {
        // A snippet cannot end a loop around its call.
        val escape = Snippet("escape", program = listOf(check("look"), ProgramNode.Exit("rows")))
        val rows = ProgramNode.ForEach("rows", listOf(buildJsonObject { put("i", 0) }), listOf(ProgramNode.UseSnippet("call", "escape"), check("seen")))
        assertTrue(reasons(bundle(listOf(StepDefinition("go", "Go", program = listOf(rows, check("end")))), listOf(escape))).any { it.contains("片段外的循环") })
        // An outer snippet re-exports what an inner call exported.
        val inner = press.copy(exports = mapOf("ready" to SnippetExport("done", "ok")))
        val outer = Snippet("outer", params = listOf(SnippetParam("dx", SnippetParamType.NUMBER)), exports = mapOf("ready" to SnippetExport("in", "ready")),
            program = listOf(ProgramNode.UseSnippet("in", "press", buildJsonObject { put("dx", buildJsonObject { put("\$param", "dx") }) })))
        val reading = StepDefinition("go", "Go", program = listOf(ProgramNode.UseSnippet("o", "outer", buildJsonObject { put("dx", 1) }),
            ProgramNode.Branch(ActionInputBinding.Output("o", "ready"), listOf(ProgramCase(JsonPrimitive(true), listOf(check("end")))))))
        val nodes = program(bundle(listOf(reading), listOf(inner, outer)))
        assertEquals(ActionInputBinding.Output("o.in.done", "ok"), (nodes[2] as ProgramNode.Branch).value)
        // Reaching inside without an export is refused.
        val sneaky = StepDefinition("go", "Go", program = listOf(ProgramNode.UseSnippet("a", "press", buildJsonObject { put("dx", 1) }),
            ProgramNode.Branch(ActionInputBinding.Output("a.done", "ok"), listOf(ProgramCase(JsonPrimitive(true), listOf(check("end")))))))
        assertTrue(reasons(bundle(listOf(sneaky), listOf(press))).any { it.contains("只能通过片段导出") })
        // A loop named like a snippet is not a cycle.
        val named = ProgramNode.ForEach("press", listOf(buildJsonObject { put("i", 0) }), listOf(ProgramNode.UseSnippet("p", "press", buildJsonObject { put("dx", 1) })))
        assertTrue(read(bundle(listOf(StepDefinition("go", "Go", program = listOf(named, check("end")))), listOf(press))).toString(),
            read(bundle(listOf(StepDefinition("go", "Go", program = listOf(named, check("end")))), listOf(press))) is BundleReadResult.Valid)
    }

    @Test fun badReferencesAreReportedNotGuessed() {
        val step = { use: String, with: JsonObject? -> StepDefinition("go", "Go", use = use, with = with) }
        assertTrue(reasons(bundle(listOf(step("press", JsonObject(emptyMap()))), listOf(press))).any { it.contains("缺少参数") })
        assertTrue(reasons(bundle(listOf(step("press", buildJsonObject { put("dx", "far") })), listOf(press))).any { it.contains("类型不符") })
        assertTrue(reasons(bundle(listOf(step("missing", null)), listOf(press))).any { it.contains("未声明的片段") })
        val loop = Snippet("loop", program = listOf(ProgramNode.UseSnippet("again", "loop")))
        assertTrue(reasons(bundle(listOf(step("loop", null)), listOf(loop))).any { it.contains("循环引用") })
        val outside = StepDefinition("go", "Go", program = listOf(tap("tap", param("dx")), check("done")))
        assertTrue(reasons(bundle(listOf(outside))).any { it.contains("只能在片段里") })
        val unused = bundle(listOf(StepDefinition("go", "Go", program = listOf(check("done")))), listOf(press))
        assertTrue(reasons(unused).any { it.contains("未被任何任务使用") })
    }

    @Test fun untilEndsOnAnyOfItsConditionsAndChecksEachOne() {
        fun loop(vararg or: UntilCondition) = StepDefinition("go", "Go", program = listOf(ProgramNode.Until(ActionInputBinding.Output("a", "ok"),
            JsonPrimitive(true), JsonPrimitive(5), listOf(check("a"), check("b")), "scan", or.toList()), check("end")))
        val good = bundle(listOf(loop(UntilCondition(ActionInputBinding.Output("b", "ok"), JsonPrimitive(true)))))
        assertTrue(read(good).toString(), read(good) is BundleReadResult.Valid)
        assertEquals(1, ((program(good).first()) as ProgramNode.Until).or.size)
        assertTrue(reasons(bundle(listOf(loop(UntilCondition(ActionInputBinding.Output("b", "ok"), JsonPrimitive("yes")))))).any { it.contains("类型不匹配") })
        assertTrue(reasons(bundle(listOf(loop(UntilCondition(ActionInputBinding.Output("elsewhere", "ok"), JsonPrimitive(true)))))).any { it.contains("顶层动作") })
    }
}
