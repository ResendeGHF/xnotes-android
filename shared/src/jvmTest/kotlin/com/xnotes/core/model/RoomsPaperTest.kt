package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Rooms.paper]: the page strips a template paints on an imported PDF page — everything but
 * the room openings. Ported from upstream, which paints templates in the margins and room
 * strips only, never over the PDF itself.
 */
class RoomsPaperTest {

    @Test fun paperWithoutRoomsIsTheMargins() {
        val cover = Rect(-10.0, -20.0, 220.0, 340.0)
        val paper = Rooms.paper(cover, emptyList(), 200.0, 300.0)
        val area = paper.sumOf { it.w * it.h }
        assertEquals(220.0 * 340.0 - 200.0 * 300.0, area, 1e-6)
        assertEquals(4, paper.size)
    }

    @Test fun paperTakesInTheRoomStrips() {
        val cover = Rect(0.0, 0.0, 200.0, 350.0)
        val paper = Rooms.paper(cover, listOf(Room(100.0, 50.0)), 200.0, 300.0)
        assertEquals(listOf(Rect(0.0, 100.0, 200.0, 50.0)), paper)
        val side = Rooms.paper(Rect(0.0, 0.0, 210.0, 300.0), listOf(Room(60.0, 10.0, sideways = true)), 200.0, 300.0)
        assertEquals(listOf(Rect(60.0, 0.0, 10.0, 300.0)), side)
    }
}
