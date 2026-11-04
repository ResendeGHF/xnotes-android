package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicySortTest {
    private fun row(uri: String, name: String, isDir: Boolean = false) =
        ShareServerEntry(uri, name, 10L, 0L, if (isDir) "folder" else "note", isDir)

    @Test fun sharedFirstThenFoldersThenName() {
        val rows = listOf(
            row("u3", "zebra.xnote"),
            row("u1", "apple.xnote"),
            row("u2", " Mango"),
            row("d9", "docs", isDir = true),
            row("d1", "archive", isDir = true),
        )
        val sorted = rows.sortedWith(sharedFirst(setOf("u2", "d9")))
        assertEquals(listOf("d9", "u2", "d1", "u1", "u3"), sorted.map { it.uri })
    }

    @Test fun emptySharedKeepsFoldersFirst() {
        val rows = listOf(row("u2", "b.xnote"), row("d1", "a"), row("u1", "a.xnote"))
        // folders ("a" dir) sort before files when nothing is shared
        val sorted = rows.sortedWith(sharedFirst(emptySet()))
        assertEquals(listOf("d1", "u1", "u2"), sorted.map { it.uri })
    }

    @Test fun orderDoesNotDependOnToggleTime() {
        // Toggles flip checkboxes in place; the order snapshot is fixed per
        // picker/load, so assert the comparator is a pure function of its input.
        val rows = listOf(row("u1", "a.xnote"), row("u2", "b.xnote"))
        val a = rows.sortedWith(sharedFirst(setOf("u1"))).map { it.uri }
        val b = rows.sortedWith(sharedFirst(setOf("u1", "u2"))).map { it.uri }
        assertEquals(listOf("u1", "u2"), a)
        assertEquals(listOf("u1", "u2"), b)
    }
}
