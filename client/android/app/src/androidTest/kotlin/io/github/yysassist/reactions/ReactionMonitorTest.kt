package io.github.yysassist.reactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.ExecutionRuntime
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real executor and storage: a passive task must go through the same queue as everything else. */
@RunWith(AndroidJUnit4::class)
class ReactionMonitorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var database: AssistDatabase
    private lateinit var library: TaskVersionLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var runtime: ExecutionRuntime
    private lateinit var monitor: ReactionMonitor
    private var now = 1_000_000L
    private val game = ExecutionContext("game", Principal.Anonymous)
    private fun wait(ms: Int) = ActionDefinition("wait", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(ms))))
    private fun node(id: String) = ProgramNode.Action(wait(0).copy(id = id))

    @Before fun open(): Unit = runBlocking {
        name = "reactions-${UUID.randomUUID()}.db"; database = AssistDatabase.open(context, name)
        val factory = BuiltInStepWorkFactory(); val codec = BundleCodec(factory.contracts); library = TaskVersionLibrary(database, codec)
        val decide = ActionDefinition("decide", SELECTION_CONTAINS, 1, JsonObject(mapOf("value" to JsonPrimitive("jade"))),
            bindings = mapOf("selection" to ActionInputBinding.Configuration("accept")))
        val invite = TaskDefinition("invite", "game", "协作邀请", "daily", 1_000, listOf(ConfigurationField("accept", "接受的协作", FieldWidget.MULTI_CHOICE, true,
            options = listOf(FieldOption("jade", "勾玉协作"), FieldOption("food", "猫粮协作")))),
            listOf(StepDefinition("answer", "处理", program = listOf(ProgramNode.Action(decide), ProgramNode.Branch(ActionInputBinding.Output("decide", "selected"), listOf(
                ProgramCase(JsonPrimitive(true), listOf(node("accepted"))), ProgramCase(JsonPrimitive(false), listOf(node("ignored")))))))),
            trigger = TaskTrigger(listOf("悬赏协作"), "accept", 30_000))
        val long = TaskDefinition("long", "game", "长任务", "daily", 10, steps = listOf(StepDefinition("wait", "等待", listOf(wait(1_500)))))
        val bundle = TaskBundle(1, "v1", "2026-11-18T00:00:00Z", "测试", listOf(invite, long),
            listOf(TargetProfile("game", "游戏", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("daily", "日常")))))
        val text = codec.write(bundle); library.install(text, "v1", TaskVersionLibrary.sha256(text)); library.activate("v1")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runtime = ExecutionRuntime(ExecutionStateStore(database, library), library, TaskAdmissionRepository(database, library) { t, k -> settings[t to k] }, factory, scope)
        monitor = ReactionMonitor(RuntimeReactionPort(runtime), scope) { now }
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
    }
    @After fun close(): Unit = runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join(); database.close(); context.deleteDatabase(name) }
    private val settings = mutableMapOf<Pair<String, String>, JsonObject>()
    private fun select(vararg options: String) {
        settings["game" to "invite"] = JsonObject(mapOf("accept" to JsonArray(options.map(::JsonPrimitive))))
    }
    private fun enqueued(task: String) = runtime.state.value.facts.count { it.type == "enqueued" && it.taskId == task }
    private suspend fun settle() = delay(600)

    @Test fun nothingSelectedMeansTheInvitationIsLeftAlone(): Unit = runBlocking {
        select(); monitor.observed(game, listOf("好友邀请你悬赏协作")); settle()
        assertEquals(0, enqueued("invite"))
    }
    @Test fun aSelectedInvitationPreemptsTheRunningTaskWhichThenFinishes(): Unit = runBlocking {
        select("jade"); runtime.enqueueTask(TaskAddress("game", "long")).await()
        withTimeout(5_000) { runtime.state.first { it.current?.task?.id == "long" } }
        monitor.observed(game, listOf("好友邀请你悬赏协作"))
        val done = withTimeoutOrNull(10_000) { runtime.state.first { state -> listOf("invite", "long").all { id -> state.facts.any { it.type == "task-completed" && it.taskId == id } } } }
        if (done == null) { val v = runtime.state.value; fail("phase=${v.phase} hold=${v.manualHold} current=${v.current?.task?.id} waiting=${v.waiting.map { it.task.id }} unresolved=${v.unresolved.map { it.reason }} status=${runtime.status.value} facts=${v.facts.map { it.type + ":" + it.taskId + ":" + it.detail }}") }
        val finished = runtime.state.value.facts.filter { it.type == "task-completed" }.map { it.taskId }
        assertEquals(listOf("invite", "long"), finished)
    }
    @Test fun theSameInvitationIsAnsweredOncePerCooldown(): Unit = runBlocking {
        select("jade"); monitor.observed(game, listOf("悬赏协作"))
        withTimeout(5_000) { runtime.state.first { state -> state.facts.any { it.type == "task-completed" && it.taskId == "invite" } } }
        now += 29_000; monitor.observed(game, listOf("悬赏协作")); settle()
        assertEquals(1, enqueued("invite"))
        now += 1_000; monitor.observed(game, listOf("悬赏协作"))
        withTimeout(5_000) { runtime.state.first { state -> state.facts.count { it.type == "enqueued" && it.taskId == "invite" } == 2 } }
    }
    @Test fun otherTextAndAPausedQueueStartNothing(): Unit = runBlocking {
        select("jade"); monitor.observed(game, listOf("庭院")); settle()
        runtime.submit(ExecutorCommand.Pause).await()
        monitor.observed(game, listOf("悬赏协作")); settle()
        assertEquals(0, enqueued("invite"))
    }
}
