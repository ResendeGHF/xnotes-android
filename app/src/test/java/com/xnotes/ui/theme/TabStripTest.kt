package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import com.xnotes.settings.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The tab strip backing ([Palette.tabStripBackground]): segmented tabs are rounded chips with
 * gaps between them, and without a backing those gaps show the default background (#000000 on
 * OLED). The strip fills its bounds with an accent-derived tone — deepened hard on dark themes,
 * tinted toward the accent on light ones — while every tab state stays readable on it.
 */
class TabStripTest {

    private val accents = listOf(Rgba(0, 102, 255), Rgba(0, 230, 118), Preferences.DEFAULT_MATERIAL_DUAL)

    private fun palettes(accent: Rgba): List<Palette> {
        val dark = MaterialColors.seeded(accent, dark = true)
        val light = MaterialColors.seeded(accent, dark = false)
        // Both dark variants share the OLED/desktop surfaces; light stands for the day theme.
        return listOf(Palette.materialDark(dark), Palette.materialOled(dark), Palette.materialLight(light))
    }

    private fun luminance(c: Rgba) = ColorMath.relativeLuminance(c)

    private fun dominant(c: Rgba): Int = listOf(c.r, c.g, c.b).indexOf(listOf(c.r, c.g, c.b).max())

    @Test fun stripIsADarkerToneOfTheAccentNeverBlack() {
        for (accent in accents) {
            for (p in palettes(accent).filter { it.isDark }) {
                val s = p.tabStripBackground
                assertEquals(255, s.a)
                assertTrue("strip $s not darker than accent ${p.accent}", luminance(s) < luminance(p.accent))
                assertTrue("strip reads as pure black for accent $accent", luminance(s) > 0.004)
                assertEquals("strip lost the hue of accent ${p.accent}", dominant(p.accent), dominant(s))
            }
        }
    }

    @Test fun blueAccentSitsOnDarkBlue() {
        for (p in palettes(Rgba(0, 102, 255)).filter { it.isDark }) {
            val s = p.tabStripBackground
            // Calibrated definition: the accent deepened hard, still unmistakably blue.
            assertEquals(ColorMath.darken(p.accent, 0.85), s)
            assertTrue("expected a dark blue strip, got $s", s.b > s.r && s.b > s.g)
        }
    }

    @Test fun darkStripKeepsEveryTabStateReadable() {
        for (accent in accents) {
            for (p in palettes(accent).filter { it.isDark }) {
                val strip = p.tabStripBackground
                // Unselected glyphs sit directly on the strip (transparent tabs).
                assertTrue(
                    "unselected tab unreadable on strip $strip (accent $accent)",
                    ColorMath.contrastRatio(p.textDim, strip) >= 6.0,
                )
                // The active tab keeps its pill; the pill must still read as a highlight wash.
                assertTrue(
                    "selection pill lost on strip $strip (accent $accent)",
                    ColorMath.contrastRatio(p.selectionBackground, strip) >= 1.7,
                )
                assertTrue(
                    "selection glyph unreadable (accent $accent)",
                    ColorMath.contrastRatio(p.selectionForeground, p.selectionBackground) >= 3.0,
                )
            }
        }
    }

    private fun rgbDist(a: Rgba, b: Rgba): Double {
        val dr = (a.r - b.r).toDouble()
        val dg = (a.g - b.g).toDouble()
        val db = (a.b - b.b).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    @Test fun lightStripIsAnAccentTintThatChangesNothingReadable() {
        for (accent in accents) {
            val p = palettes(accent).first { !it.isDark }
            val strip = p.tabStripBackground
            // Tinted toward the accent instead of plain background...
            assertTrue("strip is not accent-tinted (accent $accent)", rgbDist(strip, p.accent) < rgbDist(p.bg, p.accent))
            assertTrue("strip identical to background (accent $accent)", strip != p.bg)
            // ...while all three tab states keep about the contrast they had on the background.
            val pillNow = ColorMath.contrastRatio(p.selectionBackground, strip)
            val pillWas = ColorMath.contrastRatio(p.selectionBackground, p.bg)
            assertTrue("pill lost (accent $accent): $pillNow vs $pillWas", abs(pillNow - pillWas) < 0.6)
            assertTrue(
                "unselected tab unreadable (accent $accent)",
                ColorMath.contrastRatio(p.textDim, strip) >= 4.5,
            )
            assertTrue(
                "selection glyph unreadable (accent $accent)",
                ColorMath.contrastRatio(p.selectionForeground, p.selectionBackground) >= 3.0,
            )
        }
    }
}
