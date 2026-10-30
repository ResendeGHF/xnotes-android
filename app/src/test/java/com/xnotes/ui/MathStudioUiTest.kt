package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera presets: looking down an axis, or back to isometric. */
class QuatViewsTest {
    private fun assertMatrixNear(actual: DoubleArray, expected: DoubleArray, eps: Double = 1e-9) {
        assertEquals(9, actual.size)
        for (i in expected.indices) {
            assertTrue("m[$i]=${actual[i]} expected ${expected[i]}", kotlin.math.abs(actual[i] - expected[i]) < eps)
        }
    }

    @Test fun identityRoundTrip() {
        assertMatrixNear(
            quatFromMatrix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)).matrix(),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        )
    }

    @Test fun shortMatrixFallsBackToIdentity() {
        val q = quatFromMatrix(doubleArrayOf(1.0, 2.0))
        assertMatrixNear(q.matrix(), doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test fun xViewLooksDownPositiveXWithZUp() {
        val orbit = OrbitState()
        orbit.setView(GraphView.X)
        // screenX = worldY, screenY = worldZ, depth = worldX.
        assertMatrixNear(
            orbit.quat.matrix(),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0),
        )
    }

    @Test fun yViewKeepsZUpAndMirrorsX() {
        val orbit = OrbitState()
        orbit.setView(GraphView.Y)
        assertMatrixNear(
            orbit.quat.matrix(),
            doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0),
        )
    }

    @Test fun zViewIsTopDown() {
        val orbit = OrbitState()
        orbit.setView(GraphView.Z)
        assertMatrixNear(
            orbit.quat.matrix(),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        )
    }

    @Test fun isoMatchesReset() {
        val orbit = OrbitState()
        orbit.setView(GraphView.X)
        orbit.setView(GraphView.ISO)
        val fresh = OrbitState()
        val a = orbit.quat.matrix()
        val b = fresh.quat.matrix()
        assertMatrixNear(a, b)
    }

    @Test fun resetRestoresIsoAfterDrag() {
        val orbit = OrbitState()
        orbit.drag(120f, -40f)
        orbit.reset()
        assertMatrixNear(orbit.quat.matrix(), isoQuat().matrix())
    }

    @Test fun presetsAreUnitQuaternions() {
        for (view in GraphView.entries) {
            val orbit = OrbitState()
            orbit.setView(view)
            val q = orbit.quat
            val n = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
            assertTrue("$view not unit: $n", kotlin.math.abs(n - 1.0) < 1e-9)
        }
    }

    @Test fun presetsAreProperRotations() {
        for (view in GraphView.entries) {
            val orbit = OrbitState()
            orbit.setView(view)
            val m = orbit.quat.matrix()
            // Rows orthonormal: each row has unit length and rows are mutually orthogonal.
            fun row(i: Int) = doubleArrayOf(m[i * 3], m[i * 3 + 1], m[i * 3 + 2])
            for (i in 0..2) {
                val r = row(i)
                val len = kotlin.math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
                assertTrue("$view row $i length $len", kotlin.math.abs(len - 1.0) < 1e-9)
            }
            val r0 = row(0); val r1 = row(1); val r2 = row(2)
            // row0 x row1 == row2 (right-handed, determinant +1).
            val cross = doubleArrayOf(
                r0[1] * r1[2] - r0[2] * r1[1],
                r0[2] * r1[0] - r0[0] * r1[2],
                r0[0] * r1[1] - r0[1] * r1[0],
            )
            for (i in 0..2) {
                assertTrue("$view handedness", kotlin.math.abs(cross[i] - r2[i]) < 1e-9)
            }
        }
    }
}

/** Per-note workspace snapshots survive a capture/restore round trip. */
class MathStudioDataTest {
    private class Studio {
        val calculator = CalculatorState()
        val solver = SolverState()
        val plot2d = Plot2DState()
        val plot3d = Plot3DState()
        val fields = FieldState()
        val calculus = CalculusState()
        val symbolic = SymbolicState()
        val units = UnitState()
    }

    private fun capture(s: Studio, tab: String): String =
        MathStudioData.capture(tab, s.calculator, s.solver, s.plot2d, s.plot3d, s.fields, s.calculus, s.symbolic, s.units)

    private fun restore(json: String, s: Studio): String? =
        MathStudioData.restore(json, s.calculator, s.solver, s.plot2d, s.plot3d, s.fields, s.calculus, s.symbolic, s.units)

    @Test fun garbageRestoresNothing() {
        assertNull(restore("not json", Studio()))
        assertNull(restore("{\"v\":99}", Studio()))
    }

    @Test fun calculatorRoundTrip() {
        val a = Studio()
        a.calculator.input = androidx.compose.ui.text.input.TextFieldValue("2+2")
        a.calculator.evaluate()
        a.calculator.variables["x"] = com.xnotes.math.Complex(3.0, 0.0)
        val json = capture(a, "CALCULATOR")
        val b = Studio()
        assertEquals("CALCULATOR", restore(json, b))
        assertEquals("4", b.calculator.input.text)
        assertEquals("4", b.calculator.result)
        assertEquals(1, b.calculator.history.size)
        assertEquals("2+2", b.calculator.history[0].expression)
        assertEquals(com.xnotes.math.Complex(3.0, 0.0), b.calculator.variables["x"])
        assertEquals(com.xnotes.math.Complex(4.0, 0.0), b.calculator.ans)
    }

    @Test fun plot2dEntriesKeepColourAndVisibility() {
        val a = Studio()
        a.plot2d.expressions = listOf(
            MathEntry("sin(x)", 0xFFE06C75.toInt(), true),
            MathEntry("cos(x)", null, false),
        )
        a.plot2d.xMin = -3.0; a.plot2d.xMax = 3.0
        a.plot2d.showGrid = false
        val json = capture(a, "GRAPH_2D")
        val b = Studio()
        assertEquals("GRAPH_2D", restore(json, b))
        assertEquals(2, b.plot2d.expressions.size)
        assertEquals("sin(x)", b.plot2d.expressions[0].text)
        assertEquals(0xFFE06C75.toInt(), b.plot2d.expressions[0].colorArgb)
        assertTrue(b.plot2d.expressions[0].visible)
        assertNull(b.plot2d.expressions[1].colorArgb)
        assertFalse(b.plot2d.expressions[1].visible)
        assertEquals(-3.0, b.plot2d.xMin, 0.0)
        assertEquals(3.0, b.plot2d.xMax, 0.0)
        assertFalse(b.plot2d.showGrid)
    }

    @Test fun plot3dDomainAndStylesRoundTrip() {
        val a = Studio()
        a.plot3d.mode = SurfaceMode.CARTESIAN
        a.plot3d.cartesianList = listOf(MathEntry("x^2", 0xFF61AFEF.toInt(), true), MathEntry("y^2", null, false))
        a.plot3d.paramList = listOf(Param3D("u", "v", "u+v", 0xFF98C379.toInt(), false))
        a.plot3d.xMin = -2.0; a.plot3d.xMax = 2.0
        a.plot3d.zMin = -1.0; a.plot3d.zMax = 1.0
        a.plot3d.showGrid = false
        a.plot3d.orbit.setView(GraphView.Y)
        val json = capture(a, "GRAPH_3D")
        val b = Studio()
        assertEquals("GRAPH_3D", restore(json, b))
        assertEquals("x^2", b.plot3d.cartesianList[0].text)
        assertEquals(0xFF61AFEF.toInt(), b.plot3d.cartesianList[0].colorArgb)
        assertFalse(b.plot3d.cartesianList[1].visible)
        assertEquals("u", b.plot3d.paramList[0].x)
        assertEquals(0xFF98C379.toInt(), b.plot3d.paramList[0].colorArgb)
        assertFalse(b.plot3d.paramList[0].visible)
        assertEquals(-2.0, b.plot3d.xMin, 0.0)
        assertEquals(2.0, b.plot3d.xMax, 0.0)
        assertEquals(-1.0, b.plot3d.zMin, 0.0)
        assertEquals(1.0, b.plot3d.zMax, 0.0)
        assertFalse(b.plot3d.showGrid)
        val m = b.plot3d.orbit.quat.matrix()
        assertTrue(kotlin.math.abs(m[0] - -1.0) < 1e-9)
    }

    @Test fun fieldsRoundTrip() {
        val a = Studio()
        a.fields.mode = FieldMode.VECTOR_2D
        a.fields.vector2List = listOf(Vec2Field("y", "-x", 0xFFF472B6.toInt(), true), Vec2Field("x", "y", null, false))
        a.fields.showGrid = false
        a.fields.probeX = "1.5"
        a.fields.anim.enabled = true
        a.fields.anim.tCurrent = 2.5
        val json = capture(a, "FIELDS")
        val b = Studio()
        assertEquals("FIELDS", restore(json, b))
        assertEquals(FieldMode.VECTOR_2D, b.fields.mode)
        assertEquals(2, b.fields.vector2List.size)
        assertEquals(0xFFF472B6.toInt(), b.fields.vector2List[0].colorArgb)
        assertFalse(b.fields.vector2List[1].visible)
        assertFalse(b.fields.showGrid)
        assertEquals("1.5", b.fields.probeX)
        assertTrue(b.fields.anim.enabled)
        assertEquals(2.5, b.fields.anim.tCurrent, 0.0)
    }

    @Test fun solverAndSymbolicRoundTrip() {
        val a = Studio()
        a.solver.input = androidx.compose.ui.text.input.TextFieldValue("x^2")
        a.solver.op = SolverOp.DERIVATIVE
        a.solver.order = "2"
        a.solver.result = "2"
        a.symbolic.expression = "x^3"
        a.symbolic.result = "3*x^2"
        a.calculus.tool = CalculusTool.SERIES
        a.calculus.seriesExpression = "1/n"
        a.units.value = "2.5"
        a.units.from = "m"
        val json = capture(a, "SOLVER")
        val b = Studio()
        assertEquals("SOLVER", restore(json, b))
        assertEquals("x^2", b.solver.input.text)
        assertEquals(SolverOp.DERIVATIVE, b.solver.op)
        assertEquals("2", b.solver.order)
        assertEquals("2", b.solver.result)
        assertEquals("x^3", b.symbolic.expression)
        assertEquals("3*x^2", b.symbolic.result)
        assertEquals(CalculusTool.SERIES, b.calculus.tool)
        assertEquals("1/n", b.calculus.seriesExpression)
        assertEquals("2.5", b.units.value)
        assertEquals("m", b.units.from)
    }
}
