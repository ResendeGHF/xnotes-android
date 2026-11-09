package com.xnotes.ui

import com.xnotes.platform.JsonStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FolderListingsTest {

    private fun store(file: File): JsonStore = JsonStore(file)

    private fun row(name: String, id: String, dir: Boolean = false) =
        FolderListings.Row(name, id, dir, size = 10L, modified = 100L, created = 90L, colorHex = null)

    @Test fun roundTripsFolders() {
        val dir = Files.createTempDirectory("xnotes-listings").toFile()
        val s = FolderListings(store(File(dir, "folder_listings.json")))
        assertNull(s.get("auth|root"))
        s.note("auth|root", listOf(row("a.xnote", "root/a.xnote"), row("sub", "root/sub", dir = true)))
        val back = s.get("auth|root")!!
        assertEquals(2, back.size)
        assertEquals("a.xnote", back[0].name)
        assertEquals("root/a.xnote", back[0].childDocId)
        assertEquals(90L, back[0].created)
    }

    @Test fun dropParentAndMatching() {
        val dir = Files.createTempDirectory("xnotes-listings").toFile()
        val s = FolderListings(store(File(dir, "folder_listings.json")))
        s.note("auth|root", listOf(row("a.xnote", "root/a.xnote")))
        s.note("auth|root/sub", listOf(row("b.xnote", "root/sub/b.xnote")))
        // A deleted folder drops its own listing and its rows in the parent.
        s.dropMatching { com.xnotes.core.util.DocKeys.within(it, "auth|root/sub") }
        assertNull(s.get("auth|root/sub"))
        s.dropParent("auth|root")
        assertNull(s.get("auth|root"))
        // Clearing drops everything.
        s.note("auth|root", listOf(row("a.xnote", "root/a.xnote")))
        s.clear()
        assertNull(s.get("auth|root"))
    }

    @Test fun roundTripsDisk() {
        val dir = Files.createTempDirectory("xnotes-listings").toFile()
        val file = File(dir, "folder_listings.json")
        FolderListings(store(file)).note(
            "auth|root",
            listOf(FolderListings.Row("a.xnote", "root/a.xnote", false, 11L, 101L, 91L, "#ff0000")),
        )
        Thread.sleep(2200) // let the batched writer flush
        val back = FolderListings(store(file)).get("auth|root")!!
        assertEquals(1, back.size)
        assertEquals("#ff0000", back[0].colorHex)
        assertEquals(11L, back[0].size)
    }
}
