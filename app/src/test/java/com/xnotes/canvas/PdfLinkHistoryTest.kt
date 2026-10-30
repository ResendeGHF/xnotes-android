package com.xnotes.canvas

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boxes around PDF links plus the jump trail behind "Go Back" / "Forget". */
class PdfLinkHistoryTest {

    private fun state(pages: Int = 3): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test
    fun boxesDrawOncePerHeldPage() {
        val st = CanvasState(Document.blank(2), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 6000 // tall enough to show both A4 pages at once
            relayout()
        }
        val boxes: Map<Int, List<Rect>> = mapOf(
            0 to listOf(Rect(10.0, 20.0, 100.0, 12.0)),
            1 to listOf(Rect(5.0, 5.0, 50.0, 10.0), Rect(5.0, 30.0, 50.0, 10.0)),
        )
        val overlay = LinkOverlay(st) { boxes[it] }
        val r = FakeRenderer()
        overlay.draw(r)
        // Page 0 holds one box, page 1 two: each draws a fill and an outline.
        // (A tall viewport shows both pages; a box is never drawn twice.)
        val fills = r.ops.count { it == "fillRect" }
        val strokes = r.ops.count { it == "strokeRect" }
        assertEquals(3, fills)
        assertEquals(3, strokes)
    }

    @Test
    fun pagesWithoutLinksDrawNothing() {
        val st = state(2)
        val overlay = LinkOverlay(st) { null }
        val r = FakeRenderer()
        overlay.draw(r)
        assertTrue(r.ops.none { it == "fillRect" || it == "strokeRect" })
    }

    @Test
    fun snapshotRestoresPaginatedRow() {
        val st = state(4).apply {
            verticalScroll = false
            viewportW = 1000
            viewportH = 1400
            relayout()
        }
        st.goToPage(0)
        val before = st.snapshotView()
        st.goToPage(3)
        assertNotEquals(before.row, st.currentRow)
        st.restoreView(before)
        assertEquals(before.row, st.currentRow)
        assertEquals(before.scrollX, st.scrollX, 1e-9)
        assertEquals(before.scrollY, st.scrollY, 1e-9)
        assertEquals(before.zoom, st.zoom, 1e-9)
    }

    @Test
    fun snapshotRestoresVerticalScroll() {
        val st = state()
        st.zoom = st.fitWidthZoom()
        st.scrollY = 300.0
        st.clampScroll()
        val before = st.snapshotView()
        st.goToPage(st.document.pages.lastIndex)
        st.restoreView(before)
        assertEquals(before.scrollX, st.scrollX, 1e-9)
        assertEquals(300.0, st.scrollY, 1e-9)
    }
}
