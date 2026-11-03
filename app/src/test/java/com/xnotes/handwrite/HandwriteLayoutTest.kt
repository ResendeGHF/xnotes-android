package com.xnotes.handwrite

import com.xnotes.core.FakeMathTypesetter
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for block layout (fake backends, no Android). */
class HandwriteLayoutTest {

    private val ink = Rgba(20, 30, 40)
    private val measurer = FakeTextMeasurer()
    private val lh = 10.0 * 1.3

    @Test
    fun `words stack as glyph rows`() {
        val rows = layoutBlocks(
            listOf(HBlock.Para(listOf(HSeg.Words("hi"))), HBlock.Gap),
            measurer, ink, 10.0, 300.0,
        )
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].items.size)
        assertEquals(lh, rows[0].height, 1e-9)
        assertEquals(0.0, rows[0].top, 1e-9)
        assertTrue(rows[1].items.isEmpty())
        assertEquals(lh, rows[1].top, 1e-9)
    }

    @Test
    fun `math without a typesetter falls back to words`() {
        val old = TextItem.mathTypesetter
        TextItem.mathTypesetter = null
        try {
            val rows = layoutBlocks(listOf(HBlock.Math("x^2")), measurer, ink, 10.0, 300.0)
            assertEquals(1, rows.size)
            assertTrue(rows[0].items.none { it is TextItem && (it as TextItem).math })
            assertEquals(1, rows[0].items.size)
        } finally {
            TextItem.mathTypesetter = old
        }
    }

    @Test
    fun `math with a typesetter becomes an editable math box`() {
        val old = TextItem.mathTypesetter
        TextItem.mathTypesetter = FakeMathTypesetter()
        try {
            val rows = layoutBlocks(
                listOf(HBlock.Para(listOf(HSeg.Formula("x^2")))),
                measurer, ink, 10.0, 300.0,
            )
            assertEquals(1, rows.size)
            val box = rows[0].items.single() as TextItem
            assertTrue(box.math)
            assertEquals("x^2", box.text)
            assertTrue(box.width > 0.0)
            // Centred on the wrap width.
            assertEquals((300.0 - box.width) / 2.0, box.pos.x, 1e-9)
        } finally {
            TextItem.mathTypesetter = old
        }
    }

    @Test
    fun `table becomes an editable table holding its cells`() {
        val rows = layoutBlocks(
            listOf(HBlock.Table(listOf(listOf("a", "b"), listOf("c", "d")))),
            measurer, ink, 10.0, 300.0,
        )
        assertEquals(1, rows.size)
        val table = rows[0].items.single() as TableItem
        assertEquals(2, table.cols)
        assertEquals(2, table.rows)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), table.cellGrid())
        assertTrue(table.rect.w in 1.0..300.0)
        assertEquals(table.rect.h + 0.25 * lh, rows[0].height, 1e-9)
    }

    @Test
    fun `heading size scales the rows`() {
        val rows = layoutBlocks(
            listOf(HBlock.Para(listOf(HSeg.Words("Hi")), sizeMult = 1.5)),
            measurer, ink, 10.0, 300.0,
        )
        assertEquals(1, rows.size)
        assertEquals(15.0 * 1.3, rows[0].height, 1e-9)
    }

    @Test
    fun `face is the hand face`() {
        assertEquals(FontFace.HAND, FontSpec(10.0, FontFace.HAND).face)
    }
}
