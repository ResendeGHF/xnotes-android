package com.xnotes.canvas

import com.xnotes.core.geometry.Pt

/**
 * One-frame lookahead for the nib, so the live ink lands where the pen will be
 * at the next present instead of where its last event was.
 *
 * The predicted point is pixels only: it is meshed as an extra tail segment and
 * never enters the stroke's samples, so the next real sample corrects it and a
 * commit can never contain ink the hand did not draw. The flip side of that
 * contract is the commit boundary: whatever is still shown when the pen lifts
 * vanishes with it. So the lead shrinks with deceleration and with turning —
 * a stopping pen converges onto its real position, and a pen whipping around
 * a corner does not fire a spike off in the old direction — and a prediction
 * left showing with no follow-up samples is retracted outright (see the
 * publishers' hold timers). Either would otherwise paint ink that vanishes
 * on commit.
 */
object StrokePredictor {

    /** How far ahead of the last sample to draw, in milliseconds of pen travel. */
    const val LEAD_MS = 8.0

    /** The prediction never reaches further than this on screen, whatever the zoom. */
    const val MAX_LEAD_SCREEN_PX = 8.0

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
     * Three-point form: the lead shrinks with deceleration and with turning, so a
     * stopping pen converges onto its real position and a pen whipping around a
     * corner does not fire a spike off in the old direction — either would paint
     * ink that vanishes on commit.
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
        // Turning scrubs the lead: only the component still heading where the
        // hand is going is safe to draw ahead.
        val align = alignment(x0, y0, x1, y1, x2, y2)
        val ratio = (vLast / vPrev.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0) * align
        return predictCore(x1, y1, t1, x2, y2, t2, maxLead, ratio)
    }

    /**
     * How much of the last segment still heads the way the previous one did,
     * as clamped cosine similarity: 1 straight on, 0 at a right angle or more.
     */
    private fun alignment(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
    ): Double {
        val ax = x1 - x0
        val ay = y1 - y0
        val bx = x2 - x1
        val by = y2 - y1
        val na = kotlin.math.hypot(ax, ay)
        val nb = kotlin.math.hypot(bx, by)
        if (!na.isFinite() || !nb.isFinite() || na <= 0.0 || nb <= 0.0) return 1.0
        return ((ax * bx + ay * by) / (na * nb)).coerceIn(0.0, 1.0)
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
