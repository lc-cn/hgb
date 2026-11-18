package io.github.yysassist.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yysassist.TaskEditorState
import io.github.yysassist.core.content.*
import io.github.yysassist.storage.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Real native widget interaction; fixtures contain no game steps or account information. */
class TaskConfigurationPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var values = JsonObject(emptyMap())
    private var saves = 0
    private fun show(fields: List<ConfigurationField>, initial: JsonObject = JsonObject(emptyMap()), landscape: Boolean = false, dark: Boolean = false) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val task = TaskDefinition("fixture", "test-app", "测试任务", "tools", 1, fields,
            listOf(StepDefinition("observe", "观察画面", listOf(ActionDefinition("screen", "test.observe", 1, JsonObject(emptyMap()))))))
        val snapshot = TaskConfigurationSnapshot(TaskAddress("test-app", "fixture"), task, "fixture-version", "digest", 0, initial)
        values = initial
        compose.setContent {
            var state by remember { mutableStateOf(TaskEditorState.Ready(snapshot, initial)) }
            AssistTheme(if (dark) io.github.yysassist.ThemeMode.DARK else io.github.yysassist.ThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TaskConfigurationPage(state, landscape, {}, { field, value ->
                        values = JsonObject(values + (field to value)); state = state.copy(values = values)
                    }, { saves++ })
                }
            }
        }
    }
    private fun screenshot(name: String, dialog: Boolean = false) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("qa/configuration/$name.png")
        file.parentFile!!.mkdirs()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun dialogEvidence(name: String) {
        screenshot(name, dialog = true)
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("qa/configuration/$name.txt")
            .writeText(compose.onNode(isDialog()).printToString())
    }
    private val options get() = listOf(FieldOption("a", "甲"), FieldOption("b", "乙"), FieldOption("c", "丙"))
    private fun calendarDay(day: Int) = compose.onNodeWithText(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(compose.activity.resources.configuration.locales[0])
            .format(LocalDate.of(2026, 11, day)), substring = true, useUnmergedTree = true)

    @Test fun recommendedValuesAreOfferedButOnlyTheExplicitSaveCommitsThem() {
        show(listOf(ConfigurationField("stamina", "体力上限", FieldWidget.NUMBER, true, section = "消耗上限", recommended = JsonPrimitive(240)),
            ConfigurationField("floor", "层数", FieldWidget.NUMBER, true, section = "挑战目标")), JsonObject(mapOf("stamina" to JsonPrimitive(80))))
        compose.onNodeWithText("消耗上限").assertIsDisplayed()
        compose.onNodeWithText("推荐").assertDoesNotExist()
        compose.onNodeWithText("恢复推荐值").performClick()
        compose.onNodeWithText("推荐").assertIsDisplayed()
        compose.runOnIdle { assertEquals(JsonPrimitive(240), values["stamina"]); assertEquals(0, saves) }
    }
    @Test fun portraitChoiceButtonsSelectIndependentlyAndShareARow() {
        show(listOf(ConfigurationField("one", "处理方式", FieldWidget.SINGLE_CHOICE, true, options = options),
            ConfigurationField("many", "领取种类", FieldWidget.MULTI_CHOICE, options = listOf(FieldOption("x", "金币"), FieldOption("y", "体力")))))
        compose.onNodeWithText("甲").performScrollTo().performClick()
        compose.onNodeWithText("乙").performClick()
        compose.onNodeWithText("甲").assertIsNotSelected()
        compose.onNodeWithText("乙").assertIsSelected()
        val first = compose.onNodeWithText("甲").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("乙").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        compose.onNodeWithText("金币").performScrollTo().performClick()
        compose.onNodeWithText("体力").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))), values["many"]) }
        compose.onNodeWithText("金币").performClick()
        compose.runOnIdle { assertEquals(JsonArray(listOf(JsonPrimitive("y"))), values["many"]) }
        screenshot("choices-portrait")
    }
    @Test fun numericEditingKeepsPartialTextAndExactLargeAmount() {
        show(listOf(ConfigurationField("amount", "数量", FieldWidget.NUMBER, true, unit = "份")))
        val input = compose.onNode(hasSetTextAction())
        input.performTextInput("1."); input.assertTextContains("1.")
        input.performTextInput("5"); input.assertTextContains("1.5")
        input.performTextReplacement("9007199254740993")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(JsonPrimitive("9007199254740993"), values["amount"]); assertEquals(1, saves) }
    }
    @Test fun stepperMovesByTheDeclaredStepAndStopsAtTheLimits() {
        show(listOf(ConfigurationField("floor", "层数", FieldWidget.NUMBER, true, minimum = JsonPrimitive(1), maximum = JsonPrimitive(11), step = JsonPrimitive(5))),
            JsonObject(mapOf("floor" to JsonPrimitive(6))))
        compose.onNodeWithContentDescription("增加层数").performClick()
        compose.runOnIdle { assertEquals("11", values["floor"]!!.jsonPrimitive.content) }
        compose.onNodeWithContentDescription("增加层数").assertIsNotEnabled()
    }
    @Test fun switchFieldStoresARealBoolean() {
        show(listOf(ConfigurationField("boost", "使用加成", FieldWidget.SWITCH, true)), JsonObject(mapOf("boost" to JsonPrimitive(false))))
        compose.onNodeWithContentDescription("使用加成").performClick()
        compose.runOnIdle { assertEquals(JsonPrimitive(true), values["boost"]) }
    }
    @Test fun sliderProducesAnExactDecimalEvenBeyondDoubleIntegerPrecision() {
        val field = ConfigurationField("budget", "预算", FieldWidget.RANGE, true, unit = "份",
            minimum = JsonPrimitive("9007199254740993".toBigDecimal()), maximum = JsonPrimitive("9007199254741003".toBigDecimal()), step = JsonPrimitive(1))
        show(listOf(field))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.7f) }
        compose.runOnIdle { assertEquals("9007199254741000", values["budget"]!!.jsonPrimitive.content); assertFalse(values["budget"]!!.jsonPrimitive.isString) }
        screenshot("exact-slider")
    }
    @Test fun cancellingPickersDoesNotCreateDefaultTimeOrDate() {
        show(listOf(ConfigurationField("when", "领取时刻", FieldWidget.TIME), ConfigurationField("day", "领取日期", FieldWidget.DATE)))
        compose.onNodeWithText("选择时刻").performClick(); compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("选择日期").performClick(); compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(values.isEmpty()) }
    }
    @Test fun datePickerCommitsACalendarDateWithoutTimezoneShift() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            show(listOf(ConfigurationField("day", "领取日期", FieldWidget.DATE)), JsonObject(mapOf("day" to JsonPrimitive("2026-11-16"))))
            compose.onNodeWithText("2026-11-16").performClick()
            dialogEvidence("date-picker-before-selection")
            calendarDay(17).performClick()
            screenshot("date-picker", dialog = true)
            compose.onNodeWithText("确定").performClick()
            compose.runOnIdle { assertEquals(JsonPrimitive("2026-11-17"), values["day"]) }
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun rangePickerCommitsBothEndpointsOnlyOnConfirmation() {
        val original = JsonObject(mapOf("start" to JsonPrimitive("2026-11-16"), "end" to JsonPrimitive("2026-11-18")))
        show(listOf(ConfigurationField("period", "领取日期范围", FieldWidget.DATE_RANGE)), JsonObject(mapOf("period" to original)))
        compose.onNodeWithText("2026-11-16 — 2026-11-18").performClick()
        dialogEvidence("range-picker-before-selection")
        calendarDay(17).performClick()
        calendarDay(20).performClick()
        compose.runOnIdle { assertEquals(original, values["period"]) }
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(JsonObject(mapOf("start" to JsonPrimitive("2026-11-17"), "end" to JsonPrimitive("2026-11-20"))), values["period"]) }
    }
    @Test fun landscapeUsesWrappingChoiceButtonsAndCompactNativeTimeInput() {
        show(listOf(ConfigurationField("one", "处理方式", FieldWidget.SINGLE_CHOICE, true, options = options),
            ConfigurationField("when", "领取时刻", FieldWidget.TIME)), JsonObject(mapOf("when" to JsonPrimitive("09:30"))), landscape = true, dark = true)
        val first = compose.onNodeWithText("甲").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("乙").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        screenshot("fields-landscape-dark")
        compose.onNodeWithText("09:30").performClick()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog())).assertCountEquals(2)
        screenshot("time-input-landscape", dialog = true)
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(JsonPrimitive("09:30"), values["when"]) }
    }
}
