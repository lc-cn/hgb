package io.github.yysassist.core.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigurationValidatorTest {
    private val parser = Json
    private fun task(vararg fields: ConfigurationField) = TaskDefinition("test", "sample", "测试", "daily", 1, fields.toList(), emptyList())
    private fun values(text: String) = parser.parseToJsonElement(text).jsonObject

    @Test fun `a recommended value must satisfy its own field`() {
        fun bundle(value: Int) = TaskBundle(1, "v1", "2026-11-18T00:00:00Z", "测试", listOf(TaskDefinition("t", "app", "测试", "daily", 1,
            listOf(ConfigurationField("stamina", "体力上限", FieldWidget.NUMBER, true, maximum = JsonPrimitive(100), section = "消耗上限", recommended = JsonPrimitive(value))),
            listOf(StepDefinition("s", "步骤", listOf(ActionDefinition("a", "test.observe", 1, JsonObject(emptyMap()))))))),
            listOf(TargetProfile("app", "应用", listOf("a.b"), IdentityPolicy.NONE, listOf(TaskCategory("daily", "日常")))))
        val codec = BundleCodec(listOf(CapabilityContract("test.observe", 1) { null }))
        assertTrue(codec.read(codec.write(bundle(80))) is BundleReadResult.Valid)
        val invalid = codec.read(Json.encodeToString(TaskBundle.serializer(), bundle(120))) as BundleReadResult.Invalid
        assertTrue(invalid.issues.any { it.reason == "推荐值不符合字段约束" })
    }
    @Test fun `large exact budget is checked without binary floating point coercion`() {
        val schema = task(ConfigurationField("budget", "预算", FieldWidget.NUMBER, required = true,
            maximum = parser.parseToJsonElement("9007199254740993").jsonPrimitive))
        assertTrue(validateConfiguration(schema, values("{\"budget\":9007199254740993}")).isEmpty())
        assertTrue(validateConfiguration(schema, values("{\"budget\":9007199254740994}")).isNotEmpty())
        assertTrue(validateConfiguration(schema, values("{\"budget\":\"9007199254740993\"}")).isNotEmpty())
    }
    @Test fun `required values are never automatically defaulted or removed`() {
        val schema = task(ConfigurationField("count", "数量", FieldWidget.NUMBER, required = true))
        val input = values("{\"oldField\":10}")
        val issues = validateConfiguration(schema, input)
        assertEquals(setOf("count", "oldField"), issues.map { it.location }.toSet())
        assertEquals(values("{\"oldField\":10}"), input)
    }
    @Test fun `step validation never rounds a precise count into a different allowed value`() {
        val schema = task(ConfigurationField("count", "数量", FieldWidget.NUMBER, true, step = JsonPrimitive(2)))
        assertTrue(validateConfiguration(schema, values("{\"count\":100000000000000000000000000000000000000000002}")).isEmpty())
        assertTrue(validateConfiguration(schema, values("{\"count\":100000000000000000000000000000000000000000003}")).isNotEmpty())
        // A power of ten is even; exact exponent arithmetic must not round it or expand its digits.
        assertTrue(validateConfiguration(schema, values("{\"count\":1e2147483647}")).isEmpty())
        assertTrue(validateConfiguration(schema, values("{\"count\":1e-2147483647}")).isNotEmpty())
    }
    @Test fun `choice group enforces allowed identities and multi selection semantics`() {
        val options = listOf(FieldOption("a", "甲"), FieldOption("b", "乙"))
        val schema = task(ConfigurationField("one", "单选", FieldWidget.SINGLE_CHOICE, true, options = options),
            ConfigurationField("many", "多选", FieldWidget.MULTI_CHOICE, true, options = options))
        assertTrue(validateConfiguration(schema, values("{\"one\":\"a\",\"many\":[\"a\",\"b\"]}")).isEmpty())
        assertEquals(2, validateConfiguration(schema, values("{\"one\":\"unknown\",\"many\":[]}")).size)
        assertTrue(validateConfiguration(schema, values("{\"one\":\"a\",\"many\":[\"a\",\"a\"]}")).isNotEmpty())
    }
    @Test fun `date range and time keep calendar meaning without guessing timezone`() {
        val schema = task(ConfigurationField("time", "时刻", FieldWidget.TIME, true),
            ConfigurationField("dates", "日期范围", FieldWidget.DATE_RANGE, true))
        assertTrue(validateConfiguration(schema, values("{\"time\":\"09:30\",\"dates\":{\"start\":\"2026-11-16\",\"end\":\"2026-11-17\"}}")).isEmpty())
        assertEquals(2, validateConfiguration(schema, values("{\"time\":\"25:00\",\"dates\":{\"start\":\"2026-11-17\",\"end\":\"2026-11-16\"}}")).size)
    }
}
