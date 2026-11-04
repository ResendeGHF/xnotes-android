package com.xnotes.share.companion

import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.format.DocumentCodec
import com.xnotes.share.ShareBackendImpl
import com.xnotes.share.SharePolicy
import com.xnotes.share.ShareServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The host side of SYNC.md v1: owns files, policy and commit authority.
 *
 * Serves the shared index, answers fetches, applies companion ops to open
 * documents (or straight to files), broadcasts its own commits, streams live
 * samples both ways, and asks the UI before creating or deleting files.
 *
 * Commits are host-authoritative: every applied batch bumps the per-file
 * version, persisted in `versions.json` (tmp+rename). Companion submissions
 * carry a base version; a stale one gets `resync`.
 *
 * Threading: [onMessage] and the controller hooks run on the main thread;
 * file IO runs on [io].
 */
/** File bytes behind sync ids (SAF in production, a map in tests). */
interface SyncFiles {
    fun read(id: String): ByteArray?
    fun write(id: String, bytes: ByteArray): Boolean
    fun delete(id: String): Boolean
    fun create(dirId: String?, name: String, isDir: Boolean): String?
}

class HostEngine(
    private val backend: ShareServer.ShareBackend,
    private val files: SyncFiles,
    private val policy: SharePolicy,
    private val versionsFile: File,
    private val host: HostEngineHost,
    val deviceName: String,
    /** Where received image bytes land; defaults to the process temp dir. */
    private val imageDir: File = File(System.getProperty("java.io.tmpdir"), "xnotes-sync-images").apply { mkdirs() },
) {
    interface HostEngineHost {
        fun send(msg: JSONObject)
        /** The live document for [uri], or null when it is not open. */
        fun openDocument(uri: String): Document?
        /** Serialize [doc] (a live open document) for a fetch. */
        fun snapshotBytes(doc: Document): ByteArray?
        /** Repaint the open document for [uri] (remote apply or live). */
        fun repaintDocument(uri: String)
        /** Repaint just the live overlay's damage for [uri] (cheap partial frame). */
        fun repaintLive(uri: String)
        /** The open document's canvas state for [uri], or null. */
        fun editorState(uri: String): com.xnotes.canvas.CanvasState?
        /** A remote structural apply changed [uri]'s pages: refresh chrome, not just pixels. */
        fun structureChanged(uri: String)
        /** Show the far side's room drag on [uri]'s page without touching the model. */
        fun previewRoom(uri: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double)
        /** Drop the far side's room preview for [uri]. */
        fun clearRoomPreview(uri: String)
        /** Apply the far side's committed room edit without touching undo; true when it changed. */
        fun applyRoomEdit(uri: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean
        /** The open document's live overlay for [uri], or null. */
        fun liveOverlay(uri: String): com.xnotes.canvas.RemoteLiveStrokes?
        fun onApproval(kind: String, device: String, name: String, respond: (Boolean) -> Unit)
        fun onError(reason: String)
    }

    private val lock = Any()
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "xnotes-sync-io").apply { isDaemon = true } }
    private var seq = 0
    private val versions = HashMap<String, Long>()
    private val seenDedupes = HashMap<String, ArrayDeque<String>>()
    private val companionPresence = HashMap<String, Int>()
    private val coveredIds = HashSet<String>()
    private val idPaths = HashMap<String, String>()

    init {
        loadVersions()
    }

    // --- transport ---

    private fun send(msg: JSONObject) {
        msg.put("seq", ++seq)
        host.send(msg)
    }

    fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "index_subscribe" -> sendIndex(full = true)
            "fetch" -> onFetch(msg)
            "op" -> onOp(msg)
            "live" -> onLive(msg)
            "room_live" -> onRoomLive(msg)
            "image_data" -> onImageChunk(msg)
            "presence" -> synchronized(lock) {
                val file = msg.optString("file")
                if (file.isNotEmpty()) companionPresence[file] = msg.optInt("page", -1)
            }
            "heartbeat" -> Unit
            "delete_request" -> onDeleteRequest(msg)
            "create_request" -> onCreateRequest(msg)
            else -> Unit // hello_ack/pair_result belong to PairingClient; unknowns ignored
        }
    }

    fun companionPresence(): Map<String, Int> = synchronized(lock) { companionPresence.toMap() }

    // --- index ---

    /**
     * The shared file tree: shared files and folders, plus everything under a
     * shared folder. Ids are the stable document URIs (rename/move-safe);
     * `path` is the current display path and changes with moves.
     */
    fun buildIndex(): List<IndexEntry> {
        if (!backend.browseRootSet()) return emptyList()
        val out = ArrayList<IndexEntry>()
        val queue = ArrayDeque<Triple<String?, String, Boolean>>()
        queue += Triple(null, "", false)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val (dir, prefix, inherited) = queue.removeFirst()
            for (e in backend.listEntries(dir, onlyShared = false)) {
                if (!seen.add(e.uri)) continue
                val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                val covered = inherited || policy.isShared(e.uri)
                if (e.isDir) {
                    // Shared folders share their whole subtree; unshared dirs with
                    // shared files beneath still list, for navigation.
                    if (covered || subtreeHasShared(e.uri, 4)) {
                        out += IndexEntry(e.uri, path, 1, 0L, 0L)
                        queue += Triple(e.uri, path, covered)
                    }
                } else if (covered) {
                    out += IndexEntry(e.uri, path, 0, headVersion(e.uri), e.size)
                }
            }
        }
        return out.sortedWith(compareBy({ it.kind != 1 }, { it.path }))
    }

    /** Whether [uri]'s subtree (up to [depth]) holds a shared file. */
    private fun subtreeHasShared(uri: String, depth: Int): Boolean {
        if (depth <= 0) return false
        return backend.listEntries(uri, onlyShared = false).any {
            if (it.isDir) subtreeHasShared(it.uri, depth - 1) else policy.isShared(it.uri)
        }
    }

    /**
     * Coalesced index work: rapid refreshes collapse into one build loop, and a
     * refresh landing mid-build schedules one more pass so nothing goes stale.
     */
    private val indexDirty = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sendIndex(full: Boolean) {
        // Seed coverage from the policy at once so ops landing before the walk
        // finishes are still gated correctly; the walk refines it right after.
        synchronized(lock) {
            coveredIds.clear()
            coveredIds.addAll(policy.sharedUris())
        }
        // The tree walk + JSON encode runs on the single sync IO thread: doing it
        // on the caller (often the main thread) froze the policy screen per tap.
        if (!indexDirty.compareAndSet(false, true)) return
        io.execute {
            while (indexDirty.compareAndSet(true, false)) {
                val files = JSONArray()
                val ids = HashSet<String>()
                for (f in buildIndex()) {
                    ids += f.id
                    synchronized(lock) { idPaths[f.id] = f.path }
                    files.put(
                        JSONObject().put("id", f.id).put("path", f.path).put("kind", f.kind)
                            .put("version", f.version).put("size", f.size),
                    )
                }
                synchronized(lock) {
                    coveredIds.clear()
                    coveredIds.addAll(ids)
                }
                send(JSONObject().put("t", "index").put("full", full).put("files", files))
            }
        }
    }

    fun pushIndexDelta() = sendIndex(full = true)

    /** Rebuild coverage after the policy screen changes (then push a delta). */
    fun refreshPolicy() {
        synchronized(lock) { coveredIds.clear() }
        pushIndexDelta()
    }

    fun requestOpen(id: String) {
        if (!policyAllows(id)) return
        send(JSONObject().put("t", "fetch").put("id", id).put("offset", 0).put("length", 0))
    }

    fun closeFile(id: String) {
        send(JSONObject().put("t", "presence").put("file", id).put("page", -1))
    }

    fun presence(fileId: String, page: Int) {
        if (!policyAllows(fileId)) return
        send(JSONObject().put("t", "presence").put("file", fileId).put("page", page))
    }

    fun requestDelete(id: String) {
        if (!policyAllows(id)) return
        send(JSONObject().put("t", "delete_request").put("id", id).put("device", deviceName))
    }

    fun requestCreate(dirId: String?, name: String, kind: Int) {
        if (dirId != null && !policyAllows(dirId)) return
        send(JSONObject().put("t", "create_request").put("dir_id", dirId).put("name", name).put("kind", kind).put("device", deviceName))
    }

    data class IndexEntry(val id: String, val path: String, val kind: Int, val version: Long, val size: Long)

    // --- fetch ---

    private fun onFetch(msg: JSONObject) {
        val id = msg.optString("id")
        if (id.isEmpty()) return
        if (!policyAllows(id)) return
        io.execute {
            val bytes = readFileBytes(id) ?: return@execute
            val version = headVersion(id)
            val total = bytes.size.toLong()
            var offset = msg.optLong("offset", 0L).coerceIn(0L, total)
            while (offset < total) {
                val end = minOf(offset + CHUNK, total)
                val last = end >= total
                send(
                    JSONObject().put("t", "file").put("id", id).put("version", version)
                        .put("total", total).put("offset", offset)
                        .put("data", java.util.Base64.getEncoder().encodeToString(bytes.sliceArray(offset.toInt() until end.toInt())))
                        .put("last", last),
                )
                offset = end
            }
        }
    }

    /** Committed bytes, or the live document when it is open and dirty. */
    private fun readFileBytes(id: String): ByteArray? {
        host.openDocument(id)?.let { doc ->
            if (doc.dirty) {
                host.snapshotBytes(doc)?.let { return it }
            }
        }
        return files.read(id)
    }

    // --- committed ops ---

    private fun onOp(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty() || !policyAllows(file)) return
        val dedupe = msg.optString("dedupe")
        if (!markDedupe(file, dedupe)) return
        val base = msg.optLong("base_version", -1)
        val head = headVersion(file)
        if (base != head) {
            send(JSONObject().put("t", "resync").put("file", file).put("version", head))
            return
        }
        val ops = msg.optJSONArray("ops") ?: JSONArray()
        var changed = false
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op")) {
                "add_stroke" -> {
                    val stroke = SyncWire.toNative(op.optJSONObject("stroke") ?: continue) ?: continue
                    if (applyStroke(file, op.optInt("page", 0), stroke)) changed = true
                }
                "erase_strokes" -> {
                    val ids = op.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: continue
                    if (applyErase(file, op.optInt("page", 0), ids)) changed = true
                }
                "add_page" -> {
                    if (applyPageAdd(file, op.optInt("index", 0), op.optDouble("width", 0.0), op.optDouble("height", 0.0))) changed = true
                }
                "remove_page" -> {
                    if (applyPageRemove(file, op.optInt("index", -1))) changed = true
                }
                "open_room" -> {
                    if (applyRoomEdit(file, op.optInt("page", 0), op.optBoolean("sideways", false), true, op.optDouble("at", 0.0), op.optDouble("at", 0.0) + op.optDouble("size", 0.0))) changed = true
                }
                "close_room" -> {
                    if (applyRoomEdit(file, op.optInt("page", 0), op.optBoolean("sideways", false), false, op.optDouble("a", 0.0), op.optDouble("b", 0.0))) changed = true
                }
                "add_image" -> {
                    if (applyRemoteImage(file, op)) changed = true
                }
                else -> Unit
            }
        }
        // The commit supersedes its live run even when the done frame was lost.
        if (dedupe.isNotEmpty()) host.liveOverlay(file)?.finish(dedupe)
        if ((0 until ops.length()).any { ops.optJSONObject(it)?.optString("op") == "open_room" || ops.optJSONObject(it)?.optString("op") == "close_room" }) {
            host.clearRoomPreview(file)
        }
        val version = if (!changed) head else bumpVersion(file)
        if (changed) {
            // Same dedupe end to end: the sender recognizes its own echo and
            // drops it instead of applying the strokes twice.
            broadcastOp(file, version, ops, dedupe)
        }
        send(JSONObject().put("t", "op_ack").put("file", file).put("version", version))
    }

    /** A local commit to broadcast (called from the controller hooks). */
    fun submitLocalStroke(uri: String, page: Int, stroke: com.xnotes.core.model.Stroke, dedupe: String) {
        val ops = JSONArray().put(
            JSONObject().put("op", "add_stroke").put("page", page).put("stroke", SyncWire.toWire(stroke)),
        )
        submitLocalOps(uri, ops, dedupe)
    }

    /** A local page insert to broadcast (blank page shell; sizes in page px). */
    fun submitLocalPageAdd(uri: String, index: Int, width: Double, height: Double, dedupe: String) {
        val ops = JSONArray().put(
            JSONObject().put("op", "add_page").put("index", index).put("width", width).put("height", height),
        )
        submitLocalOps(uri, ops, dedupe)
    }

    /** A local page removal to broadcast. */
    fun submitLocalPageRemove(uri: String, index: Int, dedupe: String) {
        val ops = JSONArray().put(JSONObject().put("op", "remove_page").put("index", index))
        submitLocalOps(uri, ops, dedupe)
    }

    /** A local image insert to broadcast: chunked bytes first, then the placing op. */
    fun submitLocalImage(
        uri: String, page: Int, imageId: String, bytes: ByteArray,
        width: Int, height: Int, rect: com.xnotes.core.geometry.Rect,
        orientation: Int, angle: Double, flipX: Boolean, flipY: Boolean, part: com.xnotes.core.geometry.Rect?,
        dedupe: String,
    ) {
        sendImageBytes(uri, imageId, bytes)
        val op = JSONObject().put("op", "add_image").put("page", page).put("image_id", imageId)
            .put("width", width).put("height", height)
            .put("rect", JSONArray().put(rect.x).put(rect.y).put(rect.w).put(rect.h))
            .put("orientation", orientation).put("angle", angle)
        if (flipX) op.put("flip_x", true)
        if (flipY) op.put("flip_y", true)
        part?.let { op.put("part", JSONArray().put(it.x).put(it.y).put(it.right).put(it.bottom)) }
        submitLocalOps(uri, JSONArray().put(op), dedupe)
    }

    /** Chunked image bytes ahead of their op (256 KiB frames, base64 like file chunks). */
    fun sendImageBytes(uri: String, imageId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            send(JSONObject().put("t", "image_data").put("file", uri).put("image_id", imageId)
                .put("total", 0).put("offset", 0).put("data", "").put("last", true))
            return
        }
        var off = 0
        while (off < bytes.size) {
            val n = minOf(IMAGE_CHUNK, bytes.size - off)
            val slice = bytes.copyOfRange(off, off + n)
            send(JSONObject().put("t", "image_data").put("file", uri).put("image_id", imageId)
                .put("total", bytes.size).put("offset", off)
                .put("data", java.util.Base64.getEncoder().encodeToString(slice))
                .put("last", off + n >= bytes.size))
            off += n
        }
    }

    /**
     * Push the whole file after a change ops cannot carry (PDF joined, cover
     * set): the far side replaces its bytes and reloads. Bumps the head so the
     * bytes and the version stay one story.
     */
    fun pushFile(uri: String) {
        if (!policyAllows(uri)) return
        val doc = host.openDocument(uri)
        val bytes = if (doc != null) host.snapshotBytes(doc) else files.read(uri)
        bytes ?: return
        val version = bumpVersion(uri)
        pushIndexDelta()
        if (bytes.isEmpty()) {
            send(JSONObject().put("t", "file").put("id", uri).put("version", version)
                .put("total", 0).put("offset", 0).put("data", "").put("last", true))
            return
        }
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + CHUNK.toInt(), bytes.size)
            send(JSONObject().put("t", "file").put("id", uri).put("version", version)
                .put("total", bytes.size).put("offset", off)
                .put("data", java.util.Base64.getEncoder().encodeToString(bytes.sliceArray(off until end)))
                .put("last", end >= bytes.size))
            off = end
        }
    }

    /** A local room open to broadcast ([at] cut, [size] tall). */
    fun submitLocalRoomOpen(uri: String, page: Int, sideways: Boolean, at: Double, size: Double, dedupe: String) {
        val op = JSONObject().put("op", "open_room").put("page", page).put("at", at).put("size", size)
        if (sideways) op.put("sideways", true)
        submitLocalOps(uri, JSONArray().put(op), dedupe)
    }

    /** A local room close to broadcast (strip [a] to [b]). */
    fun submitLocalRoomClose(uri: String, page: Int, sideways: Boolean, a: Double, b: Double, dedupe: String) {
        val op = JSONObject().put("op", "close_room").put("page", page).put("a", a).put("b", b)
        if (sideways) op.put("sideways", true)
        submitLocalOps(uri, JSONArray().put(op), dedupe)
    }

    /** A local erase to broadcast. */
    fun submitLocalErase(uri: String, page: Int, ids: List<String>, dedupe: String) {
        val idArr = JSONArray()
        for (id in ids) idArr.put(id)
        val ops = JSONArray().put(JSONObject().put("op", "erase_strokes").put("page", page).put("ids", idArr))
        submitLocalOps(uri, ops, dedupe)
    }

    private fun submitLocalOps(uri: String, ops: JSONArray, dedupe: String) {
        if (!markDedupe(uri, dedupe)) return
        val version = bumpVersion(uri)
        broadcastOp(uri, version, ops, dedupe)
    }

    private fun broadcastOp(file: String, version: Long, ops: JSONArray, dedupe: String) {
        send(
            JSONObject().put("t", "op").put("file", file)
                .put("base_version", version - 1).put("version", version)
                .put("ops", ops).put("dedupe", dedupe),
        )
    }

    /** Apply a committed remote stroke; straight to the file when not open. */
    private fun applyStroke(file: String, page: Int, stroke: Stroke): Boolean {
        host.openDocument(file)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            pg.items.add(stroke)
            doc.dirty = true
            // Bake into the warm cache in place (highlighters composite live, so
            // appendToCache skips them by design); never a full-page rebake.
            host.editorState(file)?.appendToCache(pg, stroke)
            host.repaintDocument(file)
            return true
        }
        return modifyFile(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyFile false
            pg.items.add(stroke)
            true
        }
    }

    /** Apply a committed remote erase by content fingerprint; straight to the file when not open. */
    private fun applyErase(file: String, page: Int, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        host.openDocument(file)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: return false
            val targets = pg.items.filterIsInstance<Stroke>().filter { SyncWire.fingerprintOf(it) in ids }
            if (targets.isEmpty()) return false
            val dirty = targets.map { it.paintBounds() }.reduce { a, b -> a.union(b) }.outset(2.0)
            pg.items.removeAll(targets.toSet())
            doc.dirty = true
            // Patch just the erased area in the warm cache, like the local eraser.
            val st = host.editorState(file)
            if (st == null || !st.repairRegion(pg, dirty)) st?.invalidatePage(pg)
            host.repaintDocument(file)
            return true
        }
        return modifyFile(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyFile false
            val targets = pg.items.filterIsInstance<Stroke>().filter { SyncWire.fingerprintOf(it) in ids }
            if (targets.isEmpty()) return@modifyFile false
            pg.items.removeAll(targets.toSet())
            true
        }
    }

    /** Insert a blank remote page; bypasses undo like all remote ops. */
    private fun applyPageAdd(file: String, index: Int, width: Double, height: Double): Boolean {
        val doc = host.openDocument(file) ?: return modifyFile(file) { d ->
            val at = index.coerceIn(0, d.pages.size)
            val ref = d.pages.getOrNull(at) ?: d.pages.lastOrNull()
            d.pages.add(at, Page(if (width > 0) width else ref?.width ?: 595.0, if (height > 0) height else ref?.height ?: 842.0))
            true
        }
        val st = host.editorState(file)
        val ref = doc.pages.getOrNull(index.coerceIn(0, doc.pages.size)) ?: doc.pages.lastOrNull()
        val at = index.coerceIn(0, doc.pages.size)
        doc.pages.add(at, Page(if (width > 0) width else ref?.width ?: 595.0, if (height > 0) height else ref?.height ?: 842.0))
        doc.dirty = true
        st?.relayout()
        st?.clampScroll()
        host.structureChanged(file)
        return true
    }

    /** Remove a remote page; never empties the note. */
    private fun applyPageRemove(file: String, index: Int): Boolean {
        val doc = host.openDocument(file) ?: return modifyFile(file) { d ->
            if (d.pages.size <= 1 || index !in d.pages.indices) return@modifyFile false
            d.pages.removeAt(index)
            true
        }
        if (doc.pages.size <= 1 || index !in doc.pages.indices) return false
        val page = doc.pages.removeAt(index)
        doc.dirty = true
        host.editorState(file)?.let { st ->
            st.invalidatePage(page)
            st.relayout()
            st.clampScroll()
        }
        host.structureChanged(file)
        return true
    }

    /** Apply a committed remote room edit; straight to the file when not open. */
    private fun applyRoomEdit(file: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean {
        host.openDocument(file)?.let {
            return host.applyRoomEdit(file, page, sideways, open, a, b)
        }
        return modifyFile(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyFile false
            val cmd = if (open) {
                com.xnotes.core.room.RoomCut.open(pg, a, b - a)
            } else {
                val room = com.xnotes.core.model.Rooms.at(pg.rooms, (a + b) / 2, sideways, 1.0)
                    ?: return@modifyFile false
                com.xnotes.core.room.RoomCut.close(pg, room, a, b)
            }
            if (cmd == null) return@modifyFile false
            doc.dirty = true
            true
        }
    }

    // --- images (chunked bytes first, then the placing op) ---

    private class ImageAsm(val file: String, val total: Long, val buf: ByteArrayOutputStream, var filled: Long = 0)
    private val imageAsm = HashMap<String, ImageAsm>()
    private val pendingImageOps = HashMap<String, ArrayList<Pair<String, JSONObject>>>()
    private val imageBytes = HashMap<String, Pair<String, ByteArray>>()
    private val imageOrder = ArrayDeque<String>()

    private fun onImageChunk(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty() || !policyAllows(file)) return
        val id = msg.optString("image_id")
        if (id.isEmpty()) return
        val total = msg.optLong("total", -1)
        val offset = msg.optLong("offset", -1)
        if (total < 0 || total > IMAGE_MAX_BYTES || offset < 0 || offset > total) {
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
        val asm = imageAsm.getOrPut(id) { ImageAsm(file, total, ByteArrayOutputStream(total.coerceAtMost(IMAGE_MAX_BYTES).toInt())) }
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
        while (imageOrder.size > IMAGE_KEPT) {
            if (imageBytes.size <= 1 || imageOrder.isEmpty()) break
            imageBytes.remove(imageOrder.removeFirst())
        }
    }

    private fun flushImage(id: String) {
        val pend = pendingImageOps.remove(id) ?: return
        val (file, bytes) = imageBytes[id] ?: return
        for ((f, op) in pend) if (f == file && applyImageBytes(f, op, bytes)) Unit
    }

    /** An `add_image` op: apply at once when the bytes are in, else hold for them. */
    private fun applyRemoteImage(file: String, op: JSONObject): Boolean {
        val id = op.optString("image_id")
        if (id.isEmpty()) return false
        val ready = imageBytes[id]?.takeIf { it.first == file }?.second
        if (ready != null) return applyImageBytes(file, op, ready)
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

    /** Materialize [bytes] as an image file and place the item; closed files go through the codec. */
    private fun applyImageBytes(file: String, op: JSONObject, bytes: ByteArray): Boolean {
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
        host.openDocument(file)?.let { doc ->
            val pg = doc.pages.getOrNull(page) ?: run {
                dest.delete()
                return false
            }
            if (item.rect.w <= 0 || item.rect.h <= 0) {
                dest.delete()
                return false
            }
            pg.items.add(item)
            doc.dirty = true
            host.editorState(file)?.appendToCache(pg, item)
            host.repaintDocument(file)
            return true
        }
        val ok = modifyFile(file) { doc ->
            val pg = doc.pages.getOrNull(page) ?: return@modifyFile false
            if (item.rect.w <= 0 || item.rect.h <= 0) return@modifyFile false
            pg.items.add(item)
            true
        }
        if (!ok) dest.delete()
        return ok
    }

    private val codec by lazy {
        DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
    }

    /** Read-modify-write a closed file through the codec. */
    private fun modifyFile(file: String, edit: (Document) -> Boolean): Boolean {
        val bytes = files.read(file) ?: return false
        return try {
            val doc = codec.read(bytes.inputStream(), null, null)
            if (!edit(doc)) return false
            val out = ByteArrayOutputStream()
            codec.write(doc, out)
            files.write(file, out.toByteArray())
        } catch (_: Exception) {
            false
        }
    }

    // --- live strokes (true streaming: every sample goes out at once) ---

    /** A local pointer sample, straight to the socket. */
    fun liveSample(
        file: String,
        page: Int,
        strokeId: String,
        tool: String,
        width: Double,
        color: String,
        x: Double,
        y: Double,
        p: Double,
    ) {
        val samples = JSONArray().put(JSONArray().put(x).put(y).put(p))
        send(
            JSONObject().put("t", "live").put("file", file).put("page", page)
                .put("stroke_id", strokeId).put("tool", tool).put("width", width).put("color", color)
                .put("samples", samples).put("done", false),
        )
    }

    fun finishStroke(file: String, page: Int, strokeId: String, tool: String, width: Double, color: String) {
        send(
            JSONObject().put("t", "live").put("file", file).put("page", page)
                .put("stroke_id", strokeId).put("tool", tool).put("width", width).put("color", color)
                .put("samples", JSONArray()).put("done", true),
        )
    }

    /** A local room-drag frame, straight to the socket (no batching, like strokes). */
    fun roomPreview(file: String, page: Int, liveId: String, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        val msg = JSONObject().put("t", "room_live").put("file", file).put("page", page)
            .put("split", split).put("resume", resume).put("shift", shift)
            .put("live_id", liveId).put("done", false)
        if (sideways) msg.put("sideways", true)
        send(msg)
    }

    /** The local room drag ended: drop the far side's preview. */
    fun finishRoom(file: String, page: Int, liveId: String, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        val msg = JSONObject().put("t", "room_live").put("file", file).put("page", page)
            .put("split", split).put("resume", resume).put("shift", shift)
            .put("live_id", liveId).put("done", true)
        if (sideways) msg.put("sideways", true)
        send(msg)
    }

    private fun onRoomLive(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty() || !policyAllows(file)) return
        if (msg.optBoolean("done", false)) {
            host.clearRoomPreview(file)
        } else {
            val page = msg.optInt("page", 0)
            val doc = host.openDocument(file) ?: return
            if (page !in doc.pages.indices) return
            host.previewRoom(
                file, page, msg.optBoolean("sideways", false),
                msg.optDouble("split", 0.0), msg.optDouble("resume", 0.0), msg.optDouble("shift", 0.0),
            )
        }
        host.repaintLive(file)
    }

    private fun onLive(msg: JSONObject) {
        val file = msg.optString("file")
        if (file.isEmpty() || !policyAllows(file)) return
        val doc = host.openDocument(file) ?: return
        val overlay = host.liveOverlay(file) ?: return
        val samples = msg.optJSONArray("samples") ?: JSONArray()
        if (samples.length() == 0 && !msg.optBoolean("done", false)) return
        val color = com.xnotes.canvas.RemoteLiveStrokes.parseColor(msg.optString("color")) ?: return
        val pts = ArrayList<com.xnotes.core.geometry.Pt>(samples.length())
        for (i in 0 until samples.length()) {
            val s = samples.optJSONArray(i) ?: continue
            if (s.length() < 2) continue
            pts += com.xnotes.core.geometry.Pt(s.optDouble(0), s.optDouble(1))
        }
        val page = msg.optInt("page", 0)
        if (page !in doc.pages.indices) return
        overlay.update(file, page, msg.optString("stroke_id"), msg.optDouble("width", 3.0), color, pts, msg.optBoolean("done", false))
        host.repaintLive(file)
    }

    // --- create / delete (host approves in UI) ---

    private fun onDeleteRequest(msg: JSONObject) {
        val id = msg.optString("id")
        if (id.isEmpty() || !policyAllows(id)) {
            send(JSONObject().put("t", "delete_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", false).put("reason", "not shared"))
            return
        }
        val name = displayName(id)
        host.onApproval("delete", msg.optString("device", "?"), name) { allow ->
            if (!allow) {
                send(JSONObject().put("t", "delete_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", false).put("reason", "declined"))
                return@onApproval
            }
            val ok = files.delete(id)
            if (ok) {
                dropFile(id)
                policy.setShared(id, false)
                pushIndexDelta()
            }
            send(JSONObject().put("t", "delete_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", ok))
        }
    }

    private fun onCreateRequest(msg: JSONObject) {
        val dirId = msg.optString("dir_id").ifEmpty { null }
        val name = msg.optString("name").takeIf { it.isNotEmpty() } ?: run {
            send(JSONObject().put("t", "create_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", false).put("reason", "no name"))
            return
        }
        if (dirId != null && !policyAllows(dirId)) {
            send(JSONObject().put("t", "create_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", false).put("reason", "not shared"))
            return
        }
        host.onApproval("create", msg.optString("device", "?"), name) { allow ->
            if (!allow) {
                send(JSONObject().put("t", "create_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", false).put("reason", "declined"))
                return@onApproval
            }
            val kind = msg.optInt("kind", 0)
            val id = files.create(dirId, name, kind == 1)
            if (id == null) {
                send(JSONObject().put("t", "create_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", false).put("reason", "failed"))
                return@onApproval
            }
            // An approved creation is shared from birth.
            policy.setShared(id, true)
            headVersion(id) // registers at 1
            pushIndexDelta()
            send(JSONObject().put("t", "create_result").put("in_reply_to", msg.optInt("seq", -1)).put("ok", true).put("id", id))
        }
    }

    // --- versions / dedupe / policy ---

    /** Head version of [id], registering new files at 1 (and only then persisting). */
    fun headVersion(id: String): Long = synchronized(lock) {
        versions[id] ?: run {
            versions[id] = 1L
            saveVersionsLocked()
            1L
        }
    }

    private fun bumpVersion(id: String): Long = synchronized(lock) {
        val next = (versions[id] ?: 1L) + 1
        versions[id] = next
        saveVersionsLocked()
        next
    }

    private fun markDedupe(file: String, dedupe: String): Boolean = synchronized(lock) {
        if (dedupe.isEmpty()) return true
        val seen = seenDedupes.getOrPut(file) { ArrayDeque() }
        if (dedupe in seen) return false
        seen.addLast(dedupe)
        while (seen.size > DEDUPE_KEPT) seen.removeFirst()
        true
    }

    private fun policyAllows(id: String): Boolean = synchronized(lock) { id in coveredIds }

    private fun displayName(id: String): String {
        val p = synchronized(lock) { idPaths[id] }
        return p?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: id
    }

    private fun dropFile(id: String) {
        synchronized(lock) {
            versions.remove(id)
            seenDedupes.remove(id)
            saveVersionsLocked()
        }
    }

    private fun loadVersions() {
        try {
            val f = File(versionsFile, VERSIONS)
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            val names = o.names() ?: return
            synchronized(lock) {
                for (i in 0 until names.length()) {
                    val id = names.getString(i)
                    versions[id] = o.optLong(id, 1L).coerceAtLeast(1L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveVersionsLocked() {
        try {
            versionsFile.mkdirs()
            val o = JSONObject()
            for ((id, v) in versions) o.put(id, v)
            val tmp = File(versionsFile, "$VERSIONS.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(versionsFile, VERSIONS))) {
                File(versionsFile, VERSIONS).delete()
                tmp.renameTo(File(versionsFile, VERSIONS))
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHUNK = 256 * 1024L
        const val IMAGE_CHUNK = 256 * 1024
        const val IMAGE_MAX_BYTES = 64L * 1024 * 1024
        const val IMAGE_KEPT = 8
        const val DEDUPE_KEPT = 64
        const val VERSIONS = "sync_versions.json"
    }
}
