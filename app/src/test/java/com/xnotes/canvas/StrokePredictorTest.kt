package com.xnotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokePredictorTest {

    @Test fun predictsAheadAlongMotion() {
        // 10 units/ms rightwards; 8 ms lead = 80 units, clamped to maxLead 20.
        val p = StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, maxLead = 20.0)!!
        assertEquals(120.0, p.x, 1e-9)
        assertEquals(0.0, p.y, 1e-9)
    }

    @Test fun unclampedLeadUsesFullEightMs() {
        val p = StrokePredictor.predict(0.0, 0.0, 0.0, 10.0, 0.0, 10.0, maxLead = 1000.0)!!
        assertEquals(18.0, p.x, 1e-9)
    }

    @Test fun stoppedPenPredictsNothing() {
        assertNull(StrokePredictor.predict(5.0, 5.0, 0.0, 5.0, 5.0, 10.0, maxLead = 20.0))
    }

    @Test fun degenerateTimingPredictsNothing() {
        assertNull(StrokePredictor.predict(0.0, 0.0, 10.0, 100.0, 0.0, 10.0, maxLead = 20.0))
        assertNull(StrokePredictor.predict(0.0, 0.0, 10.0, 100.0, 0.0, 10.0, maxLead = 0.0))
    }

    @Test fun teleportPredictsNothing() {
        assertNull(StrokePredictor.predict(0.0, 0.0, 0.0, 10000.0, 0.0, 10.0, maxLead = 1e9))
    }

    @Test fun nonFiniteInputPredictsNothing() {
        assertNull(StrokePredictor.predict(Double.NaN, 0.0, 0.0, 1.0, 0.0, 1.0, maxLead = 20.0))
    }

    @Test fun steadyMotionKeepsFullLead() {
        // 10 u/ms on both segments: ratio 1, 8 ms lead clamped to maxLead 20.
        val p = StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 200.0, 0.0, 20.0, maxLead = 20.0)!!
        assertEquals(220.0, p.x, 1e-9)
        assertEquals(0.0, p.y, 1e-9)
    }

    @Test fun decelerationShrinksLead() {
        // Second segment at half speed: the lead halves too (80 -> 40, clamped by maxLead).
        val full = StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 200.0, 0.0, 20.0, maxLead = 1e9)!!
        val slow = StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 150.0, 0.0, 20.0, maxLead = 1e9)!!
        assertEquals(280.0, full.x, 1e-9)
        assertEquals(170.0, slow.x, 1e-9)
    }

    @Test fun suddenStopPredictsNothing() {
        // Last segment barely moves: below the minimum lead, so the tip converges.
        assertNull(StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 100.1, 0.0, 20.0, maxLead = 20.0))
    }

    @Test fun degenerateThreePointTimingPredictsNothing() {
        assertNull(StrokePredictor.predict(0.0, 0.0, 10.0, 1.0, 0.0, 10.0, 2.0, 0.0, 11.0, maxLead = 20.0))
    }
}
