package com.xnotes.sdocx

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Regression tests for Samsung Notes (.sdocx) import: photos must survive
 * the import with their own bytes (no silent drops, no cross-placement
 * duplication), note-body photos must survive an unreadable title, and the
 * Samsung background (per-page template + paper tint, note-default template)
 * must map onto inheritable styles that stay editable afterwards.
 */
class SdocxImportImagesTest {

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

    // --- builders (mirror real Samsung layouts; cf. SdocxTest) ---

    private fun frame(kind: Int, props: ByteArray, fields: ByteArray, fixed: ByteArray, flex: ByteArray): ByteArray {
        val propSize = 1 + props.size
        val fieldSize = 1 + fields.size
        val headerSize = 2 + 4 + propSize + fieldSize
        val flexOffset = if (flex.isEmpty() && fields.all { it == 0.toByte() }) 0 else 4 + headerSize + fixed.size
        val size = 4 + headerSize + fixed.size + flex.size
        val w = SdocxWriter()
        w.writeU32(size.toLong())
        w.writeI16(kind)
        w.writeU32(flexOffset.toLong())
        w.writeU8(props.size); w.writeBytes(props)
        w.writeU8(fields.size); w.writeBytes(fields)
        w.writeBytes(fixed); w.writeBytes(flex)
        return w.bytes()
    }

    private fun baseFrame(bbox: DoubleArray): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(4000)
        fixed.writeUtf8U16(UUID.randomUUID().toString())
        fixed.writeI64(0)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeI32(0); fixed.writeU8(0)
        return frame(0, byteArrayOf(0xBF.toByte(), 0x01), byteArrayOf(0x00, 0x60, 0x00, 0x00), fixed.bytes(), ByteArray(0))
    }

    private fun shapeBaseFrame(): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(0); fixed.writeU32(4); fixed.writeU32(0); fixed.writeU8(0)
        return frame(6, byteArrayOf(0x00), byteArrayOf(0x00), fixed.bytes(), ByteArray(0))
    }

    private fun imageFillFlex(bindId: Int, fillLen: Int = 62): ByteArray {
        val fill = SdocxWriter()
        if (fillLen == 122) {
            // Alternate coedit-hash encoding: no main bind id inside.
            fill.writeBytes(ByteArray(122) { 0x41.toByte() })
        } else {
            fill.writeU8(0); fill.writeI32(bindId)
            for (i in 0 until 9) fill.writeF32(if (i in 6..7) 100f else 0f)
            fill.writeU8(0); repeat(4) { fill.writeI32(0) }; fill.writeI32(0)
        }
        val fillBytes = fill.bytes()
        check(fillBytes.size == fillLen)
        val flex = SdocxWriter()
        flex.writeU32(fillBytes.size.toLong()); flex.writeU8(2); flex.writeBytes(fillBytes)
        return flex.bytes()
    }

    private fun imageShapeFrame(bindId: Int, bbox: DoubleArray, fillLen: Int = 62): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(4)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeF32(0f); fixed.writeU32(0); fixed.writeU8(0)
        return frame(7, byteArrayOf(0x00), byteArrayOf(0x20, 0x00, 0x00, 0x00), fixed.bytes(), imageFillFlex(bindId, fillLen))
    }

    private fun imageObject(bindId: Int, bbox: DoubleArray, fillLen: Int = 62): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(baseFrame(bbox))
        out.write(shapeBaseFrame())
        out.write(imageShapeFrame(bindId, bbox, fillLen))
        out.write(frame(3, byteArrayOf(0x00), byteArrayOf(0x00, 0x00, 0x00, 0x00), ByteArray(0), ByteArray(0)))
        return out.toByteArray()
    }

    private fun wrapObj(type: Int, payload: ByteArray): ByteArray {
        val w = SdocxWriter()
        w.writeU8(type); w.writeU16(0); w.writeU32((payload.size + 32).toLong())
        w.writeBytes(payload); w.writeBytes(ByteArray(32))
        return w.bytes()
    }

    private fun pageWithBg(uuid: String, mask: Int, flex: ByteArray, w: Int = 1440, h: Int = 2036): ByteArray {
        val header = SdocxWriter()
        header.writeMask(SdocxMask(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        val fm = byteArrayOf((mask and 0xFF).toByte(), ((mask ushr 8) and 0xFF).toByte(),
            ((mask ushr 16) and 0xFF).toByte(), ((mask ushr 24) and 0xFF).toByte())
        header.writeMask(SdocxMask(fm))
        header.writeU32(0); header.writeU32(w.toLong()); header.writeU32(h.toLong())
        header.writeU32(0); header.writeU32(0)
        header.writeUtf16U16(uuid); header.writeI64(0)
        header.writeU32(5400); header.writeU32(5400)
        val propOff = 0x80
        val out = SdocxWriter()
        out.writeU32((propOff + flex.size).toLong()); out.writeU32(propOff.toLong())
        out.writeBytes(header.bytes()); out.writeBytes(flex)
        val layerId = UUID.randomUUID().toString()
        val extra = SdocxWriter().also { it.writeUtf16U16(layerId); it.writeI64(0) }.bytes()
        val layer = SdocxWriter()
        layer.writeU32((16 + extra.size).toLong()); layer.writeU32(0)
        layer.writeU8(1); layer.writeU8(0x02); layer.writeU8(1); layer.writeU8(0x18)
        layer.writeU32(0); layer.writeBytes(extra)
        layer.writeU32(0); layer.writeBytes(ByteArray(32))
        out.writeU16(1); out.writeU16(0); out.writeBytes(layer.bytes())
        return out.bytes()
    }

    /** Page flex holding bit4 mode, bit5 color, bit6 width, bit9 template type. */
    private fun bgFlex(color: Int? = null, type: Int? = null, mode: Int = 2, w: Int = 1440): ByteArray {
        val f = SdocxWriter()
        f.writeU32(mode.toLong())
        if (color != null) f.writeU32(color.toLong() and 0xFFFFFFFFL)
        f.writeU32(w.toLong())
        if (type != null) f.writeU32(type.toLong() and 0xFFFFFFFFL)
        return f.bytes()
    }

    private fun maskOf(vararg bits: Int): Int {
        var m = 0
        for (b in bits) m = m or (1 shl b)
        return m
    }

    private fun pageIdInfo(uuid: String): ByteArray {
        val w = SdocxWriter()
        w.writeBytes(ByteArray(32)); w.writeU16(1); w.writeUtf16U16(uuid); w.writeBytes(ByteArray(32))
        return w.bytes()
    }

    private fun mediaInfo(entries: List<Triple<Int, String, ByteArray>>): ByteArray {
        val w = SdocxWriter()
        w.writeU32(5500); w.writeU16(entries.size)
        for ((id, name, _) in entries) {
            val rec = SdocxWriter()
            rec.writeI32(id); rec.writeUtf16U16(name)
            rec.writeBytes(ByteArray(2)); rec.writeU16(1); rec.writeI64(0); rec.writeU8(1)
            val rb = rec.bytes(); w.writeU32(rb.size.toLong()); w.writeBytes(rb)
        }
        w.writeBytes(byteArrayOf(0x45, 0x4F, 0x46, 0x58))
        return w.bytes()
    }

    private fun fakeJpeg(seed: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01))
        out.write(ByteArray(100) { (seed * 31 + it).toByte() })
        return out.toByteArray()
    }

    private fun textCommonWithImage(imageObject: ByteArray): ByteArray {
        val span = SdocxWriter()
        span.writeU32(imageObject.size.toLong())
        span.writeU32(3)
        span.writeBytes(imageObject)
        span.writeU32(0); span.writeU32(0); span.writeU32(0)
        val spanBytes = span.bytes()
        val common = SdocxWriter()
        common.writeUtf16U32("￼")
        common.writeU32(0)
        common.writeU32(1)
        val para = SdocxWriter()
        para.writeU32(3); para.writeU32(0); para.writeU32(1); para.writeU32(2)
        val paraBytes = para.bytes()
        common.writeU16(paraBytes.size)
        common.writeBytes(paraBytes)
        common.writeF32(0f); common.writeF32(0f); common.writeF32(0f); common.writeF32(0f)
        common.writeU8(0)
        common.writeU16(0)
        common.writeU32(1); common.writeU32(0)
        common.writeU32(1)
        common.writeU32(spanBytes.size.toLong())
        common.writeBytes(spanBytes)
        val commonBytes = common.bytes()
        val flex = SdocxWriter()
        flex.writeU32(commonBytes.size.toLong())
        flex.writeBytes(commonBytes)
        return flex.bytes()
    }

    private fun textObjectBlob(textCommonFlex: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(baseFrame(doubleArrayOf(0.0, 0.0, 400.0, 300.0)))
        out.write(shapeBaseFrame())
        out.write(frame(7, byteArrayOf(0x00), byteArrayOf(0x01), ByteArray(0), textCommonFlex))
        return out.toByteArray()
    }

    private fun noteFile(titleBlob: ByteArray, bodyBlob: ByteArray): ByteArray {
        val w = SdocxWriter()
        w.writeU32(0)
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeU32(4000)
        w.writeUtf16U16("")
        w.writeBytes(ByteArray(4 + 8 + 8 + 4 + 4 + 4 + 4 + 4))
        w.writeU32(titleBlob.size.toLong())
        w.writeBytes(titleBlob)
        w.writeU32(bodyBlob.size.toLong())
        w.writeBytes(bodyBlob)
        return w.bytes()
    }

    private fun createTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "x")
        f.delete(); f.mkdirs()
        return f
    }

    // --- image survival ---

    @Test fun multiplePhotosKeepDistinctBytes() {
        val b1 = doubleArrayOf(10.0, 10.0, 410.0, 310.0)
        val b2 = doubleArrayOf(500.0, 500.0, 900.0, 800.0)
        val j1 = fakeJpeg(1); val j2 = fakeJpeg(2)
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(5, "5@a.jpg", j1), Triple(9, "9@b.jpg", j2))),
            "media/5@a.jpg" to j1,
            "media/9@b.jpg" to j2,
            "$uuid.page" to pageWithObjects(uuid, maskOf(4, 5, 6), bgFlex(color = 0xFFFCFCFC.toInt()), listOf(
                wrapObj(3, imageObject(5, b1)), wrapObj(3, imageObject(9, b2)))),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to ByteArray(0),
        )
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(1, parsed.pages.size)
        assertEquals(2, parsed.pages[0].images.size)
        val tmp = createTempDir("sdocx-multiimg")
        try {
            val doc = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val imgs = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>()
            assertEquals(2, imgs.size)
            val bodies = imgs.map { it.image.file.readBytes() }
            assertTrue("first photo keeps its bytes", bodies.any { it.contentEquals(j1) })
            assertTrue("second photo keeps its bytes", bodies.any { it.contentEquals(j2) })
        } finally { tmp.deleteRecursively() }
    }

    @Test fun unresolvablePhotoKeepsItsOwnBytes() {
        // A 122-byte coedit-hash fill carries no bind id; the photo must still
        // show its own media, not duplicate the first asset.
        val b1 = doubleArrayOf(10.0, 10.0, 410.0, 310.0)
        val b2 = doubleArrayOf(500.0, 500.0, 900.0, 800.0)
        val j1 = fakeJpeg(1); val j2 = fakeJpeg(2)
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(5, "5@a.jpg", j1), Triple(9, "9@b.jpg", j2))),
            "media/5@a.jpg" to j1,
            "media/9@b.jpg" to j2,
            "$uuid.page" to pageWithObjects(uuid, maskOf(4, 5, 6), bgFlex(color = 0xFFFCFCFC.toInt()), listOf(
                wrapObj(3, imageObject(5, b1)), wrapObj(3, imageObject(9, b2, fillLen = 122)))),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to ByteArray(0),
        )
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(listOf(5, null), parsed.pages[0].images.map { it.mediaId })
        val tmp = createTempDir("sdocx-coeditimg")
        try {
            val doc = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val imgs = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>()
            assertEquals(2, imgs.size)
            val bodies = imgs.map { it.image.file.readBytes() }
            assertTrue("first photo keeps its bytes", bodies.any { it.contentEquals(j1) })
            assertTrue("hash-fill photo keeps its own bytes", bodies.any { it.contentEquals(j2) })
        } finally { tmp.deleteRecursively() }
    }

    @Test fun bodyPhotoSurvivesUnreadableTitle() {
        // An empty title blob must not discard a valid body photo.
        val bbox = doubleArrayOf(48.0, 90.0, 499.0, 390.0)
        val photo = imageObject(7, bbox)
        val body = textObjectBlob(textCommonWithImage(photo))
        val png = fakeJpeg(7)
        val pageUuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(7, "7@photo.jpg", png))),
            "media/7@photo.jpg" to png,
            "$pageUuid.page" to pageWithObjects(pageUuid, maskOf(4, 5, 6), bgFlex(color = 0xFFFCFCFC.toInt()), emptyList()),
            "pageIdInfo.dat" to pageIdInfo(pageUuid),
            "note.note" to noteFile(ByteArray(0), body),
        )
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(1, parsed.bodyImages.size)
        assertEquals(7, parsed.bodyImages[0].mediaId)
        val tmp = createTempDir("sdocx-bodyimgtitle")
        try {
            val doc = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val imgs = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>()
            assertEquals(1, imgs.size)
            assertTrue(imgs[0].image.file.readBytes().contentEquals(png))
        } finally { tmp.deleteRecursively() }
    }

    // --- background import ---

    @Test fun perPageGridAndPaperPreserved() {
        val yellow = 0xFFFFFF00.toInt()
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "$uuid.page" to pageWithBg(uuid, maskOf(4, 5, 6, 9), bgFlex(color = yellow, type = 5)),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to ByteArray(0),
        )
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(5, parsed.pages[0].templateType)
        val tmp = createTempDir("sdocx-bgpage")
        try {
            val doc = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            assertEquals(PagePattern.GRID.id, doc.pages[0].style.template)
            assertEquals(yellow, doc.pages[0].style.pageColor?.toArgb())
            assertNull(doc.style.template)
        } finally { tmp.deleteRecursively() }
    }

    @Test fun noteDefaultTemplateInheritedByPlainPages() {
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "$uuid.page" to pageWithBg(uuid, maskOf(4, 5, 6), bgFlex(color = 0xFFFCFCFC.toInt())),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to ByteArray(0),
        )
        val base = SdocxParser.parseArchive(entries)
        val parsed = SdocxDocument(base.pages, base.mediaByBindId, base.mediaByFileName,
            title = null, defaultTemplateUri = "template://grid")
        val tmp = createTempDir("sdocx-bgdefault")
        try {
            val doc = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            assertEquals(PagePattern.GRID.id, doc.style.template)
            assertNull(doc.pages[0].style.template)
            assertNull(doc.pages[0].style.pageColor)
            assertEquals(PagePattern.GRID.id, doc.pages[0].resolvedTemplate(doc))
        } finally { tmp.deleteRecursively() }
    }

    @Test fun explicitBlankStaysPlain() {
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "$uuid.page" to pageWithBg(uuid, maskOf(4, 5, 6, 9), bgFlex(color = 0xFFFCFCFC.toInt(), type = 0)),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to ByteArray(0),
        )
        val base = SdocxParser.parseArchive(entries)
        val parsed = SdocxDocument(base.pages, base.mediaByBindId, base.mediaByFileName,
            title = null, defaultTemplateUri = "template://grid")
        // Give the page content so it is kept (empty shells are dropped).
        val kept = parsed.pages.map {
            SdocxPage(it.uuid, it.width, it.height, backgroundColorArgb = it.backgroundColorArgb,
                templateType = it.templateType, templateUri = it.templateUri,
                strokes = listOf(SdocxStroke(
                    points = listOf(SdocxPoint(10.0, 10.0), SdocxPoint(100.0, 100.0)),
                    pressures = listOf(0.8, 0.8), colorArgb = -16777216, penWidth = 3.0,
                    bbox = SdocxRect(10.0, 10.0, 100.0, 100.0),
                    penName = null, penNameId = null, advancedSettingId = null, toolTypeRaw = 1,
                )))
        }
        val doc = SdocxDocument(kept, parsed.mediaByBindId, parsed.mediaByFileName,
            title = null, defaultTemplateUri = "template://grid")
        val tmp = createTempDir("sdocx-bgblank")
        try {
            val out = SdocxImport.toDocument(doc, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            assertEquals(PagePattern.NONE.id, out.pages[0].style.template)
            assertEquals(PagePattern.NONE.id, out.pages[0].resolvedTemplate(out))
        } finally { tmp.deleteRecursively() }
    }

    @Test fun exportGridYellowRoundtrip() {
        val doc = Document(dpi = 150)
        doc.pages.add(Page(1440.0, 2036.0,
            style = PageStyle(pageColor = Rgba(255, 255, 0, 255), template = PagePattern.GRID.id)))
        val out = ByteArrayOutputStream()
        SdocxExport.writeDocument(doc, out)
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(java.io.ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(4, parsed.pages[0].templateType)
        assertEquals(Rgba(255, 255, 0, 255).toArgb(), parsed.pages[0].backgroundColorArgb)
        val tmp = createTempDir("sdocx-bgrt")
        try {
            val back = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            assertEquals(PagePattern.GRID.id, back!!.pages[0].style.template)
            assertEquals(Rgba(255, 255, 0, 255), back.pages[0].style.pageColor)
        } finally { tmp.deleteRecursively() }
    }

    // --- page builder with explicit object list ---

    private fun pageWithObjects(uuid: String, mask: Int, flex: ByteArray, objs: List<ByteArray>, w: Int = 1440, h: Int = 2036): ByteArray {
        val header = SdocxWriter()
        header.writeMask(SdocxMask(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        val fm = byteArrayOf((mask and 0xFF).toByte(), ((mask ushr 8) and 0xFF).toByte(),
            ((mask ushr 16) and 0xFF).toByte(), ((mask ushr 24) and 0xFF).toByte())
        header.writeMask(SdocxMask(fm))
        header.writeU32(0); header.writeU32(w.toLong()); header.writeU32(h.toLong())
        header.writeU32(0); header.writeU32(0)
        header.writeUtf16U16(uuid); header.writeI64(0)
        header.writeU32(5400); header.writeU32(5400)
        val propOff = 0x80
        val out = SdocxWriter()
        out.writeU32((propOff + flex.size).toLong()); out.writeU32(propOff.toLong())
        out.writeBytes(header.bytes()); out.writeBytes(flex)
        val layerId = UUID.randomUUID().toString()
        val extra = SdocxWriter().also { it.writeUtf16U16(layerId); it.writeI64(0) }.bytes()
        val layer = SdocxWriter()
        layer.writeU32((16 + extra.size).toLong()); layer.writeU32(0)
        layer.writeU8(1); layer.writeU8(0x02); layer.writeU8(1); layer.writeU8(0x18)
        layer.writeU32(0); layer.writeBytes(extra)
        layer.writeU32(objs.size.toLong())
        for (o in objs) layer.writeBytes(o)
        layer.writeBytes(ByteArray(32))
        out.writeU16(1); out.writeU16(0); out.writeBytes(layer.bytes())
        return out.bytes()
    }

    private fun bgFlexNoTemplate(): ByteArray {
        val f = SdocxWriter()
        f.writeU32(2); f.writeU32(0xFFFCFCFC); f.writeU32(1440)
        return f.bytes()
    }
}
