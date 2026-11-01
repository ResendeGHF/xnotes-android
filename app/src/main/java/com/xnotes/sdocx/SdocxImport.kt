package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.StickyNoteItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Converts a parsed Samsung Notes document into an xnotes [Document].
 *
 * Covers pages, strokes (+ colors/widths, including type-15 development
 * strokes and single-tap dots), raster images (page objects,
 * page-background and note-body photos), single-PDF backgrounds, text boxes
 * (+ shape labels), basic shapes and straight lines (as editable shapes),
 * free outlines (as polygon/polyline shapes), tables (type-22 page objects
 * and note-body tables, with cell values), sticky memos (nested `.sdocx`
 * sub-notes as [StickyNoteItem]) and voice memos (as audio pills, any
 * container sniffed by magic).
 *
 * Deliberately dropped: fills on imported shapes (outlines only), text
 * rotation (boxes land upright), mixed per-range styling (collapses to
 * whole-box bold/italic), custom fonts (Samsung's font names map to the sans
 * face), video/painting media and containers' hidden layers.
 */
object SdocxImport {
    fun toDocument(
        sdocx: SdocxDocument,
        dpi: Int,
        imageDir: File,
        pdfDir: File,
        imageCodec: ImageCodec,
        textMeasurer: TextMeasurer,
    ): Document? {
        if (sdocx.pages.isEmpty()) return null
        val doc = Document(dpi = dpi)
        if (!sdocx.title.isNullOrBlank()) {
            doc.displayName = sdocx.title
        }

        // Single-PDF case: the media referenced by the most pages wins. Pages
        // referencing any other PDF import as plain pages (strokes preserved).
        val pdfRefs = sdocx.pages.flatMap { it.pdfPapers }.map { it.mediaId }
        val chosenPdfId: Int? = pdfRefs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        var pdfFile: File? = null
        if (chosenPdfId != null) {
            val entry = sdocx.mediaByBindId[chosenPdfId]
            val bytes = entry?.bytes
            if (bytes != null && bytes.isNotEmpty() && (entry?.isPdf != false)) {
                try {
                    val f = File.createTempFile("sdocx-pdf", ".pdf", pdfDir)
                    f.writeBytes(bytes)
                    pdfFile = f
                } catch (_: Exception) {
                    pdfFile = null
                }
            }
        }
        if (pdfFile != null) doc.pdfFile = pdfFile

        var nextRid = 0
        val (fallbackW, fallbackH) = PageSize.A4.pixels(Orientation.PORTRAIT, dpi)
        for (spage in sdocx.pages) {
            val w = saneDim(spage.width, fallbackW)
            val h = saneDim(spage.height, fallbackH)
            val page = Page(w, h)

            var style = PageStyle()
            if (spage.backgroundColorArgb != null) {
                style = style.copy(pageColor = Rgba.fromArgb(spage.backgroundColorArgb))
            }
            val pattern = SdocxMapping.samsungTemplateToPattern(spage.templateType, spage.templateUri ?: sdocx.defaultTemplateUri)
            if (pattern != "none") {
                style = style.copy(template = pattern)
            }
            page.style = style

            val paper = spage.pdfPapers.firstOrNull { it.mediaId == chosenPdfId && it.pageIndex >= 0 }
                ?: spage.pdfPapers.firstOrNull { chosenPdfId == null && it.pageIndex >= 0 }
            if (paper != null && pdfFile != null && paper.mediaId == chosenPdfId) {
                page.pdfPage = paper.pageIndex
            }

            for (s in spage.strokes) {
                toStroke(s)?.let { page.items.add(it) }
            }
            // Page-background photos (bgImageId) carry real images on some
            // Samsung writers: import them edge-to-edge under the ink.
            spage.backgroundImageId?.let { bgId ->
                toBackgroundImage(bgId, sdocx, imageDir, imageCodec, w, h)?.let { page.items.add(0, it) }
            }
            for (im in spage.images) {
                toImage(im, sdocx, imageDir, imageCodec)?.let { page.items.add(it) }
            }
            for (t in spage.textBoxes) {
                toTextBox(t, textMeasurer)?.let { page.items.add(it) }
            }
            for (sh in spage.shapes) {
                page.items.addAll(toShapeItems(sh, textMeasurer))
            }
            for (ln in spage.lines) {
                page.items.addAll(toLineItems(ln))
            }
            for (v in spage.voices) {
                toVoice(v, sdocx, imageDir, nextRid++, dpi, textMeasurer)?.let { page.items.add(it) }
            }
            for (t in spage.tables) {
                toTable(t, w, h, textMeasurer, flowCoords = false)?.let { page.items.add(it) }
            }

            // Drop fully-empty shells (attachment collapses, trailing blanks) but keep
            // pattern-only pages and meaningfully-tinted pages: they are real
            // content. The default paper tint alone is not meaningful. Keep at
            // least one page overall.
            val hasPdf = page.pdfPage != null
            val defaultBg = 0xFFFCFCFC.toInt()
            val hasMeaningfulStyle = pattern != "none" ||
                (spage.backgroundColorArgb != null && spage.backgroundColorArgb != defaultBg)
            if (page.items.isEmpty() && !hasPdf && !hasMeaningfulStyle) continue
            doc.pages.add(page)
        }

        // Photos pasted into Samsung text live in note.note's body, not in any
        // page: lay each on its own page so none vanish. Empty pages take one
        // first (never covering ink or PDF paper); the rest append new pages.
        if (sdocx.bodyImages.isNotEmpty()) {
            val sized = doc.pages.firstOrNull()?.let { Pair(it.width, it.height) }
                ?: sdocx.pages.firstOrNull()?.let {
                    Pair(saneDim(it.width, fallbackW), saneDim(it.height, fallbackH))
                }
                ?: Pair(fallbackW, fallbackH)
            for (im in sdocx.bodyImages) {
                val fitted = fitBodyImage(im, sized.first, sized.second) ?: continue
                val item = toImage(fitted, sdocx, imageDir, imageCodec) ?: continue
                val target = doc.pages.firstOrNull { it.items.isEmpty() && it.pdfPage == null }
                    ?: Page(sized.first, sized.second).also { doc.pages.add(it) }
                target.items.add(item)
            }
        }

        // Tables pasted into Samsung text (note.note body) land like body photos:
        // fit-and-clamp into the page, reusing empty pages first.
        if (sdocx.bodyTables.isNotEmpty()) {
            val sized = doc.pages.firstOrNull()?.let { Pair(it.width, it.height) }
                ?: sdocx.pages.firstOrNull()?.let {
                    Pair(saneDim(it.width, fallbackW), saneDim(it.height, fallbackH))
                }
                ?: Pair(fallbackW, fallbackH)
            for (t in sdocx.bodyTables) {
                val item = toTable(t, sized.first, sized.second, textMeasurer, flowCoords = true) ?: continue
                val target = doc.pages.firstOrNull { it.items.isEmpty() && it.pdfPage == null }
                    ?: Page(sized.first, sized.second).also { doc.pages.add(it) }
                target.items.add(item)
            }
        }

        importStickyMemos(sdocx, doc, textMeasurer)

        if (doc.pages.isEmpty()) {
            // All shells: keep one page so the note is never page-less.
            val first = sdocx.pages.first()
            val w = saneDim(first.width, fallbackW)
            val h = saneDim(first.height, fallbackH)
            doc.pages.add(Page(w, h))
        }
        doc.created = System.currentTimeMillis()
        return doc
    }

    private fun saneDim(v: Double, fallback: Double): Double {
        if (!v.isFinite() || v < 200 || v > 5000) return fallback
        return v
    }

    fun toStroke(s: SdocxStroke): Stroke? {
        if (s.points.size < 2) return null
        val tool = SdocxMapping.samsungPenToTool(s.penName, s.colorArgb)
        val base = ToolDefaults.configFor(tool)
        val argb = s.colorArgb ?: Rgba(20, 20, 20, 255).toArgb()
        val color = Rgba.fromArgb(argb)
        val width = s.penWidth.takeIf { it.isFinite() && it > 0 }?.coerceIn(1.0, 48.0)
            ?: if (tool == Tool.HIGHLIGHTER) 24.0 else 8.0
        val config = if (tool == Tool.HIGHLIGHTER) {
            // Samsung alpha is carried in the color; xnotes multiplies it at render.
            // Keep the hue opaque and move the translucency into highlighterAlpha.
            val alpha = color.a
            base.copy(
                baseWidth = width,
                rgba = color.copy(a = 255),
                pressureEnabled = false,
                pressureMinFactor = 1.0,
                highlighterAlpha = (alpha / 255.0).coerceIn(0.10, 1.0),
            )
        } else {
            base.copy(baseWidth = width, rgba = color)
        }
        val stroke = Stroke(tool, config)
        val n = s.points.size
        val samples = ArrayList<Sample>(n)
        for (i in 0 until n) {
            val p = s.points[i]
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            val pressure = if (i < s.pressures.size) s.pressures[i].coerceIn(0.0, 1.0) else 0.5
            samples.add(Sample(p.x, p.y, pressure))
        }
        if (samples.size < 2) return null
        stroke.setSamples(samples)
        stroke.finished = true
        return stroke
    }

    /**
     * Note-body photo placement: text-flow coordinates are meaningless outside
     * Samsung's layout, so keep the stored size (scaled to fit an oversized
     * photo) and clamp it into the page, guaranteeing every photo lands
     * visibly. Null when the stored bbox is unusable.
     */
    private fun fitBodyImage(im: SdocxImage, pageW: Double, pageH: Double): SdocxImage? {
        var w = im.bbox.width
        var h = im.bbox.height
        if (!(w > 0) || !(h > 0) || !w.isFinite() || !h.isFinite()) return null
        if (!(pageW > 0) || !(pageH > 0) || !pageW.isFinite() || !pageH.isFinite()) return null
        val scale = minOf(1.0, pageW / w, pageH / h)
        if (!scale.isFinite() || scale <= 0) return null
        w *= scale
        h *= scale
        val left = im.bbox.left.takeIf { it.isFinite() }
            ?.coerceIn(0.0, (pageW - w).coerceAtLeast(0.0)) ?: 0.0
        val top = im.bbox.top.takeIf { it.isFinite() }
            ?.coerceIn(0.0, (pageH - h).coerceAtLeast(0.0)) ?: 0.0
        return SdocxImage(SdocxRect(left, top, left + w, top + h), im.mediaId, im.rotationDegrees)
    }

    private fun toImage(
        im: SdocxImage,
        doc: SdocxDocument,
        imageDir: File,
        imageCodec: ImageCodec,
    ): ImageItem? {
        try {
            // Explicit bind id first; on failure fall through to a scan of any
            // decodable image so a stale id never drops a real photo.
            im.mediaId?.let { id ->
                val entry = doc.mediaByBindId[id]
                val bytes = entry?.bytes
                if (bytes != null && bytes.isNotEmpty()) {
                    imageItemFromBytes(bytes, entry?.fileName ?: "", im.bbox, imageDir, imageCodec)?.let { return it }
                }
            }
            // No (or unresolvable) bind id: fall back to any media bytes that
            // actually decode as an image, regardless of file extension.
            for (entry in doc.mediaByBindId.values + doc.mediaByFileName.values) {
                val bytes = entry.bytes ?: continue
                if (bytes.isEmpty()) continue
                imageItemFromBytes(bytes, entry.fileName, im.bbox, imageDir, imageCodec)?.let { return it }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /** Build an [ImageItem] in [bbox] from raw [bytes]; null when not an image. */
    private fun imageItemFromBytes(
        bytes: ByteArray,
        fileName: String,
        bbox: SdocxRect,
        imageDir: File,
        imageCodec: ImageCodec,
    ): ImageItem? {
        try {
            if (bytes.isEmpty()) return null
            val ext = imageExtFor(bytes, fileName) ?: return null
            var w = bbox.width
            var h = bbox.height
            if (!(w > 0) || !(h > 0) || !w.isFinite() || !h.isFinite()) {
                w = 400.0
                h = 400.0
            }
            val left = if (bbox.left.isFinite()) bbox.left else 0.0
            val top = if (bbox.top.isFinite()) bbox.top else 0.0
            val file = File.createTempFile("sdocx-img", ext, imageDir)
            file.writeBytes(bytes)
            // Never drop a real photo because the prober rejects it: fall
            // back to the stored bbox size so the image still shows.
            val size = try {
                imageCodec.probeFile(file.path)
            } catch (_: Exception) {
                null
            } ?: ImageSize(
                w.roundToInt().coerceIn(1, 10000),
                h.roundToInt().coerceIn(1, 10000),
            )
            val rect = Rect(left, top, w, h)
            return ImageItem(ImageData(file, size.width, size.height), rect)
        } catch (_: Exception) {
            return null
        }
    }

    /** Page-background photo referenced by bgImageId: full-page image. */
    private fun toBackgroundImage(
        bindId: Int,
        doc: SdocxDocument,
        imageDir: File,
        imageCodec: ImageCodec,
        pageW: Double,
        pageH: Double,
    ): ImageItem? {
        return try {
            val entry = doc.mediaByBindId[bindId] ?: return null
            val bytes = entry.bytes ?: return null
            if (bytes.isEmpty()) return null
            if (imageExtFor(bytes, entry.fileName) == null) return null
            imageItemFromBytes(bytes, entry.fileName, SdocxRect(0.0, 0.0, pageW, pageH), imageDir, imageCodec)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Image extension (with dot) for [bytes] named [fileName], trusting magic
     * over suffix so extension-less or mis-suffixed Samsung assets still
     * import. Null when the bytes are not a recognised image.
     */
    private fun imageExtFor(bytes: ByteArray, fileName: String): String? {
        sniffImageExt(bytes)?.let { return it }
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> ".png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> ".jpg"
            lower.endsWith(".webp") -> ".webp"
            lower.endsWith(".gif") -> ".gif"
            lower.endsWith(".bmp") -> ".bmp"
            lower.endsWith(".heic") || lower.endsWith(".heif") -> ".heic"
            lower.endsWith(".avif") -> ".avif"
            lower.endsWith(".tif") || lower.endsWith(".tiff") -> ".tif"
            lower.endsWith(".dng") -> ".dng"
            lower.endsWith(".wbmp") -> ".wbmp"
            lower.endsWith(".ico") -> ".ico"
            else -> null
        }
    }

    /** Magic-based image type sniff; null when unknown. */
    private fun sniffImageExt(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return ".jpg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) return ".png"
        if (bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() &&
            (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) && bytes[5] == 0x61.toByte()
        ) return ".gif"
        if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return ".bmp"
        if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return ".webp"
        // ISO BMFF (HEIC/HEIF/AVIF): ....ftyp + brand.
        if (bytes.size >= 12 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        ) {
            val brand = try {
                String(bytes, 8, 4, Charsets.US_ASCII).lowercase()
            } catch (_: Exception) {
                ""
            }
            if (brand == "avif") return ".avif"
            if (brand.startsWith("heic") || brand.startsWith("heix") ||
                brand.startsWith("hevc") || brand.startsWith("hevx") ||
                brand.startsWith("heim") || brand.startsWith("heis") ||
                brand.startsWith("hevm") || brand.startsWith("hevs") ||
                brand == "mif1" || brand == "msf1" || brand == "heic"
            ) return ".heic"
            if (brand == "mif1" || brand == "msf1") return ".heif"
        }
        // TIFF: II*\0 or MM\0*.
        if (bytes.size >= 4 && ((bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) ||
                (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() &&
                    bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()))
        ) return ".tif"
        // ICO: 00 00 01 00.
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() && bytes[3] == 0x00.toByte()
        ) return ".ico"
        return null
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean = sniffImageExt(bytes) != null

    /** Sniff common audio containers Samsung Notes uses; null when unknown. */
    private fun sniffAudioExt(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        // MP4/M4A/3GP: ftyp box at offset 4.
        if (bytes.size >= 12 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        ) {
            val brand = try { String(bytes, 8, 4, Charsets.US_ASCII) } catch (_: Exception) { "" }
            return when {
                brand.startsWith("3g") -> "3gp"
                brand.startsWith("M4A") || brand.startsWith("mp4") -> "m4a"
                else -> "m4a"
            }
        }
        // MP3: ID3 or frame sync.
        if (bytes.size >= 3 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) return "mp3"
        if (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return "mp3"
        // WAV: RIFF....WAVE
        if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x41.toByte()
        ) return "wav"
        // OGG.
        if (bytes[0] == 0x4F.toByte() && bytes[1] == 0x67.toByte() &&
            bytes[2] == 0x67.toByte() && bytes[3] == 0x53.toByte()
        ) return "ogg"
        // AMR.
        if (bytes.size >= 6 && bytes[0] == 0x23.toByte() && bytes[1] == 0x21.toByte() &&
            bytes[2] == 0x41.toByte() && bytes[3] == 0x4D.toByte()
        ) return "amr"
        return null
    }

    // --- text ---

    fun toTextBox(t: SdocxTextBox, measurer: TextMeasurer): TextItem? {
        try {
            if (t.text.isEmpty() || t.text.length > 100_000) return null
            val w = t.bbox.width
            if (!(w > 0) || w > 20000 || !w.isFinite()) return null
            if (!t.bbox.left.isFinite() || !t.bbox.top.isFinite()) return null
            val size = t.fontSize?.toDouble()?.takeIf { it.isFinite() && it in 4.0..200.0 }
                ?: TextItem.DEFAULT_POINT_SIZE
            // No font-name mapping: Samsung's names are device fonts, so
            // imported body text uses the neutral sans face.
            return TextItem(
                Pt(t.bbox.left, t.bbox.top),
                w,
                0.0,
                t.text,
                t.colorArgb?.let { Rgba.fromArgb(it) } ?: Rgba(20, 20, 20, 255),
                size,
                FontFace.SANS,
                measurer,
                bold = t.bold,
                italic = t.italic,
                underline = t.underline,
                strike = t.strike,
                align = SdocxMapping.samsungAlignToHAlign(t.align),
            )
        } catch (_: Exception) {
            return null
        }
    }

    // --- shapes & lines ---

    private fun shapeColor(argb: Int?): Rgba = argb?.let { Rgba.fromArgb(it) } ?: Rgba(20, 20, 20, 255)

    private fun shapeWidth(w: Float?): Double =
        w?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.coerceIn(1.0, 80.0) ?: 3.0

    /**
     * Samsung template ids renderable from their bbox alone. 4 is the
     * rectangle (confirmed by image placeholders); 2/3 need triangle insets;
     * 1 and 8 are the remaining box-like templates the reference renderer
     * draws without a native path, mapped here to ellipses as the closest
     * xnotes kind. Anything else imports through [SdocxShape.subpaths] or is
     * skipped.
     */
    private fun templateKind(shapeType: Int, w: Double, h: Double): ShapeKind? = when (shapeType) {
        4 -> ShapeKind.RECTANGLE
        2, 3 -> ShapeKind.TRIANGLE
        1, 8 -> if (w > 0 && h > 0 && kotlin.math.abs(w - h) <= 0.01 * maxOf(w, h)) ShapeKind.CIRCLE else ShapeKind.ELLIPSE
        else -> null
    }

    private fun labelOf(box: SdocxTextBox, measurer: TextMeasurer): ShapeLabel? {
        if (box.text.isEmpty()) return null
        val size = box.fontSize?.toDouble()?.takeIf { it.isFinite() && it in 4.0..200.0 }
            ?: TextItem.DEFAULT_POINT_SIZE
        return ShapeLabel(
            box.text,
            TextStyle(
                box.colorArgb?.let { Rgba.fromArgb(it) } ?: Rgba(20, 20, 20, 255),
                size,
                FontFace.SANS,
                bold = box.bold,
                italic = box.italic,
                underline = box.underline,
                strike = box.strike,
                align = SdocxMapping.samsungAlignToHAlign(box.align),
            ),
            measurer,
        )
    }

    private fun attachLabel(item: ShapeItem, box: SdocxTextBox?, measurer: TextMeasurer) {
        if (box == null) return
        labelOf(box, measurer)?.let { item.label = it }
    }

    fun toShapeItems(s: SdocxShape, measurer: TextMeasurer): List<CanvasItem> {
        try {
            val color = shapeColor(s.strokeColorArgb)
            val width = shapeWidth(s.strokeWidth)
            val heads = s.headStart || s.headEnd
            val subs = s.subpaths?.filter { it.points.size >= 2 } ?: emptyList()
            if (subs.isNotEmpty()) {
                // Exact native outline: one editable free shape per subpath.
                return subs.mapNotNull { sub ->
                    try {
                        val pts = sub.points.map { Pt(it.x, it.y) }
                        if (pts.size < 2) return@mapNotNull null
                        val kind = if (sub.closed) ShapeKind.POLYGON else ShapeKind.POLYLINE
                        val item = ShapeItem.poly(kind, pts, color, width, dashed = s.dashed)
                        if (heads) {
                            item.headStart = if (s.headStart) Head.OPEN else Head.NONE
                            item.headEnd = if (s.headEnd) Head.OPEN else Head.NONE
                        }
                        attachLabel(item, s.label, measurer)
                        item
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val kind = templateKind(s.shapeType, s.geoBox.width, s.geoBox.height) ?: return emptyList()
            if (!(s.geoBox.width > 0) || !(s.geoBox.height > 0)) return emptyList()
            val item = ShapeItem(
                kind,
                Pt(s.geoBox.left, s.geoBox.top),
                Pt(s.geoBox.right, s.geoBox.bottom),
                color,
                width,
                dashed = s.dashed,
            )
            if (heads) {
                item.headStart = if (s.headStart) Head.OPEN else Head.NONE
                item.headEnd = if (s.headEnd) Head.OPEN else Head.NONE
            }
            val rot = s.rotationDegrees
            if (rot != null && rot.isFinite() && rot != 0.0) {
                item.rotation = rot * PI / 180.0
            }
            if (s.flipH || s.flipV) {
                item.flip = com.xnotes.core.geometry.Flip.of(s.flipH, s.flipV)
            }
            attachLabel(item, s.label, measurer)
            return listOf(item)
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun toLineItems(l: SdocxLine): List<CanvasItem> {
        try {
            val color = shapeColor(l.strokeColorArgb)
            val width = shapeWidth(l.strokeWidth)
            val b = Pt(l.begin.x, l.begin.y)
            val e = Pt(l.end.x, l.end.y)
            if (!b.x.isFinite() || !b.y.isFinite() || !e.x.isFinite() || !e.y.isFinite()) return emptyList()
            val heads = l.headStart || l.headEnd
            // Elbow/curve routes become editable polylines through their
            // control points; straight lines stay lines (or arrows).
            if (l.lineType != 0 && l.controlPoints.isNotEmpty()) {
                val pts = ArrayList<Pt>(l.controlPoints.size + 2)
                pts.add(b)
                for (c in l.controlPoints) {
                    if (!c.x.isFinite() || !c.y.isFinite()) return emptyList()
                    pts.add(Pt(c.x, c.y))
                }
                pts.add(e)
                if (pts.size < 2) return emptyList()
                val item = ShapeItem.poly(ShapeKind.POLYLINE, pts, color, width, dashed = l.dashed)
                if (heads) {
                    item.headStart = if (l.headStart) Head.OPEN else Head.NONE
                    item.headEnd = if (l.headEnd) Head.OPEN else Head.NONE
                }
                return listOf(item)
            }
            if (b == e) return emptyList()
            val kind = if (heads && l.headEnd) ShapeKind.ARROW else ShapeKind.LINE
            val item = ShapeItem(kind, b, e, color, width, dashed = l.dashed)
            item.headStart = if (l.headStart) Head.OPEN else Head.NONE
            item.headEnd = if (l.headEnd || kind == ShapeKind.ARROW) Head.OPEN else Head.NONE
            return listOf(item)
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // --- voice memos ---

    fun toVoice(
        v: SdocxVoice,
        doc: SdocxDocument,
        imageDir: File,
        rid: Int,
        dpi: Int,
        measurer: TextMeasurer,
    ): AudioItem? {
        try {
            // Explicit id first with a sniff check; on failure fall through to
            // any audio payload so renamed or mis-indexed memos still import.
            v.mediaId?.let { id ->
                val entry = doc.mediaByBindId[id]
                val bytes = entry?.bytes
                if (bytes != null && bytes.isNotEmpty()) {
                    audioExtFor(bytes, entry?.fileName ?: "")?.let { ext ->
                        return@toVoice audioItemFor(bytes, ext, v, imageDir, rid, dpi, measurer)
                    }
                }
            }
            // Fallback: any audio payload (sniffed or named like audio).
            for (entry in doc.mediaByBindId.values + doc.mediaByFileName.values) {
                val bytes = entry.bytes ?: continue
                if (bytes.isEmpty()) continue
                val ext = audioExtFor(bytes, entry.fileName) ?: continue
                // Skip non-audio names when the payload does not sniff as audio.
                if (sniffAudioExt(bytes) == null && !looksLikeAudioName(entry.fileName) && !entry.isAudio) continue
                return audioItemFor(bytes, ext, v, imageDir, rid, dpi, measurer)
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun audioItemFor(
        bytes: ByteArray,
        ext: String,
        v: SdocxVoice,
        imageDir: File,
        rid: Int,
        dpi: Int,
        measurer: TextMeasurer,
    ): AudioItem? {
        return try {
            val file = File.createTempFile("sdocx-aud", ".$ext", imageDir)
            file.writeBytes(bytes)
            val audio = AudioData(file, ext, v.durationMs, ByteArray(0))
            val w = v.bbox.width
            val h = v.bbox.height
            val rect = if (w > 10 && h > 10 && w.isFinite() && h.isFinite()) {
                Rect(v.bbox.left, v.bbox.top, w, h)
            } else {
                AudioItem.boxAt(Pt((v.bbox.left + v.bbox.right) / 2.0, (v.bbox.top + v.bbox.bottom) / 2.0), dpi)
            }
            val name = v.title.ifEmpty { "Voice memo" }
            AudioItem(audio, rect, name, rid, measurer)
        } catch (_: Exception) {
            null
        }
    }

    /** Extension (no dot) for [bytes] named [fileName]; `.dat`/`.bin` count as `m4a`. */
    private fun audioExtFor(bytes: ByteArray, fileName: String): String? {
        sniffAudioExt(bytes)?.let { return it }
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', "").takeIf { '.' in lower && it.length in 1..5 } ?: run {
            // Extension-less `.dat`-style names still decode as m4a audio.
            if (lower.endsWith(".dat") || lower.endsWith(".bin")) return "m4a"
            return null
        }
        if (ext == "dat" || ext == "bin") return "m4a"
        return if (isKnownAudioExt(ext)) ext else null
    }

    fun isKnownAudioExt(ext: String): Boolean {
        val e = ext.lowercase().trimStart('.')
        return e == "m4a" || e == "mp3" || e == "wav" || e == "ogg" || e == "3gp" || e == "amr"
    }

    fun looksLikeAudioName(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.contains('.')) return false
        val ext = lower.substringAfterLast('.', "")
        if (ext.length !in 1..5) return false
        return isKnownAudioExt(ext) || ext == "dat" || ext == "bin"
    }

    // --- tables ---

    /**
     * A Samsung grid as an editable [TableItem]. Page tables keep their stored
     * box (sanitized and clamped); body tables ([flowCoords]) are fit-and-
     * clamped like body photos because text-flow coordinates are meaningless
     * on the page. Cell texts are trimmed to 2000 chars.
     */
    fun toTable(
        t: SdocxTable,
        pageW: Double,
        pageH: Double,
        measurer: TextMeasurer,
        flowCoords: Boolean = false,
    ): TableItem? {
        try {
            if (t.columnWidths.isEmpty() || t.rows.isEmpty()) return null
            if (!(pageW > 0) || !(pageH > 0) || !pageW.isFinite() || !pageH.isFinite()) return null
            val cols = t.columnWidths.size.coerceIn(1, TableItem.MAX_LINES)
            val rowsN = t.rows.size.coerceIn(1, TableItem.MAX_LINES)

            var w = t.bbox.width.takeIf { it.isFinite() && it > 0 && it <= 10000 } ?: 0.0
            var h = t.bbox.height.takeIf { it.isFinite() && it > 0 && it <= 10000 } ?: 0.0
            var left = t.bbox.left.takeIf { it.isFinite() } ?: 0.0
            var top = t.bbox.top.takeIf { it.isFinite() } ?: 0.0
            val colSum = t.columnWidths.filter { it.isFinite() && it > 0 }.sum()
            var rowSum = 0.0
            for (r in t.rows) {
                val rh = r.height.takeIf { it.isFinite() && it > 0 } ?: 0.0
                rowSum += rh
            }
            if (!(w > 0)) w = if (colSum > 0) colSum else 400.0
            if (!(h > 0)) h = if (rowSum > 0) rowSum else 200.0

            if (flowCoords) {
                // Fit-and-clamp like body images: shrink oversized tables,
                // then clamp into the page; default origin 40,40.
                if (!left.isFinite()) left = 40.0
                if (!top.isFinite()) top = 40.0
                // When the stored origin is far outside the page (text-flow
                // coords), fall back to the default origin.
                if (left < -pageW || left > 2 * pageW) left = 40.0
                if (top < -pageH || top > 2 * pageH) top = 40.0
                val scale = minOf(1.0, pageW / w, pageH / h)
                if (scale.isFinite() && scale > 0 && scale < 1.0) {
                    w *= scale
                    h *= scale
                }
                if (w > pageW) w = pageW
                if (h > pageH) h = pageH
                left = left.coerceIn(0.0, (pageW - w).coerceAtLeast(0.0))
                top = top.coerceIn(0.0, (pageH - h).coerceAtLeast(0.0))
                if (!(left.isFinite())) left = 40.0.coerceIn(0.0, (pageW - w).coerceAtLeast(0.0))
                if (!(top.isFinite())) top = 40.0.coerceIn(0.0, (pageH - h).coerceAtLeast(0.0))
            } else {
                if (!left.isFinite()) left = 0.0
                if (!top.isFinite()) top = 0.0
                if (w > pageW) w = pageW
                if (h > pageH) h = pageH
                left = left.coerceIn(0.0, (pageW - w).coerceAtLeast(0.0))
                top = top.coerceIn(0.0, (pageH - h).coerceAtLeast(0.0))
            }
            if (!(w > 0) || !(h > 0) || !w.isFinite() || !h.isFinite()) return null

            val rect = Rect(left, top, w, h)
            val item = TableItem.create(rect, cols, rowsN, Rgba(20, 20, 20, 255), 2.0)

            // Replace even fractions with the stored proportions.
            try {
                val totalW = t.columnWidths.filter { it.isFinite() && it > 0 }.sum()
                if (totalW > 0) {
                    item.colFractions.clear()
                    for (i in 0 until cols) {
                        val src = t.columnWidths.getOrNull(i)?.takeIf { it.isFinite() && it > 0 } ?: (totalW / cols)
                        item.colFractions.add(src / totalW)
                    }
                    val s = item.colFractions.sum()
                    if (s > 0) for (i in item.colFractions.indices) item.colFractions[i] = item.colFractions[i] / s
                }
                var totalH = 0.0
                for (r in t.rows) totalH += r.height.takeIf { it.isFinite() && it > 0 } ?: 0.0
                if (totalH > 0) {
                    item.rowFractions.clear()
                    for (i in 0 until rowsN) {
                        val src = t.rows.getOrNull(i)?.height?.takeIf { it.isFinite() && it > 0 } ?: (totalH / rowsN)
                        item.rowFractions.add(src / totalH)
                    }
                    val s = item.rowFractions.sum()
                    if (s > 0) for (i in item.rowFractions.indices) item.rowFractions[i] = item.rowFractions[i] / s
                }
            } catch (_: Exception) {
            }

            // Cell texts via reflection so a tree without the cell feature
            // still compiles; trimmed to 2000 chars.
            for ((ri, row) in t.rows.withIndex()) {
                if (ri >= rowsN) break
                for (cell in row.cells) {
                    try {
                        val c = cell.col.coerceIn(0, cols - 1)
                        val txt = cell.text.trim().take(2000)
                        if (txt.isEmpty()) continue
                        setTableCell(item, c, ri, txt)
                    } catch (_: Exception) {
                    }
                }
            }
            return item
        } catch (_: Exception) {
            return null
        }
    }

    private fun setTableCell(item: TableItem, c: Int, r: Int, text: String) {
        try {
            item.setCell(c, r, text)
        } catch (_: Exception) {
        }
    }

    // --- sticky memos (.sdocx attachments) ---

    /**
     * Sticker sub-notes (`.sdocx` media): each inner note's ink+text becomes a
     * 300×300 [StickyNoteItem] on the first empty page (else a new page sized
     * like the first). Falls back to a `TextItem("Sticky")` plus regular
     * strokes. Never throws.
     */
    private fun importStickyMemos(
        sdocx: SdocxDocument,
        doc: Document,
        measurer: TextMeasurer,
    ) {
        try {
            val entries = (sdocx.mediaByBindId.values + sdocx.mediaByFileName.values)
                .distinctBy { it.fileName.lowercase() }
                .filter { it.fileName.lowercase().endsWith(".sdocx") && it.bytes != null && it.bytes!!.isNotEmpty() }
            if (entries.isEmpty()) return
            for (entry in entries) {
                try {
                    val bytes = entry.bytes ?: continue
                    if (bytes.isEmpty()) continue
                    val innerEntries = try {
                        unzipEntries(bytes)
                    } catch (_: Exception) {
                        continue
                    }
                    val inner = try {
                        SdocxParser.parseArchive(innerEntries)
                    } catch (_: Exception) {
                        continue
                    }
                    val strokes = ArrayList<Stroke>()
                    val texts = ArrayList<String>()
                    for (page in inner.pages) {
                        for (s in page.strokes) {
                            try {
                                toStroke(s)?.let { strokes.add(it) }
                            } catch (_: Exception) {
                            }
                        }
                        for (tb in page.textBoxes) {
                            try {
                                if (tb.text.isNotBlank()) texts.add(tb.text)
                            } catch (_: Exception) {
                            }
                        }
                        for (sh in page.shapes) {
                            try {
                                val label = sh.label
                                if (label != null && label.text.isNotBlank()) texts.add(label.text)
                            } catch (_: Exception) {
                            }
                        }
                        for (t in page.tables) {
                            try {
                                for (row in t.rows) for (cell in row.cells) {
                                    if (cell.text.isNotBlank()) texts.add(cell.text)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    // Inner note-body tables (text-flow) also contribute their values.
                    try {
                        for (t in inner.bodyTables) {
                            for (row in t.rows) for (cell in row.cells) {
                                if (cell.text.isNotBlank()) texts.add(cell.text)
                            }
                        }
                    } catch (_: Exception) {
                    }
                    if (strokes.isEmpty() && texts.isEmpty()) continue
                    val combined = texts.joinToString("\n").trim().take(2000)

                    // Fit ink into 300×300 sticky: translate bbox to origin,
                    // scale to fit avail 276×240, offset 12,42.
                    val fittedInk: List<Stroke> = try {
                        fitStickyInk(strokes)
                    } catch (_: Exception) {
                        emptyList()
                    }

                    val sized = doc.pages.firstOrNull()?.let { Pair(it.width, it.height) }
                        ?: sdocx.pages.firstOrNull()?.let { Pair(it.width, it.height) }
                        ?: Pair(1440.0, 2036.0)
                    val target = doc.pages.firstOrNull { it.items.isEmpty() && it.pdfPage == null }
                        ?: Page(sized.first, sized.second).also { doc.pages.add(it) }
                    val stickyW = 300.0
                    val stickyH = 300.0
                    val sx = 40.0.coerceIn(0.0, (target.width - stickyW).coerceAtLeast(0.0))
                    val sy = 40.0.coerceIn(0.0, (target.height - stickyH).coerceAtLeast(0.0))
                    val rect = Rect(sx, sy, stickyW, stickyH)

                    val created = try {
                        StickyNoteItem(rect, StickyNoteItem.DEFAULT_COLOR, false, combined, ArrayList(fittedInk), measurer)
                    } catch (_: Exception) {
                        null
                    }
                    if (created != null) {
                        target.items.add(created)
                    } else {
                        // Fallback: regular text + strokes when the sticky class is missing.
                        try {
                            val label = if (combined.isNotBlank()) combined else "Sticky"
                            val ti = TextItem(
                                Pt(rect.x, rect.y),
                                rect.w,
                                0.0,
                                label,
                                Rgba(20, 20, 20, 255),
                                13.0,
                                com.xnotes.core.pal.FontFace.SANS,
                                measurer,
                            )
                            target.items.add(ti)
                        } catch (_: Exception) {
                        }
                        try {
                            // Offset fitted (sticky-local) ink onto the page.
                            for (s in fittedInk) {
                                try {
                                    if (s.sampleCount < 2) continue
                                    val samples = ArrayList<com.xnotes.core.stroke.Sample>(s.sampleCount)
                                    for (i in 0 until s.sampleCount) {
                                        val t = try {
                                            s.tAt(i)
                                        } catch (_: Exception) {
                                            0.0
                                        }
                                        samples.add(
                                            com.xnotes.core.stroke.Sample(
                                                s.xAt(i) + rect.x,
                                                s.yAt(i) + rect.y,
                                                s.pAt(i),
                                                t,
                                            ),
                                        )
                                    }
                                    val c = Stroke(s.tool, s.config.copy(), emptyList(), s.speedScale, s.straight, s.smoothScale)
                                    c.setSamples(samples)
                                    c.finished = true
                                    target.items.add(c)
                                } catch (_: Exception) {
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        out[e.name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Fit strokes into a 300×300 sticky's ink area (276×240 at 12,42). */
    private fun fitStickyInk(strokes: List<Stroke>): List<Stroke> {
        if (strokes.isEmpty()) return emptyList()
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (s in strokes) {
            for (i in 0 until s.sampleCount) {
                val x = s.xAt(i)
                val y = s.yAt(i)
                if (!x.isFinite() || !y.isFinite()) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return emptyList()
        var bw = maxX - minX
        var bh = maxY - minY
        if (!(bw > 0)) bw = 1.0
        if (!(bh > 0)) bh = 1.0
        val availW = 276.0
        val availH = 240.0
        var scale = minOf(availW / bw, availH / bh)
        if (!scale.isFinite() || scale <= 0) scale = 1.0
        // Shrink huge ink, keep tiny ink readable: allow growing up to 2×.
        scale = scale.coerceIn(0.05, 2.0)
        val out = ArrayList<Stroke>(strokes.size)
        for (s in strokes) {
            try {
                if (s.sampleCount < 2) continue
                val samples = ArrayList<com.xnotes.core.stroke.Sample>(s.sampleCount)
                for (i in 0 until s.sampleCount) {
                    val x = s.xAt(i)
                    val y = s.yAt(i)
                    if (!x.isFinite() || !y.isFinite()) continue
                    val t = try {
                        s.tAt(i)
                    } catch (_: Exception) {
                        0.0
                    }
                    val nx = (x - minX) * scale + 12.0
                    val ny = (y - minY) * scale + 42.0
                    samples.add(com.xnotes.core.stroke.Sample(nx, ny, s.pAt(i), t))
                }
                if (samples.size < 2) continue
                val c = Stroke(s.tool, s.config.copy(), emptyList(), s.speedScale, s.straight, s.smoothScale)
                c.setSamples(samples)
                c.finished = true
                out.add(c)
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * Create a sticky note directly (no reflection): yellow paper, expanded,
     * with [text] and note-local [ink]. Never throws.
     */
    private fun createStickyReflective(
        rect: Rect,
        text: String,
        ink: List<Stroke>,
        measurer: TextMeasurer,
    ): CanvasItem? {
        return try {
            StickyNoteItem(rect, StickyNoteItem.DEFAULT_COLOR, false, text, ArrayList(ink), measurer)
        } catch (_: Exception) {
            null
        }
    }
}
