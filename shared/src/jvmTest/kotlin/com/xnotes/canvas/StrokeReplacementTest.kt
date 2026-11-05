package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.StrokeFingerprint
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.Tool
import com.xnotes.input.PointerEvent
import com.xnotes.input.PointerSample
import com.xnotes.input.SimplePointerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committed stroke replacements (area-erase splits, selection move/restyle)
 * report pre-change fingerprints + post-change strokes so sync can replay
 * them as erase_strokes + add_stroke ops.
 */
class StrokeReplacementTest {
    private val scheduler = FakeUiScheduler()
    private val history = History()

    private fun state(vararg items: Stroke): CanvasState =
        CanvasState(
            Document(mutableListOf(Page(400.0, 800.0), Page(400.0, 800.0), Page(400.0, 800.0))),
            FakeSurfaceFactory(), TestPalette(),
        ).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }

    private fun controller(st: CanvasState) =
        InteractionController(st, history, FakeTextMeasurer(), requestRender = {}, scheduler = scheduler)

    private fun CanvasState.viewportOf(x: Double, y: Double): Pt = contentToViewport(fromPageSpace(0, Pt(x, y)))

    private fun event(action: Int, at: Pt, time: Long, tool: Int, pressure: Float = 0.6f) =
        SimplePointerEvent(
            action,
            listOf(PointerSample(0, at.x.toFloat(), at.y.toFloat(), pressure, tool)),
            time,
        )

    private fun drawStroke(st: CanvasState, ctrl: InteractionController, pts: List<Pt>) {
        ctrl.setTool(Tool.PEN)
        ctrl.onTouch(event(PointerEvent.ACTION_DOWN, st.viewportOf(pts[0].x, pts[0].y), 0, PointerEvent.TOOL_TYPE_STYLUS))
        for ((i, p) in pts.drop(1).withIndex()) {
            ctrl.onTouch(event(PointerEvent.ACTION_MOVE, st.viewportOf(p.x, p.y), 10L * (i + 1), PointerEvent.TOOL_TYPE_STYLUS))
        }
        val last = pts.last()
        ctrl.onTouch(event(PointerEvent.ACTION_UP, st.viewportOf(last.x, last.y), 10L * pts.size, PointerEvent.TOOL_TYPE_STYLUS))
    }

    @Test fun areaEraseSplitReportsRemovedAndFragments() {
        val st = state()
        val ctrl = controller(st)
        drawStroke(st, ctrl, listOf(Pt(50.0, 300.0), Pt(150.0, 300.0), Pt(250.0, 300.0)))
        assertEquals(1, st.document.pages[0].items.size)
        val original = st.document.pages[0].items.single() as Stroke
        val originalFp = StrokeFingerprint.of(original)

        var reported: List<StrokeReplacement>? = null
        ctrl.onStrokesReplaced = { reported = it }
        ctrl.setTool(Tool.ERASER)
        ctrl.setToolConfig(Tool.ERASER, ctrl.configFor(Tool.ERASER).copy(eraseMode = EraseMode.AREA))
        // Erase through the middle of the horizontal stroke (radius 24 covers y 300).
        ctrl.onTouch(event(PointerEvent.ACTION_DOWN, st.viewportOf(150.0, 300.0), 100, PointerEvent.TOOL_TYPE_ERASER))
        ctrl.onTouch(event(PointerEvent.ACTION_MOVE, st.viewportOf(155.0, 300.0), 110, PointerEvent.TOOL_TYPE_ERASER))
        ctrl.onTouch(event(PointerEvent.ACTION_UP, st.viewportOf(155.0, 300.0), 120, PointerEvent.TOOL_TYPE_ERASER))

        val changes = reported ?: error("onStrokesReplaced never fired")
        assertEquals(1, changes.size)
        val c = changes.single()
        assertEquals(0, c.erasePage)
        assertEquals(0, c.addPage)
        assertEquals(listOf(originalFp), c.removed.map { StrokeFingerprint.of(it) })
        assertTrue("expected fragments, got ${c.added.size}", c.added.size >= 1)
        // Fragments differ from the original (the erased middle is gone).
        assertTrue(c.added.all { StrokeFingerprint.of(it) != originalFp })
        // The model holds exactly the reported fragments.
        val modelFps = st.document.pages[0].items.filterIsInstance<Stroke>().map { StrokeFingerprint.of(it) }.toSet()
        assertEquals(c.added.map { StrokeFingerprint.of(it) }.toSet(), modelFps)
    }

    @Test fun restyleReportsOldAndNew() {
        val st = state()
        val ctrl = controller(st)
        drawStroke(st, ctrl, listOf(Pt(50.0, 300.0), Pt(150.0, 300.0)))
        val original = st.document.pages[0].items.single() as Stroke
        val originalFp = StrokeFingerprint.of(original)

        var reported: List<StrokeReplacement>? = null
        ctrl.onStrokesReplaced = { reported = it }
        ctrl.setTool(Tool.SELECT)
        ctrl.selectAll()
        ctrl.restyleSelection(com.xnotes.core.model.Rgba(255, 0, 0, 255), null, preview = false)
        val changes = reported ?: error("onStrokesReplaced never fired for restyle")
        assertEquals(1, changes.size)
        assertEquals(listOf(originalFp), changes.single().removed.map { StrokeFingerprint.of(it) })
        assertEquals(1, changes.single().added.size)
        assertTrue(changes.single().added.single().config.rgba == com.xnotes.core.model.Rgba(255, 0, 0, 255))
    }
}
