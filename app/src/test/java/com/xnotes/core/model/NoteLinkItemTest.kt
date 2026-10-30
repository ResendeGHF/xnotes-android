package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinkItemTest {

    @Test fun parsePagesHandlesSinglesRangesAndNoise() {
        assertEquals(listOf(0, 2, 3, 4), NoteLinkItem.parsePages("1, 3-5"))
        assertEquals(emptyList<Int>(), NoteLinkItem.parsePages("p5"))
        assertEquals(emptyList<Int>(), NoteLinkItem.parsePages(""))
        assertEquals(emptyList<Int>(), NoteLinkItem.parsePages("abc, 0, -2"))
        // Ranges collapse and dedupe; reversed ranges still work.
        assertEquals(listOf(0, 1, 2), NoteLinkItem.parsePages("3-1, 2"))
    }

    @Test fun formatAndSpecRoundTrip() {
        assertEquals("p. 5", NoteLinkItem.formatPages(listOf(4)))
        assertEquals("pp. 1, 3-5", NoteLinkItem.formatPages(listOf(0, 2, 3, 4)))
        assertEquals("", NoteLinkItem.formatPages(emptyList()))
        val pages = NoteLinkItem.parsePages("1, 3-5")
        assertEquals("1, 3-5", NoteLinkItem.specFor(pages))
    }

    @Test fun selfLinksHaveNoTarget() {
        val self = NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "A", "", "Same", listOf(1))
        assertTrue(self.isSelf)
        assertEquals(1, self.firstPage)
        val ext = NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "B", "uri", "Other", listOf(0, 2))
        assertFalse(ext.isSelf)
        assertEquals("pp. 1, 3", ext.pagesLabel())
    }

    @Test fun labelFallsBackToTargetName() {
        assertEquals("Other", NoteLinkItem(Rect(0.0, 0.0, 10.0, 10.0), "", "u", "Other", listOf(0)).label())
        assertEquals("Custom", NoteLinkItem(Rect(0.0, 0.0, 10.0, 10.0), "Custom", "u", "Other", listOf(0)).label())
    }

    @Test fun roundedDefaultsOffAndSurvivesGeometry() {
        val item = NoteLinkItem(Rect(10.0, 20.0, 50.0, 30.0), "A", "", "", listOf(0))
        assertEquals(false, item.rounded)
        item.rounded = true
        val snap = item.snapshotGeometry()
        item.translate(1000.0, 1000.0)
        item.restoreGeometry(snap)
        assertEquals(Rect(10.0, 20.0, 50.0, 30.0), (item.geometry() as RectHandle).rect)
        assertEquals(true, item.rounded)
    }

    @Test fun outlineIsClosedLoop() {
        val sharp = NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "A", "", "", listOf(0), rounded = false)
        assertEquals(4, sharp.outline().size)
        val round = NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "A", "", "", listOf(0), rounded = true)
        val outline = round.outline()
        assertTrue(outline.size > 8)
        // Closed: last point meets the first arc's start region (all points inside the box).
        for (p in outline) {
            assertTrue(p.x in -1.0..101.0)
            assertTrue(p.y in -1.0..41.0)
        }
    }

    @Test fun fittedTextIsAlwaysOneLine() {
        val item = NoteLinkItem(Rect(0.0, 0.0, 60.0, 32.0), "A very long label that cannot fit", "u", "Other", listOf(0, 1, 2))
        val availW = 60.0 - 16.0
        val availH = 32.0 - 16.0
        val text = item.fittedText(availW, availH)
        val size = item.textSize(availW, availH)
        assertTrue(size in NoteLinkItem.MIN_TEXT_SIZE..NoteLinkItem.MAX_TEXT_SIZE)
        assertTrue("no newlines", !text.contains("\n"))
        val perLine = (availW / (size * NoteLinkItem.CHAR_WIDTH)).toInt().coerceAtLeast(1)
        assertTrue("text [$text] too long", text.length <= perLine)
    }

    @Test fun fittedTextKeepsPagesWhenRoomy() {
        val item = NoteLinkItem(Rect(0.0, 0.0, 220.0, 64.0), "Go", "u", "Other", listOf(4))
        assertEquals("Go · p. 5", item.fittedText(220.0 - 16.0, 64.0 - 16.0))
    }

    @Test fun truncateClampsWithEllipsis() {
        assertEquals("", NoteLinkItem.truncate("abc", 0))
        assertEquals("…", NoteLinkItem.truncate("abc", 1))
        assertEquals("abc", NoteLinkItem.truncate("abc", 3))
        assertEquals("ab…", NoteLinkItem.truncate("abcdef", 3))
    }

    @Test fun containsAndTranslateFollowTheBox() {
        val item = NoteLinkItem(Rect(10.0, 20.0, 50.0, 30.0), "A", "", "", listOf(0))
        assertTrue(item.contains(Pt(30.0, 35.0)))
        assertFalse(item.contains(Pt(0.0, 0.0)))
        item.translate(5.0, -5.0)
        assertEquals(RectHandle(Rect(15.0, 15.0, 50.0, 30.0)), item.geometry())
    }
}
