package io.github.yysassist.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.Principal
import io.github.yysassist.device.GameAccessibilityService
import io.github.yysassist.device.ScreenshotCheck
import io.github.yysassist.floating.*
import io.github.yysassist.storage.ActiveTaskContent
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.launch
import java.io.IOException
import android.accessibilityservice.AccessibilityService
import io.github.yysassist.auth.GitHubSessionState

private enum class Page(val title: String, val icon: ImageVector) {
    HOME("工作台", AssistIcons.Home), TASKS("任务中心", AssistIcons.Tasks),
    PLANS("托管计划", AssistIcons.Calendar), HISTORY("历史记录", AssistIcons.Bell),
    SETTINGS("设置", AssistIcons.Settings),
}

@Composable
fun AssistApp(application: AssistApplication, navigation: AppNavigation? = null, navigationHandled: () -> Unit = {}) {
    val appearance by application.appearance.state.collectAsState()
    val executor by application.executor.state.collectAsState()
    val runtimeStatus by application.executor.status.collectAsState()
    val versions by application.taskUpdates.catalog.collectAsState()
    val floating by application.floating.state.collectAsState()
    val pull by application.taskUpdates.pull.collectAsState()
    val versionOperation by application.taskUpdates.operation.collectAsState()
    val taskDrafts by application.taskEditor.drafts.collectAsState()
    val github by application.github.state.collectAsState()
    val mode = (appearance as? AppearanceState.Ready)?.mode ?: ThemeMode.SYSTEM
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(Page.HOME.name) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var historyTab by rememberSaveable { mutableIntStateOf(0) }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editTask by rememberSaveable { mutableStateOf<String?>(null) }
    val address = editTarget?.let { target -> editTask?.let { TaskAddress(target, it) } }
    val editor = address?.let { taskDrafts[it] } ?: TaskEditorState.Loading
    LaunchedEffect(address) { address?.let { application.taskEditor.open(it) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(application.taskUpdates) { application.taskUpdates.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(application.taskEditor) { application.taskEditor.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(navigation?.id) { navigation?.let { editTarget = it.task.targetId; editTask = it.task.taskId; selected = Page.TASKS.name; detail = "任务配置"; navigationHandled() } }
    LaunchedEffect(application.floating) { application.floating.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(application.executor) { application.executor.messages.collect { snackbar.showSnackbar(it) } }
    val page = Page.valueOf(selected)
    BackHandler(detail != null || page != Page.HOME) {
        if (detail != null) detail = null else selected = Page.HOME.name
    }
    AssistTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= UiSpace.wide
                Row(Modifier.fillMaxSize()) {
                    if (wide && detail == null) MainNavigation(page, true) { selected = it.name }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AppHeader(if (detail == "任务配置") (editor as? TaskEditorState.Ready)?.snapshot?.task?.name ?: "任务设置" else detail ?: page.title,
                            detail != null, { detail = null }) {
                            if (page == Page.SETTINGS && detail == null) {
                                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                                    ThemeMode.entries.forEach { item ->
                                        GlassChoice(when (item) { ThemeMode.SYSTEM -> "系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" },
                                            item == mode, appearance is AppearanceState.Ready) {
                                            scope.launch {
                                                try { application.appearance.setTheme(item) }
                                                catch (_: IOException) { snackbar.showSnackbar("主题设置保存失败") }
                                            }
                                        }
                                    }
                                }
                            } else if (detail == "任务配置" && (editor as? TaskEditorState.Ready)?.dirty == true) {
                                Text("未保存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            } else if (detail == "任务更新") {
                                TextButton(enabled = pull !is TaskPullState.Fetching, onClick = application.taskUpdates::pullLatest) {
                                    Text(if (pull is TaskPullState.Fetching) "拉取中" else "拉取")
                                }
                            } else if (detail == "GitHub" && github is GitHubSessionState.Connected) {
                                TextButton(enabled = !(github as GitHubSessionState.Connected).checking, onClick = application.github::checkAccount) { Text("检查") }
                            } else if (page == Page.TASKS && detail == null) {
                                TaskVersionEntry(versions) { detail = "任务更新" }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (detail) {
                                "运行环境" -> EnvironmentPage()
                                "GitHub" -> GitHubAccountPage(github, wide, application.github::signIn, application.github::cancelSignIn,
                                    application.github::signOut, { detail = "任务更新" })
                                "悬浮球" -> FloatingSettingsPage(floating, application.floating::setVisible, application.floating::reload)
                                "任务更新" -> TaskVersionsPage(versions, pull, versionOperation, application.taskUpdates::reloadCatalog,
                                    application.taskUpdates::activate, application.taskUpdates::delete)
                                "任务配置" -> TaskConfigurationPage(editor, wide, { address?.let { application.taskEditor.reload(it) } },
                                    { field, value -> address?.let { application.taskEditor.change(it, field, value) } },
                                    { address?.let { application.taskEditor.save(it) } })
                                else -> when (page) {
                                    Page.HOME -> ExecutionWorkbench(executor, runtimeStatus, wide,
                                        { application.executor.submit(ExecutorCommand.Pause) },
                                        { id, mode -> application.executor.submit(id?.let { ExecutorCommand.ResumeCurrent(it, mode) } ?: ExecutorCommand.Resume(mode)) },
                                        { application.executor.submit(ExecutorCommand.Remove(it)) },
                                        { executor.current?.let { application.executor.submit(ExecutorCommand.StopCurrent(it.runId)) } })
                                    Page.TASKS -> TaskCenter(versions, executor, runtimeStatus,
                                        { application.executor.enqueueTask(it) },
                                        { application.executor.submit(ExecutorCommand.PauseCurrent(it)) },
                                        { application.executor.submit(ExecutorCommand.StopCurrent(it)) },
                                        { id, mode -> application.executor.submit(ExecutorCommand.ResumeCurrent(id, mode)) },
                                        application.taskUpdates::reloadCatalog, { detail = "任务更新" }, edit = {
                                        editTarget = it.targetId; editTask = it.taskId; detail = "任务配置"
                                    }, favorites = floating, toggleFavorite = application.floating::toggle)
                                    Page.PLANS -> EmptyContent("暂无计划", "取得任务并保存配置后，可组合执行计划。")
                                    Page.HISTORY -> if (runtimeStatus == RuntimeStatus.Loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                                        else if (runtimeStatus is RuntimeStatus.Failure) EmptyContent("运行记录暂不可用", (runtimeStatus as RuntimeStatus.Failure).message)
                                        else History(executor, wide, historyTab) { historyTab = it }
                                    Page.SETTINGS -> SettingsPage(appearance, versions, github) {
                                        if (it == "运行与数据") { selected = Page.HISTORY.name; historyTab = 1; detail = null }
                                        else detail = it
                                    }
                                }
                            }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                        if (!wide && detail == null) MainNavigation(page, false) { selected = it.name }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppHeader(title: String, back: Boolean, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = UiSpace.page),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        if (back) IconButton(onClick = onBack) { Icon(AssistIcons.Back, "返回") }
        Text(title, Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
private fun iconColor(page: Page, strong: Boolean = false): Color = with(if (strong) paletteOnStrong() else LocalAssistPalette.current) {
    when (page) { Page.HOME -> blue; Page.TASKS -> violet; Page.PLANS -> orange; Page.HISTORY -> teal; Page.SETTINGS -> rose }
}

@Composable
private fun MainNavigation(selected: Page, rail: Boolean, select: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    @Composable fun glyph(page: Page) {
        val color = iconColor(page, page == selected)
        Icon(page.icon, null, Modifier.size(UiSpace.icon), tint = color.copy(alpha = .82f))
    }
    if (rail) {
        NavigationRail(Modifier.width(UiSpace.rail).fillMaxHeight(), containerColor = colors.surfaceContainer,
            windowInsets = WindowInsets(0, 0, 0, 0)) {
            Page.entries.forEach { page ->
                NavigationRailItem(selected == page, { select(page) }, icon = { glyph(page) },
                    modifier = Modifier.clip(RoundedCornerShape(UiSpace.medium)).then(
                        if (selected == page) Modifier.background(glassBrush(true)) else Modifier),
                    label = { Text(page.title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent,
                        selectedTextColor = colors.onPrimary, unselectedTextColor = colors.onSurfaceVariant))
            }
        }
    } else {
        NavigationBar(containerColor = colors.surfaceContainer, tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)) {
            Page.entries.forEach { page ->
                NavigationBarItem(selected == page, { select(page) }, icon = { glyph(page) },
                    modifier = Modifier.padding(UiSpace.tiny).clip(RoundedCornerShape(UiSpace.page)).then(
                        if (selected == page) Modifier.background(glassBrush(true)) else Modifier),
                    label = { Text(page.title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                        selectedTextColor = colors.onPrimary, unselectedTextColor = colors.onSurfaceVariant))
            }
        }
    }
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
            is ActiveTaskContent.Available -> LazyColumn {
                items(content.bundle.tasks, key = { kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(it.targetId), kotlinx.serialization.json.JsonPrimitive(it.id))).toString() }) { task ->
                    val target = content.bundle.targets.single { it.id == task.targetId }
                    val ref = SubmissionRef(SubmissionKind.TASK, task.id)
                    val current = executor.current?.let { it.request.context.targetId == task.targetId && it.request.ref == ref } == true
                    val currentId = executor.current?.runId.takeIf { current }
                    val queued = executor.waiting.any { it.request.context.targetId == task.targetId && it.request.ref == ref }
                    ListItem(modifier = Modifier.clickable { edit(TaskAddress(task.targetId, task.id)) }, headlineContent = { Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = {
                        Text("${target.name} · ${target.categories.single { it.id == task.category }.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }, trailingContent = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                        val address = TaskAddress(task.targetId, task.id)
                        val selected = (favorites as? FloatingPreferencesState.Ready)?.settings?.favorites?.contains(address) == true
                        IconToggleButton(selected, { toggleFavorite(address) }, enabled = favorites is FloatingPreferencesState.Ready) {
                            Icon(AssistIcons.Star, if (selected) "取消常用：${task.name}" else "设为常用：${task.name}",
                                tint = (if (selected) LocalAssistPalette.current.gold else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = .82f))
                        }
                        if (queued || current) Text(if (queued) "队列中" else if (status is RuntimeStatus.Waiting) "等待中" else phaseLabel(executor.phase), style = MaterialTheme.typography.labelSmall,
                            color = if (queued) MaterialTheme.colorScheme.onSurfaceVariant else LocalAssistPalette.current.blue)
                        key(currentId) {
                            RunActionButton(task.name, current, queued,
                                status != RuntimeStatus.Loading && status !is RuntimeStatus.Failure &&
                                    (!current || executor.phase in setOf(ExecutorPhase.RUNNING, ExecutorPhase.PAUSED)),
                                { enqueue(TaskAddress(task.targetId, task.id)) }, { currentId?.let(pause) }, { currentId?.let(stop) },
                                paused = current && executor.phase == ExecutorPhase.PAUSED,
                                resume = { mode -> currentId?.let { resume(it, mode) } },
                                currentStateLabel = if (current && status is RuntimeStatus.Waiting) "等待中" else null)
                        }
                    } })
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(appearance: AppearanceState, versions: VersionCatalogState, github: GitHubSessionState, open: (String) -> Unit) {
    val p = LocalAssistPalette.current
    LazyColumn(Modifier.fillMaxSize()) {
        if (appearance is AppearanceState.Failure) item {
            Text(appearance.message, Modifier.padding(UiSpace.page), color = p.error)
        }
        item { SettingsRow("任务更新", when (versions) {
            VersionCatalogState.Loading -> "读取中"
            is VersionCatalogState.Failure -> "本地版本读取失败"
            is VersionCatalogState.Ready -> versions.catalog.activeVersion?.let { "当前版本 $it" } ?: "暂无已启用任务版本"
        }, AssistIcons.Cloud, p.blue) { open("任务更新") } }
        item { SettingsRow("悬浮球", "游戏中的快捷控制", AssistIcons.Circle, p.violet) { open("悬浮球") } }
        item { SettingsRow("GitHub", when (github) {
            GitHubSessionState.Loading -> "读取中"; GitHubSessionState.Saving -> "保存登录中"
            is GitHubSessionState.SigningIn -> "等待授权"; is GitHubSessionState.Failure -> "登录资料暂不可用"
            is GitHubSessionState.SignedOut -> "未登录"; is GitHubSessionState.Connected -> github.login?.let { "@$it" } ?: "已登录"
        }, AssistIcons.Cloud, p.teal) { open("GitHub") } }
        item { SettingsRow("运行环境", "授权与运行条件", AssistIcons.Phone, p.orange) { open("运行环境") } }
        item { SettingsRow("运行与数据", "执行记录", AssistIcons.Data, p.teal) { open("运行与数据") } }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, color: Color, click: () -> Unit) {
    TextButton(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = UiSpace.row),
        shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(horizontal = UiSpace.section, vertical = UiSpace.medium)) {
        Box(Modifier.size(UiSpace.tile).clip(RoundedCornerShape(UiSpace.gap)).background(color.copy(alpha = .12f)), Alignment.Center) {
            Icon(icon, null, Modifier.size(UiSpace.icon), tint = color.copy(alpha = .82f))
        }
        Column(Modifier.weight(1f).padding(horizontal = UiSpace.medium), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(AssistIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 68.dp, end = UiSpace.section), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun EnvironmentPage() {
    val context = LocalContext.current
    val service by GameAccessibilityService.connection.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScreenshotCheck?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service) { checking = false; result = null; error = null }
    Column(Modifier.padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
        Text("无障碍服务", style = MaterialTheme.typography.titleMedium)
        Text(if (service == null) "尚未授权" else "已授权", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("打开系统设置") }
        OutlinedButton(enabled = service != null && !checking, onClick = {
            val owner = service ?: return@OutlinedButton
            checking = true; error = null; result = null
            try {
                owner.checkScreenshot { value ->
                    // A late platform callback cannot write into an obsolete screen composition.
                    scope.launch {
                        if (GameAccessibilityService.connection.value === owner) result = value
                        else error = "截图授权已变化，请重新检查"
                        checking = false
                    }
                }
            } catch (_: SecurityException) { checking = false; error = "截图授权不可用" }
        }) { Text(if (checking) "检查中" else "检查截图") }
        when (val value = result) {
            is ScreenshotCheck.Success -> Text("已取得可读取画面 · ${value.width} × ${value.height}")
            is ScreenshotCheck.PlatformFailure -> Text(when (value.code) {
                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "截图授权不可用，请重新检查系统设置"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图请求过于频繁，请稍后再试"
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "当前屏幕无法截图"
                else -> "截图失败，请检查授权与当前画面"
            }, color = MaterialTheme.colorScheme.error)
            is ScreenshotCheck.DecodeFailure -> Text(value.reason, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun History(state: ExecutorSnapshot, wide: Boolean, tab: Int, select: (Int) -> Unit) {
    val records = state.facts.filter { it.type in setOf("task-completed", "task-failed", "run-ended", "cancelled") }
    @Composable fun content() {
        if (tab == 0) EmptyContent("暂无通知记录")
        else if (records.isEmpty()) EmptyContent("暂无运行记录")
        else LazyColumn { items(records.withIndex().toList(), key = { it.index }) { (_, fact) ->
            val result = when (fact.type) {
                "task-completed" -> "任务完成"
                "task-failed" -> "任务失败"
                "cancelled" -> "已移除或停止"
                else -> when (fact.detail) {
                    "COMPLETED" -> "本轮完成"
                    "PARTIAL_FAILURE" -> "本轮部分失败"
                    "FAILED" -> "本轮失败"
                    else -> "本轮结束"
                }
            }
            ListItem(headlineContent = { Text(fact.taskName) },
                supportingContent = { Text("$result · ${when (val principal = fact.context.principal) {
                    Principal.Anonymous -> "无需账号"
                    is Principal.Account -> principal.id
                }}") })
        } }
    }
    if (wide) Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(128.dp).padding(UiSpace.gap)) {
            listOf("通知", "运行").forEachIndexed { index, label ->
                TextButton(onClick = { select(index) }, Modifier.fillMaxWidth()) { Text(label) }
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f)) { content() }
    } else Column {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("通知", "运行").forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { select(index) }, text = { Text(label) }) }
        }
        content()
    }
}

@Composable
private fun SectionTitle(text: String, trail: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = UiSpace.medium)) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(trail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyContent(title: String, description: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = UiSpace.section),
        verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
