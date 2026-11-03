package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turntable orbit, ISO octant, domain centre/lock controls. */
class MathOrbitControlTest {

    private fun row2(q: Quat): Triple<Double, Double, Double> {
        val m = q.matrix()
        return Triple(m[6], m[7], m[8])
    }

    @Test fun isoLooksFromPositiveOctantWithZUp() {
        val (x, y, z) = row2(isoQuat())
        // Camera sits in the (+,+,+) octant.
        assertTrue("x=$x", x > 0)
        assertTrue("y=$y", y > 0)
        assertTrue("z=$z", z > 0)
        // World +z still projects upward on screen.
        assertTrue(isoQuat().matrix()[5] > 0)
    }

    @Test fun horizontalDragYawsAroundWorldZ() {
        val orbit = OrbitState()
        // Turntable yaw spins about world Z: the z-axis tip must not move on screen
        // (a roll would swing it). This is the regression test for "pan left/right rotates".
        fun zTip(q: Quat) = quatProject(0.0, 0.0, 3.0, q, 1f, 800, 600, 5.0, 5.0, 3.0)
        val before = zTip(orbit.quat)
        val m0 = orbit.quat.matrix()
        orbit.drag(120f, 0f)
        val after = zTip(orbit.quat)
        assertEquals(before.first, after.first, 1e-3f)
        assertEquals(before.second, after.second, 1e-3f)
        // ...but the view did turn: the camera-forward azimuth changed.
        val m1 = orbit.quat.matrix()
        assertTrue(kotlin.math.abs(m0[6] - m1[6]) + kotlin.math.abs(m0[7] - m1[7]) > 1e-6)
    }

    @Test fun verticalDragMovesPixelsVerticallyOnly() {
        val orbit = OrbitState()
        // Regression test for "pan up/down goes left/right": a vertical drag must not
        // drift pixels horizontally.
        val before = quatProject(5.0, 0.0, 0.0, orbit.quat, 1f, 800, 600, 5.0, 5.0, 3.0)
        orbit.drag(0f, 10f)
        val after = quatProject(5.0, 0.0, 0.0, orbit.quat, 1f, 800, 600, 5.0, 5.0, 3.0)
        assertEquals(before.first, after.first, 1e-2f)
        assertTrue(kotlin.math.abs(before.second - after.second) > 1e-3f)
        // Dragging the other way reverses the vertical motion.
        orbit.drag(0f, -10f)
        val back = quatProject(5.0, 0.0, 0.0, orbit.quat, 1f, 800, 600, 5.0, 5.0, 3.0)
        assertEquals(before.first, back.first, 1e-2f)
        assertEquals(before.second, back.second, 1e-2f)
        // Still a unit quaternion.
        val q = orbit.quat
        assertEquals(1.0, q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z, 1e-9)
    }

    @Test fun verticalDragPitchesCamera() {
        val orbit = OrbitState()
        val before = row2(orbit.quat)
        orbit.drag(0f, 120f)
        val after = row2(orbit.quat)
        assertTrue(kotlin.math.abs(before.first - after.first) > 1e-6 ||
            kotlin.math.abs(before.second - after.second) > 1e-6 ||
            kotlin.math.abs(before.third - after.third) > 1e-6)
        // Still a unit quaternion.
        val q = orbit.quat
        assertEquals(1.0, q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z, 1e-9)
    }

    @Test fun zeroAndNonFiniteDragIsNoop() {
        val orbit = OrbitState()
        val before = orbit.quat.matrix().toList()
        orbit.drag(0f, 0f)
        orbit.drag(Float.NaN, 1f)
        orbit.drag(1f, Float.POSITIVE_INFINITY)
        val after = orbit.quat.matrix().toList()
        assertEquals(before, after)
    }

    @Test fun centerShiftPreservesWidths() {
        val s = Plot3DState()
        s.shiftCenterX(2.0)
        assertEquals(-3.0, s.xMin, 1e-9)
        assertEquals(7.0, s.xMax, 1e-9)
        s.shiftCenterY(-1.0)
        assertEquals(-6.0, s.yMin, 1e-9)
        assertEquals(4.0, s.yMax, 1e-9)
        s.shiftCenterZ(3.0)
        assertEquals(-2.0, s.zMin, 1e-9)
        assertEquals(8.0, s.zMax, 1e-9)
    }

    @Test fun centerShiftIgnoresNonFinite() {
        val s = Plot3DState()
        s.shiftCenterX(Double.NaN)
        s.shiftCenterY(Double.POSITIVE_INFINITY)
        assertEquals(-5.0, s.xMin, 0.0)
        assertEquals(5.0, s.xMax, 0.0)
        assertEquals(-5.0, s.yMin, 0.0)
        assertEquals(5.0, s.yMax, 0.0)
    }

    @Test fun zoomDomainGrowsAndShrinksBox() {
        val s = Plot3DState()
        s.zoomDomain(0.5f)
        assertEquals(-10.0, s.xMin, 1e-9)
        assertEquals(10.0, s.xMax, 1e-9)
        s.zoomDomain(2f)
        assertEquals(-5.0, s.xMin, 1e-9)
        assertEquals(5.0, s.xMax, 1e-9)
        s.zoomDomain(0f)
        s.zoomDomain(-1f)
        s.zoomDomain(Float.NaN)
        assertEquals(-5.0, s.xMin, 1e-9)
        assertEquals(5.0, s.xMax, 1e-9)
    }

    @Test fun lockDefaultsToLocked() {
        assertTrue(Plot3DState().lockDomain)
        assertTrue(FieldState().lockDomain)
    }

    @Test fun fieldZoomDomainScalesRanges() {
        val s = FieldState()
        s.zoomDomain2D(0.5f)
        assertEquals("10", s.xRange)
        assertEquals("10", s.yRange)
        s.zoomDomain2D(2f)
        assertEquals("5", s.xRange)
        assertEquals("5", s.yRange)
        s.zoomDomain2D(0f)
        assertEquals("5", s.xRange)
    }

    @Test fun fieldZoomDomain3DScalesRanges() {
        val s = FieldState()
        s.zoomDomain3D(0.5f)
        assertEquals("10", s.xRange)
        assertEquals("10", s.yRange)
        assertEquals("6", s.zRange)
        s.zoomDomain3D(2f)
        assertEquals("5", s.xRange)
        assertEquals("5", s.yRange)
        assertEquals("3", s.zRange)
        s.zoomDomain3D(0f)
        assertEquals("5", s.xRange)
    }

    @Test fun fieldPanOffsetTracksCenterDelta() {
        // No movement, no shift.
        assertEquals(0f to 0f, fieldPanOffset(0.0, 0.0, 0.0, 0.0, 5.0, 5.0, 800, 600))
        // View window moved left in world (drag right): image shifts right.
        val (ox, _) = fieldPanOffset(0.0, 0.0, -1.0, 0.0, 5.0, 5.0, 800, 600)
        assertEquals(80f, ox, 1e-3f)
        // View window moved up in world (drag down): image shifts down (+y is down).
        val (_, oy) = fieldPanOffset(0.0, 0.0, 0.0, 1.0, 5.0, 5.0, 800, 600)
        assertEquals(60f, oy, 1e-3f)
        // Degenerate inputs never shift.
        assertEquals(0f to 0f, fieldPanOffset(0.0, 0.0, 1.0, 1.0, 0.0, 5.0, 800, 600))
        assertEquals(0f to 0f, fieldPanOffset(Double.NaN, 0.0, 1.0, 1.0, 5.0, 5.0, 800, 600))
        assertEquals(0f to 0f, fieldPanOffset(0.0, 0.0, 1.0, 1.0, 5.0, 5.0, 0, 600))
    }

    @Test fun zoomDomainKeepsCentre() {
        val s = Plot3DState()
        s.shiftCenterX(2.0)
        s.zoomDomain(0.5f)
        assertEquals(-8.0, s.xMin, 1e-9)
        assertEquals(12.0, s.xMax, 1e-9)
    }

    @Test fun lockPersistsInNote() {
        val p3 = Plot3DState()
        p3.lockDomain = false
        val f = FieldState()
        f.lockDomain = false
        val json = MathStudioData.capture("GRAPH_3D", CalculatorState(), SolverState(), Plot2DState(),
            p3, f, CalculusState(), SymbolicState(), UnitState())
        val q3 = Plot3DState()
        val qf = FieldState()
        MathStudioData.restore(json, CalculatorState(), SolverState(), Plot2DState(),
            q3, qf, CalculusState(), SymbolicState(), UnitState())
        assertFalse(q3.lockDomain)
        assertFalse(qf.lockDomain)
    }

    @Test fun lockRoundTripsThroughAnimBadge() {
        val snap = Plot3DSnap(SurfaceMode.CARTESIAN, emptyList(), emptyList(),
            ComplexComponent.MAGNITUDE, -5.0, 5.0, -5.0, 5.0, -5.0, 5.0, 24,
            Quat.IDENTITY, 1f, null, false, AnimSnap(0.0, 1.0, 8, 0.0, false), true, false)
        val spec = MathAnimSpec.capture3D(snap)
        assertNotNull(spec)
        val parsed = MathAnimSpec.parse(spec!!.toJson())
        assertNotNull(parsed)
        assertFalse(parsed!!.lock)
        val p3 = Plot3DState()
        parsed.applyTo(Plot2DState(), p3, FieldState())
        assertFalse(p3.lockDomain)
    }
}
