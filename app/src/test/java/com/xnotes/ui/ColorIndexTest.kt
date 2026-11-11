package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.platform.JsonStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ColorIndexTest {

    private fun store(file: File): JsonStore = JsonStore(file)

    private fun entry(name: String, id: String, parent: String, color: Rgba?): BrowseEntry =
        BrowseEntry(
            name = name,
            documentUri = "content://auth/tree/$id",
            isDir = false,
            parentDocId = parent,
            color = color,
        )

    private fun key(id: String) = "auth|$id"

    @Test fun learnsAndDropsRows() {
        val dir = Files.createTempDirectory("xnotes-color").toFile()
        val idx = ColorIndex(store(File(dir, "color_index.json")))
        val red = Rgba(255, 0, 0)
        idx.noteFolder("auth", "root", listOf(entry("a.pnote", "root/a.pnote", "root", red)), ::keyOf, null)
        assertEquals(setOf("root"), idx.parentsWith("auth", Rgba.toHex(red)))
        // Uncoloured re-list drops the row.
        idx.noteFolder("auth", "root", listOf(entry("a.pnote", "root/a.pnote", "root", null)), ::keyOf, null)
        assertTrue(idx.parentsWith("auth", Rgba.toHex(red)).isEmpty())
    }

    @Test fun carriesRowsAcrossRename() {
        val dir = Files.createTempDirectory("xnotes-color").toFile()
        val idx = ColorIndex(store(File(dir, "color_index.json")))
        val red = Rgba(0, 255, 0)
        idx.noteFolder("auth", "root", listOf(entry("a.pnote", "root/a.pnote", "root", red)), ::keyOf, null)
        idx.moved(key("root/a.pnote"), key("root/b.pnote"), "root")
        assertEquals(setOf("root"), idx.parentsWith("auth", Rgba.toHex(red)))
    }

    @Test fun dropsDeletedSubtree() {
        val dir = Files.createTempDirectory("xnotes-color").toFile()
        val idx = ColorIndex(store(File(dir, "color_index.json")))
        val red = Rgba(0, 0, 255)
        idx.noteFolder(
            "auth", "root/f",
            listOf(entry("a.pnote", "root/f/a.pnote", "root/f", red)),
            ::keyOf, null,
        )
        idx.removeMatching { com.xnotes.core.util.DocKeys.within(it, key("root/f")) }
        assertTrue(idx.parentsWith("auth", Rgba.toHex(red)).isEmpty())
    }

    @Test fun roundTripsDiskWithoutVaultRows() {
        val dir = Files.createTempDirectory("xnotes-color").toFile()
        val file = File(dir, "color_index.json")
        val red = Rgba(10, 20, 30)
        val idx = ColorIndex(store(file))
        idx.noteFolder("auth", "root", listOf(entry("a.pnote", "root/a.pnote", "root", red)), ::keyOf, null)
        idx.noteFolder("auth", "root/v", listOf(entry("s.pnote", "root/v/s.pnote", "root/v", red)), ::keyOf, "root/v")
        Thread.sleep(2200) // let the batched writer flush
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("root/a.pnote"))
        assertFalse(text.contains("root/v/s.pnote"))
        val reloaded = ColorIndex(store(file))
        assertEquals(setOf("root"), reloaded.parentsWith("auth", Rgba.toHex(red)))
    }

    private fun keyOf(e: BrowseEntry): String = key(e.documentUri.substringAfter("/tree/"))
}
