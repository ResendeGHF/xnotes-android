package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt

/**
 * The view half of the side-button click, shared by the paged and the infinite canvas views: it
 * feeds [StylusButtonClick] from the view's own event streams, and holds back the touch that closes
 * the pen menu so it leaves no ink.
 *
 * The interaction layer keeps its own [StylusButtonLatch] for the held button; this one only
 * watches for clicks, so neither has to know about the other.
 */
class SideButtonClicks {
    /** A click with the pen over the page, at that viewport point. */
    var onClick: ((Pt) -> Unit)? = null

    /**
     * A touch is starting on the page. True when a menu that leaves the page live took it (the pen
     * menu, which it closes): the whole touch is then the menu's, so it draws nothing.
     */
    var touchClosesMenu: (() -> Boolean)? = null

    private val latch = StylusButtonLatch()
    private val click = StylusButtonClick()

    /** The touch under way went to a menu (see [touchClosesMenu]). */
    private var touchForMenu = false

    /** Every touch event, before the view handles it. True when the view should drop it. */
    fun onTouch(e: MotionEvent): Boolean {
        click.contact(held = latch.heldFor(e), time = e.eventTime)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) touchForMenu = touchClosesMenu?.invoke() == true
        if (!touchForMenu) return false
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) touchForMenu = false
        return true
    }

    /** A hover event: where the pen is, and the button's state as the hover stream reports it. */
    fun onHover(e: MotionEvent) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        click.hover(e.x.toDouble(), e.y.toDouble())
        // The pen leaving the glass says nothing of the button: it may be on its way down to write.
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) return
        click.button(latch.heldFor(e), e.eventTime)?.let { onClick?.invoke(it) }
    }

    /** A generic-motion event, where some pens put the button's press and release. */
    fun onGenericMotion(e: MotionEvent) {
        latch.onGenericMotion(e)
        onHover(e)
    }

    /** A side-button key went down or up (Bluetooth and USI pens), at [time] on the event clock. */
    fun onKey(time: Long) {
        click.button(latch.held, time)?.let { onClick?.invoke(it) }
    }
}
