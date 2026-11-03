package com.xnotes.ui

import com.xnotes.math.ExpressionParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Per-entry plot domains: intersection helpers, per-curve sampling and persistence. */
class MathEntryDomainTest {

    @Test fun entryRangeBlankUsesGlobal() {
        assertEquals(0.0 to 10.0, entryRange("", "", 0.0, 10.0))
        assertEquals(-10.0 to 10.0, entryRange("", "", -10.0, 10.0))
    }

    @Test fun entryRangePartialSide() {
        assertEquals(2.0 to 10.0, entryRange("2", "", 0.0, 10.0))
        assertEquals(0.0 to 4.0, entryRange("", "4", 0.0, 10.0))
    }

    @Test fun entryRangeSwappedAndClamped() {
        assertEquals(2.0 to 4.0, entryRange("4", "2", 0.0, 10.0))
        assertEquals(0.0 to 10.0, entryRange("-20", "20", 0.0, 10.0))
    }

    @Test fun entryRangeOutsideOrDegenerateIsNull() {
        assertNull(entryRange("100", "200", -10.0, 10.0))
        assertNull(entryRange("", "", 5.0, 5.0))
        // Unparseable sides fall back to the view instead of hiding the curve.
        assertEquals(0.0 to 10.0, entryRange("abc", "def", 0.0, 10.0))
    }

    @Test fun entryRangeNonFiniteFallsBack() {
        assertEquals(0.0 to 10.0, entryRange("NaN", "Infinity", 0.0, 10.0))
        assertNull(entryRange("", "", Double.NaN, 10.0))
    }

    @Test fun entryClipRequiresBothSides() {
        assertEquals(0.0 to 1.0, entryClip("0", "1"))
        assertEquals(0.0 to 1.0, entryClip("1", "0"))
        assertNull(entryClip("0", ""))
        assertNull(entryClip("", ""))
        assertNull(entryClip("abc", "1"))
    }

    @Test fun entryDomainLabelFormatsWhenSet() {
        assertNull(entryDomainLabel(MathEntry("x")))
        val pi = com.xnotes.math.Numbers.tick(PI)
        assertEquals("[0, $pi]", entryDomainLabel(MathEntry("x", domA0 = "0", domA1 = PI.toString())))
        assertNotNull(entryDomainLabel(MathEntry("x", domA0 = "0", domA1 = "1")))
    }

    private fun cartExpr(text: String) = ExpressionParser.parse(text)

    @Test fun cartesianRespectsEntryXRange(): Unit = runBlocking {
        val pts = sampleCartesian(cartExpr("x"), 2.0, 4.0, -10.0, 10.0).flatMap { it.xy.toList() }
        assertTrue(pts.isNotEmpty())
        for (i in pts.indices step 2) {
            assertTrue("x=${pts[i]}", pts[i] >= 2.0 - 1e-9 && pts[i] <= 4.0 + 1e-9)
            assertEquals(pts[i], pts[i + 1], 1e-9)
        }
    }

    @Test fun cartesianAppliesYClipAsGaps(): Unit = runBlocking {
        val pts = sampleCartesian(cartExpr("x"), 0.0, 10.0, -10.0, 10.0, null, 0.0 to 1.0)
        assertTrue(pts.isNotEmpty())
        for (pl in pts) {
            val xy = pl.xy
            for (i in xy.indices step 2) {
                assertTrue("y=${xy[i + 1]}", xy[i + 1] >= 0.0 - 1e-9 && xy[i + 1] <= 1.0 + 1e-9)
            }
        }
    }

    @Test fun polarRespectsThetaRange(): Unit = runBlocking {
        val pts = samplePolar(cartExpr("1"), 0.5, 1.0).flatMap { it.xy.toList() }
        assertTrue(pts.isNotEmpty())
        for (i in pts.indices step 2) {
            val x = pts[i]; val y = pts[i + 1]
            assertEquals(1.0, hypot(x, y), 1e-9)
            val a = atan2(y, x).let { if (it < 0) it + 2 * PI else it }
            assertTrue("a=$a", a >= 0.5 - 1e-9 && a <= 1.0 + 1e-9)
        }
    }

    @Test fun polarAppliesRClip(): Unit = runBlocking {
        val pts = samplePolar(cartExpr("theta"), 0.0, 2 * PI, null, 0.0 to 1.0)
        for (pl in pts) {
            val xy = pl.xy
            for (i in xy.indices step 2) {
                assertTrue(hypot(xy[i], xy[i + 1]) <= 1.0 + 1e-9)
            }
        }
    }

    @Test fun parametricRespectsEntryTRange(): Unit = runBlocking {
        val snap = Plot2DSnap(Plot2DMode.PARAMETRIC, emptyList(), emptyList(), emptyList(), "",
            2.0, -10.0, 10.0, -10.0, 10.0, true, null, null)
        val tx = cartExpr("t"); val ty = cartExpr("2*t")
        val pts = sampleParametric(tx, ty, snap, null, 1.0 to 3.0).flatMap { it.xy.toList() }
        assertTrue(pts.isNotEmpty())
        assertEquals(1.0, pts.first(), 1e-9)
        assertEquals(3.0, pts[pts.size - 2], 1e-6)
    }

    @Test fun implicitStaysInsideEntryRegion(): Unit = runBlocking {
        val segs = sampleImplicit(cartExpr("x^2 + y^2 - 4"), 0.0, 10.0, 0.0, 10.0)
        assertTrue(segs.isNotEmpty())
        for (s in segs) {
            for (v in listOf(s.x1, s.y1, s.x2, s.y2)) {
                assertTrue("v=$v", v >= -1e-9 && v <= 10.0 + 1e-9)
            }
        }
    }

    private fun snap3D(mode: SurfaceMode, x0: Double = -5.0, x1: Double = 5.0,
        y0: Double = -5.0, y1: Double = 5.0, z0: Double = -5.0, z1: Double = 5.0): Plot3DSnap =
        Plot3DSnap(mode, emptyList(), emptyList(), ComplexComponent.MAGNITUDE,
            x0, x1, y0, y1, z0, z1, 24, Quat.IDENTITY, 1f, null, false, null, true)

    @Test fun cartesianMeshRespectsEntryBox(): Unit = runBlocking {
        val mesh = cartesianMesh("x + y", snap3D(SurfaceMode.CARTESIAN), null, 2.0, 4.0, -5.0, 5.0)
        assertNotNull(mesh)
        for (row in mesh!!.pts) for (p in row) {
            assertTrue("x=${p.x}", p.x >= 2.0 - 1e-9 && p.x <= 4.0 + 1e-9)
        }
    }

    @Test fun cylindricalMeshRespectsThetaRange(): Unit = runBlocking {
        val mesh = cylindricalMesh("1", snap3D(SurfaceMode.CYLINDRICAL), null, 0.0, PI / 2, -5.0, 5.0)
        assertNotNull(mesh)
        var n = 0
        for (row in mesh!!.pts) for (p in row) {
            if (!p.x.isFinite()) continue
            n++
            val a = atan2(p.y, p.x)
            assertTrue("a=$a", a >= -1e-9 && a <= PI / 2 + 1e-9)
        }
        assertTrue(n > 0)
    }

    @Test fun parametricMeshRespectsEntryWindow(): Unit = runBlocking {
        val mesh = parametricMesh(Param3D("u", "v", "u+v"), snap3D(SurfaceMode.PARAMETRIC),
            null, 1.0 to 2.0, 0.0 to 1.0)
        assertNotNull(mesh)
        for (row in mesh!!.pts) for (p in row) {
            assertTrue("x=${p.x}", p.x >= 1.0 - 1e-9 && p.x <= 2.0 + 1e-9)
            assertTrue("y=${p.y}", p.y >= 0.0 - 1e-9 && p.y <= 1.0 + 1e-9)
        }
    }

    @Test fun implicitCloudRespectsEntryBox(): Unit = runBlocking {
        val cloud = implicitCloud("x^2 + y^2 + z^2 - 4", snap3D(SurfaceMode.IMPLICIT), null, 0.0, 5.0, 0.0, 5.0)
        assertNotNull(cloud)
        for (p in cloud!!.pts) {
            assertTrue("x=${p.x}", p.x >= -1e-9 && p.x <= 5.0 + 1e-9)
            assertTrue("y=${p.y}", p.y >= -1e-9 && p.y <= 5.0 + 1e-9)
        }
    }

    @Test fun plot2dDomainsRoundTrip() {
        val p2 = Plot2DState()
        p2.expressions = listOf(MathEntry("sin(x)", domA0 = "0", domA1 = "6.28", domB0 = "-1", domB1 = "1"))
        p2.paramCurves = listOf(ParamCurve2D("cos(t)", "sin(t)", t0 = "0", t1 = "3.14"))
        p2.implicitList = listOf(MathEntry("x^2+y^2-4", domA0 = "-2", domA1 = "2", domB0 = "0", domB1 = "2"))
        val json = MathStudioData.capture("GRAPH_2D", CalculatorState(), SolverState(), p2,
            Plot3DState(), FieldState(), CalculusState(), SymbolicState(), UnitState())
        val q2 = Plot2DState()
        MathStudioData.restore(json, CalculatorState(), SolverState(), q2,
            Plot3DState(), FieldState(), CalculusState(), SymbolicState(), UnitState())
        assertEquals("0", q2.expressions[0].domA0)
        assertEquals("6.28", q2.expressions[0].domA1)
        assertEquals("-1", q2.expressions[0].domB0)
        assertEquals("1", q2.expressions[0].domB1)
        assertEquals("0", q2.paramCurves[0].t0)
        assertEquals("3.14", q2.paramCurves[0].t1)
        assertEquals("-2", q2.implicitList[0].domA0)
        assertEquals("2", q2.implicitList[0].domB1)
    }

    @Test fun plot3dDomainsRoundTrip() {
        val p3 = Plot3DState()
        p3.cartesianList = listOf(MathEntry("x^2", domA0 = "-1", domA1 = "1", domB0 = "-2", domB1 = "2"))
        p3.cylList = listOf(MathEntry("2", domA0 = "0", domA1 = "3.14"))
        p3.paramList = listOf(Param3D("u", "v", "u", u0 = "0", u1 = "1", v0 = "0", v1 = "2"))
        val json = MathStudioData.capture("GRAPH_3D", CalculatorState(), SolverState(), Plot2DState(),
            p3, FieldState(), CalculusState(), SymbolicState(), UnitState())
        val q3 = Plot3DState()
        MathStudioData.restore(json, CalculatorState(), SolverState(), Plot2DState(),
            q3, FieldState(), CalculusState(), SymbolicState(), UnitState())
        assertEquals("-1", q3.cartesianList[0].domA0)
        assertEquals("2", q3.cartesianList[0].domB1)
        assertEquals("3.14", q3.cylList[0].domA1)
        assertEquals("1", q3.paramList[0].u1)
        assertEquals("2", q3.paramList[0].v1)
    }

    @Test fun animSpec2DDomainsRoundTrip() {
        val snap = Plot2DSnap(Plot2DMode.POLAR,
            listOf(MathEntry("1+cos(theta)", domA0 = "0", domA1 = "3.14", domB0 = "0", domB1 = "2")),
            listOf(ParamCurve2D("cos(t)", "sin(t)", t0 = "0", t1 = "1")),
            listOf(MathEntry("x^2-y", domA0 = "-1", domA1 = "1")),
            "", 2.0, -10.0, 10.0, -6.25, 6.25, true,
            AnimSnap(0.0, 6.28, 60, 0.0, false), 0.0)
        val spec = MathAnimSpec.capture2D(snap)
        assertNotNull(spec)
        val parsed = MathAnimSpec.parse(spec!!.toJson())
        assertNotNull(parsed)
        val p2 = Plot2DState()
        parsed!!.applyTo(p2, Plot3DState(), FieldState())
        assertEquals("0", p2.expressions[0].domA0)
        assertEquals("3.14", p2.expressions[0].domA1)
        assertEquals("2", p2.expressions[0].domB1)
        assertEquals("1", p2.paramCurves[0].t1)
        assertEquals("-1", p2.implicitList[0].domA0)
    }

    @Test fun animSpec3DDomainsRoundTrip() {
        val snap = Plot3DSnap(SurfaceMode.CYLINDRICAL,
            listOf(MathEntry("2", domA0 = "0", domA1 = "3.14", domB0 = "-1", domB1 = "1")),
            listOf(Param3D("u", "v", "u", u0 = "0", u1 = "1", v0 = "2", v1 = "3")),
            ComplexComponent.MAGNITUDE, -5.0, 5.0, -5.0, 5.0, -5.0, 5.0, 24,
            Quat.IDENTITY, 1f, null, false, AnimSnap(0.0, 1.0, 8, 0.0, false), true)
        val spec = MathAnimSpec.capture3D(snap)
        assertNotNull(spec)
        val parsed = MathAnimSpec.parse(spec!!.toJson())
        assertNotNull(parsed)
        val p3 = Plot3DState()
        parsed!!.applyTo(Plot2DState(), p3, FieldState())
        assertEquals(SurfaceMode.CYLINDRICAL, p3.mode)
        assertEquals("0", p3.cylList[0].domA0)
        assertEquals("1", p3.cylList[0].domB1)
    }

    @Test fun blankDomainsRestoreAsBlankForOldNotes() {
        // Notes saved before per-entry domains existed carry no domain keys.
        val json = org.json.JSONObject()
            .put("v", 1)
            .put("plot2d", org.json.JSONObject()
                .put("mode", "CARTESIAN")
                .put("exprs", org.json.JSONArray(listOf(
                    org.json.JSONObject().put("t", "sin(x)").put("c", 0).put("v", true)))))
            .toString()
        val p2 = Plot2DState()
        MathStudioData.restore(json, CalculatorState(), SolverState(), p2,
            Plot3DState(), FieldState(), CalculusState(), SymbolicState(), UnitState())
        assertEquals("", p2.expressions[0].domA0)
        assertEquals("", p2.expressions[0].domB1)
        assertFalse(p2.expressions[0].domA0.isNotBlank())
    }
}
