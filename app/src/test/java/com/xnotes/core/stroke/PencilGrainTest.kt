package com.xnotes.core.stroke

import com.xnotes.gl.InkShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pencil's page-anchored graphite field, shared bit-for-bit between the GL
 * fragment shader and the paged rasterizer.
 *
 * Everything here pins what the two renderers rely on: the same page position
 * hashes to the same lattice on both sides (wrapping is seamless, negatives
 * included), the deposit math matches the shader's octave stack, and coverage
 * answers graphite load the same way — so committed ink is the live texture
 * rather than a dotted fallback.
 */
class PencilGrainTest {

    @Test fun fieldTileDividesTheChunk() {
        // The camera-chunk term vanishes mod the tile, which is what keeps the
        // field glued to the paper on the GL side.
        assertEquals(0.0, 4096.0 % PencilGrain.FIELD_TILE, 0.0)
        assertEquals(PencilGrain.FIELD_TILE, InkShader.PENCIL_FIELD_TILE, 0.0)
    }

    @Test fun cellHashMatchesKnownAnswers() {
        // Known answers pin the exact integer mix both sides share; if either
        // side drifts, live and committed speckle apart.
        assertEquals(176, PencilGrain.cellHash(0, 0))
        assertEquals(134, PencilGrain.cellHash(1, 0))
        assertEquals(125, PencilGrain.cellHash(0, 1))
        assertEquals(203, PencilGrain.cellHash(19, 7))
        assertEquals(174, PencilGrain.cellHash(-5, -7))
    }

    @Test fun noiseIsDeterministicAndBounded() {
        val a = PencilGrain.noise(10.25, 40.75, 8, 8, 11)
        assertEquals(a, PencilGrain.noise(10.25, 40.75, 8, 8, 11), 0.0)
        assertTrue(a in 0.0..1.0)
        // Different salts decorrelate.
        assertTrue(PencilGrain.noise(10.25, 40.75, 8, 8, 12) != a)
        // The field tiles seamlessly: sampling one period over reads the same.
        assertEquals(
            PencilGrain.noise(3.5, 9.25, 8, 8, 11),
            PencilGrain.noise(3.5 + 8.0, 9.25 - 16.0, 8, 8, 11),
            0.0,
        )
        // Negative coords wrap instead of clamping (no mirrored seam).
        assertEquals(
            PencilGrain.noise(-3.5, -9.25, 8, 8, 11),
            PencilGrain.noise(-3.5 + 16.0, -9.25 + 16.0, 8, 8, 11),
            0.0,
        )
        var varied = false
        var x = 0.0
        while (x < 8.0) {
            if (PencilGrain.noise(x, 1.5, 8, 8, 11) != PencilGrain.noise(x + 0.5, 1.5, 8, 8, 11)) {
                varied = true
                break
            }
            x += 0.5
        }
        assertTrue(varied)
    }

    @Test fun coverageAnswersLoadAndStaysInRange() {
        // Patch statistics: a single page point can be a paper fleck even at
        // full load (that is the tooth), so the load response is pinned over
        // a patch, not per pixel.
        fun patch(density: Double, edge: Double, seed: Int = 7): List<Double> {
            val out = ArrayList<Double>(64)
            for (j in 0 until 8) for (i in 0 until 8) {
                out.add(PencilGrain.coverage(123.4 + i * 0.5, 567.8 + j * 0.5, density, edge, 1.0, seed))
            }
            return out
        }
        // Hard press, centre: mostly solid graphite with tooth showing through.
        val hard = patch(1.0, 0.0)
        assertTrue(hard.all { it in 0.0..1.0 })
        assertTrue("hard mean=${hard.average()}", hard.average() > 0.5)
        assertTrue("hard graphite=${hard.count { it > 0.5 }}", hard.count { it > 0.5 } >= 48)
        // No load: bare paper with only sparse flecks.
        val bare = patch(0.0, 1.0)
        assertTrue(bare.all { it in 0.0..1.0 })
        assertTrue("bare mean=${bare.average()}", bare.average() < 0.3)
        assertTrue("bare graphite=${bare.count { it > 0.5 }}", bare.count { it > 0.5 } <= 16)
        // Lighter load keeps less graphite than hard ink over the same patch.
        val mid = patch(0.4, 0.2)
        assertTrue("mid mean=${mid.average()}", mid.average() in bare.average()..hard.average())
        // Deterministic per page position.
        assertEquals(
            PencilGrain.coverage(123.4, 567.8, 1.0, 0.0, 1.0, 7),
            PencilGrain.coverage(123.4, 567.8, 1.0, 0.0, 1.0, 7),
            0.0,
        )
        // The field is periodic in the supertile: panning by whole tiles keeps
        // the same grain glued to the same paper.
        assertEquals(
            PencilGrain.coverage(123.4, 567.8, 1.0, 0.0, 1.0, 7),
            PencilGrain.coverage(123.4 + 128.0, 567.8 - 256.0, 1.0, 0.0, 1.0, 7),
            0.0,
        )
        // Minified LOD relaxes toward solid instead of sparkling.
        val lodLow = PencilGrain.coverage(123.4, 567.8, 0.5, 0.0, 0.0)
        assertTrue(lodLow in 0.0..1.0)
    }

    @Test fun densityAnswersPressureAndGrain() {
        val base = 2.5
        val m = 0.30
        // Full width, default grain: the grain gain below solid.
        assertEquals(0.5 + 0.9 * 0.55, PencilGrain.densityAt(base / 2.0, base, m, 0.55), 1e-9)
        // Full width, full grain: a solid core.
        assertEquals(1.0, PencilGrain.densityAt(base / 2.0, base, m, 1.0), 1e-9)
        // Zero width: nothing.
        assertEquals(0.0, PencilGrain.densityAt(0.0, base, m, 0.55), 0.0)
        // Light ink breaks up more than hard ink at the same setting.
        val light = PencilGrain.densityAt(0.45, base, m, 0.55)
        val hard = PencilGrain.densityAt(1.2, base, m, 0.55)
        assertTrue(light in 0.0..1.0 && hard in 0.0..1.0)
        assertTrue(hard > light)
        // No grain: fully loaded everywhere (the solid fast path).
        assertEquals(1.0, PencilGrain.densityAt(0.4, base, m, 0.0), 0.0)
        // Grain scales the load monotonically.
        assertTrue(PencilGrain.densityAt(1.2, base, m, 1.0) >= PencilGrain.densityAt(1.2, base, m, 0.2))
        // pressureFromWidth inverts the ribbon width.
        assertEquals(1.0, PencilGrain.pressureFromWidth(base / 2.0, base, m), 1e-9)
        assertEquals(0.0, PencilGrain.pressureFromWidth(0.0, base, m), 0.0)
    }

    @Test fun edgeIsLinearAcrossTheRibbon() {
        assertEquals(0.0, PencilGrain.edgeAt(0.0, 2.0), 1e-9)
        assertEquals(0.5, PencilGrain.edgeAt(1.0, 2.0), 1e-9)
        assertEquals(1.0, PencilGrain.edgeAt(2.0, 2.0), 0.0)
        assertEquals(1.0, PencilGrain.edgeAt(99.0, 2.0), 0.0)
    }

    @Test fun packUnpackRoundTrip() {
        val packed = PencilGrain.packVertex(isEdge = true, density = 0.5)
        assertEquals(0.5, PencilGrain.unpackDensity(packed), 1.0 / 255.0 + 1e-9)
        assertEquals(1.0, PencilGrain.unpackEdge(packed), 0.0)
        val centre = PencilGrain.packVertex(isEdge = false, density = 1.0)
        assertEquals(1.0, PencilGrain.unpackDensity(centre), 0.0)
        assertEquals(0.0, PencilGrain.unpackEdge(centre), 0.0)
    }

    @Test fun tipMixFadesCoarseBandsForFineTips() {
        // Reference mapping: 12 px tip reads as pure speckle, 72+ px as full drift.
        assertEquals(0.0, PencilGrain.tipMixFor(2.5), 0.0)
        assertEquals(0.0, PencilGrain.tipMixFor(12.0), 0.0)
        assertEquals(1.0, PencilGrain.tipMixFor(72.0), 0.0)
        assertEquals(0.5, PencilGrain.tipMixFor(42.0), 1e-9)
    }

    @Test fun seedIsStableAndDecorrelates() {
        // Quantized coarsely: sub-pixel smoothing differences between the live
        // ribbon and the committed geometry must not flip the seed mid-stroke.
        val a = PencilGrain.seedFor(100.0, 200.0)
        assertEquals(a, PencilGrain.seedFor(101.0, 201.0))
        assertTrue(a in 0..255)
        // Different paper, different grain.
        assertTrue(PencilGrain.seedFor(900.0, 100.0) != PencilGrain.seedFor(100.0, 900.0))
        // The seed moves the field: same point, different seed, different graphite.
        val c0 = PencilGrain.coverage(123.4, 567.8, 0.8, 0.0, 1.0, seed = a, tipMix = 0.0)
        val c1 = PencilGrain.coverage(123.4, 567.8, 0.8, 0.0, 1.0, seed = (a + 37) % 256, tipMix = 0.0)
        assertTrue(c0 != c1)
    }

    /**
     * The fragment math, ported independently from the GLSL source (not
     * by calling it): the same lattice, fbm octave stacks, seed shift,
     * tip-mixed deposit and smoothstep. Agreement with [PencilGrain.coverage]
     * is what keeps live and committed graphite on the same field instead of
     * falling back to dots.
     */
    @Test fun fragmentFieldAgreesWithThePagedField() {
        for ((seed, tip) in listOf(Pair(7, 0.0), Pair(200, 1.0))) {
            var sum = 0.0
            var worst = 0.0
            var n = 0
            // Sweep page space, both signs, several densities and rail positions.
            var x = -260.0
            while (x < 260.0) {
                var y = -260.0
                while (y < 260.0) {
                    for (d in doubleArrayOf(0.0, 0.2, 0.55, 1.0)) {
                        for (e in doubleArrayOf(0.0, 0.3, 0.7, 1.0)) {
                            val want = PencilGrain.coverage(x, y, d, e, 1.0, seed, tip)
                            val got = fragmentCoverage(x.toFloat(), y.toFloat(), d.toFloat(), e.toFloat(), seed, tip.toFloat())
                            val diff = kotlin.math.abs(want - got)
                            sum += diff
                            worst = maxOf(worst, diff)
                            n++
                        }
                    }
                    y += 9.0
                }
                x += 11.0
            }
            // Float-vs-double rounding only: the mean stays orders of magnitude
            // below one alpha step, and no pixel flips across the whole sweep.
            assertTrue("seed=$seed tip=$tip mean diff ${sum / n}", sum / n < 0.004)
            assertTrue("seed=$seed tip=$tip worst diff $worst", worst < 1.0 / 255.0)
        }
    }

    /** The shader's pencil branch in 32-bit float, for the agreement test. */
    private fun fragmentCoverage(px: Float, py: Float, density: Float, edge: Float, seed: Int, tipMix: Float): Float {
        fun lat(ix: Int, iy: Int): Float {
            var h = ix * 73 + iy * 149
            h = (h xor 61) xor (h shr 3)
            h += h shl 5
            h = (h xor (h shr 7)) and 0xFFFF
            h += h shl 6
            h = h xor (h shr 5)
            return (h and 0xFF) / 255.0f
        }
        fun wrap(v: Int, m: Int) = ((v % m) + m) % m
        fun noise(lx: Float, ly: Float, wx: Int, wy: Int, salt: Int): Float {
            val fx = kotlin.math.floor(lx).toInt()
            val fy = kotlin.math.floor(ly).toInt()
            val ux = lx - kotlin.math.floor(lx).toFloat()
            val uy = ly - kotlin.math.floor(ly).toFloat()
            val sx = ux * ux * (3.0f - 2.0f * ux)
            val sy = uy * uy * (3.0f - 2.0f * uy)
            val x0 = wrap(fx + salt * 57, wx)
            val y0 = wrap(fy + salt * 131, wy)
            val x1 = wrap(fx + 1 + salt * 57, wx)
            val y1 = wrap(fy + 1 + salt * 131, wy)
            val a = lat(x0, y0)
            val b = lat(x1, y0)
            val c = lat(x0, y1)
            val d = lat(x1, y1)
            return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
        }
        fun sparkleAt(x: Float, y: Float): Float {
            val cx = wrap(kotlin.math.floor(x).toInt() + seed * 3, 128)
            val cy = wrap(kotlin.math.floor(y).toInt() + seed * 7, 128)
            return lat(cx, cy)
        }
        val ss = seed * 13
        val tip = tipMix.coerceIn(0.0f, 1.0f)
        val coarse = 0.30f + 0.70f * tip
        val medium = 0.62f + 0.38f * tip
        val lump = (noise(px / 32.0f, py / 32.0f, 4, 4, 11 + ss) +
            0.5f * noise(px / 16.0f, py / 16.0f, 8, 8, 11 + 7919 + ss) +
            0.25f * noise(px / 8.0f, py / 8.0f, 16, 16, 11 + 15838 + ss)) / 1.75f
        val tooth = (noise(px / 8.0f, py / 8.0f, 16, 16, 23 + ss) +
            0.5f * noise(px / 4.0f, py / 4.0f, 32, 32, 23 + 7919 + ss) +
            0.25f * noise(px / 2.0f, py / 2.0f, 64, 64, 23 + 15838 + ss)) / 1.75f
        val gritF = (noise(px / 4.0f, py / 4.0f, 32, 32, 37 + ss) +
            0.5f * noise(px / 2.0f, py / 2.0f, 64, 64, 37 + 7919 + ss) +
            0.25f * noise(px, py, 128, 128, 37 + 15838 + ss)) / 1.75f
        val grit = gritF * 0.82f + sparkleAt(px, py) * 0.18f
        val mid = (noise(px / 2.0f, py / 2.0f, 64, 64, 43 + ss) +
            0.5f * noise(px, py, 128, 128, 43 + 7919 + ss)) / 1.5f
        val fa = (noise(px / 32.0f, py / 2.0f, 4, 64, 53 + ss) +
            0.5f * noise(px / 16.0f, py, 8, 128, 53 + 7919 + ss)) / 1.5f
        val fb = (noise(px / 2.0f, py / 32.0f, 64, 4, 59 + ss) +
            0.5f * noise(px, py / 16.0f, 128, 8, 59 + 7919 + ss)) / 1.5f
        val fibre = (fa + fb) * 0.5f
        val lumps = 0.6f * noise(px / 64.0f, py / 64.0f, 2, 2, 67 + ss) +
            0.4f * noise(px / 32.0f, py / 32.0f, 4, 4, 71 + ss)
        val deff = density.coerceIn(0.0f, 1.0f) * (1.0f - 0.45f * edge.coerceIn(0.0f, 1.0f))
        val deposit = (0.645f - 0.025f * tip) +
            0.30f * (lump - 0.5f) * coarse +
            0.32f * (tooth - 0.5f) * medium +
            0.22f * (fibre - 0.5f) * medium +
            0.22f * (lumps - 0.5f) * coarse +
            0.95f * (1.10f - 0.15f * tip) * (grit - 0.5f) +
            0.40f * (mid - 0.5f)
        val edge0 = (0.72f - 0.40f * deff + 0.03375f + 0.045f * (1.0f - tip)).coerceIn(0.02f, 0.95f)
        val softw = (0.20f + 0.10f * tip) / 1.0f
        val t = ((deposit - edge0) / softw).coerceIn(0.0f, 1.0f)
        val s = t * t * (3.0f - 2.0f * t)
        val grain = 0.05f + (s - 0.05f) * 0.95f
        // LOD at full zoom, mirroring the shader's smoothstep(0.45, 0.95, 1.0).
        val lod = 1.0f
        return (grain * 0.85f * (1.0f + (0.70f + 0.62f * grit - 1.0f) * lod)).coerceIn(0.0f, 1.0f)
    }
}
