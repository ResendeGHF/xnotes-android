package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.stroke.PencilStyle
import com.xnotes.core.stroke.RibbonPoints
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

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
 * The ribbon needs no triangulator. [RibbonPoints] already carries both rails, a vertex on each
 * per centreline point, so a quad's four corners are read straight off them. Consecutive quads
 * share their whole edge exactly, so the body is watertight with no join geometry at all.
 *
 * What the rails do not cover is the round ends, and the outer notch where the stroke turns hard
 * enough that the two quads pinch. Both are filled with a disc, which is what the paged renderer
 * sweeps down the centerline, so the silhouette matches the ink the rest of the app draws.
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
     * Turn angle, in radians, past which a sample gets its own disc. Below it the two ribbon quads
     * already meet flush; above it their outer edges pinch and leave a notch the disc fills.
     * Smoothed handwriting turns a fraction of this per sample, so discs stay rare.
     */
    const val JOIN_DISC_ANGLE = 0.18

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
     * they sit inside the ribbon and change nothing about its silhouette.
     */
    fun tessellate(
        g: RibbonPoints,
        from: Int,
        count: Int,
        tolerance: Double,
        widthScale: Double = 1.0,
    ): MeshData {
        if (count <= 0) return MeshData.EMPTY
        val end = from + count
        val b = MeshBuilder(estimateVertices(count), estimateIndices(count))
        if (count == 1) {
            val h = g.hw(from) * widthScale
            if (h > MIN_HALF_WIDTH) b.circle(g.cx(from), g.cy(from), h, tolerance)
            return b.build()
        }
        if (widthScale != 1.0) return scaledRibbon(g, from, count, tolerance, widthScale)
        if (!g.hasRails) return b.build() // geometry without rails: nothing to draw

        // Body: one quad per segment, both of its vertices taken straight off the rails, so
        // consecutive quads share an entire edge and the ribbon never gaps along its length.
        for (i in from until end - 1) {
            val h0 = g.hw(i)
            val h1 = g.hw(i + 1)
            if (h0 <= MIN_HALF_WIDTH && h1 <= MIN_HALF_WIDTH) continue
            // Each rail vertex remembers how far it sits from the centreline, so a stroke thinner
            // than a pixel can be widened back to one and faded instead of breaking up.
            val l0 = b.vertex(g.leftX(i), g.leftY(i), g.leftX(i) - g.cx(i), g.leftY(i) - g.cy(i))
            val r0 = b.vertex(g.rightX(i), g.rightY(i), g.rightX(i) - g.cx(i), g.rightY(i) - g.cy(i))
            val l1 = b.vertex(g.leftX(i + 1), g.leftY(i + 1), g.leftX(i + 1) - g.cx(i + 1), g.leftY(i + 1) - g.cy(i + 1))
            val r1 = b.vertex(g.rightX(i + 1), g.rightY(i + 1), g.rightX(i + 1) - g.cx(i + 1), g.rightY(i + 1) - g.cy(i + 1))
            b.triangle(l0, r0, r1)
            b.triangle(l0, r1, l1)
        }

        // Round ends. A whole disc rather than a half one: it costs two extra fans per stroke and
        // removes every orientation question, and it is exactly the disc the paged renderer sweeps.
        if (g.hw(from) > MIN_HALF_WIDTH) b.circle(g.cx(from), g.cy(from), g.hw(from), tolerance)
        if (g.hw(end - 1) > MIN_HALF_WIDTH) b.circle(g.cx(end - 1), g.cy(end - 1), g.hw(end - 1), tolerance)

        // Discs at the hard turns only.
        for (i in from + 1 until end - 1) {
            val h = g.hw(i)
            if (h <= MIN_HALF_WIDTH) continue
            if (turnAngle(g, i) > JOIN_DISC_ANGLE) b.circle(g.cx(i), g.cy(i), h, tolerance)
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

    // --- rail accessors: kept as free functions so the tests can pin them to the geometry's own ---

    fun leftX(g: RibbonPoints, n: Int, i: Int): Double = g.leftX(i)
    fun leftY(g: RibbonPoints, n: Int, i: Int): Double = g.leftY(i)
    fun rightX(g: RibbonPoints, n: Int, i: Int): Double = g.rightX(i)
    fun rightY(g: RibbonPoints, n: Int, i: Int): Double = g.rightY(i)

    private fun estimateVertices(n: Int): Int = 2 * n + 2 * (MIN_CIRCLE_SEGMENTS + 2) + 16

    private fun estimatePencilVertices(n: Int): Int = 3 * n + 3 * (MIN_CIRCLE_SEGMENTS + 2) + 24

    private fun estimateIndices(n: Int): Int = 6 * maxOf(0, n - 1) + 6 * (MIN_CIRCLE_SEGMENTS + 2) + 48
}

