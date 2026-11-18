package io.github.yysassist.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.content.TaskBundle
import io.github.yysassist.core.plan.PlanSchedule
import io.github.yysassist.core.plan.planIssues
import io.github.yysassist.storage.PlanDefinition
import java.time.DayOfWeek

/** Nothing is preset: time, days and tasks stay empty until the user chooses them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanEditorPage(plan: PlanDefinition?, initialTask: String?, bundle: TaskBundle?,
    save: (name: String, targetId: String, schedule: PlanSchedule, taskIds: List<String>) -> Unit, delete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var name by rememberSaveable(plan?.id) { mutableStateOf(plan?.name.orEmpty()) }
    var minute by rememberSaveable(plan?.id) { mutableStateOf(plan?.schedule?.minuteOfDay) }
    var daily by rememberSaveable(plan?.id) { mutableStateOf(plan?.schedule?.daily) }
    var days by rememberSaveable(plan?.id) { mutableStateOf(ArrayList(plan?.schedule?.days.orEmpty().map { it.value })) }
    var tasks by rememberSaveable(plan?.id) { mutableStateOf(ArrayList(plan?.taskIds ?: listOfNotNull(initialTask))) }
    var issues by remember { mutableStateOf(emptyList<String>()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val definitions = bundle?.tasks.orEmpty()
    val targetId = plan?.context?.targetId ?: definitions.firstOrNull { it.id in tasks }?.targetId
    val chosenDays = if (daily == true) DayOfWeek.entries.toSet() else if (daily == false) days.map(DayOfWeek::of).toSet() else emptySet()
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = UiSpace.medium, end = UiSpace.medium, bottom = UiSpace.medium)) {
            SectionLabel("基本信息")
            AssistCard {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(UiSpace.medium), singleLine = true,
                    label = { Text("计划名称") }, shape = RoundedCornerShape(UiSpace.medium))
            }
            SectionLabel("运行时间")
            AssistCard {
                Column(Modifier.padding(UiSpace.medium), verticalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.gap)) {
                        GlassChoice("每天", daily == true, true) { daily = true }
                        GlassChoice("指定星期", daily == false, true) { daily = false }
                    }
                    if (daily == false) Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        DayOfWeek.entries.forEach { day ->
                            val active = day.value in days
                            Box(Modifier.size(UiSpace.hit).toggleable(active, role = Role.Checkbox, onValueChange = {
                                days = ArrayList(if (active) days - day.value else days + day.value) }), Alignment.Center) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(if (active) colors.primary else colors.surfaceContainer), Alignment.Center) {
                                    Text(WeekdayLabels.getValue(day), color = if (active) colors.onPrimary else colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer)
                        .clickable { picking = true }.padding(horizontal = UiSpace.medium), verticalAlignment = Alignment.CenterVertically) {
                        Text("时间", Modifier.weight(1f))
                        Text(minute?.let(::clockText) ?: "选择时间", color = if (minute == null) colors.onSurfaceVariant else colors.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            SectionLabel("任务顺序")
            AssistCard {
                if (tasks.isEmpty()) Text("从下方添加任务", Modifier.padding(14.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                tasks.forEachIndexed { index, id ->
                    if (index > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = UiSpace.tiny), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${definitions.firstOrNull { it.id == id }?.name ?: "当前版本没有此任务"}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(enabled = index > 0, onClick = { tasks = ArrayList(tasks).also { it.add(index - 1, it.removeAt(index)) } }) {
                            Icon(AssistIcons.Chevron, "上移", Modifier.rotate(-90f)) }
                        IconButton(enabled = index < tasks.lastIndex, onClick = { tasks = ArrayList(tasks).also { it.add(index + 1, it.removeAt(index)) } }) {
                            Icon(AssistIcons.Chevron, "下移", Modifier.rotate(90f)) }
                        IconButton(onClick = { tasks = ArrayList(tasks - id) }) { Icon(AssistIcons.Close, "移除") }
                    }
                }
            }
            val candidates = definitions.filter { it.id !in tasks && (targetId == null || it.targetId == targetId) }
            if (candidates.isNotEmpty()) {
                SectionLabel("添加任务")
                AssistCard {
                    candidates.forEachIndexed { index, task ->
                        if (index > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = UiSpace.hit).clickable { tasks = ArrayList(tasks + task.id) }.padding(horizontal = 14.dp, vertical = UiSpace.gap),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(task.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(AssistIcons.Plus, "添加：${task.name}", tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        // Kept outside the scrolling form so a failed save is always explained on screen.
        if (issues.isNotEmpty()) Text(issues.joinToString("；"), Modifier.fillMaxWidth().background(colors.surfaceContainerHigh)
            .padding(start = UiSpace.medium, end = UiSpace.medium, top = UiSpace.gap), color = colors.error, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh).padding(horizontal = UiSpace.medium, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (plan != null) OutlinedButton({ confirmDelete = true }, Modifier.weight(1f).heightIn(min = UiSpace.hit), shape = RoundedCornerShape(UiSpace.medium)) { Text("删除", color = colors.error) }
            Button({
                issues = planIssues(name, chosenDays, minute, tasks)
                val target = targetId
                if (issues.isEmpty() && target != null) save(name, target, PlanSchedule(chosenDays, minute!!), tasks)
            }, Modifier.weight(1.4f).heightIn(min = UiSpace.hit), shape = RoundedCornerShape(UiSpace.medium)) { Text("保存", fontWeight = FontWeight.SemiBold) }
        }
    }
    if (picking) {
        val state = rememberTimePickerState((minute ?: 0) / 60, (minute ?: 0) % 60, DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(onDismissRequest = { picking = false }, title = { Text("运行时间") }, text = { TimeInput(state) },
            confirmButton = { TextButton({ minute = state.hour * 60 + state.minute; picking = false }) { Text("确定") } },
            dismissButton = { TextButton({ picking = false }) { Text("取消") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除计划？") }, text = { Text("只删除这个计划，不影响任务和运行记录。") },
        confirmButton = { TextButton({ confirmDelete = false; delete() }) { Text("删除") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } })
}
