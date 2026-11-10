package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.snapshot
import com.xnotes.core.model.deepCopy
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * Regression tests for total-content-loss on save: a dense page must round-trip
 * intact, and document snapshots must not drop fields the writer persists.
 */
class SaveSafetyTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun lineStrokes(): MutableList<Stroke> {
        val out = mutableListOf<Stroke>()
        var x = 50.0
        repeat(40) {
            val samples = mutableListOf<Sample>()
            repeat(60) { k ->
                samples.add(Sample(x + k * 1.5, 200.0 + (k % 5), 0.8))
            }
            out.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), samples))
            x += 14.0
        }
        return out
    }

    @Test fun densePageRoundTripsIntact() {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        page.items.addAll(lineStrokes())
        // Duplicate-all until the page is covered: 40 -> 5120 strokes (~300k samples).
        repeat(7) {
            val clones = page.items.map { (it as Stroke).let { s -> Stroke(s) } }
            for (c in clones) c.translate(24.0, 24.0)
            page.items.addAll(clones)
        }
        doc.pages.add(page)
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        val imageDir = Files.createTempDirectory("xnotes-save-safety").toFile()
        val back = codec.read(ByteArrayInputStream(out.toByteArray()), imageDir = imageDir)
        assertEquals(1, back.pages.size)
        assertEquals(page.items.size, back.pages[0].items.size)
        // Spot-check first and last strokes survived with samples.
        val first = back.pages[0].items.first() as Stroke
        val last = back.pages[0].items.last() as Stroke
        assertEquals(60, first.samples.size)
        assertEquals(60, last.samples.size)
    }

    @Test fun tmpBundlePeekMatchesWrittenPageCount() {
        val doc = Document(dpi = 150)
        doc.pages.add(Page(1240.0, 1754.0).also { it.items.addAll(lineStrokes()) })
        doc.pages.add(Page(1240.0, 1754.0).also { it.items.addAll(lineStrokes()) })
        val tmp = File.createTempFile("save-safety", ".pnotes").apply { deleteOnExit() }
        File(tmp.path).outputStream().use { codec.write(doc, it) }
        val peeked = FileInputStream(tmp).use { codec.peek(it.channel) }
        assertEquals(2, peeked?.pages)
    }

    @Test fun snapshotPreservesMathStudioJson() {
        val doc = Document(dpi = 150)
        doc.pages.add(Page(100.0, 100.0))
        doc.mathStudioJson = """{"workspace":" plotted '}"""
        val snap = doc.snapshot()
        assertEquals(doc.mathStudioJson, snap.mathStudioJson)
        val out = ByteArrayOutputStream()
        codec.write(snap, out)
        val back = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(doc.mathStudioJson, back.mathStudioJson)
    }

    @Test fun deepCopyPreservesMathStudioJson() {
        val doc = Document(dpi = 150)
        doc.pages.add(Page(100.0, 100.0))
        doc.mathStudioJson = """{"workspace":1}"""
        val copy = doc.deepCopy(FakeTextMeasurer())
        assertEquals(doc.mathStudioJson, copy.mathStudioJson)
    }
}
