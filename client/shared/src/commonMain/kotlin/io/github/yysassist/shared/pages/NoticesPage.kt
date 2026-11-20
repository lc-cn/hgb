package io.github.yysassist.shared.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.core.cloud.CloudRequestException
import io.github.yysassist.protocol.DeliveryState
import io.github.yysassist.protocol.DeliveryView
import io.github.yysassist.protocol.EventRuleView
import io.github.yysassist.protocol.NoticeView
import io.github.yysassist.shared.LocalConsole
import io.github.yysassist.shared.LocalParts
import io.github.yysassist.shared.Route
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens
import io.github.yysassist.shared.ui.*
import java.io.IOException

private val TONES = mapOf("execution-failed" to Tone.BAD, "unknown-result" to Tone.WARN, "account-not-restored" to Tone.WARN, "plan-missed" to Tone.WARN,
    "queue-wait" to Tone.WARN, "no-progress" to Tone.WARN, "condition-lost" to Tone.BAD, "plan-completed" to Tone.INFO, "plan-started" to Tone.INFO,
    "task-started" to Tone.INFO, "task-succeeded" to Tone.OK)
private const val PAGE_SIZE = 50

/** One delivery in words: “钉钉 已送达”, “重试中 2/4”, “4 次都失败：…” (as the web console). */
fun deliveryText(delivery: DeliveryView): String = when (delivery.state) {
    DeliveryState.SENT -> "${delivery.channelName} 已送达"
    DeliveryState.PENDING -> if (delivery.attempts > 0) "${delivery.channelName} 发送失败，${Format.shortTime(delivery.nextAt)} 重试（第 ${delivery.attempts + 1}/4 次）"
        else "${delivery.channelName} 发送中"
    DeliveryState.FAILED -> "${delivery.channelName} ${if (delivery.attempts > 1) "${delivery.attempts} 次都失败" else "发送失败"}${if (delivery.error.isNotEmpty()) "：${delivery.error}" else ""}"
}

/**
 * 通知 on the App and Windows: what the rules noticed, newest first. Rules and channels are set up on the web only
 * (C38); [local] adds this device's own switch for popping notices up.
 */
@Composable
fun NoticesPage(local: LocalParts) = Page {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    var rule by remember { mutableStateOf<String?>(null) }
    var rules by remember { mutableStateOf<List<EventRuleView>>(emptyList()) }
    var notices by remember { mutableStateOf<List<NoticeView>?>(null) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A new notice or a change of rules reads the first page again.
    LaunchedEffect(rule, console.data.noticeVersion) {
        try {
            rules = console.api.rules()
            val first = console.api.notices(rule = rule)
            notices = first; more = first.size == PAGE_SIZE; error = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (problem: Exception) { error = io.github.yysassist.shared.failureText(problem) }
    }
    PageHeader("通知", aside = {
        Select(rule, listOf<Pair<String?, String>>(null to "全部规则") + rules.map { it.ruleId to it.name }, { rule = it }, "按规则筛选")
    })
    local.notices?.invoke()
    val list = notices
    when {
        error != null -> Card { Empty(error!!) }
        list == null -> Card { Empty("正在读取…") }
        else -> Card {
            if (list.isEmpty()) Empty(if (rule != null) "这条规则还没有记下事件" else "还没有事件。规则在网页控制台的“通知”里打开。")
            list.forEachIndexed { i, item -> NoticeRow(item, i == list.lastIndex && !more) }
            if (more) Box(Modifier.fillMaxWidth().padding(Space.s3), contentAlignment = Alignment.Center) {
                HgbButton("加载更多", { console.act { val next = console.api.notices(list.last(), rule); notices = list + next; more = next.size == PAGE_SIZE } })
            }
        }
    }
}

/** Two lines: the rule and time, then the title, its detail and where it was sent. */
@Composable
private fun NoticeRow(item: NoticeView, last: Boolean) {
    val console = LocalConsole.current
    val tokens = LocalTokens.current
    ListRow(item.jobId?.let { job -> { console.nav.go(Route.Job(job)) } }, last) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Pill(item.ruleName, TONES[item.ruleId] ?: Tone.NEUTRAL)
                Text(Format.shortTime(item.at), color = tokens.faint, style = MaterialTheme.typography.labelSmall)
            }
            Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (item.detail.isNotEmpty()) Muted(item.detail)
            // Where it was sent, on one line; a failed one keeps its retry.
            if (item.deliveries.isNotEmpty()) Text(item.deliveries.joinToString(" · ") { deliveryText(it) }, style = MaterialTheme.typography.bodySmall,
                color = when { item.deliveries.any { it.state == DeliveryState.FAILED } -> tokens.danger; item.deliveries.any { it.state == DeliveryState.PENDING } -> tokens.warn; else -> tokens.muted })
            item.deliveries.filter { it.state == DeliveryState.FAILED && it.channelId != null }.forEach { delivery ->
                LinkButton("重发到${delivery.channelName}", { console.act { console.api.retryDelivery(delivery.id) } })
            }
        }
        if (item.jobId != null) Icon(HgbIcons.Chevron, "运行记录", Modifier.size(16.dp), tint = tokens.muted)
    }
}
