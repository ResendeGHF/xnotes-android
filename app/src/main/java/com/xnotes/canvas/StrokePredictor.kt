package com.xnotes.canvas

import com.xnotes.core.geometry.Pt

/**
 * One-frame lookahead for the nib, so the live ink lands where the pen will be
 * at the next present instead of where its last event was.
 *
 * The predicted point is pixels only: it is meshed as an extra tail segment and
 * never enters the stroke's samples, so the next real sample corrects it and a
 * commit can never contain ink the hand did not draw. The flip side is the
 * commit boundary: whatever is still predicted when the pen lifts disappears
 * with it, so the lead shrinks with deceleration and a stale prediction is
 * retracted outright — a stopping pen must converge onto the real nib before
 * the stroke ends, or the tip visibly snaps back on pen-up.
 */
object StrokePredictor {

    /** How far ahead of the last sample to draw, in milliseconds of pen travel. */
    const val LEAD_MS = 8.0

    /** The prediction never reaches further than this on screen, whatever the zoom. */
    const val MAX_LEAD_SCREEN_PX = 12.0

    /** Above this implied speed the samples disagree (a teleport or a glitch): hold still. */
    const val MAX_SPEED_UNITS_PER_MS = 20.0

    /** Below this the pen is effectively stopped: no segment to draw. */
    const val MIN_LEAD_UNITS = 0.5

    /**
     * Extrapolate from the last two samples ([x1],[y1] at [t1] after [x0],[y0]
     * at [t0], milliseconds) up to [LEAD_MS] ahead, clamped to [maxLead]
     * content units. No deceleration history: full lead. Null when there is
     * nothing sensible to draw.
     */
    fun predict(
        x0: Double, y0: Double, t0: Double,
        x1: Double, y1: Double, t1: Double,
        maxLead: Double,
    ): Pt? = predictCore(x0, y0, t0, x1, y1, t1, maxLead, ratio = 1.0)

    /**
     * Three-point form: the lead scales with the deceleration from segment
     * 0→1 to segment 1→2, so a pen that is stopping converges onto its real
     * position instead of overshooting into ink that vanishes on commit.
     */
    fun predict(
        x0: Double, y0: Double, t0: Double,
        x1: Double, y1: Double, t1: Double,
        x2: Double, y2: Double, t2: Double,
        maxLead: Double,
    ): Pt? {
        if (!t0.isFinite() || !t1.isFinite() || t1 <= t0) return null
        val vPrev = kotlin.math.hypot(x1 - x0, y1 - y0) / (t1 - t0)
        if (!vPrev.isFinite()) return null
        // Was stopped, now moving: full lead. Stopping: shrink toward nothing.
        val vLast = lastSpeed(x1, y1, t1, x2, y2, t2) ?: return null
        val ratio = (vLast / vPrev.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        return predictCore(x1, y1, t1, x2, y2, t2, maxLead, ratio)
    }

    private fun lastSpeed(
        x1: Double, y1: Double, t1: Double,
        x2: Double, y2: Double, t2: Double,
    ): Double? {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
        if (!t1.isFinite() || !t2.isFinite()) return null
        val dt = t2 - t1
        if (dt <= 0.0) return null
        val v = kotlin.math.hypot(x2 - x1, y2 - y1) / dt
        if (!v.isFinite() || v <= 0.0 || v > MAX_SPEED_UNITS_PER_MS) return null
        return v
    }

    private fun predictCore(
        x0: Double, y0: Double, t0: Double,
        x1: Double, y1: Double, t1: Double,
        maxLead: Double,
        ratio: Double,
    ): Pt? {
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        if (!t0.isFinite() || !t1.isFinite() || !maxLead.isFinite() || maxLead <= 0.0) return null
        val dt = t1 - t0
        if (dt <= 0.0) return null
        val vx = (x1 - x0) / dt
        val vy = (y1 - y0) / dt
        val speed = kotlin.math.hypot(vx, vy)
        if (!speed.isFinite() || speed <= 0.0 || speed > MAX_SPEED_UNITS_PER_MS) return null
        val dist = speed * LEAD_MS * ratio.coerceIn(0.0, 1.0)
        if (dist < MIN_LEAD_UNITS) return null
        val s = (maxLead / dist).coerceIn(0.0, 1.0)
        if (s <= 0.0) return null
        val lead = LEAD_MS * ratio.coerceIn(0.0, 1.0) * s
        return Pt(x1 + vx * lead, y1 + vy * lead)
    }
}
