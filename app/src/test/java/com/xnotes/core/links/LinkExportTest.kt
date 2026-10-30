package com.xnotes.core.links

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExportTest {

    private fun loaded(doc: Document) = LinkExport.LoadedSource(doc, false)

    private val measurer = FakeTextMeasurer()
    private val red = Rgba(244, 67, 54)
    private val blue = Rgba(64, 156, 255)

    private fun blankPages(n: Int): MutableList<Page> =
        (0 until n).mapTo(mutableListOf()) { Page(400.0, 600.0) }

    private fun link(
        pages: List<Int>,
        targetUri: String = "ext",
        targetName: String = "Other",
        color: Rgba = red,
    ) = NoteLinkItem(Rect(10.0, 10.0, 220.0, 64.0), "Go", targetUri, targetName, pages, color)

    /** The spec's example: 10 pages, page 5 links 3 pages of another file -> 13 pages. */
    @Test fun externalLinkAppendsPagesBelowItsPage() {
        val doc = Document(blankPages(10))
        doc.pages[4].items.add(link(listOf(0, 1, 2)))
        val ext = Document(blankPages(3))
        ext.pages[0].items.add(com.xnotes.core.model.TextItem(com.xnotes.core.geometry.Pt(5.0, 5.0), measurer = measurer, text = "p0"))

        val r = LinkExport.expand(doc, { uri -> if (uri == "ext") loaded(ext) else null }, measurer)

        assertEquals(13, r.pageCount)
        assertEquals(1, r.expandedLinks)
        assertTrue(r.skipped.isEmpty())
        // Anchors keep their size; the original page 6 now sits at index 8.
        assertEquals(400.0, r.document.pages[0].width, 0.0)
        // The three appended pages sit right below page 5 with the link's color frame.
        for (k in 0..2) {
            val p = r.document.pages[5 + k]
            assertEquals(red, p.linkBorder)
        }
        // The original page 6 follows the appended run.
        assertEquals(doc.pages[5].width, r.document.pages[8].width, 0.0)
        // The link is rewired to the same file, pointing at the appended pages.
        val rewired = r.document.pages[4].items.filterIsInstance<NoteLinkItem>().single()
        assertEquals("", rewired.targetUri)
        assertEquals(listOf(5, 6, 7), rewired.pages)
    }

    @Test fun bookmarksFollowTheirPage() {
        val doc = Document(blankPages(10))
        doc.pages[4].items.add(link(listOf(0)))
        doc.bookmarks.add(Bookmark(7, "late"))
        val ext = Document(blankPages(1))

        val r = LinkExport.expand(doc, { _ -> loaded(ext) }, measurer)

        assertEquals(11, r.pageCount)
        assertEquals(8, r.document.bookmarks.single().page)
    }

    @Test fun selfLinkJumpsAreExpandedAndRewired() {
        val doc = Document(blankPages(4))
        doc.pages[0].items.add(NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "Self", "", "Same", listOf(2, 3), blue))

        val r = LinkExport.expand(doc, { _ -> null }, measurer)

        assertEquals(6, r.pageCount)
        val rewired = r.document.pages[0].items.filterIsInstance<NoteLinkItem>().single()
        assertEquals(listOf(1, 2), rewired.pages)
        assertEquals(blue, r.document.pages[1].linkBorder)
        assertEquals(blue, r.document.pages[2].linkBorder)
    }

    @Test fun missingTargetIsSkippedLeavingTheLinkAlone() {
        val doc = Document(blankPages(3))
        doc.pages[1].items.add(link(listOf(0), targetUri = "gone"))

        val r = LinkExport.expand(doc, { _ -> null }, measurer)

        assertEquals(3, r.pageCount)
        assertEquals(0, r.expandedLinks)
        assertEquals(1, r.skipped.size)
        val kept = r.document.pages[1].items.filterIsInstance<NoteLinkItem>().single()
        assertEquals("gone", kept.targetUri)
        assertEquals(listOf(0), kept.pages)
    }

    @Test fun otherSelfLinksRemapOntoShiftedPages() {
        val doc = Document(blankPages(6))
        // Page 2 links external page 0; page 5 self-links original page 3.
        doc.pages[1].items.add(link(listOf(0), color = red))
        doc.pages[4].items.add(NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "S", "", "", listOf(2), blue))
        val ext = Document(blankPages(1))

        // Self-links left in place still follow their page to its new slot.
        val r = LinkExport.expand(doc, { _ -> loaded(ext) }, measurer, expandSelf = false)

        // 6 + 1 appended = 7; original page 3 moved from 2 to 3.
        assertEquals(7, r.pageCount)
        assertEquals(1, r.expandedLinks)
        val external = r.document.pages[1].items.filterIsInstance<NoteLinkItem>().single()
        assertEquals(listOf(2), external.pages)
        val self = r.document.pages[5].items.filterIsInstance<NoteLinkItem>().single()
        assertEquals(listOf(3), self.pages)
    }

    @Test fun externalPdfPageBakesBackgroundAndFreezesStyle() {
        val red = Rgba(244, 67, 54)
        val srcDoc = Document(
            mutableListOf(Page(400.0, 600.0, pdfPage = 7)),
            style = PageStyle(pageColor = red),
        )
        val doc = Document(blankPages(2))
        doc.pages[0].items.add(NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "L", "ext", "Ext", listOf(0), blue))
        val bakedFile = java.io.File("baked-test.png")
        val r = LinkExport.expand(
            doc, { _ -> loaded(srcDoc) }, measurer,
            bakePdfBackground = { _, _ -> LinkExport.BakedBackground(bakedFile, 100, 150) },
        )
        assertEquals(3, r.pageCount)
        assertEquals(1, r.sidecars.size)
        val appended = r.document.pages[1]
        // The PDF no longer travels: it is baked into a full-page image behind the ink.
        assertEquals(null, appended.pdfPage)
        val bg = appended.items.filterIsInstance<ImageItem>().single()
        assertEquals(Rect(0.0, 0.0, 400.0, 600.0), bg.rect)
        assertEquals(100, bg.image.width)
        assertEquals(150, bg.image.height)
        // The source document's paper travelled with the page.
        assertEquals(red, appended.style.pageColor)
    }

    @Test fun unbakedPdfPageKeepsPointingAtItsPdf() {
        val srcDoc = Document(mutableListOf(Page(400.0, 600.0, pdfPage = 7)))
        val doc = Document(blankPages(2))
        doc.pages[0].items.add(NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "L", "ext", "Ext", listOf(0), blue))
        val r = LinkExport.expand(doc, { _ -> loaded(srcDoc) }, measurer, bakePdfBackground = { _, _ -> null })
        assertEquals(3, r.pageCount)
        assertTrue(r.sidecars.isEmpty())
        assertEquals(7, r.document.pages[1].pdfPage)
    }
}
