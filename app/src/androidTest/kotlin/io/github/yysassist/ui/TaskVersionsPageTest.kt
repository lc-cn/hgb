package io.github.yysassist.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.yysassist.*
import io.github.yysassist.storage.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** View interaction fixtures. Storage/source behavior is separately exercised on actual SQLite/HTTPS. */
class TaskVersionsPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Composable private fun TestSurface(mode: ThemeMode, content: @Composable () -> Unit) {
        AssistTheme(mode) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    private fun catalog(reference: Int = 0) = TaskVersionCatalog(listOf(
        TaskVersionRow("one", "digest-one", "2026-11-15T00:00:00Z", "第一版说明", reference),
        TaskVersionRow("two", "digest-two", "2026-11-16T00:00:00Z", "第二版说明", 0),
    ), "two", "two")
    private fun button(label: String, version: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasText(version)))
    private fun screenshot(name: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("qa/task-updates/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun expandingBodyAndActionButtonsHaveIndependentInteractions() {
        var activated: String? = null
        var deleted: String? = null
        compose.setContent { TestSurface(ThemeMode.LIGHT) {
            TaskVersionsPage(VersionCatalogState.Ready(catalog()), TaskPullState.Idle, null, {}, { activated = it }, { deleted = it })
        } }
        compose.onNodeWithText("第一版说明").assertDoesNotExist()
        button("启用", "one").performClick()
        compose.runOnIdle { assertEquals("one", activated) }
        compose.onNodeWithText("第一版说明").assertDoesNotExist()
        compose.onNodeWithText("one").performClick()
        compose.onNodeWithText("第一版说明").assertIsDisplayed()
        screenshot("versions-light-portrait")
        button("删除", "one").performClick()
        compose.runOnIdle { assertEquals("one", deleted) }
        compose.onNodeWithText("第一版说明").assertIsDisplayed()
        compose.onNodeWithText("one").performClick()
        compose.onNodeWithText("第一版说明").assertDoesNotExist()
        button("已启用", "two").assertIsNotEnabled()
        button("删除", "two").assertIsNotEnabled()
    }
    @Test fun pinnedHistoryIsProtectedWhileFetchFailureDoesNotBlockLocalRows() {
        compose.setContent { TestSurface(ThemeMode.DARK) {
            TaskVersionsPage(VersionCatalogState.Ready(catalog(reference = 1)), TaskPullState.Failure("任务源网络不可达"), null, {}, {}, {})
        } }
        compose.onNodeWithText("任务源网络不可达").assertIsDisplayed()
        compose.onNodeWithText("one").assertIsDisplayed()
        button("删除", "one").assertIsNotEnabled()
        compose.onNodeWithText("one").performClick()
        compose.onNodeWithText("第一版说明").assertIsDisplayed()
        screenshot("versions-dark-portrait")
    }
    @Test fun landscapeKeepsBothVersionRowsAndActionsVisible() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.setContent { TestSurface(ThemeMode.LIGHT) {
            TaskVersionsPage(VersionCatalogState.Ready(catalog()), TaskPullState.Idle, null, {}, {}, {})
        } }
        button("启用", "one").assertIsDisplayed()
        button("删除", "one").assertIsDisplayed()
        button("已启用", "two").assertIsDisplayed()
        button("删除", "two").assertIsDisplayed()
        screenshot("versions-light-landscape")
    }
}
