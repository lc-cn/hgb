package io.github.yysassist.floating

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.storage.TaskAddress
import io.github.yysassist.ui.*
import kotlinx.serialization.json.*

internal enum class FloatingLayer { BALL, QUICK, COMMON }
internal object FloatingUi {
    val ball = 52.dp; val quickWidth = 292.dp; val wideQuickWidth = 300.dp; val wideCommonWidth = 430.dp
    val portraitHeight = 480.dp; val landscapeHeight = 340.dp
    val portraitTop = 94.dp; val landscapeTop = 36.dp
    fun width(layer: FloatingLayer, wide: Boolean): Dp = when {
        layer == FloatingLayer.BALL -> ball
        !wide -> quickWidth
        layer == FloatingLayer.COMMON -> wideCommonWidth
        else -> wideQuickWidth
    }
}
@Composable
internal fun FloatingSurface(layer: FloatingLayer, state: ExecutorSnapshot, status: RuntimeStatus,
    common: FloatingTasksState, wide: Boolean, maxHeight: Dp, changeLayer: (FloatingLayer) -> Unit,
    enqueue: (TaskAddress) -> Unit, pause: (Long?) -> Unit, stop: (Long) -> Unit, resume: (Long?, ResumeMode) -> Unit,
    openApp: (TaskAddress?) -> Unit, ballMotion: ((MotionEvent) -> Boolean)? = null, maxWidth: Dp = FloatingUi.wideCommonWidth, feedback: String? = null) {
    val palette = LocalAssistPalette.current
    val phase = when (status) { RuntimeStatus.Loading -> "读取中"; is RuntimeStatus.Waiting -> "等待中"; is RuntimeStatus.Failure -> "存储异常"; is RuntimeStatus.RecoveryBlocked -> "等待核对"; else -> phaseLabel(state.phase) }
    if (layer == FloatingLayer.BALL) {
        val tap = { changeLayer(FloatingLayer.QUICK) }
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val gesture = if (ballMotion == null) Modifier.clickable(onClick = tap) else Modifier.pointerInteropFilter(onTouchEvent = ballMotion)
        Box(Modifier.size(FloatingUi.ball).clip(CircleShape).background(glassBrush(true)).then(gesture)
            .semantics { contentDescription = "展开快捷控制"; stateDescription = phase; role = Role.Button; onClick { tap(); true } }, Alignment.Center) {
            val icon = if (state.manualHold) AssistIcons.Pause else AssistIcons.Tasks
            Icon(icon, null, Modifier.size(UiSpace.icon), tint = paletteOnStrong().blue.copy(alpha = .82f))
            val badge = if (status is RuntimeStatus.Failure || status is RuntimeStatus.RecoveryBlocked || state.unresolved.isNotEmpty()) "!"
                else state.waiting.size.takeIf { it > 0 }?.toString()
            badge?.let { Text(it, Modifier.align(Alignment.TopEnd).padding(UiSpace.tiny), style = MaterialTheme.typography.labelSmall,
                color = paletteOnStrong().warning) }
        }
        return
    }
    val width = FloatingUi.width(layer, wide)
    var recoveryFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.current?.runId, state.phase, status) {
        if (state.phase != ExecutorPhase.PAUSED || state.current?.runId != recoveryFor || status is RuntimeStatus.Failure || status == RuntimeStatus.Loading) recoveryFor = null
    }
    Column(Modifier.width(minOf(width, maxWidth)).heightIn(max = maxHeight).clip(RoundedCornerShape(UiSpace.section))
        .background(glassBrush()).padding(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
        if (layer == FloatingLayer.COMMON) Row(verticalAlignment = Alignment.CenterVertically) {
            FloatingIcon(AssistIcons.Back, "返回快捷控制") { changeLayer(FloatingLayer.QUICK) }
            Text("常用", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            FloatingIcon(AssistIcons.Close, "收起悬浮面板") { changeLayer(FloatingLayer.BALL) }
        }
        val current = state.current
        Row(Modifier.fillMaxWidth().padding(start = UiSpace.gap), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                Text(current?.task?.name ?: if (state.waiting.isNotEmpty()) "执行队列" else "暂无执行任务", maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(if (current == null) phase else "$phase · 第 ${current.stepIndex + 1}/${current.task.stepIds.size} 步", maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    color = if (status is RuntimeStatus.Failure || state.unresolved.isNotEmpty()) palette.error else palette.blue)
            }
            key(current?.runId) {
                if (current == null && state.manualHold) FloatingIcon(AssistIcons.Play, "继续队列", enabled = status == RuntimeStatus.Ready && state.phase == ExecutorPhase.PAUSED) {
                    resume(null, ResumeMode.CONTINUE)
                } else if (current != null || state.waiting.isNotEmpty()) RunActionButton(current?.task?.name ?: "队列", current = true, queued = false,
                    enabled = status !is RuntimeStatus.Failure && status != RuntimeStatus.Loading &&
                        ((current != null && state.phase in setOf(ExecutorPhase.RUNNING, ExecutorPhase.PAUSED)) || (current == null && state.waiting.isNotEmpty())),
                    enqueue = {}, pause = { pause(current?.runId) }, stop = { current?.runId?.let(stop) },
                    paused = current != null && state.phase == ExecutorPhase.PAUSED, onRequestResume = { recoveryFor = current?.runId }, currentStateLabel = phase)
            }
        }
        val recovery = recoveryFor
        if (recovery != null && status !is RuntimeStatus.Failure && status != RuntimeStatus.Loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            OutlinedButton({ recoveryFor = null; resume(recovery, ResumeMode.CONTINUE) }, Modifier.weight(1f)) { Text("继续") }
            OutlinedButton({ recoveryFor = null; resume(recovery, ResumeMode.RESTART_CURRENT_TASK) }, Modifier.weight(1f)) { Text("重新开始") }
        }
        if (status is RuntimeStatus.Waiting) Text(status.message, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val issue = when (status) { is RuntimeStatus.Waiting -> null; is RuntimeStatus.Failure -> status.message; is RuntimeStatus.RecoveryBlocked -> status.message; else -> state.unresolved.firstOrNull()?.reason ?: feedback }
        issue?.let { TextButton(onClick = { openApp(null) }) {
            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = palette.error)
        } }
        if (layer == FloatingLayer.COMMON) {
            HorizontalDivider()
            when (common) {
                FloatingTasksState.Loading -> Text("读取常用任务", Modifier.padding(UiSpace.medium), style = MaterialTheme.typography.bodySmall)
                is FloatingTasksState.Failure -> TextButton({ openApp(null) }) { Text("常用任务暂不可用", color = palette.error) }
                is FloatingTasksState.Ready -> {
                    val candidates = common.tasks.filterNot { current?.request?.ref == SubmissionRef(SubmissionKind.TASK, it.address.taskId) && current.request.context.targetId == it.address.targetId }
                    if (candidates.isEmpty()) Text(if (common.hasUnknown) "正在核对运行条件" else if (common.hasFavorites) "当前暂无可执行的常用任务" else "暂无常用项",
                        Modifier.padding(UiSpace.medium), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(candidates, key = { JsonArray(listOf(JsonPrimitive(it.address.targetId), JsonPrimitive(it.address.taskId))).toString() }) { task ->
                            val queued = state.waiting.any { it.request.context.targetId == task.address.targetId && it.request.ref == SubmissionRef(SubmissionKind.TASK, task.address.taskId) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ openApp(task.address) }, Modifier.weight(1f)) { Text(task.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (queued) Text("队列中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                RunActionButton(task.name, false, queued, status != RuntimeStatus.Loading && status !is RuntimeStatus.Failure,
                                    { enqueue(task.address) }, {}, {})
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (layer == FloatingLayer.QUICK) TextButton({ changeLayer(FloatingLayer.COMMON) }, Modifier.weight(1f)) { Text("常用"); Icon(AssistIcons.Chevron, null) }
            else Spacer(Modifier.weight(1f))
            FloatingIcon(AssistIcons.Home, "返回助手") { openApp(null) }
            if (layer == FloatingLayer.QUICK) FloatingIcon(AssistIcons.Close, "收起悬浮面板") { changeLayer(FloatingLayer.BALL) }
        }
    }
}
@Composable
private fun FloatingIcon(icon: ImageVector, label: String, enabled: Boolean = true, click: () -> Unit) {
    IconButton(click, enabled = enabled, modifier = Modifier.size(UiSpace.hit)) {
        Icon(icon, label, Modifier.size(UiSpace.icon), tint = LocalAssistPalette.current.teal.copy(alpha = if (enabled) .82f else .35f))
    }
}
