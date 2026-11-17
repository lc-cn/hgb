package io.github.yysassist.device

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.os.Build
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Physical Android screenshot/gesture callbacks against our own touch fixture, never a game. */
@RunWith(AndroidJUnit4::class)
class AndroidDeviceBridgeTest {
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var surface: TouchSurface
    private lateinit var service: GameAccessibilityService
    private lateinit var executor: TaskExecutor
    private lateinit var ticket: AttemptTicket
    private var session: AndroidDeviceBridge.InputSession? = null
    private var ui: UiAutomation? = null
    private val trace = mutableListOf<String>()
    private val component = "io.github.yysassist/io.github.yysassist.device.GameAccessibilityService"
    private fun shell(command: String): String = ui!!.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }

    private class TouchSurface(activity: ComponentActivity) : View(activity) {
        val touches = mutableListOf<Pair<Int, Long>>()
        override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.rgb(30, 110, 190)) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touches += event.actionMasked to SystemClock.uptimeMillis()
            return true
        }
    }
    @Before fun prepare() = runBlocking {
        Assume.assumeTrue("Grant changes are restricted to explicitly opted-in emulator QA", Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("allowDeviceGrantChanges") == "true")
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val enabled = shell("settings get secure enabled_accessibility_services")
        check(enabled == "null" || enabled.isEmpty() || enabled == component) { "Fixture must not change other accessibility services" }
        // am instrument force-stops the target and marks its formerly bound service as crashed.
        // Rebind only this test fixture after the instrumentation process is already alive.
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(5_000) { while (GameAccessibilityService.connection.value != null) delay(20) }
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        service = withTimeout(10_000) { GameAccessibilityService.connection.filterNotNull().first() }
        mark("test=${testName.methodName}")
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        withTimeout(10_000) { while (instrumentation.targetContext.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) delay(30) }
        scenario.onActivity { surface = TouchSurface(it); it.setContentView(surface) }
        withTimeout(10_000) { while (withContext(Dispatchers.Main) { surface.width == 0 || surface.width > surface.height }) delay(30) }
        // Respect the platform's capture interval in this test; production does not retry requests.
        delay(400)
        executor = TaskExecutor(ExecutionContext("fixture", Principal.Anonymous))
        val response = executor.enqueue("test", ExecutionRequest(SubmissionRef(SubmissionKind.TASK, "touch"),
            ExecutionContext("fixture", Principal.Anonymous), "fixture-v1", listOf(TaskSnapshot("touch", "受控触摸", 1, listOf("observe")))))
        ticket = (response.effects.single() as ExecutorEffect.ExecuteStep).attempt.ticket
    }
    @After fun cleanup() = runBlocking {
        session?.let { withTimeout(5_000) { it.revoke() } }
        if (::surface.isInitialized) withContext(Dispatchers.Main) {
            surface.touches.forEach { (action, time) -> trace += "consumer-motion-action=$action $time" }
        }
        if (::scenario.isInitialized) scenario.close()
        val file = instrumentation.targetContext.filesDir.resolve("qa/device-bridge/trace.txt")
        file.parentFile!!.mkdirs()
        file.appendText(trace.joinToString("\n", postfix = "\n"))
    }
    private suspend fun frame(): ScreenFrame {
        val result = service.bridge.capture(instrumentation.targetContext.packageName)
        assertTrue(result.toString(), result is FrameResult.Ready)
        return (result as FrameResult.Ready).frame
    }
    @Test fun availabilityUsesFocusedApplicationAndDetectsDisplayPowerWithoutTakingAScreenshot(): Unit = runBlocking {
        val packages = setOf(instrumentation.targetContext.packageName)
        withTimeout(5_000) { while (service.bridge.availability(packages) != DeviceAvailability.READY) delay(20) }
        assertEquals(DeviceAvailability.WRONG_FOREGROUND, service.bridge.availability(setOf("fixture.other.app")))
        val before = GameAccessibilityService.sceneRevision.value
        try {
            shell("input keyevent KEYCODE_SLEEP")
            withTimeout(5_000) { while (service.bridge.availability(packages) != DeviceAvailability.DISPLAY_UNAVAILABLE) delay(20) }
            withTimeout(5_000) { GameAccessibilityService.sceneRevision.first { it > before } }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        }
        withTimeout(5_000) { while (service.bridge.availability(packages) != DeviceAvailability.READY) delay(20) }
        assertTrue(surface.touches.isEmpty())
    }
    private suspend fun input(): AndroidDeviceBridge.InputSession =
        service.bridge.openInput(ticket, executor::permitsInput)!!.also { session = it }
    private suspend fun center(): ScreenPoint = withContext(Dispatchers.Main) {
        val origin = IntArray(2); surface.getLocationOnScreen(origin)
        ScreenPoint(origin[0] + surface.width * .5f, origin[1] + surface.height * .5f)
    }
    private suspend fun waitDown() = withTimeout(2_000) {
        while (withContext(Dispatchers.Main) { surface.touches.none { it.first == MotionEvent.ACTION_DOWN } }) delay(5)
    }
    private suspend fun touchCount(action: Int) = withContext(Dispatchers.Main) { surface.touches.count { it.first == action } }
    private fun mark(label: String) { trace += "$label ${SystemClock.uptimeMillis()}" }

    @Test fun readableCaptureAndRealTapHaveIndependentResourceAndInputLifetimes() = runBlocking {
        val capture = frame()
        val point = center()
        assertEquals(Color.rgb(30, 110, 190), capture.bitmap.getPixel(point.x.toInt(), point.y.toInt()))
        val stamp = capture.stamp
        capture.close()
        assertTrue(capture.bitmap.isRecycled)
        val channel = input()
        assertEquals(GestureResult.Completed, channel.gesture(stamp, listOf(point), 80))
        assertEquals(1, touchCount(MotionEvent.ACTION_DOWN))
        assertEquals(1, touchCount(MotionEvent.ACTION_UP))
        assertEquals(InputDrain.NoInFlightGesture, channel.revoke())
        mark("capture-and-tap-terminal")
    }
    @Test fun concurrentCapturesRespectPlatformPacingAndReturnIndependentFrames(): Unit = runBlocking {
        val results = coroutineScope { List(3) { async(Dispatchers.Default) { service.bridge.capture(instrumentation.targetContext.packageName) } }.awaitAll() }
        val frames = results.filterIsInstance<FrameResult.Ready>().map { it.frame }
        try {
            assertEquals(results.toString(), 3, frames.size)
            assertNotSame(frames[0].bitmap, frames[1].bitmap); assertNotSame(frames[1].bitmap, frames[2].bitmap)
            assertEquals(3, frames.map { it.stamp.timestamp }.distinct().size)
            val point = center()
            frames.forEach { assertEquals(Color.rgb(30, 110, 190), it.bitmap.getPixel(point.x.toInt(), point.y.toInt())) }
        } finally { frames.forEach { it.close() } }
    }
    @Test fun completedCallRetainsItsReceiptAfterTheChannelHasNoInFlightGesture(): Unit = runBlocking {
        frame().use { capture ->
            val channel = input()
            val call = channel.gestureCall(capture.stamp, listOf(center()), 80)
            assertEquals(GestureResult.Completed, call.execute())
            assertEquals(InputDrain.NoInFlightGesture, channel.revoke())
            assertEquals(GestureResult.Completed, call.observeAfterRevoke())
            assertTrue(runCatching { call.execute() }.isFailure)
        }
    }
    @Test fun aCallCancelledBeforeExecuteHasNoPlatformSubmission(): Unit = runBlocking {
        frame().use { capture ->
            val channel = input(); val call = channel.gestureCall(capture.stamp, listOf(center()), 80)
            assertEquals(InputDrain.NoInFlightGesture, channel.revoke())
            assertNull(call.observeAfterRevoke())
            assertEquals(0, touchCount(MotionEvent.ACTION_DOWN))
        }
    }
    @Test fun pauseClosesAdmissionAndWaitsForThePlatformTerminalCallback() = runBlocking {
        frame().use { capture ->
            val channel = input(); val point = center()
            val gesture = async(Dispatchers.Main.immediate) { channel.gesture(capture.stamp, listOf(point, point.copy(x = point.x + 50)), 800) }
            waitDown(); mark("pause-requested")
            withContext(Dispatchers.Main) { executor.pause() }
            val drain = async(Dispatchers.Main.immediate) { channel.revoke() }
            yield()
            assertFalse("An active stroke must not be reported quiet by cancellation", drain.isCompleted)
            assertEquals(GestureResult.Rejected(DeviceRejection.NOT_OWNER), channel.gesture(capture.stamp, listOf(point), 50))
            assertEquals(InputDrain.Terminal(GestureResult.Completed), drain.await())
            val quietAt = SystemClock.uptimeMillis()
            mark("pause-channel-quiet")
            assertEquals(GestureResult.Completed, gesture.await())
            assertEquals(1, touchCount(MotionEvent.ACTION_DOWN))
            withTimeout(2_000) { while (touchCount(MotionEvent.ACTION_UP) == 0) delay(5) }
            val upAt = withContext(Dispatchers.Main) { surface.touches.single { it.first == MotionEvent.ACTION_UP }.second }
            // Platform completion follows injection, not execution of the target View's event handler.
            trace += "consumer-terminal-at=$upAt platform-quiet-at=$quietAt"
            assertEquals(ExecutorPhase.PAUSING, executor.state.value.phase)
            // This fixture has no business side effect; only now can the host acknowledge quiet.
            executor.inputQuiescent(ticket, StepOutcome.Interrupted)
            assertEquals(ExecutorPhase.PAUSED, executor.state.value.phase)
        }
    }
    @Test fun cancellingAWorkerCannotReleaseItsInFlightGesture() = runBlocking {
        frame().use { capture ->
            val channel = input(); val point = center()
            val worker = launch(Dispatchers.Main.immediate) { channel.gesture(capture.stamp, listOf(point), 800) }
            waitDown(); worker.cancelAndJoin(); mark("worker-cancelled")
            assertNull(service.bridge.openInput(AttemptTicket(99, 99)) { true })
            assertEquals(GestureResult.Rejected(DeviceRejection.BUSY), channel.gesture(capture.stamp, listOf(point), 50))
            assertEquals(InputDrain.Terminal(GestureResult.Completed), channel.revoke())
            mark("cancelled-worker-channel-quiet")
            assertEquals(1, touchCount(MotionEvent.ACTION_DOWN))
        }
    }
    @Test fun rotatedFrameCannotDispatchCoordinatesFromThePreviousScreen() = runBlocking {
        frame().use { capture ->
            val point = center(); val channel = input()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            withTimeout(10_000) { while (instrumentation.targetContext.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) delay(30) }
            scenario.onActivity { surface = TouchSurface(it); it.setContentView(surface) }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { surface.width < surface.height }) delay(30) }
            assertEquals(GestureResult.Rejected(DeviceRejection.SCREEN_CHANGED), channel.gesture(capture.stamp, listOf(point), 50))
            assertEquals(0, touchCount(MotionEvent.ACTION_DOWN))
            mark("rotation-rejected-old-frame")
        }
    }
    @Test fun anotherForegroundIsNeitherCapturedNorTouched() = runBlocking {
        assertEquals(FrameResult.Unavailable(DeviceRejection.WRONG_FOREGROUND), service.bridge.capture("invalid.fixture"))
        frame().use { capture ->
            val point = center(); val channel = input()
            scenario.moveToState(Lifecycle.State.CREATED)
            delay(200)
            assertEquals(GestureResult.Rejected(DeviceRejection.WRONG_FOREGROUND), channel.gesture(capture.stamp, listOf(point), 50))
            assertEquals(0, touchCount(MotionEvent.ACTION_DOWN))
            mark("wrong-foreground-rejected")
        }
    }
    @Test fun permissionLossRejectsInputAndAReconnectionInvalidatesEarlierFrames() = runBlocking {
        frame().use { capture ->
            val point = center()
            val oldBridge = service.bridge
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(5_000) { GameAccessibilityService.connection.filter { it == null }.first() }
            assertEquals(FrameResult.Unavailable(DeviceRejection.DISCONNECTED), oldBridge.capture(instrumentation.targetContext.packageName))
            assertNull(oldBridge.openInput(ticket, executor::permitsInput))
            shell("settings put secure enabled_accessibility_services $component")
            val rebound = withTimeout(10_000) { GameAccessibilityService.connection.filterNotNull().first() }
            val next = rebound.bridge.openInput(ticket, executor::permitsInput)!!.also { session = it }
            assertEquals(GestureResult.Rejected(DeviceRejection.SCREEN_CHANGED), next.gesture(capture.stamp, listOf(point), 50))
            assertEquals(0, touchCount(MotionEvent.ACTION_DOWN))
            mark("permission-loss-and-stale-connection-rejected")
        }
    }
    @Test fun losingTheServiceDuringInputDoesNotInventAQuietAcknowledgement() = runBlocking {
        frame().use { capture ->
            val point = center(); val channel = input()
            val worker = async(Dispatchers.Main.immediate) { channel.gesture(capture.stamp, listOf(point), 2_000) }
            waitDown(); mark("permission-revocation-requested")
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(5_000) { GameAccessibilityService.connection.filter { it == null }.first() }
            val result = withTimeout(5_000) { worker.await() }
            val drain = withTimeout(5_000) { channel.revoke() }
            if (result == GestureResult.Unconfirmed) assertEquals(InputDrain.Unconfirmed, drain)
            else {
                assertTrue("Only a real terminal callback may resolve a gesture before disconnection", result == GestureResult.Completed || result == GestureResult.Cancelled)
                assertEquals(InputDrain.NoInFlightGesture, drain)
            }
            assertNull(service.bridge.openInput(AttemptTicket(99, 99)) { true })
            assertEquals(GestureResult.Rejected(DeviceRejection.DISCONNECTED), channel.gesture(capture.stamp, listOf(point), 50))
            mark("permission-loss-result=$result drain=$drain")
        }
    }
    @Test fun cancellationBeforeAnAcquiredSessionIsDeliveredReleasesThatUnusedSession() = runBlocking {
        val reachedMain = CountDownLatch(1)
        val callerBlocked = CountDownLatch(1)
        val releaseCaller = CountDownLatch(1)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            val worker = launch(caller) {
                service.bridge.openInput(ticket) {
                    reachedMain.countDown()
                    check(callerBlocked.await(3, TimeUnit.SECONDS))
                    true
                }
            }
            caller.executor.execute { callerBlocked.countDown(); check(releaseCaller.await(5, TimeUnit.SECONDS)) }
            try {
                assertTrue(reachedMain.await(3, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { } // acquisition finished; caller cannot receive it yet
                worker.cancel()
            } finally { releaseCaller.countDown() }
            withTimeout(5_000) { worker.join() }
            instrumentation.runOnMainSync { }
            val next = service.bridge.openInput(ticket, executor::permitsInput).also { session = it }
            assertNotNull("Cancellation must not orphan an unused device input session", next)
            assertEquals(0, touchCount(MotionEvent.ACTION_DOWN))
            mark("cancelled-session-acquisition-released")
        }
    }
}
