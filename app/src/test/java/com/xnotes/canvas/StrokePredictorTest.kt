package com.xnotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun commitTipNeedsAForwardExtensionWithinReach() {
        // Straight continuation just past the nib: commit it.
        assertTrue(StrokePredictor.shouldCommitTip(0.0, 0.0, 100.0, 0.0, 108.0, 0.0, maxLead = 12.0))
        // A tip behind the nib on the drawn path is already covered: nothing would vanish.
        assertFalse(StrokePredictor.shouldCommitTip(0.0, 0.0, 100.0, 0.0, 90.0, 0.0, maxLead = 12.0))
        // Too far away to be this stroke's tip: stale or a glitch.
        assertFalse(StrokePredictor.shouldCommitTip(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, maxLead = 12.0))
        // Off to the side but within reach: it was shown, so the commit keeps it.
        assertTrue(StrokePredictor.shouldCommitTip(0.0, 0.0, 100.0, 0.0, 100.0, 8.0, maxLead = 12.0))
        // Zero-length: nothing to commit.
        assertFalse(StrokePredictor.shouldCommitTip(0.0, 0.0, 100.0, 0.0, 100.0, 0.0, maxLead = 12.0))
        // Covered mid-segment: already drawn, appending would only double-draw.
        assertFalse(StrokePredictor.shouldCommitTip(0.0, 0.0, 10.0, 0.0, 5.0, 0.3, maxLead = 12.0))
        // Degenerate last segment (pen held still): a tip just ahead still commits.
        assertTrue(StrokePredictor.shouldCommitTip(50.0, 0.0, 50.0, 0.0, 54.0, 0.0, maxLead = 12.0))
        // A turn after the last sample: the shown tip is kept, not snapped away.
        assertTrue(StrokePredictor.shouldCommitTip(0.0, 0.0, 10.0, 0.0, 16.0, 6.0, maxLead = 12.0))
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

    @Test fun sharpTurnSuppressesPrediction() {
        // Full speed into a right angle: nothing ahead is safe to draw.
        assertNull(StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 100.0, 100.0, 20.0, maxLead = 1e9))
    }

    @Test fun gentleCurveKeepsScaledLead() {
        // 45 degrees keeps cos(45deg) of the lead.
        val straight = StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 200.0, 0.0, 20.0, maxLead = 1e9)!!
        val turn = StrokePredictor.predict(0.0, 0.0, 0.0, 100.0, 0.0, 10.0, 200.0, 100.0, 20.0, maxLead = 1e9)!!
        assertEquals(280.0, straight.x, 1e-9)
        val lead = 8.0 * 0.70710678
        assertEquals(200.0 + 10.0 * lead, turn.x, 0.01)
        assertEquals(100.0 + 10.0 * lead, turn.y, 0.01)
    }

    @Test fun degenerateThreePointTimingPredictsNothing() {
        assertNull(StrokePredictor.predict(0.0, 0.0, 10.0, 1.0, 0.0, 10.0, 2.0, 0.0, 11.0, maxLead = 20.0))
    }
}
