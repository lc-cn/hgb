package io.github.yysassist.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.content.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.*

@Composable
private fun FieldHeading(field: ConfigurationField, value: JsonElement?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            Text(field.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            if (field.recommended != null && sameConfigurationValue(value, field.recommended)) TonePill("推荐", LocalAssistPalette.current.gold)
        }
        val detail = listOfNotNull(field.description, field.unit?.takeIf { field.description == null }?.let { "单位：$it" }).firstOrNull()
        detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Exact decimal stepping; the middle stays editable so any allowed amount can still be typed. */
@Composable
private fun NumberStepper(field: ConfigurationField, input: String, isError: Boolean, enabled: Boolean, change: (JsonElement) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(UiSpace.medium)
    val step = field.step?.content?.toBigDecimalOrNull() ?: BigDecimal.ONE
    val minimum = field.minimum?.content?.toBigDecimalOrNull()
    val maximum = field.maximum?.content?.toBigDecimalOrNull()
    val current = input.toBigDecimalOrNull()
    fun moved(next: BigDecimal) = change(JsonPrimitive(next.toPlainString()))
    @Composable fun button(symbol: String, label: String, allowed: Boolean, action: () -> Unit) =
        Box(Modifier.size(UiSpace.hit).clickable(enabled = enabled && allowed, role = Role.Button, onClickLabel = label, onClick = action)
            .semantics { contentDescription = label }, Alignment.Center) {
            Text(symbol, color = if (enabled && allowed) colors.onSurface else colors.onSurfaceVariant.copy(alpha = .4f), style = MaterialTheme.typography.titleLarge)
        }
    Row(Modifier.clip(shape).background(colors.surfaceContainer).border(UiSpace.hairline, if (isError) colors.error else colors.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically) {
        button("−", "减少${field.label}", current == null || minimum == null || current - step >= minimum) {
            moved(current?.minus(step) ?: minimum ?: BigDecimal.ZERO)
        }
        BasicTextField(input, { change(JsonPrimitive(it)) }, Modifier.width(64.dp).semantics { contentDescription = field.label }, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(colors.onSurface), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        button("+", "增加${field.label}", current == null || maximum == null || current + step <= maximum) {
            moved(current?.plus(step) ?: minimum ?: BigDecimal.ZERO)
        }
    }
}

@Composable
internal fun ConfigurationFieldView(field: ConfigurationField, value: JsonElement?, errors: List<ContentIssue>, enabled: Boolean,
    landscape: Boolean, change: (JsonElement) -> Unit) {
    val input = (value as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content ?: ""
    val clearable = !field.required && value != null && value != JsonNull
    Column(verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        when (field.widget) {
            FieldWidget.NUMBER -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldHeading(field, value, Modifier.weight(1f))
                if (clearable) IconButton(enabled = enabled, onClick = { change(JsonNull) }) { Icon(AssistIcons.Close, "清除${field.label}") }
                NumberStepper(field, input, errors.isNotEmpty(), enabled, change)
            }
            FieldWidget.SWITCH -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FieldHeading(field, value, Modifier.weight(1f))
                AssistSwitch((value as? JsonPrimitive)?.booleanOrNull == true, enabled, field.label) { change(JsonPrimitive(it)) }
            }
            FieldWidget.TEXT, FieldWidget.RANGE -> {
                FieldHeading(field, value)
                OutlinedTextField(value = input, onValueChange = { change(JsonPrimitive(it)) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), singleLine = field.widget != FieldWidget.TEXT, shape = RoundedCornerShape(UiSpace.medium),
                    keyboardOptions = KeyboardOptions(keyboardType = if (field.widget == FieldWidget.TEXT) KeyboardType.Text else KeyboardType.Decimal),
                    isError = errors.isNotEmpty(),
                    trailingIcon = if (clearable) {
                        { IconButton(enabled = enabled, onClick = { change(JsonNull) }) { Icon(AssistIcons.Close, "清除${field.label}") } }
                    } else null,
                    suffix = field.unit?.let { unit -> { Text(unit) } })
                if (field.widget == FieldWidget.RANGE) {
                    val scale = remember(field) { ExactRangeScale.from(field) }
                    if (scale == null) Text("此任务的滑块范围无法精确显示，请更新任务设置", color = MaterialTheme.colorScheme.error)
                    else Slider(value = scale.fractionOf(value) ?: 0f, onValueChange = { change(scale.valueAtFraction(it)) },
                        enabled = enabled && scale.lastIndex > 0, modifier = Modifier.fillMaxWidth().semantics {
                            stateDescription = if (input.isEmpty()) "尚未设置${field.label}" else "$input${field.unit.orEmpty()}"
                        })
                }
            }
            FieldWidget.SINGLE_CHOICE, FieldWidget.MULTI_CHOICE -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FieldHeading(field, value, Modifier.weight(1f))
                    if (clearable) TextButton(enabled = enabled, onClick = { change(JsonNull) }) { Text("清除") }
                }
                val choices = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                val single = field.widget == FieldWidget.SINGLE_CHOICE
                FlowRow(Modifier.fillMaxWidth().then(if (single) Modifier.selectableGroup() else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                    field.options.forEach { option ->
                        val selected = if (single) value == JsonPrimitive(option.value) else option.value in choices
                        val action = {
                            if (single) change(JsonPrimitive(option.value))
                            else change(JsonArray((if (selected) choices - option.value else choices + option.value).map(::JsonPrimitive)))
                        }
                        val shape = RoundedCornerShape(10.dp)
                        val colors = MaterialTheme.colorScheme
                        Box((if (single) Modifier.selectable(selected, enabled, Role.RadioButton, onClick = action)
                            else Modifier.toggleable(selected, enabled, Role.Checkbox, onValueChange = { action() }))
                            .heightIn(min = UiSpace.hit).clip(shape).background(if (selected) colors.primary else colors.surfaceContainer)
                            .border(UiSpace.hairline, if (selected) colors.primary else colors.outlineVariant, shape)
                            .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(option.label, color = if (selected) colors.onPrimary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
            FieldWidget.TIME, FieldWidget.DATE, FieldWidget.DATE_RANGE -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FieldHeading(field, value, Modifier.weight(1f))
                    if (clearable) TextButton(enabled = enabled, onClick = { change(JsonNull) }) { Text("清除") }
                }
                CalendarField(field, value, input, enabled, landscape, change)
            }
        }
        errors.forEach { Text(it.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Number fields hold what the user typed as text, so `240` and "240" are the same value here. */
internal fun sameConfigurationValue(value: JsonElement?, recommended: JsonElement?): Boolean =
    if (value is JsonPrimitive && recommended is JsonPrimitive && value != JsonNull && recommended != JsonNull) value.content == recommended.content
    else value == recommended

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarField(field: ConfigurationField, value: JsonElement?, input: String, enabled: Boolean, landscape: Boolean,
    change: (JsonElement) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val range = value as? JsonObject
    val label = when (field.widget) {
        FieldWidget.DATE_RANGE -> if (range == null) "选择日期范围" else "${(range["start"] as? JsonPrimitive)?.content.orEmpty()} — ${(range["end"] as? JsonPrimitive)?.content.orEmpty()}"
        FieldWidget.TIME -> input.ifEmpty { "选择时刻" }
        else -> input.ifEmpty { "选择日期" }
    }
    OutlinedButton(enabled = enabled, onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = UiSpace.hit)) { Text(label) }
    if (!open) return
    when (field.widget) {
        FieldWidget.TIME -> {
            val selected = remember(input) { runCatching { LocalTime.parse(input) }.getOrNull() ?: LocalTime.now() }
            val state = rememberTimePickerState(selected.hour, selected.minute, DateFormat.is24HourFormat(LocalContext.current))
            AlertDialog(onDismissRequest = { open = false }, title = { Text(field.label) },
                text = { if (landscape) TimeInput(state) else TimePicker(state) },
                confirmButton = { TextButton(onClick = { change(JsonPrimitive("%02d:%02d".format(java.util.Locale.ROOT, state.hour, state.minute))); open = false }) { Text("确定") } },
                dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } })
        }
        FieldWidget.DATE -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = dateMillis(input),
                initialDisplayMode = if (landscape) DisplayMode.Input else DisplayMode.Picker, yearRange = 1..9999)
            DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = { change(JsonPrimitive(dateText(state.selectedDateMillis!!))); open = false }) { Text("确定") }
            }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) { DatePicker(state) }
        }
        FieldWidget.DATE_RANGE -> {
            val start = dateMillis((range?.get("start") as? JsonPrimitive)?.content.orEmpty())
            val end = dateMillis((range?.get("end") as? JsonPrimitive)?.content.orEmpty())
            val validOrder = (end == null || start != null) && (start == null || end == null || start <= end)
            val state = rememberDateRangePickerState(initialSelectedStartDateMillis = if (validOrder) start else null,
                initialSelectedEndDateMillis = if (validOrder) end else null,
                initialDisplayMode = if (landscape) DisplayMode.Input else DisplayMode.Picker, yearRange = 1..9999)
            DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
                    change(JsonObject(mapOf("start" to JsonPrimitive(dateText(state.selectedStartDateMillis!!)), "end" to JsonPrimitive(dateText(state.selectedEndDateMillis!!))))); open = false
                }) { Text("确定") }
            }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) { DateRangePicker(state, modifier = Modifier.heightIn(max = UiSpace.calendarMaxHeight)) }
        }
        else -> Unit
    }
}
private fun dateMillis(value: String): Long? = try { LocalDate.parse(value).takeIf { it.year in 1..9999 }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() }
catch (_: DateTimeException) { null }
private fun dateText(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString()
