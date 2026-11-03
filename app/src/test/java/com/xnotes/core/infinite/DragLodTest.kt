package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class DragLodTest {

    private fun line(n: Int, step: Double = 5.0): List<Sample> =
        List(n) { Sample(it * step, 0.0, 1.0) }

    private fun widths(n: Int, w: Double = 3.0): FloatArray = FloatArray(n) { w.toFloat() }

    @Test fun straightLineKeepsOnlyItsEnds() {
        val out = DragLod.decimate(line(50), widths(50), 1.0)
        assertEquals(2, out.size)
        assertEquals(0.0, out.first().x, 1e-9)
        assertEquals(49 * 5.0, out.last().x, 1e-9)
    }

    @Test fun degenerateInputsReturnUntouched() {
        val two = line(2)
        assertSame(two, DragLod.decimate(two, widths(2), 1.0))
        assertTrue(DragLod.decimate(emptyList(), FloatArray(0), 1.0).isEmpty())
        val ten = line(10)
        assertSame(ten, DragLod.decimate(ten, widths(10), 0.0))
        assertSame(ten, DragLod.decimate(ten, widths(10), Double.NaN))
        assertSame(ten, DragLod.decimate(ten, FloatArray(3), 1.0))
    }

    @Test fun squareKeepsItsCorners() {
        val pts = listOf(
            Sample(0.0, 0.0, 1.0), Sample(30.0, 1.0, 1.0), Sample(60.0, 0.0, 1.0),
            Sample(61.0, 30.0, 1.0), Sample(60.0, 60.0, 1.0),
            Sample(30.0, 61.0, 1.0), Sample(0.0, 60.0, 1.0),
        )
        val out = DragLod.decimate(pts, widths(pts.size), 2.0)
        // The four corners survive; the near-collinear midpoints may not.
        for ((x, y) in listOf(0.0 to 0.0, 60.0 to 0.0, 60.0 to 60.0, 0.0 to 60.0)) {
            assertTrue("corner ($x, $y) kept", out.any { hypot(it.x - x, it.y - y) < 1e-9 })
        }
        assertTrue(out.size < pts.size)
    }

    @Test fun droppedPointsStayWithinEpsOfAChord() {
        val rnd = Random(11)
        val pts = List(120) { i ->
            Sample(i * 3.0, 8.0 * kotlin.math.sin(i / 5.0) + rnd.nextDouble(-1.0, 1.0), 0.8)
        }
        val eps = 1.5
        val out = DragLod.decimate(pts, widths(pts.size), eps)
        assertTrue(out.size in 3 until pts.size)
        // Every dropped index lies within eps of some kept chord (the RDP invariant). Kept
        // points are the identical Sample references, so identity finds them.
        val keptIdx = pts.mapIndexedNotNull { i, s -> if (out.any { it === s }) i else null }.sorted()
        assertEquals(0, keptIdx.first())
        assertEquals(pts.size - 1, keptIdx.last())
        for (i in pts.indices) {
            val hit = keptIdx.binarySearch(i)
            if (hit >= 0) continue
            val k = -hit - 2 // segment [k, k+1] straddles i
            val a = pts[keptIdx[k]]
            val b = pts[keptIdx[k + 1]]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq < 1e-12) 0.0 else
                ((pts[i].x - a.x) * dx + (pts[i].y - a.y) * dy) / lenSq
            val d = hypot(pts[i].x - (a.x + t * dx), pts[i].y - (a.y + t * dy))
            assertTrue("dropped point $i deviates $d > $eps", d <= eps + 1e-6)
        }
    }

    @Test fun widthSpikeForcesItsNeighbourhoodToStay() {
        val pts = line(40)
        val w = widths(40)
        w[20] = 12.0f // a pressure spike mid-line
        val out = DragLod.decimate(pts, w, 1.0)
        assertTrue("spike neighbourhood kept", out.any { it.x == 20 * 5.0 })
    }

    @Test fun dragProxyOnlyFiresWhenWorthwhile() {        val tiny = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            (0..9).map { Sample(it * 5.0, 0.0, 1.0) }.toMutableList())
        assertNull(tiny.dragProxy(1.0)) // fewer than 16 samples
        val wavy = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            (0..120).map { Sample(it * 3.0, kotlin.math.sin(it / 5.0) * 8.0, 0.8) }.toMutableList())
        val proxy = wavy.dragProxy(1.5)
        assertTrue(proxy != null)
        assertTrue(proxy!!.sampleCount < wavy.sampleCount)
        assertEquals(0.0, proxy.xAt(0), 1e-9) // endpoints preserved
        assertNull(wavy.dragProxy(0.0))
        assertNull(wavy.dragProxy(Double.NaN))
    }

    private fun circleShape(): com.xnotes.core.model.ShapeItem = com.xnotes.core.model.ShapeItem(
        com.xnotes.core.shapes.ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(400.0, 400.0),
        com.xnotes.core.model.Rgba(1, 2, 3, 255), 3.0,
    )

    private fun verts(parts: List<com.xnotes.core.infinite.MeshPart>): Int =
        parts.sumOf { it.mesh.vertexCount }

    @Test fun coarseShapeMeshKeepsBoundsWithFewerVertices() {
        val shape = circleShape()
        val fine = ItemMesher.mesh(shape)!!
        val coarse = ItemMesher.mesh(shape, 1.0)!!
        assertTrue("coarse must shed vertices: ${verts(coarse.parts)} vs ${verts(fine.parts)}",
            verts(coarse.parts) < verts(fine.parts))
        assertEquals(fine.bounds, coarse.bounds)
    }

    @Test fun coarseConnectorMeshKeepsBoundsWithFewerVertices() {
        val ink = com.xnotes.core.model.Rgba(1, 2, 3, 255)
        val c = com.xnotes.core.model.ConnectorItem(
            com.xnotes.core.model.Routing.CURVED,
            com.xnotes.core.model.ConnectorEnd(Pt(0.0, 0.0)),
            com.xnotes.core.model.ConnectorEnd(Pt(400.0, 200.0)),
            ink, 2.0,
        )
        val fine = ItemMesher.mesh(c)!!
        val coarse = ItemMesher.mesh(c, 1.0)!!
        assertTrue("coarse must shed vertices: ${verts(coarse.parts)} vs ${verts(fine.parts)}",
            verts(coarse.parts) < verts(fine.parts))
        val b = fine.bounds
        val d = coarse.bounds
        assertTrue(kotlin.math.abs(b.w - d.w) < 4.0 && kotlin.math.abs(b.h - d.h) < 4.0)
    }
}
