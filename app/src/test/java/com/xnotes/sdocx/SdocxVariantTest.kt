package com.xnotes.sdocx

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.pal.*
import com.xnotes.core.tools.Tool
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Regression tests for Samsung Notes (.sdocx) exchange, covering the
 * reported failure modes with synthetic payloads that mirror real Samsung
 * writers (v4000 base frames, 2/4-byte masks, image fills, voice memos,
 * page tables, sticker attach blocks) plus degenerate inputs real files
 * contain (zero-area body bboxes, missing placements, `.dat` audio names).
 */
class SdocxVariantTest {

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
        val flex = SdocxWriter().also { it.writeI64(0); it.writeU32(1440); it.writeU32(2036) }.bytes()
        return frame(0, byteArrayOf(0xBF.toByte(), 0x01), byteArrayOf(0x00, 0x60, 0x00, 0x00), fixed.bytes(), flex)
    }

    private fun shapeBaseFrame(): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(0); fixed.writeU32(4); fixed.writeU32(0); fixed.writeU8(0)
        return frame(6, byteArrayOf(0x00), byteArrayOf(0x0C), fixed.bytes(), ByteArray(39))
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

    private fun imageShapeFrame(bindId: Int, bbox: DoubleArray): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(4)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeF32(0f); fixed.writeU32(0); fixed.writeU8(0)
        return frame(7, byteArrayOf(0x00), byteArrayOf(0x20, 0x00, 0x00, 0x00), fixed.bytes(), imageFillFlex(bindId))
    }

    private fun wrapObj(type: Int, vararg frames: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        for (f in frames) payload.write(f)
        val p = payload.toByteArray()
        val w = SdocxWriter()
        w.writeU8(type); w.writeU16(0); w.writeU32((p.size + 32).toLong())
        w.writeBytes(p); w.writeBytes(ByteArray(32))
        return w.bytes()
    }

    private fun pageOf(uuid: String, objs: List<ByteArray>): ByteArray {
        val header = SdocxWriter()
        header.writeMask(SdocxMask(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        header.writeMask(SdocxMask(byteArrayOf(0x70, 0x00, 0x00, 0x00)))
        header.writeU32(0); header.writeU32(1440); header.writeU32(2036)
        header.writeU32(0); header.writeU32(0)
        header.writeUtf16U16(uuid); header.writeI64(0)
        header.writeU32(5400); header.writeU32(5400)
        val flex = SdocxWriter()
        flex.writeU32(2); flex.writeU32(0xFFFCFCFC); flex.writeU32(1440)
        val propOff = 0x80
        val out = SdocxWriter()
        out.writeU32((propOff + flex.size()).toLong()); out.writeU32(propOff.toLong())
        out.writeBytes(header.bytes()); out.writeBytes(flex.bytes())
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

    private fun createTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete(); f.mkdirs()
        return f
    }

    // --- image variants ---

    @Test fun imageAsShapeFillImports() {
        // Photos stored as shapes with a bitmap fill (not type-3 objects).
        val bbox = doubleArrayOf(50.0, 60.0, 450.0, 360.0)
        val payload = ByteArrayOutputStream().also {
            it.write(baseFrame(bbox))
            it.write(shapeBaseFrame())
            it.write(imageShapeFrame(5, bbox))
        }.toByteArray()
        val pageUuid = UUID.randomUUID().toString()
        val parsed = SdocxParser.parseArchive(mapOf(
            "media/mediaInfo.dat" to mediaInfo(listOf(Triple(5, "5@photo.jpg", tinyPng()))),
            "media/5@photo.jpg" to tinyPng(),
            "$pageUuid.page" to pageOf(pageUuid, listOf(wrapObj(7, payload))),
            "pageIdInfo.dat" to pageIdInfo(pageUuid),
            "note.note" to ByteArray(0),
        ))
        assertEquals(1, parsed.pages[0].images.size)
        assertEquals(5, parsed.pages[0].images[0].mediaId)
        val tmp = createTempDir("sdocx-varimg")
        try {
            val doc = SdocxImport.toDocument(parsed, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val imgs = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>()
            assertEquals(1, imgs.size)
            assertTrue(imgs[0].image.file.exists())
        } finally { tmp.deleteRecursively() }
    }

    @Test fun degenerateBodyImageImports() {
        // Pasted photos with zero-area text-flow bboxes must not vanish.
        val sdocx = SdocxDocument(
            pages = listOf(SdocxPage(uuid = "p", width = 1440.0, height = 2036.0)),
            mediaByBindId = mapOf(0 to SdocxMediaEntry(0, "0@paste.png", tinyPng())),
            mediaByFileName = emptyMap(),
            bodyImages = listOf(SdocxImage(SdocxRect(0.0, 0.0, 0.0, 0.0), 0, null)),
        )
        val tmp = createTempDir("sdocx-varbody")
        try {
            val doc = SdocxImport.toDocument(sdocx, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val imgs = doc.pages.flatMap { it.items }.filterIsInstance<ImageItem>()
            assertEquals(1, imgs.size)
            assertTrue(imgs[0].rect.w > 0 && imgs[0].rect.h > 0)
            assertTrue(imgs[0].image.file.exists())
        } finally { tmp.deleteRecursively() }
    }

    // --- audio variants ---

    @Test fun voiceMemoDatNameImports() {
        // Older writers name voice memos .dat; contents still sniff as audio.
        val bbox = doubleArrayOf(100.0, 100.0, 400.0, 140.0)
        val flex = SdocxWriter()
        flex.writeI32(9); flex.writeUtf16U16("Memo"); flex.writeUtf16U16("01:02"); flex.writeBytes(ByteArray(29))
        val payload = baseFrame(bbox) + frame(10, byteArrayOf(0x00), byteArrayOf(0x1F, 0x03), ByteArray(0), flex.bytes())
        val voice = SdocxParser.decodeVoice(payload)
        assertNotNull(voice)
        val sdocx = SdocxDocument(
            pages = listOf(SdocxPage(uuid = "v", width = 1440.0, height = 2036.0, voices = listOf(voice!!))),
            mediaByBindId = mapOf(9 to SdocxMediaEntry(9, "9@recording.dat", fakeM4a())),
            mediaByFileName = emptyMap(),
        )
        val tmp = createTempDir("sdocx-vardat")
        try {
            val doc = SdocxImport.toDocument(sdocx, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val aud = doc.pages.flatMap { it.items }.filterIsInstance<AudioItem>()
            assertEquals(1, aud.size)
            assertTrue(aud[0].audio.file.exists())
            assertEquals("Memo", aud[0].name)
        } finally { tmp.deleteRecursively() }
    }

    // --- page table with values ---

    private fun cellDirect(col: Int, text: String): ByteArray {
        val tb = text.toByteArray(Charsets.UTF_16LE)
        val body = SdocxWriter()
        body.writeU32(text.length.toLong()); body.writeBytes(tb)
        val fixed = SdocxWriter()
        fixed.writeU32(col.toLong()); fixed.writeU32(1); fixed.writeU32(1); fixed.writeU32(0xFFFFFFFFL)
        fixed.writeBytes(ByteArray(32)); fixed.writeU8(1)
        fixed.writeU32(body.size().toLong()); fixed.writeBytes(body.bytes())
        val rec = SdocxWriter()
        rec.writeU32(0)
        rec.writeMask(SdocxMask(byteArrayOf(0x00)))
        rec.writeMask(SdocxMask(byteArrayOf(0x00)))
        rec.writeBytes(fixed.bytes())
        return rec.bytes()
    }

    private fun rowBytes(height: Float, cells: List<ByteArray>): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeF32(height); fixed.writeU32(0); fixed.writeU32(cells.size.toLong())
        for (c in cells) { fixed.writeU32(c.size.toLong()); fixed.writeBytes(c) }
        val rec = SdocxWriter()
        rec.writeU32(0)
        rec.writeMask(SdocxMask(byteArrayOf(0x00)))
        rec.writeMask(SdocxMask(byteArrayOf(0x00)))
        rec.writeBytes(fixed.bytes())
        return rec.bytes()
    }

    @Test fun pageTableKeepsValues() {
        val rows = listOf(
            rowBytes(50f, listOf(cellDirect(0, "X"), cellDirect(1, "Y"))),
            rowBytes(50f, listOf(cellDirect(0, "Z"))),
        )
        val flex = SdocxWriter()
        flex.writeU32(2); flex.writeF32(200f); flex.writeF32(200f)
        flex.writeU32(rows.size.toLong())
        for (r in rows) { flex.writeU32(r.size.toLong()); flex.writeBytes(r) }
        for (v in doubleArrayOf(40.0, 40.0, 440.0, 140.0)) flex.writeF64(v)
        val bbox = doubleArrayOf(40.0, 40.0, 440.0, 140.0)
        val payload = baseFrame(bbox) + frame(22, byteArrayOf(0x00), byteArrayOf(0x1C), ByteArray(0), flex.bytes())
        val t = SdocxParser.decodeTable(payload)
        assertNotNull(t)
        assertTrue(t!!.rows.flatMap { r -> r.cells }.map { it.text }.containsAll(listOf("X", "Y", "Z")))
        val tmp = createTempDir("sdocx-vartable")
        try {
            val sdocx = SdocxDocument(
                pages = listOf(SdocxPage(uuid = "t", width = 1440.0, height = 2036.0, tables = listOf(t))),
                mediaByBindId = emptyMap(), mediaByFileName = emptyMap(),
            )
            val doc = SdocxImport.toDocument(sdocx, 150,
                File(tmp, "i").apply { mkdirs() }, File(tmp, "p").apply { mkdirs() }, fakeImages, fakeMeasurer)!!
            val tables = doc.pages.flatMap { it.items }.filterIsInstance<TableItem>()
            assertEquals(1, tables.size)
            assertTrue(tables[0].cellGrid().flatten().containsAll(listOf("X", "Y", "Z")))
        } finally { tmp.deleteRecursively() }
    }

    // --- sticker placement parsing ---

    private fun stickerFlex(bindId: Int, entries: List<Pair<String, String>>): ByteArray {
        val w = SdocxWriter()
        w.writeU32(1); w.writeU32(1); w.writeU32(0)
        for ((k, v) in entries) {
            val kb = k.toByteArray(Charsets.US_ASCII)
            val vb = v.toByteArray(Charsets.US_ASCII)
            if (k == "co_attach_file") {
                w.writeBytes(kb)
                w.writeI32(bindId); w.writeU32(entries.size.toLong() - 1)
            } else {
                w.writeU32(kb.size.toLong()); w.writeBytes(kb)
                w.writeU32(vb.size.toLong()); w.writeBytes(vb)
            }
        }
        return w.bytes()
    }

    @Test fun stickerFlexWithoutPlacementFallsBack() {
        // Attach block without collapse rect / color: bind id still parsed,
        // placement stays null so import uses the legacy fallback page.
        val flex = stickerFlex(10, listOf("co_attach_file" to "", "other_key" to "1"))
        val stickers = SdocxParser.parseStickerAttachments(flex)
        assertEquals(1, stickers.size)
        assertEquals(10, stickers[0].bindId)
        assertNull(stickers[0].collapseRect)
        assertNull(stickers[0].bgColorArgb)
    }

    @Test fun stickerFlexCorruptYieldsNothing() {
        assertTrue(SdocxParser.parseStickerAttachments(ByteArray(0)).isEmpty())
        assertTrue(SdocxParser.parseStickerAttachments(byteArrayOf(1, 2, 3)).isEmpty())
        // Truncated entry: declared but missing bytes.
        val w = SdocxWriter()
        w.writeU32(1); w.writeU32(1); w.writeU32(0)
        w.writeBytes("co_attach_file".toByteArray(Charsets.US_ASCII))
        w.writeI32(10); w.writeU32(5)
        assertTrue(SdocxParser.parseStickerAttachments(w.bytes()).isEmpty())
    }

    // --- Samsung page size ---

    @Test fun samsungPageSizeMatches() {
        val (w, h) = PageSize.SAMSUNG_NOTES.pixels(Orientation.PORTRAIT, 150)
        assertTrue("w=$w", kotlin.math.abs(w - 1440.0) < 0.01)
        assertTrue("h=$h", kotlin.math.abs(h - 2036.0) < 0.05)
        assertEquals(PageSize.SAMSUNG_NOTES, PageSizeChoice.of(1440.0, 2036.0, 150).size)
    }
}
