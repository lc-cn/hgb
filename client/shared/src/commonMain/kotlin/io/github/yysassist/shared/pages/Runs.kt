package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.protocol.*
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*

/** The jobs an app holds: the one it is running (or was just handed), then the ones queued behind it. */
fun appJobs(jobs: List<JobView>, deviceId: String, appKey: String): Pair<JobView?, List<JobView>> {
    val mine = jobs.filter { it.deviceId == deviceId && it.appKey == appKey && it.state in Format.ACTIVE }
    val current = mine.firstOrNull { it.state == JobState.RUNNING } ?: mine.firstOrNull { it.state == JobState.LEASED }
    return current to mine.filter { it != current }.reversed()
}
/** Where a job ran: the device, and the app when the device has more than one. */
fun jobPlace(job: JobView, devices: List<DeviceView>): String {
    val name = job.deviceName ?: return if (job.state == JobState.WAITING) "等可用应用" else "—"
    val device = devices.firstOrNull { it.id == job.deviceId }
    val app = device?.takeIf { it.apps.size > 1 }?.apps?.firstOrNull { it.key == job.appKey }
    return app?.let { "$name · ${it.name}" } ?: name
}
fun lastFinished(jobs: List<JobView>, keep: (JobView) -> Boolean) = jobs.firstOrNull { keep(it) && it.state !in Format.ACTIVE }

/** Pause or resume, and stop (or cancel before it starts), for a job that is still going. */
@Composable
fun JobControls(job: JobView, small: Boolean = true) {
    if (job.state !in Format.ACTIVE || job.cancelRequested) return
    val console = LocalConsole.current
    if (job.state == JobState.RUNNING) HgbButton(if (job.pauseRequested) "继续" else "暂停",
        { console.act { if (job.pauseRequested) console.api.resume(job.id) else console.api.pause(job.id) } }, small = small)
    HgbButton(if (job.state == JobState.RUNNING) "停止" else "取消", { console.act { console.api.cancel(job.id) } }, ButtonKind.DANGER, small)
}

enum class RunColumn { PLACE, ACCOUNT, SOURCE }
/** Run records as rows that open the record, newest first. The narrow tier keeps only task, time and result. */
@Composable
fun RunRows(jobs: List<JobView>, columns: Set<RunColumn>, limit: Int? = null) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val narrow = LocalTier.current == Tier.NARROW
    var all by remember { mutableStateOf(false) }
    val shown = if (limit != null && !all) jobs.take(limit) else jobs
    shown.forEachIndexed { index, job ->
        val (label, tone) = Format.jobStatus(job)
        ListRow({ console.nav.go(Route.Job(job.id)) }, last = index == shown.lastIndex && (limit == null || jobs.size <= limit)) {
            Text(job.taskName + if (RunColumn.ACCOUNT !in columns && job.accountName != null) " · ${job.accountName}" else "",
                Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (!narrow) {
                if (RunColumn.ACCOUNT in columns) Cell(job.accountName ?: "—")
                // With one device, where it ran says nothing.
                if (RunColumn.PLACE in columns && LocalTier.current == Tier.WIDE && console.data.devices.size > 1) Cell(jobPlace(job, console.data.devices))
                if (RunColumn.SOURCE in columns) Cell(Format.jobSource(job).orEmpty())
            }
            Text(Format.shortTime(job.updatedAt), color = tokens.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) { ResultText(label, tone) }
        }
    }
    if (limit != null && jobs.size > limit) Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        LinkButton(if (all) "只看最近的" else "显示全部 ${jobs.size} 条", { all = !all })
    }
}
@Composable
private fun RowScope.Cell(text: String) =
    Text(text, Modifier.weight(1f), color = LocalTokens.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)

private fun nameOf(accounts: List<GameAccountView>, id: String?) = accounts.firstOrNull { it.id == id }?.name
/** Only when running [accountId] on [app] switches accounts (or cannot): what will happen, in one short line. */
fun switchNote(app: AppView, accountId: String, accounts: List<GameAccountView>): String? {
    if (app.accountId == accountId) return null
    val name = nameOf(accounts, accountId) ?: "这个账号"
    if (!app.switchable) return "${app.name}不能切号，只在它登录着${name}时运行"
    return "会先切到$name" + (nameOf(accounts, app.defaultAccountId)?.let { "，完成后切回$it" } ?: "")
}
