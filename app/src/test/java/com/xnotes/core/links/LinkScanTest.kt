package com.xnotes.core.links

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkScanTest {

    private val red = Rgba(244, 67, 54)

    @Test fun collectsLinksInPageOrder() {
        val doc = Document(
            mutableListOf(
                Page(400.0, 600.0, mutableListOf(NoteLinkItem(Rect(0.0, 0.0, 10.0, 10.0), "B", "u2", "Two", listOf(1), red))),
                Page(400.0, 600.0),
                Page(400.0, 600.0, mutableListOf(NoteLinkItem(Rect(0.0, 0.0, 10.0, 10.0), "A", "u1", "One", listOf(0, 2)))),
            ),
        )
        val links = NoteLinkScan.of(doc)
        assertEquals(2, links.size)
        assertEquals(0, links[0].sourcePage)
        assertEquals("u2", links[0].targetUri)
        assertEquals(listOf(1), links[0].targetPages)
        assertEquals("B", links[0].label)
        assertEquals(red.toArgb(), links[0].colorArgb)
        assertEquals(2, links[1].sourcePage)
    }

    @Test fun skipsLinksWithoutPages() {
        val doc = Document(
            mutableListOf(
                Page(400.0, 600.0, mutableListOf(NoteLinkItem(Rect(0.0, 0.0, 10.0, 10.0), "Empty", "u", "U", emptyList()))),
            ),
        )
        assertTrue(NoteLinkScan.of(doc).isEmpty())
    }

    @Test fun selfLinksKeepEmptyTarget() {
        val doc = Document(
            mutableListOf(
                Page(400.0, 600.0, mutableListOf(NoteLinkItem(Rect(0.0, 0.0, 10.0, 10.0), "Self", "", "Same", listOf(3)))),
            ),
        )
        val links = NoteLinkScan.of(doc)
        assertEquals(1, links.size)
        assertEquals("", links[0].targetUri)
        assertEquals(listOf(3), links[0].targetPages)
    }
}
