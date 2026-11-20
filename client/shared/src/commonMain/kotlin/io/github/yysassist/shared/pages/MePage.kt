package io.github.yysassist.shared.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.LocalParts
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*
import java.time.ZoneId

/**
 * 我的: who is signed in and what they have, how they sign in, this end's look and the plan time zone, this device's
 * own settings, the devices bound to the account, help, and signing out.
 */
@Composable
fun MePage(local: LocalParts) = Page {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    val uri = LocalUriHandler.current
    val instance = io.github.yysassist.shared.LocalInstance.current
    var account by remember(console.account) { mutableStateOf(console.account) }
    var unlinking by remember { mutableStateOf(false) }
    var unbinding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf<io.github.yysassist.protocol.MergeOffer?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val who = account?.email ?: account?.githubLogin ?: ""
    if (unlinking) Confirm("解绑 GitHub？之后只能用邮箱登录。", "解绑", { console.act { console.api.unlinkGitHub(); account = console.api.me().also(console.accountChanged) } }, { unlinking = false })
    unbinding?.let { (id, name) -> Confirm("解绑“$name”？这台设备会下线，正在运行的作业结果将标记为未知。", "解绑", { console.act { console.api.removeDevice(id) } }, { unbinding = null }) }
    if (leaving) Confirm("退出登录？这台设备会停止接收任务。", "退出登录", { console.act { local.signOut() } }, { leaving = false })

    Card {
        Row(Modifier.padding(Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(tokens.primary), contentAlignment = Alignment.Center) {
                Text(who.take(1).uppercase(), color = tokens.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(who, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Muted("${data.devices.size} 台设备 · ${data.accounts.size} 个游戏账号 · ${data.plans.size} 个计划")
            }
        }
    }
    Block("登录方式") {
        Card {
            ListRow {
                Column(Modifier.weight(1f)) {
                    Text("邮箱", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Muted(account?.email?.let { "$it · ${if (account?.hasPassword == true) "已设置密码" else "没有设置密码，用验证码登录"}" } ?: "未绑定")
                }
                if (account != null && account?.email == null && !binding) HgbButton("绑定邮箱", { binding = true }, small = true)
            }
            if (binding && offer == null) BindEmail({ account = it; console.accountChanged(it); binding = false }, { offer = it })
            ListRow(last = true) {
                Column(Modifier.weight(1f)) {
                    Text("GitHub", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Muted(account?.githubLogin?.let { "已绑定 $it" } ?: "未绑定，可以在网页控制台的“我的”里绑定")
                }
                if (account?.githubLogin != null) HgbButton("解除绑定", { unlinking = true }, small = true, enabled = account?.email != null)
            }
        }
    }
    offer?.let { current -> MergeCard(current) { merged -> offer = null; binding = false; if (merged != null) { account = merged; console.accountChanged(merged) } } }
    Block("偏好") {
        Card {
            local.appearance?.let { (look, choose) ->
                ListRow {
                    Text("外观", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Select(look, listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色"), choose, "外观")
                }
            }
            TimeZoneRow(data.timeZone)
        }
    }
    // Narrow has no sidebar, so this device's own places (开发) and switches live here.
    val localEntries = if (LocalTier.current == Tier.NARROW) local.entries else emptyList()
    if (localEntries.isNotEmpty() || local.settings != null) Block("本机") {
        Card {
            localEntries.forEachIndexed { i, entry ->
                ListRow({ console.nav.go(Route.Local(entry.id)) }, last = i == localEntries.lastIndex && local.settings == null) {
                    Text(entry.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("进入 ›", color = tokens.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            local.settings?.invoke()
        }
    }
    Block("我的设备") {
        Card {
            data.devices.forEach { device ->
                ListRow {
                    Column(Modifier.weight(1f)) {
                        Text(device.name + if (device.id == console.thisDevice) "（这台）" else "", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Muted(listOf(Format.platformName(device.platform), if (device.online) "在线" else "最后在线 ${Format.shortTime(device.lastSeenAt)}").joinToString(" · "))
                    }
                    if (device.id != console.thisDevice) MoreMenu("${device.name} 的操作", listOf(MenuAction("解绑", danger = true) { unbinding = device.id to device.name }))
                }
            }
            ListRow(last = true) {
                Muted("在其他设备上安装护肝宝并登录这个账号即可添加", Modifier.weight(1f))
                LinkButton("下载", { uri.openUri(instance.downloadUrl) })
            }
        }
    }
    Block("帮助") {
        Card {
            ListRow({ uri.openUri(instance.docsUrl) }) { Text("使用文档", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Muted("→") }
            ListRow({ uri.openUri(instance.issuesUrl) }) {
                Text("反馈问题", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Muted("GitHub Issues →")
            }
            ListRow({ uri.openUri(instance.privacyUrl) }) { Text("隐私政策", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Muted("→") }
            ListRow({ uri.openUri(instance.termsUrl) }, last = true) { Text("服务条款", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Muted("→") }
        }
    }
    if (deleting) account?.let { DeleteAccount(it, local.signOut) { deleting = false } }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
        HgbButton("退出登录", { leaving = true }, ButtonKind.DANGER)
        if (!deleting && account != null) LinkButton("注销账号", { deleting = true })
    }
}

@Composable
private fun TimeZoneRow(zone: String) {
    val console = LocalConsole.current
    val zones = remember(zone) { (ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") } + zone).distinct().sorted() }
    // Saved as soon as it is picked; every plan runs by this zone.
    ListRow(last = true) {
        Text("计划时区", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Select(zone, zones.map { it to it }, { next -> console.act { console.api.setTimeZone(next) } }, "计划时区")
    }
}
