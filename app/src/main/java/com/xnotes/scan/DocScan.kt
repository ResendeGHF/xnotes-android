package com.xnotes.scan

import com.xnotes.core.geometry.Pt
import kotlin.math.ceil

/**
 * Document-quad handling for the camera scan flow: find the page's four corners
 * in a photo ([detectQuad]), size the de-warped output ([quadOutputSize]) and
 * flatten the perspective ([warpPerspective], Android bitmap op).
 *
 * Corners are plain working-pixel points; the UI stores them as fractions so one
 * quad edits every resolution of the photo.
 */
object DocScan {

    /** Page corners in pixels: top-left, top-right, bottom-right, bottom-left. */
    data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
        fun toFractions(w: Int, h: Int): FloatArray = floatArrayOf(
            (tl.x / w).toFloat(), (tl.y / h).toFloat(),
            (tr.x / w).toFloat(), (tr.y / h).toFloat(),
            (br.x / w).toFloat(), (br.y / h).toFloat(),
            (bl.x / w).toFloat(), (bl.y / h).toFloat(),
        )

        fun toPixels(w: Int, h: Int): FloatArray = floatArrayOf(
            tl.x.toFloat(), tl.y.toFloat(),
            tr.x.toFloat(), tr.y.toFloat(),
            br.x.toFloat(), br.y.toFloat(),
            bl.x.toFloat(), bl.y.toFloat(),
        )

        companion object {
            fun fromFractions(f: FloatArray, w: Int, h: Int): Quad = Quad(
                Pt((f[0] * w).toDouble(), (f[1] * h).toDouble()),
                Pt((f[2] * w).toDouble(), (f[3] * h).toDouble()),
                Pt((f[4] * w).toDouble(), (f[5] * h).toDouble()),
                Pt((f[6] * w).toDouble(), (f[7] * h).toDouble()),
            )

            /** The whole frame with a small inset, the corner editor's reset state. */
            fun inset(w: Int, h: Int, frac: Double = 0.06): Quad {
                val ix = w * frac
                val iy = h * frac
                return Quad(Pt(ix, iy), Pt(w - ix, iy), Pt(w - ix, h - iy), Pt(ix, h - iy))
            }
        }
    }

    /**
     * Best-effort page finder over row-major 0..255 [lum]: the largest bright
     * blob (paper on a darker surround) approximated by its extreme-projection
     * corners, or null when no plausible page fills the frame. Runs on a
     * downsampled grid, so it is cheap enough to run on capture.
     */
    fun detectQuad(lum: IntArray, w: Int, h: Int): Quad? {
        require(lum.size == w * h) { "luminance size ${lum.size} != ${w}x$h" }
        if (w < 40 || h < 40) return null
        val stride = maxOf(1, ceil(maxOf(w, h) / 400.0).toInt())
        val gw = (w + stride - 1) / stride
        val gh = (h + stride - 1) / stride
        val small = IntArray(gw * gh) { i ->
            val gx = i % gw
            val gy = i / gw
            lum[(gy * stride + stride / 2).coerceAtMost(h - 1) * w + (gx * stride + stride / 2).coerceAtMost(w - 1)]
        }
        val level = InkVectorizer.otsuThreshold(small)
        val bright = BooleanArray(small.size) { small[it] > level }
        val brightFrac = bright.count { it }.toDouble() / bright.size
        // No paper/background split to find: a full-bleed page or no page at all.
        if (brightFrac < 0.05 || brightFrac > 0.97) return null
        // Largest bright blob.
        val seen = BooleanArray(bright.size)
        val queue = ArrayDeque<Int>()
        var best: IntArray? = null
        for (s in bright.indices) {
            if (!bright[s] || seen[s]) continue
            val pixels = ArrayList<Int>(256)
            queue.clear()
            queue.add(s)
            seen[s] = true
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                pixels.add(p)
                val x = p % gw
                val y = p / gw
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= gw) continue
                        val q = ny * gw + nx
                        if (bright[q] && !seen[q]) {
                            seen[q] = true
                            queue.add(q)
                        }
                    }
                }
            }
            if (best == null || pixels.size > best.size) best = pixels.toIntArray()
        }
        val blob = best ?: return null
        if (blob.size < 0.08 * gw * gh) return null
        // Extreme projections: TL minimises x+y, TR maximises x-y, BR maximises
        // x+y, BL minimises x-y. Exact for a parallelogram, close otherwise —
        // the user trims the rest by hand.
        var tl = 0
        var tr = 0
        var br = 0
        var bl = 0
        var tlV = Int.MAX_VALUE
        var trV = Int.MIN_VALUE
        var brV = Int.MIN_VALUE
        var blV = Int.MAX_VALUE
        for (p in blob) {
            val x = p % gw
            val y = p / gw
            val sum = x + y
            val dif = x - y
            if (sum < tlV) {
                tlV = sum
                tl = p
            }
            if (dif > trV) {
                trV = dif
                tr = p
            }
            if (sum > brV) {
                brV = sum
                br = p
            }
            if (dif < blV) {
                blV = dif
                bl = p
            }
        }
        fun px(p: Int): Pt = Pt(
            (((p % gw) * stride + stride / 2).coerceAtMost(w - 1)).toDouble(),
            (((p / gw) * stride + stride / 2).coerceAtMost(h - 1)).toDouble(),
        )
        val quad = Quad(px(tl), px(tr), px(br), px(bl))
        if (quadArea(quad) < 0.10 * w * h) return null
        return quad
    }

    /** Polygon area of [quad] (shoelace), in px². */
    fun quadArea(q: Quad): Double {
        val xs = doubleArrayOf(q.tl.x, q.tr.x, q.br.x, q.bl.x)
        val ys = doubleArrayOf(q.tl.y, q.tr.y, q.br.y, q.bl.y)
        var a = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return kotlin.math.abs(a) / 2.0
    }

    /**
     * Output size for a de-warped page: the quad's longest top/bottom edge by its
     * longest left/right edge, scaled to fit [maxSide]. Never below 1px.
     */
    fun quadOutputSize(quadPx: FloatArray, maxSide: Int): Pair<Int, Int> {
        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Double =
            kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble())
        val top = dist(quadPx[0], quadPx[1], quadPx[2], quadPx[3])
        val right = dist(quadPx[2], quadPx[3], quadPx[4], quadPx[5])
        val bottom = dist(quadPx[4], quadPx[5], quadPx[6], quadPx[7])
        val left = dist(quadPx[6], quadPx[7], quadPx[0], quadPx[1])
        val w = maxOf(top, bottom).coerceAtLeast(1.0)
        val h = maxOf(left, right).coerceAtLeast(1.0)
        val s = minOf(1.0, maxSide / maxOf(w, h))
        return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
    }

    /**
     * Flatten the perspective: map [quadPx] (TL,TR,BR,BL in source pixels) onto
     * an [outW]×[outH] rectangle over a white page. Null when the quad is
     * degenerate. IO-safe to call off the main thread.
     */
    fun warpPerspective(src: android.graphics.Bitmap, quadPx: FloatArray, outW: Int, outH: Int): android.graphics.Bitmap? {
        if (outW <= 0 || outH <= 0) return null
        val m = android.graphics.Matrix()
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        if (!m.setPolyToPoly(quadPx, 0, dst, 0, 4)) return null
        return runCatching {
            val out = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
            out.eraseColor(android.graphics.Color.WHITE)
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
            }
            canvas.drawBitmap(src, m, paint)
            out
        }.getOrNull()
    }
}
