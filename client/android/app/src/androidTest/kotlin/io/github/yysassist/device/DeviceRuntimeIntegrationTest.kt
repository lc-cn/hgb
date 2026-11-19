package io.github.yysassist.device

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.*
import io.github.yysassist.core.content.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real gesture admission through the production runtime/SQLite/bridge, only on our own emulator surface. */
@RunWith(AndroidJUnit4::class)
class DeviceRuntimeIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val component = "io.github.yysassist/io.github.yysassist.device.GameAccessibilityService"
    private var automation: UiAutomation? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var database: AssistDatabase? = null
    private var scope: CoroutineScope? = null
    private lateinit var name: String
    private lateinit var surface: TouchSurface
    private lateinit var bridge: AndroidDeviceBridge
    private val releaseStore = CompletableDeferred<Unit>()
    private fun shell(command: String): String = automation!!.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }
    private class TouchSurface(activity: ComponentActivity) : View(activity) {
        var downs = 0
        override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.rgb(32, 110, 160)) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) downs++
            return true
        }
    }
    @Before fun prepare(): Unit = runBlocking {
        Assume.assumeTrue("Explicit isolated emulator only", Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("allowDeviceGrantChanges") == "true")
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val enabled = shell("settings get secure enabled_accessibility_services")
        check(enabled == "null" || enabled.isEmpty() || enabled == component) { "Do not modify other accessibility services" }
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(5_000) { while (GameAccessibilityService.connection.value != null) delay(20) }
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        bridge = withTimeout(10_000) { GameAccessibilityService.connection.filterNotNull().first().bridge }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            surface = TouchSurface(activity); activity.setContentView(surface)
        }
        withTimeout(10_000) { while (withContext(Dispatchers.Main) { surface.width <= 0 || surface.width > surface.height }) delay(20) }
        delay(400)
        name = "device-runtime-${UUID.randomUUID()}.db"
        database = AssistDatabase.open(context, name)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    @After fun close(): Unit = runBlocking {
        releaseStore.complete(Unit)
        scope?.cancel(); scope?.coroutineContext?.get(Job)?.join()
        database?.close()
        if (::name.isInitialized) context.deleteDatabase(name)
        scenario?.close()
    }
    @Test fun pauseClosesRealInputBeforeItsStateCommitFinishes(): Unit = runBlocking {
        val db = database!!
        val captured = CompletableDeferred<Unit>()
        val tryInput = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<GestureResult>()
        val storeEntered = CompletableDeferred<Unit>()
        lateinit var runtime: ExecutionRuntime
        val factory = object : StepWorkFactory {
            override val contracts = listOf(CapabilityContract("fixture.touch", 1) { if (it.isEmpty()) null else "No fixture inputs" })
            override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle) = false
            override fun create(attempt: StepAttempt, bundle: TaskBundle): StepWork = object : StepWork {
                private var session: AndroidDeviceBridge.InputSession? = null
                @Volatile private var gesture: GestureResult? = null
                @Volatile private var attempted = false
                override suspend fun execute(): StepOutcome {
                    session = checkNotNull(bridge.openInput(attempt.ticket, runtime::permitsInput))
                    val result = bridge.capture(context.packageName)
                    check(result is FrameResult.Ready)
                    result.frame.use { frame ->
                        captured.complete(Unit); tryInput.await()
                        attempted = true
                        gesture = session!!.gesture(frame.stamp, listOf(ScreenPoint(frame.bitmap.width / 2f, frame.bitmap.height / 2f)), 80)
                        observed.complete(gesture!!)
                    }
                    awaitCancellation()
                }
                override suspend fun revoke(): InputStopObservation = when (session?.revoke()) {
                    InputDrain.Unconfirmed -> InputStopObservation.Unconfirmed("Fixture input unconfirmed")
                    else -> InputStopObservation.Quiet(if (gesture is GestureResult.Rejected || !attempted)
                        StepOutcome.Interrupted else StepOutcome.Unknown("Touch callback is not business evidence"))
                }
            }
        }
        val codec = BundleCodec(factory.contracts)
        val library = TaskVersionLibrary(db, codec)
        val action = ActionDefinition("touch", "fixture.touch", 1, JsonObject(emptyMap()))
        val task = TaskDefinition("touch", "fixture", "Touch fixture", "tools", 1,
            steps = listOf(StepDefinition("touch", "Touch", listOf(action))))
        val target = TargetProfile("fixture", "Own surface", listOf(context.packageName), IdentityPolicy.NONE,
            listOf(TaskCategory("tools", "Tools")))
        val bundle = TaskBundle(1, "device-qa", "2026-11-16T00:00:00Z", "Isolated touch fixture", listOf(task), listOf(target))
        val text = codec.write(bundle)
        library.install(text, bundle.version, TaskVersionLibrary.sha256(text)); library.activate(bundle.version)
        val store = ExecutionStateStore(db, library)
        val slowStore = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.snapshot.manualHold) { storeEntered.complete(Unit); releaseStore.await() }
                return store.persist(expectedRevision, prepared)
            }
        }
        runtime = ExecutionRuntime(slowStore, library, TaskAdmissionRepository(db, library), factory, scope!!)
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
        runtime.enqueueTask(TaskAddress("fixture", "touch")).await()
        withTimeout(5_000) { captured.await() }
        val pause = runtime.submit(ExecutorCommand.Pause)
        try {
            withTimeout(5_000) { storeEntered.await() }
            assertFalse("Uncommitted progress must not be published", runtime.state.value.manualHold)
            tryInput.complete(Unit)
            assertEquals("Pause must close input before waiting for SQLite", GestureResult.Rejected(DeviceRejection.NOT_OWNER),
                withTimeout(5_000) { observed.await() })
            assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
        } finally { releaseStore.complete(Unit) }
        pause.await()
        withTimeout(5_000) { runtime.state.first { it.phase == ExecutorPhase.PAUSED } }
        assertTrue(store.load().decode().state.value.manualHold)
    }

    @Test fun realInputWaitsForDurableIntentAndItsPlatformReceiptCannotCompleteBusinessWork(): Unit = runBlocking {
        val intentEntered = CompletableDeferred<Unit>(); val observed = CompletableDeferred<GestureResult>()
        lateinit var runtime: ExecutionRuntime
        val factory = object : StepWorkFactory {
            override val contracts = listOf(CapabilityContract("fixture.tap", 1) { if (it.isEmpty()) null else "No fixture arguments" })
            override fun restartHasNoDeviceInput(attempt: StepAttempt, bundle: TaskBundle) = false
            override fun create(attempt: StepAttempt, bundle: TaskBundle) = object : StepWork {
                private var session: AndroidDeviceBridge.InputSession? = null
                @Volatile private var attempted = false
                @Volatile private var result: GestureResult? = null
                override suspend fun execute(): StepOutcome {
                    val capture = bridge.capture(context.packageName); check(capture is FrameResult.Ready)
                    capture.frame.use { frame ->
                        session = checkNotNull(bridge.openInput(attempt.ticket, runtime::permitsInput))
                        val intent = DeviceActionIntent("fixture-real-tap", attempt.ticket, "tap", "fixture.tap", 1,
                            "frame:${frame.stamp.timestamp}")
                        val admission = runtime.submit(ExecutorCommand.RecordDeviceAction(intent)).await()
                        check(((admission as RuntimeCommandResult.Applied).reply as ExecutorReply.DeviceActionRecorded).result == DeviceActionWriteResult.Recorded)
                        attempted = true
                        result = session!!.gesture(frame.stamp, listOf(ScreenPoint(frame.bitmap.width / 2f, frame.bitmap.height / 2f)), 80)
                        val delivery = when (result) {
                            GestureResult.Completed -> DeviceActionDelivery.COMPLETED
                            GestureResult.Cancelled -> DeviceActionDelivery.CANCELLED
                            is GestureResult.Rejected -> DeviceActionDelivery.NOT_SENT
                            else -> DeviceActionDelivery.UNCONFIRMED
                        }
                        val receipt = runtime.submit(ExecutorCommand.RecordDeviceActionReceipt(attempt.ticket, intent.id,
                            DeviceActionReceipt(delivery, "自有画面的实际平台回执"))).await()
                        check(((receipt as RuntimeCommandResult.Applied).reply as ExecutorReply.DeviceActionRecorded).result == DeviceActionWriteResult.Recorded)
                        observed.complete(result!!)
                    }
                    awaitCancellation()
                }
                override suspend fun revoke(): InputStopObservation = when (session?.revoke()) {
                    InputDrain.Unconfirmed -> InputStopObservation.Unconfirmed("平台输入未确认静默")
                    else -> InputStopObservation.Quiet(if (!attempted || result is GestureResult.Rejected)
                        StepOutcome.Interrupted else StepOutcome.Unknown("平台回调不能证明业务结果"))
                }
            }
        }
        val codec = BundleCodec(factory.contracts)
        val library = TaskVersionLibrary(database!!, codec)
        val task = TaskDefinition("tap", "fixture", "自有点击夹具", "tools", 1, steps = listOf(
            StepDefinition("tap", "自有点击", listOf(ActionDefinition("tap", "fixture.tap", 1, JsonObject(emptyMap()))))))
        val target = TargetProfile("fixture", "自有画面", listOf(context.packageName), IdentityPolicy.NONE, listOf(TaskCategory("tools", "工具")))
        val bundle = TaskBundle(1, "intent-qa", "2026-11-17T00:00:00Z", "独立测试", listOf(task), listOf(target))
        val content = codec.write(bundle)
        library.install(content, bundle.version, TaskVersionLibrary.sha256(content)); library.activate(bundle.version)
        val store = ExecutionStateStore(database!!, library)
        val gated = object : ExecutionPersistence {
            override suspend fun load() = store.load()
            override suspend fun persist(expectedRevision: Long, prepared: PreparedExecutorTransition): Long {
                if (prepared.snapshot.deviceActions.any { it.receipt == null }) {
                    intentEntered.complete(Unit); releaseStore.await()
                }
                return store.persist(expectedRevision, prepared)
            }
        }
        runtime = ExecutionRuntime(gated, library, TaskAdmissionRepository(database!!, library), factory, scope!!)
        withTimeout(5_000) { runtime.status.first { it == RuntimeStatus.Ready } }
        try {
            runtime.enqueueTask(TaskAddress("fixture", "tap")).await()
            withTimeout(5_000) { intentEntered.await() }
            assertEquals(0, withContext(Dispatchers.Main) { surface.downs })
            assertFalse(observed.isCompleted); assertTrue(store.load().decode().state.value.deviceActions.isEmpty())
            releaseStore.complete(Unit)
            assertEquals(GestureResult.Completed, withTimeout(5_000) { observed.await() })
            assertEquals(1, withContext(Dispatchers.Main) { surface.downs })
            assertFalse(runtime.state.value.facts.any { it.type == "task-completed" || it.type == "step-completed" })
            assertEquals(DeviceActionDelivery.COMPLETED, store.load().decode().state.value.deviceActions.single().receipt!!.delivery)
            runtime.submit(ExecutorCommand.Pause).await()
            val unresolved = withTimeout(5_000) { runtime.state.first { it.phase == ExecutorPhase.BLOCKED_UNKNOWN } }
            assertTrue(unresolved.manualHold); assertEquals(1, unresolved.unresolved.size)
            assertEquals(1, library.snapshot().versions.single().referenceCount)
        } finally { releaseStore.complete(Unit) }
    }
}
