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
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageSizeChoice
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
 * strokes and single-tap dots, plus base-frame creation times for replay),
 * raster images (page objects, page-background and note-body photos mapped
 * onto their pages), single-PDF backgrounds, text boxes (+ shape labels),
 * basic shapes and straight lines (as editable shapes), free outlines (as
 * polygon/polyline shapes), tables (type-22 page objects and note-body
 * tables, with cell values), sticky memos (nested `.sdocx` sub-notes as
 * [StickyNoteItem]) and audio (imported files as type-10 voice-memo pills;
 * recorded-while-writing sessions as note-level pills with per-stroke
 * [SyncMark] replay offsets so ink re-appears with the audio).
 *
 * Deliberately dropped: fills on imported shapes (outlines only), text
 * rotation (boxes land upright), mixed per-range styling (collapses to
 * whole-box bold/italic), custom fonts (Samsung's font names map to the sans
 * face), video/painting media and containers' hidden layers.
 */
object SdocxImport {
    private class NoteVoiceSlot(
        val voice: SdocxNoteVoice,
        val rid: Int,
        val startUs: Long?,
        val endUs: Long?,
    )

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

        // Bind ids already claimed by an explicit placement, so the fallback
        // scan below prefers media no placement names (then anything unused).
        // Without this, one unresolvable photo would show another photo's
        // bytes once per placement instead of its own.
        val referencedIds: Set<Int> = try {
            (sdocx.pages.flatMap { it.images }.mapNotNull { it.mediaId } +
                sdocx.bodyImages.mapNotNull { it.mediaId } +
                sdocx.pages.mapNotNull { it.backgroundImageId }).toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val usedImages = HashSet<SdocxMediaEntry>()

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
        // Note-level recorded audio ("record while writing"): allocate rids up
        // front so per-stroke replay marks can point at them while pages import.
        // Each entry keeps its replay interval in microseconds since epoch.
        val noteVoiceSlots: List<NoteVoiceSlot> = try {
            sdocx.noteVoices.map { v ->
                val slot = NoteVoiceSlot(v, nextRid++, v.startUs, v.endUs)
                slot
            }
        } catch (_: Exception) {
            emptyList()
        }
        fun syncForStroke(strokeTsUs: Long?): com.xnotes.core.model.SyncMark? {
            try {
                val ts = strokeTsUs ?: return null
                // Garbage base-frame timestamps (far-future uninitialized data
                // seen on some writers) must never sync ink to a recording.
                if (ts < 1_000_000_000_000L || ts > 4_000_000_000_000_000L) return null
                for (slot in noteVoiceSlots) {
                    val s = slot.startUs
                    val e = slot.endUs
                    if (s == null || e == null) continue
                    if (ts < s || ts > e) continue
                    val dur = slot.voice.durationMs.takeIf { it > 0 } ?: continue
                    val ms = ((ts - s) / 1000L).coerceIn(0L, dur.toLong()).toInt()
                    return com.xnotes.core.model.SyncMark(slot.rid, ms)
                }
            } catch (_: Exception) {
            }
            return null
        }
        // Samsung pages are 1440 x 2036 px; fall back to the Samsung size
        // (not A4) when a header carries no usable dimensions.
        val (fallbackW, fallbackH) = PageSize.SAMSUNG_NOTES.pixels(Orientation.PORTRAIT, dpi)
        // Note-level Samsung default template: pages without a template of
        // their own inherit this, so the note keeps one editable default
        // instead of an explicit copy stamped onto every page.
        try {
            val notePattern = SdocxMapping.samsungTemplateToPattern(null, sdocx.defaultTemplateUri)
            if (notePattern != PagePattern.NONE.id && notePattern != "none") {
                doc.style = doc.style.copy(template = notePattern)
            }
        } catch (_: Exception) {
        }
        // Pre-parse sticker sub-notes once: host pages that yield real
        // content are kept (not dropped as empty shells) and the sticky is
        // placed back on its own host page at its recorded position.
        val stickerContents = parseStickerContents(sdocx, textMeasurer)
        val hostPageOf = HashMap<SdocxPage, Page>()
        for (spage in sdocx.pages) {
            val w = saneDim(spage.width, fallbackW)
            val h = saneDim(spage.height, fallbackH)
            val page = Page(w, h)

            // Background levels mirror Samsung's own structure: only what the
            // page record itself carries becomes a per-page override (explicit
            // blank stays plain so it never inherits an unrelated ruling).
            // Samsung's factory tint means "no override" and stays inheritable
            // (editable, rendering the same under the default theme); a truly
            // custom tint stays explicit. Anything else inherits the note
            // default above, keeping All-Pages editing alive.
            var style = PageStyle()
            spage.backgroundColorArgb?.let { argb ->
                if (argb != SdocxMapping.SAMSUNG_DEFAULT_PAPER_ARGB) {
                    style = style.copy(pageColor = Rgba.fromArgb(argb))
                }
            }
            SdocxMapping.samsungPageTemplate(spage.templateType, spage.templateUri)?.let {
                style = style.copy(template = it)
            }
            page.style = style

            val paper = spage.pdfPapers.firstOrNull { it.mediaId == chosenPdfId && it.pageIndex >= 0 }
                ?: spage.pdfPapers.firstOrNull { chosenPdfId == null && it.pageIndex >= 0 }
            if (paper != null && pdfFile != null && paper.mediaId == chosenPdfId) {
                page.pdfPage = paper.pageIndex
            }

            for (s in spage.strokes) {
                try {
                    toStroke(s)?.let { stroke ->
                        try {
                            syncForStroke(s.createdTimeUs)?.let { stroke.sync = it }
                        } catch (_: Exception) {
                        }
                        page.items.add(stroke)
                    }
                } catch (_: Exception) {
                }
            }
            // Page-background photos (bgImageId) carry real images on some
            // Samsung writers: import them edge-to-edge under the ink.
            spage.backgroundImageId?.let { bgId ->
                toBackgroundImage(bgId, sdocx, imageDir, imageCodec, w, h, usedImages)?.let { page.items.add(0, it) }
            }
            for (im in spage.images) {
                toImage(im, sdocx, imageDir, imageCodec, usedImages, referencedIds)?.let { page.items.add(it) }
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
            // pattern-only pages, meaningfully-tinted pages and sticker hosts:
            // they are real content. The default paper tint alone is not meaningful.
            // Keep at least one page overall.
            val hasPdf = page.pdfPage != null
            val defaultBg = SdocxMapping.SAMSUNG_DEFAULT_PAPER_ARGB
            val resolvedPattern = page.style.template ?: doc.style.template ?: PagePattern.NONE.id
            val hasMeaningfulStyle = resolvedPattern != PagePattern.NONE.id && resolvedPattern != "none" ||
                (spage.backgroundColorArgb != null && spage.backgroundColorArgb != defaultBg)
            val hostsSticky = spage.stickers.any { stickerContents.containsKey(it.bindId) }
            if (page.items.isEmpty() && !hasPdf && !hasMeaningfulStyle && !hostsSticky) continue
            doc.pages.add(page)
            hostPageOf[spage] = page
        }

        // Photos pasted into Samsung text live in note.note's body, not in
        // any page. Their stored bbox is kept (position/size on the page are
        // accurate); the page itself is resolved in order: the first photo
        // lands on its mapped page, each following photo that would collide
        // onto the same page advances to the next page instead, so no two
        // photos ever stack on the first page. Placements without a usable
        // bbox reuse an empty page first (never covering ink or PDF paper,
        // and never a sticker host page reserved for its sticky).
        val stickerHosts = hostPageOf.values.toSet()
        if (sdocx.bodyImages.isNotEmpty()) {
            val sized = doc.pages.firstOrNull()?.let { Pair(it.width, it.height) }
                ?: sdocx.pages.firstOrNull()?.let {
                    Pair(saneDim(it.width, fallbackW), saneDim(it.height, fallbackH))
                }
                ?: Pair(fallbackW, fallbackH)
            val mappedUseCount = HashMap<Int, Int>()
            for (im in sdocx.bodyImages) {
                val item = toBodyImage(im, sdocx, imageDir, imageCodec, sized.first, sized.second, usedImages, referencedIds) ?: continue
                if (!im.hasPlacement) {
                    val target = bodyPageFor(im.bbox.top, sized.first, sized.second, sdocx.pages.size, doc, stickerHosts, placeOnMapped = false)
                        ?: continue
                    target.items.add(item)
                    continue
                }
                val mapped = bodyPageIndex(im.bbox.top, sized.second, sdocx.pages.size)
                val n = mappedUseCount.getOrDefault(mapped, 0)
                mappedUseCount[mapped] = n + 1
                val targetIdx = (mapped + n).coerceAtMost(sdocx.pages.size)
                val target = pageAtOrAppend(doc, targetIdx, sized.first, sized.second)
                // Keep the stored page-local rect (coordinates stay accurate);
                // only the page changes so colliding photos spread out.
                target.items.add(item)
            }
        }

        // Tables pasted into Samsung text (note.note body) land like body
        // photos: on the page their stored bbox falls on, at that bbox,
        // spreading colliding tables across following pages like images.
        if (sdocx.bodyTables.isNotEmpty()) {
            val sized = doc.pages.firstOrNull()?.let { Pair(it.width, it.height) }
                ?: sdocx.pages.firstOrNull()?.let {
                    Pair(saneDim(it.width, fallbackW), saneDim(it.height, fallbackH))
                }
                ?: Pair(fallbackW, fallbackH)
            val mappedUseCount = HashMap<Int, Int>()
            for (t in sdocx.bodyTables) {
                val mapped = bodyPageIndex(t.bbox.top, sized.second, sdocx.pages.size)
                val n = mappedUseCount.getOrDefault(mapped, 0)
                mappedUseCount[mapped] = n + 1
                val idx = (mapped + n).coerceAtMost(sdocx.pages.size)
                val local = SdocxRect(t.bbox.left, t.bbox.top - mapped * sized.second, t.bbox.right, t.bbox.bottom - mapped * sized.second)
                val item = toTable(SdocxTable(local, t.columnWidths, t.rows), sized.first, sized.second, textMeasurer, flowCoords = true) ?: continue
                val target = pageAtOrAppend(doc, idx, sized.first, sized.second)
                target.items.add(item)
            }
        }

        importStickyMemos(sdocx, doc, textMeasurer, stickerContents, hostPageOf)

        // Recorded-while-writing audio (note-level voices): Samsung keeps no
        // canvas pill (its player lives in the app bar), so place one pill per
        // recording at its page's center (stacked when sharing a page) and
        // leave stroke SyncMarks pointing at it for replay.
        try {
            importNoteVoices(sdocx, doc, imageDir, dpi, textMeasurer, noteVoiceSlots)
        } catch (_: Exception) {
        }

        // Safety net: any decodable image bytes never claimed by a placement
        // (newer frame layouts, missed bboxes, stale bind ids) land on their
        // own pages so no Samsung photo ever vanishes silently.
        try {
            sweepUnusedImages(sdocx, doc, imageDir, imageCodec, usedImages, stickerHosts)
        } catch (_: Exception) {
        }

        if (doc.pages.isEmpty()) {
            // All shells: keep one page so the note is never page-less.
            val first = sdocx.pages.first()
            val w = saneDim(first.width, fallbackW)
            val h = saneDim(first.height, fallbackH)
            doc.pages.add(Page(w, h))
        }
        // Later pages added to an imported Samsung note keep Samsung's page
        // size instead of dropping back to the app default.
        try {
            val first = sdocx.pages.firstOrNull()
            val landscape = first != null && first.width > first.height
            doc.newPageSize = PageSizeChoice(
                PageSize.SAMSUNG_NOTES,
                if (landscape) Orientation.LANDSCAPE else Orientation.PORTRAIT,
            )
        } catch (_: Exception) {
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
        } else if (s.tapered) {
            // Samsung's taper flag: same coordinates and color, tapered ends
            // like our taper pen.
            base.copy(
                baseWidth = width,
                rgba = color,
                taperEnabled = true,
                taperMinFactor = ToolDefaults.DEFAULT_TAPER_TIP,
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
     * Note-body photo placement: the stored bbox is in note coordinates
     * (pages stacked top to bottom at a uniform page height), so it is kept
     * at its stored size and mapped onto its page. Only a missing or
     * non-finite bbox falls back to a fitted rect. Never drops a decodable
     * photo.
     */
    private fun toBodyImage(
        im: SdocxImage,
        sdocx: SdocxDocument,
        imageDir: File,
        imageCodec: ImageCodec,
        pageW: Double,
        pageH: Double,
        usedImages: MutableSet<SdocxMediaEntry>,
        referencedIds: Set<Int>,
    ): ImageItem? {
        try {
            if (!(pageW > 0) || !(pageH > 0) || !pageW.isFinite() || !pageH.isFinite()) return null
            val (bytes, fileName) = resolveImageBytes(im, sdocx, usedImages, referencedIds) ?: return null
            val ext = imageExtFor(bytes, fileName) ?: return null
            val file = File.createTempFile("sdocx-img", ext, imageDir)
            file.writeBytes(bytes)
            val intrinsic = try {
                imageCodec.probeFile(file.path)
            } catch (_: Exception) {
                null
            }
            val rect = bodyImageRect(im.bbox, pageW, pageH)
                ?: fallbackBodyRect(intrinsic, pageW, pageH)
                ?: return null
            val size = intrinsic ?: ImageSize(
                rect.w.roundToInt().coerceIn(1, 10000),
                rect.h.roundToInt().coerceIn(1, 10000),
            )
            return ImageItem(ImageData(file, size.width, size.height), rect).also {
                it.angle = imageAngle(im)
            }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * A note-body bbox mapped onto its page: the stored size is kept (what
     * Samsung shows), the origin is made page-local and clamped into the
     * page. Null when the bbox carries no usable size.
     */
    private fun bodyImageRect(bbox: SdocxRect, pageW: Double, pageH: Double): Rect? {
        val w = bbox.width
        val h = bbox.height
        if (!(w > 0) || !(h > 0) || !w.isFinite() || !h.isFinite()) return null
        if (!(pageW > 0) || !(pageH > 0) || !pageW.isFinite() || !pageH.isFinite()) return null
        val cw = w.coerceAtMost(pageW)
        val ch = h.coerceAtMost(pageH)
        var left = bbox.left.takeIf { it.isFinite() } ?: 0.0
        var top = bbox.top.takeIf { it.isFinite() } ?: 0.0
        if (top >= 0.0) {
            // Note coordinates stack pages top to bottom: make it page-local.
            val idx = (top / pageH).toInt().coerceAtLeast(0)
            top -= idx * pageH
        }
        left = left.coerceIn(0.0, (pageW - cw).coerceAtLeast(0.0))
        top = top.coerceIn(0.0, (pageH - ch).coerceAtLeast(0.0))
        return Rect(left, top, cw, ch)
    }

    /** Last-resort rect for a decodable photo without a usable bbox: intrinsic
     *  size fitted into the page at a visible origin, so it never vanishes. */
    private fun fallbackBodyRect(intrinsic: ImageSize?, pageW: Double, pageH: Double): Rect? {
        return try {
            var w = intrinsic?.width?.toDouble()?.takeIf { it > 0 } ?: 800.0
            var h = intrinsic?.height?.toDouble()?.takeIf { it > 0 } ?: 600.0
            if (!(w > 0) || !(h > 0) || !w.isFinite() || !h.isFinite()) {
                w = 800.0
                h = 600.0
            }
            val scale = minOf(1.0, pageW / w, pageH / h)
            if (scale.isFinite() && scale > 0) {
                w *= scale
                h *= scale
            }
            w = w.coerceIn(1.0, pageW)
            h = h.coerceIn(1.0, pageH)
            val left = 40.0.coerceIn(0.0, (pageW - w).coerceAtLeast(0.0))
            val top = 40.0.coerceIn(0.0, (pageH - h).coerceAtLeast(0.0))
            Rect(left, top, w, h)
        } catch (_: Exception) {
            null
        }
    }

    /** Note-coordinate top mapped to a page index: the stacked page the
     *  offset falls on, clamped to one past the end (append a single page).
     *  [pageCount] is the note's own page count, so dropped empty shells do
     *  not shift later images onto the wrong page. */
    private fun bodyPageIndex(top: Double, pageH: Double, pageCount: Int): Int {
        if (!top.isFinite() || !(pageH > 0) || pageCount <= 0) return 0
        return (top / pageH).toInt().coerceIn(0, pageCount)
    }

    /** The page at [idx], appending Samsung-sized pages until it exists.
     *  [idx] is already clamped to one past the note's own page count, so
     *  this appends a bounded number of pages. */
    private fun pageAtOrAppend(doc: Document, idx: Int, w: Double, h: Double): Page {
        val at = idx.coerceAtLeast(0)
        while (doc.pages.size <= at) doc.pages.add(Page(w, h))
        return doc.pages[at]
    }

    /** Target page for a note-body photo: its mapped page when it carries a
     *  real placement, else the legacy first-empty-page (or a fresh page). */
    private fun bodyPageFor(
        bboxTop: Double,
        pageW: Double,
        pageH: Double,
        pageCount: Int,
        doc: Document,
        stickerHosts: Set<Page>,
        placeOnMapped: Boolean,
    ): Page {
        if (!placeOnMapped) {
            return doc.pages.firstOrNull { it.items.isEmpty() && it.pdfPage == null && it !in stickerHosts }
                ?: Page(pageW, pageH).also { doc.pages.add(it) }
        }
        return pageAtOrAppend(doc, bodyPageIndex(bboxTop, pageH, pageCount), pageW, pageH)
    }

    /** Free rotation of a placed photo, radians clockwise; 0 when unset. */
    private fun imageAngle(im: SdocxImage): Double {
        val deg = im.rotationDegrees?.takeIf { it.isFinite() } ?: return 0.0
        if (deg == 0.0) return 0.0
        return deg * PI / 180.0
    }

    /** True when [im] carries a real placement (a decoded bbox with a media
     *  id): it belongs on its mapped page, not on a fallback page. */
    private val SdocxImage.hasPlacement: Boolean
        get() = mediaId != null && bbox.width > 0 && bbox.height > 0 &&
            bbox.width.isFinite() && bbox.height.isFinite()

    /** Resolve the media bytes for an image placement: explicit bind id first,
     *  then an unused decodable image (preferring media no placement names, so
     *  one unresolvable photo never clones another photo's bytes once per
     *  placement). Explicit repeats are allowed, so the same photo may still
     *  appear twice when Samsung references it twice. */
    private fun resolveImageBytes(
        im: SdocxImage,
        sdocx: SdocxDocument,
        usedImages: MutableSet<SdocxMediaEntry>,
        referencedIds: Set<Int>,
    ): Pair<ByteArray, String>? {
        try {
            // Explicit bind id first and repeatable: the same photo may
            // legitimately appear twice. Record the hit so the final sweep
            // never duplicates an already-placed photo on its own page.
            im.mediaId?.let { id ->
                val entry = sdocx.mediaByBindId[id]
                    ?: sdocx.mediaByFileName.values.firstOrNull { it.bindId == id && it.bytes?.isNotEmpty() == true }
                val bytes = entry?.bytes
                if (bytes != null && bytes.isNotEmpty()) {
                    usedImages.add(entry)
                    return bytes to (entry?.fileName ?: "")
                }
                // Manifest miss but archive holds the bytes under another key:
                // scan every media entry with this bind id before falling back.
                if (entry == null) {
                    val any = (sdocx.mediaByBindId.values + sdocx.mediaByFileName.values)
                        .firstOrNull { it.bindId == id && it.bytes?.isNotEmpty() == true }
                    val anyBytes = any?.bytes
                    if (anyBytes != null && anyBytes.isNotEmpty()) {
                        usedImages.add(any)
                        return anyBytes to any.fileName
                    }
                }
            }
            fallbackImageEntry(sdocx, usedImages, referencedIds)?.let { entry ->
                val bytes = entry.bytes ?: return@let
                if (bytes.isEmpty()) return@let
                usedImages.add(entry)
                return bytes to entry.fileName
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /** First unused decodable image: media no placement names wins, so an
     *  unresolvable photo keeps distinct bytes instead of duplicating the
     *  first asset once per placement. Falls back to reusing an asset only
     *  when every image is already consumed, so a photo is never dropped
     *  while decodable bytes exist. Null when no image bytes exist at all. */
    private fun fallbackImageEntry(
        sdocx: SdocxDocument,
        usedImages: MutableSet<SdocxMediaEntry>,
        referencedIds: Set<Int>,
    ): SdocxMediaEntry? {
        // `mediaByBindId` and `mediaByFileName` hold the same objects; dedupe
        // by identity so one asset is never offered twice.
        val candidates = LinkedHashSet<SdocxMediaEntry>()
        for (entry in sdocx.mediaByBindId.values + sdocx.mediaByFileName.values) {
            candidates.add(entry)
        }
        fun usable(e: SdocxMediaEntry, allowUsed: Boolean): Boolean {
            if (!allowUsed && e in usedImages) return false
            val bytes = e.bytes ?: return false
            if (bytes.isEmpty()) return false
            // Skip non-raster attachments (nested .sdocx, .spi paintings,
            // video, audio): only real photos qualify for image fallback.
            if (isNonImageAttachment(e, bytes)) return false
            return imageExtFor(bytes, e.fileName) != null
        }
        return candidates.firstOrNull { it.bindId !in referencedIds && usable(it, allowUsed = false) }
            ?: candidates.firstOrNull { usable(it, allowUsed = false) }
            ?: candidates.firstOrNull { usable(it, allowUsed = true) }
    }

    /** True when [entry] is a known non-image attachment (nested note, painting,
     *  video, audio, PDF): never surface its bytes as a photo, even when the
     *  filename is odd. Image sniff wins over the name so mis-suffixed photos
     *  still qualify. */
    private fun isNonImageAttachment(e: SdocxMediaEntry, bytes: ByteArray): Boolean {
        try {
            val lower = e.fileName.lowercase()
            if (lower.endsWith(".sdocx") || lower.endsWith(".spi") || lower.endsWith(".pdf")) return true
            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") ||
                lower.endsWith(".mkv") || lower.endsWith(".webm")
            ) return true
            // Audio by name or sniff: voice memos must never become photos.
            if (e.isAudio) return true
            if (sniffAudioExt(bytes) != null && sniffImageExt(bytes) == null) return true
            // ZIP without an image magic is a nested note/archive, not a photo.
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte() &&
                sniffImageExt(bytes) == null
            ) return true
            return false
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Final safety net: every decodable image asset never claimed by a
     * placement lands on its own page (reusing empty non-sticker pages first)
     * so no Samsung photo vanishes when its frame layout is newer than the
     * strict decoder. Already-placed bytes are never duplicated.
     */
    private fun sweepUnusedImages(
        sdocx: SdocxDocument,
        doc: Document,
        imageDir: File,
        imageCodec: ImageCodec,
        usedImages: MutableSet<SdocxMediaEntry>,
        stickerHosts: Set<Page>,
    ) {
        val candidates = LinkedHashSet<SdocxMediaEntry>()
        for (entry in sdocx.mediaByBindId.values + sdocx.mediaByFileName.values) {
            candidates.add(entry)
        }
        val sized = doc.pages.firstOrNull()?.let { Pair(it.width, it.height) }
            ?: sdocx.pages.firstOrNull()?.let { Pair(saneDim(it.width, 1440.0), saneDim(it.height, 2036.0)) }
            ?: Pair(1440.0, 2036.0)
        for (entry in candidates) {
            if (entry in usedImages) continue
            val bytes = entry.bytes ?: continue
            if (bytes.isEmpty()) continue
            if (isNonImageAttachment(entry, bytes)) continue
            if (imageExtFor(bytes, entry.fileName) == null) continue
            // Place on an empty page first (never covering ink/PDF or a
            // sticker host), else append a fresh Samsung-sized page.
            val target = doc.pages.firstOrNull { it.items.isEmpty() && it.pdfPage == null && it !in stickerHosts }
                ?: Page(sized.first, sized.second).also { doc.pages.add(it) }
            // Fit into the page preserving aspect ratio, like body photos:
            // intrinsic size wins, clamped to the page; origin at 40,40.
            try {
                val ext = imageExtFor(bytes, entry.fileName) ?: continue
                val file = File.createTempFile("sdocx-img", ext, imageDir)
                file.writeBytes(bytes)
                val intrinsic = try {
                    imageCodec.probeFile(file.path)
                } catch (_: Exception) {
                    null
                }
                var w = intrinsic?.width?.toDouble()?.takeIf { it > 0 }
                    ?: (sized.first - 80.0).coerceIn(1.0, sized.first)
                var h = intrinsic?.height?.toDouble()?.takeIf { it > 0 }
                    ?: (sized.second - 80.0).coerceIn(1.0, sized.second)
                if (!(w > 0) || !(h > 0) || !w.isFinite() || !h.isFinite()) {
                    w = (sized.first - 80.0).coerceIn(1.0, sized.first)
                    h = (sized.second - 80.0).coerceIn(1.0, sized.second)
                }
                val scale = minOf(1.0, sized.first / w, sized.second / h)
                if (scale.isFinite() && scale > 0) {
                    w *= scale
                    h *= scale
                }
                w = w.coerceIn(1.0, sized.first)
                h = h.coerceIn(1.0, sized.second)
                val left = 40.0.coerceIn(0.0, (sized.first - w).coerceAtLeast(0.0))
                val top = 40.0.coerceIn(0.0, (sized.second - h).coerceAtLeast(0.0))
                val size = intrinsic ?: ImageSize(
                    w.roundToInt().coerceIn(1, 10000),
                    h.roundToInt().coerceIn(1, 10000),
                )
                // Reuse the file we already probed instead of writing twice.
                val item = ImageItem(ImageData(file, size.width, size.height), Rect(left, top, w, h))
                target.items.add(item)
                usedImages.add(entry)
            } catch (_: Exception) {
                continue
            }
        }
    }

    private fun toImage(
        im: SdocxImage,
        doc: SdocxDocument,
        imageDir: File,
        imageCodec: ImageCodec,
        usedImages: MutableSet<SdocxMediaEntry>,
        referencedIds: Set<Int>,
    ): ImageItem? {
        try {
            // Explicit bind id first and repeatable: the same photo may
            // legitimately appear twice. Record the hit so the final sweep
            // never duplicates an already-placed photo on its own page.
            // On failure fall through to an unused decodable image so a stale
            // id never drops a real photo (and never clones another
            // placement's photo while unused bytes remain).
            im.mediaId?.let { id ->
                val entry = doc.mediaByBindId[id]
                    ?: doc.mediaByFileName.values.firstOrNull { it.bindId == id && it.bytes?.isNotEmpty() == true }
                    ?: (doc.mediaByBindId.values + doc.mediaByFileName.values)
                        .firstOrNull { it.bindId == id && it.bytes?.isNotEmpty() == true }
                val bytes = entry?.bytes
                if (bytes != null && bytes.isNotEmpty()) {
                    imageItemFromBytes(bytes, entry?.fileName ?: "", im.bbox, imageDir, imageCodec, imageAngle(im))?.let {
                        usedImages.add(entry)
                        return it
                    }
                }
            }
            // No (or unresolvable) bind id: fall back to unused media bytes
            // that actually decode as an image, regardless of file extension.
            val entry = fallbackImageEntry(doc, usedImages, referencedIds) ?: return null
            val bytes = entry.bytes ?: return null
            if (bytes.isEmpty()) return null
            return imageItemFromBytes(bytes, entry.fileName, im.bbox, imageDir, imageCodec, imageAngle(im))?.also {
                usedImages.add(entry)
            }
        } catch (_: Exception) {
            return null
        }
    }

    /** Build an [ImageItem] in [bbox] from raw [bytes]; null when not an image.
     *  [angle] is the photo's free turn, radians clockwise about its centre. */
    private fun imageItemFromBytes(
        bytes: ByteArray,
        fileName: String,
        bbox: SdocxRect,
        imageDir: File,
        imageCodec: ImageCodec,
        angle: Double = 0.0,
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
            return ImageItem(ImageData(file, size.width, size.height), rect).also {
                it.angle = angle
            }
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
        usedImages: MutableSet<SdocxMediaEntry>? = null,
    ): ImageItem? {
        return try {
            val entry = doc.mediaByBindId[bindId]
                ?: doc.mediaByFileName.values.firstOrNull { it.bindId == bindId && it.bytes?.isNotEmpty() == true }
                ?: return null
            val bytes = entry.bytes ?: return null
            if (bytes.isEmpty()) return null
            if (imageExtFor(bytes, entry.fileName) == null) return null
            imageItemFromBytes(bytes, entry.fileName, SdocxRect(0.0, 0.0, pageW, pageH), imageDir, imageCodec)?.also {
                usedImages?.add(entry)
            }
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
            lower.endsWith(".svg") -> ".svg"
            lower.endsWith(".jxl") -> ".jxl"
            lower.endsWith(".jpe") || lower.endsWith(".jfif") -> ".jpg"
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
        // JPEG XL: FF 0A FF 0B (codestream) or ....ftyp + jxl brand.
        if (bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x0A.toByte() &&
            bytes[2] == 0xFF.toByte() && bytes[3] == 0x0B.toByte()
        ) return ".jxl"
        // SVG: XML with <svg (after optional BOM/whitespace/comments).
        if (looksLikeSvg(bytes)) return ".svg"
        // WBMP: type(0) + fixed header(0) + width/height as multi-byte ints.
        // Minimal check: starts 00 00 and decodes sanely; extension still wins.
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte()) {
            // Avoid clashing with ICO (00 00 01 00 handled above).
            if (!(bytes[2] == 0x01.toByte() && bytes[3] == 0x00.toByte())) {
                if (isPlausibleWbmp(bytes)) return ".wbmp"
            }
        }
        return null
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        try {
            if (bytes.isEmpty()) return false
            // Strip UTF-8 BOM.
            var off = 0
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) off = 3
            // Skip leading whitespace.
            while (off < bytes.size && (bytes[off] == 0x20.toByte() || bytes[off] == 0x0A.toByte() ||
                    bytes[off] == 0x0D.toByte() || bytes[off] == 0x09.toByte())
            ) off++
            if (off + 4 > bytes.size) return false
            // <?xml ... <svg or direct <svg.
            val head = try {
                String(bytes, off, minOf(512, bytes.size - off), Charsets.UTF_8).lowercase()
            } catch (_: Exception) {
                return false
            }
            return head.contains("<svg")
        } catch (_: Exception) {
            return false
        }
    }

    private fun isPlausibleWbmp(bytes: ByteArray): Boolean {
        try {
            var off = 2
            repeat(2) {
                // Multi-byte int: high-bit continuation.
                var shifts = 0
                while (off < bytes.size) {
                    val b = bytes[off++].toInt() and 0xFF
                    shifts++
                    if (shifts > 4) return false
                    if ((b and 0x80) == 0) break
                }
                if (off > bytes.size) return false
            }
            return true
        } catch (_: Exception) {
            return false
        }
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

    /**
     * Note-level recorded audio ("record while writing"): one pill per
     * recording. Samsung keeps no canvas coordinates (its player lives in the
     * app bar), so pills land at their page's center, stacked when sharing a
     * page. Target page prefers the one holding most of the recording's synced
     * strokes, else the first page. Never throws; unresolvable recordings are
     * skipped without dropping anything else.
     */
    private fun importNoteVoices(
        sdocx: SdocxDocument,
        doc: Document,
        imageDir: File,
        dpi: Int,
        measurer: TextMeasurer,
        slots: List<NoteVoiceSlot>,
    ) {
        try {
            if (slots.isEmpty()) return
            if (doc.pages.isEmpty()) return
            // Count synced strokes per page per voice to pick host pages.
            val pageStrokeCounts = HashMap<Int, MutableMap<Int, Int>>()
            try {
                for ((pageIdx, page) in doc.pages.withIndex()) {
                    for (item in page.items) {
                        val mark = item.sync ?: continue
                        val voiceIdx = slots.indexOfFirst { it.rid == mark.rid }
                        if (voiceIdx < 0) continue
                        val counts = pageStrokeCounts.getOrPut(voiceIdx) { HashMap() }
                        counts[pageIdx] = (counts[pageIdx] ?: 0) + 1
                    }
                }
            } catch (_: Exception) {
            }
            val perPagePillCount = HashMap<Int, Int>()
            for ((voiceIdx, slot) in slots.withIndex()) {
                try {
                    val voice = slot.voice
                    val entry = sdocx.mediaByBindId[voice.mediaId]
                        ?: sdocx.mediaByFileName.values.firstOrNull {
                            it.bindId == voice.mediaId && it.bytes?.isNotEmpty() == true
                        } ?: continue
                    val bytes = entry.bytes ?: continue
                    if (bytes.isEmpty()) continue
                    val ext = audioExtFor(bytes, entry.fileName) ?: continue
                    // Skip non-audio payloads masquerading under an audio bind id.
                    if (sniffAudioExt(bytes) == null && !looksLikeAudioName(entry.fileName) && !entry.isAudio) continue
                    val file = File.createTempFile("sdocx-aud", ".$ext", imageDir)
                    try {
                        file.writeBytes(bytes)
                    } catch (_: Exception) {
                        continue
                    }
                    val dur = voice.durationMs.takeIf { it > 0 } ?: 0
                    val audio = AudioData(file, ext, dur, ByteArray(0))
                    // Host page: most synced strokes, else first page.
                    val hostIdx = try {
                        pageStrokeCounts[voiceIdx]?.maxByOrNull { it.value }?.key ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    val host = doc.pages.getOrNull(hostIdx) ?: doc.pages.first()
                    val n = perPagePillCount.getOrDefault(hostIdx, 0)
                    perPagePillCount[hostIdx] = n + 1
                    val center = Pt(host.width / 2.0, host.height / 2.0 + n * 90.0)
                    val rect = try {
                        val base = AudioItem.boxAt(center, dpi)
                        // Clamp stacked pills inside the page.
                        val x = base.x.coerceIn(0.0, (host.width - base.w).coerceAtLeast(0.0))
                        val y = base.y.coerceIn(0.0, (host.height - base.h).coerceAtLeast(0.0))
                        Rect(x, y, base.w, base.h)
                    } catch (_: Exception) {
                        AudioItem.boxAt(Pt(host.width / 2.0, host.height / 2.0), dpi)
                    }
                    val name = voice.title.ifEmpty { "Recording" }
                    host.items.add(AudioItem(audio, rect, name, slot.rid, measurer))
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
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

    private class StickerContent(
        val strokes: List<Stroke>,
        val text: String,
    )

    /**
     * Parse every nested `.sdocx` attachment once: inner ink (as sticky-local
     * strokes) plus inner text (boxes, shape labels and tables). Entries that
     * do not parse or carry no content are absent, so their host pages stay
     * dropped and no blank page appears for them.
     */
    private fun parseStickerContents(
        sdocx: SdocxDocument,
        measurer: TextMeasurer,
    ): Map<Int, StickerContent> {
        val out = HashMap<Int, StickerContent>()
        try {
            val entries = (sdocx.mediaByBindId.values + sdocx.mediaByFileName.values)
                .distinctBy { it.fileName.lowercase() }
                .filter { it.fileName.lowercase().endsWith(".sdocx") && it.bytes != null && it.bytes!!.isNotEmpty() }
            if (entries.isEmpty()) return out
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
                    out[entry.bindId] = StickerContent(fittedInk, combined)
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Sticker sub-notes (`.sdocx` media): each inner note's ink+text becomes a
     * 300×300 [StickyNoteItem] on its own host page at its recorded collapse
     * position (clamped into the page). Stickies without recorded placement
     * fall back to the first empty page (else a new page sized like the
     * first) at 40,40. Never throws.
     */
    private fun importStickyMemos(
        sdocx: SdocxDocument,
        doc: Document,
        measurer: TextMeasurer,
        stickerContents: Map<Int, StickerContent>,
        hostPageOf: Map<SdocxPage, Page>,
    ) {
        if (stickerContents.isEmpty()) return
        try {
            // Host-page placements first: same page, recorded x/y.
            val placed = HashSet<Pair<Int, Page>>()
            for (spage in sdocx.pages) {
                if (spage.stickers.isEmpty()) continue
                val target = hostPageOf[spage] ?: continue
                for (st in spage.stickers) {
                    val content = stickerContents[st.bindId] ?: continue
                    if (!placed.add(st.bindId to target)) continue
                    try {
                        val stickyW = 300.0
                        val stickyH = 300.0
                        val collapse = st.collapseRect
                        val sx = if (collapse != null && collapse.left.isFinite()) {
                            collapse.left.coerceIn(0.0, (target.width - stickyW).coerceAtLeast(0.0))
                        } else {
                            40.0.coerceIn(0.0, (target.width - stickyW).coerceAtLeast(0.0))
                        }
                        val sy = if (collapse != null && collapse.top.isFinite()) {
                            collapse.top.coerceIn(0.0, (target.height - stickyH).coerceAtLeast(0.0))
                        } else {
                            40.0.coerceIn(0.0, (target.height - stickyH).coerceAtLeast(0.0))
                        }
                        val rect = Rect(sx, sy, stickyW, stickyH)
                        val color = try {
                            st.bgColorArgb?.let { Rgba.fromArgb(it) } ?: StickyNoteItem.DEFAULT_COLOR
                        } catch (_: Exception) {
                            StickyNoteItem.DEFAULT_COLOR
                        }
                        target.items.add(
                            // Collapsed by default: the icon pins the Samsung
                            // position without covering the page; tap to expand.
                            StickyNoteItem(rect, color, true, content.text, ArrayList(content.strokes), measurer),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
            }
            // Stickies without a host placement: legacy first-empty/new page.
            for ((bindId, content) in stickerContents) {
                if (placed.any { it.first == bindId }) continue
                try {
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
                    target.items.add(
                        // Collapsed by default, like the host placement above.
                        StickyNoteItem(rect, StickyNoteItem.DEFAULT_COLOR, true, content.text, ArrayList(content.strokes), measurer),
                    )
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
                        try {
                            out[e.name] = zis.readBytes()
                        } catch (_: Exception) {
                            // Skip a corrupt entry without aborting later ones.
                        }
                    }
                    try {
                        zis.closeEntry()
                    } catch (_: Exception) {
                    }
                    e = try {
                        zis.nextEntry
                    } catch (_: Exception) {
                        break
                    }
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
}
