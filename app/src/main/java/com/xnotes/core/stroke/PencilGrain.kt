package com.xnotes.core.stroke

import kotlin.math.max

/**
 * The pencil's graphite as a page-anchored noise field, not as stamped dots.
 *
 * Both renderers evaluate the same field over the same page coordinates, so the
 * live stroke under the pen and the committed stroke are one texture by
 * construction — there is no dotted fallback to drift into. The field follows
 * the reference model (layered seamless value-noise octaves): coarse lumps and
 * medium tooth for tonal drift, fine grit with a salt-and-pepper sparkle, and
 * two stretched fibre octaves mixed together so the graphite reads as dragged
 * pigment rather than uniform speckle. A smooth threshold turns the deposit
 * into coverage, with a whisper of haze left in the gaps so paper shows as
 * paper instead of halftone holes.
 *
 * Two per-stroke parameters shape the deposit the way a real tip does: the tip
 * mix fades the coarse bands for fine tips (a thin stroke reads as fine
 * speckle, not blotches) and the integer seed decorrelates the grain between
 * strokes. Both are pure functions of the stroke itself, so live runs and the
 * committed stroke evaluate the identical field.
 *
 * The lattice wraps over a 128 px supertile (every octave period divides it,
 * and the chunk size is a multiple of it), which is what keeps the grain
 * paper-locked: both sides wrap the same absolute page position, so live and
 * committed evaluate the identical field. The integer hash is exact on every
 * GPU (32-bit ops that never overflow) and bit-identical in Kotlin, so live and
 * committed keep the same cells up to rasterization.
 */
object PencilGrain {
    /** How far paint can stray past the plain ribbon: the rasterizer's feather. */
    const val BOUNDS_OUTSET = 2.0

    /** Field supertile, in content px. Every octave period divides it, and the
     *  canvas chunk is a multiple of it, so wrapping keeps the grain glued to
     *  the paper on every renderer. Pinned by test. */
    const val FIELD_TILE = 128.0

    /** Deposit base and tip-mix blend (see [tipMixFor]): a fine tip fades the
     *  coarse bands so a thin stroke reads as fine speckle, not blotches. */
    const val FIELD_DEPOSIT_FINE = 0.645
    const val FIELD_DEPOSIT_BROAD = 0.62

    /** Threshold floor and span over the graphite load. Matches the GL shader. */
    const val FIELD_EDGE_BASE = 0.72
    const val FIELD_EDGE_SPAN = 0.40
    const val FIELD_EDGE_FLOOR = 0.03375

    /** Smoothstep width of the coverage transition at unit contrast. */
    const val FIELD_SOFT_FINE = 0.20
    const val FIELD_SOFT_BROAD = 0.30

    /** Transition sharpness, mirroring the reference contrast control. */
    const val FIELD_CONTRAST = 1.0

    /** Darkest graphite deposit, so hard strokes keep tooth instead of
     *  flattening to marker black. Matches the GL shader. */
    const val FIELD_OPACITY_MAX = 0.85

    /** Haze floor and mix in the gaps: paper shows, never halftone holes. */
    const val FIELD_HAZE = 0.05
    const val FIELD_HAZE_KEEP = 0.95

    /** Per-stroke field parameters, carried on the mesh part so live runs and
     *  the committed stroke shade identically. */
    data class PencilParams(val seed: Int, val tipMix: Float)

    /**
     * Deposit-structure blend for a tip of [sizePx] page px: 0 = fine tip
     * (fine speckle, no broad blotches), 1 = broad tip (full lump/tooth
     * drift). A fine tip fades the coarse bands instead of resampling finer,
     * so the grain never shimmers under pan or zoom.
     */
    fun tipMixFor(sizePx: Double): Double = ((sizePx - 12.0) / 60.0).coerceIn(0.0, 1.0)

    /**
     * Per-stroke decorrelation seed from a ribbon point, quantized coarsely so
     * the live ribbon and the committed geometry (whose smoothing can differ
     * by a fraction of a pixel) agree exactly. In `[0, 255]`.
     */
    fun seedFor(x: Double, y: Double): Int {
        val qx = wrapCell(kotlin.math.floor(x / 4.0).toLong(), 64)
        val qy = wrapCell(kotlin.math.floor(y / 4.0).toLong(), 64)
        return cellHash(qx, qy)
    }

    /**
     * The shared integer lattice mix: 32-bit ops that never overflow (inputs
     * stay small), so Kotlin `Int` and GLSL `highp int` agree bit-for-bit and
     * the field can never NaN out. Returns the low byte, `[0, 255]`.
     */
    fun cellHash(cx: Int, cy: Int): Int {
        var h = cx * 73 + cy * 149
        h = (h xor 61) xor (h shr 3)
        h += h shl 5
        h = (h xor (h shr 7)) and 0xFFFF
        h += h shl 6
        h = h xor (h shr 5)
        return h and 0xFF
    }

    /** Effective pressure in `[0, 1]` read back off a ribbon half-width. */
    fun pressureFromWidth(halfWidth: Double, baseWidth: Double, minFactor: Double): Double {
        if (halfWidth <= 0.0) return 0.0
        val full = max(baseWidth, 1e-9)
        return (((halfWidth * 2.0 / full) - minFactor) / max(1.0 - minFactor, 1e-9)).coerceIn(0.0, 1.0)
    }

    /**
     * Graphite load in `[0, 1]` at a ribbon point: pressure times the grain gain.
     * Feeds the GL vertex density and the paged threshold alike.
     */
    fun densityAt(halfWidth: Double, baseWidth: Double, minFactor: Double, grain: Double): Double {
        if (halfWidth <= 0.0 || baseWidth <= 0.0) return 0.0
        // No tooth: fully loaded everywhere (the solid fast path on both renderers).
        if (grain <= 0.0) return 1.0
        val p = pressureFromWidth(halfWidth, baseWidth, minFactor)
        return ((0.25 + 0.75 * p) * (0.5 + 0.9 * grain.coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0)
    }

    /**
     * Across-ribbon edge in `[0, 1]`: 0 on the centreline, 1 at the rail and past
     * it. Linear, exactly what the pencil strip's rail/centre interpolation carries
     * on the GL side, so both sides read the same falloff.
     */
    fun edgeAt(dist: Double, halfWidth: Double): Double {
        if (halfWidth <= 0.0 || dist >= halfWidth) return 1.0
        if (dist <= 0.0) return 0.0
        return (dist / halfWidth).coerceIn(0.0, 1.0)
    }

    /**
     * What a pencil mesh vertex carries in its packed ARGB: the graphite density
     * in alpha, the across-ribbon edge (0 centre, 1 rail) in red. The ink colour
     * itself arrives as a shader uniform, so the bytes are control, not colour.
     */
    fun packVertex(isEdge: Boolean, density: Double): Int {
        val d = (density.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
        return (d shl 24) or (if (isEdge) 0xFF0000 else 0)
    }

    fun unpackDensity(packed: Int): Double = ((packed ushr 24) and 0xFF) / 255.0

    fun unpackEdge(packed: Int): Double = ((packed shr 16) and 0xFF) / 255.0

    /**
     * Zoom-to-LOD ramp in `[0, 1]`: full grain at normal zoom, averaged out when
     * minified so distant ink reads as a line instead of shimmering static. The
     * GL shader evaluates the identical ramp from its zoom uniform; the paged
     * rasterizer derives it from its canvas scale the same way. [coverage]
     * applies its own smoothstep on top, mirroring the shader exactly.
     */
    fun lodQuality(scale: Double): Double = ((scale - 0.25) / 0.75).coerceIn(0.0, 1.0)

    /** Floor-mod: wraps negative lattice coordinates into `[0, m)` like GLSL mod. */
    fun wrapCell(v: Long, m: Int): Int = (((v % m) + m) % m).toInt()

    /**
     * One seamless value-noise octave over lattice coords ([lx], [ly]) with a
     * wrapping period ([wrapX], [wrapY]) cells and a [salt] stream. Bilinear
     * with a smoothstep fade, in `[0, 1]`. Bit-identical to the GL port for the
     * same inputs up to float rounding.
     */
    fun noise(lx: Double, ly: Double, wrapX: Int, wrapY: Int, salt: Int): Double {
        val fx = kotlin.math.floor(lx)
        val fy = kotlin.math.floor(ly)
        val tx = lx - fx
        val ty = ly - fy
        val ux = tx * tx * (3.0 - 2.0 * tx)
        val uy = ty * ty * (3.0 - 2.0 * ty)
        val x0 = wrapCell(fx.toLong() + salt * 57, wrapX)
        val y0 = wrapCell(fy.toLong() + salt * 131, wrapY)
        val x1 = wrapCell(fx.toLong() + 1 + salt * 57, wrapX)
        val y1 = wrapCell(fy.toLong() + 1 + salt * 131, wrapY)
        val a = cellHash(x0, y0) / 255.0
        val b = cellHash(x1, y0) / 255.0
        val c = cellHash(x0, y1) / 255.0
        val d = cellHash(x1, y1) / 255.0
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    /**
     * Graphite coverage in `[0, 1]` at the page point ([x], [y]): the shared
     * decision function. [density] is the ribbon load there, [edge] the
     * across-ribbon position (0 centre, 1 rail), [quality] the LOD fade,
     * [seed] the stroke's decorrelation seed and [tipMix] its tip blend (see
     * [tipMixFor]). The GL fragment shader evaluates the same field; the two
     * agree up to float rounding of the arithmetic.
     */
    fun coverage(
        x: Double, y: Double,
        density: Double, edge: Double, quality: Double,
        seed: Int = 0, tipMix: Double = 0.0,
    ): Double {
        val ss = seed * 13
        val tip = tipMix.coerceIn(0.0, 1.0)
        val coarse = 0.30 + 0.70 * tip
        val medium = 0.62 + 0.38 * tip
        // Coarse lumps, fractal stack (features ~64/32/16 px).
        val lump = (noise(x / 32.0, y / 32.0, 4, 4, 11 + ss) +
            0.5 * noise(x / 16.0, y / 16.0, 8, 8, 11 + 7919 + ss) +
            0.25 * noise(x / 8.0, y / 8.0, 16, 16, 11 + 15838 + ss)) / 1.75
        // Medium tooth, fractal stack (features ~8/4/2 px).
        val tooth = (noise(x / 8.0, y / 8.0, 16, 16, 23 + ss) +
            0.5 * noise(x / 4.0, y / 4.0, 32, 32, 23 + 7919 + ss) +
            0.25 * noise(x / 2.0, y / 2.0, 64, 64, 23 + 15838 + ss)) / 1.75
        // Fine grit, fractal stack (features ~4/2/1 px) with a whisper of
        // per-pixel sparkle: graphite dust under magnification.
        val gritFine = (noise(x / 4.0, y / 4.0, 32, 32, 37 + ss) +
            0.5 * noise(x / 2.0, y / 2.0, 64, 64, 37 + 7919 + ss) +
            0.25 * noise(x, y, 128, 128, 37 + 15838 + ss)) / 1.75
        val sparkle = cellHash(
            wrapCell(kotlin.math.floor(x).toLong() + seed * 3, 128),
            wrapCell(kotlin.math.floor(y).toLong() + seed * 7, 128),
        ) / 255.0
        val grit = gritFine * 0.82 + sparkle * 0.18
        // Mid-frequency streak grit the fibre contrast rides on.
        val mid = (noise(x / 2.0, y / 2.0, 64, 64, 43 + ss) +
            0.5 * noise(x, y, 128, 128, 43 + 7919 + ss)) / 1.5
        // Fibre streaks the tip drags pigment along, stretched along the
        // stroke in both orientations and mixed, so no direction dominates.
        val fibreA = (noise(x / 32.0, y / 2.0, 4, 64, 53 + ss) +
            0.5 * noise(x / 16.0, y, 8, 128, 53 + 7919 + ss)) / 1.5
        val fibreB = (noise(x / 2.0, y / 32.0, 64, 4, 59 + ss) +
            0.5 * noise(x, y / 16.0, 128, 8, 59 + 7919 + ss)) / 1.5
        val fibre = (fibreA + fibreB) * 0.5
        // Slow pressure drift along the stroke (magnified fetch); the coarse
        // band stands in when minified so the grain averages out.
        val drift = if (quality < 0.5) {
            lump
        } else {
            0.6 * noise(x / 64.0, y / 64.0, 2, 2, 67 + ss) +
                0.4 * noise(x / 32.0, y / 32.0, 4, 4, 71 + ss)
        }
        val slow = 0.30 * (lump - 0.5) * coarse +
            0.32 * (tooth - 0.5) * medium +
            0.22 * (fibre - 0.5) * medium +
            0.22 * (drift - 0.5) * coarse
        val fast = (grit - 0.5) * 0.95 * (1.10 - 0.15 * tip) +
            (mid - 0.5) * 0.40
        val deposit = (FIELD_DEPOSIT_FINE - 0.025 * tip) + slow + fast
        // Threshold rides the load: light strokes break into sparse flecks,
        // hard ones fill toward solid, rails fade softer than the centre.
        // Fine tips keep a harder, slightly higher edge: crisp pinhole gaps
        // instead of washed-out stretches.
        val deff = density.coerceIn(0.0, 1.0) * (1.0 - 0.45 * edge.coerceIn(0.0, 1.0))
        val edge0 = (FIELD_EDGE_BASE - FIELD_EDGE_SPAN * deff + FIELD_EDGE_FLOOR +
            0.045 * (1.0 - tip)).coerceIn(0.02, 0.95)
        val soft = (FIELD_SOFT_FINE + (FIELD_SOFT_BROAD - FIELD_SOFT_FINE) * tip) / FIELD_CONTRAST
        val t = ((deposit - edge0) / soft).coerceIn(0.0, 1.0)
        val softened = t * t * (3.0 - 2.0 * t)
        val grain = FIELD_HAZE + (softened - FIELD_HAZE) * FIELD_HAZE_KEEP
        // Per-pixel density jitter at the finest scale, fading out minified.
        // (The lod ramp matches smoothstep(0.45, 0.95, quality) in the shader.)
        val lq = quality.coerceIn(0.0, 1.0)
        val ls = ((lq - 0.45) / 0.5).coerceIn(0.0, 1.0)
        val lod = ls * ls * (3.0 - 2.0 * ls)
        val densityJitter = 1.0 + (0.70 + 0.62 * grit - 1.0) * lod
        return (grain * FIELD_OPACITY_MAX * densityJitter).coerceIn(0.0, 1.0)
    }
}
