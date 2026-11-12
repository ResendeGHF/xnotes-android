package com.xnotes.core.stroke

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One-frame lookahead for the pen tip, so live ink lands where the pen will be
 * at the next present instead of where its last event was.
 *
 * The filter core is a port of AOSP's `androidx.input.motionprediction` Kalman
 * stack (Apache License 2.0, platform/frameworks/support,
 * input/input-motionprediction): one 4-state (position, velocity, acceleration,
 * jank) Kalman filter per axis plus pressure, normalized to dt=1, with a noisy
 * process model (Q from the G vector) and position-only measurements (H).
 * The gating around it mirrors `SinglePointerPredictor`: a minimum sample
 * count, report-rate estimation, and a confidence factor that shrinks the
 * lookahead when the pen is slow (finger) or the path turns (high jank), so a
 * stopping pen converges onto its real position and a whipping pen never
 * fires spikes off in the old direction.
 *
 * Two deliberate deviations from the AOSP transcription, both documented here:
 * - `predict()` writes the covariance update back into P (F·P·Fᵀ + Q). The
 *   upstream allocation-trimming refactor computes it into a scratch matrix
 *   that is then dropped, which would freeze P after the first step.
 * - The "accurate tool" (stylus) speed range is treated as fully confident
 *   instead of the upstream `[0, 0]` range, whose division produces NaN for a
 *   still pen. A still pen is handled by decaying velocity instead.
 *
 * All coordinates are in device pixels (view px); pass the view density at
 * [start] so internal thresholds stay resolution-independent. Times are
 * milliseconds on the event clock. Predicted points are display-only: they
 * must never enter committed geometry, only the live tail, and a new real
 * sample always replaces them.
 */
class TipPredictor(
    /** How far ahead of the last sample to project, in milliseconds. */
    private val horizonMs: Double = 8.0,
    /** Samples required before any prediction is emitted. */
    private val minIterations: Int = 4,
) {
    /** A predicted tip in view pixels, with the 0..1 confidence behind it. */
    data class Tip(val x: Double, val y: Double, val pressure: Double, val confidence: Double)

    /** Influence of jank/acceleration/velocity per projected report step. */
    private companion object {
        const val JANK_INFLUENCE = 0.1
        const val ACCEL_INFLUENCE = 0.5
        const val VELOCITY_INFLUENCE = 1.0

        /** Jank range mapping to a 1..0 confidence multiplier. */
        const val LOW_JANK = 0.02
        const val HIGH_JANK = 0.2

        /** Sustained finger speed (dp/ms) mapping to a 0..1 confidence multiplier. */
        const val LOW_SPEED = 0.0
        const val HIGH_SPEED = 2.0

        /** Slowest report interval tracked for projection; also the dt fallback. */
        const val FALLBACK_REPORT_MS = 8.0

        /** Implied per-sample speed that must be a teleport or glitch: skip the update. */
        const val MAX_SPEED_PX_PER_MS = 20.0

        /** Projections shorter than this (view px) are not worth drawing. */
        const val MIN_LEAD_PX = 0.5
    }

    private val xFilter = axisFilter()
    private val yFilter = axisFilter()
    private val pFilter = axisFilter()

    private var iterations = 0
    private var reportSamples = 0
    private var reportSumMs = 0.0
    private var reportRateMs = FALLBACK_REPORT_MS
    private var lastT = Double.NaN
    private var lastX = 0.0
    private var lastY = 0.0
    private var density = 1.0
    private var accurate = true
    private var started = false

    private fun axisFilter(): KalmanFilter {
        // State transition from constant-jerk physics at normalized dt=1, matching AOSP's
        // PointerKalmanFilter (0.16 == dt^3/6, 0.5 == dt^2/2 with dt=1).
        val dt = 1.0
        val f = KalmanFilter(4, 1)
        f.f[0, 0] = 1.0; f.f[0, 1] = dt; f.f[0, 2] = 0.5 * dt * dt; f.f[0, 3] = 0.16 * dt * dt * dt
        f.f[1, 0] = 0.0; f.f[1, 1] = 1.0; f.f[1, 2] = dt; f.f[1, 3] = 0.5 * dt * dt
        f.f[2, 0] = 0.0; f.f[2, 1] = 0.0; f.f[2, 2] = 1.0; f.f[2, 3] = dt
        f.f[3, 0] = 0.0; f.f[3, 1] = 0.0; f.f[3, 2] = 0.0; f.f[3, 3] = 1.0
        // Noisy-force process model: Q = sigmaProcess * G·Gᵀ.
        val g = doubleArrayOf(0.16 * dt * dt * dt, 0.5 * dt * dt, dt, 1.0)
        for (i in 0..3) {
            for (j in 0..3) f.q[i, j] = 0.01 * g[i] * g[j]
        }
        // Position-only measurements with unit noise.
        f.h[0, 0] = 1.0
        f.r[0, 0] = 1.0
        return f
    }

    /** Begin a stroke. Coordinates are view pixels; [density] is view px per dp. */
    fun start(x: Double, y: Double, pressure: Double, tMs: Double, density: Double, accurate: Boolean) {
        xFilter.reset()
        yFilter.reset()
        pFilter.reset()
        iterations = 0
        reportSamples = 0
        reportSumMs = 0.0
        reportRateMs = FALLBACK_REPORT_MS
        lastT = Double.NaN
        lastX = x
        lastY = y
        this.density = if (density.isFinite() && density > 0.0) density else 1.0
        this.accurate = accurate
        started = true
        record(x, y, pressure, tMs)
    }

    /** Fold in a new raw sample (view pixels, event ms). Out-of-order or absurd input is skipped. */
    fun record(x: Double, y: Double, pressure: Double, tMs: Double) {
        if (!started) return
        if (!x.isFinite() || !y.isFinite() || !pressure.isFinite() || !tMs.isFinite()) return
        if (!lastT.isNaN() && tMs <= lastT) return
        if (!lastT.isNaN()) {
            val dt = tMs - lastT
            // Teleport or glitch: don't let one wild sample corrupt the velocity history.
            if (dt > 0.0 && hypot(x - lastX, y - lastY) / dt > MAX_SPEED_PX_PER_MS) return
            if (reportSamples < 20 && dt > 0.0 && dt < 1000.0) {
                reportSumMs += dt
                reportSamples++
                reportRateMs = reportSumMs / reportSamples
            }
        }
        lastT = tMs
        lastX = x
        lastY = y
        // Filter in dp so the tuned speed/jank thresholds hold at any density.
        val inv = 1.0 / density
        if (iterations == 0) {
            xFilter.x[0, 0] = x * inv
            yFilter.x[0, 0] = y * inv
            pFilter.x[0, 0] = pressure.coerceIn(0.0, 1.0)
        } else {
            xFilter.predict()
            xFilter.update(x * inv)
            yFilter.predict()
            yFilter.update(y * inv)
            pFilter.predict()
            pFilter.update(pressure.coerceIn(0.0, 1.0))
        }
        iterations++
    }

    /**
     * Project the tip [horizonMs] (construction default 8 ms) into the future, clamped to
     * [maxLeadPx] view pixels of travel. Returns null while the filter is still warming
     * up, when the pen is effectively stopped or turning hard, or on any invalid state.
     * The result is display-only: never commit it, a real sample always replaces it.
     */
    fun predict(maxLeadPx: Double, horizonMs: Double = this.horizonMs): Tip? {
        if (!started || iterations < minIterations) return null
        if (!maxLeadPx.isFinite() || maxLeadPx <= 0.0) return null
        if (!horizonMs.isFinite() || horizonMs <= 0.0) return null
        val reportRate = if (reportRateMs.isFinite() && reportRateMs > 0.0) reportRateMs else FALLBACK_REPORT_MS

        var px = xFilter.x[0, 0]
        var py = yFilter.x[0, 0]
        var vx = xFilter.x[1, 0]
        var vy = yFilter.x[1, 0]
        var ax = xFilter.x[2, 0]
        var ay = yFilter.x[2, 0]
        val jx = xFilter.x[3, 0]
        val jy = yFilter.x[3, 0]
        var pressure = pFilter.x[0, 0]
        var pressureChange = pFilter.x[1, 0]
        if (!px.isFinite() || !py.isFinite() || !vx.isFinite() || !vy.isFinite() ||
            !ax.isFinite() || !ay.isFinite() || !jx.isFinite() || !jy.isFinite() ||
            !pressure.isFinite() || !pressureChange.isFinite()
        ) return null

        // Confidence gate: fast straight motion predicts fully, slow or jerky motion shrinks
        // toward nothing so a stop converges and a turn does not fire off in the old direction.
        val speedAbs = hypot(vx, vy) / reportRate
        val speedFactor = if (accurate) 1.0 else normalize(speedAbs, LOW_SPEED, HIGH_SPEED)
        val jankAbs = hypot(jx, jy)
        val jankFactor = 1.0 - normalize(jankAbs, LOW_JANK, HIGH_JANK)
        val confidence = speedFactor * jankFactor
        if (!(confidence > 0.0) || !confidence.isFinite()) return null

        var steps = kotlin.math.ceil(horizonMs / reportRate * confidence).toInt()
        if (steps < 1) steps = 1
        // Cap absurd horizons to a bounded walk.
        if (steps > 16) steps = 16
        repeat(steps) {
            ax += jx * JANK_INFLUENCE
            ay += jy * JANK_INFLUENCE
            vx += ax * ACCEL_INFLUENCE
            vy += ay * ACCEL_INFLUENCE
            px += vx * VELOCITY_INFLUENCE
            py += vy * VELOCITY_INFLUENCE
            pressure += pressureChange
        }
        if (!px.isFinite() || !py.isFinite() || !pressure.isFinite()) return null
        pressure = pressure.coerceIn(0.0, 1.0)

        // Back to view pixels, clamped to the maximum lead.
        val ox = lastX
        val oy = lastY
        var dx = (px - lastXDp()) * density
        var dy = (py - lastYdp()) * density
        val dist = hypot(dx, dy)
        if (!dist.isFinite() || dist < MIN_LEAD_PX) return null
        var scale = 1.0
        if (dist > maxLeadPx) scale = maxLeadPx / dist
        dx *= scale
        dy *= scale
        return Tip(ox + dx, oy + dy, pressure, confidence.coerceIn(0.0, 1.0))
    }

    /** Drop all history; the next record() starts a fresh stroke. */
    fun reset() {
        xFilter.reset()
        yFilter.reset()
        pFilter.reset()
        iterations = 0
        reportSamples = 0
        reportSumMs = 0.0
        reportRateMs = FALLBACK_REPORT_MS
        lastT = Double.NaN
        started = false
    }

    fun isStarted(): Boolean = started

    private fun lastXDp(): Double = lastX / density
    private fun lastYdp(): Double = lastY / density

    private fun normalize(x: Double, lo: Double, hi: Double): Double {
        val denom = hi - lo
        if (!(denom > 0.0)) return 0.0
        return ((x - lo) / denom).coerceIn(0.0, 1.0)
    }
}
