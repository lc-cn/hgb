package io.github.yysassist.shared.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** 设备: a card per device with what it is doing, then the latest runs across all of them. */
@Composable
fun DevicesPage() = Page {
    val console = LocalConsole.current
    val data = console.data
    PageHeader("设备", "${data.devices.size} 台 · ${data.devices.count { it.online }} 台在线")
    if (data.devices.isEmpty()) Card { Empty("在手机或电脑上安装护肝宝，用这个账号登录，设备就会出现在这里。") }
    else CardGrid(data.devices.size) { DeviceCard(data.devices[it]) }
    Block("最近运行") {
        Card { if (data.jobs.isEmpty()) Empty("还没有运行记录") else RunRows(data.jobs, setOf(RunColumn.PLACE, RunColumn.SOURCE), 12) }
    }
}

/** How a device is doing as a pill, and how many of its apps are running. */
fun deviceStatus(device: DeviceView, jobs: List<JobView>): Triple<String, Tone, Int> {
    val running = device.apps.count { app -> app.phase == "RUNNING" || jobs.any { it.deviceId == device.id && it.appKey == app.key && it.state == JobState.RUNNING } }
    return when {
        !device.online -> Triple("离线", Tone.NEUTRAL, 0)
        running > 1 -> Triple("$running 个应用运行中", Tone.INFO, running)
        running == 1 || device.phase == "RUNNING" -> Triple("运行中", Tone.INFO, maxOf(running, 1))
        else -> Triple("在线", Tone.OK, 0)
    }
}

@Composable
private fun DeviceCard(device: DeviceView) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    val (status, tone, running) = deviceStatus(device, data.jobs)
    val active = data.jobs.filter { it.deviceId == device.id && (it.state == JobState.RUNNING || it.state == JobState.LEASED) }
    fun account(id: String?) = data.accounts.firstOrNull { it.id == id }?.name
    val now = when {
        !device.online -> "离线 · 最后在线 ${Format.shortTime(device.lastSeenAt)}"
        active.size > 1 || running > 1 -> "${active.size.takeIf { it > 0 } ?: running} 个应用在运行（${active.joinToString("、") { it.accountName ?: it.taskName }}）"
        active.size == 1 -> active[0].let { job -> listOfNotNull(device.apps.firstOrNull { it.key == job.appKey }?.name, job.accountName, job.taskName).joinToString(" · ") }
        device.apps.isEmpty() -> "空闲 · 还没有护肝宝支持的游戏"
        else -> "空闲 · " + device.apps.joinToString("；") { app -> "${app.name} · ${account(app.accountId) ?: app.observed?.nickname ?: "未指定账号"}" }
    }
    val last = lastFinished(data.jobs) { it.deviceId == device.id }
    val queued = data.jobs.count { it.deviceId == device.id && it.state == JobState.QUEUED }
    val after = listOfNotNull(queued.takeIf { it > 0 }?.let { "排队 $it 个" },
        last?.let { "上次 ${Format.shortTime(it.updatedAt)} ${it.taskName} ${Format.JOB_STATES.getValue(it.state).first}" }).joinToString(" · ")
    val busy = running > 0 && device.online
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.xl)).background(tokens.surface).border(1.dp, tokens.line, RoundedCornerShape(Radius.xl))
        .clickable { console.nav.go(Route.Device(device.id)) }.padding(horizontal = Space.s5, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Muted(Format.platformName(device.platform) + if (device.id == console.thisDevice) " · 这台手机" else "")
            }
            Pill(status, tone, dot = true)
        }
        Text(now, Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(if (busy) tokens.primarySoft else tokens.bg)
            .padding(horizontal = 14.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (after.isNotEmpty()) Muted(after)
    }
}
