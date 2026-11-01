package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.EditStickyInk
import com.xnotes.core.history.EditStickyText
import com.xnotes.core.history.RecolorSticky
import com.xnotes.core.history.SetStickyCollapsed
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.StickyNoteItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.deepCopy
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class StickyNoteTest {

    private val measurer = FakeTextMeasurer()
    private val canvasCodec = CanvasCodec(FakeImageCodec(), measurer)
    private val docCodec = DocumentCodec(FakeImageCodec(), measurer)

    private fun sticky(
        color: Rgba = StickyNoteItem.DEFAULT_COLOR,
        collapsed: Boolean = false,
        text: String = "hello",
    ): StickyNoteItem {
        val item = StickyNoteItem(Rect(10.0, 20.0, 300.0, 300.0), color, collapsed, text, measurer = measurer)
        item.ink.add(
            Stroke(Tool.PEN, ToolConfig(rgba = Rgba(10, 20, 30, 255)), mutableListOf(Sample(1.0, 2.0, 1.0), Sample(9.0, 8.0, 1.0))),
        )
        return item
    }

    // --- model ---

    @Test fun collapsedBoundsAreASmallDiscreteIconAtThePaperTopLeft() {
        val item = sticky()
        assertEquals(Rect(10.0, 20.0, 300.0, 300.0), item.bounds())
        item.collapsed = true
        val icon = item.bounds()
        assertEquals(10.0, icon.x, 1e-9)
        assertEquals(20.0, icon.y, 1e-9)
        assertEquals(StickyNoteItem.ICON_SIZE, icon.w, 1e-9)
        assertEquals(StickyNoteItem.ICON_SIZE, icon.h, 1e-9)
        assertTrue(icon.w < item.rect.w && icon.h < item.rect.h)
    }

    @Test fun collapseAndColorControlsSitSideBySideAtTopLeft() {
        val item = sticky()
        val x = item.collapseHit()
        val dot = item.colorHit()
        assertEquals(item.rect.x, x.x, 1e-9)
        assertEquals(item.rect.y, x.y, 1e-9)
        assertEquals(x.right, dot.x, 1e-9)
        assertEquals(x.y, dot.y, 1e-9)
        assertTrue(item.isCollapseHit(Pt(item.rect.x + 4.0, item.rect.y + 4.0)))
        assertTrue(item.isColorHit(Pt(item.rect.x + StickyNoteItem.CONTROL_HIT + 4.0, item.rect.y + 4.0)))
        assertFalse(item.isCollapseHit(Pt(item.rect.x + StickyNoteItem.CONTROL_HIT + 4.0, item.rect.y + 4.0)))
    }

    @Test fun collapsedIconHasThePaperColorAsItsOutline() {
        val item = sticky(color = Rgba(255, 205, 210, 255))
        assertEquals(item.paperColor(), item.edgeColor())
    }

    @Test fun iconOutlineIsARoundedFoldedSilhouette() {
        val outline = StickyNoteItem.iconOutline(Rect(0.0, 0.0, StickyNoteItem.ICON_SIZE, StickyNoteItem.ICON_SIZE))
        assertTrue(outline.size > 8)
        val box = Rect.bounding(outline)
        assertEquals(0.0, box.x, 1e-9)
        assertEquals(0.0, box.y, 1e-9)
        assertEquals(StickyNoteItem.ICON_SIZE, box.w, 1e-9)
        assertEquals(StickyNoteItem.ICON_SIZE, box.h, 1e-9)
    }

    @Test fun moveKeepsNoteLocalInk() {
        val item = sticky()
        item.translate(5.0, -7.0)
        assertEquals(Rect(15.0, 13.0, 300.0, 300.0), item.rect)
        assertEquals(1.0, item.ink.single().samples[0].x, 1e-9)
    }

    @Test fun deepCopyDuplicatesInk() {
        val item = sticky()
        val copy = item.deepCopy(measurer) as StickyNoteItem
        assertEquals(item.text, copy.text)
        assertEquals(item.color, copy.color)
        assertEquals(item.collapsed, copy.collapsed)
        copy.ink.clear()
        assertEquals(1, item.ink.size)
    }

    // --- history ---

    @Test fun stickyCommandsRoundTrip() {
        val item = sticky()
        EditStickyText(item, "hello", "bye").redo()
        assertEquals("bye", item.text)
        EditStickyText(item, "hello", "bye").undo()
        assertEquals("hello", item.text)

        RecolorSticky(listOf(RecolorSticky.Entry(item, item.color, Rgba(1, 2, 3, 255)))).redo()
        assertEquals(Rgba(1, 2, 3, 255), item.color)

        SetStickyCollapsed(item, true).redo()
        assertTrue(item.collapsed)
        SetStickyCollapsed(item, false).redo()
        assertFalse(item.collapsed)

        val before = item.ink.map { Stroke(it) }
        val after = emptyList<Stroke>()
        EditStickyInk(item, before, after).redo()
        assertTrue(item.ink.isEmpty())
        EditStickyInk(item, before, after).undo()
        assertEquals(1, item.ink.size)
    }

    // --- canvas codec ---

    private fun canvasRoundTrip(item: StickyNoteItem): StickyNoteItem {
        val doc = InfiniteDocument(dpi = 150)
        doc.add(item)
        val bytes = ByteArrayOutputStream().also { canvasCodec.write(doc, it) }.toByteArray()
        val dir = Files.createTempDirectory("sticky-canvas").toFile()
        return canvasCodec.read(ByteArrayInputStream(bytes), dir).items.single() as StickyNoteItem
    }

    @Test fun stickyRoundTripsOnACanvas() {
        val back = canvasRoundTrip(sticky(color = Rgba(255, 205, 210, 255), collapsed = true))
        assertEquals(Rect(10.0, 20.0, 300.0, 300.0), back.rect)
        assertEquals(Rgba(255, 205, 210, 255), back.color)
        assertTrue(back.collapsed)
        assertEquals("hello", back.text)
        assertEquals(1, back.ink.size)
        assertEquals(9.0, back.ink.single().samples[1].x, 1e-9)
    }

    @Test fun stickyLoadsForgivingly() {
        val manifest = """{"format":"xcanvas","items":[{"kind":"sticky","rect":[0,0,-5,10]}]}"""
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json").apply { method = java.util.zip.ZipEntry.DEFLATED })
            zos.write(manifest.toByteArray())
            zos.closeEntry()
        }
        assertTrue(canvasCodec.read(ByteArrayInputStream(out.toByteArray())).isEmpty)
    }

    @Test fun stickyMeshCarriesPaperTextAndInk() {
        val item = sticky()
        val meshed = ItemMesher.mesh(item)!!
        assertFalse(meshed.isEmpty)
        assertEquals(item.paintBounds(), meshed.bounds)
        // Paper + border + fold + text + one ink stroke: more than the paper alone.
        assertTrue(meshed.parts.size >= 4)
        val collapsed = sticky(collapsed = true)
        val iconMeshed = ItemMesher.mesh(collapsed)!!
        assertFalse(iconMeshed.isEmpty)
        assertEquals(collapsed.paintBounds(), iconMeshed.bounds)
    }

    // --- document codec ---

    @Test fun stickyRoundTripsOnAPage() {
        val doc = Document(pages = mutableListOf(Page(600.0, 800.0, mutableListOf(sticky()))), dpi = 150)
        val bytes = ByteArrayOutputStream().also { docCodec.write(doc, it) }.toByteArray()
        val dir = Files.createTempDirectory("sticky-doc").toFile()
        val back = docCodec.read(ByteArrayInputStream(bytes), pdfDir = dir, imageDir = dir)
        val item = back.pages.single().items.single() as StickyNoteItem
        assertEquals(Rect(10.0, 20.0, 300.0, 300.0), item.rect)
        assertEquals("hello", item.text)
        assertEquals(1, item.ink.size)
        assertFalse(item.collapsed)
    }
}
