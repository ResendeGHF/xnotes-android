package com.xnotes.handwrite

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.RefillShapes
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TableItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every handwritten item stays individually editable: glyph lines expose a
 * style and a fill (so the selection's Change-style popup recolours them),
 * tables expose a style, and all of them move, resize and delete like any
 * canvas item.
 */
class HandwriteEditTest {

    private val ink = Rgba(20, 30, 40)
    private val red = Rgba(200, 30, 30)
    private val blue = Rgba(30, 60, 200)

    private fun glyphLine(): ShapeItem {
        val lines = HandwriteGlyphs.toLines("hi", FakeTextMeasurer(), FontSpec(10.0, FontFace.HAND), 300.0, ink)
        val shape = lines.single().shape!!
        assertEquals(ShapeKind.GLYPHS, shape.shape)
        return shape
    }

    @Test
    fun `glyph line exposes a restyleable style`() {
        val shape = glyphLine()
        val style = DrawStyle.of(shape)
        assertNotNull(style)
        DrawStyle(red, style!!.width).applyTo(shape)
        assertEquals(red, shape.strokeRgba)
        // The fill was the outline's own colour, so it follows as a tint.
        assertEquals(red.copy(a = ink.a), shape.fillRgba)
    }

    @Test
    fun `glyph line exposes a refillable fill`() {
        val shape = glyphLine()
        val entries = RefillShapes.of(listOf(shape), blue)
        assertTrue(entries.isNotEmpty())
        RefillShapes(entries).redo()
        assertEquals(blue.copy(a = ink.a), shape.fillRgba)
        RefillShapes(entries).undo()
        assertEquals(ink, shape.fillRgba)
    }

    @Test
    fun `glyph line moves and resizes like any item`() {
        val shape = glyphLine()
        val before = shape.bounds()
        shape.translate(10.0, 5.0)
        assertEquals(before.left + 10.0, shape.bounds().left, 1e-6)
        assertTrue(shape.resizable && !shape.locked)
    }

    @Test
    fun `table exposes a restyleable style and keeps its cells`() {
        val rows = layoutBlocks(
            listOf(HBlock.Table(listOf(listOf("a", "b")))),
            FakeTextMeasurer(), ink, 10.0, 300.0,
        )
        val table = rows.single().items.single() as TableItem
        assertNotNull(DrawStyle.of(table))
        DrawStyle(red, 3.0).applyTo(table)
        assertEquals(red, table.strokeRgba)
        assertEquals(listOf(listOf("a", "b")), table.cellGrid())
    }
}
