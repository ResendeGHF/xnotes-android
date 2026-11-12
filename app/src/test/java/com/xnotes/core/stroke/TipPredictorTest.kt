package com.xnotes.core.stroke

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TipPredictorTest {

    /** Feed a straight constant-velocity stroke in view px at 120 Hz. */
    private fun straight(
        predictor: TipPredictor,
        n: Int = 12,
        fromX: Double = 0.0,
        fromY: Double = 0.0,
        stepPx: Double = 8.0,
        dtMs: Double = 8.0,
        pressure: Double = 0.5,
        density: Double = 2.0,
        accurate: Boolean = false,
    ): TipPredictor {
        predictor.start(fromX, fromY, pressure, 0.0, density, accurate)
        for (i in 1..n) {
            predictor.record(fromX + i * stepPx, fromY, pressure, i * dtMs)
        }
        return predictor
    }

    @Test fun needsMinimumSamplesBeforePredicting() {
        val p = TipPredictor()
        p.start(0.0, 0.0, 0.5, 0.0, 1.0, false)
        assertNull(p.predict(100.0))
        p.record(8.0, 0.0, 0.5, 8.0)
        assertNull(p.predict(100.0))
        p.record(16.0, 0.0, 0.5, 16.0)
        assertNull(p.predict(100.0))
    }

    @Test fun predictsAheadAlongSteadyMotion() {
        val p = straight(TipPredictor())
        val tip = p.predict(200.0) ?: error("expected a prediction")
        // Last sample at x=96; an 8 ms lookahead at 1 px/ms heads +x.
        assertTrue("tip.x=${tip.x} should be ahead of 96", tip.x > 96.0)
        assertTrue("tip.x=${tip.x} should stay bounded", tip.x <= 96.0 + 200.0)
        assertEquals(0.0, tip.y, 3.0)
        assertTrue(tip.confidence in 0.0..1.0)
    }

    @Test fun leadScalesWithHorizon() {
        // Fast steady motion (full confidence): one report step per 8 ms of horizon.
        fun run(horizonMs: Double): Double {
            val p = TipPredictor()
            p.start(0.0, 0.0, 0.5, 0.0, 1.0, true)
            for (i in 1..16) p.record(i * 16.0, 0.0, 0.5, i * 8.0)
            return p.predict(500.0, horizonMs = horizonMs)!!.x
        }
        val near = run(4.0)
        val far = run(16.0)
        assertTrue("far lead $far should exceed near lead $near", far > near)
    }

    @Test fun stoppedPenConvergesToRest() {
        val p = TipPredictor()
        p.start(0.0, 0.0, 0.5, 0.0, 1.0, false)
        for (i in 1..16) p.record(i * 8.0, 0.0, 0.5, i * 8.0)
        var t = 16 * 8.0
        var tip = p.predict(200.0)
        assertNotNull("moving pen should predict", tip)
        repeat(30) {
            t += 8.0
            p.record(128.0, 0.0, 0.5, t)
        }
        tip = p.predict(200.0)
        if (tip != null) {
            assertTrue("held pen must (nearly) stop predicting, got ${tip.x}", tip.x <= 128.0 + 2.0)
        }
    }

    @Test fun hardTurnShrinksTheLead() {
        fun leadAfter(turn: Boolean): Double {
            val p = TipPredictor()
            p.start(0.0, 0.0, 0.5, 0.0, 1.0, true)
            for (i in 1..16) p.record(i * 8.0, 0.0, 0.5, i * 8.0)
            var t = 16 * 8.0
            for (i in 1..4) {
                t += 8.0
                if (turn) p.record(128.0, i * 8.0, 0.5, t) else p.record(128.0 + i * 8.0, 0.0, 0.5, t)
            }
            return p.predict(500.0)?.let { hypot(it.x - 128.0, it.y - 32.0) } ?: 0.0
        }
        val straightLead = leadAfter(false)
        val turnLead = leadAfter(true)
        assertTrue("turn lead $turnLead should be smaller than straight lead $straightLead", turnLead < straightLead)
    }

    @Test fun jitterDoesNotExplode() {
        val p = TipPredictor()
        p.start(0.0, 0.0, 0.5, 0.0, 2.0, true)
        var t = 0.0
        // Noisy straight-ish motion with ±1.5 px jitter at high density.
        val rnd = java.util.Random(7)
        for (i in 1..40) {
            t += 8.0
            p.record(i * 6.0 + (rnd.nextDouble() - 0.5) * 3.0, (rnd.nextDouble() - 0.5) * 3.0, 0.5, t)
        }
        val tip = p.predict(50.0)
        if (tip != null) {
            assertTrue("jittered lead must stay bounded, got (${tip.x}, ${tip.y})",
                tip.x < 240.0 + 60.0 && abs(tip.y) < 60.0)
            assertTrue(tip.confidence in 0.0..1.0)
        }
    }

    @Test fun teleportIsIgnored() {
        val p = straight(TipPredictor())
        val before = p.predict(200.0)!!
        // A wild jump (palm touch, glitch) must not corrupt the filter or spike the lead.
        p.record(100000.0, 50000.0, 0.5, 200.0)
        val after = p.predict(200.0)
        if (after != null) {
            assertTrue("teleport must not fling the tip, got (${after.x}, ${after.y})",
                hypot(after.x - 96.0, after.y) < 2000.0)
        }
        // Filter still tracks the real line afterwards.
        p.record(104.0, 0.0, 0.5, 208.0)
        p.record(112.0, 0.0, 0.5, 216.0)
        val recovered = p.predict(200.0)
        assertNotNull(recovered)
    }

    @Test fun resetRequiresWarmupAgain() {
        val p = straight(TipPredictor())
        assertNotNull(p.predict(200.0))
        p.reset()
        assertNull(p.predict(200.0))
        assertFalse(p.isStarted())
    }

    @Test fun degenerateInputNeverProducesNaN() {
        val p = TipPredictor()
        // Identical timestamps, zero density, NaN guarded at the call sites.
        p.start(10.0, 10.0, 0.5, 100.0, 0.0, true)
        p.record(10.0, 10.0, 0.5, 100.0)
        p.record(10.0, 10.0, 0.5, 100.0)
        repeat(10) { p.record(10.0, 10.0, 0.5, 108.0 + it) }
        val tip = p.predict(50.0)
        if (tip != null) {
            assertTrue(tip.x.isFinite() && tip.y.isFinite())
            assertTrue("still pen must barely move, got (${tip.x}, ${tip.y})",
                hypot(tip.x - 10.0, tip.y - 10.0) < 5.0)
        }
    }

    @Test fun maxLeadClampsReachButKeepsDirection() {
        val p = TipPredictor()
        p.start(0.0, 0.0, 0.5, 0.0, 1.0, true)
        for (i in 1..16) p.record(i * 16.0, 0.0, 0.5, i * 8.0)
        val tip = p.predict(10.0)!!
        val dx = tip.x - 256.0
        assertTrue("clamped lead should be ~10px, got $dx", abs(dx - 10.0) < 2.0)
        assertEquals(0.0, tip.y, 2.0)
    }

    @Test fun stylusIgnoresSlowSpeedGate() {
        // A slow-but-steady stylus move still predicts (jank-gated only).
        val p = TipPredictor()
        p.start(0.0, 0.0, 0.5, 0.0, 1.0, true)
        for (i in 1..12) p.record(i * 1.0, 0.0, 0.5, i * 8.0)
        val tip = p.predict(200.0)
        assertNotNull("slow stylus motion should still predict", tip)
        assertTrue(tip!!.x > 12.0)
    }

    @Test fun kalmanTracksConstantVelocity() {
        // Direct filter check: feed a ramp, velocity state must converge near the slope.
        val f = KalmanFilter(4, 1)
        f.f[0, 0] = 1.0; f.f[0, 1] = 1.0; f.f[0, 2] = 0.5; f.f[0, 3] = 1.0 / 6.0
        f.f[1, 1] = 1.0; f.f[1, 2] = 1.0; f.f[1, 3] = 0.5
        f.f[2, 2] = 1.0; f.f[2, 3] = 1.0
        f.f[3, 3] = 1.0
        val g = doubleArrayOf(1.0 / 6.0, 0.5, 1.0, 1.0)
        for (i in 0..3) for (j in 0..3) f.q[i, j] = 0.01 * g[i] * g[j]
        f.h[0, 0] = 1.0
        f.r[0, 0] = 1.0
        // First sample initializes position directly.
        f.x[0, 0] = 0.0
        for (k in 1..30) {
            f.predict()
            f.update(k * 3.0)
        }
        assertEquals(3.0, f.x[1, 0], 0.5)
        assertEquals(90.0, f.x[0, 0], 3.0)
    }
}
