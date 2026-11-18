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
import io.github.yysassist.core.content.TaskBundle
import io.github.yysassist.core.execution.ExecutorSnapshot
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.storage.PlanDefinition
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val WeekdayLabels = DayOfWeek.entries.associateWith { "一二三四五六日"[it.value - 1].toString() }
internal fun clockText(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
internal fun scheduleText(schedule: PlanSchedule): String = (if (schedule.daily) "每天" else
    schedule.days.sorted().joinToString("、") { "周${WeekdayLabels[it]}" }) + " " + clockText(schedule.minuteOfDay)

private fun whenText(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    val day = when (moment.toLocalDate()) {
        today -> "今天"; today.plusDays(1) -> "明天"
        else -> "周${WeekdayLabels[moment.dayOfWeek]}"
    }
    return "$day ${clockText(moment.hour * 60 + moment.minute)}"
}

private data class PlanResult(val text: String, val kind: Int)
private fun planResult(plan: PlanDefinition, state: ExecutorSnapshot): PlanResult {
    val run = plan.lastRunId
    plan.lastOutcome?.let { return PlanResult(it, 2) }
    if (run == null) return PlanResult("尚未运行", 0)
    if (state.current?.runId == run) return PlanResult("运行中", 1)
    if (state.waiting.any { it.runId == run }) return PlanResult("排队中", 1)
    val ended = state.facts.lastOrNull { it.runId == run && it.type == "run-ended" } ?: return PlanResult("已提交", 0)
    return when (ended.detail) {
        "COMPLETED" -> PlanResult("上次成功", 3); "PARTIAL_FAILURE" -> PlanResult("上次部分失败", 2)
        "FAILED" -> PlanResult("上次失败", 2); else -> PlanResult("上次已结束", 0)
    }
}

@Composable
internal fun PlansPage(plans: List<PlanDefinition>?, state: ExecutorSnapshot, bundle: TaskBundle?, nextRun: (PlanDefinition, Long) -> Long,
    setEnabled: (PlanDefinition, Boolean) -> Unit, runNow: (PlanDefinition) -> Unit, edit: (PlanDefinition) -> Unit, create: () -> Unit) {
    if (plans == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    if (plans.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyContent("暂无计划", "把任务组合起来，按每天或指定星期自动运行。")
            Box(Modifier.padding(horizontal = UiSpace.page)) { SmallAction("新建计划", onClick = create) }
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val next = plans.filter { it.enabled }.minByOrNull { nextRun(it, now) }
    val names = bundle?.tasks?.associate { it.id to it.name }.orEmpty()
    LazyVerticalGrid(GridCells.Adaptive(340.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.gap),
        horizontalArrangement = Arrangement.spacedBy(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("下一次运行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(next?.let { whenText(nextRun(it, now)) } ?: "暂无启用的计划", style = MaterialTheme.typography.titleLarge)
                next?.let { Text("${it.name} · ${it.taskIds.size} 个任务", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        items(plans, key = { it.id }) { plan ->
            PlanCard(plan, planResult(plan, state), names, open == plan.id, { open = if (open == plan.id) null else plan.id },
                { setEnabled(plan, it) }, { runNow(plan) }, { edit(plan) })
        }
    }
}

@Composable
private fun PlanCard(plan: PlanDefinition, result: PlanResult, names: Map<String, String>, open: Boolean, toggle: () -> Unit,
    setEnabled: (Boolean) -> Unit, runNow: () -> Unit, edit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalAssistPalette.current
    AssistCard {
        Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = toggle).padding(start = 14.dp, end = UiSpace.gap, top = 14.dp, bottom = UiSpace.gap)) {
                Text(plan.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(scheduleText(plan.schedule), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            AssistSwitch(plan.enabled, label = "启用计划：${plan.name}", change = setEnabled)
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = UiSpace.medium).semantics { contentDescription = scheduleText(plan.schedule) },
            horizontalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            DayOfWeek.entries.forEach { day ->
                val active = day in plan.schedule.days
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (active) colors.primary else colors.surfaceContainer), Alignment.Center) {
                    Text(WeekdayLabels.getValue(day), color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (open) {
            RowDivider()
            plan.taskIds.forEachIndexed { index, id ->
                if (index > 0) RowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(colors.surfaceContainer), Alignment.Center) {
                        Text("${index + 1}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(names[id] ?: "当前版本没有此任务", color = if (id in names) colors.onSurface else colors.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = UiSpace.tiny, bottom = UiSpace.medium), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                listOf("编辑" to edit, "立即运行" to runNow).forEach { (label, action) ->
                    Box(Modifier.weight(1f).heightIn(min = UiSpace.hit).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer).clickable(onClick = action), Alignment.Center) { Text(label) }
                }
            }
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            Text("${plan.taskIds.size} 个任务", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            val tint: Color = when (result.kind) { 1 -> palette.blue; 2 -> palette.warning; 3 -> palette.success; else -> colors.onSurfaceVariant }
            Text(result.text, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}
