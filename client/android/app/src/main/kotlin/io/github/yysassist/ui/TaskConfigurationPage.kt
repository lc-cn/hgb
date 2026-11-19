package io.github.yysassist.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import io.github.yysassist.core.content.ConfigurationField
import io.github.yysassist.core.content.isVisible
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.TaskEditorState
import kotlinx.serialization.json.JsonElement

@Composable
internal fun TaskConfigurationPage(state: TaskEditorState, wide: Boolean, notice: String, reload: () -> Unit,
    change: (String, JsonElement) -> Unit, save: () -> Unit) {
    when (state) {
        TaskEditorState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is TaskEditorState.Failure -> Column(Modifier.padding(UiSpace.page)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = reload) { Text("重新读取") }
        }
        is TaskEditorState.Ready -> {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            // A task never configured before opens on its recommended values; nothing is stored until the user saves.
            LaunchedEffect(state.snapshot.address, state.snapshot.revision) {
                if (state.snapshot.revision == 0L) state.snapshot.task.fields.forEach { field ->
                    if (field.recommended != null && state.values[field.id] == null) change(field.id, field.recommended!!)
                }
            }
            val tabs = state.snapshot.task.fields.mapNotNull { it.tab }.distinct()
            var tabName by rememberSaveable(state.snapshot.address.taskId) { mutableStateOf(tabs.firstOrNull()) }
            Column(Modifier.fillMaxSize().imePadding()) {
                if (tabs.size > 1) Row(Modifier.padding(start = UiSpace.medium, end = UiSpace.medium, top = UiSpace.medium).fillMaxWidth()
                    .clip(RoundedCornerShape(UiSpace.medium)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(UiSpace.hairline, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(UiSpace.medium)).padding(3.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                    tabs.forEach { tab ->
                        val active = tab == tabName
                        Box(Modifier.weight(1f).heightIn(min = UiSpace.hit).clip(RoundedCornerShape(9.dp))
                            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .selectable(active, role = Role.Tab) { tabName = tab }, Alignment.Center) {
                            Text(tab, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Text(notice, Modifier.fillMaxWidth().padding(start = UiSpace.medium, end = UiSpace.medium, top = UiSpace.gap),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                ConfigurationFields(state, state.snapshot.task.fields.filter { (tabs.size < 2 || it.tab == tabName) && it.isVisible(state.values) }, landscape, Modifier.weight(1f).fillMaxWidth(), change, reload)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = UiSpace.medium, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val recommended = state.snapshot.task.fields.filter { it.recommended != null }
                    if (recommended.isNotEmpty()) OutlinedButton(enabled = !state.saving,
                        onClick = { recommended.forEach { change(it.id, it.recommended!!) } }, shape = RoundedCornerShape(UiSpace.medium),
                        modifier = Modifier.weight(1f).heightIn(min = UiSpace.hit)) { Text("恢复推荐值", fontWeight = FontWeight.SemiBold) }
                    Button(enabled = !state.saving && (state.dirty || state.snapshot.revision == 0L), onClick = save, shape = RoundedCornerShape(UiSpace.medium),
                        modifier = Modifier.weight(1.4f).heightIn(min = UiSpace.hit)) {
                        Text(if (state.saving) "保存中" else "保存", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationFields(state: TaskEditorState.Ready, shown: List<ConfigurationField>, landscape: Boolean, modifier: Modifier,
    change: (String, JsonElement) -> Unit, reload: () -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(UiSpace.medium), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        state.message?.let { message -> item {
            Column {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (state.canReload) TextButton(enabled = !state.saving, onClick = reload) { Text("重新读取") }
            }
        } }
        if (state.issues.any { issue -> state.snapshot.task.fields.none { it.id == issue.location } }) item {
            Text("部分原设置与当前任务版本不兼容，原值已保留，可在任务更新中切换版本。", color = MaterialTheme.colorScheme.error)
        }
        if (state.snapshot.task.fields.isEmpty()) item { Text("此任务无需额外设置", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown.groupBy { it.section }.toList(), key = { it.first ?: "" }) { (section, fields) ->
            section?.let { SectionLabel(it) }
            AssistCard {
                fields.chunked(if (landscape) 2 else 1).forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(UiSpace.medium), horizontalArrangement = Arrangement.spacedBy(UiSpace.section)) {
                        row.forEach { field ->
                            Box(Modifier.weight(1f)) {
                                ConfigurationFieldView(field, state.values[field.id], state.issues.filter { it.location == field.id }, !state.saving && !field.readOnly, landscape) { change(field.id, it) }
                            }
                        }
                        if (landscape && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
