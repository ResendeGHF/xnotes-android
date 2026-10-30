package com.xnotes.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the dependency-free scan-to-stroke vectorizer. */
class InkVectorizerTest {

    private fun lumOf(w: Int, h: Int, ink: (x: Int, y: Int) -> Boolean): IntArray {
        // White paper (255), black ink (0).
        return IntArray(w * h) { i -> if (ink(i % w, i / w)) 0 else 255 }
    }

    @Test
    fun `otsu separates black ink from white paper`() {
        val lum = IntArray(100) { if (it < 30) 10 else 240 }
        val t = InkVectorizer.otsuThreshold(lum)
        assertTrue("threshold $t should sit between the two peaks", t in 11..239)
    }

    @Test
    fun `horizontal line traces to one long path`() {
        val w = 60
        val h = 20
        val lum = lumOf(w, h) { x, y -> y in 9..11 && x in 5..54 }
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertTrue("expected paths, got ${paths.size}", paths.isNotEmpty())
        val longest = paths.maxByOrNull { it.lengthPx }!!
        // A 50px bar thins to a ~50px centreline (junction splits may shorten it).
        assertTrue("longest ${longest.lengthPx} too short", longest.lengthPx > 30.0)
        // Endpoints near the bar's ends.
        val xs = longest.points.map { it.x }
        assertTrue(xs.min() < 15.0 && xs.max() > 45.0)
    }

    @Test
    fun `blank page yields no paths`() {
        val w = 40
        val h = 40
        val lum = IntArray(w * h) { 255 }
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertEquals(0, paths.size)
    }

    @Test
    fun `full black page aborts instead of hanging`() {
        val w = 40
        val h = 40
        val lum = IntArray(w * h) { 0 }
        val paths = InkVectorizer.vectorize(
            lum, w, h,
            InkVectorizer.TraceOptions(threshold = 128, maxStrokes = 6000),
        )
        // More ink than paper: the threshold is certainly wrong, so bail out fast.
        assertEquals(0, paths.size)
    }

    @Test
    fun `despeckle removes lone pixels`() {
        val w = 20
        val h = 20
        val ink = BooleanArray(w * h)
        ink[5 * w + 5] = true // single speck
        // A solid 8x8 block stays.
        for (y in 10..17) for (x in 10..17) ink[y * w + x] = true
        val out = InkVectorizer.despeckle(ink, w, h, minSize = 12)
        assertTrue(!out[5 * w + 5])
        assertTrue(out[14 * w + 14])
    }

    @Test
    fun `sauvola finds ink under a light gradient`() {
        val w = 120
        val h = 30
        // Paper shading from 110 (left) to 229 (right), with a dark bar across.
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (y in 13..16 && x in 10..109) 20 else 110 + x
        }
        // A fixed global level drowns the shadowed side: background darker than
        // the level counts as ink (more than double the bar's own pixels).
        val globalInk = InkVectorizer.binarize(lum, 128, false).count { it }
        assertTrue("shadowed paper should fool a global level, got $globalInk", globalInk > 800)
        val mask = InkVectorizer.sauvolaBinarize(lum, w, h)
        val localInk = mask.count { it }
        // Just the bar (400 px) plus a little edge tolerance.
        assertTrue("sauvola should isolate the bar, got $localInk", localInk in 200..900)
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(denoisePx = 0))
        val longest = paths.maxByOrNull { it.lengthPx }!!
        assertTrue("bar should trace end to end, got ${longest.lengthPx}", longest.lengthPx > 70.0)
    }

    @Test
    fun `autoEnhance levels a gradient and keeps ink dark`() {
        val w = 60
        val h = 20
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (y in 8..11) 10 else 100 + x
        }
        val out = InkVectorizer.autoEnhance(lum, w, h)
        val bg = out[5 * w + 5] // paper, no ink
        val ink = out[9 * w + 30] // inside the bar
        assertTrue("paper should come back bright, got $bg", bg >= 200)
        assertTrue("ink should stay dark, got $ink", ink <= 60)
    }

    @Test
    fun `autoEnhance leaves a clean gradient nearly flat`() {
        val w = 60
        val h = 20
        val lum = IntArray(w * h) { i -> 100 + (i % w) }
        val out = InkVectorizer.autoEnhance(lum, w, h)
        assertTrue("levelled paper should be near-uniform", out.max() - out.min() <= 12)
    }

    @Test
    fun `distanceTransform measures the stroke radius`() {
        val w = 11
        val h = 11
        val ink = BooleanArray(w * h) { i -> (i % w) in 2..8 }
        val d = InkVectorizer.distanceTransform(ink, w, h)
        assertEquals(4.0, d[5 * w + 5].toDouble(), 0.6)
        assertEquals(1.0, d[5 * w + 2].toDouble(), 0.01)
        assertEquals(0.0, d[5 * w + 0].toDouble(), 0.0)
        // An isolated pixel reads radius one.
        val lone = BooleanArray(9) { it == 4 }
        assertEquals(1.0, InkVectorizer.distanceTransform(lone, 3, 3)[4].toDouble(), 0.01)
    }

    @Test
    fun `a surviving speck traces as a dot path`() {
        val w = 20
        val h = 20
        val lum = IntArray(w * h) { 255 }
        for (y in 9..11) for (x in 9..11) lum[y * w + x] = 0
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertEquals(1, paths.size)
        assertEquals(1, paths[0].points.size)
    }

    @Test
    fun `paths carry pressures from the stroke radius`() {
        val w = 60
        val h = 20
        val lum = IntArray(w * h) { 255 }
        for (y in 8..12) for (x in 5..54) lum[y * w + x] = 0
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertTrue(paths.isNotEmpty())
        for (p in paths) {
            assertEquals(p.points.size, p.pressures.size)
            assertTrue(p.pressures.all { it in 0.0..1.0 })
        }
    }

    @Test
    fun `growFromSeeds bridges weak gaps and drops isolated weak`() {
        val w = 9
        val h = 5
        val strong = BooleanArray(w * h)
        val weak = BooleanArray(w * h)
        // Strong seeds at both ends of row 2, a weak bridge between them.
        strong[2 * w + 1] = true
        strong[2 * w + 7] = true
        for (x in 1..7) weak[2 * w + x] = true
        // An isolated weak speck far from any seed.
        weak[4 * w + 4] = true
        val out = InkVectorizer.growFromSeeds(strong, weak, w, h)
        for (x in 1..7) assertTrue("bridge x=$x kept", out[2 * w + x])
        assertTrue("isolated weak dropped", !out[4 * w + 4])
    }

    @Test
    fun `hysteresis keeps a faint tail that plain threshold drops`() {
        val w = 60
        val h = 30
        val lum = IntArray(w * h) { 245 }
        // Dark bar, then a faint extension of the same stroke.
        for (y in 13..16) {
            for (x in 5..25) lum[y * w + x] = 0
            for (x in 26..50) lum[y * w + x] = 170
        }
        val plain = InkVectorizer.vectorize(
            lum, w, h,
            InkVectorizer.TraceOptions(threshold = 100, hysteresis = false, denoisePx = 0),
        )
        val grown = InkVectorizer.vectorize(
            lum, w, h,
            InkVectorizer.TraceOptions(threshold = 100, hysteresis = true, weakExtra = 80, denoisePx = 0),
        )
        val plainLongest = plain.maxByOrNull { it.lengthPx }?.lengthPx ?: 0.0
        val grownLongest = grown.maxByOrNull { it.lengthPx }?.lengthPx ?: 0.0
        assertTrue("plain drops the tail at $plainLongest", plainLongest < 25.0)
        assertTrue("hysteresis keeps the tail at $grownLongest", grownLongest > 35.0)
    }

    @Test
    fun `sample budget keeps dots and longest paths first`() {
        val w = 60
        val h = 30
        val lum = IntArray(w * h) { 255 }
        // A long bar plus an isolated dot.
        for (y in 13..16) for (x in 5..54) lum[y * w + x] = 0
        lum[25 * w + 3] = 0
        val paths = InkVectorizer.vectorize(
            lum, w, h,
            InkVectorizer.TraceOptions(threshold = 128, denoisePx = 0, maxTotalSamples = 2),
        )
        val total = paths.sumOf { it.points.size }
        assertTrue("budget respected, got $total", total <= 2)
        assertTrue("dots survive the budget", paths.any { it.points.size == 1 })
    }

    @Test
    fun `simplify keeps endpoints and drops collinear middles`() {
        val pts = (0..10).map { com.xnotes.core.geometry.Pt(it.toDouble(), 0.0) }
        val simp = InkVectorizer.simplify(pts, eps = 0.5)
        assertEquals(2, simp.size)
        assertEquals(0.0, simp.first().x, 0.0)
        assertEquals(10.0, simp.last().x, 0.0)
    }

    @Test
    fun `thin keeps a diagonal connected`() {
        val w = 30
        val h = 30
        val lum = lumOf(w, h) { x, y -> kotlin.math.abs(x - y) <= 1 && x in 4..25 }
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.maxByOrNull { it.lengthPx }!!.lengthPx > 15.0)
    }

    @Test
    fun `invert traces light ink on dark source`() {
        val w = 40
        val h = 20
        // White bar on near-black.
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (y in 9..11 && x in 5..34) 250 else 10
        }
        val inverted = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128, invert = true))
        // The bar comes out as one long path…
        assertTrue("inverted should find the bar", inverted.any { it.lengthPx > 15.0 })
        // …while the default polarity (correctly) refuses: the whole dark
        // background would be ink, so the degenerate guard bails out instead.
        val normal = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertTrue("normal polarity must abort on a dark page, got ${normal.size}", normal.isEmpty())
    }
}
