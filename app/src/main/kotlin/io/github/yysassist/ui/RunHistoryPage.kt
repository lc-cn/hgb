package io.github.yysassist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.execution.ExecutionFact
import io.github.yysassist.core.execution.ExecutorSnapshot
import io.github.yysassist.core.model.Principal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

private enum class RunFilter(val label: String) { ALL("全部"), ATTENTION("需关注"), DONE("已完成") }
private enum class RunKind { DONE, ATTENTION, NEUTRAL }

private val ExecutionFact.kind: RunKind get() = when (type) {
    "task-completed" -> RunKind.DONE
    "task-failed" -> RunKind.ATTENTION
    "run-ended" -> if (detail == "COMPLETED") RunKind.DONE else RunKind.ATTENTION
    else -> RunKind.NEUTRAL
}
private val ExecutionFact.resultLabel: String get() = when (type) {
    "task-completed" -> "完成"
    "task-failed" -> "失败"
    "cancelled" -> "已中断"
    else -> when (detail) { "PARTIAL_FAILURE" -> "本轮部分失败"; "FAILED" -> "本轮失败"; "COMPLETED" -> "本轮完成"; else -> "本轮结束" }
}

@Composable
internal fun RunHistoryPage(state: ExecutorSnapshot) {
    var filterName by rememberSaveable { mutableStateOf(RunFilter.ALL.name) }
    val filter = RunFilter.valueOf(filterName)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    val startedAt = remember(state.facts) { state.facts.groupBy { it.runId }.mapValues { (_, facts) -> facts.minOf { it.occurredAtEpochMillis } } }
    val days = remember(state.facts, filter, today) {
        state.facts.asReversed().filter { it.type in setOf("task-completed", "task-failed", "run-ended", "cancelled") }
            // A completed round repeats the task result directly above it.
            .filterNot { it.type == "run-ended" && it.detail == "COMPLETED" }
            .filter { when (filter) { RunFilter.ALL -> true; RunFilter.ATTENTION -> it.kind == RunKind.ATTENTION; RunFilter.DONE -> it.kind == RunKind.DONE } }
            .groupBy { Instant.ofEpochMilli(it.occurredAtEpochMillis).atZone(zone).toLocalDate() }.toList()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = UiSpace.medium, vertical = UiSpace.gap).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            RunFilter.entries.forEach { item -> Box(Modifier.weight(1f)) {
                GlassChoice(item.label, item == filter, true, Modifier.fillMaxWidth()) { filterName = item.name }
            } }
        }
        if (days.isEmpty()) EmptyContent(if (filter == RunFilter.ALL) "暂无运行记录" else "没有符合条件的记录")
        else LazyColumn(contentPadding = PaddingValues(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
            items(days, key = { it.first.toString() }) { (day, facts) ->
                SectionLabel(when (day) { today -> "今天"; today.minusDays(1) -> "昨天"; else -> day.toString() })
                AssistCard {
                    facts.forEachIndexed { index, fact ->
                        if (index > 0) RowDivider()
                        RunRecord(fact, startedAt[fact.runId], expanded == fact.id) { expanded = if (expanded == fact.id) null else fact.id }
                    }
                }
            }
        }
    }
}

private fun durationText(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when { seconds < 60 -> "用时 $seconds 秒"; seconds < 3600 -> "用时 ${seconds / 60} 分钟"; else -> "用时 ${seconds / 3600} 小时 ${seconds % 3600 / 60} 分钟" }
}

@Composable
private fun RunRecord(fact: ExecutionFact, startedAt: Long?, open: Boolean, toggle: () -> Unit) {
    val palette = LocalAssistPalette.current
    val colors = MaterialTheme.colorScheme
    val time = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(fact.occurredAtEpochMillis))
    val account = when (val principal = fact.context.principal) { Principal.Anonymous -> "无需账号"; is Principal.Account -> principal.id }
    val color: Color = when (fact.kind) { RunKind.DONE -> palette.success; RunKind.ATTENTION -> palette.error; RunKind.NEUTRAL -> colors.onSurfaceVariant }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = toggle).padding(horizontal = 14.dp, vertical = UiSpace.gap),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            Column(Modifier.weight(1f)) {
                Text(fact.taskName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                Text(listOfNotNull(time, startedAt?.let { durationText(fact.occurredAtEpochMillis - it) }).joinToString(" · "), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(fact.resultLabel, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        if (open) Text(listOf(fact.detail.takeIf { it.isNotBlank() && fact.type != "run-ended" }, "任务版本 ${fact.packageVersion}", account).filterNotNull().joinToString("\n"),
            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
