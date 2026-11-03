package com.xnotes.handwrite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import com.xnotes.scan.InkVectorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the scanned-page trace mapping. */
class HandwriteTraceTest {

    private val ink = Rgba(10, 10, 10)

    @Test
    fun `traced bar maps to pen strokes with scale and origin`() {
        val w = 60
        val h = 20
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (y in 9..11 && x in 5..54) 0 else 255
        }
        val opts = InkVectorizer.TraceOptions(threshold = 128, printedText = true, removeRulings = false, closeGaps = false)
        val page = HandwriteTrace.ScanPage(InkVectorizer.vectorize(lum, w, h, opts), w, h)
        assertTrue(page.paths.isNotEmpty())
        val strokes = HandwriteTrace.toStrokes(page, ink, 3.0, origin = Pt(5.0, 7.0), scale = 2.0)
        assertEquals(page.paths.size, strokes.size)
        val s = strokes.maxByOrNull { it.sampleCount }!!
        assertEquals(Tool.PEN, s.tool)
        assertEquals(ink, s.config.rgba)
        assertEquals(0.0, s.smoothScale, 0.0)
        // Render px map 1:2 onto the page from the origin.
        assertTrue(s.xAt(0) >= 5.0 && s.xAt(0) <= 5.0 + 60.0 * 2.0)
        assertTrue(s.sampleCount >= 2)
    }

    @Test
    fun `empty and degenerate traces yield no strokes`() {
        val empty = HandwriteTrace.ScanPage(emptyList(), 10, 10)
        assertTrue(HandwriteTrace.toStrokes(empty, ink, 3.0).isEmpty())
        val dot = HandwriteTrace.ScanPage(
            listOf(InkVectorizer.InkPath(listOf(Pt(3.0, 4.0)), listOf(0.8), 0.0)), 10, 10,
        )
        val strokes = HandwriteTrace.toStrokes(dot, ink, 3.0)
        assertEquals(1, strokes.size)
        assertEquals(1, strokes[0].sampleCount)
        assertEquals(0.8, strokes[0].pAt(0), 1e-6)
    }
}
