package io.github.yysassist.core.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Editing text is kept verbatim; conversion happens only at validation/save, without rounding. */
fun normalizeConfigurationDraft(task: TaskDefinition, draft: JsonObject): JsonObject = JsonObject(draft.mapValues { (id, value) ->
    val field = task.fields.find { it.id == id }
    if (field?.widget in listOf(FieldWidget.NUMBER, FieldWidget.RANGE) && value is JsonPrimitive && value.isString) {
        if (value.content.isBlank()) JsonNull
        else value.content.toBigDecimalOrNull()?.let { JsonPrimitive(it) } ?: value
    } else value
})

fun configurationSchemaJson(task: TaskDefinition): String = Json.encodeToString(task.fields)

/** Float is a thumb position only. Selected amounts remain exact decimal values on the schema grid. */
class ExactRangeScale private constructor(private val minimum: BigDecimal, private val increment: BigDecimal, val lastIndex: Int,
    private val context: MathContext) {
    fun valueAt(index: Int): JsonPrimitive {
        require(index in 0..lastIndex)
        return JsonPrimitive(minimum.add(increment.multiply(BigDecimal(index), context), context))
    }
    fun indexOf(value: JsonElement?): Int? = try {
        val number = (value as? JsonPrimitive)?.let { if (it.isString) it.content.toBigDecimalOrNull() else it.decimalOrNull() }
        if (number == null || number < minimum || number > valueAt(lastIndex).decimalOrNull()!!) null
        else number.subtract(minimum, context).divide(increment, context).intValueExact().takeIf { it in 0..lastIndex }
    } catch (_: ArithmeticException) { null }
    fun valueAtFraction(fraction: Float): JsonPrimitive {
        require(fraction.isFinite() && fraction in 0f..1f)
        val index = BigDecimal(fraction.toString()).multiply(BigDecimal(lastIndex)).setScale(0, RoundingMode.HALF_UP).intValueExact()
        return valueAt(index)
    }
    fun fractionOf(value: JsonElement?): Float? = indexOf(value)?.let { if (lastIndex == 0) 0f else it.toFloat() / lastIndex.toFloat() }
    companion object {
        fun from(field: ConfigurationField): ExactRangeScale? = try {
            val min = field.minimum?.decimalOrNull()
            val max = field.maximum?.decimalOrNull()
            val step = field.step?.decimalOrNull()
            if (min == null || max == null || step == null || step <= BigDecimal.ZERO || max < min) null
            else {
                val context = MathContext(maxOf(min.precision(), max.precision(), step.precision()) + 10, RoundingMode.UNNECESSARY)
                val count = max.subtract(min, context).divideToIntegralValue(step, context).intValueExact()
                ExactRangeScale(min, step, count, context)
            }
        } catch (_: ArithmeticException) { null }
    }
}
