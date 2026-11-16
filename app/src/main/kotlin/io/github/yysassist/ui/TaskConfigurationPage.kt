package io.github.yysassist.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import io.github.yysassist.TaskEditorState
import kotlinx.serialization.json.JsonElement

@Composable
internal fun TaskConfigurationPage(state: TaskEditorState, wide: Boolean, reload: () -> Unit,
    change: (String, JsonElement) -> Unit, save: () -> Unit) {
    when (state) {
        TaskEditorState.Loading -> Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        is TaskEditorState.Failure -> Column(Modifier.padding(UiSpace.page)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = reload) { Text("重新读取") }
        }
        is TaskEditorState.Ready -> {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            Column(Modifier.fillMaxSize().imePadding()) {
                if (wide && landscape) Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.weight(.24f), contentPadding = PaddingValues(UiSpace.page)) {
                        item { TaskSteps(state, expandedInitially = true) }
                    }
                    VerticalDivider()
                    ConfigurationFields(state, true, Modifier.weight(.76f).fillMaxHeight(), false, change, reload)
                } else ConfigurationFields(state, landscape, Modifier.weight(1f).fillMaxWidth(), true, change, reload)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = UiSpace.gap)) {
                    Button(enabled = !state.saving && (state.dirty || state.snapshot.revision == 0L), onClick = save,
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiSpace.hit)) {
                        Text(if (state.saving) "保存中" else "保存规则")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationFields(state: TaskEditorState.Ready, landscape: Boolean, modifier: Modifier,
    includeSteps: Boolean, change: (String, JsonElement) -> Unit, reload: () -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.section)) {
        if (includeSteps) item { TaskSteps(state, false) }
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
        items(state.snapshot.task.fields.chunked(if (landscape) 2 else 1), key = { it.first().id }) { fields ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpace.section)) {
                fields.forEach { field ->
                    Box(Modifier.weight(1f)) {
                        ConfigurationFieldView(field, state.values[field.id], state.issues.filter { it.location == field.id }, !state.saving, landscape) { change(field.id, it) }
                    }
                }
                if (landscape && fields.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TaskSteps(state: TaskEditorState.Ready, expandedInitially: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(expandedInitially) }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        TextButton(onClick = { expanded = !expanded }) { Text("执行步骤 · ${state.snapshot.task.steps.size} 步") }
        if (expanded) state.snapshot.task.steps.forEachIndexed { index, step ->
            Text("${index + 1}. ${step.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
