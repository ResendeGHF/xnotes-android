package com.xnotes.canvas

import com.xnotes.settings.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusCalibrationTest {

    @Test fun targetsStayInsideAndCountNine() {
        val targets = StylusCalibrator.targetsFor(1000.0, 800.0)
        assertEquals(StylusCalibrator.TARGET_COUNT, targets.size)
        for (t in targets) {
            assertTrue(t.x >= 1000.0 * 0.05 && t.x <= 1000.0 * 0.95)
            assertTrue(t.y >= 800.0 * 0.05 && t.y <= 800.0 * 0.95)
        }
        // Centre first: the stable anchor before any edges.
        assertEquals(500.0, targets.first().x, 1e-9)
        assertEquals(400.0, targets.first().y, 1e-9)
    }

    @Test fun uniformShiftIsRecoveredInDp() {
        // Digitizer reports everything 30px left and 20px up; density 3 → +10dp, +20/3dp.
        val taps = StylusCalibrator.targetsFor(900.0, 600.0).map { t ->
            StylusCalibrator.Tap(t.x, t.y, t.x - 30.0, t.y - 20.0)
        }
        val r = StylusCalibrator.estimate(taps, density = 3.0)
        assertNotNull(r)
        assertEquals(10.0, r!!.offsetXDp, 1e-9)
        assertEquals(20.0 / 3.0, r.offsetYDp, 1e-9)
        assertEquals(StylusCalibrator.Quality.EXCELLENT, r.quality)
        assertEquals(taps.size, r.kept)
    }

    @Test fun oneMisTapIsDropped() {
        val good = StylusCalibrator.targetsFor(900.0, 600.0).map { t ->
            StylusCalibrator.Tap(t.x, t.y, t.x - 30.0, t.y - 20.0)
        }
        // One wild tap 200px away: an outlier, not the parallax.
        val taps = good.toMutableList()
        val wild = taps[8]
        taps[8] = wild.copy(reportedX = wild.targetX + 200.0, reportedY = wild.targetY + 200.0)
        val r = StylusCalibrator.estimate(taps, density = 3.0)
        assertNotNull(r)
        assertEquals(taps.size - 1, r!!.kept)
        assertEquals(10.0, r.offsetXDp, 0.5)
        assertEquals(20.0 / 3.0, r.offsetYDp, 0.5)
    }

    @Test fun tooFewTapsGivesNoEstimate() {
        assertNull(StylusCalibrator.estimate(emptyList(), density = 3.0))
        val two = listOf(
            StylusCalibrator.Tap(0.0, 0.0, 1.0, 1.0),
            StylusCalibrator.Tap(10.0, 10.0, 11.0, 11.0),
        )
        assertNull(StylusCalibrator.estimate(two, density = 3.0))
    }

    @Test fun scatteredTapsGradePoor() {
        // Genuinely inconsistent taps with no majority cluster: offsets 0..200 px in
        // steps of 25, so no trim is possible and the spread stays huge.
        val taps = List(9) { i ->
            StylusCalibrator.Tap(i * 100.0, i * 50.0, i * 100.0 - i * 25.0, i * 50.0)
        }
        val r = StylusCalibrator.estimate(taps, density = 2.0)
        assertNotNull(r)
        assertEquals(StylusCalibrator.Quality.POOR, r!!.quality)
    }

    @Test fun offsetRoundTripsThroughPrefs() {
        val prefs = Preferences(stylusOffsetXDp = 2.5, stylusOffsetYDp = -1.5)
        val back = Preferences.fromJson(prefs.toJson())
        assertEquals(2.5, back.stylusOffsetXDp, 1e-9)
        assertEquals(-1.5, back.stylusOffsetYDp, 1e-9)
    }

    @Test fun offsetDefaultsToZeroAndIsUnwritten() {
        val defaults = Preferences.fromJson(org.json.JSONObject())
        assertEquals(0.0, defaults.stylusOffsetXDp, 0.0)
        assertEquals(0.0, defaults.stylusOffsetYDp, 0.0)
        assertTrue(!defaults.toJson().has("stylus_offset_x_dp"))
        assertTrue(!defaults.toJson().has("stylus_offset_y_dp"))
    }

    @Test fun outOfRangeOffsetIsClamped() {
        val json = org.json.JSONObject()
            .put("stylus_offset_x_dp", 999.0)
            .put("stylus_offset_y_dp", -999.0)
        val back = Preferences.fromJson(json)
        assertEquals(StylusCalibrator.MAX_OFFSET_DP, back.stylusOffsetXDp, 1e-9)
        assertEquals(-StylusCalibrator.MAX_OFFSET_DP, back.stylusOffsetYDp, 1e-9)
    }
}
