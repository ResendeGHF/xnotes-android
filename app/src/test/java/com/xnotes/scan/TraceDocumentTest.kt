package com.xnotes.scan

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for turning traced paths into a note. */
class TraceDocumentTest {

    private fun page(vararg pts: Pt) = InkVectorizer.InkPath(points = pts.toList(), lengthPx = InkVectorizer.pathLength(pts.toList()))

    @Test
    fun `build maps working pixels onto note pages without a background`() {
        val traced = TraceDocument.TracedPage(
            paths = listOf(page(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 50.0))),
            workW = 200,
            workH = 100,
        )
        val doc = TraceDocument.build(listOf(traced), dpi = 200, pageWidthPx = 1000.0, ink = Rgba(1, 2, 3), inkWidthPx = 3.0)
        assertEquals(1, doc.pages.size)
        // Source is 2:1, so the page keeps that aspect.
        assertEquals(1000.0, doc.pages[0].width, 0.001)
        assertEquals(500.0, doc.pages[0].height, 0.001)
        // The scan background is discarded: editable strokes only, no PDF.
        assertNull(doc.pdfFile)
        assertEquals(1, doc.pages[0].items.size)
        val stroke = doc.pages[0].items[0] as com.xnotes.core.model.Stroke
        assertEquals(3, stroke.sampleCount)
        // (100, 50) working px scales to (500, 250) page px.
        assertEquals(500.0, stroke.xAt(1), 0.01)
        assertEquals(0.0, stroke.yAt(1), 0.01)
        assertEquals(500.0, stroke.xAt(2), 0.01)
        assertEquals(250.0, stroke.yAt(2), 0.01)
        assertEquals(Rgba(1, 2, 3), stroke.config.rgba)
    }

    @Test
    fun `one note page per source page`() {
        val p = TraceDocument.TracedPage(listOf(page(Pt(0.0, 0.0), Pt(10.0, 10.0))), 100, 100)
        val doc = TraceDocument.build(listOf(p, p, p), dpi = 200, pageWidthPx = 800.0, ink = Rgba(0, 0, 0), inkWidthPx = 3.0)
        assertEquals(3, doc.pages.size)
        assertTrue(doc.pages.all { it.items.size == 1 })
    }

    @Test
    fun `empty trace still yields a page`() {
        val doc = TraceDocument.build(emptyList(), dpi = 200, pageWidthPx = 800.0, ink = Rgba(0, 0, 0), inkWidthPx = 3.0)
        assertEquals(1, doc.pages.size)
    }

    @Test
    fun `a dot path becomes a dot stroke with its pressure`() {
        val traced = TraceDocument.TracedPage(
            paths = listOf(InkVectorizer.InkPath(listOf(Pt(50.0, 25.0)), listOf(0.9), 0.0)),
            workW = 100,
            workH = 100,
        )
        val doc = TraceDocument.build(listOf(traced), dpi = 200, pageWidthPx = 800.0, ink = Rgba(0, 0, 0), inkWidthPx = 3.0)
        val stroke = doc.pages[0].items.single() as com.xnotes.core.model.Stroke
        assertEquals(1, stroke.sampleCount)
        assertEquals(400.0, stroke.xAt(0), 0.01)
        assertEquals(200.0, stroke.yAt(0), 0.01)
        assertEquals(0.9, stroke.pAt(0), 0.01)
    }

    @Test
    fun `non-finite samples never reach the note`() {
        val traced = TraceDocument.TracedPage(
            paths = listOf(
                InkVectorizer.InkPath(
                    listOf(Pt(Double.NaN, 0.0), Pt(10.0, 10.0)),
                    listOf(0.5, 0.5),
                    14.0,
                ),
                InkVectorizer.InkPath(
                    listOf(Pt(Double.NaN, Double.NaN)),
                    listOf(0.5),
                    0.0,
                ),
            ),
            workW = 100,
            workH = 100,
        )
        val doc = TraceDocument.build(listOf(traced), dpi = 200, pageWidthPx = 800.0, ink = Rgba(0, 0, 0), inkWidthPx = 3.0)
        // The all-NaN path is dropped; the mixed one keeps only its finite sample.
        assertEquals(1, doc.pages[0].items.size)
        val stroke = doc.pages[0].items[0] as com.xnotes.core.model.Stroke
        assertEquals(1, stroke.sampleCount)
        assertTrue(stroke.xAt(0).isFinite())
    }
}
