package io.github.yysassist.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.ThemeMode
import io.github.yysassist.auth.GitHubSessionState
import io.github.yysassist.core.auth.*
import org.junit.*
import org.junit.Assert.*

class GitHubAccountPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("qa/github/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun portraitDeviceCodeCanBeCopiedOpenedAndExplicitlyCancelled() {
        var state by mutableStateOf<GitHubSessionState>(GitHubSessionState.SigningIn(GitHubDevicePrompt("TEST-CODE", GitHubDeviceAuthorization.VERIFICATION_URI, 900)))
        var copied: String? = null; var opened: String? = null
        compose.setContent { AssistTheme(ThemeMode.LIGHT) { Surface(Modifier.fillMaxSize()) {
            GitHubAccountPage(state, false, {}, { state = GitHubSessionState.SignedOut() }, {}, {},
                { opened = it }, { copied = it })
        } } }
        compose.onNodeWithContentDescription("GitHub 设备码：TEST-CODE").assertIsDisplayed()
        compose.onNodeWithText("复制设备码").performClick(); compose.onNodeWithText("打开 GitHub").performClick()
        compose.runOnIdle { assertEquals("TEST-CODE", copied); assertEquals("https://github.com/login/device", opened) }
        screenshot("code-portrait-light")
        compose.onNodeWithText("取消登录").performClick()
        compose.onNodeWithText("使用 GitHub 登录").assertIsEnabled()
        compose.onNodeWithText("TEST-CODE").assertDoesNotExist()
    }
    @Test fun landscapeConnectedPageUsesTwoColumnsAndLogoutRequiresItsOwnAction() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        var updates = 0; var logout = 0
        compose.setContent { AssistTheme(ThemeMode.DARK) { Surface(Modifier.fillMaxSize()) {
            GitHubAccountPage(GitHubSessionState.Connected("fixture-owner"), true, {}, {}, { logout++ }, { updates++ })
        } } }
        compose.onNodeWithText("@fixture-owner").assertIsDisplayed()
        screenshot("connected-landscape-dark")
        compose.onNodeWithText("任务更新").performClick(); compose.runOnIdle { assertEquals(1, updates) }
        compose.onNodeWithText("退出登录").performClick(); compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, logout) }
        compose.onNodeWithText("退出登录").performClick()
        compose.onAllNodesWithText("退出登录").onLast().performClick()
        compose.runOnIdle { assertEquals(1, logout) }
    }
    @Test fun loadingSavingOfflineAndPermissionErrorsKeepTheirRecoveryControlsDistinct() {
        var state by mutableStateOf<GitHubSessionState>(GitHubSessionState.Loading)
        compose.setContent { AssistTheme(ThemeMode.DARK) { Surface(Modifier.fillMaxSize()) {
            GitHubAccountPage(state, false, {}, {}, {}, {})
        } } }
        compose.onNodeWithContentDescription("读取登录资料").assertIsDisplayed()
        compose.runOnIdle { state = GitHubSessionState.Saving }
        compose.onNodeWithContentDescription("保存登录中").assertIsDisplayed()
        compose.onNodeWithText("取消登录").assertDoesNotExist()
        compose.runOnIdle { state = GitHubSessionState.SignedOut("网络不可用，请检查网络后重新登录") }
        compose.onNodeWithText("使用 GitHub 登录").assertIsEnabled()
        compose.onNodeWithText("网络不可用，请检查网络后重新登录").assertIsDisplayed()
        compose.runOnIdle { state = GitHubSessionState.SignedOut("GitHub 授权已取消") }
        compose.onNodeWithText("GitHub 授权已取消").assertIsDisplayed()
        compose.runOnIdle { state = GitHubSessionState.Failure("GitHub 登录资料无法读取，原记录已保留") }
        compose.onNodeWithText("重新登录").assertIsEnabled()
        compose.onNodeWithText("清除本机登录").assertIsEnabled()
        screenshot("storage-error-portrait-dark")
    }
}
