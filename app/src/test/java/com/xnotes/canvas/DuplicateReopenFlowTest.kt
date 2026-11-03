package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reported flow: write a line, duplicate it until the page is covered,
 *  close, reopen, and the canvas must show every stroke. */
class DuplicateReopenFlowTest {

    private fun lineStrokes(): MutableList<Stroke> {
        val out = mutableListOf<Stroke>()
        var x = 50.0
        repeat(30) {
            val samples = mutableListOf<Sample>()
            repeat(8) { k -> samples.add(Sample(x + k * 2.0, 200.0 + (k % 3), 0.8)) }
            out.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), samples).also { it.finished = true })
            x += 30.0
        }
        return out
    }

    @Test fun duplicateCoverCloseAndReopenKeepsEveryStrokeVisible() {
        val page = Page(1240.0, 1754.0)
        page.items.addAll(lineStrokes())
        val doc = Document(mutableListOf(page))
        val st = CanvasState(doc, FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1080
            viewportH = 2400
            relayout()
        }
        val history = History()
        val ctrl = InteractionController(st, history, FakeTextMeasurer(), requestRender = {})
        st.cacheFor(page)

        // Duplicate-all until the page is covered, like the report.
        repeat(7) {
            ctrl.selectAll()
            ctrl.duplicateSelection()
        }
        println("items after duplication=${page.items.size}")
        assertTrue(page.items.size > 1000)

        // Reopen: clear the selection exactly like replaceDocument does, then
        // drop the caches and rebuild over the same model.
        ctrl.clearSelection()
        st.invalidateAllCaches()
        st.relayout()
        val entry = st.cacheFor(page)
        val painter = (entry.surface as FakeRasterSurface).painter
        println("ribbonRuns after reopen=${painter.ribbonRuns.size}")
        assertEquals(
            "every duplicated stroke must paint after reopen",
            page.items.size, painter.ribbonRuns.size,
        )

        // A fresh stroke committed now must land in the cache too.
        val s = Stroke(
            Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(10.0, 10.0, 1.0), Sample(60.0, 60.0, 1.0)),
        ).also { it.finished = true }
        page.items.add(s)
        st.appendToCache(page, s)
        val again = st.cacheForOrSchedule(page)
        assertTrue(again != null)
        val painter2 = (again!!.surface as FakeRasterSurface).painter
        assertTrue(
            "fresh stroke must be cached, runs=${painter2.ribbonRuns.size}",
            painter2.ribbonRuns.size >= page.items.size,
        )
    }
}
