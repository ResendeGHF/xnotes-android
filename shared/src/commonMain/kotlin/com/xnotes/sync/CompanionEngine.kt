package com.xnotes.sync

import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.platform.File
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.monotonicNanos
import com.xnotes.core.platform.withLock
import kotlin.random.Random

/**
 * The companion side of SYNC.md v1: pairing, shared-file index, fetch, committed
 * ops both ways and true-streamed live strokes. Transport-agnostic: bytes in
 * via [bytesReceived], bytes out through [SyncEngineHost.onBytes].
 *
 * The host (Android) owns files and commit authority; this side caches, submits
 * and mirrors. Remote committed ops apply through [SyncEngineHost] (which routes
 * them to the open document, bypassing undo); local edits go through the
 * normal local path and are submitted with the head version as base.
 */
class CompanionEngine(
    private val host: SyncEngineHost,
    pinsDir: File,
    /** Shown to the host at pairing (`Guest-<device-name>` until pinned). */
    val deviceName: String,
    /** Our own certificate fingerprint (lowercase hex), sent in `hello`. */
    val ownCertFp: String,
) {
    /** The far side's live runs, painted by the canvas overlay (wired by the host). */
    var liveOverlay: RemoteLiveStrokes? = null

    private val lock = Lock()
    private val decoder = SyncFrames.Decoder()
    private val pins = SyncPins(pinsDir)
    private var seq = 0

    /** Pairing state. */
    private var displayCode: String? = null
    private var codeAtMs: Long = 0
    private var codeAttempts = 0
    private var pendingPair: PendingPair? = null

    /** Connection state. */
    private var helloSent = false
    private var pairedFp: String? = null
    private var pairedName: String? = null
    private var lastRxMs: Long = 0L

    /** Shared files, by id. */
    private val index = LinkedHashMap<String, SyncFileEntry>()
    private val versions = HashMap<String, Long>()
    private val fileCache = LinkedHashMap<String, CachedFile>()
    private val assemblers = HashMap<String, FileAssembler>()

    /** Dedupe ids seen, per file (bounded). */
    private val seenDedupes = HashMap<String, ArrayDeque<String>>()

    /** Host presence: file id to note page (-1 = closed). */
    private val hostPresence = HashMap<String, Int>()

    private var lastHeartbeatMs = 0L

    private data class PendingPair(val deviceName: String, val fp: String, val replySeq: Int)
    private data class CachedFile(val version: Long, val bytes: ByteArray, var lastUsedMs: Long)

    private class FileAssembler(val version: Long, val total: Long) {
        val buf = ByteArray(total.toInt().coerceAtLeast(0))
        var filled = 0L
        var complete = false
    }

    // --- transport ---

    /** Queue a message for the socket. */
    private fun send(msg: SyncMsg) {
        host.onBytes(SyncFrames.encode(msg))
    }

    private fun nextSeq(): Int = withLock(lock) { ++seq }

    /** Socket bytes arrived. */
    fun bytesReceived(data: ByteArray) {
        lastRxMs = nowMs()
        for (msg in decoder.feed(data)) onMessage(msg)
    }

    /** 5 s UI tick: heartbeat due, peer silence, pairing-code expiry. */
    fun tick() {
        val now = nowMs()
        if (helloSent && now - lastHeartbeatMs >= HEARTBEAT_MS) {
            lastHeartbeatMs = now
            send(SyncMsg.Heartbeat(nextSeq()))
        }
        if (helloSent && lastRxMs > 0 && now - lastRxMs >= TIMEOUT_MS) {
            host.onError("connection silent for 60 s")
        }
        withLock(lock) {
            if (displayCode != null && now - codeAtMs >= CODE_TTL_MS) {
                displayCode = null
                pendingPair = null
            }
        }
    }

    // --- handshake + pairing ---

    /** The socket connected: reset per-connection state and wait for the host's `hello`. */
    fun onConnected() {
        helloSent = true
        lastRxMs = nowMs()
        lastHeartbeatMs = lastRxMs
    }

    /**
     * Show a pairing code on the companion screen. Single use, 5 minutes, 3
     * attempts. Returns the code to display.
     */
    fun beginPairing(): String {
        val code = Random.Default.nextInt(1_000_000).toString().padStart(6, '0')
        withLock(lock) {
            displayCode = code
            codeAtMs = nowMs()
            codeAttempts = 0
            pendingPair = null
        }
        return code
    }

    /** The desktop user answers the approval popup. */
    fun decidePairing(accept: Boolean) {
        val pending = withLock(lock) { pendingPair.also { pendingPair = null } } ?: return
        if (!accept) {
            send(SyncMsg.PairResult(nextSeq(), pending.replySeq, ok = false, reason = "declined"))
            return
        }
        pins.pin(pending.deviceName, pending.fp)
        pairedFp = pending.fp
        pairedName = pending.deviceName
        send(SyncMsg.PairResult(nextSeq(), pending.replySeq, ok = true, reason = null))
        host.onPaired(pending.deviceName)
        send(SyncMsg.IndexSubscribe(nextSeq()))
    }

    fun pairedDevice(): String? = pairedName
    fun pins(): List<SyncPins.Pin> = pins.all()

    /** The socket dropped: forget live runs and half-fetched chunks. */
    fun onDisconnected() {
        liveOverlay?.clear()
        withLock(lock) {
            assemblers.clear()
            imageAsm.clear()
            pendingImages.clear()
        }
    }

    fun unpair(name: String): Boolean {
        if (pairedName == name) {
            pairedFp = null
            pairedName = null
        }
        return pins.unpin(name)
    }

    // --- files ---

    /** Current shared-file list, sorted by path. */
    fun files(): List<SyncFileEntry> = withLock(lock) { index.values.sortedBy { it.path } }

    /** The head version known for [id], or null. */
    fun headVersion(id: String): Long? = withLock(lock) { versions[id] }

    fun fileVersion(id: String): Long? = withLock(lock) { versions[id] }

    fun fileBytes(id: String): ByteArray? = withLock(lock) {
        fileCache[id]?.also { it.lastUsedMs = nowMs() }?.bytes
    }

    /** Open [id]: fetch when uncached, else hand over at once. */
    fun requestOpen(id: String) {
        val cached = withLock(lock) { fileCache[id] }
        val version = withLock(lock) { versions[id] }
        if (cached != null && version != null && cached.version == version) {
            host.onFileReady(id)
            return
        }
        send(SyncMsg.Fetch(nextSeq(), id, 0L, 0L))
    }

    fun requestDelete(id: String) {
        send(SyncMsg.DeleteRequest(nextSeq(), id, deviceName))
    }

    fun requestCreate(dirId: String?, name: String, kind: Int) {
        send(SyncMsg.CreateRequest(nextSeq(), deviceName, dirId, name, kind))
    }

    fun closeFile(id: String) {
        send(SyncMsg.Presence(nextSeq(), id, -1))
    }

    fun presence(fileId: String, page: Int) {
        send(SyncMsg.Presence(nextSeq(), fileId, page))
    }

    /** Host-side presence the engine has seen. */
    fun hostPresence(): Map<String, Int> = withLock(lock) { hostPresence.toMap() }

    // --- ops ---

    /**
     * Submit a locally committed batch. [baseVersion] is the head the edit was
     * made against; a `resync` answer means refetch and retry once. The dedupe
     * is remembered at once so the host's fan-out echo of our own batch is
     * dropped instead of applied twice.
     */
    fun submitOp(fileId: String, baseVersion: Long, dedupe: String, ops: List<SyncOp>) {
        markDedupe(fileId, dedupe)
        send(SyncMsg.Op(nextSeq(), fileId, baseVersion, 0L, dedupe, ops))
    }

    // --- live strokes (true streaming: every sample goes out at once) ---

    fun liveSample(
        fileId: String,
        page: Int,
        strokeId: String,
        tool: String,
        width: Double,
        color: String,
        x: Double,
        y: Double,
        p: Double,
    ) {
        send(
            SyncMsg.Live(
                nextSeq(), fileId, page, strokeId, tool, width, color,
                listOf(WireSample(x, y, p)), done = false,
            ),
        )
    }

    fun finishStroke(fileId: String, page: Int, strokeId: String, tool: String, width: Double, color: String) {
        send(SyncMsg.Live(nextSeq(), fileId, page, strokeId, tool, width, color, emptyList(), done = true))
    }

    // --- room drags (live preview + committed edit, mirroring strokes) ---

    fun previewRoom(
        fileId: String,
        page: Int,
        liveId: String,
        sideways: Boolean,
        split: Double,
        resume: Double,
        shift: Double,
    ) {
        send(SyncMsg.RoomLive(nextSeq(), fileId, page, sideways, split, resume, shift, liveId, done = false))
    }

    fun finishRoom(fileId: String, page: Int, liveId: String, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        send(SyncMsg.RoomLive(nextSeq(), fileId, page, sideways, split, resume, shift, liveId, done = true))
    }

    // --- images (chunked bytes first, then the placing op) ---

    /** Largest single image accepted, so a bad peer cannot OOM the assembler. */
    private val IMAGE_MAX_BYTES = 64L * 1024 * 1024

    /** Chunks an image's encoded bytes; the `add_image` op follows on the same stream. */
    fun sendImageData(fileId: String, imageId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            send(SyncMsg.ImageChunk(nextSeq(), fileId, imageId, 0L, 0L, "", last = true))
            return
        }
        var off = 0
        while (off < bytes.size) {
            val n = minOf(IMAGE_CHUNK_BYTES, bytes.size - off)
            send(
                SyncMsg.ImageChunk(
                    nextSeq(), fileId, imageId, bytes.size.toLong(), off.toLong(),
                    base64Of(bytes.copyOfRange(off, off + n)), last = off + n >= bytes.size,
                ),
            )
            off += n
        }
    }

    private class ImageAsm(val file: String, val total: Long, val buf: ByteArray, var filled: Long = 0)
    private data class PendingImage(val file: String, val op: SyncOp.AddImage)
    private data class ImageBytes(val file: String, val bytes: ByteArray)
    private val imageAsm = HashMap<String, ImageAsm>()
    private val pendingImages = HashMap<String, ArrayList<PendingImage>>()
    private val imageBytes = HashMap<String, ImageBytes>()
    private val imageOrder = ArrayDeque<String>()

    private fun rememberImageBytes(imageId: String, file: String, bytes: ByteArray) {
        withLock(lock) {
            imageBytes[imageId] = ImageBytes(file, bytes)
            imageOrder.remove(imageId)
            imageOrder.addLast(imageId)
            while (imageOrder.size > IMAGE_KEPT || imageBytes.values.sumOf { it.bytes.size.toLong() } > IMAGE_KEPT_BYTES) {
                val oldest = imageOrder.removeFirstOrNull() ?: break
                if (imageBytes.size <= 1) break
                imageBytes.remove(oldest)
            }
        }
    }

    private fun dropImageLocked(imageId: String) {
        imageAsm.remove(imageId)
        pendingImages.remove(imageId)
        imageBytes.remove(imageId)
        imageOrder.remove(imageId)
    }

    // --- inbound ---

    private fun onMessage(msg: SyncMsg) {
        when (msg) {
            is SyncMsg.Hello -> onHello(msg)
            is SyncMsg.PairRequest -> onPairRequest(msg)
            is SyncMsg.PairResult -> onPairResult(msg)
            is SyncMsg.Index -> onIndex(msg)
            is SyncMsg.FileChunk -> onFileChunk(msg)
            is SyncMsg.Op -> onOp(msg)
            is SyncMsg.OpAck -> onOpAck(msg)
            is SyncMsg.Resync -> onResync(msg)
            is SyncMsg.Live -> onLive(msg)
            is SyncMsg.RoomLive -> onRoomLive(msg)
            is SyncMsg.ImageChunk -> onImageChunk(msg)
            is SyncMsg.Presence -> withLock(lock) { hostPresence[msg.file] = msg.page }
            is SyncMsg.IndexSubscribe -> Unit // we send it; the host answers an index
            is SyncMsg.HelloAck -> Unit // the host never sends one; we answer hellos
            is SyncMsg.Heartbeat -> Unit
            is SyncMsg.DeleteResult -> host.onDeleteResult(msg.ok, msg.reason)
            is SyncMsg.CreateResult -> host.onCreateResult(msg.ok, msg.id, msg.reason)
            is SyncMsg.Hello, is SyncMsg.PairRequest, is SyncMsg.Fetch,
            is SyncMsg.DeleteRequest, is SyncMsg.CreateRequest,
            is SyncMsg.Unknown,
            -> Unit // host-side or future messages; ignored here
        }
    }

    private fun onHello(m: SyncMsg.Hello) {
        // The dialer introduces itself; answer with our pin state and ask for the file list.
        val pinned = pins.nameFor(m.hostCertFp)
        val paired = pinned != null
        if (paired) {
            pairedFp = m.hostCertFp
            pairedName = pinned
            host.onPaired(pinned)
        }
        send(SyncMsg.HelloAck(nextSeq(), m.seq, ownCertFp, paired, codeRequired = !paired))
        if (paired) {
            send(SyncMsg.IndexSubscribe(nextSeq()))
        }
    }

    private fun onPairRequest(m: SyncMsg.PairRequest) {
        val now = nowMs()
        val attempt = withLock(lock) {
            val code = displayCode
            if (code == null || now - codeAtMs >= CODE_TTL_MS) {
                null
            } else {
                codeAttempts++
                if (codeAttempts > CODE_ATTEMPTS) {
                    displayCode = null
                    null
                } else if (m.code == code) {
                    displayCode = null // single use
                    true
                } else {
                    false
                }
            }
        }
        when (attempt) {
            null -> send(SyncMsg.PairResult(nextSeq(), m.seq, ok = false, reason = "no pairing open"))
            false -> send(SyncMsg.PairResult(nextSeq(), m.seq, ok = false, reason = "bad code"))
            true -> {
                withLock(lock) { pendingPair = PendingPair(m.deviceName, m.hostCertFp, m.seq) }
                host.onPairRequest(m.deviceName, m.hostCertFp)
            }
        }
    }

    private fun onPairResult(m: SyncMsg.PairResult) {
        // Pairing was desktop-approved at the host… nothing to do companion-side:
        // this side approves, it never asks. A stray result is ignored.
        host.onError("unexpected pair_result: ${m.reason ?: "ok"}")
    }

    private fun onIndex(m: SyncMsg.Index) {
        // Deltas (full:false) carry the files that changed: merge them. Only a
        // full listing may retire ids. Heads only move forward: the host is the
        // authority, so an index head is always at least what ops gave us, and
        // advancing it makes a later open refetch instead of serving stale bytes.
        val gone = withLock(lock) {
            val seen = HashSet<String>()
            for (f in m.files) {
                seen += f.id
                index[f.id] = f
                if (f.kind == 0) {
                    val cur = versions[f.id]
                    if (cur == null || f.version > cur) versions[f.id] = f.version
                } else {
                    versions.remove(f.id)
                }
            }
            if (!m.full) {
                emptyList<String>()
            } else {
                index.keys.filter { it !in seen }.also { dropped ->
                    for (id in dropped) {
                        index.remove(id)
                        versions.remove(id)
                        fileCache.remove(id)
                        hostPresence.remove(id)
                    }
                }
            }
        }
        for (id in gone) host.onFileGone(id)
        host.onIndexChanged()
    }

    private fun onFileChunk(m: SyncMsg.FileChunk) {
        val asm = withLock(lock) {
            assemblers.getOrPut(m.id) { FileAssembler(m.version, m.total) }
        }
        if (asm.version != m.version || asm.total != m.total) {
            withLock(lock) { assemblers[m.id] = FileAssembler(m.version, m.total) }
            return requestOpen(m.id)
        }
        val bytes = bytesOfBase64(m.data) ?: return
        if (m.offset != asm.filled || asm.filled + bytes.size > asm.total) return
        bytes.copyInto(asm.buf, asm.filled.toInt())
        asm.filled += bytes.size
        val done = m.last || asm.filled >= asm.total
        if (!done) {
            if (asm.filled < asm.total) send(SyncMsg.Fetch(nextSeq(), m.id, asm.filled, 0L))
            return
        }
        asm.complete = true
        val data = asm.buf.copyOf(asm.filled.toInt())
        withLock(lock) {
            assemblers.remove(m.id)
            fileCache[m.id] = CachedFile(m.version, data, nowMs())
            versions[m.id] = m.version
            evictLocked()
        }
        liveOverlay?.clearFile(m.id)
        host.onFileReady(m.id)
    }

    private fun evictLocked() {
        var total = fileCache.values.sumOf { it.bytes.size.toLong() }
        while (total > FILE_CACHE_BYTES) {
            val oldest = fileCache.minByOrNull { it.value.lastUsedMs } ?: break
            if (fileCache.size <= 1) break
            fileCache.remove(oldest.key)
            total -= oldest.value.bytes.size
        }
    }

    private fun onOp(m: SyncMsg.Op) {
        // Host broadcasts its commits (and fans ours back): apply, track the head.
        // The host fans our own batch back with `dedupe + ":fanned"`; that echo
        // must move the head and drop the live run, never re-apply the strokes.
        val echo = m.dedupe.endsWith(FAN_SUFFIX) && m.dedupe.length > FAN_SUFFIX.length
        val key = if (echo) m.dedupe.dropLast(FAN_SUFFIX.length) else m.dedupe
        val fresh = markDedupe(m.file, key)
        withLock(lock) { versions[m.file] = m.version }
        liveOverlay?.finish(key)
        if (!fresh) return
        // A room commit supersedes its live preview even when the done frame was lost.
        if (m.ops.any { it is SyncOp.OpenRoom || it is SyncOp.CloseRoom }) host.clearRemoteRoomPreview(m.file)
        for (op in m.ops) {
            when (op) {
                is SyncOp.AddStroke -> host.applyRemoteStroke(m.file, op.page, op.stroke)
                is SyncOp.EraseStrokes -> host.applyRemoteErase(m.file, op.page, op.ids)
                is SyncOp.AddPage -> host.applyRemotePageAdd(m.file, op.index, op.width, op.height)
                is SyncOp.RemovePage -> host.applyRemotePageRemove(m.file, op.index)
                is SyncOp.OpenRoom -> host.applyRemoteRoomEdit(m.file, op.page, op.sideways, true, op.at, op.at + op.size)
                is SyncOp.CloseRoom -> host.applyRemoteRoomEdit(m.file, op.page, op.sideways, false, op.a, op.b)
                is SyncOp.AddImage -> onRemoteImage(m.file, op)
                is SyncOp.Unknown -> Unit
            }
        }
    }

    private fun onOpAck(m: SyncMsg.OpAck) {
        withLock(lock) { versions[m.file] = m.version }
    }

    private fun onResync(m: SyncMsg.Resync) {
        withLock(lock) {
            versions[m.file] = m.version
            fileCache.remove(m.file)
            assemblers.remove(m.file)
        }
        liveOverlay?.clearFile(m.file)
        host.applyResync(m.file, m.version)
        send(SyncMsg.Fetch(nextSeq(), m.file, 0L, 0L))
    }

    private fun onLive(m: SyncMsg.Live) {
        if (m.samples.isEmpty() && !m.done) return
        liveOverlay?.update(m.file, m.page, m.strokeId, m.tool, m.width, m.color, m.samples, m.done)
        host.onLiveDirty()
    }    private fun onRoomLive(m: SyncMsg.RoomLive) {
        if (m.done) {
            host.clearRemoteRoomPreview(m.file)
        } else {
            host.previewRemoteRoom(m.file, m.page, m.sideways, m.split, m.resume, m.shift)
        }
        host.onLiveDirty()
    }

    private fun onImageChunk(m: SyncMsg.ImageChunk) {
        // Bytes precede their op on the same stream; a mismatch drops the transfer.
        if (m.total < 0 || m.total > IMAGE_MAX_BYTES || m.offset < 0 || m.offset > m.total) {
            withLock(lock) { dropImageLocked(m.imageId) }
            return
        }
        if (m.total == 0L) {
            if (!m.last) {
                withLock(lock) { dropImageLocked(m.imageId) }
                return
            }
            rememberImageBytes(m.imageId, m.file, ByteArray(0))
            flushImage(m.imageId)
            return
        }
        val asm = withLock(lock) { imageAsm.getOrPut(m.imageId) { ImageAsm(m.file, m.total, ByteArray(m.total.toInt())) } }
        if (asm.total != m.total || asm.file != m.file) {
            withLock(lock) { dropImageLocked(m.imageId) }
            return
        }
        val bytes = bytesOfBase64(m.data) ?: run {
            withLock(lock) { dropImageLocked(m.imageId) }
            return
        }
        if (m.offset != asm.filled || asm.filled + bytes.size > asm.total) {
            withLock(lock) { dropImageLocked(m.imageId) }
            return
        }
        bytes.copyInto(asm.buf, asm.filled.toInt())
        asm.filled += bytes.size
        if (!m.last && asm.filled < asm.total) return
        if (asm.filled != asm.total) {
            withLock(lock) { dropImageLocked(m.imageId) }
            return
        }
        val data = asm.buf.copyOf(asm.filled.toInt())
        withLock(lock) { imageAsm.remove(m.imageId) }
        rememberImageBytes(m.imageId, m.file, data)
        flushImage(m.imageId)
    }

    /** An `add_image` op: apply at once when the bytes are in, else hold for them. */
    private fun onRemoteImage(file: String, op: SyncOp.AddImage) {
        val ready = withLock(lock) { imageBytes[op.image.imageId]?.takeIf { it.file == file } }
        if (ready != null) {
            host.applyRemoteImage(file, op.page, op.image, ready.bytes)
            return
        }
        withLock(lock) {
            val pend = pendingImages.getOrPut(op.image.imageId) { ArrayList() }
            pend += PendingImage(file, op)
            while (pendingImages.values.sumOf { it.size } > IMAGE_PENDING_MAX) {
                val first = pendingImages.keys.firstOrNull() ?: break
                val list = pendingImages[first]!!
                list.removeAt(0)
                if (list.isEmpty()) pendingImages.remove(first)
            }
        }
    }

    private fun flushImage(imageId: String) {
        val pend = withLock(lock) { pendingImages.remove(imageId) } ?: return
        val ready = withLock(lock) { imageBytes[imageId] } ?: return
        for (p in pend) host.applyRemoteImage(p.file, p.op.page, p.op.image, ready.bytes)
    }

    private fun markDedupe(file: String, dedupe: String): Boolean = withLock(lock) {
        if (dedupe.isEmpty()) return true
        val seen = seenDedupes.getOrPut(file) { ArrayDeque() }
        if (dedupe in seen) return false
        seen.addLast(dedupe)
        while (seen.size > DEDUPE_KEPT) seen.removeFirst()
        true
    }

    private fun nowMs(): Long = monotonicNanos() / 1_000_000L

    companion object {
        /** Suffix the host appends when fanning our batch back (tolerated either way). */
        const val FAN_SUFFIX = ":fanned"

        /** Image bytes chunked at the same size as file chunks. */
        const val IMAGE_CHUNK_BYTES = 256 * 1024

        /** Completed images remembered, for ops that arrive after their bytes. */
        const val IMAGE_KEPT = 8
        const val IMAGE_KEPT_BYTES = 64L * 1024 * 1024

        /** Image ops held for bytes still in flight, across all files. */
        const val IMAGE_PENDING_MAX = 16

        /** Heartbeat every 20 s; silence over 60 s drops the connection. */
        const val HEARTBEAT_MS = 20_000L
        const val TIMEOUT_MS = 60_000L

        /** A displayed pairing code lives 5 min and takes 3 attempts. */
        const val CODE_TTL_MS = 5 * 60_000L
        const val CODE_ATTEMPTS = 3

        /** File bytes kept, oldest first. */
        const val FILE_CACHE_BYTES = 64L * 1024 * 1024

        /** Dedupe ids remembered per file. */
        const val DEDUPE_KEPT = 64
    }
}
