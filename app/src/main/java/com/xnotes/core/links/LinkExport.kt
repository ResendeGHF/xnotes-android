package com.xnotes.core.links

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedAccentColor
import com.xnotes.core.model.resolvedColors
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.resolvedParams
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.TextMeasurer

/**
 * Self-contained export of notes with note links ("export with links").
 *
 * A 10-page note whose page 5 links 3 pages of another file exports as 13 pages:
 * the linked pages are appended directly below the page holding the link, and the
 * original document continues after them. Links that were expanded are rewired to
 * point at the appended pages of the same (now self-contained) file, and each
 * appended page carries the link's color as [Page.linkBorder].
 */
object LinkExport {

    /** One link that could not be expanded (missing file or no valid pages). */
    data class Skipped(val page: Int, val label: String, val reason: String)

    /** A PDF background baked to a raster: the encoded file plus its pixel size. */
    data class BakedBackground(val file: java.io.File, val widthPx: Int, val heightPx: Int)

    /** A document loaded for expansion, and whether the caller owns its temp files. */
    data class LoadedSource(val doc: Document, val transient: Boolean)

    data class Result(
        val document: Document,
        /** Expanded page count. */
        val pageCount: Int,
        /** Links that were expanded (rewired to same-file pages). */
        val expandedLinks: Int,
        val skipped: List<Skipped>,
        /** Every source document the loader returned, for temp-file cleanup. */
        val loadedSources: List<LoadedSource> = emptyList(),
        /** Baked background files created during expansion, for cleanup after the write. */
        val sidecars: List<java.io.File> = emptyList(),
    )

    /**
     * Build the self-contained copy of [doc].
     *
     * @param loadExternal resolves an external link's [NoteLinkItem.targetUri] to its
     *   document, or null when it cannot be opened (deleted file, no permission, ...).
     * @param measurer lays out copied text boxes.
     * @param expandSelf whether self-links (same-note jumps) are expanded too. True matches
     *   the spec: the exported file inlines what the links point at.
     */
    fun expand(
        doc: Document,
        loadExternal: (uri: String) -> LoadedSource?,
        measurer: TextMeasurer,
        expandSelf: Boolean = true,
        /**
         * Rasterize a PDF-backed page of an *external* document into a plain image,
         * since its source PDF cannot travel with the export. Null = leave the page
         * as it is (best effort). Runs on the caller's thread; IO is expected.
         */
        bakePdfBackground: ((source: Document, page: Page) -> BakedBackground?)? = null,
    ): Result {
        // Deep copy first so rewiring never mutates the live open note.
        val out = doc.deepCopy(measurer)
        val n = out.pages.size
        if (n == 0) return Result(out, 0, 0, emptyList())

        // Snapshot the links per original page (identity-matched later by fields).
        data class LinkKey(
            val displayName: String,
            val targetUri: String,
            val targetName: String,
            val pages: List<Int>,
            val color: com.xnotes.core.model.Rgba,
        )
        fun keyOf(l: NoteLinkItem) = LinkKey(l.displayName, l.targetUri, l.targetName, l.pages.toList(), l.color)
        val srcLinks: List<List<NoteLinkItem>> = doc.pages.map { it.items.filterIsInstance<NoteLinkItem>() }

        val loadedSources = ArrayList<LoadedSource>()
        val sidecars = ArrayList<java.io.File>()
        // Anchor i -> appended page copies in encounter order.
        val insertions = HashMap<Int, MutableList<Page>>()
        // Anchor i -> for each expanded link on it (encounter order): how many pages it appended.
        val counts = HashMap<Int, MutableList<Int>>()
        // Anchor i -> the out-copy link objects that were expanded (encounter order).
        val rewritten = HashMap<Int, MutableList<NoteLinkItem>>()

        // Makes an external page self-contained in the export: freezes its effective
        // paper, ruling and margins (which otherwise live on the source document and
        // would be lost), carries the template it shows, and bakes a PDF background
        // into a plain image (its source PDF cannot travel with the export). Best
        // effort throughout: a page that cannot be baked keeps pointing at its PDF
        // and still exports its ink.
        fun bakeExternal(source: Document, original: Page, copy: Page) {
            copy.style = resolvedExportStyle(source, original)
            if (original.width > 0.0 && original.height > 0.0) {
                val ins = original.insets(source)
                copy.margins = PageMargins(
                    left = (ins.left / original.width).takeIf { it > 0.0 },
                    top = (ins.top / original.height).takeIf { it > 0.0 },
                    right = (ins.right / original.width).takeIf { it > 0.0 },
                    bottom = (ins.bottom / original.height).takeIf { it > 0.0 },
                )
            }
            val templateKey = copy.style.template
            if (!templateKey.isNullOrEmpty() && !PageTemplates.isBuiltIn(templateKey)) {
                source.templates[templateKey]?.let { out.templates += templateKey to it }
                source.templatePackages[templateKey]?.let { out.templatePackages += templateKey to it }
            }
            if (original.pdfPage == null) return
            val baked = try {
                bakePdfBackground?.invoke(source, original)
            } catch (_: Exception) {
                null
            } ?: return
            sidecars.add(baked.file)
            copy.items.add(
                0,
                ImageItem(
                    ImageData(baked.file, baked.widthPx, baked.heightPx),
                    Rect(0.0, 0.0, copy.width, copy.height),
                ),
            )
            copy.pdfPage = null
            copy.pdfTurn = 0
        }
        val skipped = ArrayList<Skipped>()
        var expandedLinks = 0

        // Match out-copy links to source links by encounter order per page.
        val outLinks: List<MutableList<NoteLinkItem>> =
            out.pages.map { it.items.filterIsInstance<NoteLinkItem>().toMutableList() }

        for (i in 0 until n) {
            val srcs = srcLinks[i]
            val dsts = outLinks[i]
            // Pair by order; both lists are in z-order and deepCopy preserves it.
            val pairs = srcs.zip(dsts)
            for ((src, dst) in pairs) {
                if (keyOf(src) != keyOf(dst)) continue // paranoia; order should match
                val valid = src.pages.filter { it >= 0 }
                if (valid.isEmpty()) {
                    skipped.add(Skipped(i, src.label(), "no pages"))
                    continue
                }
                if (src.isSelf && !expandSelf) continue
                val loaded: LoadedSource? = if (src.isSelf) LoadedSource(doc, false) else loadExternal(src.targetUri)
                val source: Document? = loaded?.doc
                if (loaded != null) loadedSources.add(loaded)
                if (source == null) {
                    skipped.add(Skipped(i, src.label(), "unreadable target"))
                    continue
                }
                val external = loaded != null && !src.isSelf
                val targets = valid.mapNotNull { p -> source.pages.getOrNull(p) }
                if (targets.isEmpty()) {
                    skipped.add(Skipped(i, src.label(), "no such page"))
                    continue
                }
                val copies = targets.map { t ->
                    t.deepCopy(measurer).also { cp ->
                        cp.cover = false
                        cp.linkBorder = src.color
                        if (external) bakeExternal(source, t, cp)
                    }
                }
                insertions.getOrPut(i) { ArrayList() }.addAll(copies)
                counts.getOrPut(i) { ArrayList() }.add(copies.size)
                rewritten.getOrPut(i) { ArrayList() }.add(dst)
                expandedLinks++
            }
        }

        // Final page list: originals in order with their insertions right after each anchor.
        val finalPages = ArrayList<Page>(n + insertions.values.sumOf { it.size })
        val newIndexOf = IntArray(n)
        for (i in 0 until n) {
            newIndexOf[i] = finalPages.size
            finalPages.add(out.pages[i])
            insertions[i]?.let { finalPages.addAll(it) }
        }

        // Rewire expanded links to the appended same-file pages.
        for (i in 0 until n) {
            val start = newIndexOf[i] + 1
            var pos = start
            val rws = rewritten[i].orEmpty()
            val cts = counts[i].orEmpty()
            for ((k, dst) in rws.withIndex()) {
                val count = cts.getOrElse(k) { 0 }
                dst.targetUri = ""
                dst.pages = (pos until pos + count).toList()
                pos += count
            }
        }

        // Remap every other self-link (including ones inside appended copies) onto new indices.
        val rewrittenSet = rewritten.values.flatten().toSet()
        for (page in finalPages) {
            for (link in page.items.filterIsInstance<NoteLinkItem>()) {
                if (link in rewrittenSet) continue
                if (!link.isSelf) continue
                link.pages = link.pages.map { p -> if (p in 0 until n) newIndexOf[p] else p }
            }
        }

        // Bookmarks and flow page-breaks follow their original page to its new slot.
        for (mark in out.bookmarks) {
            if (mark.page in 0 until n) mark.page = newIndexOf[mark.page]
        }
        for (para in out.flow.paragraphs) {
            val b = para.breakToPage
            if (b != null && b in 0 until n) para.breakToPage = newIndexOf[b]
        }

        out.pages.clear()
        out.pages.addAll(finalPages)
        out.dirty = true
        return Result(out, finalPages.size, expandedLinks, skipped, loadedSources, sidecars)
    }

    /**
     * What [page] actually draws as in [source]: the resolved paper, ruling and
     * template settings flattened into an explicit override, so the copy keeps
     * showing the same background after its document is gone.
     */
    private fun resolvedExportStyle(source: Document, page: Page): PageStyle = PageStyle(
        pageColor = page.resolvedPageColor(source, null),
        template = page.resolvedTemplate(source),
        patternColor = page.resolvedPatternColor(source),
        spacing = page.resolvedSpacing(source),
        accentColor = page.resolvedAccentColor(source),
        params = page.resolvedParams(source).takeIf { it.isNotEmpty() },
        colors = page.resolvedColors(source).takeIf { it.isNotEmpty() },
    )
}
