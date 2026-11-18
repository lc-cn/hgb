package io.github.yysassist.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.ThemeMode
import org.junit.*
import org.junit.Assert.*

class NotificationAccessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("qa/notification/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun stateFollowsThePlatformRules() {
        assertEquals(NotificationAccess.REQUESTABLE, notificationAccess(33, false, false, false))
        assertEquals(NotificationAccess.SYSTEM_SETTINGS, notificationAccess(33, false, false, true))
        assertEquals(NotificationAccess.ALLOWED, notificationAccess(33, true, true, false))
        // Granted, but the app switch was later turned off in system settings.
        assertEquals(NotificationAccess.SYSTEM_SETTINGS, notificationAccess(33, true, false, false))
        // Before Android 13 there is no runtime prompt.
        assertEquals(NotificationAccess.ALLOWED, notificationAccess(30, true, true, false))
        assertEquals(NotificationAccess.SYSTEM_SETTINGS, notificationAccess(30, true, false, false))
    }
    @Test fun eachStateOffersOnlyItsOwnAction() {
        var access by mutableStateOf(NotificationAccess.REQUESTABLE)
        var requests = 0; var settings = 0
        compose.setContent { AssistTheme(ThemeMode.DARK) { Surface(Modifier.fillMaxSize()) {
            NotificationAccessContent(access, { requests++ }, { settings++ })
        } } }
        compose.onNodeWithText("开启通知").performClick()
        compose.onNodeWithText("打开通知设置").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(0, settings) }
        screenshot("requestable-dark")
        access = NotificationAccess.SYSTEM_SETTINGS
        compose.onNodeWithText("打开通知设置").performClick()
        compose.onNodeWithText("开启通知").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settings) }
        access = NotificationAccess.ALLOWED
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.onNodeWithText("开启通知").assertDoesNotExist(); compose.onNodeWithText("打开通知设置").assertDoesNotExist()
    }
}
