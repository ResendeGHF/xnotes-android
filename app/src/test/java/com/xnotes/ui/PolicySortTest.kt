package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicySortTest {
    private fun row(uri: String, name: String) =
        ShareServerEntry(uri, name, 10L, 0L, "note")

    @Test fun sharedFirstThenByName() {
        val rows = listOf(row("u3", "Zebra.xnote"), row("u1", "apple.xnote"), row("u2", "Mango.xnote"))
        val sorted = rows.sortedWith(sharedFirst(setOf("u2", "u3")))
        assertEquals(listOf("u2", "u3", "u1"), sorted.map { it.uri })
    }

    @Test fun emptySharedKeepsNameOrder() {
        val rows = listOf(row("u2", "b.xnote"), row("u1", "a.xnote"))
        val sorted = rows.sortedWith(sharedFirst(emptySet()))
        assertEquals(listOf("u1", "u2"), sorted.map { it.uri })
    }

    @Test fun unsharedStaysPutAfterToggle() {
        // Order is fixed at load: toggling must not move rows under the finger.
        val rows = listOf(row("u1", "a.xnote"), row("u2", "b.xnote"))
        val before = rows.sortedWith(sharedFirst(setOf("u1"))).map { it.uri }
        assertEquals(listOf("u1", "u2"), before)
        val after = rows.sortedWith(sharedFirst(setOf("u1", "u2"))).map { it.uri }
        assertEquals(listOf("u1", "u2"), after)
    }
}
