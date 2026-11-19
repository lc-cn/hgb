package io.github.yysassist.floating

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.*
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.device.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real accessibility window + real runtime/SQLite/preferences. No fixture touches a game or main DB. */
@RunWith(AndroidJUnit4::class)
class FloatingOverlayIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as AssistApplication
    private val component = "io.github.yysassist/io.github.yysassist.device.GameAccessibilityService"
    private var ui: UiAutomation? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var mainScenario: ActivityScenario<MainActivity>? = null
    private var database: AssistDatabase? = null
    private var scope: CoroutineScope? = null
    private var window: FloatingOverlayWindow? = null
    private lateinit var preferences: FloatingPreferences
    private lateinit var runtime: ExecutionRuntime
    private lateinit var library: TaskVersionLibrary
    private lateinit var bundle: TaskBundle
    private lateinit var service: GameAccessibilityService
    private lateinit var surface: TouchSurface
    private lateinit var dbName: String
    private lateinit var preferenceFile: java.io.File
    private var originalTheme: ThemeMode? = null
    private val ready = AtomicBoolean(true)
    private val blue = Color.rgb(32, 110, 160)
    private inner class TouchSurface(activity: ComponentActivity) : View(activity) {
        var downs = 0
        override fun onDraw(canvas: Canvas) { canvas.drawColor(blue) }
        override fun onTouchEvent(event: MotionEvent): Boolean { if (event.actionMasked == MotionEvent.ACTION_DOWN) downs++; return true }
    }
    private fun shell(command: String): String = ui!!.executeShellCommand(command).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
    }
    @Before fun open(): Unit = runBlocking {
        Assume.assumeTrue(Build.HARDWARE == "ranchu" && InstrumentationRegistry.getArguments().getString("allowDeviceGrantChanges") == "true")
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui!!.serviceInfo = ui!!.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val enabled = shell("settings get secure enabled_accessibility_services")
        check(enabled in listOf("null", "", component))
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(5_000) { while (GameAccessibilityService.connection.value != null) delay(20) }
        shell("settings put secure enabled_accessibility_services $component"); shell("settings put secure accessibility_enabled 1")
        service = withTimeout(10_000) { GameAccessibilityService.connection.filterNotNull().first() }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        withTimeout(10_000) { while (context.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) delay(20) }
        scenario!!.onActivity { surface = TouchSurface(it); it.setContentView(surface) }
        originalTheme = (withTimeout(5_000) { app.appearance.state.first { it is AppearanceState.Ready } } as AppearanceState.Ready).mode
        app.appearance.setTheme(ThemeMode.LIGHT)
        withTimeout(5_000) { app.appearance.state.first { it == AppearanceState.Ready(ThemeMode.LIGHT) } }
        val token = UUID.randomUUID().toString(); dbName = "floating-$token.db"
        database = AssistDatabase.open(context, dbName); val db = database!!
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        preferenceFile = context.preferencesDataStoreFile("floating-$token")
        val data = PreferenceDataStoreFactory.create(scope = scope!!, produceFile = { preferenceFile })
        preferences = FloatingPreferences(context, scope!!, data)
        val factory = BuiltInStepWorkFactory(); val codec = BundleCodec(factory.contracts); library = TaskVersionLibrary(db, codec)
        val tasks = listOf("one" to "等待一", "two" to "等待二").map { (id, name) ->
            val action = ActionDefinition("wait", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(60_000))))
            TaskDefinition(id, "floating-qa", name, "tools", 1, steps = listOf(StepDefinition("wait", "Wait", listOf(action))))
        }
        bundle = TaskBundle(1, "floating-v1", "2026-11-16T00:00:00Z", "Fixture", tasks,
            listOf(TargetProfile("floating-qa", "Fixture", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "Tools")))))
        val text = codec.write(bundle); library.install(text, bundle.version, TaskVersionLibrary.sha256(text)); library.activate(bundle.version)
        runtime = ExecutionRuntime(ExecutionStateStore(db, library), library, TaskAdmissionRepository(db, library) { _, _ -> null }, factory, scope!!,
            ExecutionContextReadiness { _, _ -> ready.get() })
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
        preferences.toggle(TaskAddress("floating-qa", "one")); preferences.toggle(TaskAddress("floating-qa", "two"))
        withTimeout(5_000) { preferences.state.first { it is FloatingPreferencesState.Ready && it.settings.favorites.size == 2 } }
        val catalog = library.catalog.map { VersionCatalogState.Ready(it) as VersionCatalogState }.stateIn(scope!!, SharingStarted.Eagerly, VersionCatalogState.Loading)
        val common = floatingTasks(preferences, catalog, runtime, scope!!)
        withContext(Dispatchers.Main) {
            service.floating?.close()
            // These scenarios drive the ball directly; idle docking has its own scenario.
            preferences.setAutoDock(false)
            withTimeout(5_000) { preferences.state.first { it is FloatingPreferencesState.Ready && !it.settings.autoDock } }
            window = FloatingOverlayWindow(service, app, runtime, preferences, common)
            service.bridge.obstruction = window
        }
        waitFor { window!!.isShowing && window!!.frameBounds().width() > 0 }
        delay(400)
    }
    @After fun close(): Unit = runBlocking {
        withContext(Dispatchers.Main) { window?.close(); if (::service.isInitialized) service.bridge.obstruction = null }
        mainScenario?.close(); scenario?.close()
        originalTheme?.let { app.appearance.setTheme(it) }
        scope?.cancel(); scope?.coroutineContext?.get(Job)?.join()
        database?.close(); if (::dbName.isInitialized) context.deleteDatabase(dbName)
        if (::preferenceFile.isInitialized) preferenceFile.delete()
    }
    private suspend fun waitFor(label: String = "condition", test: () -> Boolean) {
        val done = withTimeoutOrNull(8_000) { while (!withContext(Dispatchers.Main) { test() }) delay(20); true }
        if (done != true) throw AssertionError("$label; bounds=${withContext(Dispatchers.Main) { window?.frameBounds() }}; screen=${screenBounds()}; context=${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}; nodes=${allNodes().map { "${it.text}/${it.contentDescription}" }}")
    }
    private fun allNodes(type: Int = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY): List<AccessibilityNodeInfo> {
        fun descend(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) { result += node; repeat(node.childCount) { node.getChild(it)?.let { child -> descend(child, result) } } }
        return ui!!.windows.filter { it.type == type }.flatMap { w -> mutableListOf<AccessibilityNodeInfo>().also { list -> w.root?.let { descend(it, list) } } }
    }
    private suspend fun node(label: String, description: Boolean = true, type: Int = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY): AccessibilityNodeInfo {
        return withTimeoutOrNull(8_000) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) { found = allNodes(type).firstOrNull { it.isVisibleToUser && (if (description) it.contentDescription else it.text)?.toString() == label }; if (found == null) delay(20) }
            found
        } ?: throw AssertionError("Missing $label; windows=${ui!!.windows.map { it.type }}; nodes=${allNodes(type).map { "${it.text}/${it.contentDescription}" }}")
    }
    private suspend fun click(label: String, description: Boolean = true, type: Int = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
        var target = node(label, description, type)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("$label has an enabled click action", target.isClickable && target.isEnabled)
        assertTrue("$label click accepted", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private suspend fun common() {
        click("展开快捷控制"); click("常用")
        waitFor("common heading") { allNodes().any { it.isVisibleToUser && it.text?.toString() == "常用" && it.isHeading } }
        val wide = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = (if (wide) FloatingUi.wideCommonWidth.value else FloatingUi.quickWidth.value) * context.resources.displayMetrics.density
        val screen = screenBounds()
        waitFor("complete common window, expected width $width") { window!!.frameBounds().let { it.width() >= width.toInt() - 1 && screen.contains(it) } }
    }
    private fun pointer(action: Int, x: Float, y: Float, started: Long) {
        val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(ui!!.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private suspend fun screenshot(name: String) {
        ui!!.waitForIdle(500, 5_000)
        instrumentation.waitForIdleSync()
        withContext(Dispatchers.Main) { suspendCancellableCoroutine { continuation ->
            val clock = Choreographer.getInstance()
            clock.postFrameCallback { clock.postFrameCallback { if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> } } }
        } }
        val file = context.filesDir.resolve("qa/floating/$name.png"); file.parentFile!!.mkdirs()
        ui!!.takeScreenshot().useBitmap { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        file.resolveSibling("$name-geometry.txt").writeText(buildString {
            appendLine("view=${withContext(Dispatchers.Main) { window!!.frameBounds() }}")
            appendLine("service=${service.resources.displayMetrics}")
            ui!!.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.forEach { w ->
                val rect = Rect(); w.getBoundsInScreen(rect); appendLine("window=$rect")
            }
            allNodes().filter { it.isClickable }.forEach { n -> val rect = Rect(); n.getBoundsInScreen(rect); appendLine("${n.text}/${n.contentDescription}: $rect") }
        })
    }
    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
    // Raw overlay/window coordinates share the screenshot's origin, not a context's usable width.
    private fun screenBounds(): Rect = ui!!.takeScreenshot().useBitmap { Rect(0, 0, it.width, it.height) }

    @Test fun defaultBallIsRealAndScreenshotExcludesItThenRestoresIt(): Unit = runBlocking {
        val bounds = withContext(Dispatchers.Main) { window!!.frameBounds() }
        ui!!.takeScreenshot().useBitmap { assertNotEquals(blue, it.getPixel(bounds.centerX(), bounds.centerY())) }
        screenshot("ball-light-portrait")
        val frame = service.bridge.capture(context.packageName) as FrameResult.Ready
        frame.frame.use { assertEquals(blue, it.bitmap.getPixel(bounds.centerX(), bounds.centerY())) }
        waitFor { window!!.isShowing }
        assertTrue((preferences.state.value as FloatingPreferencesState.Ready).settings.visible)
    }
    @Test fun automaticTouchAtTheBallLocationReachesOnlyTheUnderlyingSurface(): Unit = runBlocking {
        runtime.enqueueTask(TaskAddress("floating-qa", "one")).await()
        val ticket = runtime.state.value.activeTicket!!
        val frame = (service.bridge.capture(context.packageName) as FrameResult.Ready).frame
        val bounds = withContext(Dispatchers.Main) { window!!.frameBounds() }
        val session = checkNotNull(service.bridge.openInput(ticket, runtime::permitsInput))
        try {
            frame.use { assertEquals(GestureResult.Completed, session.gesture(it.stamp,
                listOf(ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY())), 80)) }
            waitFor { surface.downs == 1 && window!!.isShowing }
            assertTrue(allNodes().none { it.text?.toString() == "常用" })
        } finally { session.revoke() }
    }
    @Test fun overlappingCaptureAndTouchExclusionsCannotRestoreTheWindowPrematurely(): Unit = runBlocking {
        val box = withContext(Dispatchers.Main) { window!!.frameBounds() }
        val captureReady = CompletableDeferred<java.io.Closeable>()
        val secondReady = CompletableDeferred<java.io.Closeable>()
        val touchReady = CompletableDeferred<java.io.Closeable>()
        val leases = withContext(Dispatchers.Main) {
            listOf(window!!.excludeCapture { captureReady.complete(it) },
                window!!.excludeCapture { secondReady.complete(it) },
                window!!.excludeGesture(listOf(ScreenPoint(box.exactCenterX(), box.exactCenterY()))) { touchReady.complete(it) })
        }
        try {
            withTimeout(5_000) { captureReady.await(); secondReady.await(); touchReady.await() }
            withContext(Dispatchers.Main) { leases[0].close(); assertFalse(window!!.isShowing) }
            withContext(Dispatchers.Main) { leases[1].close(); assertFalse(window!!.isShowing) }
            withContext(Dispatchers.Main) { leases[2].close() }
            waitFor { window!!.isShowing }
        } finally { withContext(Dispatchers.Main) { leases.forEach { it.close() } } }
    }
    @Test fun cancellingTheWorkerKeepsTheBallExcludedUntilTheGestureActuallyTerminates(): Unit = runBlocking {
        runtime.enqueueTask(TaskAddress("floating-qa", "one")).await()
        val ticket = runtime.state.value.activeTicket!!
        val frame = (service.bridge.capture(context.packageName) as FrameResult.Ready).frame
        val bounds = withContext(Dispatchers.Main) { window!!.frameBounds() }
        val session = checkNotNull(service.bridge.openInput(ticket, runtime::permitsInput))
        val worker = async { frame.use { session.gesture(it.stamp, listOf(ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY())), 1_500) } }
        try {
            waitFor { surface.downs == 1 }
            worker.cancelAndJoin()
            assertFalse(withContext(Dispatchers.Main) { window!!.isShowing })
            assertTrue(withTimeout(5_000) { session.revoke() } is InputDrain.Terminal)
            waitFor { window!!.isShowing }
            assertEquals(1, surface.downs)
        } finally { worker.cancelAndJoin(); session.revoke() }
    }
    @Test fun threeLayersControlTheSameQueueAndLongPressCannotEnqueueAgain(): Unit = runBlocking {
        common(); click("加入队列：等待一")
        waitFor { runtime.state.value.current?.task?.id == "one" }
        click("加入队列：等待二"); waitFor { runtime.state.value.waiting.size == 1 }
        waitFor { allNodes().none { it.contentDescription?.toString() == "加入队列：等待二" } }
        val old = runtime.state.value.activeTicket
        assertNotNull(old)
        click("暂停：等待一"); waitFor { runtime.state.value.phase == ExecutorPhase.PAUSED }
        click("继续：等待一"); click("重新开始", false)
        waitFor { runtime.state.value.phase == ExecutorPhase.RUNNING && runtime.state.value.activeTicket != old }
        val rect = Rect(); node("暂停：等待一").getBoundsInScreen(rect)
        val started = SystemClock.uptimeMillis(); pointer(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), started)
        delay(800); pointer(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), started)
        waitFor { runtime.state.value.current == null && runtime.state.value.phase == ExecutorPhase.PAUSED }
        assertEquals(listOf("two"), runtime.state.value.waiting.map { it.task.id })
        assertEquals(1, runtime.state.value.facts.count { it.type == "enqueued" && it.taskId == "one" })
        click("继续队列"); waitFor { runtime.state.value.current?.task?.id == "two" && runtime.state.value.phase == ExecutorPhase.RUNNING }
        node("暂停：等待二")
        screenshot("common-light-portrait")
    }
    @Test fun anIdleBallDocksToAThinEdgeTabAndATapWakesIt(): Unit = runBlocking {
        val ball = withContext(Dispatchers.Main) { window!!.frameBounds() }
        preferences.setAutoDock(true)
        val density = context.resources.displayMetrics.density
        waitFor("docked tab") { window!!.frameBounds().width() <= (FloatingUi.dockWidth.value * density).toInt() + 1 }
        val tab = withContext(Dispatchers.Main) { window!!.frameBounds() }
        assertTrue("tab is smaller than the ball", tab.width() * tab.height() < ball.width() * ball.height())
        assertEquals(screenBounds().right, tab.right)
        click("唤醒悬浮球")
        waitFor("awake ball") { window!!.frameBounds().width() >= (FloatingUi.ball.value * density).toInt() - 1 }
        node("展开快捷控制")
    }
    @Test fun commonRowsTrackFavoritesAndTheActiveVersionWithoutSubmittingOrCopyingTasks(): Unit = runBlocking {
        common(); node("加入队列：等待一"); node("加入队列：等待二")
        preferences.toggle(TaskAddress("floating-qa", "one"))
        waitFor { allNodes().none { it.contentDescription?.toString() == "加入队列：等待一" } }
        val updated = bundle.copy(version = "floating-v2", tasks = bundle.tasks.map { it.copy(name = "新版${it.name}") })
        val text = BundleCodec(BuiltInStepWorkFactory().contracts).write(updated)
        library.install(text, updated.version, TaskVersionLibrary.sha256(text)); library.activate(updated.version)
        node("加入队列：新版等待二")
        assertTrue(runtime.state.value.facts.none { it.type == "enqueued" })
        assertTrue(library.snapshot().versions.all { it.referenceCount == 0 })
        assertEquals(setOf(TaskAddress("floating-qa", "two")), (preferences.state.value as FloatingPreferencesState.Ready).settings.favorites)
    }
    @Test fun draggingDoesNotOpenAndRotationUsesAnIndependentClampedAnchor(): Unit = runBlocking {
        val box = withContext(Dispatchers.Main) { window!!.frameBounds() }
        val started = SystemClock.uptimeMillis(); pointer(MotionEvent.ACTION_DOWN, box.exactCenterX(), box.exactCenterY(), started)
        repeat(5) { i -> pointer(MotionEvent.ACTION_MOVE, box.exactCenterX() * (4 - i) / 5f, box.exactCenterY() * (4 - i) / 5f, started); delay(30) }
        pointer(MotionEvent.ACTION_UP, 0f, 0f, started)
        withTimeout(5_000) { preferences.state.first { it is FloatingPreferencesState.Ready && it.settings.portrait != null } }
        val portrait = (preferences.state.value as FloatingPreferencesState.Ready).settings.portrait!!
        assertEquals(0f, portrait.x, .001f); assertEquals(0f, portrait.y, .001f)
        assertTrue(allNodes().none { it.text?.toString() == "常用" })
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        withTimeout(8_000) { while (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) delay(20) }
        scenario!!.onActivity { surface = TouchSurface(it); it.setContentView(surface) }
        waitFor("landscape fixture layout") { surface.width > surface.height && surface.height > 0 }
        // This fixture owns a separate runtime/window; production forwards this service callback to its window.
        withContext(Dispatchers.Main) { window!!.configurationChanged(context.resources.configuration) }
        app.appearance.setTheme(ThemeMode.DARK)
        screenshot("landscape-ball-drag")
        val screen = screenBounds()
        waitFor { window!!.isShowing && screen.contains(window!!.frameBounds()) }
        common(); screenshot("common-dark-landscape")
        assertEquals(portrait, (preferences.state.value as FloatingPreferencesState.Ready).settings.portrait)
        click("收起悬浮面板")
        waitFor("collapsed landscape ball") { window!!.frameBounds().width() <= (FloatingUi.ball.value * context.resources.displayMetrics.density).toInt() + 1 }
        val landscapeBox = withContext(Dispatchers.Main) { window!!.frameBounds() }
        val landStarted = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, landscapeBox.exactCenterX(), landscapeBox.exactCenterY(), landStarted)
        pointer(MotionEvent.ACTION_MOVE, landscapeBox.exactCenterX() - 180, landscapeBox.exactCenterY() + 80, landStarted)
        pointer(MotionEvent.ACTION_UP, landscapeBox.exactCenterX() - 180, landscapeBox.exactCenterY() + 80, landStarted)
        withTimeout(5_000) { preferences.state.first { it is FloatingPreferencesState.Ready && it.settings.landscape != null } }
        assertEquals(portrait, (preferences.state.value as FloatingPreferencesState.Ready).settings.portrait)
        val landscape = (preferences.state.value as FloatingPreferencesState.Ready).settings.landscape
        assertNotEquals(portrait, landscape)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        withTimeout(8_000) { while (context.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) delay(20) }
        withContext(Dispatchers.Main) { window!!.configurationChanged(context.resources.configuration) }
        waitFor("portrait anchor restored") { window!!.frameBounds().right < context.resources.displayMetrics.widthPixels / 2 }
        assertEquals(landscape, (preferences.state.value as FloatingPreferencesState.Ready).settings.landscape)
    }
    @Test fun unknownStartConditionsKeepCurrentControlsAndHidingDoesNotPause(): Unit = runBlocking {
        runtime.enqueueTask(TaskAddress("floating-qa", "one")).await()
        waitFor { runtime.state.value.current != null }
        val ticket = runtime.state.value.activeTicket
        preferences.setVisible(false); waitFor { !window!!.isShowing }
        assertEquals(ticket, runtime.state.value.activeTicket)
        assertEquals(ExecutorPhase.RUNNING, runtime.state.value.phase)
        preferences.setVisible(true); waitFor { window!!.isShowing }
        ready.set(false); runtime.readinessChanged(); common()
        node("正在核对运行条件", false)
        click("暂停：等待一"); waitFor { runtime.state.value.phase == ExecutorPhase.PAUSED }
        preferences.setVisible(false); waitFor { !window!!.isShowing }
        assertTrue(runtime.state.value.manualHold)
        preferences.setVisible(true); waitFor { window!!.isShowing }
        click("继续：等待一"); click("继续", false)
        // This port rejects prerequisites before work starts; it has produced no business unknown.
        waitFor { runtime.status.value is RuntimeStatus.Waiting }
        assertTrue(runtime.state.value.unresolved.isEmpty())
        node("等待核对当前目标与账号", false)
        screenshot("waiting-light-portrait")
        assertNotEquals(ticket, runtime.state.value.activeTicket)
    }
    @Test fun returnAndConfigurationIntentsPreserveNavigationAndNeverSubmitWork(): Unit = runBlocking {
        common(); click("返回助手")
        waitFor { app.mainVisible.value && !window!!.isShowing }
        val before = app.executor.state.value.facts.count { it.type == "enqueued" }
        mainScenario = ActivityScenario.launch(MainActivity.taskIntent(context, TaskAddress("floating-qa", "one")))
        click("返回", type = AccessibilityWindowInfo.TYPE_APPLICATION)
        node("任务", false, AccessibilityWindowInfo.TYPE_APPLICATION)
        mainScenario!!.recreate()
        node("任务", false, AccessibilityWindowInfo.TYPE_APPLICATION)
        waitFor { allNodes(AccessibilityWindowInfo.TYPE_APPLICATION).none { it.isVisibleToUser && it.contentDescription?.toString() == "返回" } }
        assertEquals(before, app.executor.state.value.facts.count { it.type == "enqueued" })
    }
}
