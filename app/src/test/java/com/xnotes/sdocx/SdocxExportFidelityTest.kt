package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.StickyNoteItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Samsung Notes fidelity fixes: stroke widths (stylus bit, no fixed-width
 * bit, real pen-field layout), native sticky sub-notes, playable voice
 * pills (real layout bytes) and taper import/export.
 */
class SdocxExportFidelityTest {

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

    private fun createTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "x")
        f.delete(); f.mkdirs()
        return f
    }

    private fun penStroke(tool: Tool, w: Double, vararg pts: Double): Stroke {
        val s = Stroke(tool, ToolDefaults.configFor(tool).copy(baseWidth = w))
        val samples = ArrayList<Sample>()
        var i = 0
        while (i < pts.size) { samples.add(Sample(pts[i], pts[i + 1], 0.8)); i += 2 }
        s.setSamples(samples); s.finished = true
        return s
    }

    private fun fakeM4a(): ByteArray {
        val w = SdocxWriter()
        w.writeU32(24)
        w.writeBytes(byteArrayOf(0x66, 0x74, 0x79, 0x70))
        w.writeBytes(byteArrayOf(0x4D, 0x34, 0x41, 0x20))
        w.writeBytes(ByteArray(12))
        return w.bytes()
    }

    private fun unzipToMap(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return out
    }

    /** First kind-1 stroke frame found in a page blob, with its masks. */
    private fun firstStrokeFrame(pageBytes: ByteArray): Triple<ByteArray, ByteArray, ByteArray>? {
        // Walk objects like the parser: header, layers, objects.
        val r = SdocxReader(pageBytes)
        r.readU32(); r.readU32() // rawLayerOffset, propertyOffset
        val rawLayer = run {
            val rr = SdocxReader(pageBytes)
            rr.readU32().toInt()
        }
        val lr = SdocxReader(pageBytes, rawLayer)
        val layerCount = lr.readU16(); lr.readU16()
        repeat(layerCount) {
            val headerSize = lr.readU32().toInt()
            lr.skip(headerSize - 4)
            val objectCount = lr.readU32().toInt()
            repeat(objectCount) {
                if (lr.remaining < 7) return@repeat
                val type = lr.readU8()
                lr.readU16()
                val declaredSize = lr.readU32().toInt()
                if (declaredSize < 32 || lr.remaining < declaredSize - 32 + 32) return@repeat
                val payload = lr.readBytes(declaredSize - 32)
                lr.skip(32)
                if (type == 1) {
                    val pr = SdocxReader(payload)
                    val base = SdocxParser.readFrame(pr)
                    if (!pr.isEof) {
                        val frame = SdocxParser.readFrame(pr)
                        if (frame.kind == 1) return Triple(base.properties.bytes, frame.properties.bytes, frame.fields.bytes)
                    }
                }
            }
            if (lr.remaining >= 32) lr.skip(32)
        }
        return null
    }

    @Test fun strokeWidthSurvivesWithStylusProps() {
        val tmp = createTempDir("sdocx-width")
        try {
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            page.items.add(penStroke(Tool.PEN, 6.0, 100.0, 100.0, 400.0, 400.0))
            doc.pages.add(page)
            val outBytes = ByteArrayOutputStream().also { SdocxExport.writeDocument(doc, it) }.toByteArray()
            val entries = unzipToMap(outBytes)
            val pageBytes = entries.entries.first { it.key.endsWith(".page") }.value
            val (baseProps, props, fields) = firstStrokeFrame(pageBytes) ?: throw AssertionError("no stroke frame")
            val propMask = com.xnotes.sdocx.SdocxMask(props)
            // Mirrors real Samsung writers: compressed + stylus, no fixed-width.
            assertTrue("stylus bit set", propMask.contains(2))
            assertTrue("compressed bit set", propMask.contains(0))
            assertTrue("fixed-width bit clear", !propMask.contains(4))
            val fieldMask = com.xnotes.sdocx.SdocxMask(fields)
            for (b in listOf(1, 2, 3, 7, 8, 10, 13)) assertTrue("field bit $b set", fieldMask.contains(b))
            assertTrue("legacy pen-name bit clear like Samsung", !fieldMask.contains(0))
            // Width round-trips 1:1 through our own parser.
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages[0].strokes.size)
            assertEquals(6.0, parsed.pages[0].strokes[0].penWidth, 1e-6)
            assertEquals(2, parsed.pages[0].strokes[0].toolTypeRaw)
        } finally { tmp.deleteRecursively() }
    }

    @Test fun taperSurvivesExportImport() {
        // Import: newer Samsung writers flag tapered ends on bit 9.
        val tapered = SdocxStroke(
            points = listOf(SdocxPoint(10.0, 10.0), SdocxPoint(200.0, 200.0)),
            pressures = listOf(0.8, 0.8), colorArgb = -16777216, penWidth = 4.0,
            bbox = SdocxRect(10.0, 10.0, 200.0, 200.0),
            penName = "InkPen2", penNameId = 1, advancedSettingId = 2, toolTypeRaw = 2,
            tapered = true, createdTimeUs = null,
        )
        val plain = SdocxStroke(
            points = listOf(SdocxPoint(10.0, 10.0), SdocxPoint(200.0, 200.0)),
            pressures = listOf(0.8, 0.8), colorArgb = -16777216, penWidth = 4.0,
            bbox = SdocxRect(10.0, 10.0, 200.0, 200.0),
            penName = "InkPen2", penNameId = 1, advancedSettingId = 2, toolTypeRaw = 2,
            tapered = false, createdTimeUs = null,
        )
        val t1 = SdocxImport.toStroke(tapered)!!
        assertTrue("taper flag enables taper rendering", t1.config.taperEnabled)
        val t2 = SdocxImport.toStroke(plain)!!
        assertTrue("plain stroke stays untapered", !t2.config.taperEnabled)
        // Pen names containing taper map to the taper tool.
        assertEquals(Tool.TAPER, SdocxMapping.samsungPenToTool("MyTaperPen"))
        // Export mirrors real Samsung writers byte-for-byte (no unknown bits,
        // so Samsung never drops the stroke): taper travels as fountain-pen
        // geometry with its coordinates and color intact.
        val tmp = createTempDir("sdocx-taper")
        try {
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            val s = penStroke(Tool.TAPER, 4.0, 50.0, 50.0, 300.0, 300.0)
            page.items.add(s)
            doc.pages.add(page)
            val outBytes = ByteArrayOutputStream().also { SdocxExport.writeDocument(doc, it) }.toByteArray()
            val entries = unzipToMap(outBytes)
            val pageBytes = entries.entries.first { it.key.endsWith(".page") }.value
            val (_, props, _) = firstStrokeFrame(pageBytes) ?: throw AssertionError("no stroke frame")
            assertTrue("stylus bit set like Samsung writers", com.xnotes.sdocx.SdocxMask(props).contains(2))
            assertTrue("no unknown property bits (Samsung-safe)", !com.xnotes.sdocx.SdocxMask(props).contains(9))
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages[0].strokes.size)
            assertEquals(4.0, parsed.pages[0].strokes[0].penWidth, 1e-6)
            val back = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val backStroke = back.pages.flatMap { it.items }.filterIsInstance<Stroke>().single()
            assertEquals("coordinates survive", 50.0, backStroke.xAt(0), 1e-6)
            assertEquals("color survives", s.config.rgba.toArgb(), backStroke.config.rgba.toArgb())
        } finally { tmp.deleteRecursively() }
    }

    @Test fun tableExportsAsNativeType22AndRoundTrips() {
        val tmp = createTempDir("sdocx-table")
        try {
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            page.items.add(penStroke(Tool.PEN, 3.0, 100.0, 100.0, 200.0, 200.0))
            val table = com.xnotes.core.model.TableItem.create(Rect(100.0, 500.0, 400.0, 300.0), 2, 2, Rgba(0, 0, 0, 255), 2.0)
            table.setCell(0, 0, "A1")
            table.setCell(1, 0, "B1")
            table.setCell(0, 1, "A2")
            table.setCell(1, 1, "B2")
            page.items.add(table)
            doc.pages.add(page)
            val outBytes = ByteArrayOutputStream().also { SdocxExport.writeDocument(doc, it) }.toByteArray()
            val entries = unzipToMap(outBytes)
            val pageBytes = entries.entries.first { it.key.endsWith(".page") }.value
            // A native type-22 table object is on the page (not flattened ink).
            var sawTable = false
            run {
                val head = SdocxReader(pageBytes)
                head.readU32()
                head.readU32()
                val raw = run { val rr = SdocxReader(pageBytes); rr.readU32().toInt() }
                val lay = SdocxReader(pageBytes, raw)
                lay.readU16(); lay.readU16()
                val hs = lay.readU32().toInt(); lay.skip(hs - 4)
                val oc = lay.readU32().toInt()
                repeat(oc) {
                    if (lay.remaining < 7) return@run
                    val t = lay.readU8(); lay.readU16(); val ds = lay.readU32().toInt()
                    if (ds < 32 || lay.remaining < ds) return@run
                    lay.skip(ds - 32); lay.skip(32)
                    if (t == 22) sawTable = true
                }
            }
            assertTrue("native type-22 table object present", sawTable)
            // Our parser reads it back as a table with its cell values.
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages.sumOf { it.tables.size })
            // Full re-import surfaces a real table (not flattened strokes/text).
            val back = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val tables = back.pages.flatMap { it.items }.filterIsInstance<com.xnotes.core.model.TableItem>()
            assertEquals(1, tables.size)
            val grid = tables[0].cellGrid().flatten()
            assertTrue("cell values survive, got $grid", grid.containsAll(listOf("A1", "B1", "A2", "B2")))
        } finally { tmp.deleteRecursively() }
    }

    @Test fun stickyExportsAsNativeSubNoteAndRoundTrips() {        val tmp = createTempDir("sdocx-sticky")
        try {
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            page.items.add(penStroke(Tool.PEN, 3.0, 100.0, 100.0, 200.0, 200.0))
            val ink = mutableListOf(penStroke(Tool.PEN, 3.0, 0.0, 0.0, 50.0, 50.0))
            page.items.add(StickyNoteItem(Rect(100.0, 900.0, 300.0, 300.0), Rgba(255, 241, 118, 255), false, "hello sticky", ink, fakeMeasurer))
            doc.pages.add(page)
            val outBytes = ByteArrayOutputStream().also { SdocxExport.writeDocument(doc, it) }.toByteArray()
            val entries = unzipToMap(outBytes)
            // Nested sub-note asset exists and the host page carries its attach block.
            val nested = entries.keys.filter { it.endsWith(".sdocx") }
            assertEquals("one nested sticky, got $nested", 1, nested.size)
            val pageBytes = entries.entries.first { it.key.endsWith(".page") }.value
            val ascii = String(pageBytes, Charsets.US_ASCII)
            assertTrue("co_attach_file block present", ascii.contains("co_attach_file"))
            assertTrue("collapse rect present", ascii.contains("skn_collapse_rect"))
            assertTrue("bg color present", ascii.contains("skn_bg_color"))
            // The sub-note parses on its own with the sticky content.
            val innerEntries = HashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(entries[nested[0]])).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) innerEntries[e.name] = zis.readBytes()
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            val inner = SdocxParser.parseArchive(innerEntries)
            assertEquals(1, inner.pages.size)
            assertTrue("inner ink survives", inner.pages[0].strokes.isNotEmpty())
            // Full re-import surfaces a real sticky (not flattened ink).
            val parsed = SdocxParser.parseArchive(entries)
            val back = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val stickies = back.pages.flatMap { it.items }.filterIsInstance<StickyNoteItem>()
            assertEquals("sticky survives round-trip, got ${back.pages.flatMap { it.items }.map { it.kind }}", 1, stickies.size)
            assertEquals("hello sticky", stickies[0].text)
            assertTrue(stickies[0].ink.isNotEmpty())
        } finally { tmp.deleteRecursively() }
    }

    @Test fun voicePillCarriesRealLayoutBytes() {
        val tmp = createTempDir("sdocx-voice")
        try {
            val audFile = File(tmp, "a.m4a").apply { writeBytes(fakeM4a()) }
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            page.items.add(penStroke(Tool.PEN, 3.0, 100.0, 100.0, 200.0, 200.0))
            page.items.add(AudioItem(AudioData(audFile, "m4a", 3000, ByteArray(0)), AudioItem.boxAt(Pt(720.0, 500.0), 150), "Memo", 3, fakeMeasurer))
            doc.pages.add(page)
            val outBytes = ByteArrayOutputStream().also { SdocxExport.writeDocument(doc, it) }.toByteArray()
            val entries = unzipToMap(outBytes)
            val pageBytes = entries.entries.first { it.key.endsWith(".page") }.value
            // Real Samsung pill layout bytes (not zeros) accompany the voice frame.
            val marker = byteArrayOf(0xce.toByte(), 0xbc.toByte(), 0xcf.toByte())
            var found = false
            var i = 0
            while (i + marker.size <= pageBytes.size) {
                var j = 0
                while (j < marker.size && pageBytes[i + j] == marker[j]) j++
                if (j == marker.size) { found = true; break }
                i++
            }
            assertTrue("voice pill carries Samsung layout bytes", found)
            assertTrue("voice title present", String(pageBytes, Charsets.UTF_16LE).contains("Memo"))
            // And it still round-trips through our own parser.
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages.sumOf { it.voices.size })
            assertEquals("Memo", parsed.pages[0].voices[0].title)
        } finally { tmp.deleteRecursively() }
    }
}
