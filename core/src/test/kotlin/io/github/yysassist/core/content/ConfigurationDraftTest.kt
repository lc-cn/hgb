package io.github.yysassist.core.content

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigurationDraftTest {
    @Test fun numericEditingDoesNotLoseTheDecimalSeparatorOrLargeInteger() {
        val task = TaskDefinition("task", "app", "设置", "tools", 1, listOf(ConfigurationField("amount", "数量", FieldWidget.NUMBER)), emptyList())
        val draft = JsonObject(mapOf("amount" to JsonPrimitive("9007199254740993.00"), "old" to JsonPrimitive("保留")))
        val normalized = normalizeConfigurationDraft(task, draft)
        assertEquals("9007199254740993.00", normalized["amount"]!!.jsonPrimitive.content)
        assertFalse(normalized["amount"]!!.jsonPrimitive.isString)
        assertEquals(JsonPrimitive("保留"), normalized["old"])
        assertEquals("9007199254740993.00", draft["amount"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("-"), normalizeConfigurationDraft(task, JsonObject(mapOf("amount" to JsonPrimitive("-"))))["amount"])
    }
    @Test fun sliderQuantizationNeverUsesFloatAsTheSavedAmount() {
        val field = ConfigurationField("amount", "数量", FieldWidget.RANGE, minimum = JsonPrimitive("9007199254740993".toBigDecimal()),
            maximum = JsonPrimitive("9007199254741003".toBigDecimal()), step = JsonPrimitive(1))
        val scale = ExactRangeScale.from(field)!!
        assertEquals("9007199254741000", scale.valueAtFraction(.7f).content)
        assertEquals(7, scale.indexOf(scale.valueAtFraction(.7f)))
        assertNull(scale.indexOf(JsonPrimitive("9007199254740993.1".toBigDecimal())))
    }
    @Test fun decimalStepsAndNonGridUpperBoundsHaveOnlyExactSelectableValues() {
        val field = ConfigurationField("amount", "数量", FieldWidget.RANGE, minimum = JsonPrimitive(.1), maximum = JsonPrimitive(.65), step = JsonPrimitive(.2))
        val scale = ExactRangeScale.from(field)!!
        assertEquals(2, scale.lastIndex)
        assertEquals("0.5", scale.valueAtFraction(1f).content)
        assertEquals(1, scale.indexOf(JsonPrimitive("0.3".toBigDecimal())))
        assertNull(ExactRangeScale.from(field.copy(maximum = JsonPrimitive("1e2147483647".toBigDecimal()))))
    }
}
