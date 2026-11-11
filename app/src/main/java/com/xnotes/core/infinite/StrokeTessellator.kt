package com.xnotes.core.infinite

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.stroke.PencilStyle
import com.xnotes.core.stroke.RibbonPoints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * A triangle mesh in content space. Positions are doubles because the canvas is unbounded: a float
 * absolute position has already lost visible precision a million pixels from the origin, and the
 * uploader splits each coordinate into a coarse chunk index plus a small local offset precisely so
 * the GPU never has to hold one. Indices are relative to this mesh's own first vertex.
 */
class MeshData(
    /** Interleaved x,y in content space; `positions.size / 2` vertices. */
    val positions: DoubleArray,
    /**
     * Interleaved x,y displacement of each vertex from the line it belongs to, zero for a fill.
     * The renderer uses it to keep a sub-pixel line visible rather than letting it shimmer.
     */
    val offsets: DoubleArray,
    /** Triangle list, three indices per triangle, zero-based within this mesh. */
    val indices: IntArray,
    /**
     * Packed ARGB per vertex, or null when the whole mesh is one colour and the uploader supplies
     * it. Set only by a gradient fill, which is per-vertex colour on a subdivided mesh: the vertex
     * format already carries RGBA and the rasterizer already interpolates it, so a ramp costs
     * nothing per frame beyond the extra triangles it took to resolve.
     */
    val colors: IntArray? = null,
) {
    val vertexCount: Int get() = positions.size / 2
    val triangleCount: Int get() = indices.size / 3
    val isEmpty: Boolean get() = indices.isEmpty()

    companion object {
        val EMPTY = MeshData(DoubleArray(0), DoubleArray(0), IntArray(0))
    }
}

/**
 * Turns a ribbon's points into triangles once, at commit time. Every frame then draws those
 * triangles with the current zoom pushed in as a uniform, so ink is resolution independent: there
 * is no raster to be at the wrong scale, and no blur to resolve when a pinch settles.
 *
 * The silhouette is the paged painter's ([com.xnotes.core.pal.Renderer.fillDiskRibbon]): a brush
 * disc swept down the centreline, each segment the hull of its two end discs. A hull's sides are
 * tangent to both discs, so they lean back where the brush grows and forward where it shrinks. Rails
 * laid square across the centreline miss exactly that lean, which is most of what a calligraphy nib
 * does, and drew its swells thinner and more angular here than on a page.
 *
 * Each hull's straight part is one quad between its four tangent points. Where two meet at a
 * sample their tangent points nearly coincide, so they share one vertex on the disc's rim there,
 * and a smooth stroke costs a quad per segment. Where the edge really kinks, at a turn or where the
 * nib stops widening, each keeps its own and the rim between them is filled. The ends, and a disc
 * that holds its neighbour whole, are drawn as whole discs.
 *
 * Antialiasing comes from multisampling on these triangle edges rather than from coverage computed
 * in a shader, because MSAA is the only one of the two that composes correctly where a stroke
 * overlaps itself: a sample is covered or not, so the same colour written twice is still that
 * colour. That makes the silhouette's fidelity the whole of the quality, which is why the round
 * parts are tessellated to a chord tolerance rather than to a fixed segment count.
 */
object StrokeTessellator {

    /**
     * Content-space chord error allowed on a round cap or join. Chosen so a curve stays under half
     * a device pixel of error at [CanvasViewport.MAX_ZOOM], since geometry is baked once and the
     * canvas zooms far past what a page ever does.
     */
    const val DEFAULT_TOLERANCE = 0.5 / CanvasViewport.MAX_ZOOM

    /** Fewest and most segments a full circle is ever cut into. */
    const val MIN_CIRCLE_SEGMENTS = MeshBuilder.MIN_CIRCLE_SEGMENTS
    const val MAX_CIRCLE_SEGMENTS = MeshBuilder.MAX_CIRCLE_SEGMENTS

    /**
     * Turn angle, in radians, past which a join shows its own disc. Below it the two quads meeting
     * there share one vertex and cut the rim between them by under half a percent of the radius;
     * above it the rim is filled. Smoothed handwriting turns a fraction of this per sample.
     */
    const val JOIN_DISC_ANGLE = 0.18

    /** Edge turn, in radians, past which a join is drawn as the sample's whole disc. */
    private const val WHOLE_DISC_ANGLE = 2.0 * PI / 3.0

    /** Half-widths at or below this contribute nothing and are skipped. */
    private const val MIN_HALF_WIDTH = 1e-6

    /**
     * [widthScale] narrows the ribbon about its own centreline, which is how neon's white-hot core
     * is drawn: the same path at a fraction of the width, so it rounds with the body on every pen.
     */
    fun tessellate(
        g: RibbonPoints,
        tolerance: Double = DEFAULT_TOLERANCE,
        widthScale: Double = 1.0,
    ): MeshData = tessellate(g, 0, g.pointCount, tolerance, widthScale)

    /**
     * [tessellate] over the [count] points starting at [from]. A stroke still under the pen is
     * meshed in runs — the settled ones once each, the moving tail every frame — so that what a
     * frame costs stops growing with the stroke. Consecutive runs are asked to overlap by a point,
     * which makes the quad bridging them fall to the later run and leaves no gap on the join.
     *
     * The discs a run puts at its own two ends are the same discs the sweep would place there, so
     * they sit inside the ribbon and change nothing about its silhouette. A run reads nothing past
     * its own points, so a run meshed once stays right however the stroke goes on.
     */
    fun tessellate(
        g: RibbonPoints,
        from: Int,
        count: Int,
        tolerance: Double,
        widthScale: Double = 1.0,
    ): MeshData {
        if (count <= 0) return MeshData.EMPTY
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        if (count == 1) {
            val h = g.hw(from) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(from), g.cy(from), h, tolerance)
            return b.build()
        }
        if (widthScale != 1.0) return scaledRibbon(g, from, count, tolerance, widthScale)

        // Each segment's tangent normals, left x, y then right x, y; none where one disc holds the
        // other, and that disc is drawn whole instead. A disc held by its neighbour is never drawn
        // whole itself: down a run of them the largest holds every one.
        val segs = count - 1
        val normals = DoubleArray(4 * segs)
        val body = BooleanArray(segs)
        val whole = BooleanArray(count)
        val held = BooleanArray(count)
        whole[0] = true
        whole[count - 1] = true
        val n = DoubleArray(4)
        for (k in 0 until segs) {
            val i = from + k
            val r0 = g.hw(i)
            val r1 = g.hw(i + 1)
            if (r0 <= MIN_HALF_WIDTH && r1 <= MIN_HALF_WIDTH) continue
            if (Geometry.capsule(g.cx(i), g.cy(i), r0, g.cx(i + 1), g.cy(i + 1), r1, n) == Geometry.SWALLOWED) {
                val big = if (r0 >= r1) k else k + 1
                whole[big] = true
                held[if (big == k) k + 1 else k] = true
                continue
            }
            body[k] = true
            n.copyInto(normals, 4 * k)
        }

        // The rim vertices each quad ends on (back) and starts from (front) at every sample.
        val backL = IntArray(count)
        val backR = IntArray(count)
        val frontL = IntArray(count)
        val frontR = IntArray(count)
        for (k in 0 until count) {
            val i = from + k
            val x = g.cx(i)
            val y = g.cy(i)
            val r = g.hw(i)
            val back = 4 * (k - 1)
            val front = 4 * k
            val hasBack = k > 0 && body[k - 1]
            val hasFront = k < segs && body[k]
            if (hasBack && hasFront && !whole[k]) {
                val turnL = turn(normals, back, front)
                val turnR = turn(normals, back + 2, front + 2)
                if (abs(turnL) <= WHOLE_DISC_ANGLE && abs(turnR) <= WHOLE_DISC_ANGLE) {
                    join(b, x, y, r, normals, back, front, turnL, turnR, tolerance, k, backL, backR, frontL, frontR)
                    continue
                }
                whole[k] = true
            }
            if (hasBack) {
                backL[k] = rim(b, x, y, r, normals[back], normals[back + 1])
                backR[k] = rim(b, x, y, r, normals[back + 2], normals[back + 3])
            }
            if (hasFront) {
                frontL[k] = rim(b, x, y, r, normals[front], normals[front + 1])
                frontR[k] = rim(b, x, y, r, normals[front + 2], normals[front + 3])
            }
            if (whole[k] && !held[k] && r > MIN_HALF_WIDTH) b.circle(x, y, r, tolerance)
        }

        for (k in 0 until segs) {
            if (!body[k]) continue
            b.triangle(frontL[k], frontR[k], backR[k + 1])
            b.triangle(frontL[k], backR[k + 1], backL[k + 1])
        }
        return b.build()
    }

    /**
     * Graphite DENSITY for smoothed stylus pressure [p] in `[0, 1]`, drawn
     * with [style]: the fraction of pixels that carry opaque graphite. Never
     * 1: paper always shows through somewhere, like real graphite. The
     * [PencilStyle.pressure] gain answers light touches before the map, the
     * [PencilStyle.density] scale packs more grains after it.
     *
     * The GL cover evaluates the same map from uniforms, so the two
     * renderers cannot drift apart (see InkShader's uDens* + uPencilCfg).
     */
    fun pencilDensity(p: Double, style: PencilStyle): Double =
        pencilDensityFor(pencilEffectivePressure(p, style.pressure), style.density)

    /** Effective pressure: sensitivity gain applied, NaN-proofed, clamped.
     *  A zero (or negative) gain means "no response": pressure always reads
     *  maximum, so the stroke keeps its darkest shade and density everywhere. */
    fun pencilEffectivePressure(p: Double, gain: Double): Double {
        if (gain <= 0.0) return 1.0
        val c = if (p.isNaN()) 0.0 else p.coerceIn(0.0, 1.0)
        return (c * gain).coerceIn(0.0, 1.0)
    }

    /** Density map over effective pressure, scaled and capped below solid. */
    fun pencilDensityFor(pe: Double, scale: Double): Double =
        ((PENCIL_DENSITY_BASE + PENCIL_DENSITY_SPAN * pe.pow(PENCIL_DENSITY_GAMMA)) * scale)
            .coerceIn(0.0, PENCIL_DENSITY_MAX)

    /** Density map intercept: graphite density at zero pressure. Mirrored in InkShader. */
    const val PENCIL_DENSITY_BASE = 0.55

    /** Density map slope: additional density at full pressure. Mirrored in InkShader. */
    const val PENCIL_DENSITY_SPAN = 0.33

    /** Density map gamma: lifts mid pressure toward solid. Mirrored in InkShader. */
    const val PENCIL_DENSITY_GAMMA = 0.7

    /** Hard ceiling: graphite is never fully solid, always noise. */
    const val PENCIL_DENSITY_MAX = 0.97

    /**
     * Grain SHADE for smoothed stylus pressure [p], drawn with [style]: light
     * pressure leaves light-grey grains, hard pressure dark graphite ones — a
     * gradient mapping over pressure, never translucency. 0.45 mixes almost
     * halfway to white; 1.0 is the full ink colour.
     */
    fun pencilShade(p: Double, style: PencilStyle): Double =
        pencilShadeFor(pencilEffectivePressure(p, style.pressure), style.shadeResponse)

    /** Shade map over effective pressure with an explicit response. */
    fun pencilShadeFor(pe: Double, response: Double): Double =
        (PENCIL_SHADE_BASE + (PENCIL_SHADE_SPAN * pe.pow(PENCIL_SHADE_GAMMA)) * response)
            .coerceIn(0.0, 1.0)

    /** Shade map intercept: grain colour mix at zero pressure. Mirrored in InkShader. */
    const val PENCIL_SHADE_BASE = 0.45

    /** Shade map slope: additional mix at full pressure. Mirrored in InkShader. */
    const val PENCIL_SHADE_SPAN = 0.55

    /** Shade map gamma: lifts mid pressure toward full ink. Mirrored in InkShader. */
    const val PENCIL_SHADE_GAMMA = 0.7

    /** Pressure bands: the paged renderer quantizes effective pressure into
     *  these, so every band fills from one pre-thresholded tile at one shade. */
    const val PENCIL_BANDS = 12

    fun pencilBandOf(p: Double, gain: Double): Int =
        (pencilEffectivePressure(p, gain) * PENCIL_BANDS).toInt().coerceIn(0, PENCIL_BANDS - 1)

    fun pencilBandDensity(band: Int, scale: Double): Double =
        pencilDensityFor((band.coerceIn(0, PENCIL_BANDS - 1) + 0.5) / PENCIL_BANDS, scale)

    fun pencilBandShade(band: Int, response: Double): Double =
        pencilShadeFor((band.coerceIn(0, PENCIL_BANDS - 1) + 0.5) / PENCIL_BANDS, response)

    /**
     * The pencil: the pen's swept ribbon — rail quads plus whole discs at the
     * stroke's ends and at hard turns — carrying real ink colour with raw
     * stylus pressure in the vertex alpha (see [packPencilPressure]).
     *
     * Overlaps are never avoided here; the stencil-consume cover
     * ([com.xnotes.gl.CanvasScene.drawPencil]) unions every pixel exactly
     * once, so joins and caps are round by construction: no miters, no
     * bevels, no fans, nothing that can spike or facet. The single-point
     * stroke is one whole disc.
     *
     * Runs tile the ribbon with one shared station; settled runs bake once
     * and the tail repaints over the same stations with the same pressures,
     * so the seam cannot tone-shift.
     */
    fun tessellatePencil(
        g: RibbonPoints,
        from: Int,
        count: Int,
        tolerance: Double,
        ink: Rgba,
        pressureAt: (Int) -> Double,
    ): MeshData {
        if (count <= 0) return MeshData.EMPTY
        val end = from + count
        val b = MeshBuilder(estimatePencilVertices(count), estimateIndices(count) * 2)
        if (count == 1) {
            val h = g.hw(from)
            if (h > MIN_HALF_WIDTH) {
                b.circleColored(g.cx(from), g.cy(from), h, tolerance, packPencilPressure(ink, pressureAt(from)))
            }
            return b.build()
        }
        if (!g.hasRails) return b.build()
        // Body: one quad per segment off the rails, exactly like the pen's
        // sweep. Consecutive quads share edges; sharp turns and folds overlap
        // freely — the stencil cover unions them into one silhouette.
        for (i in from until end - 1) {
            val h0 = g.hw(i)
            val h1 = g.hw(i + 1)
            if (h0 <= MIN_HALF_WIDTH && h1 <= MIN_HALF_WIDTH) continue
            val p0 = packPencilPressure(ink, pressureAt(i))
            val p1 = packPencilPressure(ink, pressureAt(i + 1))
            val cx0 = g.cx(i)
            val cy0 = g.cy(i)
            val cx1 = g.cx(i + 1)
            val cy1 = g.cy(i + 1)
            val lx0 = g.leftX(i)
            val ly0 = g.leftY(i)
            val rx0 = g.rightX(i)
            val ry0 = g.rightY(i)
            val lx1 = g.leftX(i + 1)
            val ly1 = g.leftY(i + 1)
            val rx1 = g.rightX(i + 1)
            val ry1 = g.rightY(i + 1)
            val l0 = b.vertexColored(lx0, ly0, lx0 - cx0, ly0 - cy0, p0)
            val r0 = b.vertexColored(rx0, ry0, rx0 - cx0, ry0 - cy0, p0)
            val l1 = b.vertexColored(lx1, ly1, lx1 - cx1, ly1 - cy1, p1)
            val r1 = b.vertexColored(rx1, ry1, rx1 - cx1, ry1 - cy1, p1)
            b.triangle(l0, r0, r1)
            b.triangle(l0, r1, l1)
        }
        // Round ends: whole discs, exactly the discs the paged renderer
        // sweeps. A run caps only at the stroke's own ends.
        if (from == 0 && g.hw(from) > MIN_HALF_WIDTH) {
            b.circleColored(g.cx(from), g.cy(from), g.hw(from), tolerance, packPencilPressure(ink, pressureAt(from)))
        }
        if (end == g.pointCount && g.hw(end - 1) > MIN_HALF_WIDTH) {
            b.circleColored(g.cx(end - 1), g.cy(end - 1), g.hw(end - 1), tolerance, packPencilPressure(ink, pressureAt(end - 1)))
        }
        // Round joins: discs where the path turns hard, exactly like the pen.
        // Turns accumulate along the run and reset at every disc, so a long
        // gentle curve still gets discs before its outer edge can notch.
        var acc = 0.0
        for (i in from + 1 until end - 1) {
            val h = g.hw(i)
            if (h <= MIN_HALF_WIDTH) {
                acc = 0.0
                continue
            }
            acc += turnAngle(g, i)
            if (acc > JOIN_DISC_ANGLE) {
                b.circleColored(g.cx(i), g.cy(i), h, tolerance, packPencilPressure(ink, pressureAt(i)))
                acc = 0.0
            }
        }
        // A hard turn exactly on the run's last station (a bake-then-tail
        // seam): the next run starts past it, so this run owns its disc.
        if (end != g.pointCount && count >= 2 && turnAngle(g, end - 1) > JOIN_DISC_ANGLE) {
            val h = g.hw(end - 1)
            if (h > MIN_HALF_WIDTH) {
                b.circleColored(g.cx(end - 1), g.cy(end - 1), h, tolerance, packPencilPressure(ink, pressureAt(end - 1)))
            }
        }
        return b.build()
    }

    /**
     * Packed ARGB for pencil ink carrying raw stylus pressure `[0, 1]` in the
     * alpha channel. The cover shader maps pressure to graphite tone and
     * multiplies the grain; because the stencil guarantees each pixel is
     * covered once, interpolation across overlapping triangles never compounds.
     */
    fun packPencilPressure(ink: Rgba, pressure: Double): Int {
        val p = if (pressure.isNaN()) 0.0 else pressure.coerceIn(0.0, 1.0)
        val a = (p * 255.0).toInt().coerceIn(0, 255)
        return (a shl 24) or
            (ink.r.coerceIn(0, 255) shl 16) or
            (ink.g.coerceIn(0, 255) shl 8) or
            ink.b.coerceIn(0, 255)
    }

    /**
     * Where the two quads meeting at sample [k] attach to its disc, and the rim between them.
     *
     * A side whose edge turns less than [JOIN_DISC_ANGLE], or by less than [tolerance] of rim, gets
     * one vertex both quads share, half way round. Otherwise each quad keeps its own tangent point
     * and the rim between them is filled as one fan. The fan's points all lie on the disc, so it
     * never paints outside the ink, and from back left round the front to back right it covers the
     * notch on whichever side the stroke turns away from.
     */
    private fun join(
        b: MeshBuilder,
        x: Double,
        y: Double,
        r: Double,
        normals: DoubleArray,
        back: Int,
        front: Int,
        turnL: Double,
        turnR: Double,
        tolerance: Double,
        k: Int,
        backL: IntArray,
        backR: IntArray,
        frontL: IntArray,
        frontR: IntArray,
    ) {
        if (r <= MIN_HALF_WIDTH) {
            val c = b.vertex(x, y)
            backL[k] = c
            backR[k] = c
            frontL[k] = c
            frontR[k] = c
            return
        }
        val shareL = shares(r, turnL, tolerance)
        val shareR = shares(r, turnR, tolerance)
        if (shareL) {
            val v = midRim(b, x, y, r, normals, back, front)
            backL[k] = v
            frontL[k] = v
        } else {
            backL[k] = rim(b, x, y, r, normals[back], normals[back + 1])
            frontL[k] = rim(b, x, y, r, normals[front], normals[front + 1])
        }
        if (shareR) {
            val v = midRim(b, x, y, r, normals, back + 2, front + 2)
            backR[k] = v
            frontR[k] = v
        } else {
            backR[k] = rim(b, x, y, r, normals[back + 2], normals[back + 3])
            frontR[k] = rim(b, x, y, r, normals[front + 2], normals[front + 3])
        }
        if (shareL && shareR) return

        val step = 2.0 * PI / circleSegments(r, tolerance)
        val anchor = backL[k]
        var last = -1
        fun fan(v: Int) {
            if (v == anchor || v == last) return
            if (last >= 0) b.triangle(anchor, last, v)
            last = v
        }
        if (!shareL) arc(b, x, y, r, normals[back], normals[back + 1], turnL, step) { fan(it) }
        fan(frontL[k])
        fan(frontR[k])
        if (!shareR) arc(b, x, y, r, normals[front + 2], normals[front + 3], -turnR, step) { fan(it) }
        fan(backR[k])
    }

    /** Whether one vertex can stand for both quads' tangent points on a side whose edge turns [turn]. */
    private fun shares(r: Double, turn: Double, tolerance: Double): Boolean =
        abs(turn) <= JOIN_DISC_ANGLE || r * (1.0 - cos(turn / 2.0)) <= tolerance

    /** The rim points strictly between normal ([nx], [ny]) and that normal turned by [sweep]. */
    private inline fun arc(
        b: MeshBuilder,
        x: Double,
        y: Double,
        r: Double,
        nx: Double,
        ny: Double,
        sweep: Double,
        step: Double,
        emit: (Int) -> Unit,
    ) {
        val parts = ceil(abs(sweep) / step).toInt()
        for (t in 1 until parts) {
            val a = sweep * t / parts
            val c = cos(a)
            val s = sin(a)
            emit(rim(b, x, y, r, nx * c - ny * s, nx * s + ny * c))
        }
    }

    /** Signed angle from the normal at [a] to the one at [c], both packed x, y in [normals]. */
    private fun turn(normals: DoubleArray, a: Int, c: Int): Double {
        val ax = normals[a]
        val ay = normals[a + 1]
        val cx = normals[c]
        val cy = normals[c + 1]
        return atan2(ax * cy - ay * cx, ax * cx + ay * cy)
    }

    /** A vertex on the rim of disc ([x], [y], [r]) along unit normal ([nx], [ny]), carrying its offset. */
    private fun rim(b: MeshBuilder, x: Double, y: Double, r: Double, nx: Double, ny: Double): Int =
        b.vertex(x + r * nx, y + r * ny, r * nx, r * ny)

    /** The rim vertex half way between the normals at [a] and [c] in [normals]. */
    private fun midRim(b: MeshBuilder, x: Double, y: Double, r: Double, normals: DoubleArray, a: Int, c: Int): Int {
        val mx = normals[a] + normals[c]
        val my = normals[a + 1] + normals[c + 1]
        val len = hypot(mx, my)
        return rim(b, x, y, r, mx / len, my / len)
    }

    /**
     * The dashed pen: the smoothed centreline cut into on/off runs, each drawn as a constant-width
     * ribbon with round ends. Same runs, same width and same round caps the paged renderer's dashed
     * pen paints, so a dashed stroke breaks in the same places on either canvas.
     *
     * A stroke too short to have a line is left to [tessellate], which draws it as the dot the paged
     * painter falls back to.
     */
    fun tessellateDashed(
        g: RibbonPoints,
        dashLength: Double,
        dashGap: Double,
        halfWidth: Double,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): MeshData {
        val n = g.pointCount
        if (n < 2) return tessellate(g, tolerance)
        return tessellateDashed(g, 0, n, dashLength, dashGap, halfWidth, 0.0, tolerance)
    }

    /**
     * [tessellateDashed] over a run, picking the pattern up [phase] units in. Splitting a dashed
     * line into runs would otherwise restart its rhythm at every seam; the phase is the arc the
     * runs before it spent, so the dashes land where an unbroken line would have put them.
     */
    fun tessellateDashed(
        g: RibbonPoints,
        from: Int,
        count: Int,
        dashLength: Double,
        dashGap: Double,
        halfWidth: Double,
        phase: Double,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): MeshData {
        if (count < 2) return tessellate(g, from, count, tolerance)
        if (halfWidth <= MIN_HALF_WIDTH) return MeshData.EMPTY
        val path = ArrayList<Pt>(count)
        for (i in from until from + count) path.add(Pt(g.cx(i), g.cy(i)))
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        for (run in MeshBuilder.dashRuns(path, dashLength, dashGap, closed = false, phase = phase)) {
            b.polylineRibbon(run, halfWidth, closed = false, tolerance = tolerance)
        }
        return b.build()
    }

    /**
     * The ribbon at a fraction of its width, built from the centreline and scaled half-widths
     * rather than from the rails, since the rails only exist at full width.
     */
    private fun scaledRibbon(g: RibbonPoints, from: Int, count: Int, tolerance: Double, widthScale: Double): MeshData {
        val end = from + count
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        for (i in from until end - 1) {
            val h0 = g.hw(i) * widthScale
            val h1 = g.hw(i + 1) * widthScale
            if (h0 <= MIN_HALF_WIDTH && h1 <= MIN_HALF_WIDTH) continue
            val dx = g.cx(i + 1) - g.cx(i)
            val dy = g.cy(i + 1) - g.cy(i)
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val nx = -dy / len
            val ny = dx / len
            val l0 = b.vertex(g.cx(i) + nx * h0, g.cy(i) + ny * h0, nx * h0, ny * h0)
            val r0 = b.vertex(g.cx(i) - nx * h0, g.cy(i) - ny * h0, -nx * h0, -ny * h0)
            val l1 = b.vertex(g.cx(i + 1) + nx * h1, g.cy(i + 1) + ny * h1, nx * h1, ny * h1)
            val r1 = b.vertex(g.cx(i + 1) - nx * h1, g.cy(i + 1) - ny * h1, -nx * h1, -ny * h1)
            b.triangle(l0, r0, r1)
            b.triangle(l0, r1, l1)
        }
        // A disc at every sample, since a segment-normal ribbon gaps at its joins.
        for (i in from until end) {
            val h = g.hw(i) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(i), g.cy(i), h, tolerance)
        }
        return b.build()
    }

    /** Segments a circle of [radius] needs to stay within [tolerance] of true. */
    fun circleSegments(radius: Double, tolerance: Double): Int =
        MeshBuilder.circleSegments(radius, tolerance)

    /** Angle between the ribbon's normal before and after sample [i], in radians. */
    fun turnAngle(g: RibbonPoints, i: Int): Double {
        val n = g.pointCount
        if (i <= 0 || i >= n - 1) return 0.0
        val ax = g.cx(i) - g.cx(i - 1)
        val ay = g.cy(i) - g.cy(i - 1)
        val bx = g.cx(i + 1) - g.cx(i)
        val by = g.cy(i + 1) - g.cy(i)
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < 1e-12 || lb < 1e-12) return 0.0
        val cross = (ax * by - ay * bx) / (la * lb)
        val dot = (ax * bx + ay * by) / (la * lb)
        return kotlin.math.abs(atan2(cross, dot))
    }

    private fun estimateVertices(n: Int): Int = 2 * n + 2 * (MIN_CIRCLE_SEGMENTS + 2) + 16

    private fun estimatePencilVertices(n: Int): Int = 3 * n + 3 * (MIN_CIRCLE_SEGMENTS + 2) + 24

    private fun estimateIndices(n: Int): Int = 6 * maxOf(0, n - 1) + 6 * (MIN_CIRCLE_SEGMENTS + 2) + 48
}

