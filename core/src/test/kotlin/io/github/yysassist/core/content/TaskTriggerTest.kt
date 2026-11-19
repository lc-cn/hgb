package io.github.yysassist.core.content

import io.github.yysassist.core.execution.BuiltInStepWorkFactory
import io.github.yysassist.core.execution.SELECTION_CONTAINS
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskTriggerTest {
    private val accept = ConfigurationField("accept", "接受的协作", FieldWidget.MULTI_CHOICE, true,
        options = listOf(FieldOption("jade", "勾玉协作"), FieldOption("soul", "御魂组队")))
    private val stamina = ConfigurationField("stamina", "体力上限", FieldWidget.NUMBER, true, visibleWhen = FieldCondition("accept", "soul"))
    private val step = StepDefinition("s", "处理", listOf(ActionDefinition("w", "control.wait", 1, JsonObject(mapOf("durationMs" to JsonPrimitive(0))))))
    private fun task(priority: Long = 1000, trigger: TaskTrigger? = TaskTrigger(listOf("协作"), "accept"), fields: List<ConfigurationField> = listOf(accept, stamina)) =
        TaskDefinition("invite", "app", "协作邀请", "daily", priority, fields, listOf(step), trigger = trigger)
    private fun bundle(definition: TaskDefinition) = TaskBundle(1, "v1", "2026-11-18T00:00:00Z", "测试", listOf(definition),
        listOf(TargetProfile("app", "应用", listOf("a.b"), IdentityPolicy.NONE, listOf(TaskCategory("daily", "日常")))))
    private val codec = BundleCodec(BuiltInStepWorkFactory().contracts)
    private fun values(vararg selected: String, extra: Map<String, JsonElement> = emptyMap()) =
        JsonObject(mapOf("accept" to JsonArray(selected.map(::JsonPrimitive))) + extra)

    @Test fun `nothing selected is a valid saved state and leaves the task off`() {
        assertTrue(validateConfiguration(task(), values()).isEmpty())
        assertFalse(task().trigger!!.enabled(values()))
        assertTrue(task().trigger!!.enabled(values("jade")))
    }
    @Test fun `a second level setting applies only while its option is selected`() {
        assertFalse(stamina.isVisible(values("jade")))
        assertTrue(validateConfiguration(task(), values("jade")).isEmpty())
        assertEquals(listOf("stamina"), validateConfiguration(task(), values("soul")).map { it.location })
        assertTrue(validateConfiguration(task(), values("soul", extra = mapOf("stamina" to JsonPrimitive(240)))).isEmpty())
    }
    @Test fun `a hidden value is kept but never checked`() {
        assertTrue(validateConfiguration(task(), values("jade", extra = mapOf("stamina" to JsonPrimitive("无效")))).isEmpty())
    }
    @Test fun `bundles reject triggers and conditions that cannot work`() {
        assertTrue(codec.validate(bundle(task())).isEmpty())
        assertTrue(codec.validate(bundle(task(priority = 10))).any { "优先级" in it.reason })
        assertTrue(codec.validate(bundle(task(trigger = TaskTrigger(emptyList(), "accept")))).any { "触发文字" in it.reason })
        assertTrue(codec.validate(bundle(task(trigger = TaskTrigger(listOf("协作"), "stamina")))).any { "多选字段" in it.reason })
        val orphan = stamina.copy(visibleWhen = FieldCondition("accept", "missing"))
        assertTrue(codec.validate(bundle(task(fields = listOf(accept, orphan)))).any { "选项不存在" in it.reason })
    }
    @Test fun `an action cannot depend on a setting that may be hidden`() {
        val bound = step.copy(actions = listOf(ActionDefinition("w", "control.wait", 1, JsonObject(emptyMap()),
            bindings = mapOf("durationMs" to ActionInputBinding.Configuration("stamina")))))
        assertTrue(codec.validate(bundle(task().copy(steps = listOf(bound)))).any { "按条件显示" in it.reason })
    }
    @Test fun `the selection check is declared for bundles to use`() {
        assertTrue(BuiltInStepWorkFactory().contracts.any { it.id == SELECTION_CONTAINS })
    }
    @Test fun `text matches anywhere in a recognized line`() {
        val trigger = TaskTrigger(listOf("悬赏协作"), "accept")
        assertTrue(trigger.matches(listOf("好友邀请你悬赏协作")))
        assertFalse(trigger.matches(listOf("悬赏", "协作")))
    }
    @Test fun `the gate admits one answer per cooldown for each task`() {
        val gate = TriggerGate()
        assertTrue(gate.admit("a", 1000, 0)); assertFalse(gate.admit("a", 1000, 999))
        assertTrue(gate.admit("b", 1000, 999)); assertTrue(gate.admit("a", 1000, 1000))
    }
}
