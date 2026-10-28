package com.xnotes.settings

import com.xnotes.core.model.Rgba
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsBackupTest {

    private fun tempDir(): File = Files.createTempDirectory("backup").toFile()

    private val mine = Settings(
        browseRoot = "content://tree/mine",
        toolbarColorCount = 3,
        recentDocs = listOf(RecentDoc("content://a", "a", 1L)),
        prefs = Preferences(fingerDraws = true, maxCacheResolution = 4096, codeThemePath = "/data/mine.toml"),
    )

    @Test fun roundTripKeepsSettingsAndAssets() {
        val from = tempDir()
        File(from, "fonts").mkdirs()
        File(from, "fonts/Mine.ttf").writeText("font")
        File(from, "theme").mkdirs()
        File(from, "theme/code.toml").writeText("theme")
        val settings = JSONObject()
            .put("browse_root", "content://old")
            .put("toolbar_color_count", 5)
            .put("prefs", JSONObject().put("markdown_input", true).put("code_theme_path", "/old/theme/code.toml"))
        val zip = ByteArrayOutputStream().also { SettingsBackup.export(settings, from, it) }.toByteArray()

        val to = tempDir()
        val current = JSONObject().put("browse_root", "content://here")
        val adopted = SettingsBackup.import(ByteArrayInputStream(zip), current, to)!!
        assertEquals(5, adopted.getInt("toolbar_color_count"))
        assertEquals("content://here", adopted.getString("browse_root"))
        assertTrue(adopted.getJSONObject("prefs").getBoolean("markdown_input"))
        assertEquals(File(to, "theme/code.toml").path, adopted.getJSONObject("prefs").getString("code_theme_path"))
        assertEquals("font", File(to, "fonts/Mine.ttf").readText())
    }

    @Test fun rejectsAZipWithoutSettings() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("fonts/a.ttf")); it.write(1); it.closeEntry() }
        }.toByteArray()
        val to = tempDir()
        assertNull(SettingsBackup.import(ByteArrayInputStream(bytes), JSONObject(), to))
        assertFalse(File(to, "fonts/a.ttf").exists())
    }

    @Test fun ignoresPathsThatClimbOut() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("settings.json")); it.write("{}".toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("fonts/../../evil")); it.write(1); it.closeEntry()
            }
        }.toByteArray()
        val to = tempDir()
        SettingsBackup.import(ByteArrayInputStream(bytes), JSONObject(), to)
        assertFalse(File(to.parentFile, "evil").exists())
    }

    @Test fun anExportLeavesThisDeviceBehind() {
        val out = SettingsBackup.portable(mine.toJson())
        assertFalse(out.has("browse_root"))
        assertFalse(out.has("recent_docs"))
        assertEquals(3, out.getInt("toolbar_color_count"))
        val prefs = out.getJSONObject("prefs")
        assertTrue(prefs.getBoolean("finger_draws"))
        assertFalse(prefs.has("max_cache_resolution"))
        assertFalse(prefs.has("code_theme_path"))
    }

    @Test fun anImportTakesWhatItCarriesAndKeepsTheRest() {
        val theirs = Settings(
            browseRoot = "content://tree/theirs",
            toolbarColorCount = 9,
            prefs = Preferences(fingerDraws = false, maxCacheResolution = 1024, pageColor = Rgba(1, 2, 3, 255)),
        )
        val incoming = SettingsBackup.portable(theirs.toJson())
        incoming.remove("toolbar_layout")
        incoming.put("from_a_newer_version", true)
        val next = Settings.fromJson(SettingsBackup.merged(mine.toJson(), incoming))
        assertEquals(9, next.toolbarColorCount)
        assertFalse(next.prefs.fingerDraws)
        assertEquals(Rgba(1, 2, 3, 255), next.prefs.pageColor)
        // This device's own folder, recents, cache size and code theme stay.
        assertEquals("content://tree/mine", next.browseRoot)
        assertEquals(1, next.recentDocs.size)
        assertEquals(4096, next.prefs.maxCacheResolution)
        assertEquals("/data/mine.toml", next.prefs.codeThemePath)
        assertEquals(mine.toolbarLayout, next.toolbarLayout)
    }

    @Test fun aBackupWithoutPreferencesKeepsThem() {
        val next = Settings.fromJson(SettingsBackup.merged(mine.toJson(), JSONObject().put("toolbar_color_count", 4)))
        assertEquals(4, next.toolbarColorCount)
        assertTrue(next.prefs.fingerDraws)
        assertNull(next.newNoteFlow)
    }
}
