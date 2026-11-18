package io.github.yysassist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.RuntimeStatus
import io.github.yysassist.core.execution.*

/** Anything the user may need to see or control keeps the status surface on screen. */
internal fun runStatusVisible(state: ExecutorSnapshot, status: RuntimeStatus): Boolean =
    state.current != null || state.waiting.isNotEmpty() || state.manualHold || state.phase == ExecutorPhase.BLOCKED_UNKNOWN ||
        status is RuntimeStatus.Waiting || status is RuntimeStatus.RecoveryBlocked || status is RuntimeStatus.Failure

private fun runTitle(state: ExecutorSnapshot): String = state.current?.task?.name ?: when {
    state.manualHold -> "队列已暂停"
    state.waiting.isNotEmpty() -> "等待运行"
    else -> "运行状态"
}

internal fun runSummary(state: ExecutorSnapshot, status: RuntimeStatus): String = when (status) {
    is RuntimeStatus.Waiting -> status.message
    is RuntimeStatus.RecoveryBlocked -> status.message
    is RuntimeStatus.Failure -> status.message
    else -> listOfNotNull(phaseLabel(state.phase),
        state.current?.let { "第 ${it.stepIndex + 1}/${it.task.stepIds.size} 步" },
        state.waiting.size.takeIf { it > 0 }?.let { "队列还有 $it 个" }).joinToString(" · ")
}

private fun runProgress(state: ExecutorSnapshot): Float? = state.current?.let {
    (it.stepIndex + 1f) / it.task.stepIds.size.coerceAtLeast(1)
}

@Composable
private fun RunControls(state: ExecutorSnapshot, status: RuntimeStatus, pause: () -> Unit, resume: (Long?, ResumeMode) -> Unit, stop: () -> Unit, labelled: Boolean = false) {
    val usable = status !is RuntimeStatus.Failure
    var chooseResume by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val current = state.current
    LaunchedEffect(current?.runId) { chooseResume = false; confirmStop = false }
    val hold = state.manualHold
    val primaryEnabled = if (hold) usable && state.phase == ExecutorPhase.PAUSED else usable && (current != null || state.waiting.isNotEmpty())
    val primary = { if (!hold) pause() else if (current != null) chooseResume = true else resume(null, ResumeMode.CONTINUE) }
    if (labelled) {
        Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            Button(primary, Modifier.weight(1f).height(44.dp), primaryEnabled, RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) { Text(if (hold) "继续" else "暂停") }
            OutlinedButton({ confirmStop = true }, Modifier.weight(1f).height(44.dp), usable && current != null, RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) { Text("停止") }
        }
    } else {
        IconButton(primary, enabled = primaryEnabled) { Icon(if (hold) AssistIcons.PlayFilled else AssistIcons.Pause, if (hold) "继续运行" else "暂停运行") }
        IconButton({ confirmStop = true }, enabled = usable && current != null) { Icon(AssistIcons.Stop, "停止当前任务") }
    }
    if (chooseResume && current != null && usable) ResumeTaskDialog({ chooseResume = false; resume(current.runId, it) }, { chooseResume = false })
    if (confirmStop && current != null) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text("停止当前任务？") }, text = { Text("停止后队列会暂停，可以稍后继续运行其余任务。") },
        confirmButton = { TextButton(onClick = { confirmStop = false; stop() }) { Text("停止") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } })
}

/** Portrait: a compact bar above the navigation. */
@Composable
internal fun RunStatusBar(state: ExecutorSnapshot, status: RuntimeStatus, open: () -> Unit,
    pause: () -> Unit, resume: (Long?, ResumeMode) -> Unit, stop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpace.medium, vertical = UiSpace.gap),
        shape = RoundedCornerShape(14.dp), color = colors.primary, contentColor = colors.onPrimary) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = UiSpace.tiny),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(runTitle(state), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(runSummary(state, status), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                RunControls(state, status, pause, resume, stop)
            }
            runProgress(state)?.let { value ->
                LinearProgressIndicator(progress = { value }, Modifier.fillMaxWidth().height(3.dp),
                    color = paletteOnStrong().blue, trackColor = colors.onPrimary.copy(alpha = .2f), drawStopIndicator = {}, gapSize = 0.dp)
            }
        }
    }
}

/** Landscape: the same state and controls as a side panel. */
@Composable
internal fun RunStatusPanel(state: ExecutorSnapshot, status: RuntimeStatus, open: () -> Unit,
    pause: () -> Unit, resume: (Long?, ResumeMode) -> Unit, stop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = Modifier.width(216.dp).fillMaxHeight(), shape = RectangleShape, color = colors.surfaceContainerHigh) {
        Column(Modifier.padding(UiSpace.medium), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            Text(if (state.manualHold) "已暂停" else "正在运行", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(runTitle(state), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            runProgress(state)?.let { value ->
                LinearProgressIndicator(progress = { value }, Modifier.fillMaxWidth().height(4.dp), color = LocalAssistPalette.current.blue,
                    trackColor = colors.surfaceContainer, drawStopIndicator = {}, gapSize = 0.dp)
            }
            Text(runSummary(state, status), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f)) {
                state.waiting.take(3).forEachIndexed { index, run ->
                    HorizontalDivider(color = colors.outlineVariant)
                    Text("${if (index == 0) "下一个" else "然后"}：${run.task.name}", Modifier.padding(vertical = 6.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) { RunControls(state, status, pause, resume, stop, labelled = true) }
        }
    }
}
