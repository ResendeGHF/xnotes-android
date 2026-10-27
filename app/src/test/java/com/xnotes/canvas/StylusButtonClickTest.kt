package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A side-button click while the pen hovers, as the held state's two transitions. */
class StylusButtonClickTest {

    private val click = StylusButtonClick()

    @Test
    fun pressedAndLetGoWhileHoveringIsAClickWhereThePenWas() {
        click.hover(100.0, 200.0)
        assertNull(click.button(held = true, time = 1_000))
        click.hover(103.0, 201.0) // a hand is never quite still
        assertEquals(Pt(100.0, 200.0), click.button(held = false, time = 1_150))
    }

    @Test
    fun theStateRepeatedIsStillOneClick() {
        // Every hover event carries the state, so "held" arrives many times over.
        click.hover(10.0, 10.0)
        click.button(true, 0)
        assertNull(click.button(true, 20))
        assertNull(click.button(true, 40))
        assertEquals(Pt(10.0, 10.0), click.button(false, 60))
        assertNull(click.button(false, 80))
    }

    @Test
    fun aHoldIsNotAClick() {
        click.hover(0.0, 0.0)
        click.button(true, 0)
        assertNull(click.button(false, StylusButtonClick.MAX_CLICK_MS + 1))
    }

    @Test
    fun writingWithTheButtonHeldIsNotAClick() {
        click.hover(0.0, 0.0)
        click.button(true, 0)
        click.contact()
        assertNull(click.button(false, 200))
    }

    @Test
    fun aButtonPressedWhileWritingIsNotAClickOnceThePenLifts() {
        // Pressed mid-stroke to erase, then let go just after the pen came up.
        click.hover(0.0, 0.0)
        click.contact(held = false, time = 0)
        click.contact(held = true, time = 50)
        click.hover(0.0, 0.0)
        assertNull(click.button(held = true, time = 120))
        assertNull(click.button(held = false, time = 200))
    }

    @Test
    fun movingAcrossThePageIsNotAClick() {
        // An eraser or a pan during hover.
        click.hover(0.0, 0.0)
        click.button(true, 0)
        click.hover(80.0, 0.0)
        assertNull(click.button(false, 300))
    }

    @Test
    fun aContactBeforeThePressDoesNotSpoilIt() {
        click.contact()
        click.hover(5.0, 5.0)
        click.button(true, 0)
        assertEquals(Pt(5.0, 5.0), click.button(false, 100))
    }

    @Test
    fun theNextClickStartsClean() {
        click.hover(0.0, 0.0)
        click.button(true, 0)
        click.contact()
        assertNull(click.button(false, 100))
        click.button(true, 500)
        assertEquals(Pt(0.0, 0.0), click.button(false, 600))
    }

    @Test
    fun aResetDropsThePressInProgress() {
        click.hover(0.0, 0.0)
        click.button(true, 0)
        click.reset()
        assertNull(click.button(false, 100))
    }
}
