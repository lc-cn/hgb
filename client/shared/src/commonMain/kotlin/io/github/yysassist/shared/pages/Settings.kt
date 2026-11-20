package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.protocol.CloudTask
import io.github.yysassist.protocol.ConfigView
import io.github.yysassist.protocol.GameAccountView
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException

/** A setting a task declares, as the server sends it in [ConfigView.fields]. */
@Serializable
private data class FieldDef(val id: String, val label: String, val widget: String, val required: Boolean = false, val description: String? = null,
    val options: List<FieldOption> = emptyList(), val minimum: Double? = null, val maximum: Double? = null, val step: Double? = null,
    val recommended: JsonElement? = null, val readOnly: Boolean = false)
@Serializable private data class FieldOption(val value: String, val label: String)
private val fieldJson = Json { ignoreUnknownKeys = true }

/** The account's settings for one task. Fields come from the newest uploaded version that has the task. */
@Composable
fun SettingsForm(account: GameAccountView, task: CloudTask, titled: Boolean = true) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    var view by remember { mutableStateOf<ConfigView?>(null) }
    var values by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var fresh by remember { mutableStateOf(emptySet<String>()) }
    var dirty by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fields = remember(view) { view?.fields?.mapNotNull { runCatching { fieldJson.decodeFromJsonElement<FieldDef>(it) }.getOrNull() }.orEmpty() }
    LaunchedEffect(account.id, task) {
        try {
            val loaded = console.api.config(account.id, task)
            // A field never saved starts from its recommended value, marked until it is saved.
            val defs = loaded.fields.mapNotNull { runCatching { fieldJson.decodeFromJsonElement<FieldDef>(it) }.getOrNull() }
            val start = mutableMapOf<String, JsonElement>(); val filled = mutableSetOf<String>()
            defs.forEach { def ->
                loaded.values[def.id]?.let { start[def.id] = it } ?: def.recommended?.takeIf { it !is JsonNull }?.let { start[def.id] = it; filled += def.id }
            }
            view = loaded; values = start; fresh = filled; dirty = filled.isNotEmpty()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (problem: Exception) { loadError = io.github.yysassist.shared.failureText(problem) }
    }
    fun change(id: String, value: JsonElement?) { values = if (value == null) values - id else values + (id to value); dirty = true; saveError = null }
    Card {
        Column(Modifier.padding(horizontal = Space.s5, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            if (titled) Text("${task.name} · 设置", style = MaterialTheme.typography.titleMedium)
            val loaded = view
            when {
                loadError != null -> Muted(loadError!!)
                loaded == null -> Muted("正在读取设置…")
                fields.isEmpty() -> Muted("这个任务不需要设置。")
                else -> {
                    fields.forEach { def ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(def.label + if (def.required) "（必填）" else "", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            def.description?.let { Muted(it) }
                            SettingInput(def, values[def.id]) { change(def.id, it) }
                        }
                    }
                    // The button says the state: 已保存 (nothing to do) or 保存; only a failure, or values filled in for the
                    // user, gets a line beside it.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        val note = saveError ?: if (dirty && fresh.isNotEmpty()) "已填入推荐值" else null
                        Text(note.orEmpty(), Modifier.weight(1f), color = if (saveError != null) tokens.danger else tokens.muted, style = MaterialTheme.typography.bodySmall)
                        HgbButton(if (dirty) "保存" else "已保存", {
                            busy = true
                            console.act {
                                try {
                                    val saved = console.api.saveConfig(account.id, task, JsonObject(values), loaded.revision)
                                    view = saved; fresh = emptySet(); dirty = false
                                } catch (failure: CloudRequestException) { saveError = failure.message } finally { busy = false }
                            }
                        }, ButtonKind.PRIMARY, enabled = dirty && !busy)
                    }
                }
            }
        }
    }
}

/** One input per setting the task declares; null means the field is left out of the save. */
@Composable
private fun SettingInput(def: FieldDef, value: JsonElement?, change: (JsonElement?) -> Unit) {
    val enabled = !def.readOnly
    when (def.widget) {
        "SINGLE_CHOICE" -> if (enabled) Segments(def.options.map { it.value to it.label }, (value as? JsonPrimitive)?.contentOrNull) { change(JsonPrimitive(it)) }
            else Muted(def.options.firstOrNull { it.value == (value as? JsonPrimitive)?.contentOrNull }?.label ?: "—")
        "MULTI_CHOICE" -> {
            val chosen = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
            FlowRow { def.options.forEach { option ->
                Check(option.label, option.value in chosen, { on ->
                    change(JsonArray(def.options.map { it.value }.filter { if (it == option.value) on else it in chosen }.map(::JsonPrimitive)))
                }, enabled)
            } }
        }
        "SWITCH" -> HgbSwitch((value as? JsonPrimitive)?.booleanOrNull == true, { change(JsonPrimitive(it)) }, enabled)
        "NUMBER", "RANGE" -> {
            var text by remember(value) { mutableStateOf((value as? JsonPrimitive)?.doubleOrNull?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
            Column {
                TextInput(text, { next ->
                    text = next.filter { it.isDigit() || it == '.' || it == '-' }
                    change(text.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) JsonPrimitive(it.toLong()) else JsonPrimitive(it) })
                }, listOfNotNull(def.minimum?.let { "最小 ${it.clean()}" }, def.maximum?.let { "最大 ${it.clean()}" }).joinToString("，"), Modifier.width(160.dp))
            }
        }
        "TEXT", "TIME", "DATE" -> TextInput((value as? JsonPrimitive)?.contentOrNull.orEmpty(), { change(it.ifEmpty { null }?.let(::JsonPrimitive)) },
            when (def.widget) { "TIME" -> "08:30"; "DATE" -> "2026-01-31"; else -> "" }, Modifier.widthIn(max = 320.dp))
        else -> Muted("这个设置暂时只能在 App 的开发页里修改")
    }
}
private fun Double.clean() = if (this % 1.0 == 0.0) toLong().toString() else toString()
