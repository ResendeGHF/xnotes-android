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

    /** Connected: introduce ourselves. The host answers `hello_ack`. */
    fun startHandshake() {
        helloSent = true
        lastRxMs = nowMs()
        lastHeartbeatMs = lastRxMs
        send(SyncMsg.Hello(nextSeq(), "xnotes-linux", 1, deviceName, ownCertFp))
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
        withLock(lock) { assemblers.clear() }
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
        send(SyncMsg.DeleteRequest(nextSeq(), id))
    }

    fun requestCreate(dirId: String?, name: String, kind: Int) {
        send(SyncMsg.CreateRequest(nextSeq(), dirId, name, kind))
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
     * made against; a `resync` answer means refetch and retry once.
     */
    fun submitOp(fileId: String, baseVersion: Long, dedupe: String, ops: List<SyncOp>) {
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

    // --- inbound ---

    private fun onMessage(msg: SyncMsg) {
        when (msg) {
            is SyncMsg.HelloAck -> onHelloAck(msg)
            is SyncMsg.PairRequest -> onPairRequest(msg)
            is SyncMsg.PairResult -> onPairResult(msg)
            is SyncMsg.Index -> onIndex(msg)
            is SyncMsg.FileChunk -> onFileChunk(msg)
            is SyncMsg.Op -> onOp(msg)
            is SyncMsg.OpAck -> onOpAck(msg)
            is SyncMsg.Resync -> onResync(msg)
            is SyncMsg.Live -> onLive(msg)
            is SyncMsg.Presence -> withLock(lock) { hostPresence[msg.file] = msg.page }
            is SyncMsg.IndexSubscribe -> Unit // we send it; the host answers an index
            is SyncMsg.Heartbeat -> Unit
            is SyncMsg.DeleteResult -> host.onDeleteResult(msg.ok, msg.reason)
            is SyncMsg.CreateResult -> host.onCreateResult(msg.ok, msg.id, msg.reason)
            is SyncMsg.Hello, is SyncMsg.PairRequest, is SyncMsg.Fetch,
            is SyncMsg.DeleteRequest, is SyncMsg.CreateRequest,
            is SyncMsg.Unknown,
            -> Unit // host-side or future messages; ignored here
        }
    }

    private fun onHelloAck(m: SyncMsg.HelloAck) {
        // The live peer must be the pinned companion (or be pairing now).
        val pinned = pins.nameFor(m.companionCertFp)
        if (pinned != null) {
            pairedFp = m.companionCertFp
            pairedName = pinned
            host.onPaired(pinned)
        } else if (m.paired) {
            // The host thinks we are paired but we pinned nothing: re-pair.
            host.onError("host claims pairing we do not have; pair again")
            return
        }
        if (pairedFp != null) {
            // Ask for the file list.
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
        val gone = withLock(lock) {
            val seen = HashSet<String>()
            for (f in m.files) {
                seen += f.id
                index[f.id] = f
                if (f.kind == 0) { if (f.id !in versions) versions[f.id] = f.version } else versions.remove(f.id)
            }
            index.keys.filter { it !in seen }.also { dropped ->
                for (id in dropped) {
                    index.remove(id)
                    versions.remove(id)
                    fileCache.remove(id)
                    hostPresence.remove(id)
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
        if (!markDedupe(m.file, m.dedupe)) return
        withLock(lock) { versions[m.file] = m.version }
        for (op in m.ops) {
            when (op) {
                is SyncOp.AddStroke -> host.applyRemoteStroke(m.file, op.page, op.stroke)
                is SyncOp.EraseStrokes -> host.applyRemoteErase(m.file, op.page, op.ids)
                is SyncOp.Unknown -> Unit
            }
        }
        liveOverlay?.finish(m.dedupe)
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
