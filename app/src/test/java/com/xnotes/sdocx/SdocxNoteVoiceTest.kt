package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.pal.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Recorded-while-writing audio (note.note bit 13) plus its stroke replay.
 * Samsung stores these sessions at note level (not as type-10 page voices);
 * the importer must surface one pill per session and tag in-interval strokes
 * with SyncMarks so ink replays with the audio. Images must survive alongside.
 */
class SdocxNoteVoiceTest {

    private val fakeImages = object : ImageCodec {
        override fun probeFile(path: String): ImageSize? = ImageSize(100, 100)
    }
    private val fakeMeasurer = object : TextMeasurer {
        override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect =
            Rect(0.0, 0.0, wrapWidth, 20.0)
        override fun lineHeight(font: FontSpec): Double = 20.0
        override fun metrics(font: FontSpec): LineMetrics = LineMetrics(16.0, 4.0)
        override fun advances(text: String, font: FontSpec): DoubleArray = DoubleArray(text.length) { 10.0 }
    }

    private fun loadRecordedEntries(): Map<String, ByteArray>? {
        // Preferred: test resource (moved from project root).
        try {
            val stream = javaClass.getResourceAsStream("/sdocx/note-recorded.sdocx")
            if (stream != null) {
                stream.use { ins ->
                    val bytes = ins.readBytes()
                    val out = HashMap<String, ByteArray>()
                    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            if (!e.isDirectory) {
                                try { out[e.name] = zis.readBytes() } catch (_: Exception) { }
                            }
                            try { zis.closeEntry() } catch (_: Exception) { }
                            e = try { zis.nextEntry } catch (_: Exception) { break }
                        }
                    }
                    if (out.isNotEmpty()) return out
                }
            }
        } catch (_: Exception) { }
        // Fallbacks for local runs.
        for (path in listOf("/tmp/opencode/note.sdocx", "app/src/test/resources/sdocx/note-recorded.sdocx")) {
            try {
                val f = File(path)
                if (!f.exists()) continue
                val out = HashMap<String, ByteArray>()
                java.util.zip.ZipFile(f).use { zf ->
                    val e = zf.entries()
                    while (e.hasMoreElements()) {
                        val en = e.nextElement()
                        if (!en.isDirectory) {
                            try { out[en.name] = zf.getInputStream(en).readBytes() } catch (_: Exception) { }
                        }
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) { }
        }
        return null
    }

    private fun createTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "x")
        f.delete(); f.mkdirs()
        return f
    }

    @Test fun recordedVoicesParseWithIntervals() {
        val entries = loadRecordedEntries() ?: return
        val noteBytes = entries["note.note"] ?: return
        val voices = SdocxParser.parseNoteVoices(noteBytes)
        assertEquals("expected 2 recorded sessions, got $voices", 2, voices.size)
        val byId = voices.associateBy { it.mediaId }
        assertTrue(byId.containsKey(3))
        assertTrue(byId.containsKey(5))
        val v1 = byId[3]!!
        val v2 = byId[5]!!
        assertEquals("Voice 001", v1.title)
        assertEquals("Voice 002", v2.title)
        assertTrue("v1 dur ${v1.durationMs}", v1.durationMs in 6000..8000)
        assertTrue("v2 dur ${v2.durationMs}", v2.durationMs in 30000..36000)
        assertNotNull(v1.startUs); assertNotNull(v1.endUs)
        assertNotNull(v2.startUs); assertNotNull(v2.endUs)
        assertTrue(v1.startUs!! < v1.endUs!!)
        assertTrue(v2.startUs!! < v2.endUs!!)
        // ~7.3s and ~33.3s intervals in microseconds.
        assertTrue((v1.endUs!! - v1.startUs!!) in 6_000_000L..9_000_000L)
        assertTrue((v2.endUs!! - v2.startUs!!) in 30_000_000L..36_000_000L)
    }

    @Test fun strokeTimestampsParsed() {
        val entries = loadRecordedEntries() ?: return
        val parsed = SdocxParser.parseArchive(entries)
        assertTrue(parsed.pages.isNotEmpty())
        val withTs = parsed.pages.flatMap { it.strokes }.count { it.createdTimeUs != null }
        assertTrue("expected stroke timestamps, got $withTs/${parsed.pages.sumOf { it.strokes.size }}", withTs > 50)
    }

    @Test fun recordedAudioAndImagesImportWithSync() {
        val entries = loadRecordedEntries() ?: return
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(2, parsed.noteVoices.size)
        val tmp = createTempDir("sdocx-notevoice")
        try {
            val doc = SdocxImport.toDocument(
                parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() },
                fakeImages, fakeMeasurer,
            )!!
            // Both recordings surface as pills with their own bytes.
            val audios = doc.pages.flatMap { it.items }.filterIsInstance<AudioItem>()
            assertEquals("expected 2 audio pills, got ${audios.size}", 2, audios.size)
            val byName = audios.associateBy { it.name }
            assertTrue(byName.containsKey("Voice 001"))
            assertTrue(byName.containsKey("Voice 002"))
            assertTrue(byName["Voice 001"]!!.audio.file.exists())
            assertTrue(byName["Voice 002"]!!.audio.file.exists())
            assertTrue(byName["Voice 001"]!!.audio.durationMs in 6000..8000)
            assertTrue(byName["Voice 002"]!!.audio.durationMs in 30000..36000)
            assertTrue(byName["Voice 001"]!!.rid != byName["Voice 002"]!!.rid)
            // Images survive alongside the recordings (orphan PNGs via sweep).
            val images = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>()
            assertTrue("expected images alongside audio, got ${images.size}", images.size >= 2)
            for (img in images) assertTrue(img.image.file.exists())
            // Strokes written during Voice 002 replay in sync with it.
            val v2 = parsed.noteVoices.first { it.mediaId == 5 }
            val v2rid = audios.first { it.name == "Voice 002" }.rid
            val synced = doc.pages.flatMap { it.items }
                .filter { it.sync?.rid == v2rid }
            assertTrue("expected synced strokes for Voice 002, got ${synced.size}", synced.size >= 50)
            for (s in synced) {
                val ms = s.sync!!.ms
                assertTrue("sync ms $ms in 0..${v2.durationMs}", ms in 0..v2.durationMs)
            }
            // Pills sit at page centers, not at (0,0) covering ink.
            for (a in audios) {
                val host = doc.pages.firstOrNull { it.items.contains(a) }!!
                assertTrue(a.rect.x in 0.0..host.width)
                assertTrue(a.rect.y in 0.0..host.height)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun emptyNoteVoicesWhenAbsent() {
        assertTrue(SdocxParser.parseNoteVoices(ByteArray(0)).isEmpty())
        // Minimal note.note without bit 13 must not throw.
        val w = SdocxWriter()
        w.writeU32(0)
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeU32(4000)
        w.writeUtf16U16("")
        w.writeBytes(ByteArray(4 + 8 + 8 + 4 + 4 + 4 + 4 + 4))
        w.writeU32(0); w.writeU32(0)
        assertTrue(SdocxParser.parseNoteVoices(w.bytes()).isEmpty())
    }

    // --- export round-trip (our app -> Samsung Notes -> our app) ---

    private fun tinyPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00.toByte(), 0x90.toByte(), 0x77, 0x53,
        0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
        0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x3F,
        0x00, 0x05, 0xFE.toByte(), 0x02, 0xFE.toByte(), 0xDC.toByte(), 0xCC.toByte(), 0x59,
        0xE7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    private fun fakeM4a(): ByteArray {
        val w = SdocxWriter()
        w.writeU32(24)
        w.writeBytes(byteArrayOf(0x66, 0x74, 0x79, 0x70))
        w.writeBytes(byteArrayOf(0x4D, 0x34, 0x41, 0x20))
        w.writeBytes(ByteArray(12))
        return w.bytes()
    }

    private fun penStroke(x0: Double, y0: Double, x1: Double, y1: Double): Stroke {
        val s = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        s.setSamples(listOf(Sample(x0, y0, 0.8), Sample(x1, y1, 0.8)))
        s.finished = true
        return s
    }

    @Test fun exportRoundTripKeepsImagesOnPagesAndBothAudios() {
        val tmp = createTempDir("sdocx-export-rt")
        try {
            val png1 = tinyPng()
            val png2 = tinyPng().also { it[30] = 0x01 }
            val m4aStandalone = fakeM4a()
            val m4aRecorded = fakeM4a().also { it[20] = 0x02 }
            val fImg1 = File(tmp, "i1.png").apply { writeBytes(png1) }
            val fImg2 = File(tmp, "i2.png").apply { writeBytes(png2) }
            val fAudStand = File(tmp, "a1.m4a").apply { writeBytes(m4aStandalone) }
            val fAudRec = File(tmp, "a2.m4a").apply { writeBytes(m4aRecorded) }

            val doc = Document(dpi = 150)
            val page0 = Page(1440.0, 2036.0)
            page0.items.add(ImageItem(ImageData(fImg1, 1, 1), Rect(64.0, 100.0, 400.0, 300.0)))
            page0.items.add(penStroke(100.0, 100.0, 200.0, 200.0))
            page0.items.add(
                AudioItem(AudioData(fAudStand, "m4a", 5000, ByteArray(0)), AudioItem.boxAt(Pt(720.0, 300.0), 150), "Attached", 10, fakeMeasurer),
            )
            val page1 = Page(1440.0, 2036.0)
            page1.items.add(ImageItem(ImageData(fImg2, 1, 1), Rect(64.0, 500.0, 400.0, 300.0)))
            val recRid = 11
            val s1 = penStroke(100.0, 400.0, 300.0, 500.0).also { it.sync = SyncMark(recRid, 0) }
            val s2 = penStroke(320.0, 500.0, 500.0, 600.0).also { it.sync = SyncMark(recRid, 1500) }
            page1.items.add(s1)
            page1.items.add(s2)
            page1.items.add(
                AudioItem(AudioData(fAudRec, "m4a", 4000, ByteArray(0)), AudioItem.boxAt(Pt(720.0, 1500.0), 150), "Lecture", recRid, fakeMeasurer),
            )
            doc.pages.add(page0)
            doc.pages.add(page1)

            // Export like Samsung Notes would receive it.
            val outBytes = ByteArrayOutputStream().also { SdocxExport.writeDocument(doc, it) }.toByteArray()
            assertTrue(outBytes.size > 1000)
            val entries = HashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(outBytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) entries[e.name] = zis.readBytes()
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            // Samsung-observable artefacts exist.
            assertTrue(entries.keys.any { it.endsWith(".page") })
            assertTrue(entries.containsKey("media/mediaInfo.dat"))
            assertTrue(entries.containsKey("note.note"))
            val audioMedia = entries.keys.filter { it.startsWith("media/") && (it.endsWith(".m4a") || it.endsWith(".3gp")) }
            assertEquals("exported audio assets, got $audioMedia", 2, audioMedia.size)

            // Our own parser reads it back: recorded session + page voice.
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.noteVoices.size)
            assertEquals("Lecture", parsed.noteVoices[0].title)
            assertTrue(parsed.noteVoices[0].durationMs in 3500..4500)
            val pageVoices = parsed.pages.sumOf { it.voices.size }
            assertEquals("standalone page voice, got $pageVoices", 1, pageVoices)

            // Full re-import: images stay on their pages, both audios surface, sync kept.
            val back = SdocxImport.toDocument(
                parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() },
                fakeImages, fakeMeasurer,
            )!!
            assertEquals(2, back.pages.size)
            val backImages0 = back.pages[0].items.filterIsInstance<ImageItem>()
            val backImages1 = back.pages[1].items.filterIsInstance<ImageItem>()
            assertEquals("page 0 keeps its photo, got ${backImages0.size}", 1, backImages0.size)
            assertEquals("page 1 keeps its photo, got ${backImages1.size}", 1, backImages1.size)
            assertTrue(backImages0[0].image.file.readBytes().contentEquals(png1))
            assertTrue(backImages1[0].image.file.readBytes().contentEquals(png2))
            val backAudios = back.pages.flatMap { it.items }.filterIsInstance<AudioItem>()
            assertEquals(2, backAudios.size)
            val rec = backAudios.first { it.name == "Lecture" }
            assertTrue(rec.audio.durationMs in 3500..4500)
            val synced = back.pages.flatMap { it.items }.filter { it.sync?.rid == rec.rid }
            assertTrue("synced strokes survive export, got ${synced.size}", synced.size >= 2)
            val ms = synced.map { it.sync!!.ms }.sorted()
            assertEquals(listOf(0, 1500), ms)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
