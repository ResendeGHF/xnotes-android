@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.PdfTextSource
import com.xnotes.capi.c.xn_host
import com.xnotes.core.pdf.PageText
import com.xnotes.core.platform.pathString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set

/** How many PDF pages' text a note keeps read. */
private const val KEPT_PAGES = 48

/** A PDF page's text through the host's pdf_text; null when the host has none or cannot read the page. */
internal fun readPdfPageText(host: xn_host, path: String, page: Int): PageText? {
    val read = host.pdf_text ?: return null
    return memScoped {
        val cpath = path.cstr.ptr
        val count = read.invoke(host.ctx, cpath, page, 0, null, null)
        if (count < 0) return@memScoped null
        val cps = allocArray<UIntVar>(count)
        val boxes = allocArray<FloatVar>(4 * count)
        if (read.invoke(host.ctx, cpath, page, count, cps, boxes) != count) return@memScoped null
        PageText(
            IntArray(count) { cps[it].toInt() },
            FloatArray(4 * count) { boxes[it] },
            ByteArray(count),
            null,
        )
    }
}

/**
 * A note's PDF text, read through the host when a page is first asked for and kept for the pages
 * read last. Reads happen on the UI thread, which the selection gestures already run on.
 */
internal class CPdfTexts(private val host: xn_host, private val state: CanvasState) : PdfTextSource {
    private val read = LinkedHashMap<Int, PageText?>()
    private var forDocument: Any? = null

    private fun load(page: Int): PageText? {
        val doc = state.document
        if (forDocument !== doc) {
            read.clear()
            forDocument = doc
        }
        val index = doc.pages.getOrNull(page)?.pdfPage ?: return null
        val path = doc.pdfFile?.pathString ?: return null
        if (read.containsKey(index)) return read[index]
        val text = readPdfPageText(host, path, index)
        read[index] = text
        if (read.size > KEPT_PAGES) read.remove(read.keys.first())
        return text
    }

    override fun peek(page: Int): PageText? = load(page)

    /** Reads are made when asked for, so there is nothing to start early. */
    override fun prefetch(page: Int) = Unit

    override fun request(page: Int, onReady: (PageText?) -> Unit) = onReady(load(page))
}
