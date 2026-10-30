package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A note link and its page's color frame survive a bundle write and read back. */
class NoteLinkCodecTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun roundTrip(doc: Document): Document {
        val bytes = ByteArrayOutputStream().use { out ->
            codec.write(doc, out)
            out.toByteArray()
        }
        return ByteArrayInputStream(bytes).use { input ->
            codec.read(input, pdfDir = null, imageDir = null)
        }
    }

    @Test fun noteLinkRoundTrips() {
        val link = NoteLinkItem(
            rect = Rect(12.0, 34.0, 220.0, 64.0),
            displayName = "Chapter 2",
            targetUri = "content://notes/abc",
            targetName = "Physics.xnote",
            pages = listOf(0, 2, 3),
            color = Rgba(244, 67, 54),
            rounded = true,
        )
        val doc = Document(mutableListOf(Page(400.0, 600.0, mutableListOf(link))))

        val back = roundTrip(doc)
        val item = back.pages.single().items.filterIsInstance<NoteLinkItem>().single()
        assertEquals(link.rect, item.rect)
        assertEquals("Chapter 2", item.displayName)
        assertEquals("content://notes/abc", item.targetUri)
        assertEquals("Physics.xnote", item.targetName)
        assertEquals(listOf(0, 2, 3), item.pages)
        assertEquals(Rgba(244, 67, 54), item.color)
        assertEquals(true, item.rounded)
    }

    @Test fun selfLinkAndEmptyPagesRoundTrip() {
        val link = NoteLinkItem(Rect(0.0, 0.0, 100.0, 40.0), "", "", "", emptyList())
        val doc = Document(mutableListOf(Page(400.0, 600.0, mutableListOf(link))))

        val back = roundTrip(doc)
        val item = back.pages.single().items.filterIsInstance<NoteLinkItem>().single()
        assertTrue(item.isSelf)
        assertTrue(item.pages.isEmpty())
        assertEquals(NoteLinkItem.DEFAULT_COLOR, item.color)
    }

    @Test fun linkBorderRoundTrips() {
        val doc = Document(mutableListOf(Page(400.0, 600.0)))
        doc.pages[0].linkBorder = Rgba(0, 188, 212)

        val back = roundTrip(doc)
        assertEquals(Rgba(0, 188, 212), back.pages.single().linkBorder)
    }

    @Test fun canvasLinkRoundTrips() {
        val canvasCodec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val link = NoteLinkItem(Rect(5.0, 5.0, 200.0, 60.0), "C", "u", "N", listOf(1))
        val doc = com.xnotes.core.infinite.InfiniteDocument()
        doc.addAll(listOf(link))
        val bytes = ByteArrayOutputStream().use { out ->
            canvasCodec.write(doc, out)
            out.toByteArray()
        }
        val back = ByteArrayInputStream(bytes).use { canvasCodec.read(it, imageDir = null) }
        val item = back.items.filterIsInstance<NoteLinkItem>().single()
        assertEquals(link.rect, item.rect)
        assertEquals("C", item.displayName)
        assertEquals(listOf(1), item.pages)
    }
}
