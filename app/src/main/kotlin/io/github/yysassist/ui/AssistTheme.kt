package io.github.yysassist.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.yysassist.ThemeMode

data class AssistPalette(
    val dark: Boolean,
    val blue: Color, val violet: Color, val orange: Color,
    val teal: Color, val rose: Color, val gold: Color,
    val success: Color, val warning: Color, val error: Color,
)
private val LightPalette = AssistPalette(false,
    Color(0xFF2868B2), Color(0xFF7950BA), Color(0xFFAA5319),
    Color(0xFF167474), Color(0xFFB13E70), Color(0xFF946611),
    Color(0xFF276547), Color(0xFF7D5315), Color(0xFFA1333A))
private val DarkPalette = AssistPalette(true,
    Color(0xFFA8CFF9), Color(0xFFCFB1FC), Color(0xFFF8BF89),
    Color(0xFF8BD9CD), Color(0xFFF5ABC9), Color(0xFFF5D079),
    Color(0xFFA4D5B4), Color(0xFFF1CE8E), Color(0xFFFFB4B8))
val LocalAssistPalette = staticCompositionLocalOf { LightPalette }
@Composable
fun paletteOnStrong(): AssistPalette = if (LocalAssistPalette.current.dark) LightPalette else DarkPalette
object UiSpace {
    val tiny = 4.dp; val gap = 8.dp; val medium = 12.dp; val page = 20.dp
    val section = 24.dp; val hit = 48.dp; val icon = 22.dp; val tile = 32.dp
    val rail = 80.dp; val wide = 600.dp; val row = 72.dp
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
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFEEEEEE), onPrimary = Color(0xFF171717),
        background = Color(0xFF161616), surface = Color(0xFF161616),
        surfaceContainer = Color(0xFF222222), surfaceContainerHigh = Color(0xFF2B2B2B),
        onBackground = Color(0xFFF2F2F2), onSurface = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFFBCBCBC), outlineVariant = Color(0xFF414141),
        error = DarkPalette.error,
    ) else lightColorScheme(
        primary = Color(0xFF202020), onPrimary = Color.White,
        background = Color(0xFFF6F6F6), surface = Color(0xFFF6F6F6),
        surfaceContainer = Color(0xFFEDEDED), surfaceContainerHigh = Color.White,
        onBackground = Color(0xFF181818), onSurface = Color(0xFF181818),
        onSurfaceVariant = Color(0xFF555555), outlineVariant = Color(0xFFD3D3D3),
        error = LightPalette.error,
    )
    CompositionLocalProvider(LocalAssistPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.hostActivity()
    else -> null
}
