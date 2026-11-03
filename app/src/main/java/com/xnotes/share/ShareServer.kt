package com.xnotes.share

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * The device's LAN share endpoint. TLS only — no plaintext socket exists —
 * speaking the small HTTP API described in [ShareProtocol].
 *
 * The server never streams live strokes: [ShareBackend.versionOf] changes only
 * when a full committed write lands, the PDF snapshot is (re)generated from
 * that committed state, and `/api/watch` wakes clients only on such commits.
 * Realtime here means "the desktop learns about a saved change within
 * seconds", not keystroke sync.
 */
class ShareServer(
    port: Int,
    sslContext: SSLContext,
    private val backend: ShareBackend,
    private val locks: ShareLockManager,
    private val token: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Sink for one line per request (`METHOD path -> status bytes ms`). Android passes Log.d. */
    private val logger: (String) -> Unit = {},
) : java.io.Closeable {
    /** Storage + export work the Android service provides; pure JVM surface for tests. */
    interface ShareBackend {
        data class Entry(val uri: String, val name: String, val size: Long, val mtime: Long, val kind: String, val isDir: Boolean = false)

        /** Children of [dirUri], or of the granted root when null. Directories come first. */
        fun listEntries(dirUri: String?, onlyShared: Boolean = true): List<Entry>
        /** False when the app has no explorer folder granted yet, so there is nothing to list. */
        fun browseRootSet(): Boolean = true
        /** False when [uri] is not selected for sharing (unshared URIs behave as missing). */
        fun isShared(uri: String): Boolean = true

        /** Page sizes and scale for the companion canvas, or null when unavailable. */
        data class PageSpec(val w: Double, val h: Double)
        data class CanvasInfo(val version: String, val title: String, val dpi: Int, val pages: List<PageSpec>)
        fun canvasInfo(uri: String): CanvasInfo? = null

        /** One exported page rasterized to PNG, or null when unavailable. */
        data class PagePng(val bytes: ByteArray, val version: String, val page: Int, val dpi: Int)
        fun pagePng(uri: String, page: Int, dpi: Int): PagePng? = null

        /** The page's strokes as companion wire polylines, with the version they belong to. */
        data class StrokesPage(val strokes: List<ShareStrokes.WireStroke>, val version: String)
        fun pageStrokes(uri: String, page: Int): StrokesPage? = null

        /** Append companion strokes as native items (sealed rewrite). Lease-checked by the route. */
        fun appendStrokes(uri: String, page: Int, baseVersion: String, strokes: List<ShareStrokes.WireStroke>): ReplaceResult =
            ReplaceResult.Rejected(501, "unsupported")
        fun versionOf(uri: String): String?
        fun pdfBytes(uri: String): PdfSnapshot?
        fun rawBytes(uri: String): RawSnapshot?
        fun replacePdf(uri: String, bytes: ByteArray, baseVersion: String): ReplaceResult

        /** Object notified (notifyAll) after every committed write. */
        fun monitor(): Any
    }

    data class PdfSnapshot(val bytes: ByteArray, val version: String, val fileName: String)
    data class RawSnapshot(val bytes: ByteArray, val version: String, val fileName: String, val mime: String)

    sealed interface ReplaceResult {
        data class Ok(val version: String) : ReplaceResult
        data class Rejected(val status: Int, val message: String) : ReplaceResult
    }

    private val serverSocket: SSLServerSocket =
        sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "share-conn").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(true)
    private val acceptThread: Thread

    /** Consecutive auth-failure timestamps, for public-network brute-force delay. */
    private val authFailures = ArrayDeque<Long>()

    /** The port actually bound (useful when [port] was 0). */
    val boundPort: Int get() = serverSocket.localPort

    init {
        val supported = serverSocket.supportedProtocols.toSet()
        val wanted = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        if (wanted.isNotEmpty()) serverSocket.enabledProtocols = wanted.toTypedArray()
        acceptThread = Thread(::acceptLoop, "share-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    /** Alias for [stop] so tests can use `use {}`. */
    override fun close() = stop()

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept() as Socket
            } catch (_: Exception) {
                return
            }
            pool.execute {
                try {
                    handle(socket)
                } catch (_: Exception) {
                    // Per-connection failures must never take the listener down.
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
    }

    // --- request handling ---

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val started = nowMs()
        val request = readRequest(input) ?: run {
            writeStatus(output, 400, "text/plain", "bad request".toByteArray())
            logger("?? -> 400 (${nowMs() - started}ms, unreadable head)")
            return
        }
        if (!request.path.startsWith("/api/")) {
            writeStatus(output, 404, "application/json", jsonError("unknown path"))
            logger("${request.method} ${request.path} -> 404 (${nowMs() - started}ms)")
            return
        }
        if (!authed(request)) {
            authDelay()
            val body = jsonError("unauthorized")
            output.write(responseHead(401, "application/json", body.size.toLong(), extra = listOf("WWW-Authenticate: Bearer")))
            output.write(body)
            output.flush()
            logger("${request.method} ${request.path} -> 401 (${nowMs() - started}ms)")
            return
        }
        route(request, output)
        logger("${request.method} ${request.path} -> done body=${request.body.size} (${nowMs() - started}ms)")
    }

    private fun authed(request: Request): Boolean {
        val got = ShareProtocol.bearerToken(request.headers["authorization"]) ?: return false
        return ShareProtocol.tokensEqual(got, token)
    }

    /** After repeated failures, stall the attempt: cheap, effective on a LAN. */
    private fun authDelay() {
        val now = nowMs()
        val recent: Int
        synchronized(authFailures) {
            while (authFailures.isNotEmpty() && now - authFailures.first() > 60_000L) authFailures.removeFirst()
            authFailures.addLast(now)
            recent = authFailures.size
        }
        if (recent > 5) Thread.sleep(2_000L)
    }

    private fun route(request: Request, output: java.io.OutputStream) {
        when (request.path) {
            "/api/status" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val locked = backend.listEntries(null).mapNotNull { locks.holderOf(it.uri)?.uri }.distinct()
                val body = "{\"name\":\"xnotes-share\",\"api\":1,\"locked\":[" +
                    locked.joinToString(",") { ShareProtocol.jsonString(it) } + "]}"
                writeJson(output, 200, body)
            }
            "/api/files" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val rootSet = backend.browseRootSet()
                val dirUri = request.query[ShareProtocol.Q_URI]?.ifEmpty { null }
                val body = buildString {
                    append("{\"root\":$rootSet,")
                    append("\"dir\":${ShareProtocol.jsonString(dirUri ?: "")},\"files\":[")
                    backend.listEntries(dirUri).forEachIndexed { i, e ->
                        if (i > 0) append(',')
                        append("{\"uri\":").append(ShareProtocol.jsonString(e.uri))
                        append(",\"name\":").append(ShareProtocol.jsonString(e.name))
                        append(",\"size\":").append(e.size)
                        append(",\"mtime\":").append(e.mtime)
                        append(",\"kind\":").append(ShareProtocol.jsonString(e.kind))
                        if (e.isDir) append(",\"dir\":true")
                        val holder = locks.holderOf(e.uri)?.holder
                        if (holder != null) append(",\"lockedBy\":").append(ShareProtocol.jsonString(holder))
                        append('}')
                    }
                    append("]}")
                }
                writeJson(output, 200, body)
            }
            "/api/version" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                val version = backend.versionOf(uri) ?: return notFound(output, "no such file")
                writeJson(output, 200, "{\"uri\":${ShareProtocol.jsonString(uri)},\"version\":${ShareProtocol.jsonString(version)}}")
            }
            "/api/pdf" -> when (request.method) {
                "GET" -> servePdf(request, output)
                "PUT" -> receivePdf(request, output)
                else -> methodNotAllowed(output)
            }
            "/api/raw" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                val snap = backend.rawBytes(uri) ?: return notFound(output, "no such file")
                serveBytes(output, snap.bytes, snap.mime, snap.version, request.headers["if-none-match"], request.headers["range"])
            }
            "/api/canvas" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                val info = backend.canvasInfo(uri) ?: return notFound(output, "no canvas for file")
                val pages = info.pages.joinToString(",") { "{\"w\":${it.w},\"h\":${it.h}}" }
                writeJson(output, 200, "{\"uri\":${ShareProtocol.jsonString(uri)}," +
                    "\"version\":${ShareProtocol.jsonString(info.version)}," +
                    "\"title\":${ShareProtocol.jsonString(info.title)}," +
                    "\"dpi\":${info.dpi},\"pages\":[$pages]}")
            }
            "/api/page" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                val page = request.query["page"]?.toIntOrNull() ?: 0
                val dpi = request.query["dpi"]?.toIntOrNull() ?: 110
                val snap = backend.pagePng(uri, page, dpi) ?: return notFound(output, "no page")
                serveBytes(output, snap.bytes, "image/png", snap.version, request.headers["if-none-match"], null)
            }
            "/api/strokes" -> when (request.method) {
                "GET" -> {
                    val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                    val page = request.query["page"]?.toIntOrNull() ?: 0
                    val since = request.query[ShareProtocol.Q_SINCE] ?: ""
                    val snap = backend.pageStrokes(uri, page) ?: return notFound(output, "no strokes for file")
                    val changed = snap.version != since
                    val body = buildString {
                        append("{\"uri\":${ShareProtocol.jsonString(uri)},\"page\":$page,")
                        append("\"version\":${ShareProtocol.jsonString(snap.version)},\"changed\":$changed,")
                        append("\"strokes\":[")
                        if (changed) {
                            snap.strokes.forEachIndexed { i, s ->
                                if (i > 0) append(',')
                                append("{\"tool\":${ShareProtocol.jsonString(s.tool)},")
                                append("\"color\":${ShareProtocol.jsonString(s.color)},")
                                append("\"width\":${s.width},\"pts\":[")
                                append(s.points.joinToString(","))
                                append("]}")
                            }
                        }
                        append("]}")
                    }
                    writeJson(output, 200, body)
                }
                "POST" -> receiveStrokes(request, output)
                else -> methodNotAllowed(output)
            }
            "/api/lock" -> when (request.method) {
                "POST" -> {
                    val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                    val holder = request.query[ShareProtocol.Q_HOLDER]?.takeIf { it.isNotBlank() }
                        ?: return badRequest(output, "missing holder")
                    val lease = locks.acquire(uri, holder)
                    if (lease == null) {
                        val by = locks.holderOf(uri)?.holder ?: "someone"
                        writeJson(output, 423, "{\"error\":\"locked\",\"lockedBy\":${ShareProtocol.jsonString(by)}}")
                    } else {
                        writeJson(output, 200, "{\"lease\":${ShareProtocol.jsonString(lease.leaseId)},\"expires\":${lease.expiresAtMs}}")
                    }
                }
                "DELETE" -> {
                    val lease = request.query[ShareProtocol.Q_LEASE] ?: return badRequest(output, "missing lease")
                    val holder = request.query[ShareProtocol.Q_HOLDER] ?: return badRequest(output, "missing holder")
                    val released = locks.release(lease, holder)
                    writeJson(output, 200, "{\"released\":$released}")
                }
                else -> methodNotAllowed(output)
            }
            "/api/heartbeat" -> {
                if (request.method != "POST") return methodNotAllowed(output)
                val params = ShareProtocol.parseParams(request.body.toString(Charsets.UTF_8)) + request.query
                val lease = params[ShareProtocol.Q_LEASE] ?: return badRequest(output, "missing lease")
                val holder = params[ShareProtocol.Q_HOLDER] ?: return badRequest(output, "missing holder")
                val renewed = locks.renew(lease, holder)
                if (renewed == null) notFound(output, "lease expired")
                else writeJson(output, 200, "{\"lease\":${ShareProtocol.jsonString(renewed.leaseId)},\"expires\":${renewed.expiresAtMs}}")
            }
            "/api/watch" -> {
                if (request.method != "GET") return methodNotAllowed(output)
                val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
                val since = request.query[ShareProtocol.Q_SINCE] ?: ""
                watch(request, output, uri, since)
            }
            else -> notFound(output, "unknown api")
        }
    }

    private fun servePdf(request: Request, output: java.io.OutputStream) {
        val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
        val snap = backend.pdfBytes(uri) ?: return notFound(output, "no pdf for file")
        val extra = listOf("Content-Disposition: attachment; filename=\"${asciiFileName(snap.fileName)}\"")
        serveBytes(output, snap.bytes, "application/pdf", snap.version, request.headers["if-none-match"], request.headers["range"], extra)
    }

    private fun receiveStrokes(request: Request, output: java.io.OutputStream) {
        val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
        val page = request.query["page"]?.toIntOrNull() ?: 0
        leaseHolder(uri, request, output) ?: return
        if (request.body.isEmpty()) return badRequest(output, "empty body")
        val parsed = parseStrokePost(request.body) ?: return badRequest(output, "bad strokes json")
        when (val result = backend.appendStrokes(uri, page, parsed.first, parsed.second)) {
            is ReplaceResult.Ok ->
                writeJson(output, 200, "{\"uri\":${ShareProtocol.jsonString(uri)},\"page\":$page,\"version\":${ShareProtocol.jsonString(result.version)}}")
            is ReplaceResult.Rejected ->
                writeJson(output, result.status, "{\"error\":${ShareProtocol.jsonString(result.message)}}")
        }
    }

    /**
     * Parse a strokes POST body (`{"base": "...", "strokes": [{tool, color, width, pts}]}`).
     * Returns the base version plus the wire strokes, or null when malformed.
     * Validation of each stroke happens in [ShareStrokes.toNative], via the backend.
     */
    private fun parseStrokePost(body: ByteArray): Pair<String, List<ShareStrokes.WireStroke>>? {
        return try {
            val json = org.json.JSONObject(body.toString(Charsets.UTF_8))
            val base = json.optString("base", "")
            if (base.isEmpty()) return null
            val arr = json.optJSONArray("strokes") ?: return null
            if (arr.length() > ShareStrokes.MAX_STROKES_PER_POST) return null
            val out = ArrayList<ShareStrokes.WireStroke>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return null
                val ptsJson = o.optJSONArray("pts") ?: return null
                val pts = ArrayList<Double>(ptsJson.length())
                for (j in 0 until ptsJson.length()) {
                    val v = ptsJson.optDouble(j, Double.NaN)
                    if (!v.isFinite()) return null
                    pts.add(v)
                }
                out.add(
                    ShareStrokes.WireStroke(
                        tool = o.optString("tool", ""),
                        color = o.optString("color", ""),
                        width = o.optDouble("width", Double.NaN),
                        points = pts,
                    ),
                )
            }
            base to out
        } catch (_: Exception) {
            null
        }
    }

    /** The lease holder for [uri] from `X-Lease`, or null after answering the error. */
    private fun leaseHolder(uri: String, request: Request, output: java.io.OutputStream): String? {
        val leaseId = request.headers[ShareProtocol.HEADER_LEASE.lowercase()]
            ?: run { badRequest(output, "missing ${ShareProtocol.HEADER_LEASE}"); return null }
        val holder = locks.holderOf(uri)?.takeIf { it.leaseId == leaseId }?.holder
            ?: run { locked(output, "edit lease required"); return null }
        if (locks.isLockedByOther(uri, holder)) {
            locked(output, "locked by someone else")
            return null
        }
        return holder
    }

    private fun receivePdf(request: Request, output: java.io.OutputStream) {
        val uri = request.query[ShareProtocol.Q_URI] ?: return badRequest(output, "missing uri")
        val base = request.query[ShareProtocol.Q_BASE] ?: ""
        val leaseId = request.headers[ShareProtocol.HEADER_LEASE.lowercase()]
            ?: return badRequest(output, "missing ${ShareProtocol.HEADER_LEASE}")
        val holder = locks.holderOf(uri)?.takeIf { it.leaseId == leaseId }?.holder
            ?: return locked(output, "edit lease required")
        if (locks.isLockedByOther(uri, holder)) return locked(output, "locked by someone else")
        if (request.body.isEmpty()) return badRequest(output, "empty body")
        when (val result = backend.replacePdf(uri, request.body, base)) {
            is ReplaceResult.Ok ->
                writeJson(output, 200, "{\"uri\":${ShareProtocol.jsonString(uri)},\"version\":${ShareProtocol.jsonString(result.version)}}")
            is ReplaceResult.Rejected ->
                writeJson(output, result.status, "{\"error\":${ShareProtocol.jsonString(result.message)}}")
        }
    }

    /**
     * Long-poll until the *committed* version moves past [since] (or the
     * timeout elapses). Commits only — the backend bumps versions after full
     * writes, so mid-stroke states are never surfaced here.
     */
    private fun watch(request: Request, output: java.io.OutputStream, uri: String, since: String) {
        val monitor = backend.monitor()
        val deadline = nowMs() + ShareProtocol.WATCH_TIMEOUT_MS
        while (true) {
            val current = backend.versionOf(uri) ?: return notFound(output, "no such file")
            if (current != since) {
                writeJson(output, 200, "{\"uri\":${ShareProtocol.jsonString(uri)},\"version\":${ShareProtocol.jsonString(current)},\"changed\":true}")
                return
            }
            val remaining = deadline - nowMs()
            if (remaining <= 0) {
                writeJson(output, 200, "{\"uri\":${ShareProtocol.jsonString(uri)},\"version\":${ShareProtocol.jsonString(current)},\"changed\":false}")
                return
            }
            synchronized(monitor) {
                try {
                    (monitor as Object).wait(minOf(remaining, 1_000L))
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (requestSkipped(request)) return
        }
    }

    /** Best-effort: the socket closing mid-poll just ends the poll. */
    private fun requestSkipped(@Suppress("UNUSED_PARAMETER") request: Request): Boolean = false

    // --- byte serving ---

    private fun serveBytes(
        output: java.io.OutputStream,
        bytes: ByteArray,
        mime: String,
        version: String,
        ifNoneMatch: String?,
        rangeHeader: String?,
        extra: List<String> = emptyList(),
    ) {
        if (ifNoneMatch != null && ifNoneMatch == version) {
            output.write(responseHead(304, mime, -1, version, extra))
            output.flush()
            return
        }
        val range = ShareProtocol.parseRange(rangeHeader, bytes.size.toLong())
        if (rangeHeader != null && range == null) {
            val head = responseHead(416, mime, 0, version, extra + "Content-Range: bytes */${bytes.size}")
            output.write(head)
            output.flush()
            return
        }
        if (range == null) {
            output.write(responseHead(200, mime, bytes.size.toLong(), version, extra))
            output.write(bytes)
        } else {
            val slice = bytes.sliceArray(range.start.toInt()..range.endInclusive.toInt())
            val head = responseHead(
                206, mime, slice.size.toLong(), version,
                extra + "Content-Range: bytes ${range.start}-${range.endInclusive}/${bytes.size}",
            )
            output.write(head)
            output.write(slice)
        }
        output.flush()
    }

    private fun asciiFileName(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).trim('_')
        return clean.ifEmpty { "note.pdf" }
    }

    // --- small responses ---

    private fun writeJson(output: java.io.OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write(responseHead(status, "application/json; charset=utf-8", bytes.size.toLong()))
        output.write(bytes)
        output.flush()
    }

    private fun writeStatus(output: java.io.OutputStream, status: Int, mime: String, body: ByteArray) {
        output.write(responseHead(status, mime, body.size.toLong()))
        output.write(body)
        output.flush()
    }

    private fun responseHead(
        status: Int,
        mime: String,
        length: Long,
        etag: String? = null,
        extra: List<String> = emptyList(),
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(ShareProtocol.reason(status)).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        if (etag != null) sb.append("ETag: ").append(etag).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun jsonError(message: String): ByteArray =
        "{\"error\":${ShareProtocol.jsonString(message)}}".toByteArray(StandardCharsets.UTF_8)

    private fun badRequest(output: java.io.OutputStream, message: String) =
        writeJson(output, 400, "{\"error\":${ShareProtocol.jsonString(message)}}")

    private fun notFound(output: java.io.OutputStream, message: String) =
        writeJson(output, 404, "{\"error\":${ShareProtocol.jsonString(message)}}")

    private fun locked(output: java.io.OutputStream, message: String) =
        writeJson(output, 423, "{\"error\":${ShareProtocol.jsonString(message)}}")

    private fun methodNotAllowed(output: java.io.OutputStream) =
        writeJson(output, 405, "{\"error\":\"method not allowed\"}")

    // --- request parsing ---

    private fun readRequest(input: InputStream): Request? {
        return try {
        val head = readHead(input) ?: return null
        val headerEnd = head.indexOf("\r\n\r\n")
        if (headerEnd < 0) return null
        val headerText = head.substring(0, headerEnd)
        var body = head.substring(headerEnd + 4).toByteArray(StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(' ')
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) return null // chunked unsupported
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength < 0 || contentLength > ShareProtocol.MAX_PUT_BYTES) return null
        if (body.size > contentLength) return null
        val out = ByteArrayOutputStream()
        out.write(body)
        var remaining = contentLength - body.size
        val buf = ByteArray(256 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) return null
            out.write(buf, 0, n)
            remaining -= n
        }
        body = out.toByteArray()
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = ShareProtocol.parseParams(if (q < 0) null else target.substring(q + 1))
        Request(method, path, query, headers, body)
        } catch (_: Exception) {
            null
        }
    }

    /** Read up to the end of headers (plus any bytes already pipelined), capped. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val window = ByteArray(4)
        var matched = 0
        val expected = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < 64 * 1024) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (b == expected[matched].toInt()) {
                matched++
                if (matched == 4) return out.toString("ISO-8859-1")
            } else {
                matched = if (b == '\r'.code) 1 else 0
            }
            window[0] = 0 // keep the array referenced; matching is done via [matched]
        }
        return null
    }
}
