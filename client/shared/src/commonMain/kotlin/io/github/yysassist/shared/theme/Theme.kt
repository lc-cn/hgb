package io.github.yysassist.shared.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The design tokens' colours for the current theme; pages read named colours (ok, warn, run…) from here. */
val LocalTokens = staticCompositionLocalOf { DesignTokens.light }

private fun TokenColors.scheme(dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = primary, onPrimary = onPrimary, primaryContainer = primarySoft, onPrimaryContainer = navCurrent,
    secondaryContainer = navCurrentBg, onSecondaryContainer = navCurrent,
    background = bg, onBackground = ink, surface = bg, onSurface = ink, surfaceVariant = neutralBg, onSurfaceVariant = muted,
    surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface, surfaceContainerHighest = lineSoft,
    outline = control, outlineVariant = line, error = danger, onError = onPrimary, errorContainer = dangerBg, onErrorContainer = danger,
)

/** Type sizes of design/tokens.json on Material's roles: title 24, 2xl 18, xl 16, lg 15, md 14, sm 13, xs 12. */
private val typography = with(DesignTokens.Text) {
    fun style(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontSize = size, lineHeight = (size.value * 1.45f).sp, fontWeight = weight, letterSpacing = 0.sp)
    Typography(
        headlineSmall = style(title, FontWeight.SemiBold),
        titleLarge = style(xxl, FontWeight.SemiBold), titleMedium = style(xl, FontWeight.SemiBold), titleSmall = style(md, FontWeight.SemiBold),
        bodyLarge = style(lg), bodyMedium = style(md), bodySmall = style(sm),
        labelLarge = style(md, FontWeight.Medium), labelMedium = style(sm, FontWeight.Medium), labelSmall = style(xs),
    )
}

/** The theme every page of the console, the Android App and the Windows client is drawn in. */
@Composable
fun HgbTheme(dark: Boolean, content: @Composable () -> Unit) {
    val tokens = if (dark) DesignTokens.dark else DesignTokens.light
    CompositionLocalProvider(LocalTokens provides tokens) {
        MaterialTheme(colorScheme = tokens.scheme(dark), typography = typography, content = content)
    }
}
