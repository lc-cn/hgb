package io.github.yysassist.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.theme.DesignTokens.Control
import io.github.yysassist.shared.theme.DesignTokens.Radius
import io.github.yysassist.shared.theme.DesignTokens.Space
import io.github.yysassist.shared.theme.LocalTokens

/** The building blocks every page is made of, as client/web/src/components/ui.tsx; their look comes only from the tokens. */

/** How a status reads: done, going, needs a look, failed, or nothing to say. */
enum class Tone { NEUTRAL, OK, INFO, WARN, BAD }

/** The padding around a page's content in the current tier (the web console's main area). */
@Composable
fun pagePadding(): PaddingValues = when (LocalTier.current) {
    Tier.NARROW -> PaddingValues(Space.s4, Space.s4, Space.s4, Space.s6)
    Tier.MEDIUM -> PaddingValues(Space.s6, Space.s5, Space.s6, Space.s8)
    Tier.WIDE -> PaddingValues(Space.s8, Space.s7, Space.s8, 40.dp)
}
/** The gap between a page's blocks. */
@Composable
fun pageGap() = if (LocalTier.current == Tier.NARROW) Space.s4 else Space.s5

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTokens.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).background(tokens.surface)
        .border(1.dp, tokens.line, RoundedCornerShape(Radius.xl)), content = content)
}
/** A card's heading row, as in "排在这个应用后面". */
@Composable
fun CardTitle(text: String, aside: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        aside?.invoke(this)
    }
}

/** A list row inside a card; [onClick] makes the whole row a link. */
@Composable
fun ListRow(onClick: (() -> Unit)? = null, last: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val tokens = LocalTokens.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.s4, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
        if (!last) HorizontalDivider(color = tokens.lineSoft)
    }
}

enum class ButtonKind { PRIMARY, GHOST, DANGER }
@Composable
fun HgbButton(text: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.GHOST, small: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val shape = RoundedCornerShape(Radius.md)
    val height = if (small) Control.heightSm else Control.height
    val padding = PaddingValues(horizontal = 14.dp)
    val content: @Composable RowScope.() -> Unit = { Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
    when (kind) {
        ButtonKind.PRIMARY -> Button(onClick, modifier.heightIn(min = height), enabled, shape,
            ButtonDefaults.buttonColors(tokens.primary, tokens.onPrimary, tokens.primary.copy(alpha = .5f), tokens.onPrimary),
            contentPadding = padding, content = content)
        else -> OutlinedButton(onClick, modifier.heightIn(min = height), enabled, shape,
            ButtonDefaults.outlinedButtonColors(tokens.surface, if (kind == ButtonKind.DANGER) tokens.danger else tokens.ink),
            border = BorderStroke(1.dp, tokens.control), contentPadding = padding, content = content)
    }
}
@Composable
fun LinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = Space.s1)) {
        Text(text, color = LocalTokens.current.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> = with(LocalTokens.current) {
    when (tone) {
        Tone.OK -> okBg to ok; Tone.INFO -> runBg to run; Tone.WARN -> warnBg to warn; Tone.BAD -> dangerBg to danger
        Tone.NEUTRAL -> neutralBg to neutral
    }
}
/** A status pill; [dot] adds the leading dot the design uses for live states. */
@Composable
fun Pill(text: String, tone: Tone = Tone.NEUTRAL, dot: Boolean = false) {
    val (bg, fg) = toneColors(tone)
    Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(bg).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
/** Result text in a list, coloured by tone. */
@Composable
fun ResultText(text: String, tone: Tone) {
    val color = if (tone == Tone.NEUTRAL) LocalTokens.current.muted else toneColors(tone).second
    Text(text, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
}
@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall, maxLines = maxLines)
@Composable
fun Empty(text: String) = Text(text, Modifier.fillMaxWidth().padding(vertical = Space.s6, horizontal = Space.s4),
    color = LocalTokens.current.muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
/** A result line under a form. */
@Composable
fun Message(text: String?, ok: Boolean = false) {
    if (text.isNullOrEmpty()) return
    Text(text, color = if (ok) LocalTokens.current.ok else LocalTokens.current.danger, style = MaterialTheme.typography.bodySmall)
}

/** The design's on/off switch. */
@Composable
fun HgbSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val tokens = LocalTokens.current
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = tokens.primary, checkedBorderColor = tokens.primary,
        uncheckedThumbColor = Color.White, uncheckedTrackColor = tokens.switchOff, uncheckedBorderColor = tokens.switchOff))
}

/** A page's title row: [back] puts a way back before the title; [aside] holds the page's actions. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, crumbs: String? = null, back: (() -> Unit)? = null, aside: (@Composable RowScope.() -> Unit)? = null,
    /** Sits right after the title, as the app page's account button. */
    titleExtra: (@Composable () -> Unit)? = null) {
    val tokens = LocalTokens.current
    // A place's own page, when narrow: the top bar already shows its name, so only the line under it and the actions stay.
    if (LocalTier.current == Tier.NARROW && back == null && titleExtra == null) {
        if (subtitle != null || aside != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(subtitle.orEmpty(), Modifier.weight(1f), color = tokens.muted, style = MaterialTheme.typography.bodyMedium)
            aside?.invoke(this)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        if (crumbs != null && LocalTier.current != Tier.NARROW) Text(crumbs, color = tokens.muted, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            if (back != null) IconButton(back, Modifier.offset(x = (-12).dp).size(40.dp)) { Icon(HgbIcons.Back, "返回", tint = tokens.ink) }
            // The title and what sits right after it share the free width; the actions stay at the end.
            Row(Modifier.weight(1f).offset(x = if (back != null) (-12).dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false).semantics { heading() },
                    style = if (LocalTier.current == Tier.NARROW) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                titleExtra?.invoke()
            }
            aside?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = it) }
        }
        if (subtitle != null) Text(subtitle, color = tokens.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Underlined tabs inside a page; they scroll sideways when they do not fit. */
@Composable
fun <T> PageTabs(tabs: List<Pair<T, String>>, value: T, select: (T) -> Unit) {
    val tokens = LocalTokens.current
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
            tabs.forEach { (id, label) ->
                val on = id == value
                // IntrinsicSize.Max: the row scrolls sideways, so the underline takes the label's width rather than filling.
                Column(Modifier.width(IntrinsicSize.Max).selectable(on, role = Role.Tab) { select(id) }.heightIn(min = Control.heightLg), verticalArrangement = Arrangement.Bottom) {
                    Text(label, Modifier.padding(horizontal = Space.s1, vertical = 11.dp), color = if (on) tokens.ink else tokens.muted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) tokens.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = tokens.line)
    }
}
/** The filled buttons the design uses to pick one of a few (a game, an app, an account). */
@Composable
fun <T> Segments(items: List<Pair<T, String>>, value: T?, select: (T) -> Unit) {
    val tokens = LocalTokens.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        items.forEach { (id, label) ->
            val on = id == value
            Box(Modifier.heightIn(min = Control.height).clip(RoundedCornerShape(Radius.md))
                .background(if (on) tokens.primary else tokens.surface).border(1.dp, if (on) tokens.primary else tokens.control, RoundedCornerShape(Radius.md))
                .selectable(on, role = Role.RadioButton) { select(id) }.padding(horizontal = Space.s4), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) tokens.onPrimary else tokens.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
/** A label-value list like 应用 / 上次结果. */
@Composable
fun Facts(items: List<Pair<String, String>>) {
    val tokens = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        items.forEach { (term, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(term, color = tokens.muted, style = MaterialTheme.typography.bodySmall)
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
/** Cards in one column when narrow, two when medium, and as many 300-wide ones as fit when wide. */
@Composable
fun CardGrid(count: Int, item: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when (LocalTier.current) { Tier.NARROW -> 1; Tier.MEDIUM -> 2; Tier.WIDE -> (maxWidth / 300.dp).toInt().coerceAtLeast(1) }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            (0 until count).chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { item(it) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A choice shown as its value in the accent colour with a small arrow; it opens the options one per row, as a select
 * does, the chosen one highlighted. Used wherever a page picks one of a few (an account, a place, a filter).
 */
@Composable
fun <T> Select(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, label: String, small: Boolean = true, enabled: Boolean = true) {
    val tokens = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(Radius.sm)).clickable(enabled = enabled, onClickLabel = label) { open = true }
            .padding(horizontal = Space.s1), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(options.firstOrNull { it.first == value }?.second ?: "—", color = if (enabled) tokens.primary else tokens.muted, fontWeight = FontWeight.SemiBold,
                style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, maxLines = 1)
            Icon(HgbIcons.Chevron, null, Modifier.size(14.dp).rotate(90f), tint = if (enabled) tokens.primary else tokens.muted)
        }
        DropdownMenu(open, { open = false }, containerColor = tokens.surface, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (id, text) ->
                val on = id == value
                DropdownMenuItem({ Text(text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) tokens.navCurrent else tokens.ink) },
                    { open = false; if (!on) onChange(id) }, Modifier.widthIn(min = 180.dp).background(if (on) tokens.navCurrentBg else tokens.surface))
            }
        }
    }
}
/** A label beside a control, as the web console's inline field. */
@Composable
fun InlineField(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(label, color = LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall); content()
    }
}

/** A one-line text box with the design's border. */
@Composable
fun TextInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, secret: Boolean = false) {
    val tokens = LocalTokens.current
    OutlinedTextField(value, onChange, modifier.heightIn(min = Control.heightSm), singleLine = true,
        placeholder = { Text(placeholder, color = tokens.faint, style = MaterialTheme.typography.bodyMedium) },
        textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(Radius.sm),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = tokens.focus, unfocusedBorderColor = tokens.control,
            focusedContainerColor = tokens.surface, unfocusedContainerColor = tokens.surface))
}

/** A checkbox with its label, for picking several of a list. */
@Composable
fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val tokens = LocalTokens.current
    Row(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(Radius.sm)).selectable(checked, enabled, Role.Checkbox) { onChange(!checked) }.padding(end = Space.s2),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = tokens.primary, uncheckedColor = tokens.control, checkmarkColor = tokens.onPrimary))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
/** Asks before something that cannot be undone; [onConfirm] runs on yes. */
@Composable
fun Confirm(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalTokens.current
    AlertDialog(onDismiss, confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(confirm, color = tokens.danger, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("取消", color = tokens.ink) } }, text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        containerColor = tokens.surface)
}
/** A labelled field above its control, for forms. */
@Composable
fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(label, color = LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall); content()
    }
}

/** A row's less used actions behind ⋯; an item whose text starts with the danger flag is drawn in red. */
class MenuAction(val text: String, val danger: Boolean = false, val run: () -> Unit)
@Composable
fun MoreMenu(label: String, actions: List<MenuAction>) {
    val tokens = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.size(36.dp)) { Icon(HgbIcons.More, label, Modifier.size(20.dp), tint = tokens.muted) }
        DropdownMenu(open, { open = false }, containerColor = tokens.surface) {
            actions.forEach { action ->
                DropdownMenuItem({ Text(action.text, color = if (action.danger) tokens.danger else tokens.ink) }, { open = false; action.run() })
            }
        }
    }
}

/** A card's top line as a short sentence around a choice, such as “在 自动分配 ▾ 运行”. */
@Composable
fun ChoiceLine(before: String, after: String = "", choice: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(before, color = tokens.muted, style = MaterialTheme.typography.bodyMedium); choice()
        if (after.isNotEmpty()) Text(after, color = tokens.muted, style = MaterialTheme.typography.bodyMedium)
    }
}
