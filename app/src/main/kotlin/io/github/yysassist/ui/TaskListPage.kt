package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.VersionCatalogState
import io.github.yysassist.core.content.TaskBundle
import io.github.yysassist.core.content.TaskDefinition
import io.github.yysassist.core.execution.*
import io.github.yysassist.floating.FloatingPreferencesState
import io.github.yysassist.storage.ActiveTaskContent
import io.github.yysassist.storage.TaskAddress

private data class TaskGroup(val key: String, val name: String, val tasks: List<TaskDefinition>)

private fun taskGroups(bundle: TaskBundle): List<TaskGroup> = bundle.tasks.groupBy { it.targetId to it.category }.map { (id, tasks) ->
    val target = bundle.targets.single { it.id == id.first }
    val category = target.categories.single { it.id == id.second }.name
    TaskGroup("${id.first}/${id.second}", if (bundle.targets.size > 1) "${target.name} · $category" else category, tasks)
}

@Composable
internal fun TaskCenter(versions: VersionCatalogState, executor: ExecutorSnapshot, status: RuntimeStatus,
    enqueue: (TaskAddress) -> Unit, pause: (Long) -> Unit, stop: (Long) -> Unit, resume: (Long, ResumeMode) -> Unit,
    reload: () -> Unit, updates: () -> Unit, edit: (TaskAddress) -> Unit,
    favorites: FloatingPreferencesState = FloatingPreferencesState.Loading, toggleFavorite: (TaskAddress) -> Unit = {}) {
    when (versions) {
        VersionCatalogState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is VersionCatalogState.Failure -> Column(Modifier.padding(UiSpace.page)) {
            Text(versions.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = reload) { Text("重新读取") }
        }
        is VersionCatalogState.Ready -> when (val content = versions.catalog.activeContent) {
            ActiveTaskContent.None -> Column(Modifier.fillMaxSize()) {
                EmptyContent("暂无任务", "启用已取得的任务版本后，任务会出现在这里。")
                TextButton(onClick = updates, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("任务更新") }
            }
            is ActiveTaskContent.Invalid -> Column(Modifier.padding(UiSpace.page)) {
                Text(content.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = updates) { Text("任务更新") }
            }
            is ActiveTaskContent.Available -> TaskBrowser(content.bundle, executor, status, enqueue, pause, stop, resume, edit, favorites, toggleFavorite)
        }
    }
}

@Composable
private fun TaskBrowser(bundle: TaskBundle, executor: ExecutorSnapshot, status: RuntimeStatus,
    enqueue: (TaskAddress) -> Unit, pause: (Long) -> Unit, stop: (Long) -> Unit, resume: (Long, ResumeMode) -> Unit,
    edit: (TaskAddress) -> Unit, favorites: FloatingPreferencesState, toggleFavorite: (TaskAddress) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var accordion by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val favoriteSet = (favorites as? FloatingPreferencesState.Ready)?.settings?.favorites.orEmpty()
    val groups = remember(bundle) { taskGroups(bundle) }
    val text = query.trim()
    val visible = groups.mapNotNull { group ->
        group.tasks.filter { task -> (!onlyFavorites || TaskAddress(task.targetId, task.id) in favoriteSet) && (text.isEmpty() || task.name.contains(text, true)) }
            .takeIf { it.isNotEmpty() }?.let { group.copy(tasks = it) }
    }
    val filtering = text.isNotEmpty() || onlyFavorites
    @Composable fun row(task: TaskDefinition) = TaskRow(task, executor, status, TaskAddress(task.targetId, task.id) in favoriteSet,
        favorites is FloatingPreferencesState.Ready, enqueue, pause, stop, resume, edit, toggleFavorite)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= UiSpace.wide
        Column(Modifier.fillMaxSize()) {
            TaskToolbar(searching, query, { query = it }, { searching = true }, { searching = false; query = "" },
                onlyFavorites, { onlyFavorites = it }, accordion, { value ->
                    accordion = value
                    if (value) collapsed = ArrayList(groups.drop(1).map { it.key })
                }, visible.sumOf { it.tasks.size })
            if (visible.isEmpty()) EmptyContent("没有符合条件的任务")
            else if (wide) Row(Modifier.weight(1f).fillMaxWidth()) {
                val current = visible.firstOrNull { it.key == selectedGroup } ?: visible.first()
                Column(Modifier.width(168.dp).padding(horizontal = UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                    visible.forEach { group ->
                        val selected = group.key == current.key
                        Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { selectedGroup = group.key }.padding(horizontal = UiSpace.medium),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(group.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.SemiBold else null)
                            Text(group.tasks.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(current.tasks, key = { "${it.targetId}/${it.id}" }) { task -> row(task); RowDivider() }
                }
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.gap),
                verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                items(visible, key = { it.key }) { group ->
                    val open = filtering || group.key !in collapsed
                    AssistCard {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !filtering) {
                            collapsed = ArrayList(when {
                                group.key in collapsed && accordion -> groups.map { it.key } - group.key
                                group.key in collapsed -> collapsed - group.key
                                else -> collapsed + group.key
                            })
                        }.semantics { stateDescription = if (open) "已展开" else "已折叠" }.padding(horizontal = UiSpace.medium),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                            Text(group.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.tasks.size} 个", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Icon(AssistIcons.Chevron, null, Modifier.size(18.dp).rotate(if (open) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (open) group.tasks.forEach { task -> RowDivider(); row(task) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskToolbar(searching: Boolean, query: String, changeQuery: (String) -> Unit, openSearch: () -> Unit, closeSearch: () -> Unit,
    onlyFavorites: Boolean, setOnlyFavorites: (Boolean) -> Unit, accordion: Boolean, setAccordion: (Boolean) -> Unit, count: Int) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = UiSpace.medium, end = UiSpace.tiny, bottom = UiSpace.gap),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        if (searching) {
            OutlinedTextField(query, changeQuery, Modifier.weight(1f), singleLine = true, placeholder = { Text("搜索任务") },
                leadingIcon = { Icon(AssistIcons.Search, null) })
            IconButton(onClick = closeSearch) { Icon(AssistIcons.Close, "关闭搜索") }
        } else {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                GlassChoice("全部", !onlyFavorites, true) { setOnlyFavorites(false) }
                GlassChoice("常用", onlyFavorites, true) { setOnlyFavorites(true) }
            }
            Text("共 $count 个", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = openSearch) { Icon(AssistIcons.Search, "搜索任务") }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(AssistIcons.Sliders, "视图选项") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("只看常用") }, { setOnlyFavorites(!onlyFavorites) }, trailingIcon = { Switch(onlyFavorites, null) })
                    DropdownMenuItem({ Text("同时只展开一组") }, { setAccordion(!accordion) }, trailingIcon = { Switch(accordion, null) })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskDefinition, executor: ExecutorSnapshot, status: RuntimeStatus, favorite: Boolean, canFavorite: Boolean,
    enqueue: (TaskAddress) -> Unit, pause: (Long) -> Unit, stop: (Long) -> Unit, resume: (Long, ResumeMode) -> Unit,
    edit: (TaskAddress) -> Unit, toggleFavorite: (TaskAddress) -> Unit) {
    val address = TaskAddress(task.targetId, task.id)
    val ref = SubmissionRef(SubmissionKind.TASK, task.id)
    val current = executor.current?.let { it.request.context.targetId == task.targetId && it.request.ref == ref } == true
    val currentId = executor.current?.runId.takeIf { current }
    val queued = executor.waiting.any { it.request.context.targetId == task.targetId && it.request.ref == ref }
    val palette = LocalAssistPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.row).padding(end = UiSpace.gap), verticalAlignment = Alignment.CenterVertically) {
        IconToggleButton(favorite, { toggleFavorite(address) }, enabled = canFavorite) {
            Icon(AssistIcons.Star, if (favorite) "取消常用：${task.name}" else "设为常用：${task.name}",
                tint = (if (favorite) palette.gold else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = .86f))
        }
        Column(Modifier.weight(1f).heightIn(min = UiSpace.hit).clickable { edit(address) }.padding(vertical = UiSpace.gap),
            verticalArrangement = Arrangement.Center) {
            Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text("${task.steps.size} 个步骤", maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (queued || current) Text(if (queued) "队列中" else if (status is RuntimeStatus.Waiting) "等待中" else phaseLabel(executor.phase),
            Modifier.padding(horizontal = UiSpace.gap), style = MaterialTheme.typography.labelSmall,
            color = if (queued) MaterialTheme.colorScheme.onSurfaceVariant else palette.blue)
        key(currentId) {
            RunActionButton(task.name, current, queued,
                status != RuntimeStatus.Loading && status !is RuntimeStatus.Failure &&
                    (!current || executor.phase in setOf(ExecutorPhase.RUNNING, ExecutorPhase.PAUSED)),
                { enqueue(address) }, { currentId?.let(pause) }, { currentId?.let(stop) },
                paused = current && executor.phase == ExecutorPhase.PAUSED,
                resume = { mode -> currentId?.let { resume(it, mode) } },
                currentStateLabel = if (current && status is RuntimeStatus.Waiting) "等待中" else null)
        }
    }
}
