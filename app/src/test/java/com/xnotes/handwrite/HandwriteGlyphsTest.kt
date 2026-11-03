package com.xnotes.handwrite

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the text-to-glyph-shape layout (fake measurer, no Android). */
class HandwriteGlyphsTest {

    private val ink = Rgba(20, 30, 40)
    private val measurer = FakeTextMeasurer()
    private val font = FontSpec(pointSize = 10.0, face = FontFace.HAND)
    private val lineHeight = 10.0 * 1.3

    @Test
    fun `blank text yields no lines`() {
        assertTrue(HandwriteGlyphs.toLines("", measurer, font, 300.0, ink).isEmpty())
        assertTrue(HandwriteGlyphs.toLines("  \n ", measurer, font, 300.0, ink).isEmpty())
    }

    @Test
    fun `one line becomes one filled glyph shape`() {
        val lines = HandwriteGlyphs.toLines("hi", measurer, font, 300.0, ink)
        assertEquals(1, lines.size)
        val shape = lines[0].shape!!
        assertEquals(ShapeKind.GLYPHS, shape.shape)
        assertEquals(ink, shape.fillRgba)
        assertEquals(ink, shape.strokeRgba)
        assertEquals(lineHeight, lines[0].advance, 1e-9)
        // Two character squares side by side, each one advance wide, plus the
        // shape bounds' padding (half the glyph pen + 1px each side).
        val adv = 10.0 * FakeTextMeasurer.ADVANCE_PER_POINT
        assertEquals(2 * adv + 2 * (0.5 / 2 + 1.0), HandwriteGlyphs.blockWidth(lines), 1e-9)
    }

    @Test
    fun `blank paragraphs become spacers and survive`() {
        val lines = HandwriteGlyphs.toLines("a\n\nb", measurer, font, 300.0, ink)
        assertEquals(3, lines.size)
        assertTrue(lines[0].shape != null)
        assertNull(lines[1].shape)
        assertEquals(lineHeight, lines[1].advance, 1e-9)
        assertTrue(lines[2].shape != null)
    }

    @Test
    fun `whitespace-only paragraph is a spacer not a shape`() {
        val lines = HandwriteGlyphs.toLines("a\n   \nb", measurer, font, 300.0, ink)
        assertEquals(3, lines.size)
        assertNull(lines[1].shape)
    }
}
