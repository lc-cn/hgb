package io.github.yysassist.shared.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.protocol.*
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.theme.DesignTokens.Radius
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*

/** A phone goes straight to its first app; a device with several app instances (Windows) lists them first. */
@Composable
fun DevicePage(id: String) {
    val console = LocalConsole.current
    val device = console.data.devices.firstOrNull { it.id == id }
        ?: return NotFound("找不到这台设备，它可能已经解绑", "回到设备") { console.nav.open(Section.DEVICES) }
    if (device.platform.equals("android", true) && device.apps.isNotEmpty()) AppDetail(device, device.apps[0]) else DeviceDetail(device)
}

/** 应用详情 on its own; a phone's device page shows this for its first app. */
@Composable
fun AppPage(deviceId: String, key: String) {
    val console = LocalConsole.current
    val device = console.data.devices.firstOrNull { it.id == deviceId }
        ?: return NotFound("找不到这台设备，它可能已经解绑", "回到设备") { console.nav.open(Section.DEVICES) }
    val app = device.apps.firstOrNull { it.key == key } ?: return NotFound("这台设备上没有这个应用", "回到设备") { console.nav.back() }
    AppDetail(device, app)
}

/**
 * Which accounts an app uses: the one signed in now and the default it returns to. Both are kept here and sent
 * together, so two quick changes cannot undo each other; a refused change shows the server's values again.
 */
private class AppAccounts(val current: String?, val fallback: String?, val setCurrent: (String?) -> Unit, val setDefault: (String?) -> Unit)
@Composable
private fun rememberAppAccounts(device: DeviceView, app: AppView): AppAccounts {
    val console = LocalConsole.current
    var current by remember(app.accountId) { mutableStateOf(app.accountId) }
    var fallback by remember(app.defaultAccountId) { mutableStateOf(app.defaultAccountId) }
    fun save(accountId: String?, defaultAccountId: String?) {
        current = accountId; fallback = defaultAccountId
        console.act { try { console.api.setAppAccounts(device.id, app.key, io.github.yysassist.protocol.AppAccounts(accountId, defaultAccountId)) }
            catch (failure: Exception) { current = app.accountId; fallback = app.defaultAccountId; throw failure } }
    }
    return AppAccounts(current, fallback, { save(it, fallback) }, { save(current, it) })
}
/**
 * The app's account beside its name: the account signed in now in the accent colour, then a gear. It opens a list of
 * the game's accounts like a select's options, the one signed in now selected; each row ends with 设为默认 or 取消默认.
 * Tapping a row says that account is signed in, unless the app reads it from the game itself.
 */
@Composable
private fun AccountMenu(app: AppView, accounts: List<GameAccountView>, state: AppAccounts) {
    val tokens = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    val reads = app.observed != null
    val shape = RoundedCornerShape(Radius.md)
    val current = accounts.firstOrNull { it.id == state.current }?.name ?: app.observed?.nickname ?: "未指定"
    Box {
        Row(Modifier.padding(start = Space.s2).clip(shape).clickable(onClickLabel = "${app.name} 的账号设置") { open = true }.padding(Space.s1),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(current, color = tokens.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Icon(HgbIcons.Settings, null, Modifier.size(18.dp), tint = tokens.primary)
        }
        DropdownMenu(open, { open = false }, containerColor = tokens.surface) {
            if (accounts.isEmpty()) Muted("先在“游戏账号”里添加这个游戏的账号", Modifier.padding(horizontal = Space.s4, vertical = Space.s2))
            // One row per account, as a select's options: the one signed in now is the selected row; the suffix sets the default.
            accounts.forEach { account ->
                val selected = account.id == state.current
                val isDefault = account.id == state.fallback
                DropdownMenuItem(
                    text = { Text(account.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) tokens.navCurrent else tokens.ink) },
                    onClick = { if (!reads) state.setCurrent(account.id); open = false },
                    modifier = Modifier.widthIn(min = 220.dp).background(if (selected) tokens.navCurrentBg else tokens.surface),
                    trailingIcon = {
                        TextButton({ state.setDefault(if (isDefault) null else account.id); open = false }, contentPadding = PaddingValues(horizontal = Space.s2)) {
                            Text(if (isDefault) "取消默认" else "设为默认", color = if (isDefault) tokens.muted else tokens.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
        }
    }
}

/** What the game showed on screen, with a way to keep it as a game account when it is not one yet. */
@Composable
private fun Observed(app: AppView, accounts: List<GameAccountView>) {
    val seen = app.observed ?: return
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    var naming by remember { mutableStateOf(false) }
    var name by remember(seen.nickname) { mutableStateOf(seen.nickname) }
    val saved = accounts.any { it.uid == seen.uid }
    if (saved) return
    FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(tokens.primarySoft).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("游戏里：${seen.nickname}（编号 ${seen.uid}）${if (app.switchable) " · 可切号" else ""}", color = tokens.nav, style = MaterialTheme.typography.bodyMedium)
        if (!saved && !naming) HgbButton("保存为游戏账号", { naming = true }, small = true)
        if (!saved && naming) {
            TextInput(name, { name = it.take(40) }, "名称，如主号", Modifier.widthIn(max = 200.dp))
            HgbButton("保存", { console.act({ naming = false }) { console.api.createAccount(GameAccountSave(app.gameId, name.trim(), seen.uid, seen.nickname)) } },
                ButtonKind.PRIMARY, small = true, enabled = name.isNotBlank())
            HgbButton("取消", { naming = false }, small = true)
        }
    }
}

/** One game instance on a device: its accounts, what it runs now, what waits for it, and running a task on it. */
@Composable
fun AppDetail(device: DeviceView, app: AppView) = Page {
    val console = LocalConsole.current
    val data = console.data
    val phone = device.platform.equals("android", true)
    var tab by remember { mutableStateOf("run") }
    val game = data.games.firstOrNull { it.id == app.gameId }
    val accounts = data.accounts.filter { it.gameId == app.gameId }
    val state = rememberAppAccounts(device, app)
    val (job, _) = appJobs(data.jobs, device.id, app.key)
    val (status, tone) = if (job?.state == JobState.RUNNING && job.pauseRequested) "已暂停" to Tone.WARN else Format.appStatus(app.phase, device.online)
    PageHeader(app.name, crumbs = listOfNotNull("设备", device.name, app.name.takeIf { !phone }).joinToString(" / "),
        back = if (console.nav.canGoBack) ({ console.nav.back(); Unit }) else null, aside = { Pill(status, tone, dot = true) },
        titleExtra = { AccountMenu(app, accounts, state) })
    if (phone && device.apps.size > 1) Segments(device.apps.map { it.key to it.name }, app.key) { key ->
        console.nav.replace(if (key == device.apps[0].key) Route.Device(device.id) else Route.App(device.id, key))
    }
    Observed(app, accounts)
    PageTabs(listOf("run" to "运行", "runs" to "运行记录"), tab) { tab = it }
    // Keyed by app, so notes about a run sent to one app are not shown on another.
    if (tab == "run") key(app.key) { RunTab(device, app) } else Card {
        val mine = data.jobs.filter { it.deviceId == device.id && it.appKey == app.key }
        if (mine.isEmpty()) Empty("还没有运行记录") else RunRows(mine, setOf(RunColumn.ACCOUNT, RunColumn.SOURCE))
    }
}

/** Run notes beside each task: what happened when the user pressed 运行. */
@Composable
internal fun rememberRunNow(): Triple<Map<String, Pair<String, Boolean>>, Boolean, (CloudTask, CreateJobRequest, String) -> Unit> {
    val console = LocalConsole.current
    var notes by remember { mutableStateOf(mapOf<String, Pair<String, Boolean>>()) }
    var busy by remember { mutableStateOf(false) }
    return Triple(notes, busy) { task, request, done ->
        busy = true
        console.act({ notes = notes + (task.taskId to (done to true)) }) {
            try { console.api.run(request) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { notes = notes + (task.taskId to (io.github.yysassist.shared.failureText(failure) to false)); throw failure }
            finally { busy = false }
        }
    }
}
@Composable
internal fun Note(note: Pair<String, Boolean>?) {
    if (note != null) Text(note.first, color = if (note.second) LocalTokens.current.muted else LocalTokens.current.danger, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RunTab(device: DeviceView, app: AppView) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    val (job, queued) = appJobs(data.jobs, device.id, app.key)
    val accounts = data.accounts.filter { it.gameId == app.gameId }
    val tasks = device.tasks.filter { it.targetId == app.gameId }
    var chosen by remember { mutableStateOf<String?>(null) }
    val pick = chosen?.takeIf { id -> accounts.any { it.id == id } } ?: app.accountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
    val (notes, busy, run) = rememberRunNow()
    val narrow = LocalTier.current == Tier.NARROW
    // What runs now and what is queued behind it, in one card; an idle, online app has nothing to show (the header says 空闲).
    if (job != null || queued.isNotEmpty() || app.currentTask != null || !device.online) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).background(tokens.surface)
        .border(1.dp, if (job != null) tokens.primaryLine else tokens.line, RoundedCornerShape(Radius.xl))) {
        Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            if (job != null) {
                val (label, tone) = Format.jobStatus(job)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(job.taskName, style = MaterialTheme.typography.titleMedium); Pill(label, tone); Spacer(Modifier.weight(1f))
                    LinkButton("步骤 ›", { console.nav.go(Route.Job(job.id)) })
                }
                Muted(listOfNotNull(job.accountName, Format.jobSource(job), job.detail.ifEmpty { null }).joinToString(" · "))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { JobControls(job, small = true) }
            } else {
                Text(app.currentTask ?: if (device.online) "空闲" else "离线", style = MaterialTheme.typography.titleMedium)
                // Only what the user cannot see otherwise: a run the console cannot control, or why nothing arrives.
                if (app.currentTask != null) Muted("这次运行不是从控制台发起的，这里不能暂停或停止")
                else if (!device.online) Muted("上线后才会收到任务")
            }
        }
        queued.forEach { item ->
            HorizontalDivider(color = tokens.lineSoft)
            ListRow({ console.nav.go(Route.Job(item.id)) }, last = true) {
                Muted("接下来", Modifier.width(48.dp))
                Text("${item.taskName} · ${item.accountName ?: "—"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Format.jobSource(item)?.let { Muted(it) }
            }
        }
    }
    Card {
        if (pick != null) ChoiceLine("用", "运行") { Select(pick, accounts.map { it.id to it.name }, { chosen = it }, "运行用的游戏账号") }
        if (accounts.isEmpty() || pick == null) { Muted("先在“游戏账号”里添加这个游戏的账号。", Modifier.padding(Space.s4)); return@Card }
        val note = listOfNotNull(switchNote(app, pick, accounts), "设备离线，上线后执行".takeIf { !device.online }).joinToString("；")
        if (note.isNotEmpty()) Muted(note, Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s2))
        if (tasks.isEmpty()) Muted("这台设备还没有上报这个游戏的任务。", Modifier.padding(Space.s4))
        tasks.forEachIndexed { i, task -> ListRow(last = i == tasks.lastIndex) {
            Column(Modifier.weight(1f)) { Text(task.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Note(notes[task.taskId]) }
            HgbButton("运行", { run(task, CreateJobRequest(task.targetId, task.taskId, pick, device.id, app.key), "已排进${app.name}的队列") },
                small = true, enabled = !busy)
        } }
    }
}

/** A device with several apps (Windows): its games, each app as a card, what waits, and running a task now. */
@Composable
private fun DeviceDetail(device: DeviceView) = Page {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    val (status, tone) = deviceStatus(device, data.jobs)
    val gameIds = device.apps.map { it.gameId }.distinct()
    var chosen by remember { mutableStateOf(gameIds.firstOrNull()) }
    val gameId = chosen?.takeIf { it in gameIds } ?: gameIds.firstOrNull()
    fun gameName(id: String) = data.games.firstOrNull { it.id == id }?.name ?: id
    PageHeader(device.name, "${Format.platformName(device.platform)} · ${device.apps.size} 个应用", crumbs = "设备 / ${device.name}",
        back = if (console.nav.canGoBack) ({ console.nav.back(); Unit }) else null, aside = { Pill(status, tone, dot = true) })
    if (gameId == null) { Card { Empty(if (device.online) "这台设备上还没有护肝宝支持的游戏。" else "设备离线，上线后会显示它的应用。") }; return@Page }
    val apps = device.apps.filter { it.gameId == gameId }
    val accounts = data.accounts.filter { it.gameId == gameId }
    if (gameIds.size > 1) Segments(gameIds.map { id -> id to "${gameName(id)} · ${device.apps.count { it.gameId == id }} 个应用" }, gameId) { chosen = it }
    Block(gameName(gameId)) {
        CardGrid(apps.size) { index ->
            val app = apps[index]
            key(app.key) { AppCard(device, app, accounts) }
        }
    }
    DeviceWaiting(device, apps, gameId)
    RunNow(device, apps, accounts)
}

@Composable
private fun AppCard(device: DeviceView, app: AppView, accounts: List<GameAccountView>) {
            val console = LocalConsole.current
            val tokens = LocalTokens.current
            val data = console.data
            val state = rememberAppAccounts(device, app)
            val (job, _) = appJobs(data.jobs, device.id, app.key)
            val (label, appTone) = Format.appStatus(app.phase, device.online)
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.xl)).background(tokens.surface)
                .border(1.dp, if (job != null) tokens.primaryLine else tokens.line, RoundedCornerShape(Radius.xl))
                .clickable { console.nav.go(Route.App(device.id, app.key)) }.padding(horizontal = 18.dp, vertical = Space.s4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AccountMenu(app, accounts, state); Spacer(Modifier.weight(1f)); Pill(label, appTone)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(if (job != null) tokens.primarySoft else tokens.bg).padding(horizontal = 14.dp, vertical = Space.s3)) {
                    Text(job?.taskName ?: app.currentTask ?: "空闲", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Muted(job?.let { listOfNotNull(it.accountName, Format.jobSource(it)).joinToString(" · ") } ?: app.observed?.let { "游戏里登录着 ${it.nickname}" } ?: "没有正在运行的任务")
                }
                if (job != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { JobControls(job) }
            }
}

@Composable
private fun DeviceWaiting(device: DeviceView, apps: List<AppView>, gameId: String) {
    val console = LocalConsole.current
    val data = console.data
    val queued = apps.flatMap { app -> appJobs(data.jobs, device.id, app.key).second.map { it to "排在${app.name}后面" } }
    val waiting = data.jobs.filter { it.state == JobState.WAITING && it.targetId == gameId }
        .map { it to "等可用应用" + (it.latestStartAt?.let { at -> "，最晚 ${Format.shortTime(at)} 前开始" } ?: "") }
    val rows = queued + waiting
    if (rows.isEmpty()) return
    Card {
        CardTitle("等待中")
        rows.forEachIndexed { i, (job, where) -> ListRow({ console.nav.go(Route.Job(job.id)) }, i == rows.lastIndex) {
            Column(Modifier.weight(1f)) { Text("${job.taskName} · ${job.accountName ?: "—"}", style = MaterialTheme.typography.bodyMedium); Format.jobSource(job)?.let { Muted(it) } }
            Muted(where)
        } }
    }
}

@Composable
private fun RunNow(device: DeviceView, apps: List<AppView>, accounts: List<GameAccountView>) {
    var where by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    val (notes, busy, run) = rememberRunNow()
    val app = apps.firstOrNull { it.key == where }
    val pick = chosen?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
    val tasks = device.tasks.filter { it.targetId == apps.firstOrNull()?.gameId }
    Card {
        CardTitle("立即运行")
        if (pick == null) { Muted("先在“游戏账号”里添加这个游戏的账号。", Modifier.padding(Space.s4)); return@Card }
        ListRow {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    InlineField("应用") { Select(where, listOf<Pair<String?, String>>(null to "自动分配") + apps.map { it.key to it.name }, { key ->
                        where = key; apps.firstOrNull { it.key == key }?.accountId?.takeIf { id -> accounts.any { it.id == id } }?.let { chosen = it }
                    }, "在哪个应用运行") }
                    InlineField("账号") { Select(pick, accounts.map { it.id to it.name }, { chosen = it }, "运行用的游戏账号") }
                }
                app?.let { switchNote(it, pick, accounts) }?.let { Muted(it) }
            }
        }
        tasks.forEachIndexed { i, task -> ListRow(last = i == tasks.lastIndex) {
            Column(Modifier.weight(1f)) { Text(task.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Note(notes[task.taskId]) }
            HgbButton("运行", { run(task, CreateJobRequest(task.targetId, task.taskId, pick, app?.let { device.id }, app?.key),
                app?.let { "已排进${it.name}的队列" } ?: "已提交，服务端会分配应用") }, small = true, enabled = !busy)
        } }
    }
}
