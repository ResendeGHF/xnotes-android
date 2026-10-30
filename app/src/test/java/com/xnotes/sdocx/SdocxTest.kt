package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

class SdocxTest {

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

    private fun stroke(tool: Tool, color: Rgba, width: Double, vararg pts: Double): Stroke {
        val s = Stroke(tool, ToolDefaults.configFor(tool).copy(baseWidth = width, rgba = color))
        val samples = ArrayList<Sample>()
        var i = 0
        while (i < pts.size) {
            samples.add(Sample(pts[i], pts[i + 1], 0.8))
            i += 2
        }
        s.setSamples(samples)
        s.finished = true
        return s
    }

    @Test fun binaryRoundTrip() {
        val w = SdocxWriter()
        w.writeU16(0xABCD)
        w.writeU32(0x12345678)
        w.writeI32(-42)
        w.writeF64(123.456)
        w.writeUtf16U16("InkPen2")
        w.writeMask(SdocxMask(byteArrayOf(0x8F.toByte())))
        val r = SdocxReader(w.bytes())
        assertEquals(0xABCD, r.readU16())
        assertEquals(0x12345678L, r.readU32())
        assertEquals(-42, r.readI32())
        assertEquals(123.456, r.readF64(), 1e-9)
        assertEquals("InkPen2", r.readUtf16U16())
        val m = SdocxMask.read(r)
        assertTrue(m.contains(0))
        assertTrue(m.contains(7))
    }

    @Test fun penMappingRoundTrip() {
        assertEquals(Tool.PEN, SdocxMapping.samsungPenToTool("InkPen2"))
        assertEquals(Tool.PEN, SdocxMapping.samsungPenToTool("FountainPen"))
        assertEquals(Tool.CALLIGRAPHY, SdocxMapping.samsungPenToTool("ObliquePen"))
        assertEquals(Tool.HIGHLIGHTER, SdocxMapping.samsungPenToTool("Marker4"))
        assertEquals("InkPen2", SdocxMapping.toolToSamsungPen(Tool.PEN))
        assertEquals("Marker4", SdocxMapping.toolToSamsungPen(Tool.HIGHLIGHTER))
        assertEquals("ObliquePen", SdocxMapping.toolToSamsungPen(Tool.CALLIGRAPHY))
    }

    @Test fun templateMapping() {
        assertEquals(PagePattern.LINES.id, SdocxMapping.samsungTemplateToPattern(1, null))
        assertEquals(PagePattern.GRID.id, SdocxMapping.samsungTemplateToPattern(5, null))
        assertEquals(PagePattern.DOTS.id, SdocxMapping.samsungTemplateToPattern(8, null))
        assertEquals("none", SdocxMapping.samsungTemplateToPattern(null, null))
        assertEquals(PagePattern.GRID.id, SdocxMapping.samsungTemplateToPattern(null, "grid_note"))
    }

    @Test fun exportImportRoundTripStrokes() {
        val tmp = createTempDir("sdocx-test")
        try {
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0, style = PageStyle(template = PagePattern.LINES.id))
            page.items.add(stroke(Tool.PEN, Rgba(20, 20, 20, 255), 3.0, 100.0, 100.0, 200.0, 200.0, 300.0, 150.0))
            page.items.add(stroke(Tool.HIGHLIGHTER, Rgba(255, 235, 59, 255), 16.0, 50.0, 500.0, 400.0, 520.0))
            page.items.add(stroke(Tool.CALLIGRAPHY, Rgba(0, 0, 0, 255), 6.0, 600.0, 600.0, 700.0, 700.0))
            doc.pages.add(page)

            val out = ByteArrayOutputStream()
            SdocxExport.writeDocument(doc, out)
            assertTrue("empty sdocx", out.size() > 500)

            // Unzip and parse back.
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) entries[e.name] = zis.readBytes()
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            assertTrue(entries.keys.any { it.endsWith(".page") })
            assertTrue(entries.containsKey("media/mediaInfo.dat"))
            assertTrue(entries.containsKey("pageIdInfo.dat"))

            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages.size)
            // 3 strokes exported, all should import. Without a note.note string
            // table the pen names are unresolved (null); colors/widths survive.
            assertEquals(3, parsed.pages[0].strokes.size)
            assertEquals(1, parsed.pages[0].templateType)

            val imgDir = File(tmp, "img").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf").apply { mkdirs() }
            val back = SdocxImport.toDocument(parsed, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(back)
            assertEquals(1, back!!.pages.size)
            assertEquals(3, back.pages[0].items.filterIsInstance<Stroke>().size)
            // Calligraphy collapses to PEN without pen names; highlighter survives via alpha.
            val tools = back.pages[0].items.filterIsInstance<Stroke>().map { it.tool }
            assertEquals(listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.PEN), tools)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun exportImportImage() {
        val tmp = createTempDir("sdocx-img")
        try {
            // Minimal 1x1 PNG.
            val png = byteArrayOf(
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
            val imgFile = File(tmp, "src.png").apply { writeBytes(png) }
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            page.items.add(ImageItem(ImageData(imgFile, 1, 1), Rect(100.0, 100.0, 300.0, 300.0)))
            doc.pages.add(page)

            val out = ByteArrayOutputStream()
            SdocxExport.writeDocument(doc, out)

            val entries = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) entries[e.name] = zis.readBytes()
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages.size)
            assertEquals(1, parsed.pages[0].images.size)

            val imgDir = File(tmp, "img2").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf2").apply { mkdirs() }
            val back = SdocxImport.toDocument(parsed, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(back)
            assertEquals(1, back!!.pages[0].items.filterIsInstance<ImageItem>().size)
        } finally {
            tmp.deleteRecursively()
        }
    }



    private fun createTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun unzipToMap(bytes: ByteArray): HashMap<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return entries
    }

    @Test fun exportShapesTablesTextAsStrokesAndTextBoxes() {
        val tmp = createTempDir("sdocx-shapes")
        try {
            val doc = Document(dpi = 150)
            val page = Page(1440.0, 2036.0)
            // A rectangle shape, a dashed line shape and a table grid.
            page.items.add(ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(400.0, 300.0),
                Rgba(255, 0, 0, 255), 4.0))
            page.items.add(ShapeItem(ShapeKind.LINE, Pt(100.0, 400.0), Pt(500.0, 400.0),
                Rgba(0, 0, 255, 255), 3.0, dashed = true))
            page.items.add(TableItem.create(Rect(100.0, 500.0, 400.0, 300.0), 2, 3,
                Rgba(0, 0, 0, 255), 2.0))
            page.items.add(TextItem(Pt(100.0, 900.0), 600.0, 0.0, "Hello Samsung",
                Rgba(10, 20, 30, 255), 16.0, com.xnotes.core.pal.FontFace.SANS, fakeMeasurer,
                bold = true, align = HAlign.CENTER))
            doc.pages.add(page)

            val out = ByteArrayOutputStream()
            SdocxExport.writeDocument(doc, out)
            val parsed = SdocxParser.parseArchive(unzipToMap(out.toByteArray()))
            assertEquals(1, parsed.pages.size)
            val sp = parsed.pages[0]
            // Rect closed run + dashed line + table (outline + 1 col + 2 rows) = strokes.
            assertTrue("expected shape strokes, got ${sp.strokes.size}", sp.strokes.size >= 1 + 1 + 4)
            assertEquals(1, sp.textBoxes.size)
            val tb = sp.textBoxes[0]
            assertEquals("Hello Samsung", tb.text)
            assertEquals(Rgba(10, 20, 30, 255).toArgb(), tb.colorArgb)
            assertEquals(16f, tb.fontSize)
            assertTrue(tb.bold)
            assertEquals(2, tb.align)

            val imgDir = File(tmp, "img").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf").apply { mkdirs() }
            val back = SdocxImport.toDocument(parsed, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(back)
            val items = back!!.pages[0].items
            // Shapes/tables come back as ink (documented), text as a text box.
            assertTrue(items.filterIsInstance<Stroke>().size >= 6)
            val texts = items.filterIsInstance<TextItem>()
            assertEquals(1, texts.size)
            assertEquals("Hello Samsung", texts[0].text)
            assertTrue(texts[0].bold)
            assertEquals(HAlign.CENTER, texts[0].align)
        } finally {
            tmp.deleteRecursively()
        }
    }

    // --- synthetic Samsung payload builders (mirror the writer's layouts) ---

    private fun wrapTestObject(type: Int, vararg frames: ByteArray, children: Int = 0): ByteArray {
        val payload = ByteArrayOutputStream()
        for (f in frames) payload.write(f)
        val p = payload.toByteArray()
        val w = SdocxWriter()
        w.writeU8(type)
        w.writeU16(children)
        w.writeU32((p.size + 32).toLong())
        w.writeBytes(p)
        w.writeBytes(ByteArray(32))
        return w.bytes()
    }

    private fun testBaseFrame(bbox: DoubleArray): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(1)
        fixed.writeUtf8U16(UUID.randomUUID().toString())
        fixed.writeI64(0)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeI32(0)
        fixed.writeU8(0)
        return testFrame(0, byteArrayOf(0x0E.toByte()), byteArrayOf(0x00), fixed.bytes(), ByteArray(0))
    }

    private fun testFrame(kind: Int, props: ByteArray, fields: ByteArray, fixed: ByteArray, flex: ByteArray): ByteArray {
        val propSize = 1 + props.size
        val fieldSize = 1 + fields.size
        val headerSize = 2 + 4 + propSize + fieldSize
        val flexOffset = if (flex.isEmpty() && fields.all { it == 0.toByte() }) 0 else 4 + headerSize + fixed.size
        val size = 4 + headerSize + fixed.size + flex.size
        val w = SdocxWriter()
        w.writeU32(size.toLong())
        w.writeI16(kind)
        w.writeU32(flexOffset.toLong())
        w.writeU8(props.size)
        w.writeBytes(props)
        w.writeU8(fields.size)
        w.writeBytes(fields)
        w.writeBytes(fixed)
        w.writeBytes(flex)
        return w.bytes()
    }

    private fun testShapeBaseFrame(): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(0)
        fixed.writeU32(4)
        fixed.writeU32(0)
        fixed.writeU8(0)
        return testFrame(6, byteArrayOf(0x00), byteArrayOf(0x00), fixed.bytes(), ByteArray(0))
    }

    private fun testShapePayload(shapeType: Int, bbox: DoubleArray, withLabel: Boolean): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(shapeType.toLong())
        for (v in bbox) fixed.writeF64(v)
        fixed.writeF32(0f)
        fixed.writeU32(0) // empty native path
        fixed.writeU8(0) // no control points
        for (v in bbox) fixed.writeF64(v) // drawn bbox
        val flex = if (withLabel) {
            SdocxExport.textCommonBytes("Hi", Rgba(1, 2, 3, 255).toArgb(), 14f, false, false, false, false, 0)
        } else ByteArray(0)
        val fields = if (withLabel) byteArrayOf(0x01) else byteArrayOf(0x00)
        val shape = testFrame(7, byteArrayOf(0x00), fields, fixed.bytes(), flex)
        val out = ByteArrayOutputStream()
        out.write(testBaseFrame(bbox))
        out.write(testShapeBaseFrame())
        out.write(shape)
        return out.toByteArray()
    }

    /** A kind-7 shape frame carrying an image fill for [bindId] (photo storage). */
    private fun testImageShapeFrame(bindId: Int, bbox: DoubleArray): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(4) // rectangle
        for (v in bbox) fixed.writeF64(v)
        fixed.writeF32(0f) // rotation
        fixed.writeU32(0) // empty native path
        fixed.writeU8(0) // no control points
        val fill = SdocxWriter()
        fill.writeU8(0) // fill mode: stretch
        fill.writeI32(bindId)
        for (i in 0 until 9) fill.writeF32(if (i in 6..7) 100f else 0f)
        fill.writeU8(0) // rotatable
        repeat(4) { fill.writeI32(0) } // nine-patch rect
        fill.writeI32(0) // nine-patch width
        val fillBytes = fill.bytes()
        val flex = SdocxWriter()
        flex.writeU32(fillBytes.size.toLong())
        flex.writeU8(2) // fill kind: image
        flex.writeBytes(fillBytes)
        return testFrame(7, byteArrayOf(0x00), byteArrayOf(0x20), fixed.bytes(), flex.bytes())
    }

    /** Bare embedded-photo object (frames only, no object header): one text-flow image. */
    private fun testEmbeddedImageObject(bindId: Int, bbox: DoubleArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(testBaseFrame(bbox))
        out.write(testShapeBaseFrame())
        out.write(testImageShapeFrame(bindId, bbox))
        out.write(testFrame(3, byteArrayOf(0x00), byteArrayOf(0x00), ByteArray(0), ByteArray(0)))
        return out.toByteArray()
    }

    /** A TextCommon flexible block whose text is one object placeholder with an image span. */
    private fun testTextCommonWithImage(imageObject: ByteArray): ByteArray {
        val span = SdocxWriter()
        span.writeU32(imageObject.size.toLong()) // objectSize
        span.writeU32(3) // objectType: Image
        span.writeBytes(imageObject)
        span.writeU32(0) // text index
        span.writeU32(0) // layout: block
        span.writeU32(0) // constraint: normal
        val spanBytes = span.bytes()
        val common = SdocxWriter()
        common.writeUtf16U32("￼")
        common.writeU32(0) // no style spans
        common.writeU32(1) // one paragraph
        val para = SdocxWriter()
        para.writeU32(3) // Alignment
        para.writeU32(0)
        para.writeU32(1)
        para.writeU32(2) // center
        val paraBytes = para.bytes()
        common.writeU16(paraBytes.size)
        common.writeBytes(paraBytes)
        common.writeF32(0f)
        common.writeF32(0f)
        common.writeF32(0f)
        common.writeF32(0f)
        common.writeU8(0) // gravity
        common.writeU16(0) // no sections
        common.writeU32(1) // object span flags
        common.writeU32(0) // reserved
        common.writeU32(1) // one object span
        common.writeU32(spanBytes.size.toLong())
        common.writeBytes(spanBytes)
        val commonBytes = common.bytes()
        val flex = SdocxWriter()
        flex.writeU32(commonBytes.size.toLong())
        flex.writeBytes(commonBytes)
        return flex.bytes()
    }

    /** A note.note title/body text object (base + shape-base + shape-text frames). */
    private fun testTextObjectBlob(textCommonFlex: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(testBaseFrame(doubleArrayOf(0.0, 0.0, 400.0, 300.0)))
        out.write(testShapeBaseFrame())
        out.write(testFrame(7, byteArrayOf(0x00), byteArrayOf(0x01), ByteArray(0), textCommonFlex))
        return out.toByteArray()
    }

    /** Minimal note.note shell holding [title] and [body] text-object blobs. */
    private fun testNoteFile(titleBlob: ByteArray, bodyBlob: ByteArray): ByteArray {
        val w = SdocxWriter()
        w.writeU32(0) // flexible offset (unused by the body-image walk)
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeU32(4000) // format version
        w.writeUtf16U16("") // empty note id
        w.writeBytes(ByteArray(4 + 8 + 8 + 4 + 4 + 4 + 4 + 4))
        w.writeU32(titleBlob.size.toLong())
        w.writeBytes(titleBlob)
        w.writeU32(bodyBlob.size.toLong())
        w.writeBytes(bodyBlob)
        return w.bytes()
    }

    private fun testMediaInfo(entries: List<Triple<Int, String, ByteArray>>): ByteArray {
        val w = SdocxWriter()
        w.writeU32(5500)
        w.writeU16(entries.size)
        for ((id, name, _) in entries) {
            val rec = SdocxWriter()
            rec.writeI32(id)
            rec.writeUtf16U16(name)
            rec.writeBytes(ByteArray(2)) // no-hash prefix
            rec.writeU16(1) // reference count
            rec.writeI64(0)
            rec.writeU8(1) // attached
            val recBytes = rec.bytes()
            w.writeU32(recBytes.size.toLong())
            w.writeBytes(recBytes)
        }
        w.writeBytes(byteArrayOf(0x45, 0x4F, 0x46, 0x58)) // EOFX
        return w.bytes()
    }

    /** A page record holding zero objects (bg color keeps it from importing as a shell). */
    private fun testEmptyPage(pageUuid: String, w: Int = 1080, h: Int = 1527): ByteArray {
        val header = SdocxWriter()
        header.writeMask(SdocxMask(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        header.writeMask(SdocxMask(byteArrayOf(0x70, 0x00, 0x00, 0x00)))
        header.writeU32(0)
        header.writeU32(w.toLong())
        header.writeU32(h.toLong())
        header.writeU32(0)
        header.writeU32(0)
        header.writeUtf16U16(pageUuid)
        header.writeI64(0)
        header.writeU32(4000)
        header.writeU32(4000)
        val flex = SdocxWriter()
        flex.writeU32(2) // bgImageMode
        flex.writeU32(0xFFFCFCFC) // bgColor
        flex.writeU32(w.toLong()) // bgWidth
        val propOff = 0x80
        val out = SdocxWriter()
        out.writeU32((propOff + flex.size()).toLong())
        out.writeU32(propOff.toLong())
        out.writeBytes(header.bytes())
        out.writeBytes(flex.bytes())
        val layerId = UUID.randomUUID().toString()
        val extra = SdocxWriter().also { it.writeUtf16U16(layerId); it.writeI64(0) }.bytes()
        val layer = SdocxWriter()
        layer.writeU32((16 + extra.size).toLong())
        layer.writeU32(0) // metadata offset (unchecked by the parser)
        layer.writeU8(1)
        layer.writeU8(0x02)
        layer.writeU8(1)
        layer.writeU8(0x18)
        layer.writeU32(0)
        layer.writeBytes(extra)
        layer.writeU32(0) // no objects
        layer.writeBytes(ByteArray(32)) // integrity trailer
        out.writeU16(1) // one layer
        out.writeU16(0) // current layer
        out.writeBytes(layer.bytes())
        return out.bytes()
    }

    private fun testPageIdInfo(pageUuid: String): ByteArray {
        val w = SdocxWriter()
        w.writeBytes(ByteArray(32))
        w.writeU16(1)
        w.writeUtf16U16(pageUuid)
        w.writeBytes(ByteArray(32))
        return w.bytes()
    }

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

    @Test fun importSyntheticRectangleAndLabel() {
        val bbox = doubleArrayOf(10.0, 20.0, 110.0, 120.0)
        val payload = testShapePayload(4, bbox, withLabel = true)
        val s = SdocxParser.decodeShape(payload)
        assertNotNull(s)
        assertEquals(4, s!!.shapeType)
        assertNotNull(s.label)
        assertEquals("Hi", s.label!!.text)
        val items = SdocxImport.toShapeItems(s, fakeMeasurer)
        assertEquals(1, items.size)
        val shape = items[0] as ShapeItem
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
        assertNotNull(shape.label)
        assertEquals("Hi", shape.label!!.text)
    }

    @Test fun importSyntheticLine() {
        val out = ByteArrayOutputStream()
        val bbox = doubleArrayOf(0.0, 0.0, 200.0, 50.0)
        out.write(testBaseFrame(bbox))
        out.write(testShapeBaseFrame())
        val fixed = SdocxWriter()
        fixed.writeU8(0) // straight
        fixed.writeU8(0) // routing
        fixed.writeU8(0) // no control points
        fixed.writeF64(10.0); fixed.writeF64(10.0)
        fixed.writeF64(190.0); fixed.writeF64(40.0)
        for (v in bbox) fixed.writeF64(v)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeU32(0)
        out.write(testFrame(8, byteArrayOf(0x00), byteArrayOf(0x00), fixed.bytes(), ByteArray(0)))
        val line = SdocxParser.decodeLine(out.toByteArray())
        assertNotNull(line)
        assertEquals(0, line!!.lineType)
        val items = SdocxImport.toLineItems(line)
        assertEquals(1, items.size)
        val shape = items[0] as ShapeItem
        assertEquals(ShapeKind.LINE, shape.shape)
    }

    @Test fun noteBodyImagesParsedFromTitleAndBody() {
        // A pasted photo lives in note.note's body (and title) text as an
        // embedded type-3 object span, not in any `.page`.
        val bbox = doubleArrayOf(48.0, 90.0, 499.0, 390.0)
        val photo = testEmbeddedImageObject(7, bbox)
        val body = testTextObjectBlob(testTextCommonWithImage(photo))
        val title = testTextObjectBlob(
            SdocxExport.textCommonBytes("T", Rgba(1, 2, 3, 255).toArgb(), 14f, false, false, false, false, 0))
        val found = SdocxParser.parseNoteBodyImages(testNoteFile(title, body))
        assertEquals(1, found.size)
        assertEquals(7, found[0].mediaId)
        assertEquals(48.0, found[0].bbox.left, 1e-9)
        assertEquals(90.0, found[0].bbox.top, 1e-9)
        assertEquals(499.0, found[0].bbox.right, 1e-9)
        assertEquals(390.0, found[0].bbox.bottom, 1e-9)
    }

    @Test fun noteBodyImagesImportAsPhotos() {
        val tmp = createTempDir("sdocx-bodyimg")
        try {
            val png = tinyPng()
            val bbox = doubleArrayOf(48.0, 90.0, 499.0, 390.0)
            val photo = testEmbeddedImageObject(0, bbox)
            val body = testTextObjectBlob(testTextCommonWithImage(photo))
            val title = testTextObjectBlob(
                SdocxExport.textCommonBytes("T", Rgba(1, 2, 3, 255).toArgb(), 14f, false, false, false, false, 0))
            val pageUuid = UUID.randomUUID().toString()
            val entries = hashMapOf(
                "media/mediaInfo.dat" to testMediaInfo(listOf(Triple(0, "0@paste.png", png))),
                "media/0@paste.png" to png,
                "$pageUuid.page" to testEmptyPage(pageUuid),
                "pageIdInfo.dat" to testPageIdInfo(pageUuid),
                "note.note" to testNoteFile(title, body),
            )
            val parsed = SdocxParser.parseArchive(entries)
            assertEquals(1, parsed.pages.size)
            assertEquals(0, parsed.pages[0].images.size)
            assertEquals(1, parsed.bodyImages.size)
            assertEquals(0, parsed.bodyImages[0].mediaId)

            val imgDir = File(tmp, "img").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf").apply { mkdirs() }
            val doc = SdocxImport.toDocument(parsed, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(doc)
            // The empty page is reused, so no extra page is appended.
            assertEquals(1, doc!!.pages.size)
            val images = doc.pages[0].items.filterIsInstance<ImageItem>()
            assertEquals(1, images.size)
            // Stored size is kept; the flow bbox already fits the page.
            assertEquals(48.0, images[0].rect.x, 1e-9)
            assertEquals(90.0, images[0].rect.y, 1e-9)
            assertEquals(451.0, images[0].rect.w, 1e-9)
            assertEquals(300.0, images[0].rect.h, 1e-9)
            assertTrue(images[0].image.file.exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun importSyntheticVoice() {
        val out = ByteArrayOutputStream()
        val bbox = doubleArrayOf(100.0, 100.0, 400.0, 140.0)
        out.write(testBaseFrame(bbox))
        val flex = SdocxWriter()
        flex.writeI32(7) // media bind id
        flex.writeUtf16U16("Memo")
        flex.writeUtf16U16("01:02")
        flex.writeBytes(ByteArray(29)) // pill layout bytes (ignored on import)
        out.write(testFrame(10, byteArrayOf(0x00), byteArrayOf(0x1F, 0x03), ByteArray(0), flex.bytes()))
        val voice = SdocxParser.decodeVoice(out.toByteArray())
        assertNotNull(voice)
        assertEquals(7, voice!!.mediaId)
        assertEquals("Memo", voice.title)
        assertEquals(62000, voice.durationMs)

        val tmp = createTempDir("sdocx-voice")
        try {
            val audioBytes = ByteArray(64) { it.toByte() }
            val sdocx = SdocxDocument(
                pages = listOf(
                    SdocxPage(uuid = "v", width = 1440.0, height = 2036.0,
                        voices = listOf(voice)),
                ),
                mediaByBindId = mapOf(7 to SdocxMediaEntry(7, "7@note.m4a", audioBytes)),
                mediaByFileName = emptyMap(),
            )
            val imgDir = File(tmp, "img").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf").apply { mkdirs() }
            val doc = SdocxImport.toDocument(sdocx, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(doc)
            val audios = doc!!.pages[0].items.filterIsInstance<AudioItem>()
            assertEquals(1, audios.size)
            assertEquals("Memo", audios[0].name)
            assertEquals(62000, audios[0].audio.durationMs)
            assertTrue(audios[0].audio.file.exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun voiceDurationParsing() {
        assertEquals(62000, SdocxParser.parseVoiceDuration("01:02"))
        assertEquals(198000, SdocxParser.parseVoiceDuration("00:03:18"))
        assertEquals(3723000, SdocxParser.parseVoiceDuration("1:02:03"))
        assertEquals(0, SdocxParser.parseVoiceDuration(""))
        assertEquals(0, SdocxParser.parseVoiceDuration("abc"))
    }

    @Test fun sdocxIsRecognizedAsImportable() {
        assertEquals(com.xnotes.ui.ImportKind.SDOCX,
            com.xnotes.ui.ImportKind.classify("note.sdocx", ""))
        assertEquals(com.xnotes.ui.ImportKind.SDOCX,
            com.xnotes.ui.ImportKind.classify("NOTE.SDOCX", "application/octet-stream"))
        assertTrue(com.xnotes.ui.ImportKind.isKnownImport("note.sdocx", ""))
        assertTrue(com.xnotes.ui.ImportKind.isKnownImport("note.sdocx", "application/octet-stream"))
        assertTrue(com.xnotes.ui.ImportKind.isKnownImport("a.pdf", "application/pdf"))
        // Unknown binaries must not slip through as text notes.
        assertTrue(!com.xnotes.ui.ImportKind.isKnownImport("archive.zip", "application/octet-stream"))
        assertTrue(!com.xnotes.ui.ImportKind.isKnownImport("app.apk", "application/vnd.android.package-archive"))
        assertTrue(com.xnotes.core.util.ReaderKind.isSdocx("note.sdocx"))
        assertTrue(!com.xnotes.core.util.ReaderKind.isSdocx("note.pdf"))
        assertTrue(com.xnotes.core.util.ReaderKind.isListed("note.sdocx"))
        assertTrue(!com.xnotes.core.util.ReaderKind.isReadable("note.sdocx"))
    }

    @Test fun parseRealSamsungFileWhenPresent() {
        // Real-world fixture from the Saber fork (not in repo). Runs locally when
        // the reference checkout exists; skipped otherwise.
        val candidates = listOf(
            File("/tmp/opencode/notes-ref/test/Over_the_Horizon_261010_011234.sdocx"),
            File("/tmp/opencode/sformat/sdocxFiles/ThisIsTheTitle_251009_012211.sdocx"),
        )
        val file = candidates.firstOrNull { it.exists() }
        Assume.assumeTrue(file != null)
        val entries = HashMap<String, ByteArray>()
        ZipFile(file!!).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (!entry.isDirectory) entries[entry.name] = zf.getInputStream(entry).readBytes()
            }
        }
        val parsed = SdocxParser.parseArchive(entries)
        assertTrue("expected pages, got ${parsed.pages.size}", parsed.pages.isNotEmpty())
        val strokes = parsed.pages.sumOf { it.strokes.size }
        val images = parsed.pages.sumOf { it.images.size }
        val pdfs = parsed.pages.sumOf { it.pdfPapers.size }
        assertTrue("expected content (strokes=$strokes images=$images pdfs=$pdfs)", strokes + images + pdfs > 0)
        // String table should resolve at least one pen name on real files.
        assertTrue(parsed.stringTable.isNotEmpty() || parsed.pages.flatMap { it.strokes }.isNotEmpty())

        // Convert to xnotes without crashing.
        val tmp = createTempDir("sdocx-real")
        try {
            val imgDir = File(tmp, "img").apply { mkdirs() }
            val pdfDir = File(tmp, "pdf").apply { mkdirs() }
            val doc = SdocxImport.toDocument(parsed, 150, imgDir, pdfDir, fakeImages, fakeMeasurer)
            assertNotNull(doc)
            assertTrue(doc!!.pages.isNotEmpty())
            // The fixture's voice memo (media 1@*.m4a) imports as an audio pill.
            val voices = parsed.pages.sumOf { it.voices.size }
            if (voices > 0) {
                val audios = doc.pages.flatMap { it.items }.filterIsInstance<AudioItem>()
                assertEquals(voices, audios.size)
                assertTrue(audios[0].audio.file.exists())
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
