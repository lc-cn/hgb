package io.github.yysassist.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import io.github.yysassist.core.content.*
import kotlinx.serialization.json.*
import java.time.*

@Composable
internal fun ConfigurationFieldView(field: ConfigurationField, value: JsonElement?, errors: List<ContentIssue>, enabled: Boolean,
    landscape: Boolean, change: (JsonElement) -> Unit) {
    val input = (value as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content ?: ""
    val textField = field.widget in listOf(FieldWidget.TEXT, FieldWidget.NUMBER, FieldWidget.RANGE)
    Column(verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        if (!textField) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label + if (field.required) " *" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (!field.required && value != null && value != JsonNull)
                TextButton(enabled = enabled, onClick = { change(JsonNull) }) { Text("清除") }
        }
        when (field.widget) {
            FieldWidget.TEXT, FieldWidget.NUMBER, FieldWidget.RANGE -> {
                OutlinedTextField(value = input, onValueChange = { change(JsonPrimitive(it)) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), singleLine = field.widget != FieldWidget.TEXT,
                    keyboardOptions = KeyboardOptions(keyboardType = if (field.widget == FieldWidget.TEXT) KeyboardType.Text else KeyboardType.Decimal),
                    isError = errors.isNotEmpty(), label = { Text(field.label + if (field.required) " *" else "") },
                    trailingIcon = if (!field.required && value != null && value != JsonNull) {
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
                val choices = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                val single = field.widget == FieldWidget.SINGLE_CHOICE
                @Composable fun option(option: FieldOption, modifier: Modifier) {
                    val selected = if (single) value == JsonPrimitive(option.value) else option.value in choices
                    val action = {
                        if (single) change(JsonPrimitive(option.value))
                        else change(JsonArray((if (selected) choices - option.value else choices + option.value).map(::JsonPrimitive)))
                    }
                    val semantics = if (single) modifier.selectable(selected, enabled, Role.RadioButton, onClick = action)
                        else modifier.toggleable(selected, enabled, Role.Checkbox, onValueChange = { action() })
                    val shape = RoundedCornerShape(UiSpace.medium)
                    Box(semantics.heightIn(min = UiSpace.hit).clip(shape).background(glassBrush(selected))
                        .border(androidx.compose.foundation.BorderStroke(UiSpace.hairline, MaterialTheme.colorScheme.outlineVariant), shape)
                        .padding(horizontal = UiSpace.medium, vertical = UiSpace.gap), contentAlignment = Alignment.Center) {
                        Text(option.label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (landscape) FlowRow(Modifier.fillMaxWidth().then(if (single) Modifier.selectableGroup() else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                    field.options.forEach { option(it, Modifier) }
                } else Column(Modifier.fillMaxWidth().then(if (single) Modifier.selectableGroup() else Modifier),
                    verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                    field.options.forEach { option(it, Modifier.fillMaxWidth()) }
                }
            }
            FieldWidget.TIME, FieldWidget.DATE, FieldWidget.DATE_RANGE -> CalendarField(field, value, input, enabled, landscape, change)
        }
        errors.forEach { Text(it.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

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
