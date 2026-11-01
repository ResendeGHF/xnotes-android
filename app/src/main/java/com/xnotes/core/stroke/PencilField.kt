package com.xnotes.core.stroke

import com.xnotes.core.model.Rgba
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Software rasterizer for the pencil's graphite field. Evaluates the same
 * page-anchored field as the GL shader ([PencilGrain.coverage]) over a
 * tessellated pencil run, one triangle at a time, so the paged canvas and PDF
 * export show exactly the live texture instead of stamped dots.
 *
 * Coverage is a pure function of page position plus per-vertex density/edge,
 * so overlapping runs repaint identically: baking settled runs once lands
 * exactly where a full repaint would. Triangles share one consistent winding
 * and are claimed by a top-left rule, so shared edges never double-draw.
 */
object PencilField {

    /** Field raster resolution, in content px per field pixel. */
    const val FIELD_PX = 0.5

    /** One rasterized tile: straight (non-premultiplied) ARGB, row-major. */
    class Chunk(
        val pixels: IntArray,
        val w: Int,
        val h: Int,
        /** Content-space origin of pixel (0, 0). */
        val left: Double,
        val top: Double,
    )

    /**
     * Rasterize a pencil [mesh] into tiles of at most [maxTilePx] field pixels
     * a side, at [fieldPx] content px per field pixel. [quality] fades the
     * finest octave when minified, like the GL zoom LOD.
     */
    fun render(
        positions: DoubleArray,
        colors: IntArray,
        indices: IntArray,
        ink: Rgba,
        quality: Double,
        fieldPx: Double = FIELD_PX,
        maxTilePx: Int = 512,
        seed: Int = 0,
        tipMix: Double = 0.0,
    ): List<Chunk> {
        val n = positions.size / 2
        if (n == 0 || indices.isEmpty() || colors.size < n) return emptyList()
        // Bounds, outset by a pixel for the silhouette edge.
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = positions[2 * i]
            val y = positions[2 * i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (!minX.isFinite()) return emptyList()
        minX -= 1.0
        minY -= 1.0
        maxX += 1.0
        maxY += 1.0
        // Uniform grid over triangles (content px), so each pixel tests a few.
        val cell = 4.0
        val gx0 = floor(minX / cell).toInt()
        val gy0 = floor(minY / cell).toInt()
        val gx1 = floor(maxX / cell).toInt()
        val gy1 = floor(maxY / cell).toInt()
        val gw = (gx1 - gx0 + 1).coerceAtLeast(1)
        val gh = (gy1 - gy0 + 1).coerceAtLeast(1)
        val grid = Array(gw * gh) { mutableListOf<Int>() }
        var t = 0
        while (t + 2 < indices.size) {
            val a = indices[t]
            val b = indices[t + 1]
            val c = indices[t + 2]
            if (a in 0 until n && b in 0 until n && c in 0 until n) {
                val tx0 = min(positions[2 * a], min(positions[2 * b], positions[2 * c]))
                val ty0 = min(positions[2 * a + 1], min(positions[2 * b + 1], positions[2 * c + 1]))
                val tx1 = max(positions[2 * a], max(positions[2 * b], positions[2 * c]))
                val ty1 = max(positions[2 * a + 1], max(positions[2 * b + 1], positions[2 * c + 1]))
                val cx0 = max(0, floor(tx0 / cell).toInt() - gx0)
                val cy0 = max(0, floor(ty0 / cell).toInt() - gy0)
                val cx1 = min(gw - 1, floor(tx1 / cell).toInt() - gx0)
                val cy1 = min(gh - 1, floor(ty1 / cell).toInt() - gy0)
                for (cy in cy0..cy1) for (cx in cx0..cx1) grid[cy * gw + cx].add(t)
            }
            t += 3
        }
        // Tile the pixel grid so no single bitmap grows without bound.
        val out = ArrayList<Chunk>()
        var ty = floor(minY / fieldPx).toInt()
        val tyEnd = ceil(maxY / fieldPx).toInt()
        while (ty < tyEnd) {
            var tx = floor(minX / fieldPx).toInt()
            val txEnd = ceil(maxX / fieldPx).toInt()
            while (tx < txEnd) {
                val tw = min(tx + maxTilePx, txEnd) - tx
                val th = min(ty + maxTilePx, tyEnd) - ty
                out.add(rasterTile(positions, colors, indices, ink, quality, fieldPx, grid, gw, gh, gx0, gy0, cell, tx, ty, tw, th, seed, tipMix))
                tx += maxTilePx
            }
            ty += maxTilePx
        }
        return out
    }

    private fun rasterTile(
        positions: DoubleArray,
        colors: IntArray,
        indices: IntArray,
        ink: Rgba,
        quality: Double,
        fp: Double,
        grid: Array<MutableList<Int>>,
        gw: Int, gh: Int, gx0: Int, gy0: Int, cell: Double,
        tx0: Int, ty0: Int, tw: Int, th: Int,
        seed: Int,
        tipMix: Double,
    ): Chunk {
        val px = IntArray(tw * th)
        val ir = ink.r
        val ig = ink.g
        val ib = ink.b
        for (jy in 0 until th) {
            for (ix in 0 until tw) {
                val x = (tx0 + ix + 0.5) * fp
                val y = (ty0 + jy + 0.5) * fp
                val gx = floor(x / cell).toInt() - gx0
                val gy = floor(y / cell).toInt() - gy0
                if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) continue
                // First covering triangle wins: with consistent winding and the
                // top-left rule in barycentric(), a shared edge is claimed
                // identically on every rasterization of the same mesh.
                var w: DoubleArray? = null
                var va = 0
                var vb = 0
                var vc = 0
                for (tri in grid[gy * gw + gx]) {
                    val a = indices[tri] * 2
                    val b = indices[tri + 1] * 2
                    val c = indices[tri + 2] * 2
                    val cand = barycentric(
                        positions[a], positions[a + 1],
                        positions[b], positions[b + 1],
                        positions[c], positions[c + 1],
                        x, y,
                    )
                    if (cand != null) {
                        w = cand
                        va = indices[tri]
                        vb = indices[tri + 1]
                        vc = indices[tri + 2]
                        break
                    }
                }
                val weights = w ?: continue
                val da = PencilGrain.unpackDensity(colors[va])
                val db = PencilGrain.unpackDensity(colors[vb])
                val dc = PencilGrain.unpackDensity(colors[vc])
                val ea = PencilGrain.unpackEdge(colors[va])
                val eb = PencilGrain.unpackEdge(colors[vb])
                val ec = PencilGrain.unpackEdge(colors[vc])
                val d = da * weights[0] + db * weights[1] + dc * weights[2]
                val e = (ea * weights[0] + eb * weights[1] + ec * weights[2]).coerceIn(0.0, 1.0)
                val alpha = PencilGrain.coverage(x, y, d, e, quality, seed, tipMix)
                if (alpha <= 0.0) continue
                val ai = (alpha * 255.0).toInt().coerceIn(0, 255)
                px[jy * tw + ix] = (ai shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }
        return Chunk(px, tw, th, tx0 * fp, ty0 * fp)
    }

    /**
     * Barycentric weights of ([x], [y]) in the triangle (a, b, c), or null
     * when outside under the top-left rule. Returns weights in vertex order.
     *
     * The tessellator winds every triangle with non-negative signed area in
     * content space (y grows downward, so this is screen-clockwise): the
     * interior is where the edge functions are negative, and the weights are
     * the edges over their (negative) sum.
     */
    private fun barycentric(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
        x: Double, y: Double,
    ): DoubleArray? {
        // Edge functions, negative inside a screen-clockwise triangle.
        val e0 = (x - bx) * (cy - by) - (y - by) * (cx - bx)
        val e1 = (x - cx) * (ay - cy) - (y - cy) * (ax - cx)
        val e2 = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
        // Top-left rule: an edge through the pixel's top or left counts as in.
        // Edge i is top-left when it is horizontal going right (dy == 0, dx > 0)
        // or it goes down (dy > 0, content y grows downward), measured from
        // v[i] to v[i+1]. Opposite traversals of a shared edge give opposite
        // answers, so exactly one side claims it on every rasterization.
        if (!inside(e0, cy, by, cx, bx)) return null
        if (!inside(e1, ay, cy, ax, cx)) return null
        if (!inside(e2, by, ay, bx, ax)) return null
        val area = e0 + e1 + e2
        if (area >= 0.0) return null
        // Weight of a = e0/area (opposite edge b->c), etc.
        return doubleArrayOf(e0 / area, e1 / area, e2 / area)
    }

    private fun inside(e: Double, y0: Double, y1: Double, x0: Double, x1: Double): Boolean {
        if (e < 0.0) return true
        if (e > 0.0) return false
        // On the edge: top edges (horizontal, rightward) and left edges (downward).
        return y0 == y1 && x1 > x0 || y0 < y1
    }
}
