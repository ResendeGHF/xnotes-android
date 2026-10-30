package com.xnotes.sdocx

/**
 * Reverse-engineered parser for Samsung Notes `.sdocx` (ZIP) files.
 * Kotlin port of the Saber fork's `sdocx_parser.dart` (itself based on
 * twangodev/sdocx + bxff/samsung-notes-format research).
 *
 * Scope: pages, strokes (types 1/15/18), colors, images (page objects,
 * page backgrounds and photos embedded in note.note's title/body text),
 * PDFs, text, shapes, lines and voice memos. Tables (22), paintings,
 * videos and stickers stay skipped: the reference SDKs decode no
 * structure for them either.
 */
object SdocxParser {
    /** Parse an already-unzipped archive: entry name -> bytes. */
    fun parseArchive(entries: Map<String, ByteArray>): SdocxDocument {
        val mediaByBindId = HashMap<Int, SdocxMediaEntry>()
        val mediaByFileName = HashMap<String, SdocxMediaEntry>()

        val mediaInfo = entries["media/mediaInfo.dat"]
        if (mediaInfo != null) {
            try {
                for (e in parseMediaManifest(mediaInfo)) {
                    mediaByBindId[e.bindId] = e
                    mediaByFileName["media/${e.fileName}"] = e
                }
            } catch (_: Exception) {
                // Fall back to filename-prefix inference below.
            }
        }

        for ((name, bytes) in entries) {
            if (!name.startsWith("media/")) continue
            // Only the manifest itself is metadata; every other media/* entry
            // (images, audio, even ".dat" voice memos) carries payload bytes.
            if (name == "media/mediaInfo.dat") continue
            val fileName = name.substring("media/".length)
            val bindId = mediaArchiveId(name)
            var entry: SdocxMediaEntry? = bindId?.let { mediaByBindId[it] }
            if (entry == null) entry = mediaByFileName[name]
            if (entry == null) {
                val synth = SdocxMediaEntry(bindId ?: -1, fileName)
                if (bindId != null) mediaByBindId[bindId] = synth
                mediaByFileName[name] = synth
                entry = synth
            }
            entry.bytes = bytes
        }

        var stringTable: Map<Int, String> = emptyMap()
        var defaultTemplateUri: String? = null
        var bodyImages = listOf<SdocxImage>()
        val noteBytes = entries["note.note"]
        if (noteBytes != null) {
            try {
                val meta = parseNoteMetadata(noteBytes)
                stringTable = meta.first
                defaultTemplateUri = meta.second
            } catch (_: Exception) {
                val fallback = scanPenStrings(noteBytes)
                if (fallback.isNotEmpty()) stringTable = fallback
            }
            // Photos pasted into Samsung text are stored here, not in any
            // `.page`: without them every such photo vanishes on import.
            bodyImages = parseNoteBodyImages(noteBytes)
        }

        var orderedIds = listOf<String>()
        val manifestBytes = entries["pageIdInfo.dat"]
        if (manifestBytes != null) {
            try {
                orderedIds = parsePageManifest(manifestBytes)
            } catch (_: Exception) {
                orderedIds = emptyList()
            }
        }

        val pageEntries = entries.keys.filter { it.endsWith(".page") }.sorted()
        val uuidToBytes = HashMap<String, ByteArray>()
        for (name in pageEntries) {
            val bytes = entries[name] ?: continue
            try {
                uuidToBytes[peekPageUuid(bytes)] = bytes
            } catch (_: Exception) {
                val stem = name.split("/").last().removeSuffix(".page")
                uuidToBytes[stem] = bytes
            }
        }

        val ordered = ArrayList<SdocxPage>()
        if (orderedIds.isNotEmpty()) {
            for (id in orderedIds) {
                val bytes = uuidToBytes[id] ?: continue
                try {
                    ordered.add(parsePage(bytes, stringTable))
                } catch (_: Exception) {
                    continue
                }
            }
            for ((id, bytes) in uuidToBytes) {
                if (orderedIds.contains(id)) continue
                try {
                    ordered.add(parsePage(bytes, stringTable))
                } catch (_: Exception) {
                    continue
                }
            }
        } else {
            for (name in pageEntries) {
                try {
                    ordered.add(parsePage(entries[name]!!, stringTable))
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return SdocxDocument(
            pages = ordered,
            mediaByBindId = mediaByBindId,
            mediaByFileName = mediaByFileName,
            stringTable = stringTable,
            defaultTemplateUri = defaultTemplateUri,
            bodyImages = bodyImages,
        )
    }

    private fun mediaArchiveId(name: String): Int? {
        val base = name.split("/").last()
        return base.split("@").first().toIntOrNull()
    }

    fun parseMediaManifest(data: ByteArray): List<SdocxMediaEntry> {
        val r = SdocxReader(data)
        val formatVersion = r.readU32()
        if (formatVersion <= 3001) throw SdocxFormatException("Legacy media manifest not supported")
        val count = r.readU16()
        val out = ArrayList<SdocxMediaEntry>()
        for (i in 0 until count) {
            val size = r.readU32().toInt()
            val recBytes = r.readBytes(size)
            val rec = SdocxReader(recBytes)
            val bindId = rec.readI32()
            val fileName = rec.readUtf16U16()
            if (rec.remaining < 2) throw SdocxFormatException("Truncated media record hash prefix")
            val prefix = rec.readBytes(2)
            if (!(prefix[0] == 0.toByte() && prefix[1] == 0.toByte())) {
                if (rec.remaining < 62) throw SdocxFormatException("Truncated media record hash")
                rec.skip(62)
            }
            if (rec.remaining < 2 + 8 + 1) throw SdocxFormatException("Truncated media record tail")
            rec.skip(2)
            rec.skip(8)
            rec.skip(1)
            out.add(SdocxMediaEntry(bindId, fileName))
        }
        if (r.remaining < 4) throw SdocxFormatException("Missing media manifest EOFX marker")
        val marker = r.readBytes(4)
        if (!(marker[0] == 0x45.toByte() && marker[1] == 0x4F.toByte() && marker[2] == 0x46.toByte() && marker[3] == 0x58.toByte())) {
            throw SdocxFormatException("Invalid media manifest EOFX marker")
        }
        return out
    }

    fun parsePageManifest(data: ByteArray): List<String> {
        val r = SdocxReader(data)
        if (r.remaining < 32) throw SdocxFormatException("Truncated page manifest header")
        r.skip(32)
        if (r.remaining < 2) return emptyList()
        val count = r.readU16()
        val ids = ArrayList<String>()
        for (i in 0 until count) {
            if (r.remaining < 2) break
            val id = try {
                r.readUtf16U16()
            } catch (_: Exception) {
                break
            }
            if (id.isNotEmpty()) ids.add(id)
            if (r.remaining >= 32) r.skip(32) else break
        }
        return ids
    }

    /** Returns (stringTable, defaultTemplateUri). */
    fun parseNoteMetadata(data: ByteArray): Pair<Map<Int, String>, String?> {
        val r = SdocxReader(data)
        val flexibleOffset = r.readU32().toInt()
        SdocxMask.read(r) // properties
        val fields = SdocxMask.read(r)
        r.skip(4) // format version
        r.readUtf16U16() // note id
        r.skip(4 + 8 + 8 + 4 + 4 + 4 + 4 + 4)
        val titleSize = r.readU32().toInt()
        r.skip(titleSize)
        val bodySize = r.readU32().toInt()
        r.skip(bodySize)
        val payloadEnd = data.size - 32
        if (flexibleOffset < r.offset || flexibleOffset > payloadEnd || payloadEnd < 0) {
            throw SdocxFormatException("Note flexible data outside note payload")
        }
        val flex = SdocxReader(data.copyOfRange(flexibleOffset, payloadEnd))
        val stringTable = HashMap<Int, String>()
        var templateUri: String? = null
        for (bit in 0 until fields.bytes.size * 8) {
            if (!fields.contains(bit)) continue
            when (bit) {
                0 -> flex.readUtf16U16()
                1 -> {
                    flex.skip(4 + 4)
                    flex.readUtf16U16()
                }
                2 -> {
                    flex.readNullableUtf16U16()
                    flex.readNullableUtf16U16()
                    flex.readNullableUtf16U16()
                    flex.skip(4)
                }
                3 -> flex.skip(8 + 8)
                6 -> templateUri = flex.readUtf16U16()
                7 -> flex.skip(4)
                9 -> flex.skip(4 + 8)
                10 -> {
                    val size = flex.readU32().toInt()
                    val tableBytes = flex.readBytes(size)
                    val tr = SdocxReader(tableBytes)
                    val count = tr.readU16()
                    for (i in 0 until count) {
                        val id = tr.readI32()
                        val text = tr.readUtf16U16()
                        stringTable[id] = text
                    }
                }
                11 -> flex.skip(4)
                12 -> skipNotePen(flex, modern = false)
                13 -> {
                    val count = flex.readU32().toInt()
                    for (i in 0 until count) {
                        val recSize = flex.readU32().toInt()
                        flex.skip(recSize)
                    }
                }
                14 -> {
                    val count = flex.readU16()
                    for (i in 0 until count) {
                        flex.readUtf16U16()
                        flex.skip(4)
                    }
                }
                15 -> {
                    val size = flex.readU32().toInt()
                    if (size < 4) throw SdocxFormatException("Note pen block smaller than prefix")
                    flex.skip(size - 4)
                }
                16 -> flex.skip(8)
                17 -> flex.readUtf16U16()
                18 -> flex.skip(4)
                19 -> flex.skip(4)
                20 -> flex.readUtf16U16()
                21 -> flex.skip(4)
                22 -> flex.readU32()
                else -> return Pair(stringTable, templateUri)
            }
        }
        return Pair(stringTable, templateUri)
    }

    private fun skipNotePen(flex: SdocxReader, modern: Boolean) {
        flex.readUtf16U16()
        flex.skip(4 + 4 + 4)
        flex.readUtf16U16()
        flex.skip(4 + 4 + 4)
        if (modern) flex.skip(4 + 4)
        flex.skip(4 + 4 + 4 + 4)
    }

    fun scanPenStrings(data: ByteArray): Map<Int, String> {
        val known = setOf(
            "DefaultPen", "AirBrushPen", "Beautify", "Brush", "BrushPen", "ChineseBrush", "Crayon",
            "Crayon2", "Eraser", "FadedPen", "FountainPen", "InkPen", "InkPen2", "MagicPen", "Marker",
            "Marker2", "Marker3", "Marker4", "MontblancCalligraphyPen", "MontblancFountainPen",
            "MosaicPen", "ObliquePen", "OilBrush3", "Pencil", "Pencil2", "Pencil3", "SelectPen",
            "SelectPen2", "Smudge", "ColoredPencil", "WaterColorBrush", "StraightHighlighter",
            "StraightMarker", "LaserPen", "GlowPen", "PatternImagePen", "StraightInkPen2",
            "StraightGlowPen", "BlurPen", "StraightMosaicPen", "StraightBlurPen", "MosaicPen2",
            "StraightMosaicPen2", "TapePen",
        )
        val out = HashMap<Int, String>()
        var i = 0
        while (i + 6 < data.size) {
            val id = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or ((data[i + 3].toInt() and 0xFF) shl 24)
            val len = (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8)
            if (id in 0..10000 && len in 2..120) {
                val end = i + 6 + len * 2
                if (end <= data.size) {
                    var ok = true
                    val units = ArrayList<Int>()
                    var k = i + 6
                    while (k < end) {
                        val u = (data[k].toInt() and 0xFF) or ((data[k + 1].toInt() and 0xFF) shl 8)
                        if (u < 32 || u > 126) {
                            ok = false
                            break
                        }
                        units.add(u)
                        k += 2
                    }
                    if (ok) {
                        val text = String(units.map { it.toChar() }.toCharArray())
                        if (text.contains("pen.preload.") || known.contains(text)) {
                            out.putIfAbsent(id, text)
                        }
                    }
                }
            }
            i++
        }
        return out
    }

    fun peekPageUuid(data: ByteArray): String {
        val r = SdocxReader(data)
        r.readU32() // rawLayerOffset
        r.readU32() // propertyOffset
        SdocxMask.read(r)
        SdocxMask.read(r)
        r.skip(4 + 4 + 4 + 4 + 4)
        return r.readUtf16U16()
    }

    /**
     * Photos pasted into Samsung text are stored in `note.note`'s title/body
     * rich text (object spans of type 3), not in any `.page`: without them
     * every such photo vanishes on import while its media bytes sit unused.
     * Returns each embedded placement (bbox in note text-flow coordinates).
     * Never throws; malformed blobs yield no images.
     */
    fun parseNoteBodyImages(data: ByteArray): List<SdocxImage> {
        return try {
            val (title, body) = readNoteBlobs(data) ?: return emptyList()
            val out = ArrayList<SdocxImage>()
            for (blob in listOf(title, body)) {
                try {
                    val r = SdocxReader(blob)
                    readFrame(r) // kind 0 base
                    if (r.isEof) continue
                    val shapeBase = readFrame(r)
                    if (shapeBase.kind != 6) continue
                    if (r.isEof) continue
                    val shapeText = readFrame(r)
                    if (shapeText.kind != 7 || !shapeText.fields.contains(0)) continue
                    val flex = SdocxReader(shapeText.flexible)
                    val rich = decodeTextCommon(flex.readBytes(flex.remaining))
                    out.addAll(rich.images)
                } catch (_: Exception) {
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Raw title/body text-object blobs from `note.note`; null when not readable. */
    private fun readNoteBlobs(data: ByteArray): Pair<ByteArray, ByteArray>? {
        return try {
            val r = SdocxReader(data)
            r.skip(4) // flexible offset
            SdocxMask.read(r)
            SdocxMask.read(r)
            r.skip(4) // format version
            // Note id: empty on real files; the 0xFFFF null sentinel is
            // tolerated (not fatal) so body photos still import without it.
            val idUnits = r.readU16()
            if (idUnits != 0xFFFF) {
                if (idUnits * 2 > r.remaining) return null
                if (idUnits > 0) r.skip(idUnits * 2)
            }
            // Page dimensions, paddings and format bounds.
            r.skip(4 + 8 + 8 + 4 + 4 + 4 + 4 + 4)
            if (r.remaining < 4) return null
            val titleSize = r.readU32().toInt()
            if (titleSize < 0 || titleSize > r.remaining) return null
            val title = r.readBytes(titleSize)
            if (r.remaining < 4) return null
            val bodySize = r.readU32().toInt()
            if (bodySize < 0 || bodySize > r.remaining) return null
            Pair(title, r.readBytes(bodySize))
        } catch (_: Exception) {
            null
        }
    }

    fun parsePage(data: ByteArray, stringTable: Map<Int, String> = emptyMap()): SdocxPage {
        val r = SdocxReader(data)
        val rawLayerOffset = r.readU32().toInt()
        val propertyOffset = r.readU32().toInt()
        val headerEnd = (if (propertyOffset == 0) rawLayerOffset else propertyOffset)
        if (headerEnd > rawLayerOffset || rawLayerOffset > data.size) {
            throw SdocxFormatException("Page header offsets outside page")
        }
        val hr = SdocxReader(data.copyOfRange(0, headerEnd), 8)
        SdocxMask.read(hr)
        val fieldsMask = SdocxMask.read(hr)
        val propertyMask = fieldsMask.lowU32
        hr.skip(4)
        val width = hr.readU32().toDouble()
        val height = hr.readU32().toDouble()
        hr.skip(8)
        val uuid = hr.readUtf16U16()

        var backgroundColorArgb: Int? = null
        var templateUri: String? = null
        var templateType: Int? = null
        var backgroundImageId: Int? = null
        var backgroundImageMode: Int? = null
        var backgroundWidth: Int? = null
        var backgroundRotation: Int? = null
        val pdfPapers = ArrayList<SdocxPdfPaper>()
        if (propertyOffset != 0) {
            val flex = SdocxReader(data.copyOfRange(propertyOffset, rawLayerOffset))
            if ((propertyMask and (1 shl 0)) != 0) flex.skip(32)
            if ((propertyMask and (1 shl 1)) != 0) {
                val n = flex.readU16()
                for (i in 0 until n) flex.readUtf16U16()
            }
            if ((propertyMask and (1 shl 2)) != 0) templateUri = flex.readUtf16U16()
            if ((propertyMask and (1 shl 3)) != 0) backgroundImageId = flex.readU32().toInt()
            if ((propertyMask and (1 shl 4)) != 0) backgroundImageMode = flex.readU32().toInt()
            if ((propertyMask and (1 shl 5)) != 0) backgroundColorArgb = flex.readU32().toInt()
            if ((propertyMask and (1 shl 6)) != 0) backgroundWidth = flex.readU32().toInt()
            if ((propertyMask and (1 shl 7)) != 0) backgroundRotation = flex.readU32().toInt()
            if ((propertyMask and (1 shl 8)) != 0) {
                val n = flex.readU16()
                for (i in 0 until n) {
                    val mediaId = flex.readI32()
                    val pageIndex = flex.readI32()
                    val rectRaw = flex.readBytes(16)
                    val rect = try {
                        val vals = intArrayOf(
                            leI32(rectRaw, 0), leI32(rectRaw, 4), leI32(rectRaw, 8), leI32(rectRaw, 12),
                        )
                        if (vals[2] > vals[0] && vals[3] > vals[1] && vals[2] <= width + 1 && vals[3] <= height + 1) {
                            SdocxRect(vals[0].toDouble(), vals[1].toDouble(), vals[2].toDouble(), vals[3].toDouble())
                        } else {
                            SdocxRect(0.0, 0.0, width, height)
                        }
                    } catch (_: Exception) {
                        SdocxRect(0.0, 0.0, width, height)
                    }
                    pdfPapers.add(SdocxPdfPaper(mediaId, pageIndex, rect))
                }
            }
            if ((propertyMask and (1 shl 9)) != 0) templateType = flex.readU32().toInt()
        }

        val lr = SdocxReader(data, rawLayerOffset)
        val layerCount = lr.readU16()
        if (layerCount == 0) throw SdocxFormatException("Page layer count is zero")
        val currentLayer = lr.readU16()
        if (currentLayer >= layerCount) throw SdocxFormatException("Current layer index out of range")

        val content = PageContent()
        for (li in 0 until layerCount) {
            parseLayer(lr, stringTable, content)
        }

        return SdocxPage(
            uuid = uuid,
            width = width,
            height = height,
            backgroundColorArgb = backgroundColorArgb,
            pdfPapers = pdfPapers,
            strokes = content.strokes,
            images = content.images,
            textBoxes = content.textBoxes,
            shapes = content.shapes,
            lines = content.lines,
            voices = content.voices,
            templateUri = templateUri,
            templateType = templateType,
            backgroundImageId = backgroundImageId,
            backgroundImageMode = backgroundImageMode,
            backgroundWidth = backgroundWidth,
            backgroundRotation = backgroundRotation,
        )
    }

    /** Everything one page's layers collect. */
    private class PageContent {
        val strokes = ArrayList<SdocxStroke>()
        val images = ArrayList<SdocxImage>()
        val textBoxes = ArrayList<SdocxTextBox>()
        val shapes = ArrayList<SdocxShape>()
        val lines = ArrayList<SdocxLine>()
        val voices = ArrayList<SdocxVoice>()
    }

    private fun leI32(b: ByteArray, off: Int): Int {
        val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        return v
    }

    private fun parseLayer(lr: SdocxReader, stringTable: Map<Int, String>, out: PageContent) {
        val headerSize = lr.readU32().toInt()
        if (headerSize < 14 || headerSize > 16 * 1024) throw SdocxFormatException("Invalid layer header size $headerSize")
        lr.skip(headerSize - 4)
        val objectCount = lr.readU32().toInt()
        for (i in 0 until objectCount) {
            parseObject(lr, 0, out, stringTable)
        }
        if (lr.remaining < 32) throw SdocxFormatException("Truncated layer integrity trailer")
        lr.skip(32)
    }

    private fun parseObject(
        lr: SdocxReader,
        depth: Int,
        out: PageContent,
        stringTable: Map<Int, String>,
    ) {
        if (depth >= 64) throw SdocxFormatException("Object nesting too deep")
        if (lr.remaining < 7) throw SdocxFormatException("Truncated object header")
        val type = lr.readU8()
        val childCount = lr.readU16()
        val declaredSize = lr.readU32().toInt()
        if (declaredSize < 32) throw SdocxFormatException("Object size smaller than trailer")
        val payloadSize = declaredSize - 32
        if (lr.remaining < payloadSize + 32) throw SdocxFormatException("Object payload outside page")
        val payload = try {
            lr.readBytes(payloadSize)
        } catch (_: Exception) {
            throw SdocxFormatException("Object payload outside page")
        }
        lr.skip(32)
        try {
            when (type) {
                // Strokes are type 1; type 15 is the development-version stroke
                // (same binary layout) that newer Samsung Notes uses for some
                // pens — including highlighters. Type 18 (brush) shares the
                // channel layout too, so attempt it rather than dropping ink.
                1, 15 -> decodeStroke(payload, stringTable)?.let { out.strokes.add(it) }
                2 -> {
                    val tb = try { decodeTextBox(payload) } catch (_: Exception) { null }
                    tb?.let { out.textBoxes.add(it) }
                    // Inline photos attached to a text run live in the text
                    // payload's object spans, which the textbox decoder
                    // skips: salvage every image fill so they still import.
                    try {
                        val box = tb?.bbox ?: readObjectMeta(payload)?.bbox
                        if (box != null) {
                            for (id in scanImageBindIds(payload)) {
                                out.images.add(SdocxImage(box, id, null))
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                3 -> decodeImage(payload)?.let { out.images.add(it) }
                // Type 4 containers only group children (walked below); types 7/8
                // are shapes and lines; type 10 is a voice memo. Tables (22),
                // paintings, videos and stickers stay skipped: the reference
                // SDKs decode no structure for them either.
                // Photos are sometimes stored as shapes with a bitmap fill
                // instead of type-3 image objects: emit both the outline (if
                // any) and every image fill found in the payload.
                7 -> {
                    decodeShape(payload, stringTable)?.let { out.shapes.add(it) }
                    for (id in scanImageBindIds(payload)) {
                        val box = try {
                            decodeShape(payload, stringTable)?.geoBox
                        } catch (_: Exception) {
                            null
                        } ?: readObjectMeta(payload)?.bbox ?: continue
                        out.images.add(SdocxImage(box, id, null))
                    }
                }
                8 -> {
                    // Type 8 is a line on modern writers, but very old writers
                    // used 8 for audio: try the line first, then voice.
                    decodeLine(payload)?.let { out.lines.add(it) }
                        ?: decodeVoice(payload)?.let { out.voices.add(it) }
                }
                10 -> decodeVoice(payload)?.let { out.voices.add(it) }
                18 -> decodeStroke(payload, stringTable)?.let { out.strokes.add(it) }
            }
        } catch (_: Exception) {
        }
        for (c in 0 until childCount) {
            parseObject(lr, depth + 1, out, stringTable)
        }
    }

    private class Frame(
        val kind: Int,
        val properties: SdocxMask,
        val fields: SdocxMask,
        val fixed: ByteArray,
        val flexible: ByteArray,
    )

    private fun readFrame(r: SdocxReader): Frame {
        val size = r.readU32().toInt()
        if (size < 12) throw SdocxFormatException("Invalid frame size $size")
        val start = r.offset
        val end = start - 4 + size
        if (end > r.data.size) throw SdocxFormatException("Frame outside payload")
        val kind = r.readI16()
        val flexOffset = r.readU32().toInt()
        val properties = SdocxMask.read(r)
        val fields = SdocxMask.read(r)
        val headerEnd = r.offset
        val fixedEnd: Int = if (flexOffset == 0 && fields.isEmpty) end
        else {
            // Mirrors the reference decoder: the flexible block may start
            // right after the header (empty fixed part, as text shape-frames
            // and voice frames do), it just must not start inside it.
            val v = (start - 4 + flexOffset)
            if (v < headerEnd || v > end) throw SdocxFormatException("Frame flexible offset outside frame")
            v
        }
        val fixed = r.data.copyOfRange(headerEnd, fixedEnd)
        val flexible = r.data.copyOfRange(fixedEnd, end)
        r.seek(end)
        return Frame(kind, properties, fields, fixed, flexible)
    }

    private class ObjectMeta(
        val uuid: String,
        val bbox: SdocxRect,
        val visible: Boolean,
        val rotationDegrees: Double?,
    )

    private fun readObjectMeta(payload: ByteArray): ObjectMeta? {
        try {
            val r = SdocxReader(payload)
            val frame = readFrame(r)
            if (frame.kind != 0) return null
            val fixed = SdocxReader(frame.fixed)
            fixed.skip(4)
            val uuid = fixed.readUtf8U16()
            fixed.skip(8)
            val left = fixed.readF64()
            val top = fixed.readF64()
            val right = fixed.readF64()
            val bottom = fixed.readF64()
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
            fixed.skip(4 + 1)
            val visible = frame.properties.contains(3)
            var rotation: Double? = null
            if (frame.fields.contains(0)) {
                val flex = SdocxReader(frame.flexible)
                rotation = flex.readF32().toDouble()
                if (!rotation.isFinite()) return null
            }
            return ObjectMeta(uuid, SdocxRect(left, top, right, bottom), visible, rotation)
        } catch (_: Exception) {
            return null
        }
    }

    private fun signedDelta(raw: Int, divisor: Double): Double {
        val mag = (raw and 0x7FFF) / divisor
        return if ((raw and 0x8000) == 0) mag else -mag
    }

    fun decodeStroke(payload: ByteArray, stringTable: Map<Int, String> = emptyMap()): SdocxStroke? {
        val meta = readObjectMeta(payload) ?: return null
        if (!meta.visible) return null
        val r = SdocxReader(payload)
        readFrame(r)
        if (r.isEof) return null
        val frame = readFrame(r)
        if (frame.kind != 1) return null
        if (frame.properties.contains(3)) return null // eraser

        val fixed = SdocxReader(frame.fixed)
        if (fixed.remaining < 2) return null
        val pointCount = fixed.readU16()
        if (pointCount == 0 || pointCount > 65535) return null

        val compressed = frame.properties.contains(0)
        val stylus = frame.properties.contains(2)
        val channelBytes: Int = if (pointCount == 0) 0
        else if (compressed) 16 + (pointCount - 1) * 4 + (2 + (if (stylus) 2 else 0)) * (4 + (pointCount - 1) * 2)
        else pointCount * (24 + (if (stylus) 8 else 0))
        // Newer writers may append trailing fixed bytes after the tool type
        // (future extensions); require at least the known layout and ignore
        // any surplus so a single new field cannot drop a whole stroke.
        if (fixed.remaining < channelBytes + 2) return null
        val channelData = fixed.readBytes(channelBytes)
        val toolTypeRaw = fixed.readU16()
        // Ignore any trailing fixed bytes from newer writers.

        val cd = SdocxReader(channelData)
        val points = ArrayList<SdocxPoint>()
        val pressures = ArrayList<Double>()
        if (compressed) {
            var x = cd.readF64()
            var y = cd.readF64()
            if (!x.isFinite() || !y.isFinite()) return null
            points.add(SdocxPoint(x, y))
            for (i in 1 until pointCount) {
                val dxRaw = cd.readU16()
                val dyRaw = cd.readU16()
                x += signedDelta(dxRaw, 32.0)
                y += signedDelta(dyRaw, 32.0)
                if (!x.isFinite() || !y.isFinite()) return null
                points.add(SdocxPoint(x, y))
            }
        } else {
            for (i in 0 until pointCount) {
                val x = cd.readF64()
                val y = cd.readF64()
                if (!x.isFinite() || !y.isFinite()) return null
                points.add(SdocxPoint(x, y))
            }
        }
        if (pointCount > 0) {
            var p = cd.readF32().toDouble()
            if (!p.isFinite()) return null
            pressures.add(clamp01(p))
            for (i in 1 until pointCount) {
                p = if (compressed) {
                    val raw = cd.readU16()
                    p + signedDelta(raw, 4096.0)
                } else {
                    cd.readF32().toDouble()
                }
                if (!p.isFinite()) return null
                pressures.add(clamp01(p))
            }
        }

        var colorArgb: Int? = null
        var penWidth = 4.5
        var penNameId: Int? = null
        var advancedPenSettingId: Int? = null
        var legacyPenNameId: Int? = null
        try {
            val style = readStrokeStyle(frame)
            colorArgb = style.colorArgb
            if (style.penSize != null && style.penSize.isFinite() && style.penSize > 0 && style.penSize < 200) {
                penWidth = style.penSize.toDouble()
            }
            penNameId = style.penNameId
            advancedPenSettingId = style.advancedPenSettingId
            legacyPenNameId = style.legacyPenNameId
        } catch (_: Exception) {
        }
        // Single-tap dots (highlighter dabs, dotted pens) carry one point;
        // duplicate it slightly so they import as a visible dot instead of
        // vanishing entirely.
        if (points.size == 1) {
            val q = points[0]
            points.add(SdocxPoint(q.x + 0.75, q.y + 0.75))
            if (pressures.isNotEmpty()) pressures.add(pressures[0])
        }
        if (points.size < 2) return null
        return SdocxStroke(
            points = points,
            pressures = pressures,
            colorArgb = colorArgb,
            penWidth = penWidth,
            bbox = meta.bbox,
            penName = resolvePenName(stringTable, penNameId = penNameId, legacyPenNameId = legacyPenNameId),
            penNameId = penNameId ?: legacyPenNameId,
            advancedSettingId = advancedPenSettingId,
            toolTypeRaw = toolTypeRaw,
        )
    }

    fun resolvePenName(stringTable: Map<Int, String>, penNameId: Int?, legacyPenNameId: Int?): String? {
        val useId = if (penNameId != null && penNameId != -1) penNameId else legacyPenNameId
        if (useId == null || useId < 0) return null
        val full = stringTable[useId] ?: return null
        if (full.isEmpty()) return null
        val prefix = "com.samsung.android.sdk.pen.pen.preload."
        if (full.startsWith(prefix)) return full.substring(prefix.length)
        if (full.contains(".")) return full.split(".").last()
        return full
    }

    private fun clamp01(v: Double): Double = when {
        v.isNaN() -> 0.5
        v < 0 -> 0.0
        v > 1 -> 1.0
        else -> v
    }

    private class StrokeStyle(
        val colorArgb: Int?,
        val penSize: Float?,
        val legacyPenNameId: Int?,
        val advancedPenSettingId: Int?,
        val penNameId: Int?,
    )

    private fun readStrokeStyle(frame: Frame): StrokeStyle {
        var colorArgb: Int? = null
        var penSize: Float? = null
        var legacyPenNameId: Int? = null
        var advancedPenSettingId: Int? = null
        var penNameId: Int? = null
        val flex = SdocxReader(frame.flexible)
        if (frame.fields.contains(0)) legacyPenNameId = flex.readI32()
        if (frame.fields.contains(1)) advancedPenSettingId = flex.readI32()
        if (frame.fields.contains(2)) colorArgb = flex.readU32().toInt()
        if (frame.fields.contains(3)) penSize = flex.readF32()
        if (frame.fields.contains(4)) flex.skip(1)
        if (!frame.fields.contains(5) && frame.fields.contains(7)) penNameId = flex.readI32()
        return StrokeStyle(colorArgb, penSize, legacyPenNameId, advancedPenSettingId, penNameId)
    }

    fun decodeImage(payload: ByteArray): SdocxImage? {
        val meta = readObjectMeta(payload) ?: return null
        if (!meta.visible) return null
        // Strict path first: 0 + 6 + 7 frame chain with an image fill.
        // Any deviation falls through to the bind-id scan below so real
        // photos are never dropped by a rigid layout check.
        var strictId: Int? = null
        var strictOk = false
        try {
            val r = SdocxReader(payload)
            readFrame(r)
            if (!r.isEof) {
                val shapeBase = readFrame(r)
                if (shapeBase.kind == 6 && !r.isEof) {
                    val shape = readFrame(r)
                    if (shape.kind == 7) {
                        strictOk = true
                        try {
                            val flex = SdocxReader(shape.flexible)
                            if (!shape.fields.contains(5)) {
                                strictId = null
                            } else {
                                if (shape.fields.contains(0)) {
                                    val n = flex.readU32().toInt()
                                    flex.skip(n)
                                }
                                if (shape.fields.contains(1)) flex.skip(1)
                                if (shape.fields.contains(2)) flex.skip(4)
                                if (shape.fields.contains(3)) {
                                    strictId = null
                                } else {
                                    if (shape.fields.contains(4)) flex.skip(4)
                                    val fillSize = flex.readU32().toInt()
                                    val fillKind = flex.readU8()
                                    if (fillKind != 2) {
                                        strictId = null
                                    } else if (fillSize > flex.remaining + 1) {
                                        strictId = null
                                    } else {
                                        val fill = SdocxReader(flex.readBytes(fillSize))
                                        fill.skip(1)
                                        val rawId = fill.readI32()
                                        strictId = if (rawId >= 0) rawId else null
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            strictId = null
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (strictOk) return SdocxImage(meta.bbox, strictId ?: scanImageBindId(payload), meta.rotationDegrees)
        // Non-standard chain: still a placed raster when an image fill is
        // present. Keep the stored bbox and the scanned bind id.
        return SdocxImage(meta.bbox, scanImageBindId(payload), meta.rotationDegrees)
    }

    /**
     * Best-effort scan for an image-fill bind id (`fillSize + kind(2) +
     * imageType + bindId`) anywhere in [payload]. Returns the first plausible
     * non-negative id, or null. Strict field walking misses real writers that
     * store photos as shapes or reorder flexible fields; the fill magic
     * (`u32 size` + `u8 kind == 2`) plus a sane id is distinctive enough.
     */
    fun scanImageBindId(payload: ByteArray): Int? {
        var i = 0
        // Need at least size(4) + kind(1) + imageType(1) + bindId(4).
        while (i + 10 <= payload.size) {
            val size = leI32(payload, i)
            if (size in 32..512 && i + 4 + 1 <= payload.size && payload[i + 4] == 2.toByte()) {
                val bindOff = i + 4 + 1 + 1
                if (bindOff + 4 <= payload.size) {
                    val rawId = leI32(payload, bindOff)
                    if (rawId in 0..1_000_000) return rawId
                }
            }
            i++
        }
        return null
    }

    /** All plausible image-fill bind ids in [payload] (photos stored as shapes). */
    fun scanImageBindIds(payload: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 10 <= payload.size) {
            val size = leI32(payload, i)
            if (size in 32..512 && payload[i + 4] == 2.toByte()) {
                val bindOff = i + 4 + 1 + 1
                if (bindOff + 4 <= payload.size) {
                    val rawId = leI32(payload, bindOff)
                    if (rawId in 0..1_000_000 && !out.contains(rawId)) out.add(rawId)
                }
            }
            i++
        }
        return out
    }

    // ------------------------------------------------------------------
    // Rich text (text boxes + shape labels): `0 + 6 + 7` frame chains whose
    // kind-7 flexible block carries a `TextCommon` payload.
    // ------------------------------------------------------------------

    private class SdocxSpan(val kind: Int, val start: Int, val end: Int, val payload: ByteArray)
    private class SdocxPara(val kind: Int, val start: Int, val end: Int, val payload: ByteArray)

    private class SdocxRichText(
        val text: String,
        val spans: List<SdocxSpan>,
        val paragraphs: List<SdocxPara>,
        val images: List<SdocxImage> = emptyList(),
    )

    /**
     * Decode a `TextCommon` blob (a u32 size followed by that many bytes):
     * plain text, style spans, paragraphs and embedded objects. Margins,
     * gravity and sections are read when present and skipped; embedded
     * photos (object spans of type 3, e.g. pictures pasted into Samsung
     * text) decode to placed images, everything else stays skipped, so
     * older and newer writers both parse.
     */
    private fun decodeTextCommon(data: ByteArray): SdocxRichText {
        val r = SdocxReader(data)
        val size = r.readU32().toInt()
        if (size < 0 || size > r.remaining) throw SdocxFormatException("Text common outside its frame")
        val c = SdocxReader(r.readBytes(size))
        val text = c.readUtf16U32()
        val spanCount = c.readU32().toInt()
        if (spanCount < 0 || spanCount > 100_000) throw SdocxFormatException("Unreasonable span count $spanCount")
        val spans = ArrayList<SdocxSpan>(minOf(spanCount, 1024))
        for (i in 0 until spanCount) {
            val recSize = c.readU16()
            if (recSize < 16) throw SdocxFormatException("Style span smaller than its header")
            val rec = SdocxReader(c.readBytes(recSize))
            val kind = rec.readU32().toInt()
            val start = rec.readU32().toInt()
            val end = rec.readU32().toInt()
            rec.skip(4) // interval type (kept as-is; whole-text spans use ClosedOpen)
            spans.add(SdocxSpan(kind, start, end, rec.readBytes(rec.remaining)))
        }
        val paraCount = c.readU32().toInt()
        if (paraCount < 0 || paraCount > 100_000) throw SdocxFormatException("Unreasonable paragraph count $paraCount")
        val paras = ArrayList<SdocxPara>(minOf(paraCount, 64))
        for (i in 0 until paraCount) {
            val recSize = c.readU16()
            if (recSize < 12) throw SdocxFormatException("Paragraph smaller than its header")
            val rec = SdocxReader(c.readBytes(recSize))
            val kind = rec.readU32().toInt()
            val start = rec.readU32().toInt()
            val end = rec.readU32().toInt()
            paras.add(SdocxPara(kind, start, end, rec.readBytes(rec.remaining)))
        }
        val images = ArrayList<SdocxImage>()
        // Margins (4 x f32) + gravity (u8) when present; sections and embedded
        // objects after them are skipped (xnotes boxes hold plain text only).
        if (c.remaining >= 17) {
            for (m in 0 until 4) {
                val v = c.readF32()
                if (!v.isFinite()) throw SdocxFormatException("Non-finite text margin")
            }
            c.skip(1)
        }
        if (c.remaining >= 2) {
            val sectionCount = c.readU16()
            if (sectionCount > 100_000) throw SdocxFormatException("Unreasonable section count $sectionCount")
            for (i in 0 until sectionCount) {
                if (c.remaining < 8) throw SdocxFormatException("Truncated text section")
                c.skip(8)
            }
        }
        if (c.remaining >= 8) {
            val flags = c.readU32()
            c.skip(4) // reserved
            if ((flags and 1L) != 0L) {
                val count = c.readU32().toInt()
                if (count < 0 || count > 10_000) throw SdocxFormatException("Unreasonable object span count $count")
                for (i in 0 until count) {
                    val spanSize = c.readU32().toInt()
                    if (spanSize < 0 || spanSize > c.remaining) throw SdocxFormatException("Object span outside text")
                    val spanBytes = c.readBytes(spanSize)
                    decodeEmbeddedImage(spanBytes)?.let { images.add(it) }
                }
            }
        }
        return SdocxRichText(text, spans, paras, images)
    }

    /**
     * Decode one `TextCommon` object span (`objectSize + objectType +
     * objectData + textIndex + layout + constraint`): embedded photos (type
     * 3) come back as placed images with their own bbox and media id; any
     * other span (tables, code blocks, …) stays skipped. Null when the span
     * holds no photo.
     */
    private fun decodeEmbeddedImage(span: ByteArray): SdocxImage? {
        return try {
            if (span.size < 20) return null
            val s = SdocxReader(span)
            val objectSize = s.readU32().toInt()
            val objectType = s.readU32().toInt()
            if (objectSize < 0 || objectSize > span.size - 20) return null
            val objectData = s.readBytes(objectSize)
            // The text index, layout option and constraint follow; placement
            // comes from the embedded object's own bbox, so they are unread.
            if (objectType != 3) return null
            decodeImage(objectData)
        } catch (_: Exception) {
            null
        }
    }

    private fun spanBool(spans: List<SdocxSpan>, kind: Int): Boolean? {
        // Newest whole-text record wins; payload is a LE u16 equal to 1 when set.
        for (i in spans.indices.reversed()) {
            val s = spans[i]
            if (s.kind != kind || s.payload.size < 2) continue
            val v = (s.payload[0].toInt() and 0xFF) or ((s.payload[1].toInt() and 0xFF) shl 8)
            return v == 1
        }
        return null
    }

    /** Resolve a whole-box style from [rich]: explicit whole-text spans first,
     *  then uniform per-range runs (bold/italic), matching the SDK's own rule
     *  that colour, size and underline come from whole-text spans. */
    private fun resolveBoxStyle(rich: SdocxRichText): BoxStyle {
        val len = rich.text.length
        fun wholeU32(kind: Int): Long? {
            for (i in rich.spans.indices.reversed()) {
                val s = rich.spans[i]
                if (s.kind == kind && s.start == 0 && s.end == len && s.payload.size >= 4) {
                    return (s.payload[0].toLong() and 0xFF) or ((s.payload[1].toLong() and 0xFF) shl 8) or
                        ((s.payload[2].toLong() and 0xFF) shl 16) or ((s.payload[3].toLong() and 0xFF) shl 24)
                }
            }
            return null
        }
        val color = wholeU32(1)?.toInt()
        var size: Float? = null
        wholeU32(3)?.let {
            val f = java.lang.Float.intBitsToFloat(it.toInt())
            if (f.isFinite()) size = f
        }
        var bold = spanBool(rich.spans, 5)
        var italic = spanBool(rich.spans, 6)
        if ((bold == null || italic == null) && len > 0) {
            // Uniform per-range styling (e.g. every run bold) counts as box styling.
            val covered = BooleanArray(len)
            var allBold = true
            var allItalic = true
            for (s in rich.spans) {
                if ((s.kind != 5 && s.kind != 6) || s.payload.size < 2) continue
                val on = ((s.payload[0].toInt() and 0xFF) or ((s.payload[1].toInt() and 0xFF) shl 8)) == 1
                val a = s.start.coerceIn(0, len)
                val b = s.end.coerceIn(0, len)
                if (a >= b) continue
                for (k in a until b) covered[k] = true
                if (s.kind == 5 && !on) allBold = false
                if (s.kind == 6 && !on) allItalic = false
            }
            if (bold == null) bold = allBold && covered.all { it }
            if (italic == null) italic = allItalic && covered.all { it }
        }
        var align = 0
        for (p in rich.paragraphs) {
            if (p.kind == 3 && p.payload.size >= 4) {
                val v = (p.payload[0].toInt() and 0xFF) or ((p.payload[1].toInt() and 0xFF) shl 8) or
                    ((p.payload[2].toInt() and 0xFF) shl 16) or ((p.payload[3].toInt() and 0xFF) shl 24)
                if (v in 0..3) {
                    align = v
                    break
                }
            }
        }
        return BoxStyle(color, size, bold == true, italic == true,
            spanBool(rich.spans, 7) == true, spanBool(rich.spans, 20) == true, align)
    }

    private class BoxStyle(
        val colorArgb: Int?,
        val fontSize: Float?,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val align: Int,
    )

    /** Decode a type-2 text box payload: base + shape-base + shape-text frames. */
    fun decodeTextBox(payload: ByteArray): SdocxTextBox? {
        val meta = readObjectMeta(payload) ?: return null
        if (!meta.visible) return null
        return try {
            val r = SdocxReader(payload)
            readFrame(r) // kind 0
            if (r.isEof) return null
            val shapeBase = readFrame(r)
            if (shapeBase.kind != 6) return null
            if (r.isEof) return null
            val shapeText = readFrame(r)
            if (shapeText.kind != 7) return null
            // The shape-text frame carries no fixed geometry for text; the
            // style-independent tail (kind 2) carries border settings xnotes
            // boxes do not have, so both are skipped after the text is read.
            if (!shapeText.fields.contains(0)) return null
            val flex = SdocxReader(shapeText.flexible)
            val rich = decodeTextCommon(flex.readBytes(flex.remaining))
            if (rich.text.isEmpty()) return null
            val st = resolveBoxStyle(rich)
            SdocxTextBox(meta.bbox, rich.text, st.colorArgb, st.fontSize, st.bold, st.italic,
                st.underline, st.strike, st.align, meta.rotationDegrees)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Shapes (type 7), lines (type 8) and voice memos (type 10).
    // ------------------------------------------------------------------

    private class SdocxShapeStyle(
        val colorArgb: Int?,
        val width: Float?,
        val dashed: Boolean,
        val headStart: Boolean,
        val headEnd: Boolean,
    )

    /**
     * Best-effort outline style from a kind-6 shape-base frame: solid line
     * colour (bit 2) and width/dash/heads (bit 3). Anything else keeps its
     * defaults; null only when the frame itself will not parse.
     */
    private fun readShapeBaseStyle(shapeBase: Frame): SdocxShapeStyle? {
        return try {
            val fixed = SdocxReader(shapeBase.fixed)
            if (fixed.remaining < 9) return null
            val magnets = fixed.readU32().toInt()
            if (magnets < 0 || magnets > 4096) return null
            fixed.skip(magnets * 16)
            val connSize = fixed.readU32().toInt()
            if (connSize < 0 || connSize > fixed.remaining) return null
            fixed.skip(connSize)
            if (fixed.remaining < 1) return null
            fixed.skip(1) // reserved
            var color: Int? = null
            var width: Float? = null
            var dashed = false
            var headStart = false
            var headEnd = false
            val flex = SdocxReader(shapeBase.flexible)
            if (shapeBase.fields.contains(2)) {
                try {
                    val size = flex.readU32().toInt()
                    if (size >= 0 && size <= flex.remaining) {
                        val blob = SdocxReader(flex.readBytes(size))
                        SdocxMask.read(blob) // colour properties
                        if (blob.remaining >= 5) {
                            val kind = blob.readU8()
                            val argb = blob.readU32().toInt()
                            if (kind == 0) color = argb
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (shapeBase.fields.contains(3)) {
                try {
                    val size = flex.readU32().toInt()
                    if (size >= 0 && size <= flex.remaining) {
                        val blob = SdocxReader(flex.readBytes(size))
                        if (blob.remaining >= 12) {
                            val w = blob.readF32()
                            if (w.isFinite() && w > 0 && w < 1000) width = w
                            blob.skip(1) // compound
                            dashed = blob.readU8() != 0
                            blob.skip(1 + 1) // cap, join
                            headStart = blob.readU8() != 0
                            blob.skip(1)
                            headEnd = blob.readU8() != 0
                        }
                    }
                } catch (_: Exception) {
                }
            }
            SdocxShapeStyle(color, width, dashed, headStart, headEnd)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decode a simple native path (move/line/close verbs only) into subpaths.
     * Coordinates must be finite and land inside [bbox] (inflated slightly);
     * anything else — arcs, ovals, curves, out-of-bounds geometry — returns
     * null so the caller falls back to the shape's template or skips it.
     */
    private fun simpleNativePath(path: ByteArray, bbox: SdocxRect): List<SdocxSubPath>? {
        return try {
            val r = SdocxReader(path)
            val count = r.readU32().toInt()
            if (count <= 0 || count > 100_000) return null
            val out = ArrayList<SdocxSubPath>()
            var cur = ArrayList<SdocxPoint>()
            var curClosed = false
            var seenMove = false
            val pad = 2.0 + maxOf(bbox.width, bbox.height) * 0.05
            fun inside(p: SdocxPoint): Boolean =
                p.x.isFinite() && p.y.isFinite() &&
                    p.x >= bbox.left - pad && p.x <= bbox.right + pad &&
                    p.y >= bbox.top - pad && p.y <= bbox.bottom + pad
            for (i in 0 until count) {
                if (r.remaining < 1) return null
                when (r.readU8()) {
                    1 -> {
                        if (cur.size >= 2) out.add(SdocxSubPath(cur, curClosed))
                        else if (cur.size == 1 && curClosed) out.add(SdocxSubPath(cur, true))
                        cur = ArrayList()
                        curClosed = false
                        if (r.remaining < 16) return null
                        val p = SdocxPoint(r.readF64(), r.readF64())
                        if (!inside(p)) return null
                        cur.add(p)
                        seenMove = true
                    }
                    2 -> {
                        if (!seenMove || r.remaining < 16) return null
                        val p = SdocxPoint(r.readF64(), r.readF64())
                        if (!inside(p)) return null
                        cur.add(p)
                    }
                    6 -> {
                        if (!seenMove) return null
                        curClosed = true
                    }
                    else -> return null
                }
            }
            if (cur.size >= 2) out.add(SdocxSubPath(cur, curClosed))
            else if (cur.size == 1 && curClosed) out.add(SdocxSubPath(cur, true))
            if (out.isEmpty()) null else out
        } catch (_: Exception) {
            null
        }
    }

    /** Decode a type-7 shape payload. */
    fun decodeShape(payload: ByteArray, stringTable: Map<Int, String> = emptyMap()): SdocxShape? {
        val meta = readObjectMeta(payload) ?: return null
        if (!meta.visible) return null
        return try {
            val r = SdocxReader(payload)
            readFrame(r) // kind 0
            if (r.isEof) return null
            val shapeBase = readFrame(r)
            if (shapeBase.kind != 6) return null
            if (r.isEof) return null
            val shape = readFrame(r)
            if (shape.kind != 7) return null
            val style = readShapeBaseStyle(shapeBase)
            val fixed = SdocxReader(shape.fixed)
            if (fixed.remaining < 4 + 32 + 4) return null
            val shapeType = fixed.readU32().toInt()
            val gx0 = fixed.readF64()
            val gy0 = fixed.readF64()
            val gx1 = fixed.readF64()
            val gy1 = fixed.readF64()
            if (!gx0.isFinite() || !gy0.isFinite() || !gx1.isFinite() || !gy1.isFinite()) return null
            val geo = SdocxRect(minOf(gx0, gx1), minOf(gy0, gy1), maxOf(gx0, gx1), maxOf(gy0, gy1))
            val rotation = fixed.readF32().toDouble().takeIf { it.isFinite() }
            if (fixed.remaining < 4) return null
            val pathSize = fixed.readU32().toInt()
            if (pathSize < 0 || pathSize > fixed.remaining) return null
            val pathBytes = fixed.readBytes(pathSize)
            var controlPoints = 0
            if (fixed.remaining >= 1) {
                controlPoints = fixed.readU8()
                if (controlPoints < 0 || controlPoints * 16 > fixed.remaining) return null
                fixed.skip(controlPoints * 16)
            }
            // Trailing drawn-bbox and extensions are not needed for import.
            val subpaths = if (pathBytes.isNotEmpty()) simpleNativePath(pathBytes, geo) else null
            var label: SdocxTextBox? = null
            try {
                val flex = SdocxReader(shape.flexible)
                if (shape.fields.contains(0)) {
                    val rich = decodeTextCommon(flex.readBytes(flex.remaining))
                    // Only the leading text-common block belongs to the label;
                    // later fields (area type, pen ids, fill) are skipped.
                    if (rich.text.isNotEmpty()) {
                        val st = resolveBoxStyle(rich)
                        label = SdocxTextBox(geo, rich.text, st.colorArgb, st.fontSize, st.bold,
                            st.italic, st.underline, st.strike, st.align, null)
                    }
                }
            } catch (_: Exception) {
                label = null
            }
            // NOTE: when the flexible block holds more than the label (area
            // type, pen ids, fill), only its leading text-common block is
            // consumed; the rest is intentionally ignored.
            SdocxShape(meta.bbox, geo, shapeType, rotation ?: meta.rotationDegrees,
                shape.properties.contains(0), shape.properties.contains(1),
                subpaths, style?.colorArgb, style?.width,
                style?.dashed == true, style?.headStart == true, style?.headEnd == true, label)
        } catch (_: Exception) {
            null
        }
    }

    /** Decode a type-8 line payload. */
    fun decodeLine(payload: ByteArray): SdocxLine? {
        val meta = readObjectMeta(payload) ?: return null
        if (!meta.visible) return null
        return try {
            val r = SdocxReader(payload)
            readFrame(r) // kind 0
            if (r.isEof) return null
            val shapeBase = readFrame(r)
            if (shapeBase.kind != 6) return null
            if (r.isEof) return null
            val line = readFrame(r)
            if (line.kind != 8) return null
            val style = readShapeBaseStyle(shapeBase)
            val fixed = SdocxReader(line.fixed)
            if (fixed.remaining < 1 + 1 + 1) return null
            val lineType = fixed.readU8()
            fixed.skip(1) // routing
            val ctrlCount = fixed.readU8()
            if (ctrlCount < 0 || ctrlCount > 100_000 || ctrlCount * 16 > fixed.remaining) return null
            val controls = ArrayList<SdocxPoint>(minOf(ctrlCount, 16))
            for (i in 0 until ctrlCount) {
                val p = SdocxPoint(fixed.readF64(), fixed.readF64())
                if (!p.x.isFinite() || !p.y.isFinite()) return null
                if (controls.size < 1024) controls.add(p)
            }
            if (fixed.remaining < 32) return null
            val bx = fixed.readF64()
            val by = fixed.readF64()
            val ex = fixed.readF64()
            val ey = fixed.readF64()
            if (!bx.isFinite() || !by.isFinite() || !ex.isFinite() || !ey.isFinite()) return null
            SdocxLine(SdocxPoint(bx, by), SdocxPoint(ex, ey), controls, lineType, meta.bbox,
                style?.colorArgb, style?.width, style?.dashed == true,
                style?.headStart == true, style?.headEnd == true)
        } catch (_: Exception) {
            null
        }
    }

    /** Decode a type-10 voice memo payload. */
    fun decodeVoice(payload: ByteArray): SdocxVoice? {
        val meta = readObjectMeta(payload) ?: return null
        if (!meta.visible) return null
        return try {
            val r = SdocxReader(payload)
            readFrame(r) // kind 0
            if (r.isEof) return null
            val voice = readFrame(r)
            if (voice.kind != 10) return null
            val flex = SdocxReader(voice.flexible)
            if (flex.remaining < 4) return null
            val mediaId = flex.readI32()
            if (flex.remaining < 2) return null
            val title = flex.readUtf16U16()
            if (title.length > 200 || flex.remaining < 2) return null
            val durationStr = flex.readUtf16U16()
            if (durationStr.length > 32) return null
            // Layout bytes after the duration describe the pill and are not needed.
            SdocxVoice(meta.bbox, mediaId.takeIf { it >= 0 }, title, parseVoiceDuration(durationStr))
        } catch (_: Exception) {
            null
        }
    }

    /** Parse a memo duration label ("mm:ss" or "hh:mm:ss") into milliseconds. */
    fun parseVoiceDuration(s: String): Int {
        return try {
            val parts = s.trim().split(":")
            if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.length > 4 }) return 0
            val nums = parts.map { it.toIntOrNull() ?: return 0 }
            if (nums.any { it < 0 || it > 999 }) return 0
            val secs = if (nums.size == 2) nums[0] * 60 + nums[1] else nums[0] * 3600 + nums[1] * 60 + nums[2]
            (secs.toLong() * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (_: Exception) {
            0
        }
    }
}
