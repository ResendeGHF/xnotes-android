package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.StickyNoteItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.input.PointerEvent
import com.xnotes.input.PointerSample
import com.xnotes.input.SimplePointerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sticky notes on the paged canvas: ink stays note-local, the color dot cycles, collapse hides. */
class StickyInkTest {
    private val scheduler = FakeUiScheduler()
    private val history = History()

    private fun sticky() = StickyNoteItem(Rect(50.0, 50.0, 300.0, 300.0), measurer = FakeTextMeasurer())

    private fun state(item: StickyNoteItem) =
        CanvasState(Document(mutableListOf(Page(400.0, 800.0, mutableListOf(item)))), FakeSurfaceFactory(), TestPalette()).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }

    private fun controller(st: CanvasState) =
        InteractionController(st, history, FakeTextMeasurer(), requestRender = {}, scheduler = scheduler)

    private fun CanvasState.viewportOf(x: Double, y: Double): Pt = contentToViewport(fromPageSpace(0, Pt(x, y)))

    private fun down(ctrl: InteractionController, st: CanvasState, x: Double, y: Double, tool: Int, time: Long) =
        ctrl.onTouch(SimplePointerEvent(PointerEvent.ACTION_DOWN, listOf(PointerSample(0, st.viewportOf(x, y).x.toFloat(), st.viewportOf(x, y).y.toFloat(), 0.6f, tool)), time))

    private fun move(ctrl: InteractionController, st: CanvasState, x: Double, y: Double, tool: Int, time: Long) =
        ctrl.onTouch(SimplePointerEvent(PointerEvent.ACTION_MOVE, listOf(PointerSample(0, st.viewportOf(x, y).x.toFloat(), st.viewportOf(x, y).y.toFloat(), 0.6f, tool)), time))

    private fun up(ctrl: InteractionController, st: CanvasState, x: Double, y: Double, tool: Int, time: Long) =
        ctrl.onTouch(SimplePointerEvent(PointerEvent.ACTION_UP, listOf(PointerSample(0, st.viewportOf(x, y).x.toFloat(), st.viewportOf(x, y).y.toFloat(), 0.6f, tool)), time))

    @Test fun penInsideExpandedStickyWritesNoteLocalInk() {
        val item = sticky()
        val st = state(item)
        val ctrl = controller(st)
        ctrl.setTool(Tool.PEN)
        val stylus = PointerEvent.TOOL_TYPE_STYLUS

        down(ctrl, st, 100.0, 100.0, stylus, 0)
        move(ctrl, st, 150.0, 125.0, stylus, 16)
        move(ctrl, st, 200.0, 150.0, stylus, 32)
        up(ctrl, st, 200.0, 150.0, stylus, 40)

        // Nothing lands on the page itself.
        assertEquals(listOf(item), st.document.pages[0].items)
        // The run lands in the note, in note-local coordinates.
        assertEquals(1, item.ink.size)
        val first = item.ink[0].samples.first()
        assertTrue("ink is note-local, got $first", first.x in 45.0..55.0 && first.y in 45.0..55.0)
        assertTrue(history.canUndo)
    }

    @Test fun collapsingHidesNoteInkButKeepsIt() {
        val item = sticky()
        val st = state(item)
        val ctrl = controller(st)
        ctrl.setTool(Tool.PEN)
        val stylus = PointerEvent.TOOL_TYPE_STYLUS
        down(ctrl, st, 100.0, 100.0, stylus, 0)
        up(ctrl, st, 100.0, 100.0, stylus, 10)

        ctrl.toggleStickyCollapsed(item)
        assertTrue(item.collapsed)
        assertEquals(1, item.ink.size)
        // The paper's point is outside the collapsed icon now.
        assertTrue(!item.contains(Pt(100.0, 100.0)))
        ctrl.toggleStickyCollapsed(item)
        assertTrue(!item.collapsed)
        assertTrue(item.contains(Pt(100.0, 100.0)))
    }

    @Test fun colorDotCyclesTenPapers() {
        val item = sticky()
        val st = state(item)
        val ctrl = controller(st)
        // The dot's centre: right of the collapse x, in the control row.
        val dot = Pt(item.rect.x + 28.0 + 14.0, item.rect.y + 14.0)
        assertTrue(item.isColorHit(dot))
        val seen = LinkedHashSet<String>()
        repeat(9) {
            assertTrue(ctrl.stickyColorTapAt(0, dot))
            seen += "${item.color.r},${item.color.g},${item.color.b}"
        }
        assertEquals("nine papers before the wrap", 9, seen.size)
        // The tenth tap wraps to the first paper.
        assertTrue(ctrl.stickyColorTapAt(0, dot))
        assertEquals(StickyNoteItem.CYCLE.first(), item.color)
    }

    @Test fun eraserTakesNoteInkLeavingThePaper() {
        val item = sticky()
        val st = state(item)
        val ctrl = controller(st)
        val stylus = PointerEvent.TOOL_TYPE_STYLUS
        ctrl.setTool(Tool.PEN)
        down(ctrl, st, 100.0, 100.0, stylus, 0)
        move(ctrl, st, 200.0, 150.0, stylus, 16)
        up(ctrl, st, 200.0, 150.0, stylus, 24)
        assertEquals(1, item.ink.size)

        // Erase right on the run's first sample (page coords).
        ctrl.setTool(Tool.ERASER)
        down(ctrl, st, 100.0, 100.0, stylus, 100)
        move(ctrl, st, 100.0, 100.0, stylus, 116)
        up(ctrl, st, 100.0, 100.0, stylus, 124)
        assertTrue("note ink erased", item.ink.isEmpty())
        // The paper itself survives the eraser.
        assertEquals(listOf(item), st.document.pages[0].items)
    }
}
