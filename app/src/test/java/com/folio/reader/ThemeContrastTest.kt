package com.folio.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.folio.reader.data.ReaderTheme
import com.folio.reader.ui.theme.Folio
import com.folio.reader.ui.theme.ThemeStyle
import com.folio.reader.ui.theme.accentOn
import com.folio.reader.ui.theme.contentOn
import com.folio.reader.ui.theme.schemeFor
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** Presets plus custom hues around the wheel at the picker's saturation/value. */
    private val accents = Folio.accentPresets.values + (0 until 360 step 15).map { Color.hsv(it.toFloat(), 0.62f, 0.66f) }

    @Test
    fun buttonText_isReadable_forEveryAccentAndTheme() {
        for (style in ThemeStyle.entries) for (accent in accents) {
            val s = schemeFor(style, accent)
            val onButton = contrast(s.onPrimary, s.primary)
            assertTrue("$style $accent: onPrimary/primary = $onButton", onButton >= 4.5f)
            val onBg = contrast(s.primary, s.background)
            assertTrue("$style $accent: primary/background = $onBg", onBg >= 3f)
        }
    }

    /** One accent color: the roles that used to be fixed coral (secondary) and mustard (tertiary) follow the pick. */
    @Test
    fun everyAccentRole_followsTheChosenAccent_andIsReadable() {
        for (style in ThemeStyle.entries) for (accent in accents) {
            val s = schemeFor(style, accent)
            assertTrue("$style $accent: secondary", s.secondary == s.primary && s.tertiary == s.primary)
            val tonal = contrast(s.onSecondaryContainer, s.secondaryContainer)
            assertTrue("$style $accent: onSecondaryContainer/secondaryContainer = $tonal", tonal >= 4.5f)
            val container = contrast(s.onPrimaryContainer, s.primaryContainer)
            assertTrue("$style $accent: onPrimaryContainer/primaryContainer = $container", container >= 4.5f)
            assertTrue("$style $accent: onSecondary", contrast(s.onSecondary, s.secondary) >= 4.5f)
        }
    }

    /** Links and the reader's buttons on every reading-theme page, whatever the app theme. */
    @Test
    fun readerAccent_isReadableOnEveryPage() {
        for (theme in ReaderTheme.entries) for (accent in accents) {
            val page = Color(theme.background)
            val a = accentOn(page, accent)
            assertTrue("$theme $accent: accent/page = ${contrast(a, page)}", contrast(a, page) >= 4.5f)
            assertTrue("$theme $accent: text on accent", contrast(contentOn(a), a) >= 4.5f)
        }
    }

    @Test
    fun blackTheme_isTrueBlack_andNeutral() {
        val s = schemeFor(ThemeStyle.BLACK, Folio.Plum)
        assertTrue(s.background == Color.Black && s.surface == Color.Black)
        // Neutral surfaces: red, green and blue channels equal (no plum tint).
        val c = s.surfaceContainer
        assertTrue(c.red == c.green && c.green == c.blue)
    }
}
