package io.github.yysassist.core.content

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Validation never fills a default, clamps a budget, drops an unknown value or mutates the draft. */
fun validateConfiguration(task: TaskDefinition, values: JsonObject): List<ContentIssue> = buildList {
    values.keys.filter { key -> task.fields.none { it.id == key } }.forEach {
        add(ContentIssue(it, "任务版本中没有此字段，需要核对配置兼容性"))
    }
    task.fields.forEach { field ->
        fun invalid(reason: String) { add(ContentIssue(field.id, reason)) }
        val value = values[field.id]
        if (value == null || value == JsonNull) {
            if (field.required) invalid("请填写${field.label}")
            return@forEach
        }
        when (field.widget) {
            FieldWidget.SINGLE_CHOICE -> {
                val choice = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (field.options.none { it.value == choice }) invalid("请选择有效选项")
            }
            FieldWidget.MULTI_CHOICE -> {
                val choices = value as? JsonArray
                if (choices == null || choices.any { item -> item !is JsonPrimitive || !item.isString || field.options.none { it.value == item.content } })
                    invalid("请使用有效的多选项")
                else if (choices.distinct().size != choices.size) invalid("选项不能重复")
                else if (field.required && choices.isEmpty()) invalid("请至少选择一项")
            }
            FieldWidget.NUMBER, FieldWidget.RANGE -> {
                val number = (value as? JsonPrimitive)?.decimalOrNull()
                val minimum = field.minimum?.decimalOrNull()
                val maximum = field.maximum?.decimalOrNull()
                val increment = field.step?.decimalOrNull()
                if (number == null) invalid("请输入有效数字")
                else if (minimum != null && number < minimum || maximum != null && number > maximum) invalid("数值超出任务规定范围")
                else if (increment != null) {
                    // Bounded arithmetic avoids expanding pathological exponent-form input into huge buffers.
                    val matches = try {
                        val context = MathContext(maxOf(number.precision(), minimum?.precision() ?: 1, increment.precision()) + 1,
                            RoundingMode.UNNECESSARY)
                        number.subtract(minimum ?: BigDecimal.ZERO, context)
                            .remainder(increment, context).compareTo(BigDecimal.ZERO) == 0
                    } catch (_: ArithmeticException) { false }
                    if (!matches) invalid("数值不符合任务规定步长或无法精确校验")
                }
            }
            FieldWidget.TIME -> {
                val text = value.stringValue()
                if (text == null || !Regex("\\d{2}:\\d{2}").matches(text) || !validTime(text)) invalid("请选择有效时刻")
            }
            FieldWidget.DATE -> if (!validDate(value.stringValue())) invalid("请选择有效日期")
            FieldWidget.DATE_RANGE -> {
                val range = value as? JsonObject
                val start = range?.get("start")?.stringValue()
                val end = range?.get("end")?.stringValue()
                if (range == null || range.keys != setOf("start", "end") || !validDate(start) || !validDate(end)) invalid("请选择有效日期范围")
                else if (LocalDate.parse(start) > LocalDate.parse(end)) invalid("结束日期不能早于开始日期")
            }
            FieldWidget.TEXT -> {
                val text = value.stringValue()
                if (text == null) invalid("请输入文本")
                else if (field.required && text.isBlank()) invalid("请填写${field.label}")
            }
        }
    }
}

private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun validDate(text: String?): Boolean = try { text != null && LocalDate.parse(text).toString() == text }
catch (_: DateTimeParseException) { false }
private fun validTime(text: String): Boolean = try { LocalTime.parse(text); true }
catch (_: DateTimeParseException) { false }
