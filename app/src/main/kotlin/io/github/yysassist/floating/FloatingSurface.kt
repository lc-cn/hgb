package io.github.yysassist.floating

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yysassist.*
import io.github.yysassist.core.execution.*
import io.github.yysassist.storage.TaskAddress
import io.github.yysassist.ui.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlin.math.cos
import kotlin.math.sin

internal enum class FloatingLayer { DOCKED, BALL, QUICK, COMMON }
internal object FloatingUi {
    val ball = 44.dp; val dockWidth = 18.dp; val dockHeight = 56.dp
    val fanWidth = 100.dp; val fanHeight = 168.dp; val fanRadius = 60.dp; val fanButton = 44.dp
    val quickWidth = 366.dp; val wideCommonWidth = 560.dp
    val portraitHeight = 480.dp; val landscapeHeight = 340.dp
    val portraitTop = 94.dp; val landscapeTop = 36.dp
    fun width(layer: FloatingLayer, wide: Boolean): Dp = when (layer) {
        FloatingLayer.DOCKED -> dockWidth
        FloatingLayer.BALL -> ball
        FloatingLayer.QUICK -> fanWidth
        FloatingLayer.COMMON -> if (wide) wideCommonWidth else quickWidth
    }
}
private val BallFill = Color(0xE6161616)

internal fun needsAttention(state: ExecutorSnapshot, status: RuntimeStatus): Boolean =
    status is RuntimeStatus.Failure || status is RuntimeStatus.RecoveryBlocked || state.unresolved.isNotEmpty()

@Composable
private fun stateColor(state: ExecutorSnapshot, status: RuntimeStatus): Color? {
    val palette = LocalAssistPalette.current
    return when {
        needsAttention(state, status) -> palette.error
        state.manualHold -> palette.warning
        state.current != null || state.waiting.isNotEmpty() -> palette.blue
        else -> null
    }
}

@Composable
private fun StatusBall(state: ExecutorSnapshot, status: RuntimeStatus, modifier: Modifier) {
    val ring = stateColor(state, status)
    val progress = state.current?.let { (it.stepIndex + 1f) / it.task.stepIds.size.coerceAtLeast(1) }
    Box(modifier.size(FloatingUi.ball), Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clip(CircleShape).background(BallFill)) {
            val width = 3.dp.toPx(); val inset = width / 2
            val arc = Size(size.width - width, size.height - width)
            drawArc(Color.White.copy(alpha = .18f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
            if (ring != null) drawArc(ring, -90f, 360f * (if (state.manualHold || needsAttention(state, status)) 1f else progress ?: 1f), false,
                Offset(inset, inset), arc, style = Stroke(width, cap = StrokeCap.Round))
        }
        if (needsAttention(state, status)) Text("!", color = LocalAssistPalette.current.error, style = MaterialTheme.typography.titleMedium)
        else if (state.manualHold) Icon(AssistIcons.Pause, null, Modifier.size(20.dp), tint = Color.White)
        else Image(painterResource(io.github.yysassist.R.drawable.ic_logo_mark), null, Modifier.size(30.dp))
        state.waiting.size.takeIf { it > 0 }?.let { count ->
            Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(LocalAssistPalette.current.gold), Alignment.Center) {
                Text(count.toString(), color = Color(0xFF171717), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun FloatingSurface(layer: FloatingLayer, state: ExecutorSnapshot, status: RuntimeStatus,
    common: FloatingTasksState, wide: Boolean, maxHeight: Dp, changeLayer: (FloatingLayer) -> Unit,
    enqueue: (TaskAddress) -> Unit, pause: (Long?) -> Unit, stop: (Long) -> Unit, resume: (Long?, ResumeMode) -> Unit,
    openApp: (TaskAddress?) -> Unit, ballMotion: ((MotionEvent) -> Boolean)? = null, maxWidth: Dp = FloatingUi.wideCommonWidth, feedback: String? = null,
    rightSide: Boolean = true, touched: () -> Unit = {}) {
    val palette = LocalAssistPalette.current
    val phase = when (status) { RuntimeStatus.Loading -> "读取中"; is RuntimeStatus.Waiting -> "等待中"; is RuntimeStatus.Failure -> "存储异常"; is RuntimeStatus.RecoveryBlocked -> "等待核对"; RuntimeStatus.Ready -> phaseLabel(state.phase) }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun gesture(tap: () -> Unit) = if (ballMotion == null) Modifier.clickable(onClick = tap) else Modifier.pointerInteropFilter(onTouchEvent = ballMotion)
    if (layer == FloatingLayer.DOCKED) {
        val wake = { changeLayer(FloatingLayer.BALL) }
        Box(Modifier.size(FloatingUi.dockWidth, FloatingUi.dockHeight).then(gesture(wake))
            .semantics { contentDescription = "唤醒悬浮球"; stateDescription = phase; role = Role.Button; onClick { wake(); true } },
            if (rightSide) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(5.dp, 40.dp).clip(RoundedCornerShape(3.dp)).background(stateColor(state, status) ?: Color.White.copy(alpha = .7f)))
        }
        return
    }
    if (layer == FloatingLayer.BALL) {
        val tap = { changeLayer(FloatingLayer.QUICK) }
        StatusBall(state, status, gesture(tap).semantics { contentDescription = "展开快捷控制"; stateDescription = phase; role = Role.Button; onClick { tap(); true } })
        return
    }
    if (layer == FloatingLayer.QUICK) {
        val current = state.current
        val hold = state.manualHold
        val canPause = status !is RuntimeStatus.Failure && status != RuntimeStatus.Loading && (current != null || state.waiting.isNotEmpty())
        var armedStop by remember { mutableStateOf(false) }
        LaunchedEffect(armedStop) { if (armedStop) { delay(2_000); armedStop = false } }
        val center = FloatingUi.ball / 2
        val ballX = if (rightSide) FloatingUi.fanWidth - FloatingUi.ball else 0.dp
        val ballY = (FloatingUi.fanHeight - FloatingUi.ball) / 2
        Box(Modifier.size(FloatingUi.fanWidth, FloatingUi.fanHeight)) {
            @Composable fun fan(index: Int, icon: ImageVector, label: String, enabled: Boolean, tint: Color = Color.White, action: () -> Unit) {
                val radians = Math.toRadians(-90.0 + index * 60.0)
                val dx = FloatingUi.fanRadius * cos(radians).toFloat() * (if (rightSide) -1f else 1f)
                val dy = FloatingUi.fanRadius * sin(radians).toFloat()
                Box(Modifier.offset(ballX + center + dx - FloatingUi.fanButton / 2, ballY + center + dy - FloatingUi.fanButton / 2)
                    .size(FloatingUi.fanButton).clip(CircleShape).background(BallFill)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { touched(); action() }
                    .semantics { contentDescription = label }, Alignment.Center) {
                    Icon(icon, null, Modifier.size(UiSpace.icon), tint = tint.copy(alpha = if (enabled) 1f else .35f))
                }
            }
            if (hold) fan(0, AssistIcons.PlayFilled, "继续运行", canPause && state.phase == ExecutorPhase.PAUSED) { resume(current?.runId, ResumeMode.CONTINUE) }
            else fan(0, AssistIcons.Pause, "暂停运行", canPause) { pause(current?.runId) }
            // A stray touch over the game must not end a run, so stopping takes a second tap.
            fan(1, AssistIcons.Stop, if (armedStop) "再次点击确认停止" else "停止当前任务", current != null, if (armedStop) palette.error else Color.White) {
                if (armedStop) { armedStop = false; current?.runId?.let(stop) } else armedStop = true
            }
            fan(2, AssistIcons.Star, "常用", true) { changeLayer(FloatingLayer.COMMON) }
            fan(3, AssistIcons.Home, "返回助手", true) { openApp(null) }
            StatusBall(state, status, Modifier.offset(ballX, ballY).clickable(role = Role.Button) { changeLayer(FloatingLayer.BALL) }
                .semantics { contentDescription = "收起悬浮面板"; stateDescription = phase })
        }
        return
    }
    val width = FloatingUi.width(layer, wide)
    var recoveryFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.current?.runId, state.phase, status) {
        if (state.phase != ExecutorPhase.PAUSED || state.current?.runId != recoveryFor || status is RuntimeStatus.Failure || status == RuntimeStatus.Loading) recoveryFor = null
    }
    Column(Modifier.width(minOf(width, maxWidth)).heightIn(max = maxHeight).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FloatingIcon(AssistIcons.Back, "返回快捷控制") { changeLayer(FloatingLayer.QUICK) }
            Text("常用", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            FloatingIcon(AssistIcons.Home, "返回助手") { openApp(null) }
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
                    else LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 1), Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(UiSpace.gap), verticalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                        items(candidates, key = { JsonArray(listOf(JsonPrimitive(it.address.targetId), JsonPrimitive(it.address.taskId))).toString() }) { task ->
                            val queued = state.waiting.any { it.request.context.targetId == task.address.targetId && it.request.ref == SubmissionRef(SubmissionKind.TASK, task.address.taskId) }
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(UiSpace.medium)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { openApp(task.address) }.padding(horizontal = UiSpace.medium, vertical = UiSpace.gap)) {
                                    Text(task.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    if (queued) Text("队列中", style = MaterialTheme.typography.labelSmall, color = palette.gold)
                                }
                                if (!queued) RunActionButton(task.name, false, false, status != RuntimeStatus.Loading && status !is RuntimeStatus.Failure,
                                    { enqueue(task.address) }, {}, {})
                            }
                        }
                    }
                }
            }
        }
        feedback?.let { Text(it, Modifier.padding(horizontal = UiSpace.gap), color = palette.warning, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
private fun FloatingIcon(icon: ImageVector, label: String, enabled: Boolean = true, click: () -> Unit) {
    IconButton(click, enabled = enabled, modifier = Modifier.size(UiSpace.hit)) {
        Icon(icon, label, Modifier.size(UiSpace.icon), tint = LocalAssistPalette.current.teal.copy(alpha = if (enabled) .82f else .35f))
    }
}
