package io.github.yysassist.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import io.github.yysassist.core.execution.AttemptTicket
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resumeWithException

internal data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int)
internal data class ScreenPoint(val x: Float, val y: Float)
internal class FrameStamp internal constructor(
    internal val connection: Any, val geometry: ScreenGeometry, val packageName: String?, val timestamp: Long,
)

/** Readable pixels belong to one observer and never leave memory unless explicitly exported. */
internal class ScreenFrame(val bitmap: Bitmap, val stamp: FrameStamp) : Closeable {
    override fun close() { bitmap.recycle() }
}
internal enum class DeviceRejection { DISCONNECTED, WRONG_FOREGROUND, SCREEN_CHANGED, NOT_OWNER, BUSY, INVALID_GESTURE, PLATFORM_REJECTED, DECODE_FAILED }
internal enum class DeviceAvailability { READY, DISCONNECTED, WRONG_FOREGROUND, DISPLAY_UNAVAILABLE, UNCONFIRMED }
internal sealed interface FrameResult {
    data class Ready(val frame: ScreenFrame) : FrameResult
    data class Unavailable(val reason: DeviceRejection, val platformCode: Int? = null) : FrameResult
}
/** Read-only view of the one device owner; it grants no gesture authority. */
internal interface ObservationDevice {
    suspend fun capture(expectedPackage: String): FrameResult
    suspend fun validateObservation(stamp: FrameStamp): DeviceRejection?
}
internal sealed interface GestureResult {
    data object Completed : GestureResult
    /** A cancelled gesture may already have delivered some touch events. */
    data object Cancelled : GestureResult
    data class Rejected(val reason: DeviceRejection) : GestureResult
    data object Unconfirmed : GestureResult
}
internal sealed interface InputDrain {
    data object NoInFlightGesture : InputDrain
    data class Terminal(val gesture: GestureResult) : InputDrain
    data object Unconfirmed : InputDrain
}
/** A call handle is created before execute(), and retains platform evidence after caller cancellation. */
internal interface InputGestureCall {
    suspend fun execute(): GestureResult
    /** Only after closing the channel. Null means this call never reached platform dispatch. */
    suspend fun observeAfterRevoke(): GestureResult?
}
internal interface DeviceInputChannel {
    fun gestureCall(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long): InputGestureCall
    suspend fun revoke(): InputDrain
}
internal interface InputDevicePort : ObservationDevice {
    suspend fun openInput(ticket: AttemptTicket, permitsInput: (AttemptTicket) -> Boolean): DeviceInputChannel?
}

/**
 * Main-thread device owner. Worker cancellation does not cancel or acknowledge a platform gesture.
 * Revocation closes admission immediately, then drains the independently retained platform callback.
 * Neither a terminal callback nor a quiet channel establishes the business result of a click.
 */
internal class AndroidDeviceBridge(private val service: AccessibilityService) : InputDevicePort {
    private class ScreenshotRequest(val connection: Any)
    companion object {
        // AOSP rejects intervals <=1000ms on Android 11, <=333ms from Android 12.
        // Adapter pacing, not task retry/timeout; the SDK field is hidden. See observation-actions.md.
        private val SCREENSHOT_GAP_MS = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) 1_001L else 334L
        private val screenshotGate = Mutex()
        // Main Looper owns these fields. Tokens contain no Service or bitmap references.
        private var screenshotPending: ScreenshotRequest? = null
        private var screenshotAfterUptime = 0L
    }
    private var connection = Any()
    private var connected = false
    private var owner: InputSession? = null
    internal var obstruction: DeviceUiObstruction? = null
    private class InFlight(val uiLease: Closeable, val result: CompletableDeferred<GestureResult> = CompletableDeferred())

    fun connected() { mainThread(); connection = Any(); connected = true }
    fun disconnected() {
        mainThread()
        connected = false
        // Do not release the channel or manufacture a terminal callback after service loss.
        owner?.connectionLost()
    }

    override suspend fun capture(expectedPackage: String): FrameResult = captureScreen(expectedPackage)
    suspend fun checkScreen(): FrameResult = captureScreen(null)
    /** Query app focus, not the last touched overlay. Potential remote node lookup stays off Main. */
    suspend fun availability(expectedPackages: Set<String>): DeviceAvailability {
        val epoch = withContext(Dispatchers.Main.immediate) { connection.takeIf { connected } }
            ?: return DeviceAvailability.DISCONNECTED
        val focusedPackage = withContext(Dispatchers.IO) { focusedApplicationPackage() }
        return withContext(Dispatchers.Main.immediate) {
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            when {
                !connected || connection !== epoch -> DeviceAvailability.DISCONNECTED
                display == null -> DeviceAvailability.UNCONFIRMED
                display.state != Display.STATE_ON -> DeviceAvailability.DISPLAY_UNAVAILABLE
                focusedPackage == null -> DeviceAvailability.UNCONFIRMED
                focusedPackage !in expectedPackages -> DeviceAvailability.WRONG_FOREGROUND
                else -> DeviceAvailability.READY
            }
        }
    }
    @Suppress("DEPRECATION") // Return owned window/node instances on older supported Android versions.
    private fun focusedApplicationPackage(): String? {
        val windows = try { service.windows } catch (_: SecurityException) { return null }
        try {
            val focused = windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused } ?: return null
            val root = try { focused.root } catch (_: SecurityException) { return null } ?: return null
            return try { root.packageName?.toString() } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
    }
    /** Revalidate ownership/geometry, not the continued presence of pixels seen in the old image. */
    override suspend fun validateObservation(stamp: FrameStamp): DeviceRejection? = withContext(Dispatchers.Main.immediate) {
        when {
            !connected -> DeviceRejection.DISCONNECTED
            stamp.connection !== connection || stamp.geometry != geometry() -> DeviceRejection.SCREEN_CHANGED
            stamp.packageName == null || foreground() != stamp.packageName -> DeviceRejection.WRONG_FOREGROUND
            else -> null
        }
    }

    private suspend fun captureScreen(expectedPackage: String?): FrameResult = screenshotGate.withLock {
        val remaining = withContext(Dispatchers.Main.immediate) {
            if (screenshotPending?.connection === connection && connected) null
            else (screenshotAfterUptime - SystemClock.uptimeMillis()).coerceAtLeast(0)
        } ?: return@withLock FrameResult.Unavailable(DeviceRejection.BUSY)
        if (remaining > 0) delay(remaining)
        // No dispatcher handoff after bitmap delivery: the original cancellation release remains
        // attached to the caller receiving the frame. Waiting happens before hiding the overlay.
        captureNow(expectedPackage)
    }

    private suspend fun captureNow(expectedPackage: String?): FrameResult = suspendCancellableCoroutine { continuation ->
        service.mainExecutor.execute {
            if (!continuation.isActive) return@execute
            fun begin(lease: Closeable) {
                if (!continuation.isActive) { lease.close(); return }
                try { requestCapture(expectedPackage, continuation, lease) }
                catch (_: SecurityException) { lease.close(); deliverFrame(continuation, FrameResult.Unavailable(DeviceRejection.DISCONNECTED)) }
                catch (error: Exception) { lease.close(); continuation.resumeWithException(error) }
            }
            try {
                val lease = obstruction?.excludeCapture(::begin) ?: Closeable {}.also(::begin)
                continuation.invokeOnCancellation { service.mainExecutor.execute { lease.close() } }
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }
    private fun completeCapture(continuation: CancellableContinuation<FrameResult>, lease: Closeable, value: FrameResult) {
        lease.close(); deliverFrame(continuation, value)
    }

    private fun deliverFrame(continuation: CancellableContinuation<FrameResult>, value: FrameResult) {
        // Resume directly into the caller: an outer withContext could discard a delivered bitmap
        // at a second cancellation boundary without invoking this resource release handler.
        continuation.resume(value) { _, dropped, _ -> (dropped as? FrameResult.Ready)?.frame?.close() }
    }

    private fun requestCapture(expectedPackage: String?, continuation: CancellableContinuation<FrameResult>, lease: Closeable) {
        mainThread()
        if (!connected) { completeCapture(continuation, lease, FrameResult.Unavailable(DeviceRejection.DISCONNECTED)); return }
        if (expectedPackage != null && foreground() != expectedPackage) {
            completeCapture(continuation, lease, FrameResult.Unavailable(DeviceRejection.WRONG_FOREGROUND)); return
        }
        val requestedConnection = connection
        val before = geometry()
        if (before == null) { completeCapture(continuation, lease, FrameResult.Unavailable(DeviceRejection.DISCONNECTED)); return }
        val request = ScreenshotRequest(requestedConnection)
        screenshotPending = request
        screenshotAfterUptime = SystemClock.uptimeMillis() + SCREENSHOT_GAP_MS
        fun terminal() {
            if (screenshotPending === request) {
                screenshotPending = null
                if (connected && connection === requestedConnection)
                    screenshotAfterUptime = SystemClock.uptimeMillis() + SCREENSHOT_GAP_MS
            }
        }
        try { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        terminal()
                        var hardware: Bitmap? = null
                        var pixels: Bitmap? = null
                        val value = try {
                            if (!connected || connection !== requestedConnection) FrameResult.Unavailable(DeviceRejection.DISCONNECTED)
                            else if (geometry() != before) FrameResult.Unavailable(DeviceRejection.SCREEN_CHANGED)
                            else if (expectedPackage != null && foreground() != expectedPackage) FrameResult.Unavailable(DeviceRejection.WRONG_FOREGROUND)
                            else {
                                hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                pixels = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                val image = pixels
                                if (image == null) FrameResult.Unavailable(DeviceRejection.DECODE_FAILED)
                                else if (image.width != before.width || image.height != before.height) FrameResult.Unavailable(DeviceRejection.SCREEN_CHANGED)
                                else FrameResult.Ready(ScreenFrame(image, FrameStamp(requestedConnection, before, expectedPackage, result.timestamp)))
                            }
                        } catch (_: IllegalArgumentException) { FrameResult.Unavailable(DeviceRejection.DECODE_FAILED) }
                        finally { hardware?.recycle(); result.hardwareBuffer.close() }
                        if (value !is FrameResult.Ready) pixels?.recycle()
                        completeCapture(continuation, lease, value)
                    }
                    override fun onFailure(errorCode: Int) {
                        terminal()
                        completeCapture(continuation, lease, FrameResult.Unavailable(DeviceRejection.PLATFORM_REJECTED, errorCode))
                    }
        }) } catch (error: Exception) { terminal(); throw error }
    }

    override suspend fun openInput(ticket: AttemptTicket, permitsInput: (AttemptTicket) -> Boolean): InputSession? =
        suspendCancellableCoroutine { continuation ->
            service.mainExecutor.execute {
                if (!continuation.isActive) return@execute
                try {
                    val acquired = if (!connected || owner != null || !permitsInput(ticket)) null
                        else InputSession(ticket, permitsInput).also { owner = it }
                    // Ownership crosses a cancellable dispatcher boundary. If the caller never receives
                    // the value, release this unused lease here; it cannot have dispatched any input.
                    continuation.resume(acquired) { _, dropped, _ ->
                        service.mainExecutor.execute { dropped?.discardUnpublished() }
                    }
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        }

    internal inner class InputSession internal constructor(val ticket: AttemptTicket, private val permitsInput: (AttemptTicket) -> Boolean) : DeviceInputChannel {
        private val admittedConnection = connection
        internal var closed = false
        private var pending: InFlight? = null
        private val active: InFlight? get() = pending
        internal fun discardUnpublished() {
            mainThread()
            check(pending == null)
            closed = true
            if (owner === this) owner = null
        }
        internal fun connectionLost() {
            closed = true
            val flight = pending
            if (flight == null) { if (owner === this) owner = null }
            else flight.result.complete(GestureResult.Unconfirmed)
        }
        // The bridge alone completes this record; it is deliberately not a child of the worker Job.
        private fun terminal(record: InFlight, result: GestureResult) {
            mainThread()
            if (pending !== record || !connected || connection !== admittedConnection) return
            pending = null
            record.uiLease.close()
            record.result.complete(result)
            if (closed && owner === this) owner = null
        }

        private fun rejection(stamp: FrameStamp, points: List<ScreenPoint>, duration: Long): DeviceRejection? {
            mainThread()
            return when {
                !connected || connection !== admittedConnection -> DeviceRejection.DISCONNECTED
                closed || owner !== this || !permitsInput(ticket) -> DeviceRejection.NOT_OWNER
                pending != null -> DeviceRejection.BUSY
                stamp.connection !== connection || stamp.geometry != geometry() -> DeviceRejection.SCREEN_CHANGED
                stamp.packageName == null || foreground() != stamp.packageName -> DeviceRejection.WRONG_FOREGROUND
                duration !in 1..GestureDescription.getMaxGestureDuration() || points.isEmpty() || points.any {
                    !it.x.isFinite() || !it.y.isFinite() || it.x < 0 || it.y < 0 || it.x >= stamp.geometry.width || it.y >= stamp.geometry.height
                } -> DeviceRejection.INVALID_GESTURE
                else -> null
            }
        }
        private suspend fun prepareGesture(stamp: FrameStamp, points: List<ScreenPoint>, duration: Long): GesturePreparation =
            suspendCancellableCoroutine { continuation ->
                service.mainExecutor.execute {
                    if (!continuation.isActive) return@execute
                    try {
                        val reason = rejection(stamp, points, duration)
                        if (reason != null) { continuation.resume(GesturePreparation.Rejected(reason)) { _, _, _ -> }; return@execute }
                        val ready: (Closeable) -> Unit = { lease ->
                            continuation.resume(GesturePreparation.Ready(lease)) { _, dropped, _ ->
                                service.mainExecutor.execute { (dropped as? GesturePreparation.Ready)?.lease?.close() }
                            }
                        }
                        val lease = obstruction?.excludeGesture(points, ready) ?: Closeable {}.also(ready)
                        continuation.invokeOnCancellation { service.mainExecutor.execute { lease.close() } }
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }
        override fun gestureCall(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long): InputGestureCall =
            GestureCall(stamp, points.toList(), durationMillis)

        private inner class GestureCall(val stamp: FrameStamp, val points: List<ScreenPoint>, val duration: Long) : InputGestureCall {
            var submission: InFlight? = null // Main Looper owns this retained evidence, not the worker Job.
            private var started = false
            override suspend fun execute(): GestureResult {
                synchronized(this) { check(!started) { "手势调用不可重复执行" }; started = true }
                return performGesture(stamp, points, duration, this)
            }
            override suspend fun observeAfterRevoke(): GestureResult? {
                val record = withContext(Dispatchers.Main.immediate) { check(closed) { "须先关闭输入通道" }; submission }
                return record?.result?.await()
            }
        }

        suspend fun gesture(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long): GestureResult =
            gestureCall(stamp, points, durationMillis).execute()

        private suspend fun performGesture(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long, call: GestureCall): GestureResult {
            val pathPoints = points.toList()
            val preparation = prepareGesture(stamp, pathPoints, durationMillis)
            if (preparation is GesturePreparation.Rejected) return GestureResult.Rejected(preparation.reason)
            val lease = (preparation as GesturePreparation.Ready).lease
            try {
                val dispatched = withContext(Dispatchers.Main.immediate) {
                    val reason = rejection(stamp, pathPoints, durationMillis)
                    if (reason != null) return@withContext GestureDispatch.Rejected(reason)
                    val path = Path().apply {
                        moveTo(pathPoints.first().x, pathPoints.first().y)
                        pathPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis)).build()
                    val record = InFlight(lease)
                    pending = record
                    call.submission = record
                    val accepted = try {
                        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) { terminal(record, GestureResult.Completed) }
                            override fun onCancelled(gestureDescription: GestureDescription) { terminal(record, GestureResult.Cancelled) }
                        }, null)
                    } catch (_: SecurityException) {
                        closed = true
                        record.result.complete(GestureResult.Unconfirmed)
                        return@withContext GestureDispatch.Pending(record)
                    }
                    if (!accepted) {
                        pending = null
                        record.result.complete(GestureResult.Rejected(DeviceRejection.PLATFORM_REJECTED))
                        return@withContext GestureDispatch.Rejected(DeviceRejection.PLATFORM_REJECTED)
                    }
                    GestureDispatch.Pending(record)
                }
                return when (dispatched) {
                    is GestureDispatch.Rejected -> GestureResult.Rejected(dispatched.reason)
                    is GestureDispatch.Pending -> dispatched.record.result.await()
                }
            } finally {
                // Ownership already transferred to pending survives caller cancellation and dispatcher handoff.
                withContext(NonCancellable + Dispatchers.Main.immediate) { if (pending?.uiLease !== lease) lease.close() }
            }
        }

        override suspend fun revoke(): InputDrain {
            val flight = withContext(Dispatchers.Main.immediate) {
                closed = true
                active.also { if (it == null && owner === this@InputSession) owner = null }
            } ?: return InputDrain.NoInFlightGesture
            return when (val result = flight.result.await()) {
                GestureResult.Unconfirmed -> InputDrain.Unconfirmed
                else -> InputDrain.Terminal(result)
            }
        }

    }

    private sealed interface GesturePreparation {
        data class Ready(val lease: Closeable) : GesturePreparation
        data class Rejected(val reason: DeviceRejection) : GesturePreparation
    }
    private sealed interface GestureDispatch {
        data class Rejected(val reason: DeviceRejection) : GestureDispatch
        data class Pending(val record: InFlight) : GestureDispatch
    }
    private fun foreground(): String? = try { service.rootInActiveWindow?.packageName?.toString() } catch (_: SecurityException) { null }
    private fun geometry(): ScreenGeometry? {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val bounds = service.createDisplayContext(display).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return ScreenGeometry(bounds.width(), bounds.height(), display.rotation)
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}
