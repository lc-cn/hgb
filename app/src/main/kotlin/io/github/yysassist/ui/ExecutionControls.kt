package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.core.execution.*
import io.github.yysassist.core.model.Principal
import androidx.compose.ui.platform.LocalContext
import java.util.Date

@Composable
internal fun RunActionButton(name: String, current: Boolean, queued: Boolean, enabled: Boolean,
    enqueue: () -> Unit, pause: () -> Unit, stop: () -> Unit, paused: Boolean = false, resume: (ResumeMode) -> Unit = {},
    onRequestResume: (() -> Unit)? = null, currentStateLabel: String? = null) {
    var chooseResume by remember { mutableStateOf(false) }
    LaunchedEffect(paused, enabled) { if (!paused || !enabled) chooseResume = false }
    val label = if (current) (if (paused) "继续：$name" else "暂停：$name") else "加入队列：$name"
    Box(Modifier.size(UiSpace.hit).clip(RoundedCornerShape(UiSpace.medium))
        .background(glassBrush(false)).combinedClickable(enabled = enabled && !queued,
            role = Role.Button, onClickLabel = label, onLongClickLabel = if (current) "停止当前任务" else null,
            onLongClick = if (current) stop else null, onClick = {
                if (current && paused) { if (onRequestResume != null) onRequestResume() else chooseResume = true } else if (current) pause() else enqueue()
            })
        .semantics {
            contentDescription = label
            stateDescription = when { queued -> "队列中"; current && currentStateLabel != null -> currentStateLabel; paused -> "已暂停"; current -> "执行中"; else -> "未加入队列" }
        }, contentAlignment = Alignment.Center) {
        Icon(if (current && !paused) AssistIcons.Pause else AssistIcons.Play, null, Modifier.size(UiSpace.icon),
            tint = (if (current && !paused) LocalAssistPalette.current.orange else LocalAssistPalette.current.teal)
                .copy(alpha = if (enabled && !queued) .82f else .35f))
    }
    if (chooseResume && enabled && paused) ResumeTaskDialog({ chooseResume = false; resume(it) }, { chooseResume = false })
}

@Composable
internal fun ExecutionWorkbench(state: ExecutorSnapshot, status: RuntimeStatus, wide: Boolean,
    pause: () -> Unit, resume: (Long?, ResumeMode) -> Unit, remove: (Long) -> Unit, stop: () -> Unit) {
    if (status == RuntimeStatus.Loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "读取执行记录" }) }
        return
    }
    if (status is RuntimeStatus.Failure && state.current == null && state.waiting.isEmpty() && state.facts.isEmpty()) {
        Column(Modifier.padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            Text("执行记录暂不可用", style = MaterialTheme.typography.titleMedium)
            Text(status.message, color = MaterialTheme.colorScheme.error)
            Text("请检查存储空间后重新打开助手。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var chooseResumeFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.current?.runId) { if (chooseResumeFor != state.current?.runId) chooseResumeFor = null }
    val usable = status !is RuntimeStatus.Failure
    val results = state.facts.filter { it.type in setOf("task-completed", "task-failed", "cancelled") }.takeLast(3).reversed()
    val enqueuedAt = remember(state.facts) { state.facts.filter { it.type == "enqueued" }.associate { it.runId to it.occurredAtEpochMillis } }
    Column(Modifier.fillMaxSize()) {
        if (status is RuntimeStatus.Failure || status is RuntimeStatus.RecoveryBlocked) {
            Text(when (status) { is RuntimeStatus.Failure -> status.message; is RuntimeStatus.RecoveryBlocked -> status.message; else -> "" },
                Modifier.fillMaxWidth().padding(UiSpace.page).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(if (wide) .65f else 1f)) {
                Row(Modifier.fillMaxWidth().padding(UiSpace.page), verticalAlignment = Alignment.CenterVertically) {
                    Text("执行队列", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    Text((state.waiting.size + if (state.current == null) 0 else 1).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.current == null && state.waiting.isEmpty()) {
                    Column(Modifier.padding(UiSpace.page), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                        Text("队列为空", style = MaterialTheme.typography.bodyLarge)
                        Text("从任务或计划右侧加入队列。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!wide && results.isNotEmpty()) {
                        Text("最近结果", Modifier.padding(UiSpace.page), style = MaterialTheme.typography.titleSmall)
                        results.forEach { fact -> ListItem(headlineContent = { Text(fact.taskName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (fact.type == "task-completed") "任务完成" else if (fact.type == "task-failed") "任务失败" else "已移除或停止") }) }
                    }
                } else LazyColumn {
                    items(listOfNotNull(state.current) + state.waiting, key = { it.runId }) { run ->
                        QueueRow(run, run.runId == state.current?.runId, state, status, enqueuedAt[run.runId], usable, remove)
                    }
                }
            }
            if (wide) {
                VerticalDivider()
                LazyColumn(Modifier.weight(.35f), contentPadding = PaddingValues(UiSpace.page),
                    verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
                    item { Text("最近结果", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall) }
                    if (results.isEmpty()) item { Text("暂无运行结果", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(results.withIndex().toList(), key = { it.index }) { (_, fact) ->
                        Column {
                            Text(fact.taskName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when (fact.type) { "task-completed" -> "任务完成"; "task-failed" -> "任务失败"; else -> "已移除或停止" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(UiSpace.medium),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            val phase = when (status) { is RuntimeStatus.Waiting -> "等待中"; is RuntimeStatus.RecoveryBlocked -> "等待核对"; is RuntimeStatus.Failure -> "存储异常"; else -> phaseLabel(state.phase) }
            Text(phase, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, fontWeight = FontWeight.Medium,
                color = if (status is RuntimeStatus.RecoveryBlocked) LocalAssistPalette.current.warning
                    else if (status is RuntimeStatus.Waiting) MaterialTheme.colorScheme.onSurfaceVariant
                    else if (state.phase == ExecutorPhase.RUNNING) LocalAssistPalette.current.blue else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (val principal = state.context?.principal) {
                null -> "未识别账号"; Principal.Anonymous -> "无需账号"; is Principal.Account -> principal.id
            }, Modifier.widthIn(max = UiSpace.versionEntryMax), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.manualHold) IconButton(enabled = usable && state.phase == ExecutorPhase.PAUSED,
                onClick = { state.current?.let { chooseResumeFor = it.runId } ?: resume(null, ResumeMode.CONTINUE) }) {
                Icon(AssistIcons.Play, "继续队列", tint = LocalAssistPalette.current.teal.copy(alpha = .82f))
            } else key(state.current?.runId) {
                RunActionButton("队列", true, false, usable && (state.current != null || state.waiting.isNotEmpty()), {}, pause, stop, currentStateLabel = phase)
            }
        }
    }
    val resumeId = chooseResumeFor
    if (resumeId != null && usable) ResumeTaskDialog({ chooseResumeFor = null; resume(resumeId, it) }, { chooseResumeFor = null })
}

@Composable
private fun ResumeTaskDialog(resume: (ResumeMode) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("恢复当前任务") },
        text = { Column {
            TextButton(onClick = { resume(ResumeMode.CONTINUE) }) { Text("继续") }
            TextButton(onClick = { resume(ResumeMode.RESTART_CURRENT_TASK) }) { Text("重新开始当前任务") }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun QueueRow(run: RunProgress, current: Boolean, state: ExecutorSnapshot, status: RuntimeStatus, enqueuedAt: Long?, enabled: Boolean, remove: (Long) -> Unit) {
    val time = enqueuedAt?.let { android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(it)) }
    val swipe = rememberSwipeToDismissBoxState()
    val latestRemove by rememberUpdatedState(remove)
    LaunchedEffect(swipe.settledValue) {
        if (swipe.settledValue == SwipeToDismissBoxValue.StartToEnd) {
            latestRemove(run.runId)
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(swipe, enableDismissFromStartToEnd = enabled, enableDismissFromEndToStart = false,
        modifier = Modifier.semantics { if (enabled) customActions = listOf(CustomAccessibilityAction("移出队列") { remove(run.runId); true }) },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(LocalAssistPalette.current.error.copy(alpha = .12f)).padding(UiSpace.page), Alignment.CenterStart) {
                Icon(AssistIcons.Close, null, tint = LocalAssistPalette.current.error)
            }
        }) {
        ListItem(headlineContent = { Text(if (run.request.ref.kind == SubmissionKind.PLAN) run.request.ref.id else run.task.name,
            maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = {
            Text(if (current && status is RuntimeStatus.Waiting) status.message else if (current) (if (run.request.ref.kind == SubmissionKind.PLAN) "${run.task.name} · " else "") +
                "第 ${run.stepIndex + 1}/${run.task.stepIds.size} 步" else "优先级 ${run.priority}" + (time?.let { " · $it 入队" } ?: ""),
                maxLines = if (current && status is RuntimeStatus.Waiting) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { if (current && status is RuntimeStatus.Waiting) liveRegion = LiveRegionMode.Polite })
        }, trailingContent = {
            Text(if (!current) "队列中" else when (status) {
                is RuntimeStatus.Waiting -> "等待中"; is RuntimeStatus.RecoveryBlocked -> "等待核对"; is RuntimeStatus.Failure -> "状态未确认"; else -> phaseLabel(state.phase)
            }, style = MaterialTheme.typography.labelSmall,
                color = if (current) LocalAssistPalette.current.blue else MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
}

internal fun phaseLabel(phase: ExecutorPhase): String = when (phase) {
    ExecutorPhase.IDLE -> "待机"; ExecutorPhase.RUNNING -> "执行中"; ExecutorPhase.PAUSING -> "暂停中"
    ExecutorPhase.PAUSED -> "已暂停"; ExecutorPhase.REMOVING -> "移除中"; ExecutorPhase.SWITCHING -> "交接中"
    ExecutorPhase.BLOCKED_UNKNOWN -> "结果待核对"
}
