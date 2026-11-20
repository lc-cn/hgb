// Generated from design/tokens.json by scripts/tokens.mjs. Do not edit.
package io.github.yysassist.shared.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours of one theme, named as in design/tokens.json. */
data class TokenColors(
    val bg: Color,
    val surface: Color,
    val surfaceHead: Color,
    val line: Color,
    val lineSoft: Color,
    val control: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val nav: Color,
    val primary: Color,
    val primaryHover: Color,
    val onPrimary: Color,
    val navCurrentBg: Color,
    val navCurrent: Color,
    val primarySoft: Color,
    val primaryLine: Color,
    val track: Color,
    val switchOff: Color,
    val okBg: Color,
    val ok: Color,
    val runBg: Color,
    val run: Color,
    val warnBg: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnLine: Color,
    val warnInk: Color,
    val dangerBg: Color,
    val danger: Color,
    val neutralBg: Color,
    val neutral: Color,
    val live: Color,
    val lost: Color,
    val focus: Color,
    val logoStar: Color,
    val heroBg: Color,
    val heroInk: Color,
    val heroMuted: Color,
)

object DesignTokens {
    val light = TokenColors(
        bg = Color(0xFFF6F6F8),
        surface = Color(0xFFFFFFFF),
        surfaceHead = Color(0xFFFAFAFB),
        line = Color(0xFFE6E5EB),
        lineSoft = Color(0xFFF0EFF4),
        control = Color(0xFFD9D7E0),
        ink = Color(0xFF1C1B22),
        muted = Color(0xFF5E5C69),
        faint = Color(0xFF6E6C7A),
        nav = Color(0xFF3A3946),
        primary = Color(0xFF5B3FB0),
        primaryHover = Color(0xFF432C8C),
        onPrimary = Color(0xFFFFFFFF),
        navCurrentBg = Color(0xFFEEEAF8),
        navCurrent = Color(0xFF4A2F9E),
        primarySoft = Color(0xFFF3F0FB),
        primaryLine = Color(0xFFDCD3F3),
        track = Color(0xFFE6E5EB),
        switchOff = Color(0xFFC4C2CC),
        okBg = Color(0xFFE6F4EC),
        ok = Color(0xFF1E6B45),
        runBg = Color(0xFFE7F0FE),
        run = Color(0xFF1F4E9E),
        warnBg = Color(0xFFFFF4E5),
        warn = Color(0xFF8A4B00),
        warnSoft = Color(0xFFFFF8EE),
        warnLine = Color(0xFFF3D9B1),
        warnInk = Color(0xFF5C3300),
        dangerBg = Color(0xFFFBEAE8),
        danger = Color(0xFFA3261F),
        neutralBg = Color(0xFFF0EFF4),
        neutral = Color(0xFF5E5C69),
        live = Color(0xFF1E8E5A),
        lost = Color(0xFFA3261F),
        focus = Color(0xFF5B3FB0),
        logoStar = Color(0xFFFFD9A8),
        heroBg = Color(0xFF1C1B22),
        heroInk = Color(0xFFEDECF2),
        heroMuted = Color(0xFFA9A7B5),
    )
    val dark = TokenColors(
        bg = Color(0xFF141318),
        surface = Color(0xFF1D1C23),
        surfaceHead = Color(0xFF222128),
        line = Color(0xFF34323D),
        lineSoft = Color(0xFF2A2931),
        control = Color(0xFF45434F),
        ink = Color(0xFFEDECF2),
        muted = Color(0xFFA9A7B5),
        faint = Color(0xFF9A98A6),
        nav = Color(0xFFCFCDD8),
        primary = Color(0xFFB3A1F2),
        primaryHover = Color(0xFFC9BCF7),
        onPrimary = Color(0xFF1A1233),
        navCurrentBg = Color(0xFF2E2650),
        navCurrent = Color(0xFFD6CCFA),
        primarySoft = Color(0xFF262140),
        primaryLine = Color(0xFF3D3466),
        track = Color(0xFF34323D),
        switchOff = Color(0xFF4C4A56),
        okBg = Color(0xFF17331F),
        ok = Color(0xFF8FD6AE),
        runBg = Color(0xFF172A47),
        run = Color(0xFF9DC1F5),
        warnBg = Color(0xFF3A2A12),
        warn = Color(0xFFF2C27A),
        warnSoft = Color(0xFF2C2214),
        warnLine = Color(0xFF5A4320),
        warnInk = Color(0xFFF5D29E),
        dangerBg = Color(0xFF3D1C1A),
        danger = Color(0xFFFF9C94),
        neutralBg = Color(0xFF2A2931),
        neutral = Color(0xFFB5B3C0),
        live = Color(0xFF4CC38A),
        lost = Color(0xFFFF9C94),
        focus = Color(0xFFB3A1F2),
        logoStar = Color(0xFFFFD9A8),
        heroBg = Color(0xFF0E0D12),
        heroInk = Color(0xFFEDECF2),
        heroMuted = Color(0xFFA9A7B5),
    )
    /** Window widths where the layout changes: below medium is narrow (bottom bar), from wide on the full sidebar. */
    object Breakpoint {
        val medium = 600.dp
        val wide = 1024.dp
    }
    object Text {
        val xs = 12.sp
        val sm = 13.sp
        val md = 14.sp
        val lg = 15.sp
        val xl = 16.sp
        val xxl = 18.sp
        val title = 24.sp
    }
    object Space {
        val s1 = 4.dp
        val s2 = 8.dp
        val s3 = 12.dp
        val s4 = 16.dp
        val s5 = 20.dp
        val s6 = 24.dp
        val s7 = 28.dp
        val s8 = 32.dp
    }
    object Radius {
        val sm = 8.dp
        val md = 10.dp
        val lg = 12.dp
        val xl = 16.dp
        val pill = 999.dp
    }
    object Control {
        val height = 40.dp
        val heightLg = 44.dp
        val heightSm = 36.dp
    }
}
