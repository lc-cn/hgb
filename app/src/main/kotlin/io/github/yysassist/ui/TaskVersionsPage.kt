package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.yysassist.TaskPullState
import io.github.yysassist.VersionCatalogState
import io.github.yysassist.storage.TaskVersionCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TaskVersionEntry(state: VersionCatalogState, open: () -> Unit) {
    val catalog = (state as? VersionCatalogState.Ready)?.catalog
    val label = when (state) {
        VersionCatalogState.Loading -> "读取中"
        is VersionCatalogState.Failure -> "读取失败"
        is VersionCatalogState.Ready -> catalog?.activeVersion ?: "未启用"
    }
    TextButton(onClick = open, modifier = Modifier.widthIn(max = UiSpace.versionEntryMax).semantics {
        contentDescription = "任务版本，$label" + if (catalog?.hasKnownUpdate == true) "，有更新" else ""
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (catalog?.hasKnownUpdate == true) Box(Modifier.size(UiSpace.statusDot).background(LocalAssistPalette.current.error, CircleShape))
        }
    }
}

@Composable
fun TaskVersionsPage(state: VersionCatalogState, pull: TaskPullState, operation: String?, reload: () -> Unit,
    activate: (String) -> Unit, delete: (String) -> Unit) {
    when (state) {
        VersionCatalogState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is VersionCatalogState.Failure -> Column(Modifier.padding(UiSpace.page)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = reload) { Text("重新读取") }
        }
        is VersionCatalogState.Ready -> VersionRows(state.catalog, pull, operation, activate, delete)
    }
}

@Composable
private fun VersionRows(catalog: TaskVersionCatalog, pull: TaskPullState, operation: String?,
    activate: (String) -> Unit, delete: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = UiSpace.gap)) {
        if (pull is TaskPullState.Failure) item {
            Text(pull.message, Modifier.padding(horizontal = UiSpace.page, vertical = UiSpace.medium), color = MaterialTheme.colorScheme.error)
        }
        if (catalog.versions.isEmpty()) item {
            Column(Modifier.padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                Text("暂无本地任务版本", style = MaterialTheme.typography.bodyLarge)
                if (pull is TaskPullState.Finished) Text(pull.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(catalog.versions, key = { it.version }) { row ->
            var expanded by rememberSaveable(row.version) { mutableStateOf(false) }
            val active = row.version == catalog.activeVersion
            Column {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                    onClickLabel = if (expanded) "收起更新说明" else "展开更新说明") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "更新说明已展开" else "更新说明已收起" }
                    .heightIn(min = UiSpace.row).padding(horizontal = UiSpace.page, vertical = UiSpace.gap),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                        Text(row.version, style = MaterialTheme.typography.bodyLarge)
                        Text(versionDate(row.updatedAt), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !active && operation == null && catalog.versions.none { it.referenceCount != 0 },
                        onClick = { activate(row.version) }) { Text(if (active) "已启用" else "启用") }
                    TextButton(enabled = !active && row.referenceCount == 0 && operation == null,
                        onClick = { delete(row.version) }) { Text("删除") }
                }
                if (expanded) Text(row.changelog.ifBlank { "未提供更新说明" },
                    Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = UiSpace.medium),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun versionDate(value: String): String = try {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: java.time.DateTimeException) { value }
