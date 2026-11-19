package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.protocol.JobState
import io.github.yysassist.protocol.PlanRun
import io.github.yysassist.protocol.PlanView
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val WeekdayLabels = DayOfWeek.entries.associateWith { "一二三四五六日"[it.value - 1].toString() }
internal fun clockText(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
internal fun scheduleText(schedule: PlanSchedule): String = (if (schedule.daily) "每天" else
    schedule.days.sorted().joinToString("、") { "周${WeekdayLabels[it]}" }) + " " + clockText(schedule.minuteOfDay)

internal fun PlanView.schedule() = PlanSchedule(days.map(DayOfWeek::of).toSet(), minuteOfDay)
internal fun nextRun(plan: PlanView, now: Long, zone: ZoneId): Long = plan.schedule().next(Instant.ofEpochMilli(now).atZone(zone)).toInstant().toEpochMilli()

private fun whenText(epochMillis: Long, zone: ZoneId): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    val day = when (moment.toLocalDate()) {
        today -> "今天"; today.plusDays(1) -> "明天"
        else -> "周${WeekdayLabels[moment.dayOfWeek]}"
    }
    return "$day ${clockText(moment.hour * 60 + moment.minute)}"
}

private data class PlanResult(val text: String, val kind: Int)
/** How the plan's latest run went, from the server's jobs for it. */
private fun planResult(run: PlanRun?): PlanResult {
    val states = run?.states ?: return PlanResult("尚未运行", 0)
    return when {
        states.any { it == JobState.RUNNING || it == JobState.LEASED } -> PlanResult("运行中", 1)
        states.any { it == JobState.WAITING || it == JobState.QUEUED } -> PlanResult("排队中", 1)
        states.all { it == JobState.COMPLETED } -> PlanResult("上次成功", 3)
        states.any { it == JobState.COMPLETED } -> PlanResult("上次部分完成", 2)
        states.all { it == JobState.SKIPPED } -> PlanResult(run.detail.ifEmpty { "上次被跳过" }, 2)
        states.all { it == JobState.CANCELLED } -> PlanResult("上次已停止", 0)
        else -> PlanResult(run.detail.ifEmpty { "上次没有完成" }, 2)
    }
}

@Composable
/**
 * The plans of the account the game here is signed in to. They are kept and timed by the server, so they run even
 * when this phone is not the one that runs them; offline they can only be looked at.
 */
internal fun PlansPage(plans: List<PlanView>?, account: String?, editable: Boolean, runs: List<PlanRun>, zone: ZoneId,
    setEnabled: (PlanView, Boolean) -> Unit, runNow: (PlanView) -> Unit, edit: (PlanView) -> Unit, create: () -> Unit) {
    if (plans == null) { EmptyContent("登录护肝宝账号后使用计划", "计划存在服务器上，由服务器到点下发给设备。在 设置 → 云端控制 里登录。"); return }
    if (account == null) { EmptyContent("先选好当前登录的游戏账号", "计划属于游戏账号。在 设置 → 云端控制 里选择这台设备上的游戏当前登录的是哪个账号。"); return }
    if (plans.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyContent("「$account」暂无计划", "把任务组合起来，按每天或指定星期自动运行。")
            if (editable) Box(Modifier.padding(horizontal = UiSpace.page)) { SmallAction("新建计划", onClick = create) }
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val next = plans.filter { it.enabled }.minByOrNull { nextRun(it, now, zone) }
    LazyVerticalGrid(GridCells.Adaptive(340.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.gap),
        horizontalArrangement = Arrangement.spacedBy(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("下一次运行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(next?.let { whenText(nextRun(it, now, zone), zone) } ?: "暂无启用的计划", style = MaterialTheme.typography.titleLarge)
                Text((next?.let { "${it.name} · ${it.tasks.size} 个任务 · " } ?: "") + "「$account」的计划" + if (editable) "" else " · 离线时只能查看",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(plans, key = { it.id }) { plan ->
            PlanCard(plan, planResult(runs.firstOrNull { it.planId == plan.id }), editable, open == plan.id, { open = if (open == plan.id) null else plan.id },
                { setEnabled(plan, it) }, { runNow(plan) }, { edit(plan) })
        }
    }
}

@Composable
private fun PlanCard(plan: PlanView, result: PlanResult, editable: Boolean, open: Boolean, toggle: () -> Unit,
    setEnabled: (Boolean) -> Unit, runNow: () -> Unit, edit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalAssistPalette.current
    val schedule = plan.schedule()
    AssistCard {
        Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = toggle).padding(start = 14.dp, end = UiSpace.gap, top = 14.dp, bottom = UiSpace.gap)) {
                Text(plan.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(scheduleText(schedule), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            AssistSwitch(plan.enabled, label = "启用计划：${plan.name}", enabled = editable, change = setEnabled)
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = UiSpace.medium).semantics { contentDescription = scheduleText(schedule) },
            horizontalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            DayOfWeek.entries.forEach { day ->
                val active = day in schedule.days
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (active) colors.primary else colors.surfaceContainer), Alignment.Center) {
                    Text(WeekdayLabels.getValue(day), color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (open) {
            RowDivider()
            plan.tasks.forEachIndexed { index, task ->
                if (index > 0) RowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(colors.surfaceContainer), Alignment.Center) {
                        Text("${index + 1}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(task.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (editable) Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = UiSpace.tiny, bottom = UiSpace.medium), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                listOf("编辑" to edit, "立即运行" to runNow).forEach { (label, action) ->
                    Box(Modifier.weight(1f).heightIn(min = UiSpace.hit).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer).clickable(onClick = action), Alignment.Center) { Text(label) }
                }
            }
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            Text("${plan.tasks.size} 个任务", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            val tint: Color = when (result.kind) { 1 -> palette.blue; 2 -> palette.warning; 3 -> palette.success; else -> colors.onSurfaceVariant }
            Text(result.text, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}
