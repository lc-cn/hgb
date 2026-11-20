package io.github.yysassist.shared.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.protocol.*
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.theme.DesignTokens.Radius
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class StepState { DONE, STOP, RUNNING, PLAIN }
private class Step(val stepId: String, val name: String) {
    val events = mutableListOf<JobEventView>(); var state = StepState.PLAIN; var summary = ""; var tookMs = 0L
}

/**
 * Groups the log by the step it belongs to, in the order the steps ran (as the web console does). Each start of a step
 * opens its own group; a step's later lines can arrive after the next step has started, so they join the latest group
 * of the same step rather than the last group.
 */
private fun stepsOf(events: List<JobEventView>, job: JobView): List<Step> {
    val steps = mutableListOf<Step>()
    for (event in events) {
        val key = event.step.ifEmpty { "-" }
        var step = steps.lastOrNull { it.stepId == key && (event.kind != "step-started" || it.events.none { seen -> seen.kind == "step-started" }) }
        if (event.kind == "step-started" && step != null && step !== steps.last()) step = null
        if (step == null) { step = Step(key, event.stepName.ifEmpty { "作业" }); steps += step }
        step.events += event
    }
    steps.forEachIndexed { i, step ->
        val kinds = step.events.map { it.kind }.toSet()
        val last = i == steps.lastIndex
        // The step's own result says more than the later check that its input stopped.
        val facts = step.events.reversed().filter { it.kind != "action" && it.detail.isNotEmpty() }
        val fact = facts.firstOrNull { it.kind != "revocation-observed" } ?: facts.firstOrNull() ?: step.events.last()
        step.tookMs = step.events.last().at - step.events.first().at
        step.summary = listOf(Format.eventKind(fact.kind), Format.eventDetail(fact)).filter { it.isNotEmpty() }.joinToString("：")
        step.state = when {
            "task-failed" in kinds || "cancelled" in kinds || "input-unconfirmed" in kinds -> StepState.STOP
            "step-completed" in kinds -> StepState.DONE
            last && job.state in Format.ACTIVE -> StepState.RUNNING
            last && job.state != JobState.COMPLETED -> StepState.STOP
            else -> StepState.PLAIN
        }
    }
    return steps
}

/** 运行记录详情: what the job was, how it ended, and every step and action the device reported. */
@Composable
fun JobPage(id: String) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    val data = console.data
    val job = data.jobs.firstOrNull { it.id == id } ?: return NotFound("找不到这条运行记录", "返回") { console.nav.back() }
    var events by remember { mutableStateOf<List<JobEventView>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(emptySet<Int>()) }
    var all by remember { mutableStateOf(false) }
    // Read again whenever the log grows or the stream starts over.
    LaunchedEffect(id, data.epoch, data.logVersion[id]) {
        try { events = console.api.jobEvents(id); error = null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (problem: Exception) { error = io.github.yysassist.shared.failureText(problem) }
    }
    val (label, tone) = Format.jobStatus(job)
    val steps = events?.let { stepsOf(it, job) }.orEmpty()
    val stopped = steps.firstOrNull { it.state == StepState.STOP }
    val started = events?.firstOrNull { it.kind == "step-started" }?.at
    val ended = events?.lastOrNull()?.at
    val accountExists = data.accounts.any { it.id == job.accountId }
    Page {
        // The account is a link to its settings; the line under the title says only where, when and how long.
        PageHeader(job.taskName, listOfNotNull(jobPlace(job, data.devices).takeIf { data.devices.size > 1 || job.deviceName == null },
            started?.let { "${Format.shortTime(it)} 开始" } ?: "${Format.shortTime(job.createdAt)} 创建",
            if (started != null && ended != null && job.state !in Format.ACTIVE) "用时 ${Format.seconds(ended - started)}" else null, Format.jobSource(job)).joinToString(" · "),
            crumbs = "运行记录", back = if (console.nav.canGoBack) ({ console.nav.back(); Unit }) else null, aside = { Pill(label, tone) },
            titleExtra = job.accountName?.let { name -> {
                val id = job.accountId
                if (id != null && accountExists) LinkButton(name, { console.nav.go(Route.Account(id)) }) else Muted(name)
            } })
        val again = job.state !in Format.ACTIVE && job.accountId != null && accountExists
        if (again || (job.state in Format.ACTIVE && !job.cancelRequested)) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            JobControls(job, small = false)
            if (again) HgbButton("再运行一次", {
                console.act({ console.say("已提交，服务端会分配应用") }) { console.api.run(CreateJobRequest(job.targetId, job.taskId, job.accountId!!)) }
            }, ButtonKind.PRIMARY)
        }
        if (job.state == JobState.FAILED || job.state == JobState.UNKNOWN || job.state == JobState.SKIPPED || (job.state == JobState.CANCELLED && stopped != null)) {
            val bad = job.state == JobState.FAILED
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(if (bad) tokens.dangerBg else tokens.warnSoft)
                .border(1.dp, if (bad) tokens.danger.copy(alpha = .3f) else tokens.warnLine, RoundedCornerShape(Radius.lg)).padding(Space.s4),
                horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text("!", color = if (bad) tokens.danger else tokens.warn, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Text(stopped?.let { "停在“${it.name}”：$label" } ?: label, fontWeight = FontWeight.SemiBold, color = if (bad) tokens.danger else tokens.warnInk)
                    Text(job.detail.ifEmpty { stopped?.summary ?: "设备没有说明原因。" }, style = MaterialTheme.typography.bodyMedium, color = if (bad) tokens.ink else tokens.warnInk)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("步骤", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (steps.isNotEmpty()) HgbButton(if (all) "收起全部" else "展开全部", { all = !all; open = emptySet() }, small = true)
        }
        when {
            error != null -> Card { Empty(error!!) }
            events == null -> Card { Empty("正在读取…") }
            steps.isEmpty() -> Card { Empty(if (job.state in Format.ACTIVE) "设备还没有上报明细" else "这次运行没有明细") }
            else -> Card {
                steps.forEachIndexed { i, step ->
                    val shown = all || i in open
                    StepRow(step, shown, i == steps.lastIndex) { all = false; open = if (i in open) open - i else open + i }
                }
            }
        }
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
@Composable
private fun StepRow(step: Step, shown: Boolean, last: Boolean, toggle: () -> Unit) {
    val tokens = LocalTokens.current
    Column(Modifier.background(if (step.state == StepState.STOP) tokens.warnSoft else tokens.surface)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (shown) "收起" else "展开", onClick = toggle).padding(horizontal = Space.s4, vertical = Space.s3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            val (mark, bg, fg) = when (step.state) {
                StepState.DONE -> Triple("✓", tokens.okBg, tokens.ok); StepState.STOP -> Triple("!", tokens.warnBg, tokens.warn)
                StepState.RUNNING -> Triple("…", tokens.runBg, tokens.run); StepState.PLAIN -> Triple("", tokens.neutralBg, tokens.neutral)
            }
            Box(Modifier.size(24.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Text(mark, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(step.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Muted(step.summary, maxLines = if (shown) Int.MAX_VALUE else 2)
            }
            if (step.tookMs > 0) Muted(Format.seconds(step.tookMs))
            Icon(HgbIcons.Chevron, null, Modifier.size(16.dp).rotate(if (shown) 270f else 90f), tint = tokens.muted)
        }
        if (shown) Column(Modifier.fillMaxWidth().background(tokens.bg).padding(horizontal = Space.s4, vertical = Space.s2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            step.events.forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Text(Instant.ofEpochMilli(event.at).atZone(ZoneId.systemDefault()).format(clock), color = tokens.faint, style = MaterialTheme.typography.labelSmall)
                    Text(if (event.kind == "action") event.action else Format.eventKind(event.kind), Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                    Text(Format.eventDetail(event), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = if (event.kind == "action" && Regex("^(UNAVAILABLE|INTERRUPTED)").containsMatchIn(event.detail)) tokens.warn else tokens.muted)
                    if (event.elapsedMs > 0) Text(Format.shortDuration(event.elapsedMs), color = tokens.faint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!last) androidx.compose.material3.HorizontalDivider(color = tokens.lineSoft)
    }
}
