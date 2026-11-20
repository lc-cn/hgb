package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.protocol.GameAccountSave
import io.github.yysassist.protocol.GameAccountView
import io.github.yysassist.protocol.GameView
import io.github.yysassist.protocol.JobState
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.console.ConsoleData
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*

/** Where the account is now, from the jobs and the apps signed in to it. */
fun accountNow(account: GameAccountView, data: ConsoleData): Pair<String, Tone> {
    data.jobs.firstOrNull { it.accountId == account.id && it.state == JobState.RUNNING }?.let { return "正在 ${it.deviceName ?: "设备"} 上运行" to Tone.INFO }
    val signedIn = data.devices.flatMap { device -> device.apps.filter { it.accountId == account.id }.map { if (device.apps.size > 1) "${device.name} · ${it.name}" else device.name } }
    return if (signedIn.isNotEmpty()) "登录在 ${signedIn.joinToString("、")}" to Tone.OK else "空闲" to Tone.NEUTRAL
}

/** 游戏账号: the accounts of one game at a time, with adding, renaming and removing. */
@Composable
fun GameAccountsPage() = Page {
    val console = LocalConsole.current
    val data = console.data
    var adding by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(data.games.firstOrNull()?.id) }
    val gameId = chosen?.takeIf { id -> data.games.any { it.id == id } } ?: data.games.firstOrNull()?.id
    PageHeader("游戏账号", aside = { HgbButton(if (adding) "收起" else "添加", { adding = !adding }, small = true) })
    if (adding) AddAccount(data.games, gameId)
    if (gameId == null) Card { Empty("设备上线并上传任务包后，就能为它的游戏添加账号。") }
    else {
        if (data.games.size > 1) Segments(data.games.map { game -> game.id to "${game.name} · ${data.accounts.count { it.gameId == game.id }} 个" }, gameId) { chosen = it }
        val accounts = data.accounts.filter { it.gameId == gameId }
        Card {
            if (accounts.isEmpty()) Empty("这个游戏还没有账号。")
            // Keyed by account, so a row's edit state stays with its account when the list changes.
            accounts.forEachIndexed { i, account -> key(account.id) { AccountRow(account, i == accounts.lastIndex) } }
        }
    }
}

/** A table row on wider tiers, a card-like row when narrow; renaming opens a form in its place. */
@Composable
private fun AccountRow(account: GameAccountView, last: Boolean) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val (now, tone) = accountNow(account, data)
    val lastJob = data.jobs.firstOrNull { it.accountId == account.id }
    val number = listOfNotNull(account.uid?.let { "编号 $it" }, account.nickname).joinToString(" · ").ifEmpty { "未填编号" }
    val used = lastJob?.let { Format.shortTime(it.updatedAt) + (it.deviceName?.let { name -> " · $name" } ?: "") } ?: "从未使用"
    if (removing) Confirm("移除“${account.name}”？它的任务设置和计划也会删除，过去的运行记录保留。", "移除",
        { console.act { console.api.deleteAccount(account.id) } }, { removing = false })
    if (editing) { Box(Modifier.padding(Space.s4)) { EditAccount(account) { editing = false } }; return }
    ListRow({ console.nav.go(Route.Account(account.id)) }, last) {
        Column(Modifier.weight(1f)) {
            Text(account.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Muted(number, maxLines = 1)
        }
        if (LocalTier.current != Tier.NARROW || tone == Tone.INFO) Text(now, color = if (tone == Tone.INFO) tokens.run else tokens.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        if (LocalTier.current == Tier.WIDE) Text(used, color = tokens.faint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        MoreMenu("${account.name} 的操作", listOf(MenuAction("重命名") { editing = true }, MenuAction("移除", danger = true) { removing = true }))
    }
}

@Composable
private fun AccountFields(name: String, setName: (String) -> Unit, uid: String, setUid: (String) -> Unit, nickname: String, setNickname: (String) -> Unit, uidHint: String = "") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Field("名称") { TextInput(name, { setName(it.take(40)) }, "如主号", Modifier.width(180.dp)) }
        Field("编号") { TextInput(uid, { setUid(it.take(40)) }, uidHint, Modifier.width(180.dp)) }
        Field("游戏昵称") { TextInput(nickname, { setNickname(it.take(40)) }, uidHint, Modifier.width(180.dp)) }
    }
}

@Composable
private fun EditAccount(account: GameAccountView, done: () -> Unit) {
    val console = LocalConsole.current
    var name by remember { mutableStateOf(account.name) }
    var uid by remember { mutableStateOf(account.uid.orEmpty()) }
    var nickname by remember { mutableStateOf(account.nickname.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        AccountFields(name, { name = it }, uid, { uid = it }, nickname, { nickname = it })
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            HgbButton("保存", { console.act(done) { console.api.changeAccount(account.id, GameAccountSave(name = name.trim(), uid = uid.trim().ifEmpty { null }, nickname = nickname.trim().ifEmpty { null })) } },
                ButtonKind.PRIMARY, enabled = name.isNotBlank())
            HgbButton("取消", done)
        }
    }
}

@Composable
private fun AddAccount(games: List<GameView>, gameId: String?) {
    val console = LocalConsole.current
    var game by remember { mutableStateOf(gameId) }
    var name by remember { mutableStateOf("") }; var uid by remember { mutableStateOf("") }; var nickname by remember { mutableStateOf("") }
    val target = game?.takeIf { id -> games.any { it.id == id } } ?: games.firstOrNull()?.id
    Card {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Muted("也可以在设备页把游戏里读到的账号直接保存下来。护肝宝不会问你游戏密码。")
            if (target != null) {
                if (games.size > 1) Field("游戏") { Select(target, games.map { it.id to it.name }, { game = it }, "游戏") }
                AccountFields(name, { name = it }, uid, { uid = it }, nickname, { nickname = it }, "可不填")
                HgbButton("添加账号", { console.act({ name = ""; uid = ""; nickname = ""; console.say("已添加") }) {
                    console.api.createAccount(GameAccountSave(target, name.trim(), uid.trim().ifEmpty { null }, nickname.trim().ifEmpty { null }))
                } }, ButtonKind.PRIMARY, enabled = name.isNotBlank())
            }
        }
    }
}
