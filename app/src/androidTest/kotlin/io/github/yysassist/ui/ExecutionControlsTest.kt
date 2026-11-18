package io.github.yysassist.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.*
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.storage.*
import io.github.yysassist.floating.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Native controls observe the same real runtime/store used by both fixture pages. */
class ExecutionControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: AssistDatabase
    private lateinit var runtime: ExecutionRuntime
    private lateinit var library: TaskVersionLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var name: String
    private val startCondition = MutableStateFlow<StepStartCondition>(StepStartCondition.Ready)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun open(): Unit = runBlocking {
        name = "controls-${UUID.randomUUID()}.db"; database = AssistDatabase.open(context, name)
        val factory = BuiltInStepWorkFactory(); val codec = BundleCodec(factory.contracts)
        library = TaskVersionLibrary(database, codec)
        val bundle = TaskBundle(1, "v1", "2026-11-16T00:00:00Z", "UI fixture", listOf("one" to "等待一", "two" to "等待二").map { (id, label) ->
            TaskDefinition(id, "fixture", label, "tools", 1, steps = listOf(StepDefinition("wait", "等待", listOf(
                ActionDefinition("delay", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(60_000))))))))
        }, listOf(TargetProfile("fixture", "测试应用", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))))
        val text = codec.write(bundle); library.install(text, "v1", TaskVersionLibrary.sha256(text)); library.activate("v1")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runtime = ExecutionRuntime(ExecutionStateStore(database, library), library, TaskAdmissionRepository(database, library), factory, scope,
            startReadiness = object : ExecutionStartReadiness {
                override val changes = startCondition.map { Unit }
                override suspend fun check(attempt: StepAttempt, bundle: TaskBundle) = startCondition.value
            })
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
    }
    @After fun close(): Unit = runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join(); database.close(); context.deleteDatabase(name) }
    private fun show(wide: Boolean = false) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == if (wide) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val catalog = runBlocking { library.snapshot() }
        compose.setContent {
            val state by runtime.state.collectAsState(); val status by runtime.status.collectAsState()
            var home by remember { mutableStateOf(false) }
            AssistTheme(if (wide) ThemeMode.DARK else ThemeMode.LIGHT) { Surface(Modifier.fillMaxSize()) {
                Column {
                    Row { TextButton(onClick = { home = false }) { Text("任务中心") }; TextButton(onClick = { home = true }) { Text("工作台") } }
                    Box(Modifier.weight(1f)) {
                        if (home) ExecutionWorkbench(state, status, wide, { runtime.submit(ExecutorCommand.Pause) },
                            { id, mode -> runtime.submit(id?.let { ExecutorCommand.ResumeCurrent(it, mode) } ?: ExecutorCommand.Resume(mode)) },
                            { runtime.submit(ExecutorCommand.Remove(it)) }, { state.current?.let { runtime.submit(ExecutorCommand.StopCurrent(it.runId)) } })
                        else TaskCenter(VersionCatalogState.Ready(catalog), state, status, { runtime.enqueueTask(it) },
                            { runtime.submit(ExecutorCommand.PauseCurrent(it)) }, { runtime.submit(ExecutorCommand.StopCurrent(it)) },
                            { id, mode -> runtime.submit(ExecutorCommand.ResumeCurrent(id, mode)) }, {}, {}, {})
                    }
                }
            } }
        }
    }
    private fun wait(test: (ExecutorSnapshot) -> Boolean) { compose.waitUntil(5_000) { test(runtime.state.value) } }
    private fun addBoth() {
        compose.onNodeWithContentDescription("加入队列：等待一").performClick(); wait { it.current?.task?.id == "one" }
        compose.onNodeWithContentDescription("加入队列：等待二").performClick(); wait { it.waiting.size == 1 }
    }
    private fun screenshot(name: String) {
        val file = context.filesDir.resolve("qa/execution-controls/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitingControls(wide: Boolean) {
        startCondition.value = StepStartCondition.Waiting("等待目标应用回到前台")
        show(wide)
        compose.onNodeWithContentDescription("加入队列：等待一").performClick()
        compose.waitUntil(5_000) { runtime.status.value is RuntimeStatus.Waiting }
        compose.onNodeWithContentDescription("暂停：等待一")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "等待中"))
        compose.onNodeWithText("工作台").performClick()
        compose.onNodeWithText("等待目标应用回到前台").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停：队列").assertIsEnabled()
        screenshot(if (wide) "waiting-landscape-dark" else "waiting-portrait-light")
        compose.onNodeWithContentDescription("暂停：队列").performClick()
        wait { it.phase == ExecutorPhase.PAUSED }
        startCondition.value = StepStartCondition.Ready
        compose.onNodeWithContentDescription("继续队列").assertIsDisplayed()
        compose.onNodeWithText("等待目标应用回到前台").assertDoesNotExist()
        assertTrue(runtime.state.value.manualHold)
    }
    @Test fun waitingIsVisibleAndCanBePausedInPortrait() = waitingControls(false)
    @Test fun waitingIsVisibleAndCanBePausedInLandscape() = waitingControls(true)
    @Test fun queuedRowsDisableEnqueueAndPausedRowsOfferTheTwoRealRecoveryModes() {
        show(); addBoth()
        compose.onNodeWithContentDescription("加入队列：等待二").assertIsNotEnabled()
        val old = runtime.state.value.activeTicket
        compose.onNodeWithContentDescription("暂停：等待一").performClick(); wait { it.phase == ExecutorPhase.PAUSED }
        compose.onNodeWithContentDescription("继续：等待一").performClick()
        compose.onNodeWithText("重新开始当前任务").performClick(); wait { it.phase == ExecutorPhase.RUNNING && it.activeTicket != old }
        screenshot("task-controls-portrait")
    }
    @Test fun longPressStopsOnlyTheCurrentAndDoesNotAlsoEnqueueAnotherRound() {
        show(); addBoth()
        compose.onNodeWithContentDescription("暂停：等待一").performTouchInput { longClick() }
        wait { it.phase == ExecutorPhase.PAUSED && it.current == null }
        assertEquals(listOf("two"), runtime.state.value.waiting.map { it.task.id })
        compose.onNodeWithText("工作台").performClick()
        compose.onNodeWithContentDescription("继续队列").performClick(); wait { it.current?.task?.id == "two" }
    }
    @Test fun landscapeSwipeRemovesCurrentAndTheNextRunsOnTheSameHost() {
        show(wide = true); addBoth(); compose.onNodeWithText("工作台").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText("等待一")))
            .performTouchInput { swipeRight() }
        wait { it.current?.task?.id == "two" && it.phase == ExecutorPhase.RUNNING }
        assertFalse(runtime.state.value.manualHold)
        screenshot("workbench-landscape-dark")
    }
    @Test fun failedInitialReadNeverLooksLikeAnEmptyQueue() {
        val empty = TaskExecutor().state.value
        compose.setContent { AssistTheme(ThemeMode.LIGHT) { Surface {
            ExecutionWorkbench(empty, RuntimeStatus.Failure("记录读取失败"), false, {}, { _, _ -> }, {}, {})
        } } }
        compose.onNodeWithText("队列为空").assertDoesNotExist()
        compose.onNodeWithText("执行记录暂不可用").assertIsDisplayed()
    }
    @Test fun leavingTheControlBeforeLongPressDoesNotFirePauseOrStop() {
        var pauses = 0; var stops = 0
        compose.setContent { AssistTheme(ThemeMode.LIGHT) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            RunActionButton("测试", true, false, true, {}, { pauses++ }, { stops++ })
        } } }
        compose.onNodeWithContentDescription("暂停：测试").performTouchInput {
            down(center); moveTo(Offset(-width * 2f, height * 2f)); advanceEventTime(1_000); up()
        }
        compose.runOnIdle { assertEquals(0, pauses); assertEquals(0, stops) }
    }
    @Test fun favoriteIconDoesNotOpenConfigurationOrSubmitARun() {
        var favorite: TaskAddress? = null; var edited: TaskAddress? = null
        val catalog = runBlocking { library.snapshot() }
        compose.setContent { val state by runtime.state.collectAsState(); AssistTheme(ThemeMode.LIGHT) { Surface {
            TaskCenter(VersionCatalogState.Ready(catalog), state, RuntimeStatus.Ready,
                { runtime.enqueueTask(it) }, {}, {}, { _, _ -> }, {}, {}, { edited = it },
                FloatingPreferencesState.Ready(FloatingSettings(true, emptySet(), null, null)), { favorite = it })
        } } }
        compose.onNodeWithContentDescription("设为常用：等待一").performClick()
        compose.runOnIdle {
            assertEquals(TaskAddress("fixture", "one"), favorite)
            assertNull(edited); assertNull(runtime.state.value.current)
            assertTrue(runtime.state.value.facts.none { it.type == "enqueued" })
        }
        compose.onNodeWithText("等待一").performClick()
        compose.runOnIdle { assertNull(edited) }
        compose.onNodeWithText("配置").performClick()
        compose.runOnIdle { assertEquals(TaskAddress("fixture", "one"), edited) }
    }
    private fun showList() {
        val catalog = runBlocking { library.snapshot() }
        compose.setContent { val state by runtime.state.collectAsState(); AssistTheme(ThemeMode.LIGHT) { Surface(Modifier.fillMaxSize()) {
            TaskCenter(VersionCatalogState.Ready(catalog), state, RuntimeStatus.Ready, { runtime.enqueueTask(it) }, {}, {}, { _, _ -> }, {}, {}, {},
                FloatingPreferencesState.Ready(FloatingSettings(true, setOf(TaskAddress("fixture", "two")), null, null)), {})
        } } }
    }
    @Test fun groupsCollapseAndSearchAndFavoritesNarrowTheList() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        showList()
        compose.onNodeWithText("工具").performClick()
        compose.onNodeWithText("等待一").assertDoesNotExist()
        compose.onNodeWithText("工具").performClick()
        compose.onNodeWithText("等待一").assertIsDisplayed()
        compose.onNodeWithText("常用").performClick()
        compose.onNodeWithText("等待一").assertDoesNotExist()
        compose.onNodeWithText("等待二").assertIsDisplayed()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithContentDescription("搜索任务").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("等待一")
        compose.onNodeWithText("等待二").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("没有这个任务")
        compose.onNodeWithText("没有符合条件的任务").assertIsDisplayed()
    }
    @Test fun statusBarOpensTheQueueAndStopsOnlyAfterConfirmation() {
        var opened = 0
        compose.setContent { val state by runtime.state.collectAsState(); val status by runtime.status.collectAsState()
            AssistTheme(ThemeMode.LIGHT) { Surface { if (runStatusVisible(state, status)) RunStatusBar(state, status, { opened++ },
                { runtime.submit(ExecutorCommand.Pause) }, { _, _ -> }, { state.current?.let { runtime.submit(ExecutorCommand.StopCurrent(it.runId)) } }) } }
        }
        compose.onNodeWithContentDescription("暂停运行").assertDoesNotExist()
        compose.runOnIdle { runtime.enqueueTask(TaskAddress("fixture", "one")) }; wait { it.current?.task?.id == "one" }
        compose.onNodeWithText("等待一").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithContentDescription("停止当前任务").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("one", runtime.state.value.current?.task?.id) }
        compose.onNodeWithContentDescription("停止当前任务").performClick()
        compose.onNodeWithText("停止").performClick()
        wait { it.current == null }
    }
}
