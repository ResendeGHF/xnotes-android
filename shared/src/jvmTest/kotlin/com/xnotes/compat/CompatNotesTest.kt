package com.xnotes.compat

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.CanvasItem
import com.xnotes.format.DocumentCodec
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation compatibility gate: reference notes authored by the
 * Android app must open in the shared core without loss. Fixtures live in
 * resources/compat; add one per must-support feature. If a fixture fails to
 * load here, the Linux port cannot share that note — fix the codec first.
 */
class CompatNotesTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun fixture(name: String): ByteArray {
        val url = requireNotNull(javaClass.getResource("/compat/$name")) { "missing fixture $name" }
        return url.readBytes()
    }

    private fun inventory(items: List<CanvasItem>): Map<String, Int> =
        items.groupingBy { it.kind }.eachCount()

    private fun checkRoundTrip(name: String): Map<String, Int> {
        val tmpImg = Files_createTempDir("compat-img")
        val tmpPdf = File.createTempFile("compat-src", ".pdf").apply { deleteOnExit() }
        val doc = fixture(name).inputStream().use { codec.read(it, tmpPdf.parentFile, tmpImg) }
        assertTrue("$name: no pages", doc.pages.isNotEmpty())
        val kinds = HashMap<String, Int>()
        var strokes = 0
        var samples = 0
        for (p in doc.pages) {
            for ((k, n) in inventory(p.items)) kinds[k] = (kinds[k] ?: 0) + n
            for (s in p.items.filterIsInstance<com.xnotes.core.model.Stroke>()) {
                strokes++
                samples += s.sampleCount
            }
        }
        println("$name: ${doc.pages.size} pages, kinds=$kinds, strokes=$strokes samples=$samples title=${doc.title}")
        // The model must survive a write+read round trip with identical counts.
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertTrue("$name: empty rewrite", out.size() > 0)
        val tmpImg2 = Files_createTempDir("compat-img2")
        val doc2 = out.toByteArray().inputStream().use { codec.read(it, tmpPdf.parentFile, tmpImg2) }
        assertEquals("$name: page count changed", doc.pages.size, doc2.pages.size)
        assertEquals(
            "$name: item count changed",
            doc.pages.sumOf { it.items.size },
            doc2.pages.sumOf { it.items.size },
        )
        return kinds
    }

    private fun Files_createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun androidNoteOpensWithInkAndText() {
        val kinds = checkRoundTrip("example-note-created-by-note.xnote")
        assertTrue("expected strokes", (kinds["stroke"] ?: 0) > 0)
    }

    @Test
    fun stickyNoteSurvivesRoundTrip() {
        val tmpImg = Files_createTempDir("compat-sticky")
        val tmpPdf = File.createTempFile("compat-src", ".pdf").apply { deleteOnExit() }
        val doc = fixture("example-note-created-by-note.xnote").inputStream()
            .use { codec.read(it, tmpPdf.parentFile, tmpImg) }
        val stickies = doc.pages.flatMap { it.items }.filterIsInstance<com.xnotes.core.model.StickyNoteItem>()
        assertEquals("expected the example note's sticky", 1, stickies.size)
        val sticky = stickies[0]
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        val doc2 = out.toByteArray().inputStream()
            .use { codec.read(it, tmpPdf.parentFile, Files_createTempDir("compat-sticky2")) }
        val stickies2 = doc2.pages.flatMap { it.items }.filterIsInstance<com.xnotes.core.model.StickyNoteItem>()
        assertEquals(1, stickies2.size)
        assertEquals(sticky.rect, stickies2[0].rect)
        assertEquals(sticky.color, stickies2[0].color)
        assertEquals(sticky.collapsed, stickies2[0].collapsed)
        assertEquals(sticky.text, stickies2[0].text)
        assertEquals(sticky.ink.size, stickies2[0].ink.size)
    }

    @Test
    fun mathStudioWorkspaceSurvivesRoundTrip() {
        val tmpPdf = File.createTempFile("compat-src", ".pdf").apply { deleteOnExit() }
        val doc = fixture("example-note-created-by-note.xnote").inputStream()
            .use { codec.read(it, tmpPdf.parentFile, Files_createTempDir("compat-math")) }
        val studio = requireNotNull(doc.mathStudioJson) { "math studio workspace was dropped" }
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        val doc2 = out.toByteArray().inputStream()
            .use { codec.read(it, tmpPdf.parentFile, Files_createTempDir("compat-math2")) }
        assertEquals(studio, doc2.mathStudioJson)
    }

    @Test
    fun pdfBackedNoteOpensWithSource() {
        val kinds = checkRoundTrip("pdf.xnote")
        assertTrue("expected strokes", (kinds["stroke"] ?: 0) > 0)
    }
}
