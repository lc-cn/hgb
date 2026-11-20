package io.github.yysassist.device

import android.graphics.Bitmap
import io.github.yysassist.core.execution.AttemptTicket

/**
 * A still screen for checking a task against a sample: every capture is [bitmap] as if [packageName] were in front,
 * and input is accepted and changes nothing. Nothing is touched on the real screen.
 */
internal class StillScreen(private val bitmap: Bitmap, private val packageName: String) : InputDevicePort {
    private val token = Any()
    val inputs = mutableListOf<String>()
    fun recycle() = bitmap.recycle()
    override suspend fun capture(expectedPackage: String): FrameResult {
        if (expectedPackage != packageName) return FrameResult.Unavailable(DeviceRejection.WRONG_FOREGROUND)
        return FrameResult.Ready(ScreenFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false),
            FrameStamp(token, ScreenGeometry(bitmap.width, bitmap.height, 0), expectedPackage, System.nanoTime())))
    }
    override suspend fun validateObservation(stamp: FrameStamp) = if (stamp.connection === token) null else DeviceRejection.SCREEN_CHANGED
    override suspend fun openInput(ticket: AttemptTicket, permitsInput: (AttemptTicket) -> Boolean): DeviceInputChannel = object : DeviceInputChannel {
        var last: GestureResult? = null
        private fun call(record: () -> Unit) = object : InputGestureCall {
            override suspend fun execute(): GestureResult { record(); return GestureResult.Completed.also { last = it } }
            override suspend fun observeAfterRevoke(): GestureResult? = last
        }
        override fun gestureCall(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long) =
            call { inputs += points.joinToString(";", "按 ") { "%.0f,%.0f".format(it.x, it.y) } }
        override fun backCall(stamp: FrameStamp) = call { inputs += "返回" }
        override fun launchCall(packageName: String) = call { inputs += "启动 $packageName" }
        override suspend fun revoke(): InputDrain = last?.let { InputDrain.Terminal(it) } ?: InputDrain.NoInFlightGesture
    }
}
