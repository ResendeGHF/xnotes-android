package com.xnotes.share.companion

import com.xnotes.share.ShareCrypto
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * Inbound TLS listener so this Android device can *host* files for Android
 * peers (SYNC.md v1, same wire as the Linux companion path).
 *
 * Mutual TLS: the socket presents this device's identity and *requires* a
 * client certificate. The TLS trust manager accepts any client cert and
 * records its fingerprint; the application layer ([HostHandshake] / Room
 * pins) decides whether that fingerprint may proceed past pairing.
 *
 * Port: binds [basePort], bumping until free ([basePort]..[basePort]+29), so
 * two phones on one LAN (or a phone next to a Linux companion on 42317) can
 * all listen at once. The bound port is [boundPort].
 *
 * Pure JVM (no android.*), so it unit-tests on the host.
 */
class AndroidListener(
    private val identity: ShareCrypto.ServerIdentity,
    private val basePort: Int = DEFAULT_PORT,
    var onPeer: (peerFp: String, conn: PeerConn) -> Unit = { _, _ -> },
    var onMessage: (peerFp: String, msg: JSONObject) -> Unit = { _, _ -> },
    var onLost: (peerFp: String) -> Unit = {},
) {
    companion object {
        const val DEFAULT_PORT = 42317
        const val PORT_TRIES = 30
        private const val HEARTBEAT_MS = 20_000L
        private const val SILENCE_LIMIT_MS = 60_000L
    }

    /** Outbound handle for one accepted peer. */
    class PeerConn internal constructor(
        val peerFp: String,
        /** Peer's IP (socket remote); its listener port comes from `hello`. */
        val remoteHost: String,
        private val sendFn: (JSONObject) -> Unit,
        private val closeFn: () -> Unit,
    ) {
        fun send(msg: JSONObject) = sendFn(msg)
        fun close() = closeFn()
    }

    private val running = AtomicBoolean(false)
    private var server: SSLServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile var boundPort: Int = -1
        private set

    private val peers = ConcurrentHashMap<String, PeerState>()

    private class PeerState(
        val socket: SSLSocket,
        val queue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(),
    ) {
        @Volatile var alive = true
    }

    /** Bind and start accepting. Throws when no port in range is free. */
    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        val recorder = RecordingTrust()
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            val ks = java.security.KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("share", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
            }
            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm(),
            ).apply { init(ks, CharArray(0)) }
            init(kmf.keyManagers, arrayOf(recorder), java.security.SecureRandom())
        }
        var lastErr: Exception? = null
        for (p in basePort until basePort + PORT_TRIES) {
            try {
                val ss = ctx.serverSocketFactory.createServerSocket(
                    p, 50, java.net.InetAddress.getByName("0.0.0.0"),
                ) as SSLServerSocket
                ss.needClientAuth = true
                server = ss
                boundPort = p
                lastErr = null
                break
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (server == null) {
            running.set(false)
            throw lastErr ?: java.io.IOException("no free port near $basePort")
        }
        acceptThread = Thread(::acceptLoop, "android-listener").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        for ((_, st) in peers) {
            st.alive = false
            runCatching { st.queue.put(ByteArray(0)) }
            runCatching { st.socket.close() }
        }
        peers.clear()
        acceptThread?.interrupt()
        acceptThread = null
        boundPort = -1
    }

    /** Drop one peer (unpair/kick wiring calls this). */
    fun drop(peerFp: String) {
        peers.remove(peerFp.lowercase())?.let { st ->
            st.alive = false
            runCatching { st.queue.put(ByteArray(0)) }
            runCatching { st.socket.close() }
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val raw = try {
                server?.accept() as? SSLSocket ?: break
            } catch (_: Exception) {
                break
            }
            Thread({ serve(raw) }, "android-peer").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(sock: SSLSocket) {
        sock.tcpNoDelay = true
        val fp: String
        try {
            sock.startHandshake()
            val chain = sock.session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .firstOrNull()
                ?: throw javax.net.ssl.SSLPeerUnverifiedException("no client certificate")
            fp = ShareCrypto.fingerprintOf(chain).lowercase()
        } catch (_: Exception) {
            runCatching { sock.close() }
            return
        }
        val key = fp.lowercase()
        // One connection per peer: the newcomer wins, the stale socket goes.
        peers.remove(key)?.let { old ->
            old.alive = false
            runCatching { old.queue.put(ByteArray(0)) }
            runCatching { old.socket.close() }
        }
        val state = PeerState(sock)
        peers[key] = state
        val remoteHost = runCatching { (sock.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "" }.getOrDefault("")
        val peerConn = PeerConn(fp, remoteHost, sendFn = { msg ->
            if (state.alive) {
                if (!msg.has("seq")) msg.put("seq", 0)
                runCatching { state.queue.put(Frames.encode(msg)) }
            }
        }, closeFn = {
            state.alive = false
            runCatching { state.queue.put(ByteArray(0)) }
            runCatching { sock.close() }
        })
        val writer = Thread({
            try {
                val out = sock.outputStream
                while (state.alive) {
                    val bytes = state.queue.take()
                    if (bytes.isEmpty()) break
                    out.write(bytes)
                    out.flush()
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
                runCatching { sock.close() }
            }
        }, "android-peer-writer").apply {
            isDaemon = true
            start()
        }
        onPeer(fp, peerConn)
        val decoder = Frames.Decoder()
        var lastRx = System.currentTimeMillis()
        var nextBeat = lastRx + HEARTBEAT_MS
        try {
            sock.soTimeout = 1_000
            val input = sock.inputStream
            val buf = ByteArray(64 * 1024)
            while (running.get() && state.alive && !sock.isClosed) {
                try {
                    val n = input.read(buf)
                    if (n < 0) throw java.io.EOFException("peer closed")
                    lastRx = System.currentTimeMillis()
                    decoder.feed(buf, 0, n).forEach { onMessage(fp, it) }
                } catch (e: java.net.SocketTimeoutException) {
                    // Tick below.
                }
                if (System.currentTimeMillis() - lastRx > SILENCE_LIMIT_MS) {
                    throw java.io.IOException("60s silence")
                }
                val now = System.currentTimeMillis()
                if (now >= nextBeat) {
                    peerConn.send(JSONObject().put("t", "heartbeat"))
                    nextBeat = now + HEARTBEAT_MS
                }
            }
        } catch (_: Exception) {
        } finally {
            state.alive = false
            runCatching { state.queue.put(ByteArray(0)) }
            runCatching { sock.close() }
            writer.interrupt()
            if (peers.remove(key, state)) onLost(fp)
        }
    }

    /** TLS-level trust: accept any client cert, remember its fingerprint. */
    private class RecordingTrust : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }
}
