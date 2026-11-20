package io.github.yysassist.floating

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.AssistApplication
import io.github.yysassist.VersionCatalogState
import io.github.yysassist.cloud.DraftWait
import io.github.yysassist.cloud.ProbeCondition
import io.github.yysassist.cloud.ProbeResult
import io.github.yysassist.protocol.DevState
import io.github.yysassist.storage.ActiveTaskContent
import io.github.yysassist.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

internal sealed interface SampleUi {
    data object Taking : SampleUi
    data class Done(val id: String, val detail: String) : SampleUi
    data class Failed(val message: String) : SampleUi
}

/** The floating ball's developer tools: take a sample, probe the draft's page-waits on the screen as it is now. */
internal class FloatingDev(private val app: AssistApplication, private val scope: CoroutineScope) {
    private val workbench get() = app.devWorkbench
    /** The game the active task set is for; the workbench drafts are kept per game. */
    val target: String? get() = ((app.taskUpdates.catalog.value as? VersionCatalogState.Ready)?.catalog?.activeContent as? ActiveTaskContent.Available)
        ?.bundle?.targets?.firstOrNull()?.id

    var sample by mutableStateOf<SampleUi?>(null); private set
    /** The name field has the keyboard: only then may the floating window take focus from the game. */
    var typing by mutableStateOf(false)
    var sampleName by mutableStateOf(""); private set
    var suggestions by mutableStateOf(emptyList<String>()); private set
    var takenCount by mutableIntStateOf(0); private set

    var waits by mutableStateOf(emptyList<DraftWait>()); private set
    var selected by mutableIntStateOf(0); private set
    var probing by mutableStateOf(false); private set
    var probe by mutableStateOf<ProbeResult?>(null); private set
    var probeError by mutableStateOf<String?>(null); private set
    var probeCount by mutableIntStateOf(0); private set

    private fun message(error: Throwable) = error.message?.takeIf { it.isNotBlank() } ?: "没有连上服务器"

    fun takeSample() {
        val game = target ?: run { sample = SampleUi.Failed("手机还没有任务包"); return }
        if (sample == SampleUi.Taking) return
        // The game keeps focus (and the keyboard is gone) while its screen is captured.
        typing = false
        sample = SampleUi.Taking
        scope.launch {
            sample = try {
                if (suggestions.isEmpty()) suggestions = runCatching { workbench.draft(game)?.let { workbench.stateNames(it, game) } }.getOrNull().orEmpty().take(6)
                val report = workbench.sample(game, sampleName.ifBlank { "样本" })
                val id = report.sample
                if (report.state == DevState.FINISHED && id != null) { takenCount++; SampleUi.Done(id, report.detail) } else SampleUi.Failed(report.detail.ifBlank { "采样失败" })
            } catch (error: Exception) { SampleUi.Failed(message(error)) }
        }
    }
    fun name(value: String) { sampleName = value.take(64) }
    /** Saves the name typed or picked for the sample just taken. */
    fun saveName() {
        typing = false
        val done = sample as? SampleUi.Done ?: return
        val name = sampleName.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch { runCatching { workbench.rename(done.id, name) } }
    }
    /** The next sample starts unnamed, so it is not filed under the last one's name. */
    fun again() { saveName(); sampleName = ""; takeSample() }

    fun openProbe() {
        val game = target ?: run { probeError = "手机还没有任务包"; return }
        probe = null; probeError = null
        scope.launch {
            try {
                val draft = workbench.draft(game) ?: run { probeError = "工作台还没有这个游戏的草稿"; return@launch }
                waits = workbench.waits(draft)
                selected = 0
                if (waits.isEmpty()) probeError = "草稿里还没有等页面" else runProbe()
            } catch (error: Exception) { probeError = message(error) }
        }
    }
    fun pick(index: Int) { if (index != selected && !probing) { selected = index; probe = null; runProbe() } }
    /** Each probe is numbered; a result for an earlier one (another wait, or a closed panel) is dropped. */
    private var probeTicket = 0
    fun runProbe() {
        val game = target ?: return
        val wait = waits.getOrNull(selected) ?: return
        if (probing) return
        probing = true; probeError = null
        val ticket = ++probeTicket
        scope.launch {
            try { workbench.probe(game, wait.states).let { if (ticket == probeTicket) { probe = it; probeCount++ } } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == probeTicket) probeError = message(error) }
            finally { if (ticket == probeTicket) probing = false }
        }
    }
    fun closeProbe() { probeTicket++; probing = false; probe = null; probeError = null }
}

private val Sheet = Color(0xF5181818)
private val Chip = Color(0xFF333333)
private val ChipOn = Color(0xFFEFE6FF)
private val Ink = Color(0xFF171717)
private val Good = Color(0xFFA4D5B4)
private val Bad = Color(0xFFFFB4B8)
private val Muted = Color(0xFFC9C9C9)

@Composable
private fun DevChip(label: String, on: Boolean, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(10.dp), color = if (on) ChipOn else Chip, contentColor = if (on) Ink else Color.White,
        modifier = Modifier.heightIn(min = 36.dp).semantics { selected = on }) {
        Box(Modifier.padding(horizontal = 12.dp), Alignment.Center) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
    }
}
@Composable
private fun DevButton(label: String, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    Button(click, modifier.heightIn(min = 40.dp), enabled = enabled, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) ChipOn else Chip, contentColor = if (primary) Ink else Color.White),
        contentPadding = PaddingValues(horizontal = 14.dp)) { Text(label, maxLines = 1) }
}

/** After 采样: what was taken, and a name for it. */
@Composable
internal fun DevSampleSheet(dev: FloatingDev, close: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Sheet).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (title, note) = when (val s = dev.sample) {
                SampleUi.Taking, null -> "正在采样…" to ""
                is SampleUi.Done -> "已采样 · ${s.detail.removePrefix("已采样，")}" to "已传到工作台 · 第 ${dev.takenCount} 张"
                is SampleUi.Failed -> s.message to ""
            }
            Text(title, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, color = if (dev.sample is SampleUi.Failed) Bad else Color.White)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("名称", style = MaterialTheme.typography.labelMedium, color = Muted)
            // The window takes focus only once the field is tapped, never while the game is being captured.
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(dev.typing) { if (dev.typing) { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } } }
            Box(Modifier.weight(1f)) {
                OutlinedTextField(dev.sampleName, dev::name, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, placeholder = { Text("样本") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { dev.saveName() }))
                if (!dev.typing) Box(Modifier.matchParentSize().clickable(onClickLabel = "输入名称") { dev.typing = true })
            }
        }
        if (dev.suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("按草稿推荐", style = MaterialTheme.typography.labelSmall, color = Muted)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dev.suggestions.take(3).forEach { name -> DevChip(name, dev.sampleName == name) { dev.name(name); dev.saveName() } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            DevButton("再采一张", false, enabled = dev.sample != SampleUi.Taking) { dev.again() }
            DevButton("好了", true) { dev.saveName(); close() }
        }
    }
}

private fun describe(c: ProbeCondition): String = when (c.kind) {
    "image" -> "图 ${c.expect} ${score(c.score)}" + if (c.pass == true) "" else " < ${score(c.minimum)}"
    "absentImage" -> "无图 ${c.expect} ${score(c.score)}" + if (c.pass == true) "" else " ≥ ${score(c.minimum)}"
    "text" -> when (c.pass) { null -> "没读「${c.expect}」"; true -> "「${c.expect}」✓"; false -> "缺「${c.expect}」（读到${c.count ?: 0}处）" }
    else -> when (c.pass) { null -> "没读「${c.expect}」"; true -> "无「${c.expect}」✓"; false -> "有「${c.expect}」" }
}
private fun score(value: Double?) = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

/** 探测: which of a page-wait's states hold on the screen now, and what each one missed. */
@Composable
internal fun DevProbePanel(dev: FloatingDev, maxHeight: androidx.compose.ui.unit.Dp, close: () -> Unit, sample: () -> Unit) {
    Column(Modifier.heightIn(max = maxHeight).clip(RoundedCornerShape(16.dp)).background(Sheet).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("探测 · ${dev.target.orEmpty()}", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = Color.White)
            DevButton("收起", false) { dev.closeProbe(); close() }
        }
        if (dev.waits.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = "判定哪个等页面" },
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dev.waits.forEachIndexed { i, wait -> DevChip(wait.label, i == dev.selected) { dev.pick(i) } }
        }
        val result = dev.probe
        val verdict = when {
            dev.probing -> "判定中…"
            dev.probeError != null -> dev.probeError!!
            result == null -> "还没判定"
            result.matched != null -> "只成立 ${result.matched} ✓ · 第 ${dev.probeCount} 次"
            else -> {
                val held = result.conditions.groupBy { it.state }.filter { (_, cs) -> cs.all { it.pass == true } }.keys
                (if (held.size > 1) "冲突：${held.joinToString(" 和 ")} 同时成立" else "一个状态都不成立") + " · 第 ${dev.probeCount} 次"
            }
        }
        val good = result?.matched != null && !dev.probing && dev.probeError == null
        Text(verdict, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (good) Good else if (dev.probing || result == null && dev.probeError == null) Chip else Bad)
            .padding(horizontal = 10.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = if (good || (!dev.probing && (result != null || dev.probeError != null))) Ink else Color.White, style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            result?.conditions?.groupBy { it.state }?.forEach { (state, conditions) ->
                val holds = conditions.all { it.pass == true }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2D2D2D)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row { Text(state, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Text(if (holds) "成立" else "不成立", style = MaterialTheme.typography.labelMedium, color = if (holds) Good else Bad) }
                    Text(conditions.joinToString(" · ", transform = ::describe), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DevButton("再测一次", false, enabled = !dev.probing && dev.waits.isNotEmpty()) { dev.runProbe() }
            DevButton("存为样本", true, enabled = !dev.probing) { sample() }
        }
    }
}

/**
 * Where the probed conditions looked, drawn over the game: green where a condition held, red where it did not. Regions
 * are on the reference frame and scaled to the screen.
 */
@Composable
internal fun ProbeMarkers(result: ProbeResult) {
    val referenceWidth = result.referenceWidth; val referenceHeight = result.referenceHeight
    val labels = result.conditions.filter { it.within.size == 4 && it.pass != null }
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / referenceWidth; val sy = size.height / referenceHeight
        labels.forEach { c ->
            val (x, y, w, h) = c.within
            drawRect(if (c.pass == true) Good else Bad, Offset(x * sx, y * sy), Size(w * sx, h * sy), style = Stroke(2.dp.toPx()))
        }
    }
    // Labels sit just above each box, as in the design.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sx = maxWidth / referenceWidth; val sy = maxHeight / referenceHeight
        labels.distinctBy { it.within }.forEach { c ->
            val (x, y, _, _) = c.within
            Text(describe(c), Modifier.offset(sx * x, (sy * y - 18.dp).coerceAtLeast(0.dp)).clip(RoundedCornerShape(4.dp))
                .background(if (c.pass == true) Good else Bad).padding(horizontal = 5.dp), color = Ink, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
