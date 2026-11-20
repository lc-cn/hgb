package io.github.yysassist.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.console.Connection
import io.github.yysassist.shared.theme.DesignTokens
import io.github.yysassist.shared.theme.LocalTokens

/** The window's width class (C41): the same page is laid out the same way in the same tier on every end. */
enum class Tier { NARROW, MEDIUM, WIDE }
fun tierOf(width: Dp): Tier = when {
    width < DesignTokens.Breakpoint.medium -> Tier.NARROW
    width < DesignTokens.Breakpoint.wide -> Tier.MEDIUM
    else -> Tier.WIDE
}
/** The tier the current page is drawn in. */
val LocalTier = staticCompositionLocalOf { Tier.NARROW }

/** The five places of the console, the same on the web, Windows and Android (C40). */
enum class Section(val label: String, val icon: ImageVector) {
    DEVICES("设备", HgbIcons.Devices),
    ACCOUNTS("游戏账号", HgbIcons.Accounts),
    LIBRARY("任务库", HgbIcons.Library),
    NOTICES("通知", HgbIcons.Notices),
    ME("我的", HgbIcons.Me),
}

/** A place only a device that runs games has (开发), listed under “本机” in the medium and wide tiers. */
class LocalEntry(val id: String, val label: String, val icon: ImageVector)

private val LIVE = mapOf(Connection.LIVE to "实时连接中", Connection.CONNECTING to "正在连接…", Connection.LOST to "连接断开，正在重连…")
private val LIVE_SHORT = mapOf(Connection.LIVE to "已连接", Connection.CONNECTING to "连接中", Connection.LOST to "已断开")

/**
 * The frame every page sits in. From 1024 wide a full sidebar, from 600 a rail of icons with short labels, below that
 * a top bar with the navigation at the bottom. [runStrip] (Android and Windows only) sits on top of the content and
 * says what this device is running; [local] are this device's own places, [localSelected] the one shown.
 */
@Composable
fun ConsoleShell(
    section: Section?, select: (Section) -> Unit, connection: Connection?,
    local: List<LocalEntry> = emptyList(), localSelected: String? = null, selectLocal: (String) -> Unit = {},
    runStrip: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalTokens.current
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.bg)) {
        val tier = tierOf(maxWidth)
        CompositionLocalProvider(LocalTier provides tier) {
            val body: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    runStrip?.invoke()
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
            when (tier) {
                Tier.NARROW -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(tokens.surface).padding(horizontal = DesignTokens.Space.s4),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HgbMark(28.dp)
                        // Narrow: the bar names the place, so its page does not repeat the title (see PageHeader).
                        Text(section?.label ?: local.firstOrNull { it.id == localSelected }?.label ?: "护肝宝", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        connection?.let { LiveDot(it); Text(LIVE_SHORT.getValue(it), style = MaterialTheme.typography.labelSmall, color = tokens.muted) }
                    }
                    HorizontalDivider(color = tokens.line)
                    body(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = tokens.line)
                    Row(Modifier.fillMaxWidth().background(tokens.surface).padding(horizontal = DesignTokens.Space.s1).selectableGroup()) {
                        Section.entries.forEach { RailItem(it.label, it.icon, it == section, Modifier.weight(1f), 52.dp) { select(it) } }
                    }
                }
                Tier.MEDIUM -> Row(Modifier.fillMaxSize()) {
                    // Scrolls when the window is too short for every item, as a phone held sideways is.
                    Column(Modifier.width(80.dp).fillMaxHeight().background(tokens.surface).verticalScroll(rememberScrollState())
                        .padding(vertical = DesignTokens.Space.s4).selectableGroup(),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DesignTokens.Space.s1)) {
                        HgbMark(32.dp, Modifier.padding(bottom = DesignTokens.Space.s3))
                        Section.entries.forEach { RailItem(it.label, it.icon, it == section, Modifier.width(64.dp), 48.dp) { select(it) } }
                        if (local.isNotEmpty()) {
                            Text("本机", Modifier.padding(top = DesignTokens.Space.s3), style = MaterialTheme.typography.labelSmall, color = tokens.faint)
                            local.forEach { RailItem(it.label, it.icon, it.id == localSelected, Modifier.width(64.dp), 48.dp) { selectLocal(it.id) } }
                        }
                        connection?.let { Box(Modifier.padding(DesignTokens.Space.s3)) { LiveDot(it) } }
                    }
                    VerticalDivider(color = tokens.line)
                    body(Modifier.weight(1f).fillMaxHeight())
                }
                Tier.WIDE -> Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(240.dp).fillMaxHeight().background(tokens.surface)
                        .padding(vertical = DesignTokens.Space.s6, horizontal = DesignTokens.Space.s4).selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.Space.s1)) {
                        Row(Modifier.padding(start = DesignTokens.Space.s2, top = DesignTokens.Space.s1, bottom = DesignTokens.Space.s5),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HgbMark(32.dp); Text("护肝宝", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        }
                        Section.entries.forEach { SideItem(it.label, it.icon, it == section) { select(it) } }
                        if (local.isNotEmpty()) {
                            Text("本机", Modifier.padding(start = DesignTokens.Space.s3, top = DesignTokens.Space.s4, bottom = DesignTokens.Space.s1),
                                style = MaterialTheme.typography.labelSmall, color = tokens.faint)
                            local.forEach { SideItem(it.label, it.icon, it.id == localSelected) { selectLocal(it.id) } }
                        }
                        Spacer(Modifier.weight(1f))
                        connection?.let {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DesignTokens.Radius.lg)).background(tokens.bg).padding(DesignTokens.Space.s3),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Space.s2)) {
                                LiveDot(it); Text(LIVE.getValue(it), style = MaterialTheme.typography.bodySmall, color = tokens.muted)
                            }
                        }
                    }
                    VerticalDivider(color = tokens.line)
                    body(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun LiveDot(connection: Connection) {
    val tokens = LocalTokens.current
    Box(Modifier.size(8.dp).clip(CircleShape).background(when (connection) {
        Connection.LIVE -> tokens.live; Connection.LOST -> tokens.lost; Connection.CONNECTING -> tokens.switchOff }))
}

/** A rail or bottom-bar item: the icon in a pill that fills when current, a short label under it. */
@Composable
private fun RailItem(label: String, icon: ImageVector, current: Boolean, modifier: Modifier, pill: Dp, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Column(modifier.heightIn(min = 56.dp).selectable(current, role = Role.Tab, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(pill, 28.dp).clip(RoundedCornerShape(14.dp)).background(if (current) tokens.navCurrentBg else tokens.surface),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (current) tokens.navCurrent else tokens.nav)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (current) tokens.navCurrent else tokens.nav)
    }
}

/** A sidebar item: icon and label in one row, the whole row filled when current. */
@Composable
private fun SideItem(label: String, icon: ImageVector, current: Boolean, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.Control.heightLg).clip(RoundedCornerShape(DesignTokens.Radius.md))
        .background(if (current) tokens.navCurrentBg else tokens.surface).selectable(current, role = Role.Tab, onClick = onClick)
        .padding(horizontal = DesignTokens.Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (current) tokens.navCurrent else tokens.nav)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) tokens.navCurrent else tokens.nav)
    }
}
