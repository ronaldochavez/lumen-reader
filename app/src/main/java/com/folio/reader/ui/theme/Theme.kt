package com.folio.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm, cozy palette in the spirit of social reading apps: cream paper, aubergine ink, plum + coral accents.
object Folio {
    val Cream = Color(0xFFFFF8F0)
    val CreamDeep = Color(0xFFF6EBDD)
    val Paper = Color(0xFFFFFCF7)
    val Ink = Color(0xFF2A1F2D)
    val InkSoft = Color(0xFF6F6273)
    val Plum = Color(0xFF5B3E96)
    val Coral = Color(0xFFF2765A)
    val CoralSoft = Color(0xFFFFE3DA)
    val Mustard = Color(0xFFF3B948)
    val Mint = Color(0xFF6DBE9F)
    val Sky = Color(0xFF7FA7E8)
    val Line = Color(0xFFE8DCCF)

    val NightBg = Color(0xFF16121B)
    val NightSurface = Color(0xFF221B29)
    val NightInk = Color(0xFFEDE6F2)

    /** Cover placeholder gradients, picked by title hash. */
    val coverPalettes = listOf(
        Color(0xFF5B3E96) to Color(0xFF9A7BE0),
        Color(0xFFF2765A) to Color(0xFFF7B267),
        Color(0xFF2F7D6D) to Color(0xFF6DBE9F),
        Color(0xFF34457A) to Color(0xFF7FA7E8),
        Color(0xFF8C3B5E) to Color(0xFFE08BAA),
        Color(0xFF6B5A2E) to Color(0xFFF3B948),
    )

    val highlightColors = linkedMapOf(
        "yellow" to Color(0xFFF7C948),
        "coral" to Color(0xFFF2765A),
        "mint" to Color(0xFF6DBE9F),
        "violet" to Color(0xFF9A7BE0),
    )

    /** Accent choices for the main buttons. The first one is the original plum. */
    val accentPresets = linkedMapOf(
        "Ciruela" to Plum,
        "Coral" to Color(0xFFD9583C),
        "Bosque" to Color(0xFF2F7D6D),
        "Océano" to Color(0xFF2B6CB0),
        "Turquesa" to Color(0xFF0F8B8D),
        "Frambuesa" to Color(0xFFB83268),
        "Ámbar" to Color(0xFFB7791F),
        "Grafito" to Color(0xFF4A5568),
    )
}

/** Which surface palette the app uses. BLACK is a neutral, true-black dark mode (no purple tint). */
enum class ThemeStyle { LIGHT, DARK, BLACK }

private val LightBase = lightColorScheme(
    secondary = Folio.Coral,
    onSecondary = Color.White,
    secondaryContainer = Folio.CoralSoft,
    onSecondaryContainer = Color(0xFF5A1D0E),
    tertiary = Folio.Mustard,
    onTertiary = Folio.Ink,
    background = Folio.Cream,
    onBackground = Folio.Ink,
    surface = Folio.Cream,
    onSurface = Folio.Ink,
    surfaceVariant = Folio.CreamDeep,
    onSurfaceVariant = Folio.InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Folio.Paper,
    surfaceContainer = Color(0xFFFBF1E6),
    surfaceContainerHigh = Folio.CreamDeep,
    surfaceContainerHighest = Color(0xFFF0E2D2),
    outline = Color(0xFFD5C5B6),
    outlineVariant = Folio.Line,
)

private val DarkBase = darkColorScheme(
    secondary = Color(0xFFFF9A80),
    onSecondary = Color(0xFF4A1609),
    secondaryContainer = Color(0xFF6B2A1B),
    onSecondaryContainer = Folio.CoralSoft,
    tertiary = Folio.Mustard,
    background = Folio.NightBg,
    onBackground = Folio.NightInk,
    surface = Folio.NightBg,
    onSurface = Folio.NightInk,
    surfaceVariant = Folio.NightSurface,
    onSurfaceVariant = Color(0xFFB8ADC0),
    surfaceContainerLowest = Color(0xFF110D15),
    surfaceContainerLow = Color(0xFF1B1621),
    surfaceContainer = Folio.NightSurface,
    surfaceContainerHigh = Color(0xFF2B2333),
    surfaceContainerHighest = Color(0xFF342B3D),
    outline = Color(0xFF5A4F63),
    outlineVariant = Color(0xFF3A3142),
)

/** Neutral grays on pure black: easy on OLED screens and without the plum tint of [DarkBase]. */
private val BlackBase = darkColorScheme(
    secondary = Color(0xFFFF9A80),
    onSecondary = Color(0xFF4A1609),
    secondaryContainer = Color(0xFF5A2418),
    onSecondaryContainer = Folio.CoralSoft,
    tertiary = Folio.Mustard,
    background = Color.Black,
    onBackground = Color(0xFFECECEC),
    surface = Color.Black,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFA3A3A3),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF131313),
    surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFF3C3C3C),
    outlineVariant = Color(0xFF262626),
)

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** Moves [c] toward [target] until [against] reaches [min] contrast with it (or we run out of room). */
private fun ensureContrast(c: Color, against: Color, target: Color, min: Float): Color {
    var out = c
    var t = 0f
    while (contrast(out, against) < min && t < 1f) {
        t += 0.05f
        out = lerp(c, target, t)
    }
    return out
}

/**
 * Derives the primary-role colors from the user's accent so any pick stays legible: text on buttons keeps
 * ≥ 4.5:1 contrast and containers are tints of the accent over the theme's own background.
 */
fun schemeFor(style: ThemeStyle, accent: Color): ColorScheme = when (style) {
    ThemeStyle.LIGHT -> {
        val primary = ensureContrast(accent, Color.White, Color.Black, 4.5f)
        LightBase.copy(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.White, 0.86f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.62f),
            inversePrimary = lerp(accent, Color.White, 0.45f),
            surfaceTint = primary,
        )
    }
    ThemeStyle.DARK, ThemeStyle.BLACK -> {
        val base = if (style == ThemeStyle.DARK) DarkBase else BlackBase
        val primary = ensureContrast(lerp(accent, Color.White, 0.42f), base.background, Color.White, 7f)
        base.copy(
            primary = primary,
            onPrimary = ensureContrast(lerp(accent, Color.Black, 0.78f), primary, Color.Black, 4.5f),
            primaryContainer = lerp(accent, base.background, 0.58f),
            onPrimaryContainer = lerp(accent, Color.White, 0.82f),
            inversePrimary = accent,
            surfaceTint = primary,
        )
    }
}

val SerifDisplay = FontFamily.Serif

private val FolioTypography = Typography().run {
    copy(
        displaySmall = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
        headlineLarge = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = SerifDisplay, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FolioTheme(style: ThemeStyle, accent: Color = Folio.Plum, content: @Composable () -> Unit) {
    val scheme = remember(style, accent) { schemeFor(style, accent) }
    MaterialTheme(
        colorScheme = scheme,
        typography = FolioTypography,
        shapes = FolioShapes,
        content = content,
    )
}
