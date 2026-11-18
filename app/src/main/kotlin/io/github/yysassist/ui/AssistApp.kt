package io.github.yysassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.launch
import java.io.IOException
import io.github.yysassist.auth.GitHubSessionState

private enum class Page(val title: String, val icon: ImageVector) {
    TASKS("任务", AssistIcons.Tasks), PLANS("计划", AssistIcons.Calendar), SETTINGS("设置", AssistIcons.Settings),
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
    var selected by rememberSaveable { mutableStateOf(Page.TASKS.name) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
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
    BackHandler(detail != null || page != Page.TASKS) {
        if (detail != null) detail = null else selected = Page.TASKS.name
    }
    val pauseQueue = { application.executor.submit(ExecutorCommand.Pause); Unit }
    val resumeQueue = { id: Long?, resume: ResumeMode ->
        application.executor.submit(id?.let { ExecutorCommand.ResumeCurrent(it, resume) } ?: ExecutorCommand.Resume(resume)); Unit
    }
    val stopCurrent = { executor.current?.let { application.executor.submit(ExecutorCommand.StopCurrent(it.runId)) }; Unit }
    AssistTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= UiSpace.wide
                val showRun = detail == null && runStatusVisible(executor, runtimeStatus)
                Row(Modifier.fillMaxSize()) {
                    if (wide && detail == null) MainNavigation(page, true) { selected = it.name }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AppHeader(if (detail == "任务配置") (editor as? TaskEditorState.Ready)?.snapshot?.task?.name ?: "任务设置" else detail ?: page.title,
                            detail != null, { detail = null }) {
                            if (detail == "任务配置" && (editor as? TaskEditorState.Ready)?.dirty == true) {
                                Text("未保存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            } else if (detail == "任务更新") {
                                TextButton(enabled = pull !is TaskPullState.Fetching, onClick = application.taskUpdates::pullLatest) {
                                    Text(if (pull is TaskPullState.Fetching) "拉取中" else "拉取")
                                }
                            } else if (detail == "GitHub" && github is GitHubSessionState.Connected) {
                                TextButton(enabled = !(github as GitHubSessionState.Connected).checking, onClick = application.github::checkAccount) { Text("检查") }
                            } else if (page == Page.TASKS && detail == null) {
                                TaskVersionEntry(versions) { detail = "任务更新" }
                                IconButton(onClick = { detail = "运行记录" }) { Icon(AssistIcons.History, "运行记录") }
                            }
                        }
                        if (detail != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (detail) {
                                "运行队列" -> ExecutionWorkbench(executor, runtimeStatus, wide, pauseQueue, resumeQueue,
                                    { application.executor.submit(ExecutorCommand.Remove(it)) }, stopCurrent)
                                "运行记录" -> if (runtimeStatus == RuntimeStatus.Loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                                    else if (runtimeStatus is RuntimeStatus.Failure) EmptyContent("运行记录暂不可用", (runtimeStatus as RuntimeStatus.Failure).message)
                                    else RunHistoryPage(executor)
                                "GitHub" -> GitHubAccountPage(github, wide, application.github::signIn, application.github::cancelSignIn,
                                    application.github::signOut, { detail = "任务更新" })
                                "任务更新" -> TaskVersionsPage(versions, pull, versionOperation, application.taskUpdates::reloadCatalog,
                                    application.taskUpdates::activate, application.taskUpdates::delete)
                                "任务配置" -> TaskConfigurationPage(editor, wide, { address?.let { application.taskEditor.reload(it) } },
                                    { field, value -> address?.let { application.taskEditor.change(it, field, value) } },
                                    { address?.let { application.taskEditor.save(it) } })
                                else -> when (page) {
                                    Page.TASKS -> TaskCenter(versions, executor, runtimeStatus,
                                        { application.executor.enqueueTask(it) },
                                        { application.executor.submit(ExecutorCommand.PauseCurrent(it)) },
                                        { application.executor.submit(ExecutorCommand.StopCurrent(it)) },
                                        { id, resume -> application.executor.submit(ExecutorCommand.ResumeCurrent(id, resume)) },
                                        application.taskUpdates::reloadCatalog, { detail = "任务更新" }, edit = {
                                        editTarget = it.targetId; editTask = it.taskId; detail = "任务配置"
                                    }, favorites = floating, toggleFavorite = application.floating::toggle)
                                    Page.PLANS -> EmptyContent("暂无计划", "取得任务并保存配置后，可组合执行计划。")
                                    Page.SETTINGS -> SettingsPage(appearance, mode, { item ->
                                        scope.launch {
                                            try { application.appearance.setTheme(item) }
                                            catch (_: IOException) { snackbar.showSnackbar("主题设置保存失败") }
                                        }
                                    }, versions, github, floating, application.floating::setVisible, application.floating::reload) { detail = it }
                                }
                            }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                        if (showRun && !wide) RunStatusBar(executor, runtimeStatus, { detail = "运行队列" }, pauseQueue, resumeQueue, stopCurrent)
                        if (!wide && detail == null) MainNavigation(page, false) { selected = it.name }
                    }
                    if (showRun && wide) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        RunStatusPanel(executor, runtimeStatus, { detail = "运行队列" }, pauseQueue, resumeQueue, stopCurrent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppHeader(title: String, back: Boolean, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = UiSpace.page),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        if (back) IconButton(onClick = onBack) { Icon(AssistIcons.Back, "返回") }
        Text(title, Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
private fun iconColor(page: Page, strong: Boolean = false): Color = with(if (strong) paletteOnStrong() else LocalAssistPalette.current) {
    when (page) { Page.TASKS -> violet; Page.PLANS -> orange; Page.SETTINGS -> rose }
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
internal fun EmptyContent(title: String, description: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = UiSpace.section),
        verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
