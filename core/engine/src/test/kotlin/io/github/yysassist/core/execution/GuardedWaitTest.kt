package io.github.yysassist.core.execution

import io.github.yysassist.core.content.*
import io.github.yysassist.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Interruption pages (design 1.6) and step run conditions (design 2.3 ①). */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardedWaitTest {
    private val awaitScreen = CapabilityContract("screen.awaitScreen", 1, inputTypes = mapOf("states" to ActionValueType.ARRAY),
        outputTypes = mapOf("state" to ActionValueType.STRING), role = ActionRole.VERIFICATION, validateInputs = { null })
    private val tap = CapabilityContract("fixture.tap", 1, sendsDeviceInput = true, validateInputs = { null })

    /** A screen that shows [pages] in turn (the last one stays), recording what each wait looked for and every tap. */
    private class Screen(val pages: List<String>) {
        var shown = 0
        val asked = mutableListOf<List<String>>()
        val taps = mutableListOf<String>()
    }
    private fun provider(screen: Screen) = object : ActionProvider {
        override val contracts = listOf(awaitScreen, tap)
        override fun open(attempt: StepAttempt, bundle: TaskBundle) = ActionSession { action, inputs ->
            if (action.capability == "fixture.tap") { screen.taps += action.id; ActionResult.Produced(JsonObject(emptyMap())) }
            else {
                screen.asked += inputs.getValue("states").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                val page = screen.pages[minOf(screen.shown++, screen.pages.lastIndex)]
                ActionResult.Verified(buildJsonObject { put("state", page) }, "看到 $page")
            }
        }
    }

    private fun state(id: String) = buildJsonObject { put("id", id) }
    private fun await(id: String, vararg states: String) = ProgramNode.Action(ActionDefinition(id, "screen.awaitScreen", 1,
        buildJsonObject { put("states", JsonArray(states.map(::state))) }))
    private fun press(id: String) = ProgramNode.Action(ActionDefinition(id, "fixture.tap", 1, JsonObject(emptyMap())))

    /** One task of [steps]; the game declares the "lost connection" page, cleared by pressing OK and checking it went. */
    private fun bundle(steps: List<StepDefinition>, fields: List<ConfigurationField> = emptyList(), maximum: Int = 3) = TaskBundle(1, "v1", "now", "fixture",
        listOf(TaskDefinition("task", "fixture", "Task", "tools", 1, fields, steps)),
        listOf(TargetProfile("fixture", "Fixture", listOf("test.fixture"), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")),
            library = TargetLibrary(interruptions = listOf(Interruption("lost", "断线", state("dialog"), use = "ok", maximum = maximum))))),
        snippets = listOf(Snippet("ok", program = listOf(press("press"), await("gone", "cleared")))))
    private fun read(bundle: TaskBundle): TaskBundle {
        val codec = BundleCodec(listOf(awaitScreen, tap))
        return when (val read = codec.read(codec.write(bundle))) {
            is BundleReadResult.Valid -> read.bundle
            is BundleReadResult.Invalid -> error(read.issues.toString())
        }
    }
    private fun attempt(bundle: TaskBundle, step: String, config: String = "{}"): StepAttempt {
        val task = bundle.tasks.single()
        val request = ExecutionRequest(SubmissionRef(SubmissionKind.TASK, task.id), ExecutionContext(task.targetId, Principal.Anonymous),
            bundle.version, listOf(TaskSnapshot(task.id, task.name, task.priority, task.steps.map { it.id }, config)))
        return StepAttempt(AttemptTicket(1, 1), RunProgress(1, 1, request, 1).copy(stepIndex = task.steps.indexOfFirst { it.id == step }))
    }

    @Test fun everyPageWaitIsWrappedAndAnInterruptionIsClearedBeforeWaitingAgain() = runTest {
        val read = read(bundle(listOf(StepDefinition("go", "Go", program = listOf(await("home", "courtyard"))))))
        val guarded = read.tasks.single().steps.single().program!!.single() as ProgramNode.Guarded
        assertEquals("home", guarded.wait.id)
        // Its own copy of the handler, named after the wait, so two waits never share ids.
        assertEquals(listOf("home~lost.press", "home~lost.gone"), guarded.guards.single().program.map { (it as? ProgramNode.Action)?.action?.id ?: (it as ProgramNode.Guarded).wait.id })
        val screen = Screen(listOf("interruption:lost", "cleared", "courtyard"))
        val records = mutableListOf<String>()
        val outcome = BuiltInStepWorkFactory(provider(screen)) { records += it.actionId }.create(attempt(read, "go"), read).execute()
        assertTrue(outcome.toString(), outcome is StepOutcome.Completed)
        // The wait looked for its own page and the interruption; the handler pressed OK; then it waited again.
        assertEquals(listOf("courtyard", "interruption:lost"), screen.asked.first())
        assertEquals(listOf("home~lost.press"), screen.taps)
        assertEquals(listOf("home", "home~lost.press", "home~lost.gone", "home"), records)
    }

    @Test fun anInterruptionThatStaysEndsTheStepUnknownAfterItsMaximum() = runTest {
        val read = read(bundle(listOf(StepDefinition("go", "Go", program = listOf(await("home", "courtyard")))), maximum = 2))
        val screen = Screen(listOf("interruption:lost", "cleared"))
        // The page comes back every time the wait looks.
        val stubborn = object : ActionProvider by provider(screen) {
            override fun open(attempt: StepAttempt, bundle: TaskBundle) = ActionSession { action, inputs ->
                if (action.id == "home") ActionResult.Verified(buildJsonObject { put("state", "interruption:lost") }, "断线")
                else provider(screen).open(attempt, bundle).perform(action, inputs)
            }
        }
        val outcome = BuiltInStepWorkFactory(stubborn).create(attempt(read, "go"), read).execute()
        assertTrue(outcome.toString(), outcome is StepOutcome.Unknown && outcome.reason.contains("干扰页 断线"))
        assertEquals(2, screen.taps.size)
    }

    @Test fun aStepWhoseConditionDoesNotHoldIsSkippedWithoutTouchingTheScreen() = runTest {
        val collect = ConfigurationField("collect", "收取结界奖励", FieldWidget.SWITCH)
        val steps = listOf(StepDefinition("claim", "收取", program = listOf(await("realm", "realm")), runWhen = StepCondition("collect", JsonPrimitive(true))))
        val read = read(bundle(steps, listOf(collect)))
        val screen = Screen(listOf("realm"))
        val off = BuiltInStepWorkFactory(provider(screen)).create(attempt(read, "claim", """{"collect":false}"""), read).execute()
        assertEquals(StepOutcome.Completed("已跳过（收取结界奖励 ≠ 开）"), off)
        assertTrue(screen.asked.isEmpty())
        val on = BuiltInStepWorkFactory(provider(screen)).create(attempt(read, "claim", """{"collect":true}"""), read).execute()
        assertTrue(on is StepOutcome.Completed && !on.evidence.startsWith("已跳过"))
    }

    @Test fun anUnsetSwitchCountsAsOff() = runTest {
        val collect = ConfigurationField("collect", "收取结界奖励", FieldWidget.SWITCH)
        val steps = listOf(StepDefinition("claim", "收取", program = listOf(press("p"), await("w", "x")), runWhen = StepCondition("collect", JsonPrimitive(true), not = true)))
        val read = read(bundle(steps, listOf(collect)))
        val screen = Screen(listOf("x"))
        val outcome = BuiltInStepWorkFactory(provider(screen)).create(attempt(read, "claim", "{}"), read).execute()
        assertTrue(outcome.toString(), outcome is StepOutcome.Completed && !outcome.evidence.startsWith("已跳过"))
    }

    @Test fun aLibraryStateByReferenceTakesThePageId() {
        val written = bundle(listOf(StepDefinition("go", "Go", program = listOf(await("home", "courtyard")))))
        val withLibrary = written.copy(targets = written.targets.map { it.copy(library = it.library!!.copy(
            states = listOf(StateDefinition("dialog-ref", text = listOf(buildJsonObject { put("text", "确定") }))),
            interruptions = listOf(Interruption("lost", "断线", buildJsonObject { put("state", "dialog-ref") }, use = "ok")))) })
        val guarded = read(withLibrary).tasks.single().steps.single().program!!.single() as ProgramNode.Guarded
        assertEquals("interruption:lost", guarded.guards.single().state.jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun runConditionsAreChecked() {
        val codec = BundleCodec(listOf(awaitScreen, tap))
        val choice = ConfigurationField("card", "挑卡", FieldWidget.SINGLE_CHOICE, options = listOf(FieldOption("jade", "勾玉"), FieldOption("food", "体力")))
        fun issues(condition: StepCondition): List<String> {
            val written = bundle(listOf(StepDefinition("s", "S", program = listOf(await("w", "x")), runWhen = condition)), listOf(choice))
            return (codec.read(codec.write(written)) as? BundleReadResult.Invalid)?.issues.orEmpty().map { it.reason }
        }
        assertTrue(issues(StepCondition("card", JsonPrimitive("jade"))).isEmpty())
        assertTrue(issues(StepCondition("nope", JsonPrimitive("jade"))).any { it.contains("不存在的配置项") })
        assertTrue(issues(StepCondition("card", JsonPrimitive("gold"))).any { it.contains("不是该配置项的选项") })
        assertTrue(issues(StepCondition("card", contains = "jade")).any { it.contains("多选") })
    }
}
