package io.github.yysassist.shared.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.protocol.*
import io.github.yysassist.shared.AccountTab
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*

/** 游戏账号详情: everything that belongs to the account, whichever device or app runs it. */
@Composable
fun AccountDetailPage(id: String, tab: AccountTab) {
    val console = LocalConsole.current
    val data = console.data
    val account = data.accounts.firstOrNull { it.id == id }
        ?: return NotFound("找不到这个游戏账号，它可能已经移除", "回到游戏账号") { console.nav.open(Section.ACCOUNTS) }
    val game = data.games.firstOrNull { it.id == account.gameId }
    val (now, tone) = accountNow(account, data)
    Page {
        PageHeader(account.name, listOfNotNull(game?.name ?: account.gameId, account.uid?.let { "编号 $it" }, account.nickname).joinToString(" · "),
            crumbs = "游戏账号 / ${game?.name ?: account.gameId} / ${account.name}",
            back = if (console.nav.canGoBack) ({ console.nav.back(); Unit }) else null, aside = { Pill(now, tone) })
        PageTabs(AccountTab.entries.map { it to it.label }, tab) { console.nav.replace(Route.Account(account.id, it)) }
        when (tab) {
            AccountTab.TASKS -> TasksTab(account, game?.tasks.orEmpty())
            AccountTab.PLANS -> PlanList(account, game)
            AccountTab.RUNS -> Card {
                val runs = data.jobs.filter { it.accountId == account.id }
                if (runs.isEmpty()) Empty("这个账号还没有运行记录") else RunRows(runs, setOf(RunColumn.PLACE, RunColumn.SOURCE))
            }
        }
    }
}

/** A task's settings on a page of their own (narrow and medium tiers), from the account's task list. */
@Composable
fun SettingsPage(accountId: String, taskId: String) {
    val console = LocalConsole.current
    val account = console.data.accounts.firstOrNull { it.id == accountId }
    val task = console.data.games.firstOrNull { it.id == account?.gameId }?.tasks?.firstOrNull { it.taskId == taskId }
    if (account == null || task == null) return NotFound("找不到这个任务的设置", "返回") { console.nav.back() }
    Page {
        PageHeader(task.name, "${account.name}的设置", back = { console.nav.back(); Unit })
        SettingsForm(account, task, titled = false)
    }
}

@Composable
private fun TasksTab(account: GameAccountView, tasks: List<CloudTask>) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    if (tasks.isEmpty()) { Card { Empty("这个游戏还没有任务。设备上传任务包后会出现在这里。") }; return }
    var chosen by remember { mutableStateOf(tasks.first().taskId) }
    val taskId = chosen.takeIf { id -> tasks.any { it.taskId == id } } ?: tasks.first().taskId
    var where by remember { mutableStateOf<Pair<String, String>?>(null) }
    val (notes, busy, run) = rememberRunNow()
    val places = data.devices.filter { it.online }.flatMap { device -> device.apps.filter { it.gameId == account.gameId }
        .map { app -> Triple(device.id to app.key, if (device.apps.size > 1) "${device.name} · ${app.name}" else device.name, app) } }
    val place = places.firstOrNull { it.first == where }
    val wide = LocalTier.current == Tier.WIDE
    val list: @Composable (Modifier) -> Unit = { modifier ->
        Card(modifier) {
            ChoiceLine("在", "运行") { Select(where, listOf<Pair<Pair<String, String>?, String>>(null to "自动分配") + places.map { it.first to it.second }, { where = it }, "运行的位置") }
            place?.let { switchNote(it.third, account.id, data.accounts.filter { a -> a.gameId == account.gameId }) }?.let {
                Muted(it, Modifier.padding(start = Space.s4, end = Space.s4, bottom = Space.s2))
            }
            tasks.forEachIndexed { i, task ->
                val on = wide && task.taskId == taskId
                ListRow({ if (wide) chosen = task.taskId else console.nav.go(Route.Settings(account.id, task.taskId)) }, i == tasks.lastIndex) {
                    Column(Modifier.weight(1f)) {
                        Text(task.name, fontWeight = FontWeight.SemiBold, color = if (on) tokens.navCurrent else tokens.ink, style = MaterialTheme.typography.bodyMedium)
                        Note(notes[task.taskId])
                    }
                    HgbButton("运行", { run(task, CreateJobRequest(task.targetId, task.taskId, account.id, place?.first?.first, place?.first?.second),
                        place?.let { "已提交，排在${it.second}的队列" } ?: "已提交，服务端会按账号分配应用") }, small = true, enabled = !busy)
                }
            }
        }
    }
    // Wide: the list and the chosen task's settings side by side; narrower tiers open the settings as their own page.
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        list(Modifier.weight(1f)); Box(Modifier.weight(1.4f)) { key(taskId) { SettingsForm(account, tasks.first { it.taskId == taskId }) } }
    } else list(Modifier)
}

/** The account's plans. Changes that arrive while one is being edited do not touch the editor. */
@Composable
private fun PlanList(account: GameAccountView, game: GameView?) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    var editing by remember { mutableStateOf<String?>(null) }
    val mine = data.plans.filter { it.accountId == account.id }
    fun devices(plan: PlanView) = if (plan.deviceIds.isEmpty()) "所有装了${game?.name ?: "这个游戏"}的设备"
        else "只在 " + plan.deviceIds.joinToString("、") { id -> data.devices.firstOrNull { it.id == id }?.name ?: "已解绑的设备" }
    fun lastRun(plan: PlanView) = data.jobs.firstOrNull { it.planId == plan.id }
        ?.let { listOfNotNull(Format.shortTime(it.updatedAt), it.deviceName, Format.JOB_STATES.getValue(it.state).first).joinToString(" · ") } ?: "还没有运行过"
    Card {
        CardTitle("计划") { HgbButton("新建", { editing = "new" }, small = true, enabled = editing != "new") }
        if (editing == "new") Box(Modifier.background(tokens.bg).padding(Space.s4)) { PlanEditor(account, game, null) { editing = null } }
        mine.forEachIndexed { i, plan ->
            ListRow(last = i == mine.lastIndex && editing != plan.id) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Muted(listOfNotNull(Format.planRule(plan), plan.tasks.joinToString("、") { it.name }.ifEmpty { "没有任务" },
                        devices(plan).takeIf { plan.deviceIds.isNotEmpty() }).joinToString(" · "))
                    Muted("上次：${lastRun(plan)}")
                }
                HgbSwitch(plan.enabled, { on -> console.act { console.api.changePlan(plan.id, plan.save().copy(enabled = on)) } })
                MoreMenu("${plan.name} 的操作", listOf(
                    MenuAction("立即运行") { console.act({ console.say("已交给服务器下发") }) { console.api.runPlan(plan.id) } },
                    MenuAction(if (editing == plan.id) "收起编辑" else "编辑") { editing = if (editing == plan.id) null else plan.id }))
            }
            if (editing == plan.id) Box(Modifier.background(tokens.bg).padding(Space.s4)) { key(plan.id) { PlanEditor(account, game, plan) { editing = null } } }
        }
        if (mine.isEmpty() && editing != "new") Muted("这个账号还没有计划。", Modifier.padding(Space.s4))
    }
}
private fun PlanView.save() = PlanSave(name, days, minuteOfDay, tasks, deviceIds, latestStartMinutes, enabled, revision)

/** The week as seven small boxes; with [flip] they are buttons that pick the days. */
@Composable
private fun WeekDays(days: Set<Int>, flip: ((Int) -> Unit)?) {
    val tokens = LocalTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(if (flip != null) Space.s2 else Space.s1)) {
        Format.WEEK.forEachIndexed { i, label ->
            val on = (i + 1) in days
            val size = if (flip != null) 36.dp else 22.dp
            Box(Modifier.size(size).background(if (on) tokens.primary else tokens.neutralBg, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .then(if (flip != null) Modifier.clickable(onClickLabel = "周$label") { flip(i + 1) } else Modifier), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) tokens.onPrimary else tokens.muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Creates a plan for [account], or edits [plan]. Nothing is filled in on the user's behalf beyond the task list. */
@Composable
private fun PlanEditor(account: GameAccountView, game: GameView?, plan: PlanView?, close: () -> Unit) {
    val console = LocalConsole.current
    var name by remember { mutableStateOf(plan?.name.orEmpty()) }
    var at by remember { mutableStateOf(plan?.let { Format.clockText(it.minuteOfDay) }.orEmpty()) }
    var late by remember { mutableStateOf((plan?.latestStartMinutes ?: 30).toString()) }
    var enabled by remember { mutableStateOf(plan?.enabled ?: true) }
    var days by remember { mutableStateOf(plan?.days.orEmpty().toSet()) }
    var tasks by remember { mutableStateOf(plan?.tasks.orEmpty().map { it.taskId }.toSet()) }
    var where by remember { mutableStateOf(plan?.deviceIds.orEmpty().toSet()) }
    var removing by remember { mutableStateOf(false) }
    // The revision the edit started from: a newer one arriving over the stream must not be what this save claims.
    val base = remember { plan?.revision ?: 0 }
    val time = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(at.trim())
    val lateMinutes = late.toIntOrNull()?.takeIf { it in 1..1440 }
    val ready = name.isNotBlank() && time != null && lateMinutes != null
    if (removing && plan != null) Confirm("删除计划“${plan.name}”？", "删除", { console.act(close) { console.api.deletePlan(plan.id) } }, { removing = false })
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Field("计划名称") { TextInput(name, { name = it.take(40) }, "如每日领取", Modifier.width(200.dp)) }
            Field("运行时间") { TextInput(at, { at = it.take(5) }, "08:30", Modifier.width(120.dp)) }
            Field("到点后最晚多少分钟内开始") { TextInput(late, { late = it.filter(Char::isDigit).take(4) }, "30", Modifier.width(120.dp)) }
        }
        Field("运行周期") { WeekDays(days) { day -> days = if (day in days) days - day else days + day } }
        Field("按顺序运行的任务") {
            FlowRow { game?.tasks.orEmpty().forEach { task -> Check(task.name, task.taskId in tasks, { on -> tasks = if (on) tasks + task.taskId else tasks - task.taskId }) } }
        }
        Field("可以在哪些设备上运行（都不选就是所有装了这个游戏的设备）") {
            FlowRow { console.data.devices.forEach { device -> Check(device.name, device.id in where, { on -> where = if (on) where + device.id else where - device.id }) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) { HgbSwitch(enabled, { enabled = it }); Text("启用", style = MaterialTheme.typography.bodyMedium) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            HgbButton(if (plan != null) "保存计划" else "新建计划", {
                val minute = time!!.groupValues[1].toInt() * 60 + time.groupValues[2].toInt()
                // The revision the edit started from: a save over a newer one is refused instead of overwriting it.
                val save = PlanSave(name.trim(), days.sorted(), minute, game?.tasks.orEmpty().filter { it.taskId in tasks },
                    console.data.devices.filter { it.id in where }.map { it.id }, lateMinutes!!, enabled, base)
                console.act(close) { if (plan != null) console.api.changePlan(plan.id, save) else console.api.createPlan(account.id, save) }
            }, ButtonKind.PRIMARY, enabled = ready)
            HgbButton("取消", close)
            if (plan != null) HgbButton("删除计划", { removing = true }, ButtonKind.DANGER)
        }
        if (at.isNotEmpty() && time == null) Message("运行时间写成 时:分，如 08:30")
    }
}
