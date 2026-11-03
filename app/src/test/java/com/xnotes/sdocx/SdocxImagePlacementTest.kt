package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Image placement across the Samsung Notes boundary, both directions:
 * - note-body photos (object spans with a short tail, as real writers emit)
 *   decode and land on their mapped page at their stored size — not on extra
 *   pages at a fitted origin;
 * - free rotation survives import (page objects) and export (shape-fixed
 *   rotation round-trips back through import).
 */
class SdocxImagePlacementTest {

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

    // --- builders (mirror real Samsung layouts) ---

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

    private fun imageFillFlex(bindId: Int): ByteArray {
        val fill = SdocxWriter()
        fill.writeU8(0); fill.writeI32(bindId)
        for (i in 0 until 9) fill.writeF32(if (i in 6..7) 100f else 0f)
        fill.writeU8(0); repeat(4) { fill.writeI32(0) }; fill.writeI32(0)
        val fillBytes = fill.bytes()
        val flex = SdocxWriter()
        flex.writeU32(fillBytes.size.toLong()); flex.writeU8(2); flex.writeBytes(fillBytes)
        return flex.bytes()
    }

    private fun imageShapeFrame(bindId: Int, bbox: DoubleArray, rotationDeg: Float = 0f): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(4)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeF32(rotationDeg); fixed.writeU32(0); fixed.writeU8(0)
        return frame(7, byteArrayOf(0x00), byteArrayOf(0x20, 0x00, 0x00, 0x00), fixed.bytes(), imageFillFlex(bindId))
    }

    private fun imageObject(bindId: Int, bbox: DoubleArray, rotationDeg: Float = 0f): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(baseFrame(bbox))
        out.write(shapeBaseFrame())
        out.write(imageShapeFrame(bindId, bbox, rotationDeg))
        out.write(frame(3, byteArrayOf(0x00), byteArrayOf(0x00, 0x00, 0x00, 0x00), ByteArray(0), ByteArray(0)))
        return out.toByteArray()
    }

    private fun wrapObj(type: Int, payload: ByteArray): ByteArray {
        val w = SdocxWriter()
        w.writeU8(type); w.writeU16(0); w.writeU32((payload.size + 32).toLong())
        w.writeBytes(payload); w.writeBytes(ByteArray(32))
        return w.bytes()
    }

    /** Object span with the short 4-byte tail (text index only) real writers emit. */
    private fun shortTailSpan(imageObject: ByteArray, textIndex: Int): ByteArray {
        val span = SdocxWriter()
        span.writeU32(imageObject.size.toLong())
        span.writeU32(3)
        span.writeBytes(imageObject)
        span.writeI32(textIndex)
        return span.bytes()
    }

    private fun textCommonWithShortTailSpans(imageObjects: List<Pair<ByteArray, Int>>): ByteArray {
        val common = SdocxWriter()
        // One object-replacement char per image plus some text.
        val text = imageObjects.map { "￼" }.joinToString("") + "Text"
        common.writeUtf16U32(text)
        common.writeU32(0) // spans
        common.writeU32(1) // one paragraph
        val para = SdocxWriter()
        para.writeU32(3); para.writeU32(0); para.writeU32(1)
        val paraBytes = para.bytes()
        common.writeU16(paraBytes.size)
        common.writeBytes(paraBytes)
        common.writeF32(0f); common.writeF32(0f); common.writeF32(0f); common.writeF32(0f)
        common.writeU8(0)
        common.writeU16(0)
        common.writeU32(1); common.writeU32(0) // object span flags: present
        common.writeU32(imageObjects.size.toLong())
        for ((obj, idx) in imageObjects) {
            val span = shortTailSpan(obj, idx)
            common.writeU32(span.size.toLong())
            common.writeBytes(span)
        }
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
        // dims=40 layout: skip, masks, dims, title size/blob, body size/blob.
        w.writeU32(0)
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeMask(SdocxMask(byteArrayOf(0x00)))
        w.writeU32(4000)
        w.writeUtf16U16("")
        w.writeBytes(ByteArray(40))
        w.writeU32(titleBlob.size.toLong())
        w.writeBytes(titleBlob)
        w.writeU32(bodyBlob.size.toLong())
        w.writeBytes(bodyBlob)
        return w.bytes()
    }

    private fun emptyTitleBlob(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(baseFrame(doubleArrayOf(0.0, 0.0, 400.0, 300.0)))
        out.write(shapeBaseFrame())
        val common = SdocxWriter()
        common.writeUtf16U32("")
        common.writeU32(0); common.writeU32(0)
        common.writeF32(0f); common.writeF32(0f); common.writeF32(0f); common.writeF32(0f)
        common.writeU8(0); common.writeU16(0); common.writeU32(0); common.writeU32(0)
        val commonBytes = common.bytes()
        val flex = SdocxWriter()
        flex.writeU32(commonBytes.size.toLong()); flex.writeBytes(commonBytes)
        out.write(frame(7, byteArrayOf(0x00), byteArrayOf(0x01), ByteArray(0), flex.bytes()))
        return out.toByteArray()
    }

    private fun buildPageWithLayer(uuid: String, mask: Int, flex: ByteArray, objs: List<ByteArray>, w: Int = 1440, h: Int = 2036): ByteArray {
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

    private fun bgFlex(color: Int = 0xFFFCFCFC.toInt()): ByteArray {
        val f = SdocxWriter()
        f.writeU32(2); f.writeU32(color.toLong() and 0xFFFFFFFFL); f.writeU32(1440)
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

    private fun createTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "x")
        f.delete(); f.mkdirs()
        return f
    }

    private fun importDoc(entries: Map<String, ByteArray>): Document? {
        val parsed = SdocxParser.parseArchive(entries)
        val tmp = createTempDir("sdocx-place")
        try {
            return SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)
        } finally {
            tmp.deleteRecursively()
        }
    }

    // --- tests ---

    @Test fun shortTailBodyPhotoDecodesWithItsBbox() {
        val bbox = doubleArrayOf(64.0, 882.0, 595.0, 1722.0)
        val png = fakeJpeg(3)
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(7, "7@photo.jpg", png))),
            "media/7@photo.jpg" to png,
            "note.note" to noteFile(emptyTitleBlob(),
                textObjectBlob(textCommonWithShortTailSpans(listOf(imageObject(7, bbox) to 0)))),
        )
        // A page entry must exist for the archive to parse; body images are
        // independent of it.
        val uuid = UUID.randomUUID().toString()
        entries["$uuid.page"] = buildPageWithLayer(uuid, maskOf(4, 5, 6), bgFlex(), emptyList())
        entries["pageIdInfo.dat"] = pageIdInfo(uuid)
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(1, parsed.bodyImages.size)
        val im = parsed.bodyImages[0]
        assertEquals(7, im.mediaId)
        assertEquals(64.0, im.bbox.left, 1e-9)
        assertEquals(882.0, im.bbox.top, 1e-9)
        assertEquals(595.0, im.bbox.right, 1e-9)
        assertEquals(1722.0, im.bbox.bottom, 1e-9)
    }

    @Test fun bodyPhotoLandsOnItsPageAtStoredSize() {
        val bbox = doubleArrayOf(64.0, 882.0, 595.0, 1722.0) // 531 x 840
        val png = fakeJpeg(3)
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(7, "7@photo.jpg", png))),
            "media/7@photo.jpg" to png,
            "$uuid.page" to buildPageWithLayer(uuid, maskOf(4, 5, 6), bgFlex(), emptyList()),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to noteFile(emptyTitleBlob(),
                textObjectBlob(textCommonWithShortTailSpans(listOf(imageObject(7, bbox) to 0)))),
        )
        val doc = importDoc(entries)!!
        // No extra pages: the single shell page is dropped, one page holds it.
        assertEquals(1, doc.pages.size)
        val imgs = doc.pages[0].items.filterIsInstance<ImageItem>()
        assertEquals(1, imgs.size)
        assertEquals(64.0, imgs[0].rect.x, 1e-9)
        assertEquals(882.0, imgs[0].rect.y, 1e-9)
        assertEquals(531.0, imgs[0].rect.w, 1e-9)
        assertEquals(840.0, imgs[0].rect.h, 1e-9)
    }

    @Test fun bodyPhotoBelowFirstPageMapsToLaterPage() {
        // top = 2500 with pageH 2036 -> page 1 at local top 464.
        val bbox = doubleArrayOf(100.0, 2500.0, 500.0, 2900.0)
        val png = fakeJpeg(4)
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(7, "7@photo.jpg", png))),
            "media/7@photo.jpg" to png,
            "$uuid.page" to buildPageWithLayer(uuid, maskOf(4, 5, 6), bgFlex(), emptyList()),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to noteFile(emptyTitleBlob(),
                textObjectBlob(textCommonWithShortTailSpans(listOf(imageObject(7, bbox) to 0)))),
        )
        val doc = importDoc(entries)!!
        assertEquals(2, doc.pages.size)
        assertTrue(doc.pages[0].items.none { it is ImageItem })
        val imgs = doc.pages[1].items.filterIsInstance<ImageItem>()
        assertEquals(1, imgs.size)
        assertEquals(100.0, imgs[0].rect.x, 1e-9)
        assertEquals(464.0, imgs[0].rect.y, 1e-9)
        assertEquals(400.0, imgs[0].rect.w, 1e-9)
        assertEquals(400.0, imgs[0].rect.h, 1e-9)
    }

    @Test fun pageImageRotationSurvivesImport() {
        val bbox = doubleArrayOf(100.0, 200.0, 500.0, 600.0)
        val png = fakeJpeg(5)
        val uuid = UUID.randomUUID().toString()
        val entries = hashMapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(5, "5@a.jpg", png))),
            "media/5@a.jpg" to png,
            "$uuid.page" to buildPageWithLayer(uuid, maskOf(4, 5, 6), bgFlex(), listOf(
                wrapObj(3, imageObject(5, bbox, rotationDeg = 90f)))),
            "pageIdInfo.dat" to pageIdInfo(uuid),
            "note.note" to ByteArray(0),
        )
        val parsed = SdocxParser.parseArchive(entries)
        assertEquals(1, parsed.pages[0].images.size)
        assertEquals(90.0, parsed.pages[0].images[0].rotationDegrees ?: Double.NaN, 1e-6)
        val doc = importDoc(entries)!!
        val img = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>().single()
        assertEquals(Rect(100.0, 200.0, 400.0, 400.0), img.rect)
        assertEquals(Math.PI / 2.0, img.angle, 1e-9)
    }

    @Test fun exportedImageRotationRoundTrips() {
        val imgFile = File.createTempFile("rot", ".png").apply { writeBytes(fakeJpeg(9)); deleteOnExit() }
        val doc = Document(dpi = 150)
        val page = Page(1440.0, 2036.0)
        val item = ImageItem(ImageData(imgFile, 200, 100), Rect(100.0, 200.0, 400.0, 200.0))
        item.angle = Math.PI / 2.0
        page.items.add(item)
        doc.pages.add(page)
        val out = ByteArrayOutputStream()
        SdocxExport.writeDocument(doc, out)
        val back = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out.toByteArray())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) back[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val parsed = SdocxParser.parseArchive(back)
        assertEquals(1, parsed.pages.size)
        assertEquals(1, parsed.pages[0].images.size)
        assertEquals(90.0, parsed.pages[0].images[0].rotationDegrees ?: Double.NaN, 1e-3)
        val mediaId = parsed.pages[0].images[0].mediaId
        assertNotNull(mediaId)
        val tmp = createTempDir("sdocx-rot")
        try {
            val docBack = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val imgBack = docBack.pages.flatMap { it.items }.filterIsInstance<ImageItem>().single()
            assertEquals(Rect(100.0, 200.0, 400.0, 200.0), imgBack.rect)
            assertEquals(Math.PI / 2.0, imgBack.angle, 1e-6)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun realRecordedNotePhotosSpreadAcrossPagesAtStoredBboxes() {
        val src = File("src/test/resources/sdocx/note-recorded.sdocx")
        if (!src.exists()) return // resource absent off-device; synthetic tests cover the fix
        val entries = HashMap<String, ByteArray>()
        java.util.zip.ZipFile(src).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val en = e.nextElement()
                if (!en.isDirectory) {
                    try { entries[en.name] = zf.getInputStream(en).readBytes() } catch (_: Exception) {}
                }
            }
        }
        val doc = importDoc(entries)!!
        // Empty shells are dropped by design (2 kept pages); colliding body
        // photos spread across following pages instead of stacking on page 0.
        assertEquals(2, doc.pages.size)
        val page0imgs = doc.pages[0].items.filterIsInstance<ImageItem>()
        val page1imgs = doc.pages[1].items.filterIsInstance<ImageItem>()
        assertEquals(1, page0imgs.size)
        assertEquals(1, page1imgs.size)
        // Stored bboxes, at stored size (not the 40,40 fallback origin).
        val r0 = page0imgs[0].rect
        assertEquals(64.0, r0.x, 1e-6)
        assertEquals(882.40087890625, r0.y, 1e-6)
        assertEquals(531.4586791992188, r0.w, 1e-6)
        assertEquals(840.0, r0.h, 0.01)
        val r1 = page1imgs[0].rect
        assertEquals(64.0, r1.x, 1e-6)
        assertEquals(623.201416015625, r1.y, 1e-6)
        assertEquals(840.0, r1.w, 1e-6)
        assertEquals(472.5, r1.h, 0.01)
        // No fallback-placed photos anywhere: every photo kept its storage.
        for (i in doc.pages.indices) {
            val orphans = doc.pages[i].items.filterIsInstance<ImageItem>()
                .filter { it.rect.x == 40.0 && it.rect.y == 40.0 }
            assertTrue("page $i has no fallback-placed photo", orphans.isEmpty())
        }
    }
}
