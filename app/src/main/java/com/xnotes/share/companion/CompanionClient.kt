package com.xnotes.share.companion

import com.xnotes.share.ShareCrypto
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import org.json.JSONObject

/**
 * SYNC.md v1 companion (host-side) TLS client. The companion listens; this
 * dials it. Pure JVM (no android.*), so it unit-tests on the host.
 *
 * Threading: [connect] returns immediately; a single daemon worker runs the
 * connect/read loop, plus a writer draining [send]s and a heartbeat tick.
 * [onMessage] fires on the worker thread — callers must hop as needed.
 *
 * Wire: every message carries `t` and `seq` (per-connection monotonic,
 * assigned here when absent); unknown `t` values are ignored by the peer.
 * Heartbeat `{"t":"heartbeat","seq":n}` goes out every 20 s; 60 s of receive
 * silence tears the connection down and the backoff loop redials.
 */
class CompanionClient(
    private val host: String,
    private val port: Int,
    private val identity: ShareCrypto.ServerIdentity,
    /** Pinned companion cert fingerprint (lowercase hex), or null for TOFU. */
    @Volatile var pinnedFp: String?,
    var autoReconnect: Boolean = true,
    var onConnected: (peerFp: String) -> Unit = {},
    var onMessage: (JSONObject) -> Unit = {},
    var onDisconnected: (willRetry: Boolean) -> Unit = {},
    var onError: (String) -> Unit = {},
) {
    companion object {
        const val HEARTBEAT_MS = 20_000L
        const val SILENCE_LIMIT_MS = 60_000L
        /** Reconnect delays: 2 s, 5 s, 15 s, then 30 s capped. */
        val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L)
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    private val running = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var lastPeerFp: String? = null
    /** True between a completed handshake and the socket closing. */
    @Volatile var connected: Boolean = false
        private set
    private val writeQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    /** Poison marker that unblocks the writer on shutdown. */
    private val POISON = ByteArray(0)

    /** Fingerprint of the current/last connection's peer, if handshook. */
    fun peerFingerprint(): String? = lastPeerFp

    /** True while this client targets [h]:[p] (for dial reuse checks). */
    fun isTarget(h: String, p: Int): Boolean = h == host && p == port

    /** Next per-connection sequence number (resets to 1 on each connect). */
    fun nextSeq(): Int = seq.incrementAndGet()

    /**
     * Queue [msg] for sending. Thread-safe; assigns `seq` when absent and
     * encodes immediately, so the caller may reuse the object. Silently
     * dropped while disconnected.
     */
    fun send(msg: JSONObject) {
        if (!running.get()) return
        if (!msg.has("seq")) msg.put("seq", nextSeq())
        runCatching { writeQueue.put(Frames.encode(msg)) }
    }

    /** Dial on a background thread; no-op when already connecting/connected. */
    @Synchronized
    fun connect() {
        if (running.getAndSet(true)) return
        seq.set(0)
        val t = Thread(::loop, "companion-client")
        t.isDaemon = true
        worker = t
        t.start()
    }

    /** Close cleanly and stop any retry. Safe to call when idle. */
    @Synchronized
    fun disconnect() {
        running.set(false)
        runCatching { writeQueue.put(POISON) }
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var backoffIdx = 0
        while (running.get()) {
            val lived = serveOnce()
            // A connection that lived resets the backoff; a failed dial
            // lengthens it (2 s, 5 s, 15 s, 30 s cap).
            backoffIdx = if (lived) 0 else minOf(backoffIdx + 1, BACKOFF_MS.size - 1)
            if (!running.get() || !autoReconnect) break
            try {
                Thread.sleep(BACKOFF_MS[backoffIdx])
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /**
     * One connect + serve cycle. Returns true when a connection lived (even
     * briefly); false when the dial/handshake itself failed.
     */
    private fun serveOnce(): Boolean {
        val trust = ShareCrypto.ClientTrust(pinnedFp)
        val ctx: javax.net.ssl.SSLContext
        try {
            ctx = ShareCrypto.clientSslContext(identity, trust)
        } catch (e: Exception) {
            onError("tls setup: ${e.message}")
            return false
        }
        val sock: SSLSocket
        try {
            val raw = ctx.socketFactory.createSocket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock = raw as SSLSocket
            sock.tcpNoDelay = true
            sock.startHandshake()
        } catch (e: Exception) {
            runCatching { /* raw socket closed by factory on failure paths */ }
            onError("connect $host:$port: ${e.message}")
            onDisconnected(autoReconnect && running.get())
            return false
        }
        val fp = trust.lastPeerFp
        if (fp == null) {
            runCatching { sock.close() }
            onError("no peer certificate")
            onDisconnected(autoReconnect && running.get())
            return false
        }
        lastPeerFp = fp
        socket = sock
        out = runCatching { sock.getOutputStream() }.getOrNull()
        seq.set(0)
        connected = true
        onConnected(fp)

        val decoder = Frames.Decoder()
        val lastRx = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val writer = Thread({
            try {
                while (running.get()) {
                    val bytes = writeQueue.take()
                    if (bytes.isEmpty()) break // POISON
                    val o = out ?: break
                    o.write(bytes)
                    o.flush()
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
                runCatching { sock.close() }
            }
        }, "companion-writer")
        writer.isDaemon = true
        writer.start()

        var lived = true
        try {
            sock.soTimeout = 1_000
            val input = sock.getInputStream()
            var nextBeat = System.currentTimeMillis() + HEARTBEAT_MS
            val buf = ByteArray(64 * 1024)
            while (running.get() && !sock.isClosed) {
                try {
                    val n = input.read(buf)
                    if (n < 0) throw java.io.EOFException("peer closed")
                    lastRx.set(System.currentTimeMillis())
                    decoder.feed(buf, 0, n).forEach { onMessage(it) }
                } catch (e: java.net.SocketTimeoutException) {
                    // Tick: heartbeat out, silence check in.
                }
                if (System.currentTimeMillis() - lastRx.get() > SILENCE_LIMIT_MS) {
                    throw java.io.IOException("60s silence")
                }
                val now = System.currentTimeMillis()
                if (now >= nextBeat) {
                    send(JSONObject().put("t", "heartbeat"))
                    nextBeat = now + HEARTBEAT_MS
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (running.get()) onError(msg)
        } finally {
            connected = false
            runCatching { writeQueue.put(POISON) }
            runCatching { sock.close() }
            socket = null
            out = null
            writer.interrupt()
            if (running.get()) onDisconnected(autoReconnect)
        }
        return lived
    }
}
