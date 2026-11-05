package com.xnotes.share.companion

import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.format.DocumentCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The guest side of SYNC.md v1 for Android-to-Android sharing: this phone
 * edits files hosted by an Android peer.
 *
 * Mirrors the host protocol: subscribes to the peer's shared index, fetches
 * file bytes into a version-aware local cache (refetch only when the
 * advertised version moves), applies the host's committed ops and live
 * strokes to the open document, and submits local edits with the head
 * version as base.
 *
 * Cache layout under [cacheDir]: `<fp12>/<sha256(remoteId)>.xnote` plus
 * `meta.json` mapping hashes back to `{remoteId, version, path, name}` so a
 * reconnect serves unchanged files instantly instead of refetching them.
 *
 * Threading matches [HostEngine]: [onMessage] and controller hooks run on
 * the main thread; file IO runs on [io].
 */
class GuestEngine(
    /** Peer's cert fingerprint; empty until the TLS handshake identifies it. */
    var hostFp: String,
    private val sender: Sender,
    private val host: GuestHost,
    /** Already-scoped cache root (per dial target); meta + blobs live here. */
    cacheRoot: File,
    var peerName: String,
    /** Transport liveness, for guarded sends (fetch when offline reports instead). */
    var isConnected: () -> Boolean = { true },
) {
    fun interface Sender {
        fun send(msg: JSONObject)
    }

    interface GuestHost {
        /** The live document open for [cacheUri], or null. */
        fun openDocument(cacheUri: String): Document?
        fun repaintDocument(cacheUri: String)
        fun repaintLive(cacheUri: String)
        fun editorState(cacheUri: String): com.xnotes.canvas.CanvasState?
        fun structureChanged(cacheUri: String)
        fun previewRoom(cacheUri: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double)
        fun clearRoomPreview(cacheUri: String)
        fun applyRoomEdit(cacheUri: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean
        fun liveOverlay(cacheUri: String): RemoteLiveStrokes?
        /** The remote index changed; the Shared-with-me screen re-renders. */
        fun onIndexChanged(hostFp: String)
        /** A fetch completed: open [cacheUri] in the editor when [openNow]. */
        fun onFileReady(hostFp: String, remoteId: String, cacheUri: String, version: Long, openNow: Boolean)
        /** A create/delete request was answered (toast/status wiring). */
        fun onResult(hostFp: String, ok: Boolean, reason: String)
        /** A link access request was answered: open [remoteId] on ok. */
        fun onLinkResult(hostFp: String, remoteId: String, ok: Boolean, reason: String)
        fun onError(reason: String)
    }

    data class RemoteEntry(val id: String, val path: String, val kind: Int, val version: Long, val size: Long)

    private val lock = Any()
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "xnotes-guest-io").apply { isDaemon = true } }
    private val dir = cacheRoot.apply { mkdirs() }
    private var seq = 0

    /** Last advertised index, by remote id. */
    private val index = HashMap<String, RemoteEntry>()
    /** Known host versions, by remote id (from index/file/op/op_ack). */
    private val versions = HashMap<String, Long>()
    /** Cache file bound to each remote id. */
    private val bound = HashMap<String, String>()
    /** Dedupes of our own submitted ops, to drop the host's echo. */
    private val sentDedupes = HashMap<String, ArrayDeque<String>>()
    /** Host presence: remote id -> page. */
    private val hostPresence = HashMap<String, Int>()

    private fun send(msg: JSONObject) {
        msg.put("seq", ++seq)
        sender.send(msg)
    }

    // --- session ---

    /** Called when pairing finished (or on every reconnect): subscribe. */
    fun start() {
        send(JSONObject().put("t", "index_subscribe"))
    }

    fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "index" -> onIndex(msg)
            "file" -> onFileChunk(msg)
            "op" -> onOp(msg)
            "op_ack" -> onOpAck(msg)
            "resync" -> onResync(msg)
            "live" -> onLive(msg)
            "room_live" -> onRoomLive(msg)
            "image_data" -> onImageChunk(msg)
        "audio_data" -> onAudioChunk(msg, peaks = false)
        "audio_peaks" -> onAudioChunk(msg, peaks = true)
            "presence" -> synchronized(lock) {
                val file = msg.optString("file")
                if (file.isNotEmpty()) hostPresence[file] = msg.optInt("page", -1)
            }
            "delete_result", "create_result" -> host.onResult(
                hostFp, msg.optBoolean("ok", false), msg.optString("reason"),
            )
            "link_result" -> host.onLinkResult(
                hostFp, msg.optString("id"), msg.optBoolean("ok", false), msg.optString("reason"),
            )
            "heartbeat" -> Unit
            else -> Unit
        }
    }

    /** Ask the host for access to a link target; the answer lands in `onLinkResult`. */
    fun requestLinkAccess(remoteId: String) {
        if (remoteId.isEmpty()) return
        send(JSONObject().put("t", "link_request").put("id", remoteId).put("device", peerName))
    }

    fun remoteIndex(): List<RemoteEntry> = synchronized(lock) { index.values.toList() }

    fun presenceOf(remoteId: String): Int = synchronized(lock) { hostPresence[remoteId] ?: -1 }

    // --- index ---

    private fun onIndex(msg: JSONObject) {
        val arr = msg.optJSONArray("files") ?: JSONArray()
        val fresh = HashMap<String, RemoteEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            fresh[id] = RemoteEntry(id, o.optString("path"), o.optInt("kind", 0), o.optLong("version", 1L), o.optLong("size", 0L))
        }
        val stale: List<String>
        synchronized(lock) {
            stale = index.keys.filter { it !in fresh }
            index.clear()
            index.putAll(fresh)
            for ((id, e) in fresh) versions[id] = maxOf(versions[id] ?: 1L, e.version)
        }
        // Drop cache bindings for files that are no longer shared.
        for (id in stale) synchronized(lock) { bound.remove(id) }
        host.onIndexChanged(hostFp)
        // Opportunistically refresh open docs whose version moved.
        for ((id, e) in fresh) {
            val cache = synchronized(lock) { bound[id] }
            if (cache != null && host.openDocument(cache) != null) {
                if (e.version != synchronized(lock) { versions[id] }) fetch(id, openNow = false)
            }
        }
    }

    // --- fetch / file cache ---

    /**
     * Fetch [remoteId] into the cache; opens it in the editor when [openNow].
     * Skips the socket entirely when the cache already holds the advertised
     * version.
     */
    fun fetch(remoteId: String, openNow: Boolean) {
        val known = synchronized(lock) { versions[remoteId] ?: 0L }
        val cache = cacheFile(remoteId)
        if (cache.isFile && cacheMeta(remoteId)?.version == known && known > 0) {
            bind(remoteId, cache)
            host.onFileReady(hostFp, remoteId, cacheUri(cache), known, openNow)
            return
        }
        if (!isConnected()) {
            host.onError("Not connected.")
            return
        }
        fileAsm.remove(remoteId)
        send(JSONObject().put("t", "fetch").put("id", remoteId).put("offset", 0).put("length", 0))
        pendingOpen.add(remoteId to openNow)
    }

    private val pendingOpen = ArrayDeque<Pair<String, Boolean>>()

    private class FileAsm(val total: Long, val buf: ByteArrayOutputStream, var filled: Long = 0)
    private val fileAsm = HashMap<String, FileAsm>()

    private fun onFileChunk(msg: JSONObject) {
        val id = msg.optString("id")
        if (id.isEmpty()) return
        val total = msg.optLong("total", -1)
        val offset = msg.optLong("offset", -1)
        if (total < 0 || total > 64L * 1024 * 1024 || offset < 0 || offset > total) {
            fileAsm.remove(id)
            return
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(msg.optString("data"))
        } catch (_: Exception) {
            fileAsm.remove(id)
            return
        }
        val asm = fileAsm.getOrPut(id) { FileAsm(total, ByteArrayOutputStream(total.coerceAtMost(8L * 1024 * 1024).toInt())) }
        if (asm.total != total || offset != asm.filled || asm.filled + bytes.size > asm.total) {
            fileAsm.remove(id)
            return
        }
        asm.buf.write(bytes)
        asm.filled += bytes.size
        if (!msg.optBoolean("last", false) && asm.filled < asm.total) return
        if (asm.filled != asm.total) {
            fileAsm.remove(id)
            return
        }
        fileAsm.remove(id)
        val version = msg.optLong("version", 1L)
        val data = stripInvert(asm.buf.toByteArray())
        val openNow = synchronized(lock) {
            val want = pendingOpen.filter { it.first == id }.lastOrNull()?.second ?: (host.openDocument(bound[id] ?: "") != null)
            pendingOpen.removeAll { it.first == id }
            versions[id] = version
            want
        }
        io.execute {
            val cache = cacheFile(id)
            runCatching {
                cache.parentFile?.mkdirs()
                val tmp = File(cache.parentFile, "${cache.name}.tmp")
                tmp.writeBytes(data)
                if (!tmp.renameTo(cache)) {
                    cache.delete()
                    tmp.renameTo(cache)
                }
                saveMeta(id, version, data.size.toLong())
            }
            bind(id, cache)
            host.onFileReady(hostFp, id, cacheUri(cache), version, openNow)
        }
    }

    private fun onResync(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        synchronized(lock) { versions[file] = msg.optLong("version", versions[file] ?: 1L) }
        fetch(file, openNow = false)
    }

    /** The transport dropped: failOutstanding fetches instead of hanging silently. */
    fun onDisconnected(report: Boolean) {
        val pending = synchronized(lock) {
            val p = pendingOpen.toList()
            pendingOpen.clear()
            p
        }
        if (report && pending.isNotEmpty()) {
            host.onError("Not connected.")
        }
    }

    private fun onOpAck(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        synchronized(lock) { versions[file] = maxOf(versions[file] ?: 1L, msg.optLong("version", 1L)) }
    }

    // --- committed ops from the host ---

    private fun onOp(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        val dedupe = msg.optString("dedupe")
        val cacheUri = synchronized(lock) { bound[file] }
        // Our own echo: advance the version, skip the apply.
        if (dedupe.isNotEmpty() && synchronized(lock) { sentDedupes[file]?.contains(dedupe) == true }) {
            synchronized(lock) { versions[file] = maxOf(versions[file] ?: 1L, msg.optLong("version", 1L)) }
            return
        }
        val base = msg.optLong("base_version", -1)
        val known = synchronized(lock) { versions[file] ?: 1L }
        if (base != -1L && base != known) {
            fetch(file, openNow = false)
            return
        }
        val ops = msg.optJSONArray("ops") ?: JSONArray()
        var changed = false
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op")) {
                "add_stroke" -> {
                    val stroke = SyncWire.toNative(op.optJSONObject("stroke") ?: continue) ?: continue
                    if (applyStroke(cacheUri, file, op.optInt("page", 0), stroke)) changed = true
                }
                "erase_strokes" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: continue
                    if (applyErase(cacheUri, file, op.optInt("page", 0), ids)) changed = true
                }
                "add_page" -> {
                    if (applyPageAdd(cacheUri, file, op.optInt("index", 0), op.optDouble("width", 0.0), op.optDouble("height", 0.0))) changed = true
                }
                "remove_page" -> {
                    if (applyPageRemove(cacheUri, file, op.optInt("index", -1))) changed = true
                }
                "open_room" -> {
                    if (applyRoomEdit(cacheUri, op.optInt("page", 0), op.optBoolean("sideways", false), true, op.optDouble("at", 0.0), op.optDouble("at", 0.0) + op.optDouble("size", 0.0))) changed = true
                }
                "close_room" -> {
                    if (applyRoomEdit(cacheUri, op.optInt("page", 0), op.optBoolean("sideways", false), false, op.optDouble("a", 0.0), op.optDouble("b", 0.0))) changed = true
                }
                "add_image" -> {
                    if (applyRemoteImage(cacheUri, file, op)) changed = true
                }
                "remove_image" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
                    if (ids.isNotEmpty() && applyImageRemove(cacheUri, file, op.optInt("page", 0), ids)) changed = true
                }
                "add_text" -> {
                    val t = op.optJSONObject("text") ?: JSONObject()
                    if (applyTextAdd(cacheUri, file, op.optInt("page", 0), t)) changed = true
                }
                "remove_text" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
                    if (ids.isNotEmpty() && applyTextRemove(cacheUri, file, op.optInt("page", 0), ids)) changed = true
                }
                "add_table" -> {
                    val t = op.optJSONObject("table") ?: JSONObject()
                    if (applyTableAdd(cacheUri, file, op.optInt("page", 0), t)) changed = true
                }
                "remove_table" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
                    if (ids.isNotEmpty() && applyTableRemove(cacheUri, file, op.optInt("page", 0), ids)) changed = true
                }
                "add_audio" -> {
                    if (applyRemoteAudio(cacheUri, file, op)) changed = true
                }
                "remove_audio" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
                    if (ids.isNotEmpty() && applyAudioRemove(cacheUri, file, op.optInt("page", 0), ids)) changed = true
                }
                "add_markup" -> {
                    val m = op.optJSONObject("markup")?.let { SyncWire.markupFrom(it) }
                    if (m != null && applyMarkupAdd(cacheUri, file, op.optInt("page", 0), m)) changed = true
                }
                "remove_markup" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } }
                    if (ids != null && applyMarkupRemove(cacheUri, file, op.optInt("page", 0), ids)) changed = true
                }
                "page_style" -> {
                    if (applyPageStyle(cacheUri, file, op)) changed = true
                }
                else -> Unit
            }
        }
        if (dedupe.isNotEmpty() && cacheUri != null) host.liveOverlay(cacheUri)?.finish(dedupe)
        synchronized(lock) { versions[file] = maxOf(known, msg.optLong("version", known + if (changed) 1 else 0)) }
    }

    private fun applyStroke(cacheUri: String?, file: String, page: Int, stroke: Stroke): Boolean {
        val uri = cacheUri ?: return modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            pg.items.add(stroke)
            true
        }
        host.openDocument(uri)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            pg.items.add(stroke)
            doc.dirty = true
            host.editorState(uri)?.appendToCache(pg, stroke)
            host.repaintDocument(uri)
            return true
        }
        return modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            pg.items.add(stroke)
            true
        }
    }

    private fun applyErase(cacheUri: String?, file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            val targets = pg.items.filterIsInstance<Stroke>().filter { SyncWire.fingerprintOf(it) in ids }
            if (targets.isEmpty()) return false
            val dirty = targets.map { it.paintBounds() }.reduce { a, b -> a.union(b) }.outset(2.0)
            pg.items.removeAll(targets.toSet())
            doc.dirty = true
            val st = host.editorState(uri)
            if (st == null || !st.repairRegion(pg, dirty)) st?.invalidatePage(pg)
            host.repaintDocument(uri)
            return true
        }
        return modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            val targets = pg.items.filterIsInstance<Stroke>().filter { SyncWire.fingerprintOf(it) in ids }
            if (targets.isEmpty()) return@modifyCache false
            pg.items.removeAll(targets.toSet())
            true
        }
    }

    private fun applyMarkupAdd(cacheUri: String?, file: String, page: Int, m: com.xnotes.core.model.TextMarkup): Boolean {
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            // Upsert (same-id recolor/retype converges; see HostEngine).
            pg.markups = pg.markups.filter { it.id != m.id } + m
            doc.dirty = true
            host.editorState(uri)?.rebakeBackground(pg, listOf(m))
            host.repaintDocument(uri)
            return true
        }
        return modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            pg.markups = pg.markups.filter { it.id != m.id } + m
            true
        }
    }

    private fun applyMarkupRemove(cacheUri: String?, file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            if (pg.markups.none { it.id in ids }) return false
            pg.markups = pg.markups.filter { it.id !in ids }
            doc.dirty = true
            host.editorState(uri)?.rebakeBackground(pg, emptyList())
            host.repaintDocument(uri)
            return true
        }
        return modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            if (pg.markups.none { it.id in ids }) return@modifyCache false
            pg.markups = pg.markups.filter { it.id !in ids }
            true
        }
    }

    /**
     * Apply a committed remote page-style change. Invert flags ride page_style
     * too, but only when explicitly set: nulls keep the local view, so the
     * host's flags never leak onto this device.
     */
    private fun applyPageStyle(cacheUri: String?, file: String, op: JSONObject): Boolean {
        val keepLocalInvert = { s: com.xnotes.core.model.PageStyle, local: com.xnotes.core.model.PageStyle ->
            s.copy(
                invertColors = s.invertColors ?: local.invertColors,
                invertImages = s.invertImages ?: local.invertImages,
            )
        }
        val entries = op.optJSONArray("entries")?.let { arr ->
            List(arr.length()) { i ->
                val e = arr.optJSONObject(i) ?: return@let null
                Triple(
                    e.optInt("index", -1),
                    if (e.isNull("page_color") && e.isNull("template") && e.isNull("pattern_color") &&
                        e.isNull("spacing") && e.isNull("accent_color") && e.isNull("params") &&
                        e.isNull("colors") && e.isNull("invert_colors") && e.isNull("invert_images")
                    ) null else SyncWire.pageStyleFrom(e),
                    e.optJSONObject("margins")?.let { SyncWire.pageMarginsFrom(it) },
                )
            }
        } ?: emptyList()
        val docStyle = op.optJSONObject("doc_style")?.let { SyncWire.pageStyleFrom(it) }
        val docMargins = op.optJSONObject("doc_margins")?.let { SyncWire.pageMarginsFrom(it) }
        if (entries.isEmpty() && docStyle == null && docMargins == null) return false
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            var touched = false
            var relayout = false
            if (docStyle != null) {
                val merged = keepLocalInvert(docStyle, doc.style)
                if (doc.style != merged) {
                    doc.style = merged
                    touched = true
                }
            }
            if (docMargins != null && doc.margins != docMargins) {
                doc.margins = docMargins
                touched = true
                relayout = true
            }
            for ((index, style, margins) in entries) {
                val pg = doc.pages.getOrNull(index) ?: continue
                if (style != null) {
                    val merged = keepLocalInvert(style, pg.style)
                    if (pg.style != merged) {
                        pg.style = merged
                        touched = true
                    }
                }
                if (margins != null && pg.margins != margins) {
                    pg.margins = margins
                    touched = true
                    relayout = true
                }
            }
            if (!touched) return false
            doc.dirty = true
            host.editorState(uri)?.let { st ->
                if (relayout) {
                    st.invalidatePageGeometry()
                    st.relayout()
                }
                st.invalidateAllBackgrounds()
                st.invalidatePaper()
            }
            host.repaintDocument(uri)
            host.structureChanged(uri)
            return true
        }
        return modifyCache(file) { doc ->
            var touched = false
            if (docStyle != null) {
                val merged = keepLocalInvert(docStyle, doc.style)
                if (doc.style != merged) {
                    doc.style = merged
                    touched = true
                }
            }
            if (docMargins != null && doc.margins != docMargins) {
                doc.margins = docMargins
                touched = true
            }
            for ((index, style, margins) in entries) {
                val pg = doc.pages.getOrNull(index) ?: continue
                if (style != null) {
                    val merged = keepLocalInvert(style, pg.style)
                    if (pg.style != merged) {
                        pg.style = merged
                        touched = true
                    }
                }
                if (margins != null && pg.margins != margins) {
                    pg.margins = margins
                    touched = true
                }
            }
            touched
        }
    }

    private fun applyPageAdd(cacheUri: String?, file: String, index: Int, width: Double, height: Double): Boolean {
        val mk: (MutableList<Page>) -> Boolean = { pages ->
            val ref = pages.getOrNull(index.coerceIn(0, pages.size)) ?: pages.lastOrNull()
            val at = index.coerceIn(0, pages.size)
            pages.add(at, Page(if (width > 0) width else ref?.width ?: 595.0, if (height > 0) height else ref?.height ?: 842.0))
            true
        }
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            mk(doc.pages)
            doc.dirty = true
            host.editorState(uri)?.let { st ->
                st.relayout()
                st.clampScroll()
            }
            host.structureChanged(uri)
            return true
        }
        return modifyCache(file) { mk(it.pages) }
    }

    private fun applyPageRemove(cacheUri: String?, file: String, index: Int): Boolean {
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            if (doc.pages.size <= 1 || index !in doc.pages.indices) return false
            val page = doc.pages.removeAt(index)
            doc.dirty = true
            host.editorState(uri)?.let { st ->
                st.invalidatePage(page)
                st.relayout()
                st.clampScroll()
            }
            host.structureChanged(uri)
            return true
        }
        return modifyCache(file) { doc ->
            if (doc.pages.size <= 1 || index !in doc.pages.indices) return@modifyCache false
            doc.pages.removeAt(index)
            true
        }
    }

    private fun applyRoomEdit(cacheUri: String?, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean {
        val uri = cacheUri ?: return false
        return host.applyRoomEdit(uri, page, sideways, open, a, b)
    }

    // --- images ---

    private class ImageAsm(val file: String, val total: Long, val buf: ByteArrayOutputStream, var filled: Long = 0)
    private val imageAsm = HashMap<String, ImageAsm>()
    private val pendingImageOps = HashMap<String, ArrayList<Pair<String, JSONObject>>>()
    private val imageBytes = HashMap<String, Pair<String, ByteArray>>()
    private val imageOrder = ArrayDeque<String>()
    private val imageDir: File = File(cacheRoot, "images").apply { mkdirs() }

    private fun onImageChunk(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        val id = msg.optString("image_id")
        if (id.isEmpty()) return
        val total = msg.optLong("total", -1)
        val offset = msg.optLong("offset", -1)
        if (total < 0 || total > HostEngine.IMAGE_MAX_BYTES || offset < 0 || offset > total) {
            imageAsm.remove(id)
            pendingImageOps.remove(id)
            return
        }
        if (total == 0L) {
            if (!msg.optBoolean("last", false)) {
                imageAsm.remove(id)
                pendingImageOps.remove(id)
                return
            }
            rememberImage(id, file, ByteArray(0))
            flushImage(id)
            return
        }
        val asm = imageAsm.getOrPut(id) { ImageAsm(file, total, ByteArrayOutputStream(total.coerceAtMost(HostEngine.IMAGE_MAX_BYTES).toInt())) }
        if (asm.total != total || asm.file != file) {
            imageAsm.remove(id)
            pendingImageOps.remove(id)
            return
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(msg.optString("data"))
        } catch (_: Exception) {
            imageAsm.remove(id)
            pendingImageOps.remove(id)
            return
        }
        if (offset != asm.filled || asm.filled + bytes.size > asm.total) {
            imageAsm.remove(id)
            pendingImageOps.remove(id)
            return
        }
        asm.buf.write(bytes)
        asm.filled += bytes.size
        if (!msg.optBoolean("last", false) && asm.filled < asm.total) return
        if (asm.filled != asm.total) {
            imageAsm.remove(id)
            pendingImageOps.remove(id)
            return
        }
        imageAsm.remove(id)
        rememberImage(id, file, asm.buf.toByteArray())
        flushImage(id)
    }

    private fun rememberImage(id: String, file: String, bytes: ByteArray) {
        imageBytes[id] = file to bytes
        imageOrder.remove(id)
        imageOrder.addLast(id)
        while (imageOrder.size > HostEngine.IMAGE_KEPT) {
            if (imageBytes.size <= 1 || imageOrder.isEmpty()) break
            imageBytes.remove(imageOrder.removeFirst())
        }
    }

    private fun flushImage(id: String) {
        val pend = pendingImageOps.remove(id) ?: return
        val (file, bytes) = imageBytes[id] ?: return
        for ((f, op) in pend) if (f == file) {
            val uri = synchronized(lock) { bound[f] }
            applyImageBytes(uri, f, op, bytes)
        }
    }

    private fun applyRemoteImage(cacheUri: String?, file: String, op: JSONObject): Boolean {
        val id = op.optString("image_id")
        if (id.isEmpty()) return false
        val ready = imageBytes[id]?.takeIf { it.first == file }?.second
        if (ready != null) return applyImageBytes(cacheUri, file, op, ready)
        val pend = pendingImageOps.getOrPut(id) { ArrayList() }
        pend += file to op
        while (pendingImageOps.values.sumOf { it.size } > 16) {
            val first = pendingImageOps.keys.firstOrNull() ?: break
            val list = pendingImageOps[first]!!
            list.removeAt(0)
            if (list.isEmpty()) pendingImageOps.remove(first)
        }
        return false
    }

    private fun applyImageBytes(cacheUri: String?, file: String, op: JSONObject, bytes: ByteArray): Boolean {
        val page = op.optInt("page", 0)
        val rect = op.optJSONArray("rect") ?: return false
        if (rect.length() < 4) return false
        val safeId = op.optString("image_id").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "img" }
        val dest = try {
            imageDir.mkdirs()
            File.createTempFile("img-$safeId-", ".bin", imageDir)
        } catch (_: Exception) {
            return false
        }
        try {
            dest.writeBytes(bytes)
        } catch (_: Exception) {
            dest.delete()
            return false
        }
        val partArr = op.optJSONArray("part")
        val part = if (partArr != null && partArr.length() == 4) {
            com.xnotes.core.geometry.Rect(partArr.optDouble(0), partArr.optDouble(1),
                partArr.optDouble(2) - partArr.optDouble(0), partArr.optDouble(3) - partArr.optDouble(1))
        } else null
        val item = com.xnotes.core.model.ImageItem(
            com.xnotes.core.model.ImageData(dest, op.optInt("width", 0), op.optInt("height", 0)),
            com.xnotes.core.geometry.Rect(rect.optDouble(0), rect.optDouble(1), rect.optDouble(2), rect.optDouble(3)),
            orientation = op.optInt("orientation", 0),
            angle = op.optDouble("angle", 0.0),
            flip = com.xnotes.core.geometry.Flip.of(op.optBoolean("flip_x", false), op.optBoolean("flip_y", false)),
            part = part,
        )
        item.uid = op.optString("uid").ifEmpty { op.optString("image_id") }
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: run {
                dest.delete()
                return false
            }
            if (item.rect.w <= 0 || item.rect.h <= 0) {
                dest.delete()
                return false
            }
            (findByUid(pg, item.uid) as? com.xnotes.core.model.ImageItem)?.let { cur ->
                val i = pg.items.indexOf(cur)
                if (i >= 0) {
                    cur.rect = item.rect
                    cur.orientation = item.orientation
                    cur.angle = item.angle
                    cur.flip = item.flip
                    cur.part = item.part
                    doc.dirty = true
                    host.repaintDocument(uri)
                    return true
                }
            }
            pg.items.add(item)
            doc.dirty = true
            host.editorState(uri)?.appendToCache(pg, item)
            host.repaintDocument(uri)
            return true
        }
        val ok = modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            if (item.rect.w <= 0 || item.rect.h <= 0) return@modifyCache false
            (findByUid(pg, item.uid) as? com.xnotes.core.model.ImageItem)?.let { cur ->
                val i = pg.items.indexOf(cur)
                if (i >= 0) {
                    cur.rect = item.rect
                    cur.orientation = item.orientation
                    cur.angle = item.angle
                    cur.flip = item.flip
                    cur.part = item.part
                    return@modifyCache true
                }
            }
            pg.items.add(item)
            true
        }
        if (!ok) dest.delete()
        return ok
    }

    /** Find a shareable item by stable uid on [pg]. */
    private fun findByUid(pg: com.xnotes.core.model.Page, uid: String): com.xnotes.core.model.CanvasItem? {
        if (uid.isEmpty()) return null
        for (it in pg.items) {
            val u = (it as? com.xnotes.core.model.TextItem)?.uid
                ?: (it as? com.xnotes.core.model.TableItem)?.uid
                ?: (it as? com.xnotes.core.model.ImageItem)?.uid
                ?: (it as? com.xnotes.core.model.AudioItem)?.uid
                ?: continue
            if (u == uid) return it
        }
        return null
    }

    private fun nativeText(t: JSONObject): com.xnotes.core.model.TextItem? {
        val uid = t.optString("uid")
        if (uid.isEmpty()) return null
        return com.xnotes.core.model.TextItem(
            pos = com.xnotes.core.geometry.Pt(t.optDouble("x"), t.optDouble("y")),
            width = t.optDouble("width", com.xnotes.core.model.TextItem.DEFAULT_WIDTH),
            height = t.optDouble("height", 0.0),
            text = t.optString("text"),
            rgba = RemoteLiveStrokes.parseColor(t.optString("color")) ?: com.xnotes.core.model.Rgba(20, 20, 20, 255),
            pointSize = t.optDouble("point_size", com.xnotes.core.model.TextItem.DEFAULT_POINT_SIZE),
            face = com.xnotes.core.pal.FontFace.fromId(t.optString("face")),
            measurer = com.xnotes.platform.AndroidTextMeasurer(),
            bold = t.optBoolean("bold", false), italic = t.optBoolean("italic", false),
            underline = t.optBoolean("underline", false), strike = t.optBoolean("strike", false),
            align = when (t.optString("align")) {
                "center" -> com.xnotes.core.pal.HAlign.CENTER
                "right" -> com.xnotes.core.pal.HAlign.RIGHT
                else -> com.xnotes.core.pal.HAlign.LEFT
            },
            math = t.optBoolean("math", false),
        ).also {
            it.uid = uid
            it.link = t.optString("link").ifEmpty { null }
        }
    }

    private fun nativeTable(t: JSONObject): com.xnotes.core.model.TableItem? {
        val uid = t.optString("uid")
        if (uid.isEmpty()) return null
        fun doubles(a: org.json.JSONArray?): List<Double> {
            if (a == null) return emptyList()
            return List(a.length()) { a.optDouble(it, Double.NaN) }.filter { it.isFinite() }
        }
        val cols = doubles(t.optJSONArray("cols"))
        val rows = doubles(t.optJSONArray("rows"))
        if (cols.isEmpty() || rows.isEmpty()) return null
        val cells = t.optJSONArray("cells")?.let { arr ->
            List(rows.size) { r ->
                val row = arr.optJSONArray(r)
                List(cols.size) { c -> row?.optString(c, "").orEmpty() }
            }
        } ?: emptyList()
        return com.xnotes.core.model.TableItem(
            com.xnotes.core.geometry.Rect(t.optDouble("x"), t.optDouble("y"), t.optDouble("w"), t.optDouble("h")),
            cols, rows,
            com.xnotes.core.model.Rgba(0, 0, 0, 255).let { com.xnotes.canvas.RemoteLiveStrokes.parseColor(t.optString("stroke_color")) ?: it },
            if (t.isNull("stroke_width")) 2.0 else t.optDouble("stroke_width", 2.0),
            cells,
        ).also {
            it.uid = uid
            it.link = t.optString("link").ifEmpty { null }
        }
    }

    private fun applyToPage(cacheUri: String?, file: String, page: Int, fn: (com.xnotes.core.model.Page) -> Boolean): Boolean {
        val uri = cacheUri
        if (uri != null) host.openDocument(uri)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            if (!fn(pg)) return false
            doc.dirty = true
            host.editorState(uri)?.invalidatePage(pg)
            host.repaintDocument(uri)
            return true
        }
        return modifyCache(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyCache false
            fn(pg)
        }
    }

    /** Apply a committed remote text box (upsert by uid). */
    private fun applyTextAdd(cacheUri: String?, file: String, page: Int, t: JSONObject): Boolean {
        val fresh = nativeText(t) ?: return false
        return applyToPage(cacheUri, file, page) { pg ->
            (findByUid(pg, fresh.uid) as? com.xnotes.core.model.TextItem)?.let { cur ->
                val i = pg.items.indexOf(cur)
                if (i >= 0) pg.items[i] = fresh
            } ?: pg.items.add(fresh)
            true
        }
    }

    private fun applyTextRemove(cacheUri: String?, file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        return applyToPage(cacheUri, file, page) { pg ->
            if (pg.items.none { (it as? com.xnotes.core.model.TextItem)?.uid in ids }) return@applyToPage false
            pg.items.removeAll { (it as? com.xnotes.core.model.TextItem)?.uid in ids }
            true
        }
    }

    /** Apply a committed remote table (upsert by uid). */
    private fun applyTableAdd(cacheUri: String?, file: String, page: Int, t: JSONObject): Boolean {
        val fresh = nativeTable(t) ?: return false
        return applyToPage(cacheUri, file, page) { pg ->
            (findByUid(pg, fresh.uid) as? com.xnotes.core.model.TableItem)?.let { cur ->
                val i = pg.items.indexOf(cur)
                if (i >= 0) pg.items[i] = fresh
            } ?: pg.items.add(fresh)
            true
        }
    }

    private fun applyTableRemove(cacheUri: String?, file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        return applyToPage(cacheUri, file, page) { pg ->
            if (pg.items.none { (it as? com.xnotes.core.model.TableItem)?.uid in ids }) return@applyToPage false
            pg.items.removeAll { (it as? com.xnotes.core.model.TableItem)?.uid in ids }
            true
        }
    }

    private fun applyImageRemove(cacheUri: String?, file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        return applyToPage(cacheUri, file, page) { pg ->
            if (pg.items.none { (it as? com.xnotes.core.model.ImageItem)?.uid in ids }) return@applyToPage false
            pg.items.removeAll { (it as? com.xnotes.core.model.ImageItem)?.uid in ids }
            true
        }
    }

    private fun applyAudioRemove(cacheUri: String?, file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        return applyToPage(cacheUri, file, page) { pg ->
            if (pg.items.none { (it as? com.xnotes.core.model.AudioItem)?.uid in ids }) return@applyToPage false
            pg.items.removeAll { (it as? com.xnotes.core.model.AudioItem)?.uid in ids }
            true
        }
    }

    // --- audio (chunked bytes + peaks first, then the placing op) ---

    private class AudioAsm(val file: String, val total: Long, val buf: java.io.ByteArrayOutputStream, var filled: Long = 0)
    private val audioAsm = HashMap<String, AudioAsm>()
    private val audioPeaksAsm = HashMap<String, AudioAsm>()
    private val pendingAudioOps = HashMap<String, ArrayList<Triple<String, String, JSONObject>>>()
    private val audioBytes = HashMap<String, Pair<String, ByteArray>>()
    private val audioPeaks = HashMap<String, Pair<String, ByteArray>>()
    private val audioOrder = ArrayDeque<String>()

    private fun onAudioChunk(msg: JSONObject, peaks: Boolean) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        val id = msg.optString("audio_id")
        if (id.isEmpty()) return
        val total = msg.optLong("total", -1)
        val offset = msg.optLong("offset", -1)
        if (total < 0 || total > HostEngine.AUDIO_MAX_BYTES || offset < 0 || offset > total) {
            dropAudio(id)
            return
        }
        if (total == 0L) {
            if (!msg.optBoolean("last", false)) {
                dropAudio(id)
                return
            }
            if (peaks) {
                audioPeaks[id] = file to ByteArray(0)
                flushAudioPeaks(id)
            } else {
                rememberAudio(id, file, ByteArray(0))
                flushAudio(id)
            }
            return
        }
        val map = if (peaks) audioPeaksAsm else audioAsm
        val asm = map.getOrPut(id) { AudioAsm(file, total, java.io.ByteArrayOutputStream(total.coerceAtMost(HostEngine.AUDIO_MAX_BYTES).toInt())) }
        if (asm.total != total || asm.file != file) {
            dropAudio(id)
            return
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(msg.optString("data"))
        } catch (_: Exception) {
            dropAudio(id)
            return
        }
        if (offset != asm.filled || asm.filled + bytes.size > asm.total) {
            dropAudio(id)
            return
        }
        asm.buf.write(bytes)
        asm.filled += bytes.size
        if (!msg.optBoolean("last", false) && asm.filled < asm.total) return
        if (asm.filled != asm.total) {
            dropAudio(id)
            return
        }
        (if (peaks) audioPeaksAsm else audioAsm).remove(id)
        val data = asm.buf.toByteArray()
        if (peaks) {
            audioPeaks[id] = file to data
            flushAudioPeaks(id)
        } else {
            rememberAudio(id, file, data)
            flushAudio(id)
        }
    }

    private fun dropAudio(id: String) {
        audioAsm.remove(id)
        audioPeaksAsm.remove(id)
        pendingAudioOps.remove(id)
    }

    private fun rememberAudio(id: String, file: String, bytes: ByteArray) {
        audioBytes[id] = file to bytes
        audioOrder.remove(id)
        audioOrder.addLast(id)
        while (audioOrder.size > HostEngine.AUDIO_KEPT) {
            if (audioBytes.size <= 1 || audioOrder.isEmpty()) break
            val oldest = audioOrder.removeFirst()
            audioBytes.remove(oldest)
            audioPeaks.remove(oldest)
        }
    }

    private fun flushAudio(id: String) {
        val pend = pendingAudioOps.remove(id) ?: return
        val (file, bytes) = audioBytes[id] ?: return
        val peaks = audioPeaks[id]?.takeIf { it.first == file }?.second ?: ByteArray(0)
        for ((cacheUri, f, op) in pend) if (f == file) applyAudioBytes(cacheUri, f, op, bytes, peaks)
    }

    private fun flushAudioPeaks(id: String) {
        val pend = pendingAudioOps[id] ?: return
        val (file, bytes) = audioBytes[id] ?: return
        val peaks = audioPeaks[id]?.takeIf { it.first == file }?.second ?: return
        pendingAudioOps.remove(id)
        for ((cacheUri, f, op) in pend) if (f == file) applyAudioBytes(cacheUri, f, op, bytes, peaks)
    }

    private fun applyRemoteAudio(cacheUri: String?, file: String, op: JSONObject): Boolean {
        val audio = op.optJSONObject("audio") ?: return false
        val id = audio.optString("audio_id")
        if (id.isEmpty()) return false
        val ready = audioBytes[id]?.takeIf { it.first == file }
        if (ready != null) {
            val peaks = audioPeaks[id]?.takeIf { it.first == file }?.second ?: ByteArray(0)
            return applyAudioBytes(cacheUri, file, op, ready.second, peaks)
        }
        val pend = pendingAudioOps.getOrPut(id) { ArrayList() }
        pend += Triple(cacheUri ?: "", file, op.optJSONObject("audio")!!)
        while (pendingAudioOps.values.sumOf { it.size } > 16) {
            val first = pendingAudioOps.keys.firstOrNull() ?: break
            val list = pendingAudioOps[first]!!
            list.removeAt(0)
            if (list.isEmpty()) pendingAudioOps.remove(first)
        }
        return false
    }

    private fun applyAudioBytes(cacheUri: String?, file: String, op: JSONObject, bytes: ByteArray, peaks: ByteArray): Boolean {
        val audio = op.optJSONObject("audio") ?: return false
        val page = op.optInt("page", 0)
        val safeId = audio.optString("audio_id").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "aud" }
        val ext = audio.optString("ext").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "m4a"
        val dest = try {
            imageDir.mkdirs()
            File.createTempFile("aud-$safeId-", ".$ext", imageDir)
        } catch (_: Exception) {
            return false
        }
        try {
            dest.writeBytes(bytes)
        } catch (_: Exception) {
            dest.delete()
            return false
        }
        val uid = audio.optString("uid").ifEmpty { audio.optString("audio_id") }
        val rid = audio.optInt("rid", 0).takeIf { it != 0 }
            ?: com.xnotes.core.audio.RecordingIds.next(docItems(file, page))
        val item = com.xnotes.core.model.AudioItem(
            com.xnotes.core.model.AudioData(dest, ext, audio.optInt("duration_ms", 0), peaks),
            com.xnotes.core.geometry.Rect(audio.optDouble("x"), audio.optDouble("y"), audio.optDouble("w"), audio.optDouble("h")),
            audio.optString("name").ifEmpty { "audio" },
            rid,
            com.xnotes.platform.AndroidTextMeasurer(),
        )
        item.uid = uid
        return applyToPage(cacheUri, file, page) { pg ->
            if (item.rect.w <= 0 || item.rect.h <= 0) return@applyToPage false
            (findByUid(pg, item.uid) as? com.xnotes.core.model.AudioItem)?.let { cur ->
                val i = pg.items.indexOf(cur)
                if (i >= 0) pg.items[i] = item
            } ?: pg.items.add(item)
            true
        }.also { ok -> if (!ok) dest.delete() }
    }

    private fun docItems(file: String, page: Int): List<com.xnotes.core.model.CanvasItem> {
        val uri = synchronized(lock) { bound[file] } ?: return emptyList()
        return host.openDocument(uri)?.pages?.getOrNull(page)?.items ?: emptyList()
    }

    // --- live strokes ---

    private fun onLive(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        val uri = synchronized(lock) { bound[file] } ?: return
        val doc = host.openDocument(uri) ?: return
        val overlay = host.liveOverlay(uri) ?: return
        val samples = msg.optJSONArray("samples") ?: JSONArray()
        if (samples.length() == 0 && !msg.optBoolean("done", false)) return
        val color = RemoteLiveStrokes.parseColor(msg.optString("color")) ?: return
        val tool = com.xnotes.core.tools.Tool.fromId(msg.optString("tool")) ?: com.xnotes.core.tools.Tool.PEN
        val cfg = SyncWire.styleFrom(tool, msg.optDouble("width", 3.0), color, true, msg.optJSONObject("style"))
        val pts = ArrayList<com.xnotes.core.geometry.Pt>(samples.length())
        val pressures = ArrayList<Double>(samples.length())
        for (i in 0 until samples.length()) {
            val s = samples.optJSONArray(i) ?: continue
            if (s.length() < 2) continue
            pts += com.xnotes.core.geometry.Pt(s.optDouble(0), s.optDouble(1))
            pressures += if (s.length() > 2) s.optDouble(2, 1.0) else 1.0
        }
        val page = msg.optInt("page", 0)
        if (page !in doc.pages.indices) return
        overlay.updateFull(file, page, msg.optString("stroke_id"), tool, cfg, pts, pressures, msg.optBoolean("done", false))
        host.repaintLive(uri)
    }

    private fun onRoomLive(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty()) return
        val uri = synchronized(lock) { bound[file] } ?: return
        if (msg.optBoolean("done", false)) {
            host.clearRoomPreview(uri)
        } else {
            val page = msg.optInt("page", 0)
            val doc = host.openDocument(uri) ?: return
            if (page !in doc.pages.indices) return
            host.previewRoom(
                uri, page, msg.optBoolean("sideways", false),
                msg.optDouble("split", 0.0), msg.optDouble("resume", 0.0), msg.optDouble("shift", 0.0),
            )
        }
        host.repaintLive(uri)
    }

    // --- local submits (controller hooks, via the service router) ---

    private fun remoteIdOf(cacheUri: String): String? = synchronized(lock) {
        bound.entries.firstOrNull { it.value == cacheUri }?.key
    }

    fun isGuestUri(cacheUri: String): Boolean = synchronized(lock) { bound.containsValue(cacheUri) }

    /**
     * Committed local text markups: replayed as add_markup / remove_markup ops.
     */
    fun submitLocalMarkups(
        cacheUri: String,
        added: List<Pair<Int, com.xnotes.core.model.TextMarkup>>,
        removedIds: List<Pair<Int, String>>,
        dedupe: String,
    ) {
        val file = remoteIdOf(cacheUri) ?: return
        val ops = JSONArray()
        // Removes first (same-id recolor/retype converges; see HostEngine).
        for ((page, ids) in removedIds.groupBy({ it.first }, { it.second })) {
            ops.put(JSONObject().put("op", "remove_markup").put("page", page).put("ids", JSONArray(ids)))
        }
        for ((page, m) in added) {
            ops.put(JSONObject().put("op", "add_markup").put("page", page).put("markup", SyncWire.toWireMarkup(m)))
        }
        if (ops.length() == 0) return
        submitLocalOps(file, ops, dedupe)
    }

    /**
     * Committed local page-style/margin changes: forwarded to the host as one
     * page_style op (a guest never owns the style; the host applies it). The
     * guest's own invert flags never travel: each device keeps its own view.
     */
    fun submitLocalPageStyle(
        cacheUri: String,
        entries: List<Triple<Int, com.xnotes.core.model.PageStyle?, com.xnotes.core.model.PageMargins?>>,
        docStyle: com.xnotes.core.model.PageStyle?,
        docMargins: com.xnotes.core.model.PageMargins?,
        dedupe: String,
    ) {
        val file = remoteIdOf(cacheUri) ?: return
        val arr = JSONArray()
        for ((index, style, margins) in entries) {
            val e = JSONObject().put("index", index)
            if (style != null) {
                val s = SyncWire.toWirePageStyle(style.copy(invertColors = null, invertImages = null))
                val names = s.names() ?: JSONObject().names()
                if (names != null) for (i in 0 until names.length()) {
                    val k = names.getString(i)
                    e.put(k, s.get(k))
                }
            }
            if (margins != null) e.put("margins", SyncWire.toWireMargins(margins))
            arr.put(e)
        }
        val op = JSONObject().put("op", "page_style").put("entries", arr)
        if (docStyle != null) {
            op.put("doc_style", SyncWire.toWirePageStyle(docStyle.copy(invertColors = null, invertImages = null)))
        }
        if (docMargins != null) op.put("doc_margins", SyncWire.toWireMargins(docMargins))
        submitLocalOps(file, JSONArray().put(op), dedupe)
    }

    fun submitLocalStroke(cacheUri: String, page: Int, stroke: Stroke, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        val ops = JSONArray().put(
            JSONObject().put("op", "add_stroke").put("page", page).put("stroke", SyncWire.toWire(stroke)),
        )
        submitLocalOps(file, ops, dedupe)
    }

    fun submitLocalPageAdd(cacheUri: String, index: Int, width: Double, height: Double, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        submitLocalOps(file, JSONArray().put(
            JSONObject().put("op", "add_page").put("index", index).put("width", width).put("height", height),
        ), dedupe)
    }

    fun submitLocalPageRemove(cacheUri: String, index: Int, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "remove_page").put("index", index)), dedupe)
    }

    fun submitLocalErase(cacheUri: String, page: Int, ids: List<String>, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (ids.isEmpty()) return
        submitLocalOps(file, JSONArray().put(
            JSONObject().put("op", "erase_strokes").put("page", page).put("ids", JSONArray(ids)),
        ), dedupe)
    }

    /**
     * Committed stroke replacements (move/rotate/resize/restyle/delete-area):
     * replayed as one message of erase_strokes + add_stroke ops, so the host
     * converges with no new wire type.
     */
    fun submitLocalReplacements(cacheUri: String, changes: List<com.xnotes.canvas.StrokeReplacement>, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        val ops = JSONArray()
        for (c in changes) {
            if (c.removed.isNotEmpty()) {
                ops.put(
                    JSONObject().put("op", "erase_strokes").put("page", c.erasePage)
                        .put("ids", JSONArray(c.removed.map { SyncWire.fingerprintOf(it) })),
                )
            }
            for (s in c.added) {
                ops.put(JSONObject().put("op", "add_stroke").put("page", c.addPage).put("stroke", SyncWire.toWire(s)))
            }
        }
        if (ops.length() == 0) return
        submitLocalOps(file, ops, dedupe)
    }

    fun submitLocalRoomOpen(cacheUri: String, page: Int, sideways: Boolean, at: Double, size: Double, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        submitLocalOps(file, JSONArray().put(
            JSONObject().put("op", "open_room").put("page", page).put("at", at).put("size", size)
                .let { if (sideways) it.put("sideways", true) else it },
        ), dedupe)
    }

    fun submitLocalRoomClose(cacheUri: String, page: Int, sideways: Boolean, a: Double, b: Double, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        submitLocalOps(file, JSONArray().put(
            JSONObject().put("op", "close_room").put("page", page).put("a", a).put("b", b)
                .let { if (sideways) it.put("sideways", true) else it },
        ), dedupe)
    }

    private fun submitLocalOps(file: String, ops: JSONArray, dedupe: String) {

        val base = synchronized(lock) { versions[file] ?: 1L }
        synchronized(lock) {
            val q = sentDedupes.getOrPut(file) { ArrayDeque() }
            q.addLast(dedupe)
            while (q.size > SyncVersions.DEDUPE_KEPT) q.removeFirst()
        }
        send(
            JSONObject().put("t", "op").put("file", file)
                .put("base_version", base).put("ops", ops).put("dedupe", dedupe),
        )
    }
    /** Content ops from the service history hook (uid-keyed text/table/image/audio). */
    fun submitContentOps(id: String, ops: JSONArray, dedupe: String) = submitLocalOps(id, ops, dedupe)


    /** A local image insert: chunked bytes first, then the placing op. */
    fun submitLocalImage(
        cacheUri: String, page: Int, imageId: String, bytes: ByteArray,
        width: Int, height: Int, rect: com.xnotes.core.geometry.Rect,
        orientation: Int, angle: Double, flipX: Boolean, flipY: Boolean, part: com.xnotes.core.geometry.Rect?,
        dedupe: String,
        uid: String = "",
    ) {
        val file = remoteIdOf(cacheUri) ?: return
        sendImageBytes(file, imageId, bytes)
        val op = JSONObject().put("op", "add_image").put("page", page).put("image_id", imageId)
            .put("width", width).put("height", height)
            .put("rect", JSONArray().put(rect.x).put(rect.y).put(rect.w).put(rect.h))
            .put("orientation", orientation).put("angle", angle)
        if (flipX) op.put("flip_x", true)
        if (flipY) op.put("flip_y", true)
        part?.let { op.put("part", JSONArray().put(it.x).put(it.y).put(it.right).put(it.bottom)) }
        if (uid.isNotEmpty()) op.put("uid", uid)
        submitLocalOps(file, JSONArray().put(op), dedupe)
    }

    /** A local text-box commit to broadcast (full snapshot, upserted by uid). */
    fun submitLocalText(cacheUri: String, page: Int, item: com.xnotes.core.model.TextItem, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (item.uid.isEmpty()) item.uid = java.util.UUID.randomUUID().toString()
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "add_text").put("page", page).put("text", SyncWire.toWireText(item))), dedupe)
    }

    fun submitLocalTextRemove(cacheUri: String, page: Int, uids: List<String>, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (uids.isEmpty()) return
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "remove_text").put("page", page).put("ids", JSONArray(uids))), dedupe)
    }

    fun submitLocalTable(cacheUri: String, page: Int, item: com.xnotes.core.model.TableItem, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (item.uid.isEmpty()) item.uid = java.util.UUID.randomUUID().toString()
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "add_table").put("page", page).put("table", SyncWire.toWireTable(item))), dedupe)
    }

    fun submitLocalTableRemove(cacheUri: String, page: Int, uids: List<String>, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (uids.isEmpty()) return
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "remove_table").put("page", page).put("ids", JSONArray(uids))), dedupe)
    }

    fun submitLocalImageMove(cacheUri: String, page: Int, imageId: String, item: com.xnotes.core.model.ImageItem, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        val op = JSONObject().put("op", "add_image").put("page", page).put("image_id", imageId)
            .put("width", item.image.width).put("height", item.image.height)
            .put("rect", JSONArray().put(item.rect.x).put(item.rect.y).put(item.rect.w).put(item.rect.h))
            .put("orientation", item.orientation).put("angle", item.angle)
        if (item.flip.x) op.put("flip_x", true)
        if (item.flip.y) op.put("flip_y", true)
        item.part?.let { op.put("part", JSONArray().put(it.x).put(it.y).put(it.right).put(it.bottom)) }
        if (item.uid.isNotEmpty()) op.put("uid", item.uid)
        submitLocalOps(file, JSONArray().put(op), dedupe)
    }

    fun submitLocalImageRemove(cacheUri: String, page: Int, ids: List<String>, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (ids.isEmpty()) return
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "remove_image").put("page", page).put("ids", JSONArray(ids))), dedupe)
    }

    fun submitLocalAudio(cacheUri: String, page: Int, audioId: String, item: com.xnotes.core.model.AudioItem, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (item.uid.isEmpty()) item.uid = java.util.UUID.randomUUID().toString()
        val bytes = runCatching { item.audio.file.readBytes() }.getOrNull() ?: return
        sendAudioBytes(file, audioId, bytes)
        sendAudioPeaks(file, audioId, item.audio.peaks)
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "add_audio").put("page", page).put("audio", SyncWire.toWireAudio(audioId, item, page))), dedupe)
    }

    fun submitLocalAudioRemove(cacheUri: String, page: Int, ids: List<String>, dedupe: String) {
        val file = remoteIdOf(cacheUri) ?: return
        if (ids.isEmpty()) return
        submitLocalOps(file, JSONArray().put(JSONObject().put("op", "remove_audio").put("page", page).put("ids", JSONArray(ids))), dedupe)
    }

    fun sendAudioBytes(file: String, audioId: String, bytes: ByteArray) {
        sendChunked(file, audioId, bytes, "audio_data", "audio_id")
    }

    fun sendAudioPeaks(file: String, audioId: String, peaks: ByteArray) {
        sendChunked(file, audioId, peaks, "audio_peaks", "audio_id")
    }

    private fun sendChunked(file: String, id: String, bytes: ByteArray, type: String, idKey: String) {
        if (bytes.isEmpty()) {
            send(JSONObject().put("t", type).put("file", file).put(idKey, id)
                .put("total", 0).put("offset", 0).put("data", "").put("last", true))
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + HostEngine.IMAGE_CHUNK, bytes.size)
            send(JSONObject().put("t", type).put("file", file).put(idKey, id)
                .put("total", bytes.size).put("offset", offset)
                .put("data", java.util.Base64.getEncoder().encodeToString(bytes.sliceArray(offset until end)))
                .put("last", end >= bytes.size))
            offset = end
        }
    }

    /** Chunked image bytes ahead of their op (256 KiB frames, base64 like file chunks). */
    /** Chunked image bytes ahead of their op (256 KiB frames, base64 like file chunks). */
    fun sendImageBytes(file: String, imageId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            send(JSONObject().put("t", "image_data").put("file", file).put("image_id", imageId)
                .put("total", 0).put("offset", 0).put("data", "").put("last", true))
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + HostEngine.IMAGE_CHUNK, bytes.size)
            send(JSONObject().put("t", "image_data").put("file", file).put("image_id", imageId)
                .put("total", bytes.size).put("offset", offset)
                .put("data", java.util.Base64.getEncoder().encodeToString(bytes.sliceArray(offset until end)))
                .put("last", end >= bytes.size))
            offset = end
        }
    }

    fun liveSample(cacheUri: String, page: Int, strokeId: String, tool: String, width: Double, color: String, x: Double, y: Double, p: Double, style: JSONObject? = null) {
        val file = remoteIdOf(cacheUri) ?: return
        val samples = JSONArray().put(JSONArray().put(x).put(y).put(p))
        val msg = JSONObject().put("t", "live").put("file", file).put("page", page)
            .put("stroke_id", strokeId).put("tool", tool).put("width", width).put("color", color)
            .put("samples", samples).put("done", false)
        if (style != null) msg.put("style", style)
        send(msg)
    }

    fun finishStroke(cacheUri: String, page: Int, strokeId: String, tool: String, width: Double, color: String, style: JSONObject? = null) {
        val file = remoteIdOf(cacheUri) ?: return
        val msg = JSONObject().put("t", "live").put("file", file).put("page", page)
            .put("stroke_id", strokeId).put("tool", tool).put("width", width).put("color", color)
            .put("samples", JSONArray()).put("done", true)
        if (style != null) msg.put("style", style)
        send(msg)
    }

    fun roomPreview(cacheUri: String, page: Int, liveId: String, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        val file = remoteIdOf(cacheUri) ?: return
        val msg = JSONObject().put("t", "room_live").put("file", file).put("page", page)
            .put("split", split).put("resume", resume).put("shift", shift)
            .put("live_id", liveId).put("done", false)
        if (sideways) msg.put("sideways", true)
        send(msg)
    }

    fun finishRoom(cacheUri: String, page: Int, liveId: String, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        val file = remoteIdOf(cacheUri) ?: return
        val msg = JSONObject().put("t", "room_live").put("file", file).put("page", page)
            .put("split", split).put("resume", resume).put("shift", shift)
            .put("live_id", liveId).put("done", true)
        if (sideways) msg.put("sideways", true)
        send(msg)
    }

    fun presence(cacheUri: String, page: Int) {
        val file = remoteIdOf(cacheUri) ?: return
        send(JSONObject().put("t", "presence").put("file", file).put("page", page))
    }

    fun requestDelete(cacheUri: String) {
        val file = remoteIdOf(cacheUri) ?: return
        send(JSONObject().put("t", "delete_request").put("id", file).put("device", peerName))
    }

    fun requestCreate(cacheUri: String?, name: String, kind: Int) {
        send(JSONObject().put("t", "create_request").put("dir_id", cacheUri).put("name", name).put("kind", kind).put("device", peerName))
    }

    // --- cache files ---

    private val codec by lazy {
        DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
    }

    private fun cacheKey(remoteId: String): String =
        MessageDigest.getInstance("SHA-256").digest(remoteId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun cacheFile(remoteId: String): File = File(dir, "${cacheKey(remoteId)}.xnote")

    private fun cacheUri(cache: File): String = "file://${cache.absolutePath}"

    private fun bind(remoteId: String, cache: File) {
        synchronized(lock) { bound[remoteId] = cacheUri(cache) }
    }

    /** Raw cached bytes for [remoteId] (copy-out wiring), or null. */
    fun cachedBytes(remoteId: String): ByteArray? {
        val f = cacheFile(remoteId)
        return if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
    }

    /** Cache URI currently bound to [remoteId], if any. */
    fun boundUri(remoteId: String): String? = synchronized(lock) { bound[remoteId] }

    /**
     * Display name for a guest cache URI: "External - {inherited name}".
     * The inherited name is the host's shared path basename, so a guest tab
     * never shows an empty or hash title.
     */
    fun displayNameForCache(cacheUri: String): String? = synchronized(lock) {
        val remoteId = bound.entries.firstOrNull { it.value == cacheUri }?.key ?: return null
        val path = index[remoteId]?.path?.takeIf { it.isNotEmpty() } ?: remoteId
        val base = path.substringAfterLast('/').ifEmpty { "note" }
        "External - $base"
    }

    private data class Meta(val version: Long, val path: String)

    private fun metaFile(): File = File(dir, "meta.json")

    private fun cacheMeta(remoteId: String): Meta? = try {
        val o = JSONObject(metaFile().readText()).optJSONObject(cacheKey(remoteId)) ?: return null
        Meta(o.optLong("version", 0L), o.optString("path"))
    } catch (_: Exception) {
        null
    }

    private fun saveMeta(remoteId: String, version: Long, size: Long) {
        try {
            val root = try {
                JSONObject(metaFile().readText())
            } catch (_: Exception) {
                JSONObject()
            }
            root.put(cacheKey(remoteId), JSONObject().put("version", version).put("path", indexPath(remoteId)).put("size", size))
            val tmp = File(dir, "meta.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(metaFile())) {
                metaFile().delete()
                tmp.renameTo(metaFile())
            }
        } catch (_: Exception) {
        }
    }

    private fun indexPath(remoteId: String): String = synchronized(lock) { index[remoteId]?.path ?: remoteId }

    /** Read-modify-write a cached file through the codec. */
    /** Fetched bytes never keep the host's invert flags: each device keeps its own view. */
    private fun stripInvert(bytes: ByteArray): ByteArray {
        return try {
            val doc = codec.read(bytes.inputStream(), null, null)
            var touched = false
            if (doc.style.invertColors != null || doc.style.invertImages != null) {
                doc.style = doc.style.copy(invertColors = null, invertImages = null)
                touched = true
            }
            for (pg in doc.pages) {
                if (pg.style.invertColors != null || pg.style.invertImages != null) {
                    pg.style = pg.style.copy(invertColors = null, invertImages = null)
                    touched = true
                }
            }
            if (!touched) return bytes
            val out = ByteArrayOutputStream()
            codec.write(doc, out)
            out.toByteArray()
        } catch (_: Exception) {
            bytes
        }
    }

    private fun modifyCache(file: String, edit: (Document) -> Boolean): Boolean {
        val cache = cacheFile(file)
        if (!cache.isFile) return false
        return try {
            val doc = cache.inputStream().use { codec.read(it, null, null) }
            if (!edit(doc)) return false
            val out = ByteArrayOutputStream()
            codec.write(doc, out)
            cache.writeBytes(out.toByteArray())
            true
        } catch (_: Exception) {
            false
        }
    }
}
