package com.xnotes.canvas

import com.xnotes.core.geometry.Pt

/**
 * A click of the stylus side button with the pen over the screen: pressed and let go again, quickly,
 * without the pen touching down or moving across the page in between.
 *
 * The S Pen and most other pens report the side button only as a held state (in the hover stream's
 * `buttonState`, or as a key that [StylusButtonLatch] latches), so on its own the app sees a button
 * being held, never a click. This turns the held state's two transitions into one. A press the pen
 * writes through (the button held while drawing) or carries across the page (an eraser or pan
 * during hover) is not a click.
 *
 * Pure: the caller feeds it the latched button state, where the pen hovers, and any contact.
 */
class StylusButtonClick(
    private val maxMillis: Long = MAX_CLICK_MS,
    private val slop: Double = InteractionController.TAP_SLOP,
) {
    private var downAt = -1L
    private var at: Pt? = null
    private var last: Pt? = null
    private var spoiled = false

    /** The pen is over the screen at ([x], [y]), in viewport px. */
    fun hover(x: Double, y: Double) {
        val p = Pt(x, y)
        last = p
        val from = at
        if (downAt >= 0 && from != null && p.distanceTo(from) > slop) spoiled = true
    }

    /**
     * Something is on the screen (the pen, or a finger), with the button [held] or not at [time]:
     * whatever the button does now is part of that gesture, not a click, even once the pen lifts.
     */
    fun contact(held: Boolean = false, time: Long = 0) {
        if (held && downAt < 0) {
            downAt = time
            at = last
        }
        if (downAt >= 0) spoiled = true
    }

    /**
     * The button's latched state at [time] (ms, the event clock). Returns where the pen was when the
     * button went down if this lets go of a click, else null.
     */
    fun button(held: Boolean, time: Long): Pt? {
        if (held && downAt < 0) {
            downAt = time
            at = last
            spoiled = false
            return null
        }
        if (!held && downAt >= 0) {
            val click = !spoiled && time - downAt <= maxMillis
            downAt = -1
            return if (click) at ?: last else null
        }
        return null
    }

    /** Forget a press in progress (the pen left, the note changed). */
    fun reset() {
        downAt = -1
        at = null
        spoiled = false
    }

    companion object {
        /** Longer than this, a press is a hold, not a click. */
        const val MAX_CLICK_MS = 800L
    }
}
