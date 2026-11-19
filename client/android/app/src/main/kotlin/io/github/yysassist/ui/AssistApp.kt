package io.github.yysassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.content.IdentityPolicy
import io.github.yysassist.core.model.ExecutionContext
import io.github.yysassist.core.model.Principal
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.core.cloud.CloudState
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.protocol.CloudTask
import io.github.yysassist.protocol.PlanSave
import io.github.yysassist.protocol.PlanView
import java.time.ZoneId
import io.github.yysassist.storage.ActiveTaskContent
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.launch
import java.io.IOException
import io.github.yysassist.auth.GitHubSessionState

private enum class Page(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    TASKS("任务", AssistIcons.Tasks, AssistIcons.TasksFilled),
    PLANS("计划", AssistIcons.Calendar, AssistIcons.CalendarFilled),
    SETTINGS("设置", AssistIcons.Settings, AssistIcons.SettingsFilled),
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
    var favoritesRequest by rememberSaveable { mutableIntStateOf(0) }
    val cloudState by application.cloud.state.collectAsState()
    val cloudData by application.cloud.data.collectAsState()
    val online = (cloudState as? CloudState.SignedIn)?.online == true
    var editPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    // The version the edit started from: a save over a newer one is refused by the server instead of overwriting it.
    var editRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var planTask by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePlanFor by remember { mutableStateOf<TaskAddress?>(null) }
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
    val activeBundle = ((versions as? VersionCatalogState.Ready)?.catalog?.activeContent as? ActiveTaskContent.Available)?.bundle
    // Plans belong to the game account the game here is signed in to; a phone has one game app for now.
    val planTarget = activeBundle?.targets?.firstOrNull { application.accounts.app(it.id) != null }?.id
    val planAccount = planTarget?.let { application.accounts.current(it, cloudData) }
    val plans: List<PlanView>? = if (cloudState !is CloudState.SignedIn) null else cloudData?.plans.orEmpty().filter { it.accountId == planAccount?.id }
    val zone = runCatching { ZoneId.of(cloudData?.timeZone) }.getOrDefault(ZoneId.systemDefault())
    /** A request to the server for the user; a refusal or a lost connection is shown, never retried by itself. */
    fun cloudCall(done: () -> Unit = {}, block: suspend () -> Unit) { scope.launch {
        try { block(); done() }
        catch (rejected: CloudRequestException) { snackbar.showSnackbar(rejected.message) }
        catch (_: IOException) { snackbar.showSnackbar("没有连上服务器，请稍后再试") }
    } }
    fun PlanView.save() = PlanSave(name, days, minuteOfDay, tasks, deviceIds, latestStartMinutes, enabled, revision)
    fun taskRefs(ids: List<String>) = ids.map { id -> CloudTask(planTarget.orEmpty(), id, activeBundle?.tasks?.firstOrNull { it.id == id && it.targetId == planTarget }?.name ?: id) }
    BackHandler(detail != null || page != Page.TASKS) {
        if (detail != null) detail = null else selected = Page.TASKS.name
    }
    val pauseQueue = { application.executor.submit(ExecutorCommand.Pause); Unit }
    val resumeQueue = { id: Long?, resume: ResumeMode ->
        application.executor.submit(id?.let { ExecutorCommand.ResumeCurrent(it, resume) } ?: ExecutorCommand.Resume(resume)); Unit
    }
    val stopCurrent = { executor.current?.let { application.executor.submit(ExecutorCommand.StopCurrent(it.runId)) }; Unit }
    AssistTheme(mode) {
        choosePlanFor?.let { address ->
            val candidates = plans.orEmpty().filter { plan -> address.targetId == planTarget && plan.tasks.none { it.taskId == address.taskId } }
            AlertDialog(onDismissRequest = { choosePlanFor = null }, title = { Text("加入计划") }, text = { Column {
                candidates.forEach { plan -> TextButton({ choosePlanFor = null; cloudCall({ scope.launch { snackbar.showSnackbar("已加入「${plan.name}」") } }) {
                    application.cloud.updatePlan(plan.id, plan.save().copy(tasks = plan.tasks + taskRefs(listOf(address.taskId)))) } }) { Text(plan.name) } }
                TextButton({ choosePlanFor = null; editPlanId = null; planTask = address.taskId; detail = "新建计划" }) { Text("新建计划") }
            } }, confirmButton = {}, dismissButton = { TextButton({ choosePlanFor = null }) { Text("取消") } })
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= UiSpace.wide
                val showRun = detail == null && runStatusVisible(executor, runtimeStatus)
                Row(Modifier.fillMaxSize()) {
                    if (wide && detail == null) MainNavigation(page, true) { selected = it.name }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        // The task list and the landscape settings page carry their own title bars.
                        if (detail != null || (page != Page.TASKS && !(wide && page == Page.SETTINGS)))
                            AppHeader(if (detail == "任务配置") (editor as? TaskEditorState.Ready)?.snapshot?.task?.name ?: "任务设置" else detail ?: page.title,
                            detail != null, { detail = null }) {
                            if (detail == "任务配置" && (editor as? TaskEditorState.Ready)?.dirty == true) {
                                Text("未保存", color = LocalAssistPalette.current.warning, style = MaterialTheme.typography.labelSmall)
                            } else if (detail == "任务更新") {
                                TextButton(enabled = pull !is TaskPullState.Fetching, onClick = application.taskUpdates::pullLatest) {
                                    Text(if (pull is TaskPullState.Fetching) "拉取中" else "拉取")
                                }
                            } else if (page == Page.PLANS && detail == null && !plans.isNullOrEmpty() && online) {
                                IconButton(onClick = { editPlanId = null; planTask = null; detail = "新建计划" }) { Icon(AssistIcons.Plus, "新建计划") }
                            } else if (detail == "GitHub" && github is GitHubSessionState.Connected) {
                                TextButton(enabled = !(github as GitHubSessionState.Connected).checking, onClick = application.github::checkAccount) { Text("检查") }
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
                                "运行与数据" -> DataUsagePage(executor)
                                "云端控制" -> CloudPage(application.accounts)
                                "新建计划", "编辑计划" -> if (planTarget == null || planAccount == null) EmptyContent("先选好当前登录的游戏账号", "计划属于游戏账号，在 设置 → 云端控制 里选择。")
                                    else if (detail == "编辑计划" && plans?.none { it.id == editPlanId } != false) EmptyContent("这个计划已在别处删除", "返回计划列表查看。")
                                    else PlanEditorPage(plans?.firstOrNull { it.id == editPlanId }.takeIf { detail == "编辑计划" }, planTarget, planTask, activeBundle, online, { name, schedule: PlanSchedule, taskIds ->
                                    val existing = plans?.firstOrNull { it.id == editPlanId }.takeIf { detail == "编辑计划" }
                                    val save = PlanSave(name.trim(), schedule.days.map { it.value }.sorted(), schedule.minuteOfDay, taskRefs(taskIds),
                                        existing?.deviceIds.orEmpty(), existing?.latestStartMinutes ?: 30, existing?.enabled ?: true, if (existing == null) 0 else editRevision ?: existing.revision)
                                    cloudCall({ detail = null; selected = Page.PLANS.name }) {
                                        if (existing == null) application.cloud.createPlan(planAccount.id, save) else application.cloud.updatePlan(existing.id, save) }
                                }, { plans?.firstOrNull { it.id == editPlanId }?.let { plan -> cloudCall({ detail = null }) { application.cloud.deletePlan(plan.id) } } })
                                "GitHub" -> GitHubAccountPage(github, wide, application.github::signIn, application.github::cancelSignIn,
                                    application.github::signOut, { detail = "任务更新" })
                                "任务更新" -> TaskVersionsPage(versions, pull, versionOperation, application.taskUpdates::reloadCatalog,
                                    application.taskUpdates::activate, application.taskUpdates::delete)
                                "任务配置" -> TaskConfigurationPage(editor, wide, settingsNotice(cloudState, online, (editor as? TaskEditorState.Ready)?.snapshot?.accountName),
                                    { address?.let { application.taskEditor.reload(it) } },
                                    { field, value -> address?.let { application.taskEditor.change(it, field, value) } },
                                    { address?.let { application.taskEditor.save(it) } })
                                else -> when (page) {
                                    Page.TASKS -> TaskCenter(versions, executor, runtimeStatus,
                                        { application.runTask(it) },
                                        application.taskUpdates::reloadCatalog, { detail = "任务更新" }, edit = {
                                        editTarget = it.targetId; editTask = it.taskId; detail = "任务配置"
                                    }, favorites = floating, toggleFavorite = application.floating::toggle, openHistory = { detail = "运行记录" }, favoritesRequest = favoritesRequest, addToPlan = { choosePlanFor = it })
                                    Page.PLANS -> PlansPage(plans, planAccount?.name, online, cloudData?.lastRuns.orEmpty(), zone,
                                        { plan, enabled -> cloudCall { application.cloud.updatePlan(plan.id, plan.save().copy(enabled = enabled)) } },
                                        { plan -> cloudCall({ scope.launch { snackbar.showSnackbar("已交给服务器下发") } }) { application.cloud.runPlan(plan.id) } },
                                        { plan -> editPlanId = plan.id; editRevision = plan.revision; planTask = null; detail = "编辑计划" },
                                        { editPlanId = null; planTask = null; detail = "新建计划" })
                                    Page.SETTINGS -> SettingsPage(appearance, mode, { item ->
                                        scope.launch {
                                            try { application.appearance.setTheme(item) }
                                            catch (_: IOException) { snackbar.showSnackbar("主题设置保存失败") }
                                        }
                                    }, versions, github, floating, application.floating::setVisible, application.floating::setAutoDock, application.floating::reload) {
                                        if (it == "常用") { favoritesRequest++; selected = Page.TASKS.name } else detail = it
                                    }
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

/** Settings belong to a game account on the server; this says whose they are, or why they cannot be saved now. */
private fun settingsNotice(cloud: CloudState, online: Boolean, account: String?): String = when {
    cloud !is CloudState.SignedIn -> "设置保存在服务器上。登录护肝宝账号后才能保存：设置 → 云端控制"
    account == null -> "先在 设置 → 云端控制 里选好游戏当前登录的账号，设置跟着账号走"
    !online -> "没有连上服务器，「$account」的设置只能查看"
    else -> "「$account」的设置，保存后在网页和其他设备上也生效"
}

@Composable
internal fun AppHeader(title: String, back: Boolean, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (back) UiSpace.tiny else UiSpace.page, end = UiSpace.page),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
        if (back) IconButton(onClick = onBack) { Icon(AssistIcons.Back, "返回") }
        Text(title, Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (back) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
        actions()
    }
}

@Composable
private fun pageColor(page: Page): Color = with(LocalAssistPalette.current) {
    when (page) { Page.TASKS -> violet; Page.PLANS -> orange; Page.SETTINGS -> rose }
}

/** Selected: the label takes the page colour and the icon is filled with it. */
@Composable
private fun NavigationItem(page: Page, selected: Boolean, modifier: Modifier, select: (Page) -> Unit) {
    val color = if (selected) pageColor(page) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.selectable(selected, role = Role.Tab, onClick = { select(page) }),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (selected) page.selectedIcon else page.icon, null, Modifier.size(UiSpace.icon), tint = color)
        Spacer(Modifier.height(2.dp))
        Text(page.title, color = color, maxLines = 1, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun MainNavigation(selected: Page, rail: Boolean, select: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (rail) Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(UiSpace.rail).fillMaxHeight().background(colors.surfaceContainerHigh).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(UiSpace.gap, Alignment.CenterVertically)) {
            Page.entries.forEach { NavigationItem(it, it == selected, Modifier.fillMaxWidth().height(64.dp), select) }
        }
        VerticalDivider(color = colors.outlineVariant)
    } else Column(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh)) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().height(64.dp).selectableGroup()) {
            Page.entries.forEach { NavigationItem(it, it == selected, Modifier.weight(1f).fillMaxHeight(), select) }
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
