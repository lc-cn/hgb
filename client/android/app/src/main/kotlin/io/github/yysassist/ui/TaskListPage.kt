package io.github.yysassist.ui

import io.github.yysassist.core.content.userTasks
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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

private data class TaskGroup(val key: String, val name: String, val index: Int, val tasks: List<TaskDefinition>)

private fun taskGroups(bundle: TaskBundle): List<TaskGroup> = bundle.userTasks.groupBy { it.targetId to it.category }.map { (id, tasks) ->
    val target = bundle.targets.single { it.id == id.first }
    val index = target.categories.indexOfFirst { it.id == id.second }
    val category = target.categories[index].name
    TaskGroup("${id.first}/${id.second}", if (bundle.targets.size > 1) "${target.name} · $category" else category, index, tasks)
}

private val GroupIcons = listOf(AssistIcons.Home, AssistIcons.Gift, AssistIcons.Sword, AssistIcons.Shield, AssistIcons.Soul)

@Composable
private fun groupTint(index: Int): Color = with(LocalAssistPalette.current) { listOf(blue, gold, rose, teal, violet, orange)[index % 6] }
private fun groupIcon(index: Int): ImageVector = GroupIcons[index % GroupIcons.size]

@Composable
internal fun TaskCenter(versions: VersionCatalogState, executor: ExecutorSnapshot, status: RuntimeStatus,
    enqueue: (TaskAddress) -> Unit, reload: () -> Unit, updates: () -> Unit, edit: (TaskAddress) -> Unit,
    favorites: FloatingPreferencesState = FloatingPreferencesState.Loading, toggleFavorite: (TaskAddress) -> Unit = {},
    openHistory: () -> Unit = {}, favoritesRequest: Int = 0, addToPlan: (TaskAddress) -> Unit = {}) {
    @Composable fun plainTitle() = AppHeader("任务", false, {}) {}
    when (versions) {
        VersionCatalogState.Loading -> Column(Modifier.fillMaxSize()) {
            plainTitle(); Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        }
        is VersionCatalogState.Failure -> Column(Modifier.fillMaxSize()) {
            plainTitle()
            Text(versions.message, Modifier.padding(horizontal = UiSpace.page), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = reload, modifier = Modifier.padding(horizontal = UiSpace.gap)) { Text("重新读取") }
        }
        is VersionCatalogState.Ready -> when (val content = versions.catalog.activeContent) {
            ActiveTaskContent.None -> Column(Modifier.fillMaxSize()) {
                plainTitle()
                EmptyContent("暂无任务", "启用已取得的任务版本后，任务会出现在这里。")
                TextButton(onClick = updates, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("任务更新") }
            }
            is ActiveTaskContent.Invalid -> Column(Modifier.fillMaxSize()) {
                plainTitle()
                Text(content.message, Modifier.padding(horizontal = UiSpace.page), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = updates, modifier = Modifier.padding(horizontal = UiSpace.gap)) { Text("任务更新") }
            }
            is ActiveTaskContent.Available -> TaskBrowser(content.bundle, executor, status, enqueue, edit, favorites, toggleFavorite, openHistory, favoritesRequest, addToPlan)
        }
    }
}

@Composable
private fun TaskBrowser(bundle: TaskBundle, executor: ExecutorSnapshot, status: RuntimeStatus,
    enqueue: (TaskAddress) -> Unit, edit: (TaskAddress) -> Unit, favorites: FloatingPreferencesState,
    toggleFavorite: (TaskAddress) -> Unit, openHistory: () -> Unit, favoritesRequest: Int, addToPlan: (TaskAddress) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var accordion by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var openMenu by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(favoritesRequest) { if (favoritesRequest > 0) onlyFavorites = true }
    val favoriteSet = (favorites as? FloatingPreferencesState.Ready)?.settings?.favorites.orEmpty()
    val groups = remember(bundle) { taskGroups(bundle) }
    val text = query.trim()
    val visible = groups.mapNotNull { group ->
        group.tasks.filter { task -> (!onlyFavorites || TaskAddress(task.targetId, task.id) in favoriteSet) && (text.isEmpty() || task.name.contains(text, true)) }
            .takeIf { it.isNotEmpty() }?.let { group.copy(tasks = it) }
    }
    val filtering = text.isNotEmpty() || onlyFavorites
    val count = visible.sumOf { it.tasks.size }
    @Composable fun bar(title: String, large: Boolean) = TaskTopBar(title, large, searching, query, { query = it }, { searching = true },
        { searching = false; query = "" }, openHistory, onlyFavorites, { onlyFavorites = it }, accordion, { value ->
            accordion = value
            if (value) collapsed = ArrayList(groups.drop(1).map { it.key })
        })
    @Composable fun row(task: TaskDefinition) {
        val id = "${task.targetId}/${task.id}"
        TaskRow(task, executor, status, TaskAddress(task.targetId, task.id) in favoriteSet, favorites is FloatingPreferencesState.Ready,
            openMenu == id, { openMenu = if (openMenu == id) null else id }, enqueue, edit, toggleFavorite, openHistory, addToPlan)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Measured beside the navigation and run panel, so this is narrower than the window breakpoint.
        if (maxWidth >= 480.dp) Row(Modifier.fillMaxSize()) {
            val current = visible.firstOrNull { it.key == selectedGroup } ?: visible.firstOrNull()
            Column(Modifier.width(148.dp).fillMaxHeight().padding(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                visible.forEach { group ->
                    val selected = group.key == current?.key
                    Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                        .clickable { selectedGroup = group.key }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(group.tasks.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                bar(current?.name ?: "任务", false)
                if (current == null) EmptyContent("没有符合条件的任务")
                else LazyColumn(Modifier.weight(1f)) {
                    items(current.tasks, key = { "${it.targetId}/${it.id}" }) { task -> RowDivider(); row(task) }
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            bar("任务", true)
            Row(Modifier.fillMaxWidth().padding(start = UiSpace.page, end = UiSpace.page, bottom = 10.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                GlassChoice("全部", !onlyFavorites, true) { onlyFavorites = false }
                GlassChoice("常用", onlyFavorites, true) { onlyFavorites = true }
                Spacer(Modifier.weight(1f))
                Text("共 $count 个", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (visible.isEmpty()) EmptyContent("没有符合条件的任务")
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.gap),
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
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
                            IconTile(groupIcon(group.index), groupTint(group.index))
                            Text(group.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.tasks.size} 个", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Icon(AssistIcons.Chevron, null, Modifier.size(UiSpace.icon).rotate(if (open) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (open) group.tasks.forEach { task -> RowDivider(); row(task) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskTopBar(title: String, large: Boolean, searching: Boolean, query: String, changeQuery: (String) -> Unit, openSearch: () -> Unit,
    closeSearch: () -> Unit, openHistory: () -> Unit, onlyFavorites: Boolean, setOnlyFavorites: (Boolean) -> Unit,
    accordion: Boolean, setAccordion: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (searching) UiSpace.medium else UiSpace.page, end = UiSpace.tiny),
        verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            BasicTextField(query, changeQuery, Modifier.weight(1f).semantics { contentDescription = "搜索任务" }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.onSurface),
                decorationBox = { field ->
                    Row(Modifier.height(40.dp).clip(RoundedCornerShape(UiSpace.medium)).background(colors.surfaceContainerHigh)
                        .border(UiSpace.hairline, colors.outlineVariant, RoundedCornerShape(UiSpace.medium)).padding(horizontal = UiSpace.medium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                        Icon(AssistIcons.Search, null, Modifier.size(UiSpace.icon), tint = colors.onSurfaceVariant)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("搜索任务", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            field()
                        }
                    }
                })
            IconButton(onClick = closeSearch) { Icon(AssistIcons.Close, "关闭搜索", tint = colors.onSurfaceVariant) }
        } else {
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
            IconButton(onClick = openSearch) { Icon(AssistIcons.Search, "搜索任务", tint = colors.onSurfaceVariant) }
            IconButton(onClick = openHistory) { Icon(AssistIcons.History, "运行记录", tint = colors.onSurfaceVariant) }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(AssistIcons.Sliders, "视图选项", tint = colors.onSurfaceVariant) }
                DropdownMenu(menu, { menu = false }, Modifier.width(270.dp), containerColor = colors.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    DropdownMenuItem({ Text("只看常用") }, { setOnlyFavorites(!onlyFavorites) }, trailingIcon = { AssistSwitch(onlyFavorites) { setOnlyFavorites(it) } })
                    DropdownMenuItem({ Text("同时只展开一组") }, { setAccordion(!accordion) }, trailingIcon = { AssistSwitch(accordion) { setAccordion(it) } })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskDefinition, executor: ExecutorSnapshot, status: RuntimeStatus, favorite: Boolean, canFavorite: Boolean,
    menuOpen: Boolean, toggleMenu: () -> Unit, enqueue: (TaskAddress) -> Unit, edit: (TaskAddress) -> Unit,
    toggleFavorite: (TaskAddress) -> Unit, openHistory: () -> Unit, addToPlan: (TaskAddress) -> Unit) {
    val address = TaskAddress(task.targetId, task.id)
    val ref = SubmissionRef(SubmissionKind.TASK, task.id)
    val current = executor.current?.let { it.request.context.targetId == task.targetId && it.request.ref == ref } == true
    val queued = executor.waiting.any { it.request.context.targetId == task.targetId && it.request.ref == ref }
    val palette = LocalAssistPalette.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.row).padding(end = UiSpace.tiny), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(favorite, { toggleFavorite(address) }, enabled = canFavorite) {
                Icon(if (favorite) AssistIcons.StarFilled else AssistIcons.Star, if (favorite) "取消常用：${task.name}" else "设为常用：${task.name}",
                    tint = if (favorite) palette.gold else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).heightIn(min = UiSpace.hit).clickable(onClick = toggleMenu).padding(vertical = UiSpace.gap),
                verticalArrangement = Arrangement.Center) {
                Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                task.description?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            // The running task is controlled from the status bar, so its row only reports state.
            if (queued) Box(Modifier.padding(end = UiSpace.gap)) { TonePill("队列中", palette.warning) }
            else if (current) Box(Modifier.padding(end = UiSpace.gap)) {
                TonePill(if (status is RuntimeStatus.Waiting) "等待中" else if (executor.phase == ExecutorPhase.RUNNING) "运行中" else phaseLabel(executor.phase), palette.blue)
            }
            else if (task.trigger != null) Box(Modifier.padding(end = UiSpace.gap)) { TonePill("被动", palette.violet) }
            else RunActionButton(task.name, false, false, status != RuntimeStatus.Loading && status !is RuntimeStatus.Failure, { enqueue(address) }, {}, {})
        }
        if (menuOpen) Row(Modifier.padding(start = 48.dp, end = UiSpace.medium, bottom = UiSpace.medium), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            listOfNotNull("配置" to { edit(address) }, ("加入计划" to { addToPlan(address) }).takeIf { task.trigger == null }, "运行记录" to openHistory).forEach { (label, action) ->
                Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = action).padding(horizontal = UiSpace.medium), Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
