package com.xnotes.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTabsTest {
    @Test fun switchingKeepsBackgroundTabsWithoutDuplicatingThem() {
        val workspace = NoteTabs().open("a", "Math").open("b", "Physics").open("a", "Math")
        assertEquals(listOf("a", "b"), workspace.tabs.map { it.uri })
        assertEquals("a", workspace.primaryUri)
        assertEquals("b", workspace.hide("a").tabs.last().uri)
        assertNull(workspace.hide("a").primaryUri)
    }

    @Test fun splitMovesTabInsteadOfShowingSameDocumentTwice() {
        val workspace = NoteTabs().open("a", "Math").open("b", "Physics", secondary = true)
            .open("a", "Math", secondary = true)
        assertNull(workspace.primaryUri)
        assertEquals("a", workspace.secondaryUri)
    }

    @Test fun closeKeepsOtherPaneAndSelectsReplacementWhenPossible() {
        val workspace = NoteTabs().open("a", "A").open("b", "B")
            .open("c", "C", secondary = true)
        val closed = workspace.close("b")
        assertEquals("a", closed.primaryUri)
        assertEquals("c", closed.secondaryUri)
        assertEquals(listOf("a", "c"), closed.tabs.map { it.uri })
    }

    @Test fun reorderDoesNotChangeSelectedDocuments() {
        val workspace = NoteTabs().open("a", "A").open("b", "B").move(0, 1)
        assertEquals(listOf("b", "a"), workspace.tabs.map { it.uri })
        assertEquals("b", workspace.primaryUri)
    }
}
