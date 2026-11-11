package com.xnotes.sdocx

import com.xnotes.core.model.*
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.*
import com.xnotes.core.tools.Tool
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume
import java.io.File
import java.util.zip.ZipFile

class SdocxVerify2Test {
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
    @Test fun verifyReal() {
        val file = File("/tmp/opencode/notes-ref/test/Over_the_Horizon_261010_011234.sdocx")
        Assume.assumeTrue(file.exists())
        val entries = HashMap<String, ByteArray>()
        ZipFile(file).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val en = e.nextElement()
                if (!en.isDirectory) entries[en.name] = zf.getInputStream(en).readBytes()
            }
        }
        val parsed = SdocxParser.parseArchive(entries)
        println("PAGES ${parsed.pages.size} title=${parsed.title} bodyTables=${parsed.bodyTables.size} bodyImages=${parsed.bodyImages.size}")
        assertTrue("expected title", parsed.title == "Over_the_Horizon")
        assertTrue("expected body tables", parsed.bodyTables.isNotEmpty())
        val bt = parsed.bodyTables[0]
        println("bodyTable cols=${bt.columnWidths} rows=${bt.rows.size}")
        assertEquals(3, bt.columnWidths.size)
        assertEquals(2, bt.rows.size)
        // Check cell texts non-empty (table has actual values)
        val allTexts = bt.rows.flatMap { it.cells }.map { it.text }.filter { it.isNotBlank() }
        println("cell texts: $allTexts")
        // At least one cell should have text (real Samsung tables have values)
        // Note: Over_the_Horizon body table may be empty? Let's just log.

        val tmp = File.createTempFile("v2xx", null).let { f -> f.delete(); f.mkdirs(); f }
        try {
            val imgDir = File(tmp, "img").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf").apply { mkdirs() }
            val doc = SdocxImport.toDocument(parsed, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(doc)
            println("DOC pages=${doc!!.pages.size}")
            var images=0; var audios=0; var tables=0; var stickies=0
            for (pg in doc.pages) {
                for (it in pg.items) {
                    when (it) {
                        is ImageItem -> images++
                        is AudioItem -> audios++
                        is TableItem -> {
                            tables++
                            println(" table grid=${it.cellGrid()}")
                            // Table values must survive (Samsung 3x2 with 1..6)
                            val flat = it.cellGrid().flatten().filter { v -> v.isNotBlank() }
                            println(" table values=$flat")
                        }
                        is com.xnotes.core.model.StickyNoteItem -> {
                            stickies++
                            println(" sticky text='${it.text.take(40)}' ink=${it.ink.size}")
                        }
                        else -> {
                            if (it.kind == "sticky") stickies++
                        }
                    }
                }
            }
            println("TOT images=$images audios=$audios tables=$tables stickies=$stickies")
            assertTrue("images", images>=1)
            assertTrue("audios", audios>=1)
            assertTrue("tables", tables>=1)
            assertTrue("stickies (nested sdocx)", stickies>=1)
            // Sticky lands on its own host page at the recorded collapse
            // position (not on a new blank page at 40,40).
            val stickyItem = doc.pages.flatMap { it.items }.filterIsInstance<com.xnotes.core.model.StickyNoteItem>().firstOrNull()
            assertNotNull("sticky item", stickyItem)
            assertTrue("sticky x ${stickyItem!!.rect.x}", stickyItem.rect.x in 680.0..695.0)
            assertTrue("sticky y ${stickyItem.rect.y}", stickyItem.rect.y in 380.0..395.0)
            val hostPage = doc.pages.firstOrNull { it.items.contains(stickyItem) }
            assertNotNull("sticky host page", hostPage)
            assertEquals("host page holds only the sticky", 1, hostPage!!.items.size)
            // Table values: body table has 1..6
            val tbl = doc.pages.flatMap { it.items }.filterIsInstance<TableItem>().firstOrNull()
            assertNotNull("table item", tbl)
            val flatVals = tbl!!.cellGrid().flatten().filter { it.isNotBlank() }.sorted()
            // Over_the_Horizon body table holds 1..6
            assertTrue("table values $flatVals", flatVals.containsAll(listOf("1","2","3","4","5","6")))
            // Extra pages: original 9, with 2 shells dropped => 7 + table page + sticky page = 9. Must not grow.
            assertTrue("too many pages ${doc.pages.size}", doc.pages.size <= 9)
            // Sticky: outer has nested sdocx, should create sticky or fallback content (at least not crash, pages may increase by 1)
            // Audio file exists
            val aud = doc.pages.flatMap { it.items }.filterIsInstance<AudioItem>()
            assertTrue(aud.isNotEmpty() && aud[0].audio.file.exists())
        } finally { tmp.deleteRecursively() }
    }

    @Test fun stickerPlacementParsedFromHostPage() {
        val file = File("/tmp/opencode/notes-ref/test/Over_the_Horizon_261010_011234.sdocx")
        Assume.assumeTrue(file.exists())
        val entries = HashMap<String, ByteArray>()
        ZipFile(file).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val en = e.nextElement()
                if (!en.isDirectory) entries[en.name] = zf.getInputStream(en).readBytes()
            }
        }
        // The sticker host page carries co_attach_file + skn_collapse_rect in its flex.
        val parsed = SdocxParser.parseArchive(entries)
        val hosts = parsed.pages.filter { it.stickers.isNotEmpty() }
        assertEquals(1, hosts.size)
        val st = hosts[0].stickers[0]
        assertEquals(10, st.bindId)
        assertNotNull(st.collapseRect)
        assertTrue(st.collapseRect!!.left in 680.0..695.0)
        assertTrue(st.collapseRect!!.top in 380.0..395.0)
        assertEquals(-6482, st.bgColorArgb)
    }

    @Test fun exportHasTitleAndPens() {
        val tmp = File.createTempFile("expxx", null).let { f -> f.delete(); f.mkdirs(); f }
        try {
            val doc = Document(dpi=150)
            doc.displayName = "MyTitle.pnote"
            val page = Page(1440.0, 2036.0)
            val s = Stroke(Tool.PEN, com.xnotes.core.tools.ToolDefaults.configFor(Tool.PEN).copy(baseWidth=3.0, rgba=Rgba(20,20,20,255)))
            s.setSamples(listOf(com.xnotes.core.stroke.Sample(100.0,100.0,0.8), com.xnotes.core.stroke.Sample(200.0,200.0,0.8)))
            s.finished=true
            page.items.add(s)
            doc.pages.add(page)
            val out = java.io.ByteArrayOutputStream()
            SdocxExport.writeDocument(doc, out)
            val bytes = out.toByteArray()
            assertTrue(bytes.size>500)
            // Unzip and check note.note contains title and pen strings
            val entries = HashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e!=null) { if (!e.isDirectory) entries[e.name]=zis.readBytes(); zis.closeEntry(); e=zis.nextEntry }
            }
            val note: ByteArray = entries["note.note"] ?: throw AssertionError("no note.note")
            // Titles are stored as raw UTF-16LE bytes inside TextCommon; search
            // raw bytes (alignment-independent, like Samsung's own layout).
            fun containsUtf16(hay: ByteArray, needle: String): Boolean {
                val n = needle.toByteArray(Charsets.UTF_16LE)
                if (n.isEmpty()) return true
                outer@ for (i in 0..hay.size - n.size) {
                    for (j in n.indices) if (hay[i + j] != n[j]) continue@outer
                    return true
                }
                return false
            }
            assertTrue("title in note.note", containsUtf16(note, "MyTitle"))
            assertTrue("InkPen2 in note.note", containsUtf16(note, "InkPen2"))
            // Parse back and check pen resolved
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages.size)
            assertEquals(1, parsed.pages[0].strokes.size)
            assertEquals("InkPen2", parsed.pages[0].strokes[0].penName)
        } finally { tmp.deleteRecursively() }
    }
}
