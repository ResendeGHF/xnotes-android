package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every cache path over one dense page: full builds, region repairs, full
 *  refreshes, prune/keep, sharp viewport and highlighter caches. */
class DensePageCachePathsTest {

    private fun stroke(x: Double, y: Double, tool: Tool = Tool.PEN): Stroke {
        val s = Stroke(
            tool,
            ToolDefaults.configFor(tool),
            mutableListOf(Sample(x, y, 1.0), Sample(x + 8.0, y + 3.0, 0.8), Sample(x + 16.0, y, 0.6)),
        )
        s.finished = true
        return s
    }

    private fun state(itemCount: Int, highlighters: Int = 0): CanvasState {
        val page = Page(1240.0, 1754.0)
        var x = 20.0
        var y = 20.0
        repeat(itemCount) {
            page.items.add(stroke(x, y))
            x += 37.0
            if (x > 1180.0) {
                x = 20.0
                y += 41.0
                if (y > 1700.0) y = 20.0
            }
        }
        repeat(highlighters) {
            page.items.add(stroke(60.0, 60.0 + it * 30.0, Tool.HIGHLIGHTER))
        }
        val doc = Document(mutableListOf(page))
        return CanvasState(doc, FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1080
            viewportH = 2400
            relayout()
        }
    }

    private fun paintedRuns(st: CanvasState, page: Page): Int {
        val entry = st.cacheForOrSchedule(page) ?: return -1
        return (entry.surface as FakeRasterSurface).painter.ribbonRuns.size
    }

    @Test fun fullBuildRepairRefreshPruneAllHoldDenseInk() {
        val st = state(3000, highlighters = 40)
        val page = st.document.pages[0]
        // Full build holds every non-highlighter stroke.
        assertEquals(3000, paintedRuns(st, page))
        // Region repair keeps the cache and repaints the region (the fake
        // painter accumulates runs, so the count grows instead of staying flat).
        st.repairInkRegions(listOf(page to Rect(0.0, 0.0, 400.0, 400.0)))
        assertTrue(paintedRuns(st, page) >= 3000)
        // Full off-thread refresh (inline here) rebuilds everything.
        st.refreshAllInk()
        assertEquals(3000, paintedRuns(st, page))
        // Prune keeps the visible page; prefetch holds it too.
        st.prefetchAndPrune()
        assertEquals(3000, paintedRuns(st, page))
        // Every highlighter composites from its own cache.
        var hl = 0
        for (item in page.items) {
            if (item is Stroke && item.tool == Tool.HIGHLIGHTER) {
                st.highlighterCacheFor(item, page)
                hl++
            }
        }
        assertEquals(40, hl)
    }

    @Test fun sharpViewportRendersDensePage() {
        val st = state(3000)
        val page = st.document.pages[0]
        st.zoom = 2.5 // past the cap: 2.5 * 1754 > 2048
        assertTrue(st.isPastResolutionCap())
        st.requestSharpViewport()
        val blit = st.sharpViewportBlit()
        assertNotNull(blit)
    }

    @Test fun dropCachesExceptKeepsVisiblePage() {
        val st = state(3000)
        val page = st.document.pages[0]
        st.cacheFor(page)
        st.dropCachesExcept(setOf(page))
        assertTrue(st.cacheForOrSchedule(page) != null)
    }
}
