package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sync live path must send the style a stroke commits with: the toolbar
 * ink color (unless the tool overrides its own) and the zoom-normalized
 * width. Sending the stored tool config instead showed the far side's live
 * run in a stale color.
 */
class LiveStrokeStyleTest {
    private fun setup(): Pair<CanvasState, InteractionController> {
        val st = CanvasState(
            Document(mutableListOf(Page(400.0, 800.0))),
            FakeSurfaceFactory(), TestPalette(),
        ).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }
        val scheduler = FakeUiScheduler()
        return st to InteractionController(st, History(), FakeTextMeasurer(), requestRender = {}, scheduler = scheduler)
    }

    @Test fun liveStyleFollowsToolbarInkColor() {
        val (_, c) = setup()
        c.inkColor = Rgba(255, 0, 0, 255)
        assertEquals(Rgba(255, 0, 0, 255), c.resolvedStrokeConfig(Tool.PEN).rgba)
    }

    @Test fun toolColorOverrideWinsOverToolbar() {
        val (_, c) = setup()
        c.inkColor = Rgba(255, 0, 0, 255)
        c.setToolConfig(Tool.PEN, c.configFor(Tool.PEN).copy(colorOverride = Rgba(0, 0, 255, 255)))
        assertEquals(Rgba(0, 0, 255, 255), c.resolvedStrokeConfig(Tool.PEN).rgba)
    }

    @Test fun liveWidthMatchesCommittedWidthOffScale() {
        val (st, c) = setup()
        st.zoom = 2.0
        val base = c.configFor(Tool.PEN).baseWidth
        c.setToolConfig(Tool.PEN, c.configFor(Tool.PEN).copy(scale = false))
        assertEquals(base / 2.0, c.resolvedStrokeConfig(Tool.PEN).baseWidth, 1e-9)
    }
}
