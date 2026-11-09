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
}
