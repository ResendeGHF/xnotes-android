package com.xnotes.core.infinite

import com.xnotes.core.stroke.Sample
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Decimation for drag previews: a stroke drawn while it is being moved, rotated or resized
 * is temporary, so it may shed every sample a screen pixel cannot resolve. Unlike
 * [com.xnotes.core.stroke.StrokeSimplify] (which commits a reduction forever and guards heads,
 * corners and gaps), this keeps only the Ramer–Douglas–Peucker invariant — every dropped
 * sample lies within [eps] content px of the kept chord in position *and* half-width — and is
 * only ever drawn until the finger lifts, when the full stroke comes back.
 */
object DragLod {

    /**
     * [samples] reduced so no dropped point strays more than [eps] from the kept polyline, in
     * either centreline position or rendered half-width (a pressure spike that RDP on position
     * alone would flatten still forces its neighbourhood to stay). Endpoints are always kept.
     * Returns the original list when nothing is worth dropping.
     */
    fun decimate(samples: List<Sample>, halfWidths: FloatArray, eps: Double): List<Sample> {
        val n = samples.size
        if (n < 3 || halfWidths.size != n || eps <= 0.0 || !eps.isFinite()) return samples
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(n - 1)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            val a = stack.removeLast()
            if (b - a < 2) continue
            val ax = samples[a].x
            val ay = samples[a].y
            val dx = samples[b].x - ax
            val dy = samples[b].y - ay
            val ha = halfWidths[a].toDouble()
            val hb = halfWidths[b].toDouble()
            val lenSq = dx * dx + dy * dy
            var worst = -1
            var worstDev = eps
            for (i in a + 1 until b) {
                val px = samples[i].x - ax
                val py = samples[i].y - ay
                val t = if (lenSq < 1e-12) 0.0 else ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
                var dev = hypot(px - t * dx, py - t * dy)
                val wDev = abs(halfWidths[i] - (ha + (hb - ha) * t))
                if (wDev > dev) dev = wDev
                if (dev > worstDev) {
                    worstDev = dev
                    worst = i
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(a)
                stack.addLast(worst)
                stack.addLast(worst)
                stack.addLast(b)
            }
        }
        var kept = 0
        for (i in 0 until n) if (keep[i]) kept++
        if (kept == n) return samples
        val out = ArrayList<Sample>(kept)
        for (i in 0 until n) if (keep[i]) out.add(samples[i])
        return out
    }
}
