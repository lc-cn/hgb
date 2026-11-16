package io.github.yysassist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** One outline family matching the approved UI v5 glyphs, rendered natively without a full icon pack. */
object AssistIcons {
    val Home = glyph("Home", "M3 10L12 3L21 10V20H3Z M9 20V13H15V20")
    val Tasks = glyph("Tasks", "M3 3H10V10H3Z M14 3H21V10H14Z M3 14H10V21H3Z M14 14H21V21H14Z")
    val Calendar = glyph("Calendar", "M3 5H21V21H3Z M7 2V8 M17 2V8 M3 11H21")
    val Bell = glyph("Bell", "M5 17V9A7 7 0 0 1 19 9V17L21 19H3Z M9 22H15")
    val Settings = glyph("Settings", "M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12Z M12 2V5 M12 19V22 M2 12H5 M19 12H22 M5 5L7 7 M17 17L19 19 M5 19L7 17 M17 7L19 5")
    val Back = glyph("Back", "M14 5L7 12L14 19", true)
    val Chevron = glyph("Chevron", "M9 5L16 12L9 19", true)
    val Pause = glyph("Pause", "M8 5V19 M16 5V19")
    val Star = glyph("Star", "M12 2L15 8.5L22 9.5L17 14.5L18.5 22L12 18.5L5.5 22L7 14.5L2 9.5L9 8.5Z")
    val Play = glyph("Play", "M7 4L20 12L7 20Z")
    val Close = glyph("Close", "M6 6L18 18 M18 6L6 18")
    val Circle = glyph("Circle", "M20 12A8 8 0 1 1 4 12A8 8 0 1 1 20 12Z")
    val Cloud = glyph("Cloud", "M6 18A5 5 0 0 1 5 9A7 7 0 0 1 18 8A5 5 0 0 1 18 18Z")
    val Phone = glyph("Phone", "M6 2H18V22H6Z M10 18H14")
    val Data = glyph("Data", "M20 5A8 3 0 1 1 4 5A8 3 0 1 1 20 5Z M4 5V19C4 23 20 23 20 19V5 M4 12C4 16 20 16 20 12")

    private fun glyph(name: String, data: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
            .addPath(PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            .build()
}
