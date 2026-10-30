package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.HAlign
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.vector.PathFlattener
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Writes an xnotes [Document] as a Samsung Notes `.sdocx` (ZIP) bundle.
 *
 * Covers pages, vector strokes (uncompressed), raster images, a single
 * embedded PDF, shapes/tables/connectors (flattened to pen strokes) and
 * plain text (native text boxes with color, size, bold, italic, underline,
 * strike and alignment), plus the `note.note`, `pageIdInfo.dat` and
 * `end_tag.bin` records (with identity hashes, page footers and the appended
 * end tag) that Samsung Notes requires to open the file.
 *
 * Deliberately dropped: fills (shapes export as outlines only), neon glow,
 * glyph-art shapes, audio pills and tape (both are non-printing in xnotes —
 * see `CanvasItem.exported` — and match the PDF export's behaviour), LaTeX
 * math (exported as its raw source text), text rotation, and custom fonts
 * (Samsung's default font is used).
 */
object SdocxExport {
    /** Media referenced by the bundle while it is built. */
    private class Media(val bindId: Int, val fileName: String, val bytes: ByteArray)

    // Archive footers Samsung Notes requires after the page content.
    private val PAGE_FOOTER = "Page for SAMSUNG S-Pen SDK".toByteArray(Charsets.US_ASCII)
    private val END_TAG_FOOTER = "Document for S-Pen SDK".toByteArray(Charsets.US_ASCII)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun sha256Hex(data: ByteArray): ByteArray {
        val hex = sha256(data).joinToString("") { "%02x".format(it) }
        return hex.toByteArray(Charsets.US_ASCII)
    }

    /** Identity hash Samsung uses for object/layer/page records. */
    private fun identityHash(id: String, now: Long): ByteArray =
        sha256("$id$now".toByteArray(Charsets.UTF_8))

    private fun samsungNow(): Long = System.currentTimeMillis() * 1000L

    fun writeDocument(doc: Document, out: OutputStream) {
        val now = samsungNow()
        val media = ArrayList<Media>()
        var nextBind = 0

        // Embedded PDF first (bind 0) so page papers can name it.
        val pdfBytes = try {
            doc.pdfFile?.takeIf { it.exists() }?.readBytes()
        } catch (_: Exception) {
            null
        }
        val pdfBindId: Int? = if (pdfBytes != null && pdfBytes.isNotEmpty()) {
            val id = nextBind++
            media.add(Media(id, "$id@document.pdf", pdfBytes))
            id
        } else null

        // Images in page order, matching the manifest walk.
        val imageBindByItem = HashMap<ImageItem, Int>()
        for (page in doc.pages) {
            for (item in page.items) {
                if (item !is ImageItem) continue
                val bytes = try {
                    item.image.file.takeIf { it.exists() }?.readBytes()
                } catch (_: Exception) {
                    null
                } ?: continue
                if (bytes.isEmpty()) continue
                val ext = imageExt(bytes, item.image.file.name)
                val id = nextBind++
                media.add(Media(id, "$id@image${media.size}.$ext", bytes))
                imageBindByItem[item] = id
            }
        }

        // Page records (data + hash for the manifest).
        val pageRecords = ArrayList<Triple<String, ByteArray, ByteArray>>()
        val pages = if (doc.pages.isEmpty()) listOf(Page(1440.0, 2036.0)) else doc.pages
        for (page in pages) {
            val uuid = UUID.randomUUID().toString()
            val (bytes, hash) = buildPage(page, doc, uuid, pdfBindId, imageBindByItem, now)
            pageRecords.add(Triple(uuid, bytes, hash))
        }

        val mediaInfo = buildMediaManifest(media, now)
        val noteData = buildNoteFile(pages, now)
        val noteHash = sha256(noteData.dropLast(32).toByteArray())
        val pageManifest = buildPageManifest(noteHash, pageRecords.map { Pair(it.first, it.third) })
        val endTag = buildEndTag(pages, now)

        // ZIP entries first; the raw end tag is then appended after the ZIP
        // end record exactly like Samsung-written files.
        val zipBytes = ByteArrayOutputStream().also { buf ->
            ZipOutputStream(buf).use { zos ->
                zos.putNextEntry(ZipEntry("media/mediaInfo.dat"))
                zos.write(mediaInfo)
                zos.closeEntry()

                for (m in media) {
                    zos.putNextEntry(ZipEntry("media/${m.fileName}"))
                    zos.write(m.bytes)
                    zos.closeEntry()
                }

                for ((uuid, bytes, _) in pageRecords) {
                    zos.putNextEntry(ZipEntry("$uuid.page"))
                    zos.write(bytes)
                    zos.closeEntry()
                }

                zos.putNextEntry(ZipEntry("pageIdInfo.dat"))
                zos.write(pageManifest)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("note.note"))
                zos.write(noteData)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("end_tag.bin"))
                zos.write(endTag)
                zos.closeEntry()
            }
        }.toByteArray()
        out.write(zipBytes)
        out.write(endTag)
        out.flush()
    }

    private fun imageExt(bytes: ByteArray, name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith(".png")) return "png"
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg"
        if (lower.endsWith(".webp")) return "webp"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
        if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[8] == 0x57.toByte()) return "webp"
        return "png"
    }

    // --- manifests ---

    private fun buildMediaManifest(media: List<Media>, now: Long): ByteArray {
        val w = SdocxWriter()
        w.writeU32(5500) // format version observed in the wild
        w.writeU16(media.size)
        for (m in media) {
            val rec = SdocxWriter()
            rec.writeI32(m.bindId)
            rec.writeUtf16U16(m.fileName)
            // Real Samsung manifests carry the SHA-256 hex of the payload;
            // the all-zero "no hash" form parses but Samsung Notes rejects it.
            rec.writeBytes(sha256Hex(m.bytes))
            rec.writeU16(1) // reference count
            rec.writeI64(now)
            rec.writeU8(1) // attached
            val recBytes = rec.bytes()
            w.writeU32(recBytes.size.toLong())
            w.writeBytes(recBytes)
        }
        w.writeBytes(byteArrayOf(0x45, 0x4F, 0x46, 0x58)) // "EOFX"
        return w.bytes()
    }

    private fun buildPageManifest(noteHash: ByteArray, pages: List<Pair<String, ByteArray>>): ByteArray {
        val w = SdocxWriter()
        w.writeBytes(noteHash.copyOf(32).let { if (it.size == 32) it else ByteArray(32) })
        w.writeU16(pages.size)
        for ((id, hash) in pages) {
            w.writeUtf16U16(id)
            w.writeBytes(hash.copyOf(32).let { if (it.size == 32) it else ByteArray(32) })
        }
        return w.bytes()
    }

    // --- note.note / end_tag.bin (Samsung requires both) ---

    private val EMPTY_TITLE_TEMPLATE: ByteArray by lazy {
        java.util.Base64.getDecoder().decode(
            "cQAAAAAAaQAAAAK/AQQAQAAAoA8AACQAODVjZGI3ZmMtMWQ5Mi0xMWYxLTg4ZjAtYmI5ODM1ODY0ZDcyJJ95dMZMBgAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0AIAAAAAAABCAAAABgAbAAAAAQABDAAAAAAEAAAAAAAAAAATAAAA" +
                "AQACAAAA/wAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAAAACrAAAABwA+AAAAAQQEAQAAAAQAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGkAAAAJAAAAMAAwADAAMAAwADAAMAAwADAAAQAAABgAAQAAAAAAAAAJAAAAAQAA" +
                "ACUlJf8AAAAAAQAAABQABgAAAAAAAAABAAAAAAAAAAAAAAAAADRBAAAAAAAANEEAAAAAAAAAAAAAAAAAAAAPAAAAAgAAAAAA" +
                "AQACAAA=",
        )
    }

    private val EMPTY_BODY_TEMPLATE: ByteArray by lazy {
        java.util.Base64.getDecoder().decode(
            "cQAAAAAAaQAAAAK/AQQAQAAAoA8AACQANDA4N2NjOGUtMWYyZi0xMWYxLTk2NTAtMzcxMWM3YmE4MmExMx8buu9MBgAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0AIAAAAAAABCAAAABgAbAAAAAQABDAAAAAAEAAAAAAAAAAATAAAA" +
                "AQACAAAA/wAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAAAABqAAAABwA+AAAAAQQEASAAAAQAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACcAAAAAAAAAAAAAAAAAAAAAAIBBAAAgQQAAgEEAACBBAAAAAAAAAAAAAAACDwAA" +
                "AAIAAAAAAAEAAgAA",
        )
    }

    private val CURRENT_PEN_INFO: ByteArray by lazy {
        java.util.Base64.getDecoder().decode(
            "ngAAADMAYwBvAG0ALgBzAGEAbQBzAHUAbgBnAC4AYQBuAGQAcgBvAGkAZAAuAHMAZABrAC4AcABlAG4ALgBwAGUAbgAuAHAA" +
                "cgBlAGwAbwBhAGQALgBGAG8AdQBuAHQAYQBpAG4AUABlAG4AMzOjQNLrL/8BAAAAAAAAAAAAHgAAAAEAAAAAAAAAAAAAAHMF" +
                "LEPNzEw/7OtrPwAAAAA=",
        )
    }

    private fun patchTextObjectBase(template: ByteArray, objectId: String, now: Long): ByteArray {
        val data = template.copyOf()
        val idBytes = objectId.toByteArray(Charsets.US_ASCII).copyOf(36).let {
            // pad with '0'
            val out = ByteArray(36) { i -> if (i < it.size) it[i] else '0'.code.toByte() }
            out
        }
        idBytes.copyInto(data, 24, 0, 36)
        val nowBytes = ByteArray(8)
        for (i in 0 until 8) nowBytes[i] = ((now ushr (i * 8)) and 0xFF).toByte()
        nowBytes.copyInto(data, 60, 0, 8)
        return data
    }

    private fun buildStringIdBlock(entries: List<Pair<Int, String>>): ByteArray {
        val body = SdocxWriter()
        body.writeU16(entries.size)
        for ((id, value) in entries) {
            body.writeI32(id)
            body.writeUtf16U16(value)
        }
        val bodyBytes = body.bytes()
        val w = SdocxWriter()
        w.writeU32(bodyBytes.size.toLong())
        w.writeBytes(bodyBytes)
        return w.bytes()
    }

    /** Minimal note.note Samsung Notes accepts: empty title/body + pen table. */
    private fun buildNoteFile(pages: List<Page>, now: Long): ByteArray {
        val width = pages.maxOfOrNull { it.width.roundToInt() }?.coerceIn(200, 5000) ?: 1113
        val height = (pages.sumOf { it.height.roundToInt().coerceIn(200, 5000) }) + 8
        val titleObj = patchTextObjectBase(EMPTY_TITLE_TEMPLATE, UUID.randomUUID().toString(), now)
        val bodyObj = patchTextObjectBase(EMPTY_BODY_TEMPLATE, UUID.randomUUID().toString(), now)

        val data = SdocxWriter()
        data.writeBytes(ByteArray(4)) // flexible offset placeholder
        data.writeU8(4)
        data.writeU32(0x00000008) // header flags
        data.writeU8(4)
        data.writeU32(0x000C8E80) // property flags
        data.writeU32(4000) // format version
        data.writeUtf16U16("") // note id (empty tolerated)
        data.writeU32(5) // file revision
        data.writeI64(now)
        data.writeI64(now)
        data.writeU32(width.toLong())
        data.writeU32(height.toLong())
        data.writeU32(0) // page horizontal padding
        data.writeU32(8) // page vertical padding
        data.writeU32(4000) // min format version
        data.writeU32(titleObj.size.toLong())
        data.writeBytes(titleObj)
        data.writeU32(bodyObj.size.toLong())
        data.writeBytes(bodyObj)

        val optionalOffset = data.size()
        // Patch flexible offset at the head.
        val raw = data.bytes()
        val offBytes = ByteArray(4)
        for (i in 0 until 4) offBytes[i] = ((optionalOffset ushr (i * 8)) and 0xFF).toByte()
        offBytes.copyInto(raw, 0, 0, 4)

        val out = SdocxWriter()
        out.writeBytes(raw)
        out.writeI32(0)
        out.writeI32(-1)
        out.writeI64(0)
        out.writeBytes(
            buildStringIdBlock(
                listOf(
                    0 to "com.samsung.android.sdk.pen.pen.preload.FountainPen",
                    1 to "18;0;100;",
                ),
            ),
        )
        out.writeI32(0)
        out.writeBytes(CURRENT_PEN_INFO)
        out.writeI32(2)
        out.writeI32(2)

        val content = out.bytes()
        val hash = sha256(content)
        val fin = SdocxWriter()
        fin.writeBytes(content)
        fin.writeBytes(hash)
        return fin.bytes()
    }

    /** Minimal end_tag.bin Samsung Notes accepts. */
    private fun buildEndTag(pages: List<Page>, now: Long): ByteArray {
        val width = pages.maxOfOrNull { it.width.roundToInt() }?.coerceIn(200, 5000) ?: 1113
        val height = pages.sumOf { it.height.roundToInt().coerceIn(200, 5000) }.toFloat() + 8f
        val payload = SdocxWriter()
        payload.writeI32(4000)
        payload.writeUtf16U16("")
        payload.writeI64(now)
        payload.writeI32(0)
        payload.writeUtf16U16("")
        payload.writeI32(width)
        payload.writeF32(height)
        payload.writeUtf16U16("")
        payload.writeI32(-1)
        payload.writeI32(-1)
        payload.writeUtf16U16("")
        payload.writeI32(4000)
        payload.writeI64(now)
        payload.writeI32(0)
        payload.writeU16(0)
        payload.writeU16(0)
        payload.writeUtf16U16("")
        payload.writeI32(0)
        payload.writeI32(0)
        payload.writeI64(now)
        payload.writeI64(now / 1000L)
        payload.writeI64(0)
        payload.writeUtf16U16("")
        payload.writeI32(2)
        payload.writeI32(2)
        payload.writeI64(-1)
        payload.writeI32(0)
        payload.writeI32(0)
        // nullable custom data: empty string
        payload.writeU32(0)
        val payloadBytes = payload.bytes()
        val w = SdocxWriter()
        // Declared size + payload + signature footer.
        val withFooter = payloadBytes + END_TAG_FOOTER
        w.writeU16(withFooter.size)
        w.writeBytes(withFooter)
        return w.bytes()
    }

    // --- pages ---

    private fun buildPage(
        page: Page,
        doc: Document,
        uuid: String,
        pdfBindId: Int?,
        imageBindByItem: Map<ImageItem, Int>,
        now: Long,
    ): Pair<ByteArray, ByteArray> {
        val width = page.width.roundToInt().coerceIn(200, 5000)
        val height = page.height.roundToInt().coerceIn(200, 5000)

        // Collect objects first: each returns its bytes + identity hash.
        val objects = ArrayList<Pair<ByteArray, ByteArray>>()
        for (item in page.items) {
            when (item) {
                is Stroke -> buildStrokeObject(item, now)?.let { objects.add(it) }
                is ImageItem -> {
                    val bindId = imageBindByItem[item] ?: continue
                    buildImageObject(item, bindId, now)?.let { objects.add(it) }
                }
                // Shapes, tables and connectors have no native counterpart in
                // the writer: flatten their outlines to pen strokes (the user's
                // requested approach), so the ink still opens in Samsung Notes.
                is ShapeItem, is TableItem, is ConnectorItem ->
                    shapeStrokes(item).forEach { buildStrokeObject(it, now)?.let { o -> objects.add(o) } }
                // Text (boxes and shape labels) goes out as native text boxes.
                is TextItem -> buildTextObject(textExportOf(item), now)?.let { objects.add(it) }
                // Audio pills and tape never print (see CanvasItem.exported),
                // matching the PDF export: skipped.
                else -> Unit
            }
        }
        // Shape labels ride along as text boxes of their own.
        for (item in page.items) {
            if (item is ShapeItem) {
                labelExportOf(item)?.let { buildTextObject(it, now)?.let { o -> objects.add(o) } }
            }
        }

        var propertyMask = 0x70 // bgImageMode + bgColor + bgWidth, like real files
        val bgColor: Int = (page.style.pageColor ?: doc.style.pageColor)?.toArgb()
            ?: 0xFFFCFCFC.toInt()
        val pdfIndex = page.pdfPage
        val hasPdf = pdfIndex != null && pdfBindId != null && doc.pdfFile != null
        if (hasPdf) propertyMask = propertyMask or (1 shl 8)
        val templateType = SdocxMapping.patternToSamsungTemplate(
            page.style.template ?: doc.style.template,
        )
        if (templateType != null) propertyMask = propertyMask or (1 shl 9)

        // Drawn rect when there is content (mirrors Samsung writers).
        val drawnRect: DoubleArray? = if (objects.isNotEmpty()) {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            fun feed(x0: Double, y0: Double, x1: Double, y1: Double) {
                if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return
                if (x0 < minX) minX = x0
                if (y0 < minY) minY = y0
                if (x1 > maxX) maxX = x1
                if (y1 > maxY) maxY = y1
            }
            // Recompute cheaply from the source items (same bounds as objects).
            for (item in page.items) {
                when (item) {
                    is Stroke -> {
                        for (i in 0 until item.sampleCount) feed(item.xAt(i), item.yAt(i), item.xAt(i), item.yAt(i))
                    }
                    is ImageItem -> feed(item.rect.x, item.rect.y, item.rect.x + item.rect.w, item.rect.y + item.rect.h)
                    is TextItem -> {
                        val r = item.bounds()
                        feed(r.x, r.y, r.x + r.w, r.y + r.h)
                    }
                    else -> Unit
                }
            }
            if (minX <= maxX && minY <= maxY) {
                propertyMask = propertyMask or (1 shl 0)
                doubleArrayOf(minX, minY, maxX, maxY)
            } else null
        } else null

        // Fixed 18-byte header area uses 4-byte masks, like real files.
        val propertyOffset = 0x80
        val header = SdocxWriter()
        header.writeMask(SdocxMask(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        header.writeMask(encodeMask4(propertyMask))
        header.writeU32(0) // orientation
        header.writeU32(width.toLong())
        header.writeU32(height.toLong())
        header.writeU32(0) // offset_x
        header.writeU32(0) // offset_y
        header.writeUtf16U16(uuid)
        header.writeI64(now)
        header.writeU32(4000) // format version
        header.writeU32(4000) // min format version
        val headerBytes = header.bytes()
        check(headerBytes.size == 120) { "page header must be 120 bytes" }

        // Flexible properties in bit order.
        val flex = SdocxWriter()
        if ((propertyMask and (1 shl 0)) != 0) {
            val r = drawnRect!!
            for (v in r) flex.writeF64(v)
        }
        // Bits 4,5,6 always present (mask 0x70).
        flex.writeU32(2) // bgImageMode
        flex.writeU32((bgColor.toLong() and 0xFFFFFFFFL))
        flex.writeU32(width.toLong()) // bgWidth
        if ((propertyMask and (1 shl 8)) != 0) {
            flex.writeU16(1)
            flex.writeI32(pdfBindId!!)
            flex.writeI32(pdfIndex!!)
            flex.writeI32(0) // left
            flex.writeI32(0) // top
            flex.writeI32(width) // right
            flex.writeI32(height) // bottom
        }
        if ((propertyMask and (1 shl 9)) != 0) flex.writeU32(templateType!!.toLong())
        val flexBytes = flex.bytes()

        val rawLayerOffset = propertyOffset + flexBytes.size

        val out = SdocxWriter()
        out.writeU32(rawLayerOffset.toLong())
        out.writeU32(propertyOffset.toLong())
        out.writeBytes(headerBytes)
        out.writeBytes(flexBytes)

        // One layer holding every exported object, with a real Samsung-style
        // header (UUID + modified time) and identity hashes.
        val layerSection = SdocxWriter()
        layerSection.writeU16(1) // layer count
        layerSection.writeU16(0) // current layer
        val layerId = UUID.randomUUID().toString()
        val layerExtra = SdocxWriter().also {
            it.writeUtf16U16(layerId)
            it.writeI64(now)
        }.bytes()
        val layerStart = 4
        val layerHeaderSize = 16 + layerExtra.size
        val metadataOffsetAbs = rawLayerOffset + layerStart + 16
        val layer = SdocxWriter()
        layer.writeU32(layerHeaderSize.toLong())
        layer.writeU32(metadataOffsetAbs.toLong())
        layer.writeU8(1) // constant
        layer.writeU8(0x02) // flags_1
        layer.writeU8(1) // constant
        layer.writeU8(0x18) // flags_2: uuid + modified
        layer.writeU32(0) // number
        layer.writeBytes(layerExtra)
        layer.writeU32(objects.size.toLong())
        val objectHashes = ByteArrayOutputStream()
        for ((o, h) in objects) {
            layer.writeBytes(o)
            objectHashes.write(h)
        }
        objectHashes.write(identityHash(layerId, now))
        val layerHash = sha256(objectHashes.toByteArray())
        layer.writeBytes(layerHash)
        layerSection.writeBytes(layer.bytes())
        out.writeBytes(layerSection.bytes())

        val content = out.bytes()
        val pageHash = sha256(layerHash + identityHash(uuid, now))
        val fin = SdocxWriter()
        fin.writeBytes(content)
        fin.writeBytes(pageHash)
        fin.writeBytes(PAGE_FOOTER)
        return Pair(fin.bytes(), pageHash)
    }

    /** A text run to write as one native Samsung text box. */
    private class TextExport(
        val rect: Rect,
        val text: String,
        val rgba: Rgba,
        val pointSize: Double,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val align: HAlign,
    )

    private fun textExportOf(t: TextItem): TextExport =
        TextExport(t.bounds(), t.text, t.rgba, t.pointSize, t.bold, t.italic, t.underline, t.strike, t.align)

    /** A shape's label as a text box on the page, or null when it has no words. */
    private fun labelExportOf(s: ShapeItem): TextExport? {
        val label = s.label ?: return null
        if (label.text.isEmpty()) return null
        val corners = s.labelCorners()
        if (corners.isEmpty()) return null
        val box = Rect.bounding(corners)
        if (!(box.w > 0) || !(box.h > 0)) return null
        val st = label.style
        return TextExport(box, label.text, st.rgba, st.pointSize, st.bold, st.italic, st.underline, st.strike, st.align)
    }

    /**
     * Flatten a shape, table or connector into pen strokes for export.
     * Every stroked outline becomes baked dash runs (Samsung strokes carry no
     * dash attribute); fills, neon glow and glyph-art outlines are dropped.
     */
    internal fun shapeStrokes(item: com.xnotes.core.model.CanvasItem): List<Stroke> {
        return when (item) {
            is ShapeItem -> shapeItemStrokes(item)
            is TableItem -> tableStrokes(item)
            is ConnectorItem -> connectorStrokes(item)
            else -> emptyList()
        }
    }

    private fun penStrokeRuns(points: List<Pt>, color: Rgba, width: Double, dashed: Boolean, dashLength: Double, dashGap: Double): List<Stroke> {
        // Converted outlines are always plain pen runs: dashes are baked into
        // the geometry above (Samsung strokes carry no dash attribute).
        val config = ToolDefaults.configFor(Tool.PEN).copy(
            baseWidth = width.coerceAtLeast(1.0),
            rgba = color,
            pressureEnabled = false,
            pressureMinFactor = 1.0,
        )
        // Long flattened runs are split with a shared joint so no stroke
        // exceeds the format's 65535-point record. Dashed outlines are baked
        // into dash runs first: Samsung has no dash attribute on strokes.
        val out = ArrayList<Stroke>()
        val runs = if (dashed && dashLength > 0) dashRuns(points, dashLength, dashGap.coerceAtLeast(0.0)) else listOf(points)
        for (runPts in runs) {
            var run = ArrayList<Sample>()
            fun flush() {
                if (run.size >= 2) {
                    val s = Stroke(Tool.PEN, config)
                    s.setSamples(run)
                    s.finished = true
                    out.add(s)
                }
                run = ArrayList()
            }
            for ((i, p) in runPts.withIndex()) {
                if (!p.x.isFinite() || !p.y.isFinite()) {
                    flush()
                    continue
                }
                if (run.size == 60000) {
                    // Overlap the joint to keep the line visually continuous.
                    val joint = run.last()
                    flush()
                    run.add(joint)
                }
                run.add(Sample(p.x, p.y, 1.0))
                if (i == runPts.lastIndex) flush()
            }
        }
        return out
    }

    /**
     * Split a polyline into visible dash runs by arclength: [on] px of ink
     * followed by [off] px of gap, repeating. Closed loops keep their seam
     * inside a gap when it lands there, else the seam dash is split.
     */
    private fun dashRuns(points: List<Pt>, on: Double, off: Double): List<List<Pt>> {
        if (points.size < 2 || off < 0) return listOf(points)
        val period = on + off
        if (period <= 0) return listOf(points)
        val out = ArrayList<List<Pt>>()
        var cur = ArrayList<Pt>()
        var dist = 0.0 // arclength within the current period
        var prev: Pt? = null
        fun flushRun() {
            if (cur.size >= 2) out.add(cur)
            cur = ArrayList()
        }
        for (p in points) {
            val q = prev
            if (q == null) {
                // First point: starts a dash only if the period starts with ink.
                if (dist < on) cur.add(p)
                prev = p
                continue
            }
            var segStart: Pt = q
            var remaining = q.distanceTo(p)
            while (remaining > 1e-9) {
                val inInk = dist < on
                val leftInPhase = (if (inInk) on else period) - dist
                val step = minOf(remaining, leftInPhase)
                val t = if (remaining > 0) (q.distanceTo(p) - remaining + step) / q.distanceTo(p) else 1.0
                // Interpolate along q->p.
                val nx = q.x + (p.x - q.x) * t.coerceIn(0.0, 1.0)
                val ny = q.y + (p.y - q.y) * t.coerceIn(0.0, 1.0)
                val np = Pt(nx, ny)
                if (inInk) {
                    if (cur.isEmpty()) cur.add(segStart)
                    cur.add(np)
                } else if (cur.isNotEmpty()) {
                    flushRun()
                }
                segStart = np
                remaining -= step
                dist += step
                if (dist >= period - 1e-9) dist -= period
            }
            prev = p
        }
        flushRun()
        return out.ifEmpty { listOf(points) }
    }

    private fun shapeItemStrokes(s: ShapeItem): List<Stroke> {
        // Glyph art would trace letter outlines (bubble letters) and a dropped
        // colour has no stroked outline at all: both are skipped.
        if (s.shape == ShapeKind.GLYPHS || s.shape == ShapeKind.FILL) return emptyList()
        if (s.strokeWidth <= 0.0) return emptyList()
        val out = ArrayList<Stroke>()
        for (path in s.paths()) {
            if (path.role == PathRole.FILL_ONLY) continue
            for (contour in path.contours) {
                val flat = PathFlattener.flatten(contour, SHAPE_FLATTEN_TOL)
                if (flat.size < 2) continue
                val pts = if (contour.closed) flat + flat.first() else flat
                out.addAll(penStrokeRuns(pts, s.strokeRgba, s.strokeWidth, s.dashed, s.dashLength, s.dashGap))
            }
        }
        return out
    }

    private fun tableStrokes(t: TableItem): List<Stroke> {
        val r = t.rect
        if (!(r.w > 0) || !(r.h > 0)) return emptyList()
        val out = ArrayList<Stroke>()
        // Outer rect as one closed run, then each interior line on its own.
        val corners = listOf(Pt(r.left, r.top), Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom), Pt(r.left, r.top))
        out.addAll(penStrokeRuns(corners, t.strokeRgba, t.strokeWidth, false, 10.0, 8.0))
        for (i in 1 until t.cols) {
            val x = t.colX(i)
            out.addAll(penStrokeRuns(listOf(Pt(x, r.top), Pt(x, r.bottom)), t.strokeRgba, t.strokeWidth, false, 10.0, 8.0))
        }
        for (j in 1 until t.rows) {
            val y = t.rowY(j)
            out.addAll(penStrokeRuns(listOf(Pt(r.left, y), Pt(r.right, y)), t.strokeRgba, t.strokeWidth, false, 10.0, 8.0))
        }
        return out
    }

    private fun connectorStrokes(c: ConnectorItem): List<Stroke> {
        val flat = PathFlattener.flatten(c.body(), SHAPE_FLATTEN_TOL)
        if (flat.size < 2) return emptyList()
        return penStrokeRuns(flat, c.strokeRgba, c.strokeWidth, c.dashed, c.dashLength, c.dashGap)
    }

    private const val SHAPE_FLATTEN_TOL = 0.25

    /** Longest single text box accepted for export, in UTF-16 units. */
    private const val MAX_TEXT_UNITS = 100_000

    // --- text ---

    /**
     * Build a native Samsung text box object (type 2): base frame + empty
     * shape-base + shape-text frame carrying the text-common payload + empty
     * tail. Faithful for plain text with color, size, bold, italic, underline,
     * strike and alignment; LaTeX math arrives as its raw source text and the
     * box always uses Samsung's default font. Null when [t] holds nothing.
     */
    /**
     * The text-common payload for [text] with whole-box styling, as stored in
     * a shape-text frame's bit-0 field: `[u32 size][text][spans][paragraphs]
     * [margins][gravity]`. Exposed for tests building synthetic shape labels.
     */
    internal fun textCommonBytes(
        text: String,
        colorArgb: Int,
        pointSize: Float,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        alignValue: Int,
    ): ByteArray {
        val units = text.length
        // Style spans over the whole text.
        val spans = ArrayList<ByteArray>()
        fun span(kind: Int, payload: ByteArray) {
            val w = SdocxWriter()
            w.writeU32(kind.toLong())
            w.writeU32(0) // start_utf16
            w.writeU32(units.toLong()) // end_utf16
            w.writeU32(0) // ClosedOpen interval
            w.writeBytes(payload)
            val rec = w.bytes()
            val h = SdocxWriter()
            h.writeU16(rec.size)
            h.writeBytes(rec)
            spans.add(h.bytes())
        }
        run {
            val p = SdocxWriter()
            p.writeU32(colorArgb.toLong() and 0xFFFFFFFFL)
            p.writeU32(0)
            span(1, p.bytes()) // ForegroundColor
        }
        run {
            val p = SdocxWriter()
            p.writeF32(pointSize)
            p.writeU32(0)
            span(3, p.bytes()) // FontSize
        }
        val on = byteArrayOf(0x01, 0x00)
        if (bold) span(5, on)
        if (italic) span(6, on)
        if (underline) span(7, on)
        if (strike) span(20, on)

        // One alignment paragraph over the single paragraph.
        val paras = ArrayList<ByteArray>()
        run {
            val w = SdocxWriter()
            w.writeU32(3) // Alignment
            w.writeU32(0) // start paragraph
            w.writeU32(1) // end paragraph
            w.writeU32(alignValue.toLong())
            val rec = w.bytes()
            val h = SdocxWriter()
            h.writeU16(rec.size)
            h.writeBytes(rec)
            paras.add(h.bytes())
        }

        val common = SdocxWriter()
        common.writeUtf16U32(text)
        common.writeU32(spans.size.toLong())
        for (s in spans) common.writeBytes(s)
        common.writeU32(paras.size.toLong())
        for (p in paras) common.writeBytes(p)
        common.writeF32(0f) // margins L/T/R/B
        common.writeF32(0f)
        common.writeF32(0f)
        common.writeF32(0f)
        common.writeU8(0) // gravity
        // Samsung writers always append an (empty) section list and the
        // object-span header; omitting them reads as corruption on import.
        common.writeU16(0) // section count
        common.writeU32(0) // object span flags
        common.writeU32(0) // reserved
        val commonBytes = common.bytes()
        val flex = SdocxWriter()
        flex.writeU32(commonBytes.size.toLong())
        flex.writeBytes(commonBytes)
        return flex.bytes()
    }

    private fun buildTextObject(t: TextExport, now: Long): Pair<ByteArray, ByteArray>? {
        try {
            if (t.text.isEmpty() || t.text.length > MAX_TEXT_UNITS) return null
            val r = t.rect
            if (!(r.w > 0) || !(r.h > 0) || !r.w.isFinite() || !r.h.isFinite()) return null
            if (!r.x.isFinite() || !r.y.isFinite()) return null
            val bbox = doubleArrayOf(r.x, r.y, r.x + r.w, r.y + r.h)

            val alignValue = when (t.align) {
                HAlign.RIGHT -> 1
                HAlign.CENTER -> 2
                else -> 0
            }
            val flex = textCommonBytes(t.text, t.rgba.toArgb(), t.pointSize.toFloat(),
                t.bold, t.italic, t.underline, t.strike, alignValue)

            // Empty shape-base: no magnets or connections.
            val base6Fixed = SdocxWriter()
            base6Fixed.writeU32(0)
            base6Fixed.writeU32(4)
            base6Fixed.writeU32(0)
            base6Fixed.writeU8(0)
            val base6 = writeFrame(6, SdocxMask(byteArrayOf(0x00)), SdocxMask(byteArrayOf(0x00)), base6Fixed.bytes(), ByteArray(0))
            val shape = writeFrame(7, SdocxMask(byteArrayOf(0x00)), SdocxMask(byteArrayOf(0x01)), ByteArray(0), flex)
            val tail = writeFrame(2, SdocxMask(byteArrayOf(0x00)), SdocxMask(byteArrayOf(0x00)), ByteArray(0), ByteArray(0))

            val objectId = UUID.randomUUID().toString()
            val payload = SdocxWriter()
            payload.writeBytes(baseFrame(bbox, now = now, objectId = objectId))
            payload.writeBytes(base6)
            payload.writeBytes(shape)
            payload.writeBytes(tail)
            return Pair(wrapObject(2, payload.bytes(), objectId, now), identityHash(objectId, now))
        } catch (_: Exception) {
            return null
        }
    }

    private fun encodeMask(mask: Int): SdocxMask {
        if (mask == 0) return SdocxMask(byteArrayOf(0x00))
        if (mask and 0xFFFF0000.toInt() == 0 && mask < 256) {
            return SdocxMask(byteArrayOf((mask and 0xFF).toByte()))
        }
        // Bits 0..9 fit in two bytes.
        return SdocxMask(
            byteArrayOf(
                (mask and 0xFF).toByte(),
                ((mask ushr 8) and 0xFF).toByte(),
            ),
        )
    }

    private fun encodeMask4(mask: Int): SdocxMask {
        // Page headers always use 4-byte masks in real files.
        return SdocxMask(
            byteArrayOf(
                (mask and 0xFF).toByte(),
                ((mask ushr 8) and 0xFF).toByte(),
                ((mask ushr 16) and 0xFF).toByte(),
                ((mask ushr 24) and 0xFF).toByte(),
            ),
        )
    }

    // --- objects ---

    private fun writeFrame(
        kind: Int,
        properties: SdocxMask,
        fields: SdocxMask,
        fixed: ByteArray,
        flexible: ByteArray,
    ): ByteArray {
        val propSize = 1 + properties.bytes.size
        val fieldSize = 1 + fields.bytes.size
        val headerSize = 2 + 4 + propSize + fieldSize
        val flexOffset = if (flexible.isEmpty() && fields.isEmpty) 0
        else 4 + headerSize + fixed.size
        val size = 4 + headerSize + fixed.size + flexible.size
        val w = SdocxWriter()
        w.writeU32(size.toLong())
        w.writeI16(kind)
        w.writeU32(flexOffset.toLong())
        w.writeMask(properties)
        w.writeMask(fields)
        w.writeBytes(fixed)
        w.writeBytes(flexible)
        return w.bytes()
    }

    private fun baseFrame(bbox: DoubleArray, rotation: Double? = null, now: Long = System.currentTimeMillis() * 1000L, objectId: String? = null): ByteArray {
        val fixed = SdocxWriter()
        fixed.writeU32(1) // format version
        fixed.writeUtf8U16(objectId ?: UUID.randomUUID().toString())
        fixed.writeI64(now)
        for (v in bbox) fixed.writeF64(v)
        fixed.writeI32(0) // replay timestamp
        fixed.writeU8(0) // resize mode: free
        val props = SdocxMask(byteArrayOf(0x0E)) // selectable + movable + visible
        val fields: SdocxMask
        val flex: ByteArray
        if (rotation != null && rotation.isFinite() && rotation != 0.0) {
            fields = SdocxMask(byteArrayOf(0x01))
            val fw = SdocxWriter()
            fw.writeF32(rotation.toFloat())
            flex = fw.bytes()
        } else {
            fields = SdocxMask(byteArrayOf(0x00))
            flex = ByteArray(0)
        }
        return writeFrame(0, props, fields, fixed.bytes(), flex)
    }

    private fun buildStrokeObject(stroke: Stroke, now: Long): Pair<ByteArray, ByteArray>? {
        try {
            val n = stroke.sampleCount
            if (n < 2 || n > 65535) return null
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val x = stroke.xAt(i)
                val y = stroke.yAt(i)
                if (!x.isFinite() || !y.isFinite()) return null
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            val half = stroke.config.baseWidth / 2.0 + 1.0
            val bbox = doubleArrayOf(minX - half, minY - half, maxX + half, maxY + half)

            // Stroke frame (uncompressed, no stylus channels). No pen-name ids:
            // without a note.note string table they would dangle, so every stroke
            // is plain ballpoint/highlighter keyed only by color + width.
            val fixed = SdocxWriter()
            fixed.writeU16(n)
            for (i in 0 until n) {
                fixed.writeF64(stroke.xAt(i))
                fixed.writeF64(stroke.yAt(i))
            }
            for (i in 0 until n) {
                fixed.writeF32(stroke.pAt(i).toFloat().coerceIn(0f, 1f))
            }
            for (i in 0 until n) fixed.writeI32(0) // timestamps
            fixed.writeU16(1) // tool/input type
            val props = SdocxMask(byteArrayOf(0x00)) // uncompressed
            val fields = SdocxMask(byteArrayOf(0x0C)) // bits 2,3: color + size only
            val flex = SdocxWriter()
            var argb = stroke.renderColor.toArgb()
            // Highlighters must stay translucent (alpha < 200) so the importer's
            // pen-name fallback still sees them as highlighters rather than pens.
            if (stroke.tool == com.xnotes.core.tools.Tool.HIGHLIGHTER) {
                val a = (argb ushr 24) and 0xFF
                if (a >= 200) argb = (199 shl 24) or (argb and 0x00FFFFFF)
            }
            flex.writeU32(argb.toLong() and 0xFFFFFFFFL)
            flex.writeF32(stroke.config.baseWidth.toFloat())
            val strokeFrame = writeFrame(1, props, fields, fixed.bytes(), flex.bytes())

            val objectId = UUID.randomUUID().toString()
            val payload = SdocxWriter()
            payload.writeBytes(baseFrame(bbox, now = now, objectId = objectId))
            payload.writeBytes(strokeFrame)
            return Pair(wrapObject(1, payload.bytes(), objectId, now), identityHash(objectId, now))
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildImageObject(item: ImageItem, bindId: Int, now: Long): Pair<ByteArray, ByteArray>? {
        try {
            val r = item.rect
            if (!(r.w > 0) || !(r.h > 0) || !r.w.isFinite() || !r.h.isFinite()) return null
            val bbox = doubleArrayOf(r.x, r.y, r.x + r.w, r.y + r.h)

            // Shape base (kind 6): no magnets, no connections.
            val base6Fixed = SdocxWriter()
            base6Fixed.writeU32(0) // magnetic count
            base6Fixed.writeU32(4) // connection block size
            base6Fixed.writeU32(0) // connection count
            base6Fixed.writeU8(0) // reserved
            val base6 = writeFrame(6, SdocxMask(byteArrayOf(0x00)), SdocxMask(byteArrayOf(0x00)), base6Fixed.bytes(), ByteArray(0))

            // Shape (kind 7): rectangle type 4 with an image fill.
            val shapeFixed = SdocxWriter()
            shapeFixed.writeU32(4) // shape type
            for (v in bbox) shapeFixed.writeF64(v)
            shapeFixed.writeF32(0f) // rotation
            shapeFixed.writeU32(0) // path size
            shapeFixed.writeU8(0) // control point count
            val fill = SdocxWriter()
            fill.writeU8(0) // fill mode: stretch
            fill.writeI32(bindId)
            // 9 fill settings: all 0 except [6],[7] = 100 (no transform).
            val settings = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 100f, 100f, 0f)
            for (f in settings) fill.writeF32(f)
            fill.writeU8(0) // rotatable
            fill.writeI32(0) // nine-patch left
            fill.writeI32(0) // top
            fill.writeI32(0) // right
            fill.writeI32(0) // bottom
            fill.writeI32(0) // nine-patch width
            val fillBytes = fill.bytes()
            val shapeFlex = SdocxWriter()
            shapeFlex.writeU32(fillBytes.size.toLong())
            shapeFlex.writeU8(2) // fill kind: image
            shapeFlex.writeBytes(fillBytes)
            val shape = writeFrame(7, SdocxMask(byteArrayOf(0x00)), SdocxMask(byteArrayOf(0x20)), shapeFixed.bytes(), shapeFlex.bytes())

            // Tail (kind 3): no settings.
            val tail = writeFrame(3, SdocxMask(byteArrayOf(0x00)), SdocxMask(byteArrayOf(0x00)), ByteArray(0), ByteArray(0))

            val objectId = UUID.randomUUID().toString()
            val payload = SdocxWriter()
            payload.writeBytes(baseFrame(bbox, now = now, objectId = objectId))
            payload.writeBytes(base6)
            payload.writeBytes(shape)
            payload.writeBytes(tail)
            return Pair(wrapObject(3, payload.bytes(), objectId, now), identityHash(objectId, now))
        } catch (_: Exception) {
            return null
        }
    }

    private fun wrapObject(type: Int, payload: ByteArray, objectId: String, now: Long): ByteArray {
        val w = SdocxWriter()
        w.writeU8(type)
        w.writeU16(0) // child count
        w.writeU32((payload.size + 32).toLong()) // declared size
        w.writeBytes(payload)
        w.writeBytes(identityHash(objectId, now)) // integrity trailer
        return w.bytes()
    }

    private fun Rgba.toArgb(): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
