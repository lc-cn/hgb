package io.github.yysassist.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.github.yysassist.core.execution.AttemptTicket
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
internal sealed interface FrameResult {
    data class Ready(val frame: ScreenFrame) : FrameResult
    data class Unavailable(val reason: DeviceRejection, val platformCode: Int? = null) : FrameResult
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

/**
 * Main-thread device owner. Worker cancellation does not cancel or acknowledge a platform gesture.
 * Revocation closes admission immediately, then drains the independently retained platform callback.
 * Neither a terminal callback nor a quiet channel establishes the business result of a click.
 */
internal class AndroidDeviceBridge(private val service: AccessibilityService) {
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

    suspend fun capture(expectedPackage: String): FrameResult = captureScreen(expectedPackage)
    suspend fun checkScreen(): FrameResult = captureScreen(null)

    private suspend fun captureScreen(expectedPackage: String?): FrameResult = suspendCancellableCoroutine { continuation ->
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
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
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
                        completeCapture(continuation, lease, FrameResult.Unavailable(DeviceRejection.PLATFORM_REJECTED, errorCode))
                    }
        })
    }

    suspend fun openInput(ticket: AttemptTicket, permitsInput: (AttemptTicket) -> Boolean): InputSession? =
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

    internal inner class InputSession internal constructor(val ticket: AttemptTicket, private val permitsInput: (AttemptTicket) -> Boolean) {
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
        suspend fun gesture(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long): GestureResult {
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
                    if (!accepted) { pending = null; return@withContext GestureDispatch.Rejected(DeviceRejection.PLATFORM_REJECTED) }
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

        suspend fun revoke(): InputDrain {
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
