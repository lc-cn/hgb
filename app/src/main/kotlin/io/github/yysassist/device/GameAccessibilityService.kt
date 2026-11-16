package io.github.yysassist.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    }
    internal val bridge = AndroidDeviceBridge(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onServiceConnected() { bridge.connected(); mutableConnection.value = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        bridge.disconnected()
        if (mutableConnection.value === this) mutableConnection.value = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        bridge.disconnected()
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
