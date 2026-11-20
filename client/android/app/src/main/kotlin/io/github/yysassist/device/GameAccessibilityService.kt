package io.github.yysassist.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import io.github.yysassist.AssistApplication
import io.github.yysassist.floating.FloatingOverlayWindow
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface ScreenshotCheck {
    data class Success(val width: Int, val height: Int) : ScreenshotCheck
    data class PlatformFailure(val code: Int) : ScreenshotCheck
    data class DecodeFailure(val reason: String) : ScreenshotCheck
}

/** Manually authorized device endpoint; only an admitted input session may dispatch gestures. */
class GameAccessibilityService : AccessibilityService() {
    companion object {
        private val mutableConnection = MutableStateFlow<GameAccessibilityService?>(null)
        val connection = mutableConnection.asStateFlow()
        private val mutableSceneRevision = MutableStateFlow(0L)
        internal val sceneRevision = mutableSceneRevision.asStateFlow()
    }
    internal val bridge = AndroidDeviceBridge(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var floating: FloatingOverlayWindow? = null
        private set
    private var keepAwake: io.github.yysassist.standby.StandbyScreen? = null
    private var displayManager: DisplayManager? = null
    private var displayState: Int? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) sceneChanged() }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) sceneChanged() }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val state = displayManager?.getDisplay(displayId)?.state
            if (state != displayState) { displayState = state; sceneChanged() }
        }
    }
    private fun sceneChanged() { if (mutableConnection.value === this) mutableSceneRevision.update { it + 1 } }
    private fun stopDisplayObservation() { displayManager?.unregisterDisplayListener(displayListener); displayManager = null; displayState = null }
    override fun onServiceConnected() {
        bridge.connected()
        floating?.close()
        floating = FloatingOverlayWindow(this, application as AssistApplication)
        bridge.obstruction = floating
        val standby = (application as AssistApplication).standby
        keepAwake?.close()
        keepAwake = io.github.yysassist.standby.StandbyScreen(this, application as AssistApplication).also { it.start(scope) }
        // Captures and gestures wait for both the floating ball and the screen saver to step aside.
        bridge.obstruction = CombinedObstruction(listOfNotNull(floating, keepAwake))
        // A process brought back by the system for this service can start standby without the App being opened.
        standby.ensureRunning()
        stopDisplayObservation()
        displayManager = getSystemService(DisplayManager::class.java).also {
            displayState = it.getDisplay(Display.DEFAULT_DISPLAY)?.state
            it.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }
        mutableConnection.value = this
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); floating?.configurationChanged(newConfig); sceneChanged() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Android 11 can replace the focused window without a separate FOCUSED change event.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges and
                (AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED) != 0)) sceneChanged()
    }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        bridge.disconnected()
        stopDisplayObservation()
        floating?.close(); floating = null; bridge.obstruction = null
        keepAwake?.close(); keepAwake = null
        if (mutableConnection.value === this) mutableConnection.value = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        bridge.disconnected()
        stopDisplayObservation()
        floating?.close(); floating = null; bridge.obstruction = null
        keepAwake?.close(); keepAwake = null
        if (mutableConnection.value === this) mutableConnection.value = null
        scope.cancel()
        super.onDestroy()
    }

    fun checkScreenshot(callback: (ScreenshotCheck) -> Unit) {
        scope.launch {
            val checked = try { when (val value = bridge.checkScreen()) {
                is FrameResult.Ready -> value.frame.use { ScreenshotCheck.Success(it.bitmap.width, it.bitmap.height) }
                is FrameResult.Unavailable -> when {
                    value.platformCode != null -> ScreenshotCheck.PlatformFailure(value.platformCode)
                    value.reason == DeviceRejection.DISCONNECTED -> ScreenshotCheck.PlatformFailure(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                    value.reason == DeviceRejection.SCREEN_CHANGED -> ScreenshotCheck.DecodeFailure("屏幕正在变化，请重新检查")
                    else -> ScreenshotCheck.DecodeFailure("截图无法转换为可读取画面")
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ScreenshotCheck.DecodeFailure("截图检查失败，请重新检查授权和画面") }
            callback(checked)
        }
    }
}
