package io.github.yysassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Layered optical material for the floating controls and account page. */
@Composable
fun glassBrush(strong: Boolean = false): Brush {
    val palette = LocalAssistPalette.current
    val colors = MaterialTheme.colorScheme
    val base = if (strong) colors.primary else colors.surfaceContainerHigh
    val sheen = if (palette.dark && !strong) Color.White.copy(alpha = .12f) else Color.White.copy(alpha = .28f)
    return Brush.linearGradient(listOf(sheen.compositeOver(base), base, base, sheen.copy(alpha = .08f).compositeOver(base)))
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a),
        blue * a + background.blue * (1 - a), 1f)
}

@Composable
fun GlassChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val color = MaterialTheme.colorScheme
    Box(
        modifier.sizeIn(minWidth = UiSpace.hit, minHeight = 40.dp)
            .clip(shape).background(if (selected) color.primary else color.surfaceContainerHigh)
            .border(UiSpace.hairline, if (selected) color.primary else color.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = UiSpace.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) color.onPrimary else color.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
