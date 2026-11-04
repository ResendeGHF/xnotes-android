@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.PdfTextSource
import com.xnotes.capi.c.xn_host
import com.xnotes.core.pdf.PageText
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.pathString
import com.xnotes.core.platform.withLock
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
 * A note's PDF text, read through the host off the UI thread and kept for the pages read last.
 *
 * A dense page costs ~0.4 s to read through QtPdf, far too long for the gestures that need it, so
 * [peek] only answers from the cache while [prefetch] and [request] load in the background: a
 * pointer down prefetches the page under it (the long-press delay covers the read), and a gesture
 * that needs the text now waits on it, its [onReady] running on the UI thread. Reads run on the
 * canvas's workers ([CanvasState.runAsync], delivered on [CanvasState.postToMain]); without them
 * (tests, export) everything runs synchronously on the caller.
 */
internal class CPdfTexts(private val host: xn_host, private val state: CanvasState) : PdfTextSource {
    private val lock = Lock()
    private val read = LinkedHashMap<Int, PageText>()
    private var forDocument: Any? = null

    /** PDF indices loading now. */
    private val inFlight = HashSet<Int>()

    /** Gesture continuations waiting on a load, by PDF index; run on the UI thread once it lands. */
    private val waiters = HashMap<Int, MutableList<(PageText?) -> Unit>>()

    private data class Target(val doc: Any, val path: String, val pdfIndex: Int)

    private fun targetOf(page: Int): Target? {
        val doc = state.document
        val index = doc.pages.getOrNull(page)?.pdfPage ?: return null
        val path = doc.pdfFile?.pathString ?: return null
        return Target(doc, path, index)
    }

    private fun dropStale(doc: Any) = withLock(lock) {
        if (forDocument !== doc) {
            read.clear()
            // Loads for the outgoing note still land, but find no waiter and no cache home.
            inFlight.clear()
            waiters.clear()
            forDocument = doc
        }
    }

    override fun peek(page: Int): PageText? {
        val t = targetOf(page) ?: return null
        dropStale(t.doc)
        return withLock(lock) { read[t.pdfIndex] }
    }

    override fun prefetch(page: Int) {
        val t = targetOf(page) ?: return
        dropStale(t.doc)
        val start = withLock(lock) {
            if (read.containsKey(t.pdfIndex) || !inFlight.add(t.pdfIndex)) false else true
        }
        if (!start) return
        state.runAsync {
            val text = readPdfPageText(host, t.path, t.pdfIndex)
            val due = withLock(lock) {
                inFlight.remove(t.pdfIndex)
                if (text != null && forDocument === t.doc) {
                    read[t.pdfIndex] = text
                    if (read.size > KEPT_PAGES) read.remove(read.keys.first())
                }
                waiters.remove(t.pdfIndex)?.toList() ?: emptyList()
            }
            if (due.isNotEmpty()) state.postToMain { for (w in due) w(if (state.document === t.doc) text else null) }
        }
    }

    override fun request(page: Int, onReady: (PageText?) -> Unit) {
        val t = targetOf(page)
        if (t == null) return onReady(null)
        dropStale(t.doc)
        // One locked decision: answer from the cache, join a running load, or start one. A load
        // landing between an earlier peek and here is re-checked inside the lock, so no ready is
        // lost and none is delivered twice.
        var startLoad = false
        val hit = withLock(lock) {
            val now = read[t.pdfIndex]
            if (now == null) {
                if (t.pdfIndex !in inFlight) {
                    inFlight.add(t.pdfIndex)
                    startLoad = true
                }
                waiters.getOrPut(t.pdfIndex) { ArrayList() }.add(onReady)
            }
            now
        }
        if (hit != null) return onReady(hit)
        if (!startLoad) return
        state.runAsync {
            val text = readPdfPageText(host, t.path, t.pdfIndex)
            val due = withLock(lock) {
                inFlight.remove(t.pdfIndex)
                if (text != null && forDocument === t.doc) {
                    read[t.pdfIndex] = text
                    if (read.size > KEPT_PAGES) read.remove(read.keys.first())
                }
                waiters.remove(t.pdfIndex)?.toList() ?: emptyList()
            }
            state.postToMain { for (w in due) w(if (state.document === t.doc) text else null) }
        }
    }
}
