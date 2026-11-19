package io.github.yysassist.floating

import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Looper
import android.view.*
import androidx.compose.runtime.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import io.github.yysassist.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.device.*
import io.github.yysassist.storage.TaskAddress
import io.github.yysassist.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import kotlin.math.*

/** Owns one small accessibility window. Business commands always go to the provided unique runtime. */
internal class FloatingOverlayWindow(
    private val service: GameAccessibilityService,
    private val app: AssistApplication,
    private val runtime: ExecutionRuntime = app.executor,
    private val preferences: FloatingPreferences = app.floating,
    common: StateFlow<FloatingTasksState>? = null,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, DeviceUiObstruction, Closeable {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commonTasks = common ?: floatingTasks(preferences, app.taskUpdates.catalog, runtime, scope)
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    private val feedback = mutableStateOf<String?>(null)
    private val manager = service.getSystemService(WindowManager::class.java)
    private val view = ComposeView(service)
    // These coordinates match raw screen input and screenshots, not the text's reading direction.
    @android.annotation.SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "助手悬浮控制"
        setFitInsetsTypes(0)
    }
    private var attached = false
    private var closed = false
    private var exclusions = 0
    private var settings: FloatingSettings? = null
    private var anchor: FloatingAnchor? = null
    private var lastOrientation: Boolean? = null
    private val layer = mutableStateOf(FloatingLayer.BALL)
    private val viewport = mutableStateOf(readViewport())
    private val rightSide = mutableStateOf(true)
    private var idle: Job? = null
    private data class Viewport(val safe: Rect, val edge: Rect, val wide: Boolean, val density: Float)
    private data class Down(val rawX: Float, val rawY: Float, val windowX: Int, val windowY: Int, var moved: Boolean = false)
    private var down: Down? = null

    init {
        checkMain()
        saved.performAttach(); saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        view.setViewTreeLifecycleOwner(this); view.setViewTreeSavedStateRegistryOwner(this); view.setViewTreeViewModelStoreOwner(this)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            val state by runtime.state.collectAsState(); val status by runtime.status.collectAsState(); val tasks by commonTasks.collectAsState()
            val vp = viewport.value
            AssistTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                FloatingSurface(layer.value, state, status, tasks, vp.wide,
                    min(if (vp.wide) FloatingUi.landscapeHeight.value else FloatingUi.portraitHeight.value, vp.safe.height() / vp.density).dp,
                    ::changeLayer, { app.runTask(it) },
                    { id -> runtime.submit(id?.let { ExecutorCommand.PauseCurrent(it) } ?: ExecutorCommand.Pause) },
                    { runtime.submit(ExecutorCommand.StopCurrent(it)) },
                    { id, mode -> runtime.submit(id?.let { ExecutorCommand.ResumeCurrent(it, mode) } ?: ExecutorCommand.Resume(mode)) },
                    ::openApp, ::ballMotion, maxWidth = (vp.safe.width() / vp.density).dp, feedback = feedback.value,
                    rightSide = rightSide.value, touched = ::armIdle,
                    // "没生效" re-enters the step by looking at the screen again; "已生效" moves on to the next step.
                    resolve = { ticket, applied -> runtime.submit(ExecutorCommand.ResolveUnknown(ticket,
                        if (applied) StepOutcome.Completed("用户在悬浮球确认这一步已生效") else StepOutcome.Interrupted)) })
                }
            }
        }
        // Touching the game outside an open menu closes it; the touch itself still reaches the game.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && layer.value in setOf(FloatingLayer.QUICK, FloatingLayer.COMMON)) changeLayer(FloatingLayer.BALL)
            false
        }
        scope.launch {
            combine(runtime.state, runtime.status) { state, status -> needsAttention(state, status) }.distinctUntilChanged().collect { attention ->
                if (attention && layer.value == FloatingLayer.DOCKED) changeLayer(FloatingLayer.BALL) else armIdle()
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (view.isShown && down == null) place() }
        scope.launch {
            combine(preferences.state, app.mainVisible) { pref, main -> pref to main }.collect { (pref, _) ->
                settings = (pref as? FloatingPreferencesState.Ready)?.settings
                if (down == null) anchor = settings?.let { if (viewport.value.wide) it.landscape else it.portrait }
                updateVisibility(); place(); armIdle()
            }
        }
        scope.launch { merge(preferences.messages, runtime.messages).collect { feedback.value = it } }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    internal val isShowing: Boolean get() = attached && view.isShown
    internal fun frameBounds(): Rect {
        checkMain()
        val location = IntArray(2); view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
    fun configurationChanged(configuration: Configuration) {
        checkMain(); down = null
        viewport.value = readViewport()
        if (lastOrientation != viewport.value.wide) {
            anchor = settings?.let { if (viewport.value.wide) it.landscape else it.portrait }
            lastOrientation = viewport.value.wide
        }
        view.dispatchConfigurationChanged(configuration); place()
    }
    private fun readViewport(): Viewport {
        val metrics = manager.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val safe = Rect(metrics.bounds).apply { left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom }
        val edge = Rect(safe)
        val density = service.resources.displayMetrics.density
        val margin = (UiSpace.gap.value * density).roundToInt()
        if (safe.width() > 2 * margin && safe.height() > 2 * margin) safe.inset(margin, margin)
        return Viewport(safe, edge, metrics.bounds.width() > metrics.bounds.height(), density)
    }
    private fun updateVisibility() {
        if (closed || mutableFailure.value != null) return
        val show = settings?.visible == true && !app.mainVisible.value && exclusions == 0
        if (show && !attached) {
            place()
            try { manager.addView(view, params); attached = true }
            catch (error: Exception) { windowFailed(error); return }
        }
        if (attached) view.visibility = if (show) View.VISIBLE else View.GONE
    }
    private fun place() {
        if (closed || down != null) return
        val vp = viewport.value
        fun px(value: androidx.compose.ui.unit.Dp) = (value.value * vp.density).roundToInt()
        val ball = px(FloatingUi.ball)
        val right = (anchor?.x ?: 1f) >= .5f
        rightSide.value = right
        val ballY = vp.safe.top + (anchor?.y?.let { it * (vp.safe.height() - ball).coerceAtLeast(0) }
            ?: ((if (vp.wide) FloatingUi.landscapeTop.value else FloatingUi.portraitTop.value) * vp.density))
        val center = ballY + ball / 2f
        val width = min(vp.safe.width(), px(FloatingUi.width(layer.value, vp.wide)))
        val fixedHeight = when (layer.value) {
            FloatingLayer.DOCKED -> px(FloatingUi.dockHeight); FloatingLayer.BALL -> ball
            FloatingLayer.QUICK -> px(FloatingUi.fanHeight); FloatingLayer.COMMON -> null
        }
        val bounds = if (layer.value == FloatingLayer.DOCKED) vp.edge else vp.safe
        val height = fixedHeight ?: view.height.takeIf { it > ball } ?: ball
        val x = if (right) bounds.right - width else bounds.left
        val y = if (fixedHeight != null) (center - fixedHeight / 2f).roundToInt()
            else if (ballY + height <= vp.safe.bottom) ballY.roundToInt() else (ballY + ball - height).roundToInt()
        val ny = y.coerceIn(bounds.top, max(bounds.top, bounds.bottom - height))
        val layoutHeight = fixedHeight ?: WindowManager.LayoutParams.WRAP_CONTENT
        if (params.x != x || params.y != ny || params.width != width || params.height != layoutHeight) {
            params.x = x; params.y = ny; params.width = width; params.height = layoutHeight
            if (attached) updateLayout()
        }
    }
    /** An idle menu folds to the ball, and an idle ball folds to a thin tab on the screen edge. */
    private fun armIdle() {
        idle?.cancel()
        if (layer.value != FloatingLayer.BALL && layer.value != FloatingLayer.QUICK) return
        idle = scope.launch {
            delay(IDLE_MILLIS)
            when {
                down != null -> armIdle()
                layer.value == FloatingLayer.QUICK -> changeLayer(FloatingLayer.BALL)
                settings?.autoDock != false && !needsAttention(runtime.state.value, runtime.status.value) -> changeLayer(FloatingLayer.DOCKED)
            }
        }
    }
    fun retry() { checkMain(); mutableFailure.value = null; updateVisibility(); place() }
    private fun updateLayout() {
        try { manager.updateViewLayout(view, params) }
        catch (error: Exception) { windowFailed(error) }
    }
    private fun windowFailed(error: Exception) {
        android.util.Log.e("FloatingWindow", "Unable to attach or update floating window", error)
        mutableFailure.value = "悬浮窗口暂不可用，请重新检查授权"
        if (view.isAttachedToWindow) try { manager.removeViewImmediate(view) } catch (_: IllegalArgumentException) { }
        attached = false
    }
    private fun changeLayer(next: FloatingLayer) { feedback.value = null; layer.value = next; down = null; place(); armIdle() }
    private fun ballMotion(event: MotionEvent): Boolean {
        checkMain()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { idle?.cancel(); down = Down(event.rawX, event.rawY, params.x, params.y) }
            MotionEvent.ACTION_MOVE -> down?.let { start ->
                if (event.pointerCount != 1) { down = null; armIdle(); return true }
                val dx = event.rawX - start.rawX; val dy = event.rawY - start.rawY
                start.moved = start.moved || hypot(dx, dy) > ViewConfiguration.get(service).scaledTouchSlop
                if (start.moved) {
                    val bounds = viewport.value.safe; val size = (FloatingUi.ball.value * viewport.value.density).roundToInt()
                    // Pulling the edge tab wakes it into the ball under the finger.
                    if (layer.value == FloatingLayer.DOCKED) { layer.value = FloatingLayer.BALL; params.width = size; params.height = size }
                    params.x = (start.windowX + dx).roundToInt().coerceIn(bounds.left, max(bounds.left, bounds.right - size))
                    params.y = (start.windowY + dy).roundToInt().coerceIn(bounds.top, max(bounds.top, bounds.bottom - size))
                    updateLayout()
                }
            }
            MotionEvent.ACTION_UP -> {
                val start = down; down = null
                if (start?.moved == true) {
                    val vp = viewport.value; val size = (FloatingUi.ball.value * vp.density).roundToInt()
                    val right = params.x + size / 2 >= vp.safe.centerX()
                    val result = FloatingAnchor(if (right) 1f else 0f, ((params.y - vp.safe.top).toFloat() / (vp.safe.height() - size).coerceAtLeast(1)).coerceIn(0f, 1f))
                    anchor = result; preferences.move(vp.wide, result); place(); armIdle()
                } else if (start != null && event.x in 0f..view.width.toFloat() && event.y in 0f..view.height.toFloat())
                    changeLayer(if (layer.value == FloatingLayer.DOCKED) FloatingLayer.BALL else FloatingLayer.QUICK)
                else armIdle()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { down = null; armIdle() }
        }
        return true
    }
    private fun openApp(address: TaskAddress?) {
        val intent = if (address == null) Intent(service, MainActivity::class.java) else MainActivity.taskIntent(service, address)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { service.startActivity(intent); feedback.value = null }
        catch (_: Exception) { feedback.value = "未能打开助手，请从桌面进入" }
    }
    override fun excludeCapture(ready: (Closeable) -> Unit): Closeable = suppress(true, ready)
    override fun excludeGesture(points: List<ScreenPoint>, ready: (Closeable) -> Unit): Closeable {
        val bounds = if (isShowing) frameBounds() else {
            val size = (FloatingUi.ball.value * viewport.value.density).roundToInt()
            Rect(params.x, params.y, params.x + params.width.takeIf { it > 0 }.let { it ?: size },
                params.y + view.height.takeIf { it > 0 }.let { it ?: size })
        }
        val path = if (points.isEmpty()) Rect() else Rect(floor(points.minOf { it.x }).toInt(), floor(points.minOf { it.y }).toInt(),
            ceil(points.maxOf { it.x }).toInt() + 1, ceil(points.maxOf { it.y }).toInt() + 1)
        return suppress(Rect.intersects(bounds, path), ready)
    }
    private fun suppress(required: Boolean, ready: (Closeable) -> Unit): Closeable {
        checkMain()
        if (!required || closed) return Closeable {}.also(ready)
        val needsDisplayBarrier = isShowing
        exclusions++; updateVisibility()
        var released = false
        val lease = Closeable { checkMain(); if (!released) { released = true; exclusions--; updateVisibility(); place() } }
        if (!needsDisplayBarrier) { ready(lease); return lease }
        // Cross two display frames after the visibility change; actual pixel/input tests verify this path.
        val clock = Choreographer.getInstance()
        clock.postFrameCallback { clock.postFrameCallback { if (!released) ready(lease) } }
        return lease
    }
    override fun close() {
        checkMain(); if (closed) return
        closed = true; scope.cancel()
        if (attached) { try { manager.removeViewImmediate(view) } catch (_: IllegalArgumentException) { }; attached = false }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); viewModelStore.clear()
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private companion object { const val IDLE_MILLIS = 3_000L }
}
