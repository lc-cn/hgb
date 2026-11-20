package io.github.yysassist.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yysassist.ThemeMode
import io.github.yysassist.shared.theme.DesignTokens
import io.github.yysassist.shared.theme.HgbTheme
import io.github.yysassist.shared.theme.TokenColors

data class AssistPalette(
    val dark: Boolean,
    val blue: Color, val violet: Color, val orange: Color,
    val teal: Color, val rose: Color, val gold: Color,
    val success: Color, val warning: Color, val error: Color,
)
/** The older colour names, now taken from the design tokens; new pages use [LocalTokens]. */
private fun TokenColors.palette(dark: Boolean) = AssistPalette(dark,
    blue = run, violet = primary, orange = warn, teal = ok, rose = danger, gold = warn,
    success = ok, warning = warn, error = danger)
private val LightPalette = DesignTokens.light.palette(false)
private val DarkPalette = DesignTokens.dark.palette(true)
val LocalAssistPalette = staticCompositionLocalOf { LightPalette }
@Composable
fun paletteOnStrong(): AssistPalette = if (LocalAssistPalette.current.dark) LightPalette else DarkPalette
object UiSpace {
    val tiny = 4.dp; val gap = 8.dp; val medium = 12.dp; val page = 20.dp
    val section = 24.dp; val hit = 48.dp; val icon = 22.dp; val tile = 32.dp
    val rail = 72.dp; val wide = 600.dp; val row = 64.dp
    val statusDot = 6.dp
    val versionEntryMax = 144.dp
    val hairline = 1.dp
    val calendarMaxHeight = 480.dp
}

@Composable
fun AssistTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val activity = LocalView.current.context.hostActivity()
    SideEffect {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity?.window?.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
    }
    CompositionLocalProvider(LocalAssistPalette provides if (dark) DarkPalette else LightPalette) {
        // The shared theme (client/shared) sets the colours and type every page uses, the console's and this App's own.
        HgbTheme(dark, content)
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.hostActivity()
    else -> null
}
