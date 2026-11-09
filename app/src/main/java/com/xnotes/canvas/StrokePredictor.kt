package com.xnotes.canvas

import com.xnotes.core.geometry.Pt

/**
 * One-frame lookahead for the nib, so the live ink lands where the pen will be
 * at the next present instead of where its last event was.
 *
 * The predicted point is pixels only: it is meshed as an extra tail segment and
 * never enters the stroke's samples, so a wrong guess is corrected by the next
 * real sample and can never corrupt the committed ink. Fixed lead (about half a
 * 120 Hz frame) keeps it independent of event timestamps and clock domains.
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
     * Extrapolate from the last two samples ([x0],[y0] at [t0] to [x1],[y1] at
     * [t1], milliseconds) up to [LEAD_MS] ahead, clamped to [maxLead] content
     * units. Null when there is nothing sensible to draw.
     */
    fun predict(
        x0: Double, y0: Double, t0: Double,
        x1: Double, y1: Double, t1: Double,
        maxLead: Double,
    ): Pt? {
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        if (!t0.isFinite() || !t1.isFinite()) return null
        val dt = t1 - t0
        if (dt <= 0.0 || !maxLead.isFinite() || maxLead <= 0.0) return null
        val vx = (x1 - x0) / dt
        val vy = (y1 - y0) / dt
        val speed = kotlin.math.hypot(vx, vy)
        if (!speed.isFinite() || speed <= 0.0 || speed > MAX_SPEED_UNITS_PER_MS) return null
        val dist = speed * LEAD_MS
        if (dist < MIN_LEAD_UNITS) return null
        val s = (maxLead / dist).coerceIn(0.0, 1.0)
        if (s <= 0.0) return null
        val lead = LEAD_MS * s
        return Pt(x1 + vx * lead, y1 + vy * lead)
    }
}
