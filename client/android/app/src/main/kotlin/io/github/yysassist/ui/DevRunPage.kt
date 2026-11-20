package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.AssistApplication
import io.github.yysassist.VersionCatalogState
import io.github.yysassist.cloud.DraftTask
import io.github.yysassist.protocol.DevReport
import io.github.yysassist.protocol.DevState
import io.github.yysassist.storage.ActiveTaskContent
import kotlinx.coroutines.launch

/** 试跑草稿: runs a task of the workbench draft (or one of its steps) once on this phone, and shows what each action did. */
@Composable
internal fun DevRunPage(application: AssistApplication) {
    val p = LocalAssistPalette.current
    val scope = rememberCoroutineScope()
    val workbench = application.devWorkbench
    val target = ((application.taskUpdates.catalog.collectAsState().value as? VersionCatalogState.Ready)?.catalog?.activeContent as? ActiveTaskContent.Available)
        ?.bundle?.targets?.firstOrNull()?.id
    var tasks by remember { mutableStateOf<List<DraftTask>?>(null) }
    var revision by remember { mutableStateOf<Long?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<DevReport?>(null) }
    suspend fun load() {
        problem = null
        try {
            val game = target ?: run { problem = "手机还没有任务包"; return }
            val draft = workbench.draftView(game) ?: run { problem = "工作台还没有这个游戏的草稿"; return }
            revision = draft.revision
            tasks = workbench.tasks(draft.body)
            if (task == null || tasks?.none { it.id == task } == true) { task = tasks?.firstOrNull()?.id; step = null }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { problem = io.github.yysassist.shared.failureText(error) }
    }
    LaunchedEffect(target) { load() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = UiSpace.medium, vertical = UiSpace.gap),
            verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
            Text(listOfNotNull(target, revision?.let { "草稿修订 $it" }).joinToString(" · ").ifEmpty { "读取草稿…" },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            problem?.let { Text(it, color = p.error, style = MaterialTheme.typography.bodyMedium) }
            val list = tasks.orEmpty()
            if (list.isNotEmpty()) {
                Text("任务", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(list.map { it.id to it.name.ifEmpty { it.id } }, task) { task = it; step = null }
                val steps = list.firstOrNull { it.id == task }?.steps.orEmpty()
                if (steps.size > 1) {
                    Text("跑哪些", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(listOf<Pair<String?, String>>(null to "整个任务") + steps.map { it.first to "只跑「${it.second.ifEmpty { it.first }}」" }, step) { step = it }
                }
            }
            report?.let { r ->
                Text("本机记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(UiSpace.hairline, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    val done = r.state == DevState.FINISHED && r.detail == "完成"
                    Text(r.detail.ifEmpty { r.state.name }, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (done) p.success else p.error, style = MaterialTheme.typography.titleSmall)
                    r.traces.forEach { t ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                            val ok = t.result in setOf("PRODUCED", "VERIFIED", "COMPLETED")
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) p.success else p.error))
                            Column(Modifier.weight(1f)) {
                                Text("${t.step} · ${t.action}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(t.capability.removePrefix("screen."), t.reason, "${t.elapsedMs}ms").joinToString(" · "), maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = UiSpace.medium, vertical = UiSpace.gap), horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
            OutlinedButton({ scope.launch { load() } }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !running) { Text("重新读取") }
            Button({
                val game = target ?: return@Button; val id = task ?: return@Button
                running = true; report = null
                scope.launch {
                    try { report = workbench.trial(game, id, step) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { report = DevReport(DevState.FAILED, io.github.yysassist.shared.failureText(error)) }
                    finally { running = false }
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !running && task != null) { Text(if (running) "试跑中…" else "试跑") }
        }
    }
}

@Composable
private fun <T> Card(options: List<Pair<T, String>>, selected: T, pick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(UiSpace.hairline, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).semantics { selectableGroup() }) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { pick(value) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == selected, null)
                Spacer(Modifier.width(UiSpace.gap))
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
