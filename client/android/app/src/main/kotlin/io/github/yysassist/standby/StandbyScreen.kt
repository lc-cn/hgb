package io.github.yysassist.standby

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.yysassist.AssistApplication
import io.github.yysassist.R
import io.github.yysassist.core.execution.ExecutorPhase
import io.github.yysassist.core.execution.ExecutorSnapshot
import io.github.yysassist.core.plan.nextPlanText
import io.github.yysassist.device.DeviceUiObstruction
import io.github.yysassist.device.ScreenPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.Closeable
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * What 待命 does to the screen while the phone is plugged in:
 * - a one-pixel overlay keeps the screen on, so it never locks, and is told of every touch anywhere (only that one
 *   happened, not where), which is how idleness is measured;
 * - after [IDLE_MILLIS] without a touch and while no step is executing, a black screen saver covers everything, bars
 *   included: black pixels are off on OLED, so nothing burns in. One dim line ("护肝宝待命中 · 下次 06:30 每日") moves
 *   every [MOVE_MILLIS]. A tap lifts it; a step starting lifts it, and it returns [IDLE_MILLIS] after.
 * As a [DeviceUiObstruction] it also steps aside, and stays aside, for every capture and gesture of the automation,
 * so recognition never sees it and taps never land on it. Main thread only.
 */
internal class StandbyScreen(private val service: AccessibilityService, private val app: AssistApplication) : DeviceUiObstruction {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var sentinel: View? = null
    private var saver: FrameLayout? = null
    private var line: TextView? = null
    private var lastActivity = SystemClock.elapsedRealtime()
    private var movedAt = 0L
    /** Captures and gestures in progress; the saver stays away while any holds. */
    private var exclusions = 0
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        close()
        jobs += scope.launch { app.standby.keepAwake.collect { awake -> if (awake) showSentinel() else { hideSaver(); hideSentinel() } } }
        // A step starting lifts the saver at once; the idle time counts from its end.
        jobs += scope.launch { app.executor.state.map(::executing).distinctUntilChanged().collect { busy -> if (busy) hideSaver(); touched() } }
        // Only while standing by and plugged in is there anything to do.
        jobs += scope.launch {
            app.standby.keepAwake.collectLatest { awake ->
                while (awake) {
                    val idle = SystemClock.elapsedRealtime() - lastActivity >= IDLE_MILLIS
                    if (idle && exclusions == 0 && !executing(app.executor.state.value)) showSaver()
                    saver?.let { if (SystemClock.elapsedRealtime() - movedAt >= MOVE_MILLIS) place() }
                    delay(TICK_MILLIS)
                }
            }
        }
    }

    /** A step is being carried out (not paused, waiting or blocked): those leave the screen to the automation. */
    private fun executing(state: ExecutorSnapshot) =
        state.activeTicket != null && !state.manualHold && state.phase in setOf(ExecutorPhase.RUNNING, ExecutorPhase.SWITCHING)

    private fun touched() { lastActivity = SystemClock.elapsedRealtime() }

    override fun excludeCapture(ready: (Closeable) -> Unit): Closeable = stepAside(ready)
    override fun excludeGesture(points: List<ScreenPoint>, ready: (Closeable) -> Unit): Closeable = stepAside(ready)

    /** Removes the saver and lets the work go on two display frames later, once it is off the screen for certain. */
    private fun stepAside(ready: (Closeable) -> Unit): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val showing = saver != null
        exclusions++; hideSaver()
        var released = false
        val lease = Closeable { if (!released) { released = true; exclusions--; touched() } }
        if (!showing) { ready(lease); return lease }
        val clock = Choreographer.getInstance()
        clock.postFrameCallback { clock.postFrameCallback { if (!released) ready(lease) } }
        return lease
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSentinel() {
        if (sentinel != null) return
        val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; title = "护肝宝待命" }
        val view = View(service).apply { setOnTouchListener { _, _ -> touched(); false } }
        if (runCatching { windows.addView(view, params) }.isSuccess) { sentinel = view; touched() }
    }

    private fun hideSentinel() { sentinel?.let { runCatching { windows.removeViewImmediate(it) } }; sentinel = null }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSaver() {
        if (saver != null) { line?.text = text(); return }
        val dp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics).toInt() }
        val label = TextView(service).apply { setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); text = text() }
        val mark = ImageView(service).apply {
            setImageResource(R.drawable.ic_logo_mark); setColorFilter(DIM, PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { marginEnd = dp(8f) }
        }
        val group = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(mark); addView(label)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        val root = FrameLayout(service).apply {
            setBackgroundColor(Color.BLACK); addView(group)
            setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) { touched(); hideSaver() }; true }
            // Turned sideways: the old place may be off screen, and the line wraps differently.
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob -> if (r - l != or_ - ol || b - t != ob - ot) place() }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE).apply {
            title = "护肝宝屏保"
            // Over the status and navigation bars too, which would otherwise stay lit all night.
            fitInsetsTypes = 0; fitInsetsSides = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // As dark as the screen goes; not 0, which some phones take as "turn the backlight off".
            screenBrightness = 0.01f
        }
        if (runCatching { windows.addView(root, params) }.isFailure) return
        saver = root; line = label; movedAt = 0
        root.post { place() }
    }

    /** Somewhere else on the screen, so no pixel stays lit for long. */
    private fun place() {
        val root = saver ?: return
        val group = root.getChildAt(0) ?: return
        line?.text = text()
        group.post {
            val maxX = (root.width - group.width).coerceAtLeast(0); val maxY = (root.height - group.height).coerceAtLeast(0)
            group.translationX = Random.nextInt(maxX + 1).toFloat(); group.translationY = Random.nextInt(maxY + 1).toFloat()
        }
        movedAt = SystemClock.elapsedRealtime()
    }

    private fun text(): String {
        val data = app.cloud.data.value
        // Read each time: the device may register after the service connected.
        val device = app.cloud.registeredDeviceNow()
        val next = data?.let { nextPlanText(it.plans, device, it.timeZone, ZonedDateTime.now()) }
        val portrait = service.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (next == null) "护肝宝待命中" else "护肝宝待命中${if (portrait) "\n" else " · "}$next"
    }

    private fun hideSaver() { saver?.let { runCatching { windows.removeViewImmediate(it) } }; saver = null; line = null }

    fun close() { jobs.forEach { it.cancel() }; jobs.clear(); hideSaver(); hideSentinel() }

    private companion object {
        const val IDLE_MILLIS = 2 * 60_000L
        const val MOVE_MILLIS = 60_000L
        const val TICK_MILLIS = 5_000L
        val DIM = Color.parseColor("#5F5E5A")
    }
}
