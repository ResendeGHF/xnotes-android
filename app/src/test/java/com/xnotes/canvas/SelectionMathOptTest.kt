package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Equivalence of the indexed lasso path (edge grid + allocation-free stroke tests) against a
 * brute-force reference using the same rule. The grid only engages at 64+ edges, so the large
 * loops below exercise it while the small ones cover the direct scan.
 */
class SelectionMathOptTest {

    private fun stroke(id: Int, rnd: Random): Stroke {
        val x0 = rnd.nextDouble(-500.0, 500.0)
        val y0 = rnd.nextDouble(-500.0, 500.0)
        val n = rnd.nextInt(2, 30)
        val samples = ArrayList<Sample>(n)
        var x = x0
        var y = y0
        repeat(n) {
            samples.add(Sample(x, y, 1.0))
            x += rnd.nextDouble(-40.0, 40.0)
            y += rnd.nextDouble(-40.0, 40.0)
        }
        return Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), samples)
    }

    private fun circle(cx: Double, cy: Double, r: Double, n: Int): List<Pt> =
        List(n) { i ->
            val a = 2.0 * Math.PI * i / n
            Pt(cx + r * cos(a), cy + r * sin(a))
        }

    private fun wiggle(cx: Double, cy: Double, r: Double, n: Int, rnd: Random): List<Pt> =
        List(n) { i ->
            val a = 2.0 * Math.PI * i / n
            val rr = r * (1.0 + 0.3 * rnd.nextDouble(-1.0, 1.0))
            Pt(cx + rr * cos(a), cy + rr * sin(a))
        }

    /** Brute force of the documented rule for plain strokes (no room cuts). */
    private fun brute(strokes: List<Stroke>, ring: List<Pt>, whole: Boolean): Set<Stroke> {
        val box = Rect.bounding(ring)
        val out = HashSet<Stroke>()
        for (s in strokes) {
            val reach = s.config.baseWidth / 2.0
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (i in 0 until s.sampleCount) {
                val x = s.xAt(i)
                val y = s.yAt(i)
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            if (whole) {
                if (maxX < box.left || minX > box.right || maxY < box.top || minY > box.bottom) continue
                var allIn = true
                for (i in 0 until s.sampleCount) {
                    if (!Geometry.pointInPolygon(ring, Pt(s.xAt(i), s.yAt(i)))) {
                        allIn = false
                        break
                    }
                }
                if (allIn) out.add(s)
                continue
            }
            if (maxX + reach < box.left || minX - reach > box.right ||
                maxY + reach < box.top || minY - reach > box.bottom
            ) continue
            if (Geometry.pointInPolygon(ring, Pt(s.xAt(0), s.yAt(0)))) {
                out.add(s)
                continue
            }
            var hit = false
            for (k in 0 until s.sampleCount - 1) {
                val p = Pt(s.xAt(k), s.yAt(k))
                val q = Pt(s.xAt(k + 1), s.yAt(k + 1))
                for (e in ring.indices) {
                    val a = ring[e]
                    val b = ring[(e + 1) % ring.size]
                    if (Geometry.distanceSegmentToSegment(p, q, a, b) <= reach) {
                        hit = true
                        break
                    }
                }
                if (hit) break
            }
            if (hit) out.add(s)
        }
        return out
    }

    @Test fun largeLassoMatchesBruteForce() {
        val rnd = Random(1234)
        val strokes = List(300) { stroke(it, rnd) }
        val items: List<CanvasItem> = strokes
        for ((n, whole) in listOf(200 to false, 200 to true, 500 to false, 9 to false, 4 to true)) {
            val ring = wiggle(0.0, 0.0, 400.0, n, rnd)
            val got = SelectionMath.lassoMembers(items, ring, whole).toSet()
            val want = brute(strokes, ring, whole)
            assertEquals("n=$n whole=$whole", want, got)
        }
    }

    @Test fun largeCircleEnclosesExactlyTheInsideStrokes() {
        val rnd = Random(99)
        val strokes = List(200) { stroke(it, rnd) }
        val ring = circle(0.0, 0.0, 300.0, 256)
        val got = SelectionMath.lassoMembers(strokes, ring, false).toSet()
        val want = brute(strokes, ring, false)
        assertEquals(want, got)
        assertTrue("the test needs both hits and misses to mean anything", got.isNotEmpty() && got.size < strokes.size)
    }
}
