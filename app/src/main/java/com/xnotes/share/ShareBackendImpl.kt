package com.xnotes.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedInvertColors
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.PdfExporter
import com.xnotes.platform.PdfSource
import com.xnotes.settings.SettingsRepository
import java.io.File
import java.io.FileOutputStream

/**
 * [ShareServer.ShareBackend] over the app's storage: the granted SAF browse
 * root plus standalone PDFs next to the notes.
 *
 * Commit semantics: every version exposed here derives from the *stored*
 * file's size+mtime stamp, which only moves after a full write lands (the
 * editor persists via temp-encode-then-copy). Mid-stroke states live in RAM
 * and are never visible to this backend, so `/api/watch` and the PDF cache
 * only ever surface committed content — no live stroke streaming by design.
 * A small debounce in [startWatching] additionally guards against reading a
 * file mid-copy on flaky providers.
 */
class ShareBackendImpl(private val appContext: Context) : ShareServer.ShareBackend {

    private val saveLock = Any()
    private val monitorObj = Object()
    private val pdfCacheDir = File(appContext.cacheDir, "share-pdf").apply { mkdirs() }
    private val tmpDir = File(appContext.cacheDir, "share-tmp").apply { mkdirs() }
    private val grants: com.xnotes.share.db.GrantSource = com.xnotes.share.db.DeviceGrants.of(appContext)

    /** URIs recently served or watched; the commit poller only stats these. */
    private val observed = LinkedHashMap<String, String>()
    private var watching = false

    private val codec: DocumentCodec by lazy {
        DocumentCodec(AndroidImageCodec(), AndroidTextMeasurer())
    }

    // --- ShareBackend ---

    override fun versionOf(uri: String): String? {
        if (!grants.isShared(uri)) return null
        val stamp = stampOf(uri) ?: return null
        return ShareProtocol.etagFor(stamp.first, stamp.second)
    }

    override fun browseRootSet(): Boolean = browseRoot() != null

    override fun isShared(uri: String): Boolean = grants.isShared(uri)

    override fun pdfBytes(uri: String): ShareServer.PdfSnapshot? {
        if (!grants.isShared(uri)) return null
        val name = displayNameOf(uri) ?: return null
        val version = versionOf(uri) ?: return null
        touch(uri, version)
        return when (kindOf(name)) {
            "pdf" -> {
                val bytes = readUri(uri, 64L * 1024 * 1024) ?: return null
                ShareServer.PdfSnapshot(bytes, version, ensurePdfName(name))
            }
            "note" -> cachedNotePdf(uri, name, version) ?: return null
            else -> null // canvases: export lands as a follow-up; listing still shows them.
        }
    }

    override fun rawBytes(uri: String): ShareServer.RawSnapshot? {
        if (!grants.isShared(uri)) return null
        val name = displayNameOf(uri) ?: return null
        val version = versionOf(uri) ?: return null
        val bytes = readUri(uri, 64L * 1024 * 1024) ?: return null
        val mime = when (kindOf(name)) {
            "pdf" -> "application/pdf"
            "note" -> "application/x-xnote"
            else -> "application/octet-stream"
        }
        return ShareServer.RawSnapshot(bytes, version, name, mime)
    }

    override fun replacePdf(uri: String, bytes: ByteArray, baseVersion: String): ShareServer.ReplaceResult {
        if (bytes.isEmpty() || bytes.size > ShareProtocol.MAX_PUT_BYTES) {
            return ShareServer.ReplaceResult.Rejected(413, "size out of range")
        }
        if (!bytes.startsWithPdf()) {
            return ShareServer.ReplaceResult.Rejected(415, "not a pdf")
        }
        if (!grants.isShared(uri)) {
            return ShareServer.ReplaceResult.Rejected(404, "no such file")
        }
        synchronized(saveLock) {
            val name = displayNameOf(uri)
                ?: return ShareServer.ReplaceResult.Rejected(404, "no such file")
            val current = versionOf(uri)
                ?: return ShareServer.ReplaceResult.Rejected(404, "no such file")
            if (current != baseVersion) {
                return ShareServer.ReplaceResult.Rejected(409, "stale base; re-fetch and rebase")
            }
            val ok = when (kindOf(name)) {
                "pdf" -> replaceRawPdf(uri, bytes)
                "note" -> replaceEmbeddedPdf(uri, bytes)
                else -> return ShareServer.ReplaceResult.Rejected(415, "canvas replace unsupported")
            }
            if (!ok) return ShareServer.ReplaceResult.Rejected(500, "write failed; device copy kept")
            touch(uri, versionOf(uri) ?: current)
            synchronized(monitorObj) { (monitorObj as Object).notifyAll() }
            return ShareServer.ReplaceResult.Ok(versionOf(uri) ?: current)
        }
    }

    override fun monitor(): Any = monitorObj

    // --- commit watcher ---

    /**
     * Start the background commit poller. It re-stats [observed] files every
     * 2 s and, after a 1.5 s debounce re-check, treats a stamp move as a
     * commit: the PDF cache entry is dropped and watchers are woken. Slow,
     * cheap, and independent of the editor's in-memory state.
     */
    fun startWatching() {
        synchronized(this) {
            if (watching) return
            watching = true
        }
        Thread(::pollLoop, "share-watch").apply { isDaemon = true; start() }
    }

    private fun pollLoop() {
        while (true) {
            try {
                Thread.sleep(2_000L)
            } catch (_: InterruptedException) {
                return
            }
            val snapshot: List<Pair<String, String>>
            synchronized(this) { snapshot = observed.toList() }
            if (snapshot.isEmpty()) continue
            var changed = false
            for ((uri, known) in snapshot) {
                val now = versionOf(uri) ?: continue
                if (now == known) continue
                try {
                    Thread.sleep(1_500L) // debounce: the copy must have settled
                } catch (_: InterruptedException) {
                    return
                }
                val settled = versionOf(uri) ?: continue
                if (settled == known) continue
                dropCachedPdf(uri)
                synchronized(this) { observed[uri] = settled }
                changed = true
            }
            if (changed) synchronized(monitorObj) { (monitorObj as Object).notifyAll() }
        }
    }

    // --- PDF export ---

    private fun cachedNotePdf(uri: String, name: String, version: String): ShareServer.PdfSnapshot? {
        val key = cacheKey(uri)
        val cachedFile = File(pdfCacheDir, "$key.pdf")
        val versionFile = File(pdfCacheDir, "$key.version")
        if (cachedFile.exists() && versionFile.readTextOrNull() == version) {
            val bytes = runCatching { cachedFile.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return ShareServer.PdfSnapshot(bytes, version, ensurePdfName(name))
            }
        }
        val bytes = exportNotePdf(uri) ?: return null
        runCatching {
            val tmp = File(pdfCacheDir, "$key.pdf.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(cachedFile)) {
                cachedFile.delete()
                tmp.renameTo(cachedFile)
            }
            File(pdfCacheDir, "$key.version.tmp").writeText(version)
            File(pdfCacheDir, "$key.version.tmp").renameTo(versionFile)
        }
        return ShareServer.PdfSnapshot(bytes, version, ensurePdfName(name))
    }

    /**
     * Render the *stored* bundle to PDF on a worker thread. The source of
     * truth is the committed file bytes, so device edits appear on the
     * desktop exactly when the device's save commits them.
     */
    private fun exportNotePdf(uri: String): ByteArray? = try {
        val bundle = readUri(uri, ShareProtocol.MAX_PUT_BYTES) ?: return null
        val workPdf = File.createTempFile("share-src", ".pdf", tmpDir)
        val workImg = File(tmpDir, "share-img-${System.nanoTime()}").apply { mkdirs() }
        try {
            val doc = bundle.inputStream().use { codec.read(it, workPdf.parentFile, workImg) }
            val source = doc.pdfFile?.let { PdfSource.create(it) }
            try {
                // Templates live in the app process already, but the service can
                // outlive a warm start; init is cheap when already done.
                runCatching { com.xnotes.platform.TemplateLibrary.init(appContext) }
                val out = java.io.ByteArrayOutputStream()
                PdfExporter.export(
                    appContext, doc, source, out,
                    paperColor = { page -> exportPaper(doc, page) },
                    paintRuling = { page, r -> paintExportRuling(doc, page, r) },
                    title = doc.title.ifEmpty { "note" },
                )
                out.toByteArray().takeIf { it.isNotEmpty() }
            } finally {
                source?.close()
            }
        } finally {
            workPdf.delete()
            workImg.deleteRecursively()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The page as the device shows it: the note's own paper colour (or plain
     * white when it follows the theme, which the server cannot resolve),
     * lightness-inverted together with the ink when inversion is on.
     */
    private fun exportPaper(doc: com.xnotes.core.model.Document, page: com.xnotes.core.model.Page): Rgba {
        val paper = page.resolvedPageColor(doc, null) ?: Rgba(255, 255, 255)
        return if (page.resolvedInvertColors(doc)) paper.inverted() else paper
    }

    /** The note's template ruling over the whole paper, margins included. */
    private fun paintExportRuling(
        doc: com.xnotes.core.model.Document,
        page: com.xnotes.core.model.Page,
        r: com.xnotes.core.pal.Renderer,
    ) {
        val i = page.insets(doc)
        val cover = com.xnotes.core.geometry.Rect(-i.left, -i.top, i.left + page.width + i.right, i.top + page.height + i.bottom)
        com.xnotes.platform.TemplateLibrary.paint(r, doc, page, cover, cover)
    }

    private fun dropCachedPdf(uri: String) {
        val key = cacheKey(uri)
        File(pdfCacheDir, "$key.pdf").delete()
        File(pdfCacheDir, "$key.version").delete()
    }

    private fun cacheKey(uri: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)

    // --- PUT writers ---

    /**
     * Run [block] with the stored `.xnote` decoded. Asset files land in an
     * isolated temp dir that is removed afterwards; the returned document
     * must not escape the block with live asset references.
     */
    private inline fun <T> withNoteDoc(uri: String, block: (com.xnotes.core.model.Document) -> T): T? {
        val stored = readUri(uri, ShareProtocol.MAX_PUT_BYTES) ?: return null
        val workDir = File(tmpDir, "share-doc-${System.nanoTime()}").apply { mkdirs() }
        try {
            val doc = stored.inputStream().use { codec.read(it, workDir, workDir) }
            return block(doc)
        } catch (_: Exception) {
            return null
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Sealed rewrite of [doc] over [uri]: temp-encode, validate, copy, never truncate. */
    private fun storeNoteDoc(uri: String, doc: com.xnotes.core.model.Document): Boolean {
        val tmpNote = File.createTempFile("share-put", ".xnote", tmpDir)
        try {
            FileOutputStream(tmpNote).use { codec.write(doc, it) }
            if (!validates(tmpNote, doc.pages.size)) return false
            return copyFileToUri(tmpNote, uri)
        } catch (_: Exception) {
            return false
        } finally {
            tmpNote.delete()
        }
    }

    /** Swap the embedded source PDF of a `.xnote` bundle, preserving ink and markups on top. */
    private fun replaceEmbeddedPdf(uri: String, bytes: ByteArray): Boolean {
        return try {
            val newPdf = File.createTempFile("share-put", ".pdf", tmpDir)
            try {
                FileOutputStream(newPdf).use { it.write(bytes) }
                withNoteDoc(uri) { doc ->
                    if (doc.pdfFile == null) return@withNoteDoc false
                    doc.pdfFile = newPdf
                    storeNoteDoc(uri, doc)
                } != false
            } finally {
                newPdf.delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun replaceRawPdf(uri: String, bytes: ByteArray): Boolean = try {
        val tmp = File.createTempFile("share-put", ".pdf", tmpDir)
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            copyFileToUri(tmp, uri)
        } finally {
            tmp.delete()
        }
    } catch (_: Exception) {
        false
    }

    private fun validates(tmp: File, expectedPages: Int): Boolean = runCatching {
        tmp.inputStream().use { codec.peek(it)?.pages == expectedPages }
    }.getOrDefault(false)

    // --- companion canvas ---

    override fun canvasInfo(uri: String): ShareServer.ShareBackend.CanvasInfo? {
        if (!grants.isShared(uri)) return null
        val version = versionOf(uri) ?: return null
        val name = displayNameOf(uri) ?: return null
        if (kindOf(name) != "note") return null
        val doc = withNoteDoc(uri) { it } ?: return null
        val pt = 72.0 / doc.dpi
        return ShareServer.ShareBackend.CanvasInfo(
            version = version,
            title = doc.title.ifEmpty { name },
            dpi = doc.dpi,
            pages = doc.pages.map {
                ShareServer.ShareBackend.PageSpec(it.width * pt, it.height * pt)
            },
        )
    }

    override fun pageStrokes(uri: String, page: Int): ShareServer.ShareBackend.StrokesPage? {
        if (!grants.isShared(uri)) return null
        val version = versionOf(uri) ?: return null
        val name = displayNameOf(uri) ?: return null
        if (kindOf(name) != "note") return null
        val doc = withNoteDoc(uri) { it } ?: return null
        if (page !in doc.pages.indices) return null
        val pxToPt = 72.0 / doc.dpi
        val strokes = doc.pages[page].items
            .filterIsInstance<com.xnotes.core.model.Stroke>()
            .mapNotNull { ShareStrokes.toWire(it, pxToPt) }
        return ShareServer.ShareBackend.StrokesPage(strokes, version)
    }

    override fun appendStrokes(
        uri: String,
        page: Int,
        baseVersion: String,
        strokes: List<ShareStrokes.WireStroke>,
    ): ShareServer.ReplaceResult {
        if (strokes.isEmpty() || strokes.size > ShareStrokes.MAX_STROKES_PER_POST) {
            return ShareServer.ReplaceResult.Rejected(400, "stroke batch out of range")
        }
        if (!grants.isShared(uri)) return ShareServer.ReplaceResult.Rejected(404, "no such file")
        synchronized(saveLock) {
            val current = versionOf(uri) ?: return ShareServer.ReplaceResult.Rejected(404, "no such file")
            if (current != baseVersion) {
                return ShareServer.ReplaceResult.Rejected(409, "stale base; re-fetch and rebase")
            }
            val name = displayNameOf(uri) ?: return ShareServer.ReplaceResult.Rejected(404, "no such file")
            if (kindOf(name) != "note") {
                return ShareServer.ReplaceResult.Rejected(415, "strokes need a note")
            }
            val ok = withNoteDoc(uri) { doc ->
                if (page !in doc.pages.indices) return@withNoteDoc false
                val ptToPx = doc.dpi / 72.0
                val native = strokes.map { ShareStrokes.toNative(it, ptToPx) }
                if (native.any { it == null }) return@withNoteDoc false
                doc.pages[page].items.addAll(native.filterNotNull())
                storeNoteDoc(uri, doc)
            } == true
            if (!ok) return ShareServer.ReplaceResult.Rejected(500, "write failed; device copy kept")
            touch(uri, versionOf(uri) ?: current)
            synchronized(monitorObj) { (monitorObj as Object).notifyAll() }
            return ShareServer.ReplaceResult.Ok(versionOf(uri) ?: current)
        }
    }

    /**
     * Render one page of the committed snapshot to PNG for the companion
     * canvas background. Raw PDFs render straight from storage; notes render
     * from the cached export (which also carries the device's ink).
     */
    override fun pagePng(uri: String, page: Int, dpi: Int): ShareServer.ShareBackend.PagePng? {
        if (!grants.isShared(uri)) return null
        if (page < 0 || dpi !in 36..300) return null
        val version = versionOf(uri) ?: return null
        val name = displayNameOf(uri) ?: return null
        touch(uri, version)
        val key = "pg-${cacheKey(uri)}-$page-$dpi"
        val cachedFile = File(pdfCacheDir, "$key.png")
        val versionFile = File(pdfCacheDir, "$key.version")
        if (cachedFile.exists() && versionFile.readTextOrNull() == version) {
            val bytes = runCatching { cachedFile.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return ShareServer.ShareBackend.PagePng(bytes, version, page, dpi)
            }
        }
        val bytes = renderPagePng(uri, name, page, dpi) ?: return null
        runCatching {
            val tmp = File(pdfCacheDir, "$key.png.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(cachedFile)) {
                cachedFile.delete()
                tmp.renameTo(cachedFile)
            }
            File(pdfCacheDir, "$key.version.tmp").writeText(version)
            File(pdfCacheDir, "$key.version.tmp").renameTo(versionFile)
        }
        return ShareServer.ShareBackend.PagePng(bytes, version, page, dpi)
    }

    private fun renderPagePng(uri: String, name: String, page: Int, dpi: Int): ByteArray? = try {
        val pfd = when (kindOf(name)) {
            "pdf" -> appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
            "note" -> {
                val version = versionOf(uri) ?: return null
                cachedNotePdf(uri, name, version) ?: return null
                val snap = File(pdfCacheDir, "${cacheKey(uri)}.pdf")
                if (!snap.exists()) return null
                android.os.ParcelFileDescriptor.open(snap, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
            else -> return null
        } ?: return null
        pfd.use { fd ->
            val renderer = android.graphics.pdf.PdfRenderer(fd)
            try {
                if (page >= renderer.pageCount) return null
                renderer.openPage(page).use { p ->
                    val scale = dpi / 72f
                    val w = (p.width * scale).toInt().coerceIn(1, 4096)
                    val h = (p.height * scale).toInt().coerceIn(1, 4096)
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    bmp.recycle()
                    out.toByteArray().takeIf { it.isNotEmpty() }
                }
            } finally {
                renderer.close()
            }
        }
    } catch (_: Exception) {
        null
    }

    // --- SAF helpers ---

    override fun listEntries(dirUri: String?, onlyShared: Boolean): List<ShareServer.ShareBackend.Entry> {
        val root = browseRoot() ?: return emptyList()
        val treeUri = Uri.parse(root)
        val dirDocId = if (dirUri.isNullOrEmpty()) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            // Never escape the granted tree, however the client crafts the uri.
            if (!isUnderTree(dirUri, root)) return emptyList()
            runCatching { DocumentsContract.getDocumentId(Uri.parse(dirUri)) }.getOrNull() ?: return emptyList()
        }
        // When filtering, a directory shows only while a shared file lives under it.
        val sharedIds: Set<String>? = if (onlyShared) {
            val ids = grants.sharedUris().mapNotNull { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }.toSet()
            if (!ids.any { it == dirDocId || it.startsWith("$dirDocId/") }) return emptyList()
            ids
        } else {
            null
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val cursor = runCatching {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<ShareServer.ShareBackend.Entry>()
        cursor.use {
            while (it.moveToNext()) {
                val docId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = it.getString(2) ?: ""
                val size = if (it.isNull(3)) -1L else it.getLong(3)
                val mtime = if (it.isNull(4)) 0L else it.getLong(4)
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (sharedIds == null || sharedIds.any { it == docId || it.startsWith("$docId/") }) {
                        out.add(ShareServer.ShareBackend.Entry(uri, name, size, mtime, "dir", isDir = true))
                    }
                    continue
                }
                if (sharedIds != null) {
                    val shared = runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }
                        .getOrNull()?.let { it in sharedIds } == true
                    if (!shared) continue
                }
                out.add(ShareServer.ShareBackend.Entry(uri, name, size, mtime, kindOf(name)))
            }
        }
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    private fun isUnderTree(fileUri: String, treeUri: String): Boolean = runCatching {
        val f = Uri.parse(fileUri)
        val t = Uri.parse(treeUri)
        if (f.authority != t.authority) return false
        val treeId = DocumentsContract.getTreeDocumentId(t)
        val fileId = DocumentsContract.getDocumentId(f)
        fileId == treeId || fileId.startsWith("$treeId/")
    }.getOrDefault(false)

    private fun browseRoot(): String? = runCatching {
        SettingsRepository(appContext).load().browseRoot
    }.getOrNull()

    private fun kindOf(name: String): String = when {
        name.endsWith(DocumentKind.NOTE.suffix, ignoreCase = true) -> "note"
        name.endsWith(DocumentKind.CANVAS.suffix, ignoreCase = true) -> "canvas"
        name.endsWith(".pdf", ignoreCase = true) -> "pdf"
        else -> "file"
    }

    private fun stampOf(uri: String): Pair<Long, Long>? = runCatching {
        appContext.contentResolver.query(
            Uri.parse(uri),
            arrayOf(
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use {
            if (!it.moveToFirst()) return null
            val size = if (it.isNull(0)) -1L else it.getLong(0)
            val mtime = if (it.isNull(1)) 0L else it.getLong(1)
            if (size < 0 && mtime <= 0) null else size to mtime
        }
    }.getOrNull()

    /** Name/size/mtime/kind for one URI, without walking the tree (for the policy screen). */
    fun statEntry(uri: String): ShareServer.ShareBackend.Entry? {
        if (!isUnderTree(uri, browseRoot() ?: return null)) return null
        val name = displayNameOf(uri) ?: return null
        if (name.startsWith(".")) return null
        val dir = isDirUri(uri)
        val (size, mtime) = if (dir) (-1L to 0L) else stampOf(uri) ?: (-1L to 0L)
        return ShareServer.ShareBackend.Entry(uri, name, size, mtime, if (dir) "folder" else kindOf(name), isDir = dir)
    }

    private fun isDirUri(uri: String): Boolean = runCatching {
        appContext.contentResolver.query(
            Uri.parse(uri),
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use {
            if (!it.moveToFirst()) false else it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false
    }.getOrDefault(false)

    private fun displayNameOf(uri: String): String? = runCatching {
        appContext.contentResolver.query(
            Uri.parse(uri),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use {
            if (!it.moveToFirst()) null else it.getString(0)
        }
    }.getOrNull()

    private fun readUri(uri: String, cap: Long): ByteArray? = try {
        appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    /** Replace a shared file's bytes (sealed temp+copy, never a truncation of a good file). */
    fun writeRawBytes(uri: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            val tmp = File.createTempFile("share-replace", null, appContext.cacheDir)
            try {
                tmp.writeBytes(bytes)
                copyFileToUri(tmp, uri)
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Delete a shared document. */
    fun deleteDoc(uri: String): Boolean = try {
        DocumentsContract.deleteDocument(appContext.contentResolver, android.net.Uri.parse(uri))
    } catch (_: Exception) {
        false
    }

    /**
     * Create a file or folder under [dirUri] (or the browse root): returns the
     * new document URI. A creation the user approved is shared from birth.
     */
    fun createDoc(dirUri: String?, name: String, isDir: Boolean): String? = try {
        val tree = browseRoot() ?: return null
        val parent = dirUri?.let { android.net.Uri.parse(it) }
            ?: DocumentsContract.buildDocumentUriUsingTree(
                android.net.Uri.parse(tree),
                DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree)),
            )
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        DocumentsContract.createDocument(appContext.contentResolver, parent, mime, name)?.toString()
    } catch (_: Exception) {
        null
    }

    /** Same sealed-write shape the editor uses: temp first, copy over, never truncate a good file. */
    private fun copyFileToUri(tmp: File, uri: String): Boolean = try {
        appContext.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { out ->
            tmp.inputStream().use { it.copyTo(out) }
            true
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun ensurePdfName(name: String): String {
        val stem = name.substringBeforeLast('.', name).ifEmpty { "note" }
        val clean = stem.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).trim('_')
        return (clean.ifEmpty { "note" }) + ".pdf"
    }

    private fun ByteArray.startsWithPdf(): Boolean =
        size > 5 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() &&
            this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte()

    private fun File.readTextOrNull(): String? = runCatching { readText(Charsets.UTF_8) }.getOrNull()

    @Synchronized
    private fun touch(uri: String, version: String) {
        observed[uri] = version
        if (observed.size > 64) {
            val first = observed.keys.firstOrNull()
            if (first != null) observed.remove(first)
        }
    }
}
