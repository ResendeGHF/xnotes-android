package com.xnotes.core.stroke

import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.StrokeTessellator
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pencil end to end: a graphite field, not tinted ribbon or dots — and
 * identical live and final.
 *
 * - the mesh carries density/edge control bytes (not colours), takes the
 *   PENCIL pass with the ink as a uniform override, and joins without gaps or
 *   overlaps (mitered strip, caps only at the true ends);
 * - the paged painter rasterizes the same page-anchored field the GL shader
 *   evaluates, so the committed stroke is the live texture;
 * - painting in runs (settled batches + the moving tail, sharing one station)
 *   lands exactly where a full repaint would.
 */
class PencilStrokeTest {

    private fun samples(count: Int): List<Sample> {
        val out = ArrayList<Sample>(count)
        var i = 0
        while (i < count) {
            val u = i * 0.35
            out.add(Sample(100.0 + u * 9.0, 200.0 + kotlin.math.sin(u) * 12.0, 0.3 + 0.5 * (i % 5) / 4.0))
            i++
        }
        return out
    }

    private fun pencil(grain: Double = 0.55): Stroke {
        val s = Stroke(Tool.PENCIL, ToolDefaults.configFor(Tool.PENCIL).copy(rgba = Rgba(64, 64, 64), pencilGrain = grain))
        s.finished = false
        for (sm in samples(120)) s.addSample(sm)
        s.finished = true
        s.trimToSize()
        return s
    }

    @Test fun meshCarriesDensityNotColourOnThePencilPass() {
        val s = pencil()
        val meshed = ItemMesher.mesh(s)
        assertNotNull(meshed)
        val part = meshed!!.parts.single()
        assertEquals(InkPass.PENCIL, part.pass)
        assertNotNull(part.mesh.colors)
        val colors = part.mesh.colors!!
        assertEquals(part.mesh.vertexCount, colors.size)
        // Red channel is edge-only (0 centre, 1 rail); alpha is density.
        var sawEdge = false
        var sawCentre = false
        for (c in colors) {
            val edge = (c shr 16) and 0xFF
            val dens = ((c ushr 24) and 0xFF) / 255.0
            assertTrue(edge == 0 || edge == 255)
            assertTrue(dens in 0.0..1.0)
            if (edge == 255) sawEdge = true else sawCentre = true
        }
        assertTrue(sawEdge && sawCentre)
        // Same stroke, same bytes.
        val again = ItemMesher.mesh(s)!!.parts.single().mesh.colors!!
        assertEquals(colors.toList(), again.toList())
    }

    @Test fun meshPartitionMatchesWholeTriangleForTriangle() {
        val s = pencil()
        val g = s.geometry()
        val n = g.pointCount
        val tol = StrokeTessellator.DEFAULT_TOLERANCE
        val dens = densityOf(s, g)
        val whole = multiSetOf(StrokeTessellator.tessellatePencil(g, 0, n, tol, dens))
        // Settled batches plus a tail sharing one station of overlap, as the wet
        // caches mesh them. The mitered strip shares the seam station verbatim,
        // and interior joints draw no caps — so whole and parts union exactly.
        val settled = multiSetOf(StrokeTessellator.tessellatePencil(g, 0, 80, tol, dens))
        val tail = multiSetOf(StrokeTessellator.tessellatePencil(g, 79, n - 79, tol, dens))
        assertEquals(whole, settled + tail)
        assertTrue(whole.isNotEmpty())
    }

    private fun multiSetOf(mesh: MeshData): Map<String, Int> {
        val out = HashMap<String, Int>()
        val idx = mesh.indices
        var t = 0
        while (t + 2 < idx.size) {
            val vs = (0..2).map { k ->
                val v = idx[t + k]
                val c = mesh.colors?.get(v) ?: 0
                "%.3f,%.3f,%d".format(mesh.positions[2 * v], mesh.positions[2 * v + 1], c)
            }.sorted().joinToString("|")
            out[vs] = (out[vs] ?: 0) + 1
            t += 3
        }
        return out
    }

    private operator fun Map<String, Int>.plus(other: Map<String, Int>): Map<String, Int> {
        val out = HashMap(this)
        for ((k, v) in other) out[k] = (out[k] ?: 0) + v
        return out
    }

    private fun densityOf(s: Stroke, g: StrokeGeometry): (Int) -> Double {
        val bw = s.config.baseWidth
        val m = s.config.pressureMinFactor
        val grain = s.config.pencilGrain
        return { i -> PencilGrain.densityAt(g.hw(i), bw, m, grain) }
    }

    @Test fun paintPartitionMatchesWholeFieldPixelForPixel() {
        val s = pencil()
        val g = s.geometry()
        val whole = fieldOf { r -> s.paintForTest(r, g, 0, g.pointCount) }
        // Two runs sharing one station, as the wet cache bakes then repaints.
        val first = fieldOf { r -> s.paintForTest(r, g, 0, 80) }
        val second = fieldOf { r -> s.paintForTest(r, g, 79, g.pointCount - 79) }
        assertTrue(whole.isNotEmpty())
        // Union with max (the rasterizer assigns deterministically; the seam can
        // only differ where a shared-edge pixel lands on the rounding boundary).
        val keys = HashSet<String>()
        keys.addAll(whole.keys)
        keys.addAll(first.keys)
        keys.addAll(second.keys)
        var differing = 0
        var worst = 0
        for (k in keys) {
            val w = whole[k] ?: 0
            // Unsigned max: pixel ints with alpha >= 128 are negative as signed.
            val f = first[k]
            val s = second[k]
            val u = when {
                f == null -> s ?: 0
                s == null -> f
                else -> if (s.toUInt() > f.toUInt()) s else f
            }
            if (w != u) {
                differing++
                worst = maxOf(worst, kotlin.math.abs(((w ushr 24) and 0xFF) - ((u ushr 24) and 0xFF)))
            }
        }
        assertTrue(
            "seam differs on $differing/${keys.size} px (worst alpha $worst)",
            differing == 0,
        )
    }

    /** Pixel map of the rasterized field, keyed by content-space pixel origin. */
    private fun fieldOf(paint: (RecordingField) -> Unit): Map<String, Int> {
        val r = RecordingField()
        paint(r)
        val out = HashMap<String, Int>()
        for (call in r.calls) {
            for (c in call.chunks) {
                for (y in 0 until c.h) for (x in 0 until c.w) {
                    val px = c.pixels[y * c.w + x]
                    if (px ushr 24 != 0) {
                        val key = "%.2f,%.2f".format(c.left + x * 0.5, c.top + y * 0.5)
                        // Unsigned: pixel ints with alpha >= 128 are negative.
                        val prev = out[key]
                        out[key] = if (prev == null || px.toUInt() > prev.toUInt()) px else prev
                    }
                }
            }
        }
        return out
    }

    @Test fun grainZeroFallsBackToSolidRibbon() {
        val s = pencil(grain = 0.0)
        val r = RecordingField()
        s.paintForTest(r, s.geometry(), 0, s.geometry().pointCount)
        assertTrue(r.calls.none { it.isField })
        assertEquals(1, r.ribbons)
    }

    @Test fun translucentPencilLayersOnce() {
        val s = Stroke(
            Tool.PENCIL,
            ToolDefaults.configFor(Tool.PENCIL).copy(rgba = Rgba(64, 64, 64, 128), pencilGrain = 0.55),
        )
        s.finished = false
        for (sm in samples(40)) s.addSample(sm)
        s.finished = true
        val r = RecordingField()
        s.paint(r)
        assertEquals(1, r.layers)
        assertTrue(r.calls.any { it.isField })
        // And the stencil path takes over on the GL side.
        assertEquals(InkPass.TRANSLUCENT, ItemMesher.passFor(s))
        assertTrue(!s.wetCacheable)
    }

    @Test fun opaquePencilNeverLayers() {
        val s = pencil()
        val r = RecordingField()
        s.paintForTest(r, s.geometry(), 0, s.geometry().pointCount)
        assertEquals(0, r.layers)
        val bodies = r.calls.filter { it.isField }
        assertTrue(bodies.isNotEmpty())
        // The field paints real graphite: some pixels covered, some paper.
        val pixels = bodies.flatMap { it.chunks.flatMap { c -> c.pixels.toList() } }
        assertTrue(pixels.any { (it ushr 24) and 0xFF > 200 })
        assertTrue(pixels.any { (it ushr 24) and 0xFF == 0 })
    }

    @Test fun liveStrokeTakesThePencilFastPath() {
        val s = Stroke(Tool.PENCIL, ToolDefaults.configFor(Tool.PENCIL))
        s.finished = false
        for (sm in samples(90)) s.addSample(sm)
        assertTrue(s.wetCacheable)
        assertNotNull(s.wetRibbon)
        assertEquals(InkPass.PENCIL, ItemMesher.passFor(s))
        assertTrue(s.paintBounds().w >= s.bounds().w)
    }

    /** Calls the run painter directly: whole geometry or a run slice, same helper
     *  the stroke's own paint paths use. */
    private fun Stroke.paintForTest(r: Renderer, g: StrokeGeometry, from: Int, count: Int) {
        paintPencilRuns(r, g, from, count, this.renderColor)
    }

    private class FieldCall(
        val isField: Boolean,
        val chunks: List<PencilField.Chunk> = emptyList(),
    )

    private class RecordingField : Renderer {
        val calls = ArrayList<FieldCall>()
        var ribbons = 0
        var layers = 0

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
        override fun fillPolygon(points: List<com.xnotes.core.geometry.Pt>, color: Rgba, rule: com.xnotes.core.pal.FillRule) {}
        override fun fillCircle(center: com.xnotes.core.geometry.Pt, radius: Double, color: Rgba) {}
        override fun fillEllipse(center: com.xnotes.core.geometry.Pt, rx: Double, ry: Double, color: Rgba) {}
        override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
            ribbons++
        }
        override fun fillPencil(positions: DoubleArray, colors: IntArray, indices: IntArray, ink: Rgba, seed: Int, tipMix: Float) {
            calls.add(FieldCall(true, PencilField.render(positions, colors, indices, ink, 1.0, seed = seed, tipMix = tipMix.toDouble())))
        }
        override fun strokeRect(rect: com.xnotes.core.geometry.Rect, pen: com.xnotes.core.pal.Pen) {}
        override fun strokePolyline(points: List<com.xnotes.core.geometry.Pt>, pen: com.xnotes.core.pal.Pen) {}
        override fun strokePolygon(points: List<com.xnotes.core.geometry.Pt>, pen: com.xnotes.core.pal.Pen) {}
        override fun strokeEllipse(center: com.xnotes.core.geometry.Pt, rx: Double, ry: Double, pen: com.xnotes.core.pal.Pen) {}
        override fun drawRaster(raster: com.xnotes.core.pal.RasterSurface, dest: com.xnotes.core.geometry.Rect, src: com.xnotes.core.geometry.Rect?) {}
        override fun drawText(text: String, rect: com.xnotes.core.geometry.Rect, font: com.xnotes.core.pal.FontSpec, color: Rgba, flags: com.xnotes.core.pal.TextFlags) {}
    }
}
