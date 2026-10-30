package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.xnotes.canvas.PdfPageFilter
import com.xnotes.core.model.PdfKey
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.search.SearchText
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * A tappable link discovered on a PDF page. [rect] is the link's area in points as the page is
 * displayed (top-left origin, /Rotate applied). Exactly one target is set: an external [url]
 * (web/mailto), or an internal [destPage] (0-based source-PDF page to jump to).
 */
data class PdfLink(val rect: RectF, val url: String?, val destPage: Int?)

/**
 * Reads and rasterizes a PDF through [PdfiumDocument] (PAL §14). Pages are
 * reported in PostScript points and rendered on demand into ARGB surfaces; a
 * [PdfPageFilter] applies the note's colour filters (invert/contrast/…) to the
 * rendered pixels. A page is named by its [PdfKey], so a page shown turned is
 * read, held and drawn apart from the page itself.
 *
 * When the filter inverts, [renderPage]/[renderRegion] can keep embedded images
 * in their original colours ([PdfPageFilter.stampImages]): the whole page is
 * filtered as usual, then the original pixels are stamped back over each image's
 * box, which PDFium reads from the page's objects with the render.
 *
 * Text markups are painted onto the raw raster before either, so they invert with the page and a
 * highlight over a photo is stamped back with it.
 */
class PdfSource private constructor(
    /** The on-disk PDF this source reads. Owned by the caller, **not** by this PdfSource — [close]
     *  never deletes it, so several sources can read one file safely. */
    val file: File,
    private val pdf: PdfiumDocument,
) {
    /** Pages in the PDF, 0 when it did not open. Waits for the open, so not on the main thread. */
    val pageCount: Int get() = pdf.pageCount

    /** Why the PDF did not open, null when it did. Waits for the open. */
    val openError: PdfOpenError? get() = pdf.error

    /** The text of the pages last touched, for selecting and copying. */
    val text = PageTextCache { pdf.pageText(it, PdfPriority.INTERACTIVE) }

    /** Page [index]'s text to search, from the held text when there is one; null once closed. Waits, so not on the main thread. */
    fun searchText(index: Int): SearchText? {
        if (closed) return null
        text.peek(index)?.let { return SearchText.of(it) }
        return pdf.searchText(index, PdfPriority.SEARCH)
    }

    /** Page sizes in points already read, by page. */
    private val pageSizes = ConcurrentHashMap<Int, Pair<Float, Float>>()

    /** Page geometries already read, by page. */
    private val geometries = ConcurrentHashMap<Int, PdfPageGeometry>()

    /** How page [index]'s user space maps to its points as displayed; null once closed. Waits, so not on the main thread. */
    fun pageGeometry(index: Int): PdfPageGeometry? {
        geometries[index]?.let { return it }
        if (closed) return null
        return pdf.pageGeometry(index, PdfPriority.THUMBNAIL)?.also { geometries[index] = it }
    }

    /** Image boxes in points (l, t, r, b per image, top-left origin) already read, by page. */
    private val imageRects = ConcurrentHashMap<Int, FloatArray>()

    /** Immutable snapshot of one page's parsed links. */
    private class PageLinks(val index: Int, val links: List<PdfLink>)

    /** The most recently parsed page's links (tap fast-path); [linkCache] holds the rest. */
    @Volatile private var current: PageLinks? = null

    /** Single-flight guard for the warm pump, so a burst of [warmLinks] folds into one worker pass. */
    private val linkPumpActive = AtomicBoolean(false)

    /** Daemon worker for link parsing. Created on first use, shut down in [close]. */
    private var linksExecutor: ExecutorService? = null

    /** Guards [linksExecutor]'s lifecycle. */
    private val executorLock = Any()

    @Volatile private var closed = false

    /** Page size in points (1 pt = 1/72 inch) as displayed, its /Rotate and its key's turn applied; 0x0 for a broken page. */
    fun pageSizePoints(index: Int): Pair<Float, Float>? {
        pageSizes[index]?.let { return it }
        if (closed) return null
        val s = pdf.pageSizes(PdfKey.index(index), 1, priority())?.takeIf { it.size == 2 } ?: return null
        val size = if (PdfKey.turn(index) % 2 == 1) s[1] to s[0] else s[0] to s[1]
        return size.also { pageSizes[index] = it }
    }

    /** Every page's size in points, width then height, for importing a whole PDF. Read in slices, so
     *  renders get their turn. */
    fun allPageSizePoints(): FloatArray? {
        val n = pageCount
        val out = FloatArray(2 * n)
        for (from in 0 until n step SIZES_PER_READ) {
            if (closed) return null
            val part = pdf.pageSizes(from, SIZES_PER_READ, priority()) ?: return null
            part.copyInto(out, 2 * from)
        }
        return out
    }

    /** The whole page [index] fitted to [widthPx] × [heightPx], with [markups] on it. */
    fun renderPage(
        index: Int,
        widthPx: Int,
        heightPx: Int,
        filter: PdfPageFilter = PdfPageFilter.NONE,
        markups: List<TextMarkup> = emptyList(),
    ): AndroidRasterSurface? {
        val w = widthPx.coerceIn(1, MAX_DIM)
        val h = heightPx.coerceIn(1, MAX_DIM)
        return render(index, w, h, 0, 0, w, h, filter, markups)
    }

    /**
     * Render only the sub-rectangle ([regionLeftPx], [regionTopPx], [regionWpx], [regionHpx]) of
     * page [index] drawn at [pxPerPt] pixels per point, in pixels from the page's top-left corner,
     * into a [regionWpx] × [regionHpx] bitmap. The page is never allocated at full size; only the
     * region bitmap is, so a high-zoom viewport region can be rasterized at full resolution
     * without a giant whole-page bitmap. [markups] are drawn on it.
     */
    fun renderRegion(
        index: Int,
        pxPerPt: Double,
        regionLeftPx: Int,
        regionTopPx: Int,
        regionWpx: Int,
        regionHpx: Int,
        filter: PdfPageFilter = PdfPageFilter.NONE,
        markups: List<TextMarkup> = emptyList(),
    ): AndroidRasterSurface? {
        val (wPts, hPts) = pageSizePoints(index) ?: return null
        val fullW = (wPts * pxPerPt).roundToInt()
        val fullH = (hPts * pxPerPt).roundToInt()
        if (fullW <= 0 || fullH <= 0) return null
        return render(
            index, fullW, fullH, regionLeftPx, regionTopPx,
            regionWpx.coerceIn(1, MAX_DIM), regionHpx.coerceIn(1, MAX_DIM), filter, markups,
        )
    }

    /** Renders the [w] × [h] region at ([left], [top]) of a [fullW] × [fullH] raster of page [index]. */
    private fun render(
        index: Int, fullW: Int, fullH: Int, left: Int, top: Int, w: Int, h: Int, filter: PdfPageFilter,
        markups: List<TextMarkup>,
    ): AndroidRasterSurface? {
        if (closed) return null
        // Read before the render, which then finds the page already loaded.
        val images = if (filter.stampImages) imageRectsFor(index) else null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (!pdf.render(index, bmp, fullW, fullH, left, top, priority())) {
            bmp.recycle()
            return null
        }
        if (markups.isNotEmpty()) paintMarkups(bmp, markups, index, fullW, fullH, left, top)
        val m = filter.pageMatrix ?: return AndroidRasterSurface(bmp)

        val filtered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(filtered).drawBitmap(bmp, 0f, 0f, matrixPaint(m))
        val size = pageSizePoints(index)
        if (images != null && images.isNotEmpty() && size != null && size.first > 0f && size.second > 0f) {
            stampOriginalImages(filtered, bmp, images, fullW / size.first, fullH / size.second, left, top)
        }
        bmp.recycle()
        return AndroidRasterSurface(filtered)
    }

    /** Draws [markups] onto [bmp], the ([left], [top]) corner of page [index] rendered [fullW] × [fullH]. */
    private fun paintMarkups(bmp: Bitmap, markups: List<TextMarkup>, index: Int, fullW: Int, fullH: Int, left: Int, top: Int) {
        val (wPts, hPts) = pageSizePoints(index) ?: return
        if (wPts <= 0f || hPts <= 0f) return
        val r = AndroidRenderer(Canvas(bmp))
        r.translate(-left.toDouble(), -top.toDouble())
        // PDFium's own scale for this render, so the marks sit on its glyphs to the pixel.
        r.scale(fullW / wPts.toDouble(), fullH / hPts.toDouble())
        for (m in markups) MarkupPainter.paint(r, m)
    }

    /** A bitmap paint applying colour matrix [m]. */
    private fun matrixPaint(m: FloatArray): Paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(m))
    }

    /**
     * Copy the raw [original] pixels back over each image box so embedded images keep their real
     * colours under any filter. Boxes are in points; [sx] × [sy] pixels per point map them onto
     * the full page, then ([offsetXpx], [offsetYpx]) shifts them into this bitmap and they are
     * clipped to it (so it works for both whole-page and region renders).
     */
    private fun stampOriginalImages(
        filtered: Bitmap,
        original: Bitmap,
        rects: FloatArray,
        sx: Float,
        sy: Float,
        offsetXpx: Int,
        offsetYpx: Int,
    ) {
        val w = filtered.width
        val h = filtered.height
        val canvas = Canvas(filtered)
        for (i in 0 until rects.size / 4) {
            val left = ((rects[4 * i] * sx).roundToInt() - offsetXpx).coerceIn(0, w)
            val top = ((rects[4 * i + 1] * sy).roundToInt() - offsetYpx).coerceIn(0, h)
            val right = ((rects[4 * i + 2] * sx).roundToInt() - offsetXpx).coerceIn(0, w)
            val bottom = ((rects[4 * i + 3] * sy).roundToInt() - offsetYpx).coerceIn(0, h)
            if (right > left && bottom > top) {
                val rect = Rect(left, top, right, bottom) // src == dst: stamp 1:1 over the filtered pixels
                canvas.drawBitmap(original, rect, rect, null)
            }
        }
    }

    /** [index]'s image boxes, read once; empty when it has none or does not load. */
    private fun imageRectsFor(index: Int): FloatArray {
        imageRects[index]?.let { return it }
        val rects = pdf.imageRects(index, priority()) ?: return FloatArray(0)
        return rects.also { imageRects[index] = it }
    }

    /** The PDF's outline (bookmarks), empty when it has none. Waits, so not on the main thread. */
    fun outline(): List<PdfOutlineEntry> =
        if (closed) emptyList() else pdf.outline(MAX_OUTLINE, PdfPriority.THUMBNAIL).orEmpty()

    // --- Links ---

    /** Called on the link worker thread after a page's links land, so the view can repaint its boxes. */
    @Volatile var onLinksChanged: (() -> Unit)? = null

    /** How many pages' links are held at once for taps and boxes; beyond it the least recently
     *  wanted page is dropped (each page holds a few small rects, so this stays tiny). */
    private val linkCache = ConcurrentHashMap<Int, List<PdfLink>>()

    /** Every page the canvas wants held (visible pages + tapped ones); only ids, so unbounded scrolling stays cheap. */
    private val desiredLinkPages: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** True when [index]'s links are held (possibly an empty list). */
    fun hasLinks(index: Int): Boolean = linkCache.containsKey(index) || current?.index == index

    /** [index]'s held links, or null when not parsed yet (call [warmLinks]/[requestLinks]). */
    fun cachedLinks(index: Int): List<PdfLink>? = linkCache[index] ?: current?.takeIf { it.index == index }?.links

    /** The topmost link whose area contains ([x], [y]), in points as displayed, or null.
     *  Non-blocking: returns null unless [index]'s links are held — call [requestLinks] to
     *  parse it off the main thread. Later annotations paint on top, so iterate back-to-front. */
    fun linkAt(index: Int, x: Float, y: Float): PdfLink? {
        val links = linkCache[index] ?: current?.takeIf { it.index == index }?.links ?: return null
        for (i in links.indices.reversed()) {
            if (links[i].rect.contains(x, y)) return links[i]
        }
        return null
    }

    /** Coalesce warming: remember [index] as wanted and ensure a worker is parsing whatever is
     *  wanted but not held yet. A fast scroll just adds targets; the single pump parses them in
     *  turn, so there is never a per-page backlog. Fire-and-forget: it only fills the cache so a
     *  later tap finds it hot and the link boxes can draw. */
    fun warmLinks(index: Int) {
        if (closed || index < 0) return
        desiredLinkPages.add(index)
        if (!linkCache.containsKey(index) && current?.index != index) pumpLinks()
    }

    /** Warm every page in [indices] (the visible pages, for boxes), coalesced like [warmLinks]. */
    fun warmLinks(indices: Iterable<Int>) {
        if (closed) return
        var missing = false
        for (i in indices) {
            if (i < 0) continue
            desiredLinkPages.add(i)
            if (!linkCache.containsKey(i) && current?.index != i) missing = true
        }
        if (missing) pumpLinks()
    }

    /** Drive the warm pump: a single in-flight worker that parses whatever [desiredLinkPages]
     *  wants but the cache lacks yet. The single-flight [linkPumpActive] guard collapses a burst
     *  of [warmLinks] into one. */
    private fun pumpLinks() {
        if (closed) return
        if (!linkPumpActive.compareAndSet(false, true)) return
        val executor = linksWorker()
        if (executor == null) { linkPumpActive.set(false); return }
        val submitted = runCatching {
            executor.execute {
                try {
                    while (!closed) {
                        val target = desiredLinkPages.firstOrNull { !linkCache.containsKey(it) && current?.index != it }
                            ?: break
                        setCurrentLinks(target)
                    }
                } finally {
                    linkPumpActive.set(false)
                    val d = desiredLinkPages.firstOrNull { !linkCache.containsKey(it) && current?.index != it }
                    if (!closed && d != null) pumpLinks()
                }
            }
        }.isSuccess
        if (!submitted) linkPumpActive.set(false)
    }

    /** Guaranteed parse of [index]'s links off the main thread (a committed tap is never cancelled the
     *  way a stale [warmLinks] request is), then invoke [onReady] on the worker thread (the caller hops
     *  to the main thread itself). Cache-checked, so it no-ops if warming already parsed the page. */
    fun requestLinks(index: Int, onReady: () -> Unit) {
        if (closed) return
        if (linkCache.containsKey(index) || current?.index == index) { onReady(); return }
        desiredLinkPages.add(index)
        val executor = linksWorker() ?: return
        runCatching {
            executor.execute {
                if (closed) return@execute
                runCatching { setCurrentLinks(index) }
                if (!closed) onReady()
            }
        }
    }

    /** The single daemon worker for all link parsing (warm + guaranteed tap), created on first use;
     *  null after [close]. Keeps link parsing off the UI thread. */
    private fun linksWorker(): ExecutorService? = synchronized(executorLock) {
        if (closed) return@synchronized null
        linksExecutor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "xnotes-pdf-links").apply { isDaemon = true }
        }.also { linksExecutor = it }
    }

    /**
     * Parse [index]'s links and hold them (plus as the current page's, for the tap fast-path).
     * Interactive priority even when warming: the page is usually loaded already for its render,
     * so a read takes well under 1 ms. Notifies [onLinksChanged] so boxes repaint.
     */
    private fun setCurrentLinks(index: Int): List<PdfLink> {
        linkCache[index]?.let { return it }
        current?.let { if (it.index == index) return it.links.also { linkCache[index] = it } }
        val result = pdf.links(index, PdfPriority.INTERACTIVE).orEmpty()
        current = PageLinks(index, result)
        linkCache[index] = result
        evictLinks(except = index)
        if (!closed) runCatching { onLinksChanged?.invoke() }
        return result
    }

    /** Drop held pages past [MAX_CACHED_LINK_PAGES], least-wanted first, so a huge book can't
     *  accumulate link rects without bound. Pages still wanted are dropped last. */
    private fun evictLinks(except: Int) {
        while (linkCache.size > MAX_CACHED_LINK_PAGES) {
            val victim = linkCache.keys.firstOrNull { it != except && !desiredLinkPages.contains(it) }
                ?: linkCache.keys.firstOrNull { it != except }
                ?: break
            linkCache.remove(victim)
        }
    }

    fun close() {
        closed = true
        onLinksChanged = null
        linkCache.clear()
        desiredLinkPages.clear()
        current = null
        synchronized(executorLock) {
            runCatching { linksExecutor?.shutdownNow() }
        }
        text.close()
        pdf.close()
        // [file] is owned by the caller (Document / import staging); deleting it here is not our job.
    }

    companion object {
        private const val MAX_DIM = 4096

        /** Bookmarks read at most, so a pathological outline can't flood the Contents tab. */
        private const val MAX_OUTLINE = 2000

        /** Pages' links held at once for taps and boxes (each page holds a few small rects). */
        private const val MAX_CACHED_LINK_PAGES = 12

        /** Page sizes read per job; a page dictionary takes up to a fraction of a ms. */
        private const val SIZES_PER_READ = 128

        private val callerPriority = ThreadLocal<PdfPriority>()

        private fun priority(): PdfPriority = callerPriority.get() ?: PdfPriority.VISIBLE_RENDER

        /** Runs [block] with the renders it asks for queued at [priority] instead of VISIBLE_RENDER. */
        fun <T> withPriority(priority: PdfPriority, block: () -> T): T {
            val outer = callerPriority.get()
            callerPriority.set(priority)
            try {
                return block()
            } finally {
                if (outer == null) callerPriority.remove() else callerPriority.set(outer)
            }
        }

        /**
         * Starts opening [file] as a PDF and returns at once, so the main thread may call it; a PDF
         * that does not open just renders nothing. The file is **not** copied or owned: the caller
         * keeps it alive for the source's lifetime and deletes it afterwards.
         */
        fun open(file: File): PdfSource = PdfSource(file, PdfiumDocument.open(file))

        /** Opens [file] and waits for it: null when it does not open or has no pages. Not on the main thread. */
        fun create(file: File): PdfSource? {
            val source = open(file)
            if (source.pageCount > 0) return source
            source.close()
            return null
        }
    }
}
