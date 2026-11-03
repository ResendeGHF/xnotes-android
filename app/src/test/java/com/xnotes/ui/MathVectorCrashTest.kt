package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 3D vector pane crash: typing the first character of one
 * component (P="x", Q/R still blank) must degrade to the empty grid, never throw —
 * through snapshot, legend, persistence and the animation badge. Bitmap raster paths
 * cannot run on the JVM, so these cover every JVM-reachable step of that keystroke.
 */
class MathVectorCrashTest {

    private fun partialVectorState(p: String = "x"): FieldState {
        val s = FieldState()
        s.mode = FieldMode.VECTOR_3D
        s.vector3List = listOf(Vec3Field(p, "", ""))
        return s
    }

    @Test fun singleCharPartialVectorSnapshotsWithoutThrowing() {
        for (p in listOf("x", "y", "z", "1", "s", "-", "sin", "(", "x+")) {
            val s = partialVectorState(p)
            assertTrue("p=$p", s.hasContent())
            val snap = s.snapshot()
            assertEquals(1, snap.vector3.size)
            assertEquals(p, snap.vector3[0].p)
            // Persistence round-trip keeps the half-typed entry.
            val json = MathStudioData.capture("FIELDS", CalculatorState(), SolverState(), Plot2DState(),
                Plot3DState(), s, CalculusState(), SymbolicState(), UnitState())
            val g = FieldState()
            MathStudioData.restore(json, CalculatorState(), SolverState(), Plot2DState(),
                Plot3DState(), g, CalculusState(), SymbolicState(), UnitState())
            assertEquals(p, g.vector3List.firstOrNull()?.p)
        }
    }

    @Test fun partialVectorAnimBadgeRoundTrips() {
        val s = partialVectorState("x")
        s.anim.enabled = true
        val snap = s.snapshot()
        assertNotNull(snap.animRange)
        val spec = MathAnimSpec.captureFields(snap)
        assertNotNull(spec)
        val parsed = MathAnimSpec.parse(spec!!.toJson())
        assertNotNull(parsed)
        val f = FieldState()
        parsed!!.applyTo(Plot2DState(), Plot3DState(), f)
        assertEquals("x", f.vector3List.firstOrNull()?.p)
    }

    @Test fun vectorColorHelperNeverThrowsOnDegenerateMagnitudes() {
        // 0/0-style ratios from empty clouds must still resolve to a colour.
        for (t in listOf(0.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.5, 1.0)) {
            val c = vectorEntryColor(null, true, true, FieldPalette.JET, t, 0xFF000000.toInt())
            assertTrue(c != 0)
        }
        // Plain-colour mode ignores the ramp entirely.
        assertEquals(0xFF112233.toInt(), vectorEntryColor(null, true, false, FieldPalette.JET, 0.9, 0xFF112233.toInt()))
    }

    @Test fun emptyCloudListsHaveNoPlottableRange() {
        // The raster guards behind this: empty sampler output means the empty grid.
        val clouds = emptyList<VectorCloud>()
        assertEquals(null, clouds.maxOfOrNull { it.maxMag })
        val scalars = emptyList<ScalarCloud>()
        assertEquals(null, scalars.maxOfOrNull { it.hi })
    }
}
