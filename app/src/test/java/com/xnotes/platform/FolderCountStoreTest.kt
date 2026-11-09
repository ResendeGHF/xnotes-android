package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FolderCountStoreTest {

    private fun store(file: File): JsonStore = JsonStore(file)

    @Test fun hitsByIdentityAndMtime() {
        val dir = Files.createTempDirectory("xnotes-counts").toFile()
        val s = FolderCountStore(store(File(dir, "folder_counts.json")))
        assertNull(s.get("auth|root/f", 100L))
        s.put("auth|root/f", 100L, 7)
        assertEquals(7, s.get("auth|root/f", 100L))
        // A changed folder recounts.
        assertNull(s.get("auth|root/f", 101L))
    }

    @Test fun removeMatchingClearsSubtrees() {
        val dir = Files.createTempDirectory("xnotes-counts").toFile()
        val s = FolderCountStore(store(File(dir, "folder_counts.json")))
        s.put("auth|root/f", 1L, 3)
        s.put("auth|root/f/sub", 1L, 2)
        s.put("auth|root/other", 1L, 9)
        s.removeMatching { com.xnotes.core.util.DocKeys.within(it, "auth|root/f") }
        assertNull(s.get("auth|root/f", 1L))
        assertNull(s.get("auth|root/f/sub", 1L))
        assertEquals(9, s.get("auth|root/other", 1L))
    }

    @Test fun roundTripsDisk() {
        val dir = Files.createTempDirectory("xnotes-counts").toFile()
        val file = File(dir, "folder_counts.json")
        FolderCountStore(store(file)).put("auth|root/f", 42L, 5)
        Thread.sleep(2200) // let the batched writer flush
        val reloaded = FolderCountStore(store(file))
        assertEquals(5, reloaded.get("auth|root/f", 42L))
        assertNull(reloaded.get("auth|root/f", 43L))
    }
}
