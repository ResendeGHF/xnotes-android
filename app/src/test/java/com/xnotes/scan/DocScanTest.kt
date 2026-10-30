package com.xnotes.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the camera-scan quad handling (pure-Kotlin parts). */
class DocScanTest {

    private fun fillRect(lum: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, v: Int) {
        for (y in t..b) for (x in l..r) lum[y * w + x] = v
    }

    @Test
    fun `detectQuad finds an axis-aligned page`() {
        val w = 200
        val h = 280
        val lum = IntArray(w * h) { 30 } // dark surround
        fillRect(lum, w, h, 20, 30, 179, 249, 235) // bright page
        val q = DocScan.detectQuad(lum, w, h)
        assertNotNull(q)
        q!!
        // Within a few px of the true inset (downsample grid is ~2px here).
        assertTrue("tl=${q.tl}", q.tl.x in 12.0..28.0 && q.tl.y in 22.0..38.0)
        assertTrue("tr=${q.tr}", q.tr.x in 171.0..187.0 && q.tr.y in 22.0..38.0)
        assertTrue("br=${q.br}", q.br.x in 171.0..187.0 && q.br.y in 241.0..257.0)
        assertTrue("bl=${q.bl}", q.bl.x in 12.0..28.0 && q.bl.y in 241.0..257.0)
    }

    @Test
    fun `detectQuad returns null without a paper split`() {
        // Uniform paper: nothing to find.
        assertNull(DocScan.detectQuad(IntArray(100 * 100) { 230 }, 100, 100))
        // Uniform dark: nothing to find.
        assertNull(DocScan.detectQuad(IntArray(100 * 100) { 20 }, 100, 100))
    }

    @Test
    fun `quadOutputSize follows the longer edges`() {
        // 400x300 rectangle: output keeps the aspect, capped at 200.
        val (w, h) = DocScan.quadOutputSize(floatArrayOf(0f, 0f, 400f, 0f, 400f, 300f, 0f, 300f), 200)
        assertEquals(200, w)
        assertEquals(150, h)
    }

    @Test
    fun `quad fractions round-trip`() {
        val q = DocScan.Quad.inset(200, 100)
        val back = DocScan.Quad.fromFractions(q.toFractions(200, 100), 200, 100)
        assertEquals(q.tl.x, back.tl.x, 0.01)
        assertEquals(q.br.y, back.br.y, 0.01)
    }
}
