package io.github.yysassist.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.yysassist.ThemeMode
import io.github.yysassist.core.content.*
import io.github.yysassist.core.plan.PlanSchedule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek

class PlanEditorPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var saved: Triple<String, PlanSchedule, List<String>>? = null
    private val bundle = TaskBundle(1, "v1", "2026-11-18T00:00:00Z", "测试", listOf("a" to "邮件领取", "b" to "每日签到").map { (id, name) ->
        TaskDefinition(id, "app", name, "daily", 1, steps = listOf(StepDefinition("s", "步骤"))) },
        listOf(TargetProfile("app", "应用", listOf("a.b"), IdentityPolicy.NONE, listOf(TaskCategory("daily", "日常")))))
    private fun show() = compose.setContent { AssistTheme(ThemeMode.LIGHT) { Surface(Modifier.fillMaxSize()) {
        PlanEditorPage(null, null, bundle, { name, _, schedule, tasks -> saved = Triple(name, schedule, tasks) }, {})
    } } }

    @Test fun nothingIsPresetAndEveryMissingPartIsExplainedOnSave() {
        show()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请填写计划名称", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
    }
    @Test fun aCompleteDraftSavesTheChosenDaysTimeAndTaskOrder() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("周末领取")
        compose.onNodeWithText("指定星期").performClick()
        compose.onNodeWithText("六").performClick(); compose.onNodeWithText("日").performClick()
        compose.onNodeWithText("选择时间").performClick(); compose.onNodeWithText("确定").performClick()
        compose.onNodeWithContentDescription("添加：每日签到").performScrollTo().performClick()
        compose.onNodeWithContentDescription("添加：邮件领取").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("周末领取", saved!!.first); assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), saved!!.second.days)
            assertEquals(listOf("b", "a"), saved!!.third)
        }
    }
}
