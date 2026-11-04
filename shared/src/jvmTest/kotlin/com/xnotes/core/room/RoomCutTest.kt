package com.xnotes.core.room

import com.xnotes.core.model.CutEdges
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Room
import com.xnotes.core.model.Rooms
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.insets
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCutTest {
    private fun pen(vararg pts: Double): Stroke {
        val s = Stroke(Tool.PEN, ToolConfig(baseWidth = 4.0), emptyList())
        val xs = DoubleArray(pts.size / 3)
        val ys = DoubleArray(pts.size / 3)
        val ps = DoubleArray(pts.size / 3) { 1.0 }
        for (i in xs.indices) {
            xs[i] = pts[3 * i]
            ys[i] = pts[3 * i + 1]
        }
        s.setSamples(xs, ys, ps, null, xs.size)
        return s
    }

    private fun pageWith(vararg strokes: Stroke): Page {
        val page = Page(600.0, 800.0)
        page.items.addAll(strokes)
        return page
    }

    @Test fun roomsOpenCloseArithmetic() {
        val rooms = Rooms.opened(emptyList(), 100.0, 40.0)
        assertEquals(listOf(Room(100.0, 40.0)), rooms)
        // A cut inside a room grows it.
        assertEquals(listOf(Room(100.0, 60.0)), Rooms.opened(rooms, 120.0, 20.0))
        // Closing a strip shrinks it.
        assertEquals(listOf(Room(100.0, 30.0)), Rooms.closed(listOf(Room(100.0, 60.0)), Room(100.0, 60.0), 100.0, 130.0))
        // Closing all of it removes it.
        assertTrue(Rooms.closed(listOf(Room(100.0, 60.0)), Room(100.0, 60.0), 0.0, 1000.0).isEmpty())
        // Source/display round trip.
        assertEquals(120.0, Rooms.toSource(listOf(Room(100.0, 40.0)), 160.0, false), 1e-9)
        assertEquals(160.0, Rooms.toDisplay(listOf(Room(100.0, 40.0)), 120.0, false), 1e-9)
    }

    @Test fun openMovesBelowAndCutsCrossing() {
        val above = pen(10.0, 10.0, 1.0, 20.0, 20.0, 1.0)
        val below = pen(10.0, 200.0, 1.0, 20.0, 210.0, 1.0)
        val cross = pen(10.0, 90.0, 1.0, 20.0, 110.0, 1.0)
        val page = pageWith(above, below, cross)
        val doc = Document(mutableListOf(page))
        val cmd = RoomCut.open(page, 100.0, 40.0) ?: error("no room opened")
        // The edit lands with the command; redo/undo replay it.
        assertEquals(840.0, page.height, 1e-9)
        assertEquals(listOf(Room(100.0, 40.0)), page.rooms)
        // Wholly past moved on by the room.
        assertEquals(240.0, below.yAt(0), 1e-9)
        // Wholly above stayed.
        assertEquals(10.0, above.yAt(0), 1e-9)
        // Crossing ink was cut: the near piece keeps the page.
        assertTrue(cross.cuts.isNotEmpty())
        // Undo restores everything.
        cmd.undo()
        assertEquals(800.0, page.height, 1e-9)
        assertTrue(page.rooms.isEmpty())
        assertTrue(cross.cuts.isEmpty())
        assertEquals(200.0, below.yAt(0), 1e-9)
        // And redo replays it.
        cmd.redo()
        assertEquals(840.0, page.height, 1e-9)
        assertEquals(240.0, below.yAt(0), 1e-9)
        cmd.undo()
    }

    @Test fun closeDropsStripAndMovesBack() {
        val page = pageWith(pen(10.0, 10.0, 1.0, 20.0, 20.0, 1.0), pen(10.0, 200.0, 1.0, 20.0, 210.0, 1.0))
        val open = RoomCut.open(page, 100.0, 40.0) ?: error("no room opened")
        assertEquals(840.0, page.height, 1e-9)
        val close = RoomCut.close(page, page.rooms.single(), 100.0, 140.0) ?: error("no room closed")
        assertEquals(800.0, page.height, 1e-9)
        assertTrue(page.rooms.isEmpty())
        // Both edits undo cleanly.
        close.undo()
        assertEquals(840.0, page.height, 1e-9)
        open.undo()
        assertEquals(800.0, page.height, 1e-9)
    }

    @Test fun cutStrokesShowOnlyThroughEdges() {
        val s = pen(0.0, 0.0, 1.0, 0.0, 100.0, 1.0)
        s.cuts = CutEdges.with(emptyList(), com.xnotes.core.model.CutEdge.along(50.0, false, keepAfter = true, near = 0.0))
        // Point above the cut shows, point below does not.
        assertTrue(CutEdges.keeps(s.cuts, 0.0, 80.0))
        assertTrue(!CutEdges.keeps(s.cuts, 0.0, 20.0))
        // Bounds shrink to what shows.
        val b = s.bounds()
        assertTrue(b.top >= 50.0 - s.config.baseWidth)
    }

    @Test fun pageInsetsAvailable() {
        val doc = Document(mutableListOf(Page(600.0, 800.0)))
        val ins = doc.pages[0].insets(doc)
        assertTrue(ins.top >= 0.0 && ins.bottom >= 0.0)
        assertTrue(RoomCut.maxOpen(doc.pages[0], doc) > 0.0)
    }
}
