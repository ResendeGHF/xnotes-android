package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeFingerprint
import com.xnotes.core.tools.Tool
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of the shared replacement hook: restyle/delete report pre-change
 * fingerprints + post-change strokes for sync replay.
 */
class StrokeReplacementSyncTest {
    private fun setup(): Pair<CanvasState, InteractionController> {
        val page = Page(400.0, 800.0)
        page.items.add(
            Stroke(
                Tool.PEN,
                com.xnotes.core.tools.ToolDefaults.configFor(Tool.PEN),
                mutableListOf(Sample(50.0, 300.0, 0.5), Sample(150.0, 300.0, 0.5)),
            ),
        )
        val st = CanvasState(Document(mutableListOf(page)), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }
        return st to InteractionController(st, History(), FakeTextMeasurer(), requestRender = {})
    }

    @Test fun restyleReportsOldAndNew() {
        val (_, c) = setup()
        var reported: List<StrokeReplacement>? = null
        c.onStrokesReplaced = { reported = it }
        c.setTool(Tool.SELECT)
        c.selectAll()
        c.restyleSelection(Rgba(255, 0, 0, 255), null, preview = false)
        val changes = reported ?: error("onStrokesReplaced never fired")
        assertEquals(1, changes.size)
        assertEquals(1, changes.single().removed.size)
        assertEquals(1, changes.single().added.size)
        assertEquals(Rgba(255, 0, 0, 255), changes.single().added.single().config.rgba)
        assertTrue(
            StrokeFingerprint.of(changes.single().removed.single()) !=
                StrokeFingerprint.of(changes.single().added.single()),
        )
    }

    @Test fun deleteReportsRemovedOnly() {
        val (_, c) = setup()
        var reported: List<StrokeReplacement>? = null
        c.onStrokesReplaced = { reported = it }
        c.setTool(Tool.SELECT)
        c.selectAll()
        c.deleteSelection()
        val changes = reported ?: error("onStrokesReplaced never fired")
        assertEquals(1, changes.size)
        assertEquals(1, changes.single().removed.size)
        assertTrue(changes.single().added.isEmpty())
    }

    @Test fun quietPreviewSettlesStaySilent() {
        val (_, c) = setup()
        var count = 0
        c.onStrokesReplaced = { count++ }
        c.setTool(Tool.SELECT)
        c.selectAll()
        // A preview that changes nothing, then its settle: no replacements.
        c.restyleSelection(null, null, preview = true)
        c.restyleSelection(null, null, preview = false)
        assertEquals(0, count)
    }
}
