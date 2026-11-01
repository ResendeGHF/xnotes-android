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
        // Not a ruling: the bar is the subject, so ruling removal stays off.
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128, removeRulings = false))
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
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(denoisePx = 0, removeRulings = false))
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
            InkVectorizer.TraceOptions(threshold = 100, hysteresis = false, denoisePx = 0, removeRulings = false),
        )
        val grown = InkVectorizer.vectorize(
            lum, w, h,
            InkVectorizer.TraceOptions(threshold = 100, hysteresis = true, weakExtra = 80, denoisePx = 0, removeRulings = false),
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
        val inverted = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128, invert = true, removeRulings = false))
        // The bar comes out as one long path…
        assertTrue("inverted should find the bar", inverted.any { it.lengthPx > 15.0 })
        // …while the default polarity (correctly) refuses: the whole dark
        // background would be ink, so the degenerate guard bails out instead.
        val normal = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertTrue("normal polarity must abort on a dark page, got ${normal.size}", normal.isEmpty())
    }

    @Test
    fun `staircase curve is one junction-free through-line`() {
        // A diagonal staircase: raw neighbour counts call every step a junction
        // (3 neighbours), but topologically it is one curve (2 arms each).
        val w = 30
        val h = 30
        val skel = BooleanArray(w * h)
        for (i in 4..25) skel[i * w + i] = true
        val mid = 15 * w + 15
        assertEquals(2, InkVectorizer.skeletonArms(skel, w, h, mid))
        val paths = InkVectorizer.trace(skel, w, h)
        assertEquals("staircase should trace as one path, got ${paths.size}", 1, paths.size)
        assertTrue(paths[0].size > 15)
    }

    @Test
    fun `true Y junction still splits into three`() {
        // A real branch point (3 separate arms) must keep splitting.
        val w = 30
        val h = 30
        val skel = BooleanArray(w * h)
        for (x in 5..25) skel[15 * w + x] = true // horizontal bar
        for (y in 15..25) skel[y * w + 15] = true // vertical spur down
        assertEquals(3, InkVectorizer.skeletonArms(skel, w, h, 15 * w + 15))
        val paths = InkVectorizer.trace(skel, w, h)
        assertTrue("Y junction should split, got ${paths.size}", paths.size >= 3)
    }

    @Test
    fun `pruneSpurs cuts a spur but keeps dots and pen tips`() {
        val w = 30
        val h = 30
        // An L: long bar with a 3px spur in the middle.
        val skel = BooleanArray(w * h)
        for (x in 4..25) skel[15 * w + x] = true
        for (y in 15..17) skel[y * w + 12] = true // 3px spur down (tip at y=17)
        val pruned = InkVectorizer.pruneSpurs(skel, w, h, maxLen = 5)
        assertTrue("spur tip removed", !pruned[17 * w + 12])
        assertTrue("bar survives", pruned[15 * w + 4] && pruned[15 * w + 25])
        // An isolated 3px dash (no junction) is ink, not a spur: kept whole.
        val lone = BooleanArray(w * h)
        lone[10 * w + 10] = true
        lone[10 * w + 11] = true
        lone[10 * w + 12] = true
        val kept = InkVectorizer.pruneSpurs(lone, w, h, maxLen = 5)
        assertTrue(kept[10 * w + 10] && kept[10 * w + 12])
    }

    @Test
    fun `closeMask bridges a 1px gap`() {
        val w = 20
        val h = 5
        val ink = BooleanArray(w * h) { i -> (i / w) == 2 && (i % w) in 2..17 }
        ink[2 * w + 10] = false // 1px gap in a horizontal stroke
        val closed = InkVectorizer.closeMask(ink, w, h)
        assertTrue("gap bridged", closed[2 * w + 10])
        assertTrue("ends stay open", !closed[2 * w + 0] && !closed[2 * w + 19])
    }

    @Test
    fun `removeRulings erases a full-span line but keeps an underline`() {
        val w = 100
        val h = 30
        val ink = BooleanArray(w * h)
        for (x in 0 until w) ink[5 * w + x] = true // full-width ruled line
        for (x in 10..29) ink[20 * w + x] = true // short underline (20px)
        val out = InkVectorizer.removeRulings(ink, w, h, spanFrac = 0.5, maxThick = 4)
        assertTrue("ruled line erased", (0 until w).none { out[5 * w + it] })
        assertTrue("underline kept", (10..29).all { out[20 * w + it] })
    }

    @Test
    fun `removeRulings keeps text crossing the line`() {
        // A vertical text stroke crossing the ruled line stays: the crossing
        // pixels are thicker than a line, so they are text, not paper.
        val w = 100
        val h = 30
        val ink = BooleanArray(w * h)
        for (x in 0 until w) ink[15 * w + x] = true
        for (y in 5..25) ink[y * w + 50] = true
        val out = InkVectorizer.removeRulings(ink, w, h, spanFrac = 0.5, maxThick = 4)
        assertTrue("crossing kept", out[15 * w + 50])
        assertTrue("line gone elsewhere", !out[15 * w + 10])
    }

    @Test
    fun `cursive word traces to a handful of long strokes`() {
        // A cursive-like loop word: one ring crossed by its own tail. The old
        // raw-count tracing shattered it (every stair-step a junction); the
        // topological trace must hold each arc together.
        val w = 60
        val h = 30
        val lum = lumOf(w, h) { x, y ->
            // Ellipse ring (the bowl) plus an entry and an exit tail.
            val ex = (x - 30) / 14.0
            val ey = (y - 15) / 7.0
            val ring = ex * ex + ey * ey
            (ring > 0.7 && ring < 1.3) || (y == 15 && x in 2..16) || (y == 22 && x in 32..57)
        }
        val paths = InkVectorizer.vectorize(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        assertTrue("expected ink, got none", paths.isNotEmpty())
        val long = paths.filter { it.lengthPx > 15.0 }
        assertTrue("word should hold long arcs, got ${paths.size} paths", long.isNotEmpty())
        assertTrue("word should not shatter, got ${paths.size} paths", paths.size <= 12)
    }

    @Test
    fun `print mode keeps neighbouring glyphs separate`() {
        // Two 3px bars with a 2px gap (like adjacent print stems): the default
        // handwriting closing welds them, print mode must keep them apart.
        val w = 40
        val h = 40
        val lum = lumOf(w, h) { x, y -> y in 4..15 && (x in 10..12 || x in 15..17) }
        val hand = InkVectorizer.inkMask(lum, w, h, InkVectorizer.TraceOptions(threshold = 128))
        val print = InkVectorizer.inkMask(lum, w, h, InkVectorizer.TraceOptions(threshold = 128, printedText = true))
        // The gap column must stay paper in print mode…
        assertTrue("print keeps the inter-glyph gap", (4..15).all { !print[it * w + 13] && !print[it * w + 14] })
        // …while handwriting mode bridges it.
        assertTrue("handwriting bridges the gap", (4..15).any { hand[it * w + 13] || hand[it * w + 14] })
    }

    @Test
    fun `print words trace to separate readable glyphs`() {
        // Two rings side by side (like "oo"): each must trace as its own loop,
        // not one scribble through both.
        val w = 60
        val h = 30
        val lum = lumOf(w, h) { x, y ->
            val left = ((x - 18) / 7.0).let { it * it } + ((y - 15) / 9.0).let { it * it }
            val right = ((x - 40) / 7.0).let { it * it } + ((y - 15) / 9.0).let { it * it }
            (left > 0.7 && left < 1.3) || (right > 0.7 && right < 1.3)
        }
        val opts = InkVectorizer.TraceOptions(threshold = 128, printedText = true)
        val paths = InkVectorizer.vectorize(lum, w, h, opts)
        assertTrue("expected ink, got none", paths.isNotEmpty())
        val long = paths.filter { it.lengthPx > 15.0 }
        assertTrue("each ring should hold together, got ${paths.size} paths", long.size >= 2)
        assertTrue("rings must not shatter, got ${paths.size} paths", paths.size <= 8)
    }

    @Test
    fun `broken ruling dashes vanish but crossings and off-band bars stay`() {
        // Two broken dashes of one ruled line (each shorter than the full-span
        // pass sees), a vertical text stroke crossing them, and a fraction-bar
        // like run between the ruling rows.
        val w = 100
        val h = 40
        val ink = BooleanArray(w * h)
        for (x in 0..39) ink[10 * w + x] = true // left dash of the ruling
        for (x in 60..99) ink[10 * w + x] = true // right dash of the ruling
        for (y in 2..20) ink[y * w + 50] = true // text stroke crossing the band
        for (x in 10..34) ink[25 * w + x] = true // off-band bar (fraction bar)
        val out = InkVectorizer.removeRulings(ink, w, h, spanFrac = 0.5, maxThick = 4)
        assertTrue("left dash erased", (0..39).none { out[10 * w + it] })
        assertTrue("right dash erased", (60..99).none { out[10 * w + it] })
        assertTrue("crossing kept", out[10 * w + 50])
        assertTrue("off-band bar kept", (10..34).all { out[25 * w + it] })
    }
}
