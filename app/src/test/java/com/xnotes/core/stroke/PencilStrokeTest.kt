package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.StrokeTessellator
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.ribbonJoinStations
import com.xnotes.core.stroke.PencilStyle
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pencil end to end: the pen's swept ribbon at one constant width, toned
 * by pressure — and identical live and final.
 *
 * - pressure never changes the width, only the graphite tone (continuous on
 *   the GL cover, banded on the paged canvas);
 * - the mesh carries real ink colour with raw pressure, takes the PENCIL
 *   stencil-consume pass, and stays round everywhere (quads plus whole discs
 *   at the ends and hard turns — no miters, no fans);
 * - painting in runs (settled batches + the moving tail, sharing one station)
 *   tiles the ribbon exactly once, so baking settled runs lands exactly where
 *   a full repaint would.
 */
class PencilStrokeTest {

    private fun samples(count: Int, pressure: (Int) -> Double = { i -> 0.3 + 0.5 * (i % 5) / 4.0 }): List<Sample> {
        val out = ArrayList<Sample>(count)
        var i = 0
        while (i < count) {
            val u = i * 0.35
            out.add(Sample(100.0 + u * 9.0, 200.0 + kotlin.math.sin(u) * 12.0, pressure(i)))
            i++
        }
        return out
    }

    private fun pencil(pressure: (Int) -> Double = { i -> 0.3 + 0.5 * (i % 5) / 4.0 }): Stroke {
        val s = Stroke(Tool.PENCIL, ToolDefaults.configFor(Tool.PENCIL).copy(rgba = Rgba(64, 64, 64)))
        s.finished = false
        for (sm in samples(120, pressure)) s.addSample(sm)
        s.finished = true
        s.trimToSize()
        return s
    }

    @Test fun pressureShadesIntensityNotWidth() {
        val light = pencil { 0.25 }
        val hard = pencil { 0.9 }
        val gl = light.geometry()
        val gh = hard.geometry()
        assertEquals(gl.pointCount, gh.pointCount)
        // Same path, same widths at every point.
        for (i in 0 until gl.pointCount) {
            assertEquals(gh.hw(i), gl.hw(i), 0.0)
        }
        // But the harder stroke carries denser graphite on average.
        assertTrue(meanDensity(hard, gh) > meanDensity(light, gl))
    }

    /** Default knobs, as a fresh pencil config maps them. */
    private fun testStyle() = PencilStyle(
        phaseX = 0.0, phaseY = 0.0,
        pressure = 1.0, density = 1.0, grainSize = 1.0, shadeResponse = 1.0,
    )

    @Test fun zeroPressureResponseReadsMaximum() {
        // Gain 0 means "no response": pressure always reads maximum, so the
        // shade stays darkest and the density densest whatever the stylus
        // reports — matching the GL cover's uPencilCfg.y <= 0 branch.
        val zero = testStyle().copy(pressure = 0.0)
        for (p in listOf(0.0, 0.2, 0.5, 0.9, 1.0)) {
            assertEquals(1.0, StrokeTessellator.pencilEffectivePressure(p, zero.pressure), 0.0)
            assertEquals(
                StrokeTessellator.pencilDensity(1.0, testStyle()),
                StrokeTessellator.pencilDensity(p, zero), 1e-9,
            )
            assertEquals(
                StrokeTessellator.pencilShade(1.0, testStyle()),
                StrokeTessellator.pencilShade(p, zero), 1e-9,
            )
        }
    }

    @Test fun grainPhaseIsStablePerStrokeAndVariesAcrossStrokes() {        val cfg = ToolDefaults.configFor(Tool.PENCIL)
        val a = PencilStyle.of(100.0, 200.0, cfg)
        val b = PencilStyle.of(100.0, 200.0, cfg)
        // Same first sample: identical phase, so live and committed agree
        // and save/load changes nothing.
        assertEquals(a.phaseX, b.phaseX, 0.0)
        assertEquals(a.phaseY, b.phaseY, 0.0)
        val c = PencilStyle.of(500.0, 600.0, cfg)
        assertTrue(c.phaseX != a.phaseX || c.phaseY != a.phaseY)
        // Knobs clamp into their UI ranges instead of escaping.
        val wild = cfg.copy(pencilPressure = 99.0, pencilDensity = -5.0, pencilGrainSize = 99.0, pencilShadeResponse = -1.0)
        val w = PencilStyle.of(0.0, 0.0, wild)
        assertEquals(ToolConfig.PENCIL_PRESSURE_MAX, w.pressure, 0.0)
        assertEquals(ToolConfig.PENCIL_DENSITY_MIN, w.density, 0.0)
        assertEquals(ToolConfig.PENCIL_GRAIN_SIZE_MAX, w.grainSize, 0.0)
        assertEquals(ToolConfig.PENCIL_SHADE_RESPONSE_MIN, w.shadeResponse, 0.0)
    }

    @Test fun toneMappingAnswersPressure() {
        val style = testStyle()
        // Bounds: substantial grey at zero pressure, dense graphite at full
        // pressure — but never fully solid, always noise. Nothing here is
        // translucent: density is the fraction of pixels carrying opaque
        // graphite, shade the grain colour from light grey to ink.
        assertEquals(0.55, StrokeTessellator.pencilDensity(0.0, style), 1e-9)
        assertEquals(0.88, StrokeTessellator.pencilDensity(1.0, style), 1e-9)
        // Gamma lifts mids toward solid: half pressure is well past halfway.
        assertEquals(0.753, StrokeTessellator.pencilDensity(0.5, style), 1e-3)
        assertEquals(0.45, StrokeTessellator.pencilShade(0.0, style), 1e-9)
        assertEquals(1.0, StrokeTessellator.pencilShade(1.0, style), 1e-9)
        assertEquals(0.789, StrokeTessellator.pencilShade(0.5, style), 1e-3)
        // Monotonic, in range.
        var prev = -1.0
        var p = 0.0
        while (p <= 1.0) {
            val a = StrokeTessellator.pencilDensity(p, style)
            assertTrue(a in 0.0..1.0)
            assertTrue(a >= prev)
            prev = a
            p += 0.05
        }
        // Bands quantize pressure finely: each band centre stays within a
        // step of the continuous maps (the gamma is steepest at zero).
        for (b in 0 until StrokeTessellator.PENCIL_BANDS) {
            val c = StrokeTessellator.pencilBandDensity(b, style.density)
            assertTrue(c in 0.0..1.0)
            assertTrue(StrokeTessellator.pencilBandShade(b, style.shadeResponse) in 0.0..1.0)
        }
        var q = 0.0
        while (q <= 1.0) {
            val band = StrokeTessellator.pencilBandOf(q, style.pressure)
            assertTrue(band in 0 until StrokeTessellator.PENCIL_BANDS)
            assertTrue(kotlin.math.abs(StrokeTessellator.pencilBandDensity(band, style.density) - StrokeTessellator.pencilDensity(q, style)) <= 1.0 / StrokeTessellator.PENCIL_BANDS + 1e-9)
            assertTrue(kotlin.math.abs(StrokeTessellator.pencilBandShade(band, style.shadeResponse) - StrokeTessellator.pencilShade(q, style)) <= 1.0 / StrokeTessellator.PENCIL_BANDS + 1e-9)
            q += 0.05
        }
        // Out-of-range pressure clamps instead of NaNing.
        assertTrue(StrokeTessellator.pencilDensity(-2.0, style).isFinite())
        assertTrue(StrokeTessellator.pencilDensity(99.0, style).isFinite())
        assertTrue(StrokeTessellator.pencilDensity(Double.NaN, style).isFinite())
    }

    @Test fun meshCarriesInkWithRawPressureOnThePencilPass() {
        val s = pencil()
        val meshed = ItemMesher.mesh(s)
        assertNotNull(meshed)
        val part = meshed!!.parts.single()
        // Its own stencil-consume pass: the cover turns pressure into
        // textured graphite with every pixel painted once.
        assertEquals(InkPass.PENCIL, part.pass)
        assertNotNull(part.mesh.colors)
        val colors = part.mesh.colors!!
        assertEquals(part.mesh.vertexCount, colors.size)
        // Every vertex is the ink grey; alpha carries raw smoothed pressure.
        for (c in colors) {
            assertEquals(64, (c shr 16) and 0xFF)
            assertEquals(64, (c shr 8) and 0xFF)
            assertEquals(64, c and 0xFF)
        }
        val alphas = colors.map { ((it ushr 24) and 0xFF) / 255.0 }
        assertTrue(alphas.any { it > 0.5 })
        assertTrue(alphas.any { it < 0.5 })
        // Same stroke, same bytes: live and committed cannot drift apart.
        val again = ItemMesher.mesh(s)!!.parts.single().mesh.colors!!
        assertEquals(colors.toList(), again.toList())
        // The alpha channel is the raw pressure, not the tone: the cover
        // shader applies the tone map itself.
        val g = s.geometry()
        val n = g.pointCount
        assertTrue(n > 4)
        val mesh = part.mesh
        // An end-disc hub sits exactly on its station with zero offset.
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (v in 0 until mesh.vertexCount) {
            if (mesh.offsets[2 * v] != 0.0 || mesh.offsets[2 * v + 1] != 0.0) continue
            val d = kotlin.math.hypot(mesh.positions[2 * v] - g.cx(0), mesh.positions[2 * v + 1] - g.cy(0))
            if (d < bestDist) {
                bestDist = d
                best = v
            }
        }
        assertTrue(best >= 0)
        assertEquals(0.0, bestDist, 1e-9)
        assertEquals((g.pw(0) * 255.0).toInt(), (colors[best] ushr 24) and 0xFF)
        // Round caps: the start disc puts a ring vertex exactly one radius
        // out along +x (the fan starts at angle 0).
        val r0 = g.hw(0)
        var ring = -1
        for (v in 0 until mesh.vertexCount) {
            val dx = mesh.positions[2 * v] - g.cx(0)
            val dy = mesh.positions[2 * v + 1] - g.cy(0)
            if (kotlin.math.abs(dx - r0) < 1e-9 && kotlin.math.abs(dy) < 1e-9) {
                ring = v
                break
            }
        }
        assertTrue("start cap disc has no +x ring vertex", ring >= 0)
    }

    @Test fun meshPartitionCoversTheSameSilhouette() {
        val s = pencil()
        val g = s.geometry()
        val n = g.pointCount
        val tol = StrokeTessellator.DEFAULT_TOLERANCE
        val ink = s.renderColor
        fun mesh(from: Int, count: Int) = StrokeTessellator.tessellatePencil(g, from, count, tol, ink) { i ->
            g.pw(i)
        }
        // Settled batches plus a tail sharing one station, as the wet caches
        // mesh them. Overlaps are allowed (the stencil unions them), so the
        // comparison is coverage on a coarse grid, not triangle multisets.
        val whole = coverageOf(mesh(0, n), 0.5)
        val settled = coverageOf(mesh(0, 80), 0.5)
        val tail = coverageOf(mesh(79, n - 79), 0.5)
        // The seam station is covered on both sides; nothing may be missing.
        for (c in whole) assertTrue("silhouette cell $c missing from runs", settled.contains(c) || tail.contains(c))
        // And the runs add no blob beyond a one-cell fringe (shared discs).
        for (c in settled + tail) {
            assertTrue(
                "run cell $c far outside the silhouette",
                whole.any { kotlin.math.abs(it.first - c.first) <= 1 && kotlin.math.abs(it.second - c.second) <= 1 },
            )
        }
        assertTrue(whole.isNotEmpty())
    }

    /** Coarse covered cells of a mesh: every triangle sampled on a [cell] grid. */
    private fun coverageOf(mesh: MeshData, cell: Double): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        val idx = mesh.indices
        var t = 0
        while (t + 2 < idx.size) {
            val ax = mesh.positions[2 * idx[t]]
            val ay = mesh.positions[2 * idx[t] + 1]
            val bx = mesh.positions[2 * idx[t + 1]]
            val by = mesh.positions[2 * idx[t + 1] + 1]
            val cx = mesh.positions[2 * idx[t + 2]]
            val cy = mesh.positions[2 * idx[t + 2] + 1]
            val loX = kotlin.math.floor(minOf(ax, bx, cx) / cell).toInt()
            val hiX = kotlin.math.floor(maxOf(ax, bx, cx) / cell).toInt()
            val loY = kotlin.math.floor(minOf(ay, by, cy) / cell).toInt()
            val hiY = kotlin.math.floor(maxOf(ay, by, cy) / cell).toInt()
            for (gx in loX..hiX) for (gy in loY..hiY) {
                val px = (gx + 0.5) * cell
                val py = (gy + 0.5) * cell
                if (pointInTriangle(px, py, ax, ay, bx, by, cx, cy)) out.add(gx to gy)
            }
            t += 3
        }
        return out
    }

    private fun pointInTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = (d1 < -1e-9) || (d2 < -1e-9) || (d3 < -1e-9)
        val pos = (d1 > 1e-9) || (d2 > 1e-9) || (d3 > 1e-9)
        return !(neg && pos)
    }

    @Test fun joinStationsStayInsideTheSlice() {
        // Regression: the join loop once read one station past the slice end
        // and crashed every thumbnail render (AIOOBE in ribbonJoinTurn).
        val straight = FloatArray(2 * 4) { (it / 2 * 10).toFloat() }
        assertTrue(ribbonJoinStations(straight, 0, 4).isEmpty())
        // Two points: no interior station at all.
        assertTrue(ribbonJoinStations(FloatArray(4) { it.toFloat() }, 0, 2).isEmpty())
        // A hard corner mid-slice is reported with its slice-local index.
        val kink = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 20f, 10f)
        assertEquals(listOf(1, 2), ribbonJoinStations(kink, 0, 4))
        // A sub-range measures with its own neighbours, still in-bounds.
        assertEquals(listOf(1), ribbonJoinStations(kink, 0, 3))
    }

    @Test fun seamTurnBelongsToTheEarlierRun() {
        // A hard turn exactly on a run's last station is owned by that run:
        // the next run starts past it and draws no disc of its own.
        val s = Stroke(Tool.PENCIL, ToolDefaults.configFor(Tool.PENCIL))
        s.finished = false
        // Straight, then a right angle at station 6, then straight: the seam
        // at 6 turns hard.
        val pts = listOf(
            Sample(0.0, 0.0, 0.8), Sample(10.0, 0.0, 0.8), Sample(20.0, 0.0, 0.8),
            Sample(30.0, 0.0, 0.8), Sample(40.0, 0.0, 0.8), Sample(50.0, 0.0, 0.8),
            Sample(60.0, 0.0, 0.8), Sample(60.0, 10.0, 0.8), Sample(60.0, 20.0, 0.8),
        )
        for (sm in pts) s.addSample(sm)
        s.finished = true
        val g = s.geometry()
        assertTrue(g.pointCount >= 9)
        val n = g.pointCount
        val seam = 6
        // Whole stroke: no seam flags at all.
        val whole = unionCallsOf(0) { r, _ -> s.paintForTest(r, g, 0, n) }
        assertTrue(whole.none { it.endJoin })
        // Split at the corner: the earlier run owns the seam disc.
        val first = unionCallsOf(0) { r, _ -> s.paintForTest(r, g, 0, seam + 1) }
        assertTrue("seam turn needs its disc", first.any { it.endJoin })
        val second = unionCallsOf(seam) { r, _ -> s.paintForTest(r, g, seam, n - seam) }
        assertTrue("tail draws no seam disc", second.none { it.endJoin })
        assertTrue("tail starts capless", second.none { it.capStart })
    }

    @Test fun paintPartitionTilesTheRibbonExactlyOnce() {
        val s = pencil()
        val g = s.geometry()
        val n = g.pointCount
        val whole = unionCallsOf(0) { r, off -> s.paintForTest(r, g, 0, n) }
        val parts = unionCallsOf(0) { r, off -> s.paintForTest(r, g, 0, 80) } +
            unionCallsOf(79) { r, off -> s.paintForTest(r, g, 79, n - 79) }
        // Same quads covered exactly once on both sides.
        assertEquals(quadRanges(whole), quadRanges(parts))
        // And the coverage is COMPLETE: every ribbon segment belongs to some
        // call. (Band calls share their boundary station, so the bridging
        // quad can never fall between bands.)
        assertEquals(listOf(0 to n - 2), quadRanges(whole))
        // Same core density at every station.
        assertEquals(stationAlphas(whole), stationAlphas(parts))
        // Bands cap only where they touch a true end — never at the seam.
        assertEquals(1, whole.count { it.capStart })
        assertEquals(1, whole.count { it.capEnd })
        assertEquals(0, parts.count { it.capStart && it.from != 0 })
        assertEquals(0, parts.count { it.capEnd && it.from + it.count != n })
        assertTrue(whole.isNotEmpty())
    }

    /** One recorded union fill. */
    private data class UnionCall(
        val from: Int,
        val count: Int,
        val density: Double,
        val shade: Double,
        val overwrite: Boolean,
        val capStart: Boolean,
        val capEnd: Boolean,
        val endJoin: Boolean,
        val grainSize: Double,
    )

    private fun unionCallsOf(base: Int, paint: (RecordingRibbon, Int) -> Unit): List<UnionCall> {
        val r = RecordingRibbon()
        paint(r, base)
        // paintPencilRuns re-bases each run to slice-local indices (like the
        // pen's fast path), so shift back to stroke-global stations for comparison.
        return r.calls.map { it.copy(from = it.from + base) }
    }

    private fun unionCallsOf(paint: (RecordingRibbon) -> Unit): List<UnionCall> {
        val r = RecordingRibbon()
        paint(r)
        return r.calls
    }

    /** Quad ranges covered, as (first, last) segment indices, merged. */
    private fun quadRanges(calls: List<UnionCall>): List<Pair<Int, Int>> {
        val segs = HashSet<Int>()
        for (c in calls) {
            // Rim plus one band call per tone: all full silhouettes.
            for (i in c.from until c.from + c.count - 1) segs.add(i)
        }
        if (segs.isEmpty()) return emptyList()
        val sorted = segs.sorted()
        val out = ArrayList<Pair<Int, Int>>()
        var a = sorted[0]
        var b = a
        for (s in sorted.drop(1)) {
            if (s == b + 1) b = s else {
                out.add(a to b)
                a = s
                b = s
            }
        }
        out.add(a to b)
        return out
    }

    /** Graphite density per station: later bands overwrite earlier ones at
     *  folds, so the last density wins each station. */
    private fun stationAlphas(calls: List<UnionCall>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        for (c in calls) {
            assertTrue("pencil bands replace, never composite", c.overwrite)
            for (i in c.from until c.from + c.count) out[i] = c.density
        }
        return out
    }

    @Test fun paintIsDeterministic() {
        val s = pencil()
        val g = s.geometry()
        val a = unionCallsOf { r -> s.paintForTest(r, g, 0, g.pointCount) }
        val b = unionCallsOf { r -> s.paintForTest(r, g, 0, g.pointCount) }
        assertEquals(a, b)
    }

    @Test fun translucentPencilLayersOnce() {
        val s = Stroke(
            Tool.PENCIL,
            ToolDefaults.configFor(Tool.PENCIL).copy(rgba = Rgba(64, 64, 64, 128)),
        )
        s.finished = false
        for (sm in samples(40)) s.addSample(sm)
        s.finished = true
        val r = RecordingRibbon()
        s.paint(r)
        // Outer translucency layer plus the run's own isolating layer.
        assertEquals(2, r.layers)
        assertTrue(r.calls.isNotEmpty())
        // And the stencil path takes over on the GL side.
        assertEquals(InkPass.TRANSLUCENT, ItemMesher.passFor(s))
        assertTrue(!s.wetCacheable)
    }

    @Test fun opaquePencilIsolatesAndReplaces() {
        val s = pencil()
        val r = RecordingRibbon()
        s.paintForTest(r, s.geometry(), 0, s.geometry().pointCount)
        // One isolating layer of pressure bands, no width gradient.
        assertEquals(1, r.layers)
        // Normal compositing throughout.
        assertEquals(listOf(BlendMode.SRC_OVER), r.blends)
        assertTrue(r.calls.isNotEmpty())
        // Every fill is opaque graphite stipple replacing the last, at a
        // pressure density and shade — never translucent wash.
        for (c in r.calls) {
            assertTrue(c.overwrite)
            assertTrue(c.density in 0.0..1.0)
            assertTrue(c.shade in 0.0..1.0)
            assertTrue(c.grainSize > 0.0)
        }
        // Bands actually vary with the stroke's pressure range.
        assertTrue(r.calls.map { it.density }.distinct().size > 1)
    }

    @Test fun liveStrokeTakesThePencilStencilPath() {
        val s = Stroke(Tool.PENCIL, ToolDefaults.configFor(Tool.PENCIL))
        s.finished = false
        for (sm in samples(90)) s.addSample(sm)
        assertTrue(s.wetCacheable)
        assertNotNull(s.wetRibbon)
        assertEquals(InkPass.PENCIL, ItemMesher.passFor(s))
        assertTrue(s.paintBounds() == s.bounds())
    }

    /** Mean painted density over the union calls' band densities. */
    private fun meanDensity(s: Stroke, g: StrokeGeometry): Double {
        val calls = unionCallsOf { r -> s.paintForTest(r, g, 0, g.pointCount) }
        assertTrue(calls.isNotEmpty())
        return calls.sumOf { it.density * (it.count - 1).coerceAtLeast(0) } /
            calls.sumOf { (it.count - 1).coerceAtLeast(0).toDouble() }.coerceAtLeast(1.0)
    }

    private fun Stroke.paintForTest(r: Renderer, g: StrokeGeometry, from: Int, count: Int) {
        paintPencilRuns(r, g, from, count, this.renderColor)
    }

    private class RecordingRibbon : Renderer {
        val calls = ArrayList<UnionCall>()
        var layers = 0
        val blends = ArrayList<BlendMode>()

        override fun save() {}
        override fun restore() {}
        override fun saveLayerAlpha(bounds: com.xnotes.core.geometry.Rect, alpha: Double) {
            layers++
        }
        override fun translate(dx: Double, dy: Double) {}
        override fun scale(sx: Double, sy: Double) {}
        override fun clipRect(rect: com.xnotes.core.geometry.Rect) {}
        override fun clear() {}
        override fun fillBackground(rect: com.xnotes.core.geometry.Rect, color: Rgba) {}
        override fun fillRect(rect: com.xnotes.core.geometry.Rect, color: Rgba) {}
        override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {}
        override fun fillCircle(center: Pt, radius: Double, color: Rgba) {}
        override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {}
        override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {}
        override fun fillRibbonUnion(
            centers: FloatArray,
            radii: FloatArray,
            from: Int,
            count: Int,
            color: Rgba,
            radiusScale: Float,
            capStart: Boolean,
            capEnd: Boolean,
            overwrite: Boolean,
            endJoin: Boolean,
            ox: Double,
            oy: Double,
            density: Double,
            shade: Double,
            grainSize: Double,
        ) {
            calls.add(UnionCall(from, count, density, shade, overwrite, capStart, capEnd, endJoin, grainSize))
        }
        override fun strokeRect(rect: com.xnotes.core.geometry.Rect, pen: com.xnotes.core.pal.Pen) {}
        override fun strokePolyline(points: List<Pt>, pen: com.xnotes.core.pal.Pen) {}
        override fun strokePolygon(points: List<Pt>, pen: com.xnotes.core.pal.Pen) {}
        override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: com.xnotes.core.pal.Pen) {}
        override fun drawRaster(raster: com.xnotes.core.pal.RasterSurface, dest: com.xnotes.core.geometry.Rect, src: com.xnotes.core.geometry.Rect?) {}
        override fun drawText(text: String, rect: com.xnotes.core.geometry.Rect, font: com.xnotes.core.pal.FontSpec, color: Rgba, flags: com.xnotes.core.pal.TextFlags) {}
        override fun saveLayerBlended(bounds: com.xnotes.core.geometry.Rect, alpha: Double, blend: BlendMode) {
            layers++
            blends.add(blend)
        }
    }
}
