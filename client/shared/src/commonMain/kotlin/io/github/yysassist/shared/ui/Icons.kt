package io.github.yysassist.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yysassist.shared.theme.LocalTokens

/** Line icons on a 24 grid, the same paths as the web console's (client/web/src/shell/Shell.tsx). */
object HgbIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}A$r $r 0 0 1 ${x + w - r} ${y + h}H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}A$r $r 0 1 0 ${cx + r} ${cy}A$r $r 0 1 0 ${cx - r} ${cy}Z"

    val Devices = icon("devices", rect(7f, 2f, 10f, 20f, 2f), "M11 18h2")
    val Accounts = icon("accounts", rect(3f, 6f, 18f, 12f, 3f), "M8 10v4M6 12h4M15 11h.01M17 13h.01")
    val Library = icon("library", "M4 5h6v14H4zM14 5l5 1.5-3.5 13L11 18")
    val Notices = icon("notices", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4z", "M10 21h4")
    val Me = icon("me", circle(12f, 8f, 4f), "M5 20a7 7 0 0 1 14 0")
    val Develop = icon("develop", "M8 8l-4 4 4 4M16 8l4 4-4 4M14 5l-4 14")
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Chevron = icon("chevron", "M9 5l7 7-7 7")
    val Plus = icon("plus", "M12 5v14M5 12h14")
    val More = icon("more", "M5 12h.01M12 12h.01M19 12h.01")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7")
    /** A gear (Lucide "settings", ISC licence). */
    val Settings = icon("settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        circle(12f, 12f, 3f))
}

/** The 夜班 mark: a crescent and a star on the primary colour (client/site/src/components/Logo.astro). */
@Composable
fun HgbMark(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val mark = remember(tokens) {
        ImageVector.Builder("mark", 48.dp, 48.dp, 48f, 48f).apply {
            path(fill = SolidColor(tokens.primary)) { moveTo(12f, 0f); lineTo(36f, 0f); arcTo(12f, 12f, 0f, false, true, 48f, 12f); lineTo(48f, 36f)
                arcTo(12f, 12f, 0f, false, true, 36f, 48f); lineTo(12f, 48f); arcTo(12f, 12f, 0f, false, true, 0f, 36f); lineTo(0f, 12f)
                arcTo(12f, 12f, 0f, false, true, 12f, 0f); close() }
            addPath(addPathNodes("M27 9.5 A15 15 0 1 0 39 31 A12 12 0 1 1 27 9.5 Z"), fill = SolidColor(tokens.onPrimary))
            addPath(addPathNodes("M33.5 11 L34.8 14.3 L38 15.1 L34.8 16.1 L33.5 19.2 L32.2 16.1 L29 15.1 L32.2 14.3 Z"), fill = SolidColor(tokens.logoStar))
        }.build()
    }
    Image(mark, null, modifier.size(size))
}
