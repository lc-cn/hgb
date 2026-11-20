package io.github.yysassist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.core.cloud.CloudState
import io.github.yysassist.core.execution.*
import io.github.yysassist.protocol.AccountView
import io.github.yysassist.shared.ConsoleApp
import io.github.yysassist.shared.LocalParts
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.console.Connection
import io.github.yysassist.shared.ui.HgbIcons
import io.github.yysassist.shared.ui.HgbMark
import io.github.yysassist.shared.ui.LocalEntry
import io.github.yysassist.shared.ui.PageTabs
import io.github.yysassist.storage.TaskAddress
import kotlinx.coroutines.launch
import java.io.IOException
import io.github.yysassist.auth.GitHubSessionState

private const val DEVELOP = "develop"

/**
 * The App. Signed in, it is the console every end shares (设备, 游戏账号, 任务库, 通知, 我的), with this phone's own run
 * strip, settings and 开发 added (C40). Signed out, it signs in, and 开发 still works on this phone alone.
 */
@Composable
fun AssistApp(application: AssistApplication, navigation: AppNavigation? = null, navigationHandled: () -> Unit = {}) {
    val appearance by application.appearance.state.collectAsState()
    val mode = (appearance as? AppearanceState.Ready)?.mode ?: ThemeMode.SYSTEM
    val cloud by application.cloud.state.collectAsState()
    AssistTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                when (val state = cloud) {
                    CloudState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    is CloudState.SignedOut -> SignedOut(application, navigation, navigationHandled)
                    is CloudState.SignedIn -> SignedIn(application, mode, navigation, navigationHandled)
                }
            }
        }
    }
}

@Composable
private fun SignedIn(application: AssistApplication, mode: ThemeMode, navigation: AppNavigation?, navigationHandled: () -> Unit) {
    val session = application.console
    val store = session.store; val api = session.api; val nav = session.nav
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<AccountView?>(null) }
    var thisDevice by remember { mutableStateOf<String?>(null) }
    // Read again each time the stream (re)connects, so a start without the network fills in once it is back.
    val connection by store.connection.collectAsState()
    LaunchedEffect(connection == Connection.LIVE) {
        if (connection == Connection.LIVE) account = try { api.me() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { account }
    }
    LaunchedEffect(application) { while (thisDevice == null) { thisDevice = application.cloud.registeredDevice(); if (thisDevice == null) kotlinx.coroutines.delay(1_000) } }
    // A notification or the floating ball asking for a task's settings opens 开发, where this phone's task list is.
    LaunchedEffect(navigation?.id) { if (navigation != null) nav.openLocal(DEVELOP) }
    BackHandler(nav.canGoBack) { nav.back() }
    val executor by application.executor.state.collectAsState()
    val status by application.executor.status.collectAsState()
    val floating by application.floating.state.collectAsState()
    val local = LocalParts(
        entries = listOf(LocalEntry(DEVELOP, "开发", HgbIcons.Develop)),
        page = { LocalWorkbench(application, navigation, navigationHandled) },
        runStrip = if (!runStatusVisible(executor, status)) null else ({
            RunStatusBar(executor, status, { thisDevice?.let { nav.go(Route.Device(it)) } },
                { application.executor.submit(ExecutorCommand.Pause) },
                { id, resume -> application.executor.submit(id?.let { ExecutorCommand.ResumeCurrent(it, resume) } ?: ExecutorCommand.Resume(resume)) },
                { executor.current?.let { application.executor.submit(ExecutorCommand.StopCurrent(it.runId)) } })
        }),
        settings = { LocalDeviceSettings(floating, application.floating::setVisible, application.floating::setAutoDock, application.floating::reload) { nav.openLocal(DEVELOP) } },
        notices = { LocalNoticeSwitch(application.localNotices) },
        appearance = when (mode) { ThemeMode.SYSTEM -> "auto"; ThemeMode.LIGHT -> "light"; ThemeMode.DARK -> "dark" } to { look ->
            scope.launch { runCatching { application.appearance.setTheme(when (look) { "light" -> ThemeMode.LIGHT; "dark" -> ThemeMode.DARK; else -> ThemeMode.SYSTEM }) } }
            Unit
        },
        signOut = { application.cloud.signOut() },
    )
    ConsoleApp(store, api, nav, account, thisDevice, local)
}

/** Signed out: sign in to the server, or use 开发 on this phone alone (it works offline). */
@Composable
private fun SignedOut(application: AssistApplication, navigation: AppNavigation?, navigationHandled: () -> Unit) {
    // Whoever signs in next starts from a clean console.
    LaunchedEffect(Unit) { application.console.reset() }
    var developing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(navigation?.id) { if (navigation != null) developing = true }
    BackHandler(developing) { developing = false }
    if (developing) Column(Modifier.fillMaxSize()) {
        AppHeader("开发", true, { developing = false }) {}
        Box(Modifier.weight(1f)) { LocalWorkbench(application, navigation, navigationHandled) }
    } else SignIn(application) { developing = true }
}

/**
 * The shared sign-in page against the server the user types. GitHub uses the App's own GitHub sign-in (the device flow
 * that also downloads task versions): once it holds a token, the server checks it and signs this device in.
 */
@Composable
private fun SignIn(application: AssistApplication, develop: () -> Unit) {
    val cloud by application.cloud.state.collectAsState()
    val github by application.github.state.collectAsState()
    var server by rememberSaveable { mutableStateOf("") }
    var wantGitHub by rememberSaveable { mutableStateOf(false) }
    var githubError by remember { mutableStateOf<String?>(null) }
    val agent = application.cloud
    fun address(): String = server.trim().also { cloudServerIssue(it)?.let { issue -> throw io.github.yysassist.core.cloud.CloudRequestException(0, "invalid_server", issue) } }
    val actions = remember(application) { object : io.github.yysassist.shared.pages.SignInActions {
        override suspend fun password(email: String, password: String) = agent.signIn(address(), email, password)
        override suspend fun requestCode(email: String) = agent.requestCode(address(), email)
        override suspend fun codeLogin(email: String, code: String) = agent.signInWithCode(address(), email, code)
        override suspend fun register(email: String, password: String) = agent.register(address(), email, password)
        override suspend fun verify(email: String, code: String) = agent.verifyEmail(address(), email, code)
        override suspend fun requestReset(email: String) = agent.requestPasswordReset(address(), email)
        override suspend fun reset(email: String, code: String, password: String) = agent.resetPassword(address(), email, code, password)
    } }
    // Once GitHub has signed in at the user's request, sign in to the server with its token.
    LaunchedEffect(wantGitHub, github is GitHubSessionState.Connected) {
        if (!wantGitHub || github !is GitHubSessionState.Connected) return@LaunchedEffect
        wantGitHub = false
        githubError = try {
            val token = application.github.token() ?: throw java.io.IOException("GitHub 授权已失效，请重新授权")
            agent.signInWithGitHub(address(), token.value); null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (problem: Exception) { io.github.yysassist.shared.failureText(problem) }
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    io.github.yysassist.shared.pages.SignInPage(actions, (cloud as? CloudState.SignedOut)?.message, server to { next: String -> server = next },
        github = {
            Column(verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                when (val state = github) {
                    is GitHubSessionState.SigningIn -> state.prompt?.let { prompt ->
                        io.github.yysassist.shared.ui.Muted("在 GitHub 页面输入这个授权码")
                        Text(prompt.userCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                            io.github.yysassist.shared.ui.HgbButton("打开 GitHub", { uri.openUri(prompt.verificationUri.toString()) }, io.github.yysassist.shared.ui.ButtonKind.PRIMARY)
                            io.github.yysassist.shared.ui.HgbButton("取消", { wantGitHub = false; application.github.cancelSignIn() })
                        }
                    } ?: io.github.yysassist.shared.ui.Muted("正在连接 GitHub…")
                    is GitHubSessionState.Connected -> io.github.yysassist.shared.ui.HgbButton("用 GitHub${state.login?.let { " @$it" } ?: ""} 登录",
                        { githubError = null; wantGitHub = true }, modifier = Modifier.fillMaxWidth())
                    else -> io.github.yysassist.shared.ui.HgbButton("使用 GitHub 登录", {
                        githubError = cloudServerIssue(server.trim())
                        if (githubError == null) { wantGitHub = true; application.github.signIn() }
                    }, modifier = Modifier.fillMaxWidth())
                }
                githubError?.let { io.github.yysassist.shared.ui.Message(it) }
            }
        },
        footer = { io.github.yysassist.shared.ui.LinkButton("开发 · 不登录也能在这台手机上调试任务", develop) })
}

private enum class Tool(val title: String) { TASKS("任务"), SETTINGS("设置") }

/**
 * 开发: this phone's own tools, which work without the server — the task versions it has, running and configuring
 * them here, GitHub for downloading versions, and its own run log.
 */
@Composable
internal fun LocalWorkbench(application: AssistApplication, navigation: AppNavigation? = null, navigationHandled: () -> Unit = {}) {
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
    val cloudState by application.cloud.state.collectAsState()
    val online = (cloudState as? CloudState.SignedIn)?.online == true
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(Tool.TASKS) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var favoritesRequest by rememberSaveable { mutableIntStateOf(0) }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editTask by rememberSaveable { mutableStateOf<String?>(null) }
    val address = editTarget?.let { target -> editTask?.let { TaskAddress(target, it) } }
    val editor = address?.let { taskDrafts[it] } ?: TaskEditorState.Loading
    LaunchedEffect(address) { address?.let { application.taskEditor.open(it) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(application.taskUpdates) { application.taskUpdates.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(application.taskEditor) { application.taskEditor.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(navigation?.id) { navigation?.let { editTarget = it.task.targetId; editTask = it.task.taskId; selected = Tool.TASKS; detail = "任务配置"; navigationHandled() } }
    LaunchedEffect(application.floating) { application.floating.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(application.executor) { application.executor.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(detail != null) { detail = null }
    val pauseQueue = { application.executor.submit(ExecutorCommand.Pause); Unit }
    val resumeQueue = { id: Long?, resume: ResumeMode ->
        application.executor.submit(id?.let { ExecutorCommand.ResumeCurrent(it, resume) } ?: ExecutorCommand.Resume(resume)); Unit
    }
    val stopCurrent = { executor.current?.let { application.executor.submit(ExecutorCommand.StopCurrent(it.runId)) }; Unit }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= UiSpace.wide
        Column(Modifier.fillMaxSize()) {
            if (detail != null) {
                AppHeader(if (detail == "任务配置") (editor as? TaskEditorState.Ready)?.snapshot?.task?.name ?: "任务设置" else detail!!, true, { detail = null }) {
                    if (detail == "任务配置" && (editor as? TaskEditorState.Ready)?.dirty == true) {
                        Text("未保存", color = LocalAssistPalette.current.warning, style = MaterialTheme.typography.labelSmall)
                    } else if (detail == "任务更新") {
                        TextButton(enabled = pull !is TaskPullState.Fetching, onClick = application.taskUpdates::pullLatest) {
                            Text(if (pull is TaskPullState.Fetching) "拉取中" else "拉取")
                        }
                    } else if (detail == "GitHub" && github is GitHubSessionState.Connected) {
                        TextButton(enabled = !(github as GitHubSessionState.Connected).checking, onClick = application.github::checkAccount) { Text("检查") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            } else Box(Modifier.padding(horizontal = UiSpace.page)) { PageTabs(Tool.entries.map { it to it.title }, selected) { selected = it } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (detail) {
                    "运行队列" -> ExecutionWorkbench(executor, runtimeStatus, wide, pauseQueue, resumeQueue,
                        { application.executor.submit(ExecutorCommand.Remove(it)) }, stopCurrent)
                    "运行记录" -> if (runtimeStatus == RuntimeStatus.Loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        else if (runtimeStatus is RuntimeStatus.Failure) EmptyContent("运行记录暂不可用", (runtimeStatus as RuntimeStatus.Failure).message)
                        else RunHistoryPage(executor)
                    "运行与数据" -> DataUsagePage(executor)
                    "云端控制" -> CloudPage(application.accounts, application::identifyAccount, application.localNotices)
                    "GitHub" -> GitHubAccountPage(github, wide, application.github::signIn, application.github::cancelSignIn,
                        application.github::signOut, { detail = "任务更新" })
                    "任务更新" -> TaskVersionsPage(versions, pull, versionOperation, application.taskUpdates::reloadCatalog,
                        application.taskUpdates::activate, application.taskUpdates::delete)
                    "任务配置" -> TaskConfigurationPage(editor, wide, settingsNotice(cloudState, online, (editor as? TaskEditorState.Ready)?.snapshot?.accountName),
                        { address?.let { application.taskEditor.reload(it) } },
                        { field, value -> address?.let { application.taskEditor.change(it, field, value) } },
                        { address?.let { application.taskEditor.save(it) } })
                    else -> when (selected) {
                        Tool.TASKS -> TaskCenter(versions, executor, runtimeStatus, { application.runTask(it) },
                            application.taskUpdates::reloadCatalog, { detail = "任务更新" }, edit = { editTarget = it.targetId; editTask = it.taskId; detail = "任务配置" },
                            favorites = floating, toggleFavorite = application.floating::toggle, openHistory = { detail = "运行记录" }, favoritesRequest = favoritesRequest)
                        Tool.SETTINGS -> SettingsPage(appearance, mode, { item ->
                            scope.launch { try { application.appearance.setTheme(item) } catch (_: IOException) { snackbar.showSnackbar("主题设置保存失败") } }
                        }, versions, github, floating, application.floating::setVisible, application.floating::setAutoDock, application.floating::reload) {
                            if (it == "常用") { favoritesRequest++; selected = Tool.TASKS } else detail = it
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** Settings belong to a game account on the server; this says whose they are, or why they cannot be saved now. */
private fun settingsNotice(cloud: CloudState, online: Boolean, account: String?): String = when {
    cloud !is CloudState.SignedIn -> "设置保存在服务器上，登录护肝宝账号后才能保存"
    account == null -> "先在设备页选好游戏当前登录的账号，设置跟着账号走"
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
internal fun EmptyContent(title: String, description: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = UiSpace.section),
        verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
