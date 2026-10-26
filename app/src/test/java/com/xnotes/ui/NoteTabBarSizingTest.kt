package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTabBarSizingTest {
    @Test fun dividesAvailableSpaceUntilMinimumWidth() {
        assertEquals(1, visibleTabSlots(800f, 1))
        assertEquals(2, visibleTabSlots(800f, 2))
        assertEquals(3, visibleTabSlots(800f, 3))
        assertEquals(5, visibleTabSlots(800f, 6))
    }

    @Test fun narrowScreenPreservesTapTargetsInsteadOfCompressingTabs() {
        assertEquals(2, visibleTabSlots(360f, 5))
        assertEquals(1, visibleTabSlots(280f, 1))
        assertEquals(1, visibleTabSlots(120f, 4))
    }
}
