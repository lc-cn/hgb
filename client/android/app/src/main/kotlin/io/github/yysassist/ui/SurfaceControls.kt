package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.theme.DesignTokens
import io.github.yysassist.shared.theme.LocalTokens

private val CardShape = RoundedCornerShape(DesignTokens.Radius.xl)

/**
 * Row icons are off: rows read like the console's list rows (a name, at most one line under it, the control at the end).
 * A page may still turn them on where an icon is the only cue.
 */
internal val LocalSettingTiles = compositionLocalOf { false }

/** The grouped surface shared by task groups, configuration sections and settings; the console's card. */
@Composable
internal fun AssistCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTokens.current
    Column(modifier.fillMaxWidth().clip(CardShape).background(tokens.surface)
        .border(UiSpace.hairline, tokens.line, CardShape), content = content)
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(UiSpace.tile).clip(RoundedCornerShape(UiSpace.gap)).background(tint.copy(alpha = .14f)), Alignment.Center) {
        Icon(icon, null, Modifier.size(UiSpace.icon), tint = tint.copy(alpha = .86f))
    }
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = LocalTokens.current.lineSoft)

@Composable
internal fun SettingRow(icon: ImageVector, tint: Color, title: String, description: String? = null,
    onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = DesignTokens.Space.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.medium)) {
        if (LocalSettingTiles.current) IconTile(icon, tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (!description.isNullOrEmpty()) Text(description, color = LocalTokens.current.muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

@Composable
internal fun SettingValue(text: String, color: Color = LocalTokens.current.muted) =
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1)

@Composable
internal fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    // The console's small outlined button.
    io.github.yysassist.shared.ui.HgbButton(text, onClick, small = true, enabled = enabled)
}

/** The design's compact switch; the whole 48dp target toggles it. */
@Composable
internal fun AssistSwitch(checked: Boolean, enabled: Boolean = true, label: String? = null, change: (Boolean) -> Unit) {
    val tokens = LocalTokens.current
    Box(Modifier.size(width = 52.dp, height = UiSpace.hit).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier), Alignment.Center) {
        Box(Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape)
            .background(if (checked) tokens.primary else tokens.switchOff)) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(20.dp).clip(CircleShape)
                .background(Color.White))
        }
    }
}

@Composable
internal fun TonePill(text: String, tint: Color) {
    Text(text, Modifier.clip(CircleShape).background(tint.copy(alpha = .14f)).padding(horizontal = UiSpace.gap, vertical = 2.dp),
        color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}
