package io.github.yysassist.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yysassist.core.execution.AttemptTicket
import io.github.yysassist.device.*
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only. A scripted stand-in for the screen: captures return staged frames, and only a
 * single-point press inside a declared rectangle of the current frame moves to the next frame. Every
 * other input is recorded and changes nothing. No real touch, key or capture happens.
 *
 * replay.json: {"package": "...", "start": "a.png",
 *               "transitions": [{"from": "a.png", "rect": [l, t, r, b], "to": "b.png"}]}
 */
internal class ReplayDevice(private val folder: File) : InputDevicePort {
    private val script = JSONObject(File(folder, "replay.json").readText())
    private val expected = script.getString("package")
    private val cache = mutableMapOf<String, Bitmap>()
    private var current = script.getString("start")
    private var token = Any()
    val inputs = mutableListOf<String>()

    private fun frame(name: String) = cache.getOrPut(name) {
        require(name.matches(Regex("[a-z0-9-]+\\.png")))
        checkNotNull(BitmapFactory.decodeFile(File(folder, name).path)) { "回放帧不可解码：$name" }
    }
    override suspend fun capture(expectedPackage: String): FrameResult {
        if (expectedPackage != expected) return FrameResult.Unavailable(DeviceRejection.WRONG_FOREGROUND)
        val bitmap = frame(current)
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
        override fun gestureCall(stamp: FrameStamp, points: List<ScreenPoint>, durationMillis: Long) = call {
            val point = points.singleOrNull()
            val transitions = script.getJSONArray("transitions")
            val next = if (point == null) null else (0 until transitions.length()).map { transitions.getJSONObject(it) }.firstOrNull { t ->
                val r = t.getJSONArray("rect")
                t.getString("from") == current && point.x >= r.getInt(0) && point.y >= r.getInt(1) && point.x < r.getInt(2) && point.y < r.getInt(3)
            }?.getString("to")
            inputs += "press ${points.joinToString(";") { "%.0f,%.0f".format(it.x, it.y) }} on $current -> ${next ?: "unchanged"}"
            if (next != null) { current = next; token = Any() }
        }
        override fun backCall(stamp: FrameStamp) = call { inputs += "back on $current -> unchanged" }
        override suspend fun revoke(): InputDrain = last?.let { InputDrain.Terminal(it) } ?: InputDrain.NoInFlightGesture
    }
}
