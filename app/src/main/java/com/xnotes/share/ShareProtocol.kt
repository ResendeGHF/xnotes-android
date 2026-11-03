package com.xnotes.share

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Wire constants and small pure-JVM helpers for the share server's HTTPS API.
 *
 * The API is deliberately boring HTTP so any client (the bundled Linux
 * companion, curl, Firefox) can speak it:
 *
 * - `GET  /api/status` — `{name, version, lockedFiles}` (auth required).
 * - `GET  /api/files` — `[{uri, name, size, mtime, kind}]`.
 * - `GET  /api/version?uri=` — `{uri, version}` where version is an ETag
 *   derived from the committed file bytes. It changes only when a full write
 *   lands (never mid-stroke), so a client that re-fetches on change sees
 *   exactly what the device saved.
 * - `GET  /api/pdf?uri=` — the exported PDF snapshot (`ETag`, `Range` and
 *   `If-None-Match` supported). Regenerated only when the version changed.
 * - `GET  /api/raw?uri=` — the note bundle / file bytes as stored.
 * - `POST /api/lock?uri=&holder=` — take the per-file edit lease.
 * - `POST /api/heartbeat` (`leaseId`, `holder` form fields) — renew it.
 * - `DELETE /api/lock?lease=&holder=` — release it.
 * - `PUT  /api/pdf?uri=&base=` — replace the PDF (lease + base-version
 *   checked). `X-Lease: <leaseId>` carries the lease.
 * - `GET  /api/watch?uri=&since=` — long-poll (≤ 25 s) until the committed
 *   version differs from `since`, then `{uri, version, changed}`. This is how
 *   the desktop learns about device commits in near real time without the
 *   server ever streaming live strokes.
 *
 * Everything under `/api/` needs `Authorization: Bearer <token>`. The server
 * speaks TLS only; there is no plaintext listener.
 */
object ShareProtocol {
    /** Default port; picked to avoid clashing with common dev servers. */
    const val DEFAULT_PORT = 42317

    /** mDNS service type announced on the LAN while sharing is on. */
    const val MDNS_TYPE = "_xnoteshare._tcp."

    /** Long-poll ceiling; under typical 30 s proxy timeouts. */
    const val WATCH_TIMEOUT_MS = 25_000L

    /** Largest single PUT the server accepts (a scanned-PDF note stays far below). */
    const val MAX_PUT_BYTES = 128L * 1024 * 1024

    /** Query/form parameter and header names. */
    const val Q_URI = "uri"
    const val Q_HOLDER = "holder"
    const val Q_LEASE = "lease"
    const val Q_BASE = "base"
    const val Q_SINCE = "since"
    const val HEADER_LEASE = "X-Lease"

    /** Opaque version for a committed file: size + mtime, quoted like an HTTP ETag. */
    fun etagFor(size: Long, mtime: Long): String = "\"$size-$mtime\""

    /** Constant-time bearer comparison so token checks don't leak prefix length. */
    fun bearerToken(authorization: String?): String? {
        if (authorization == null) return null
        val prefix = "Bearer "
        if (!authorization.startsWith(prefix, ignoreCase = true)) return null
        return authorization.substring(prefix.length).trim().ifEmpty { null }
    }

    fun tokensEqual(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** Parse `a=1&b=2` (query string or urlencoded body) into a map. */
    fun parseParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key: String
            val value: String
            if (eq < 0) {
                key = decode(pair)
                value = ""
            } else {
                key = decode(pair.substring(0, eq))
                value = decode(pair.substring(eq + 1))
            }
            out.putIfAbsent(key, value)
        }
        return out
    }

    private fun decode(s: String): String = runCatching {
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    }.getOrDefault(s)

    /** A byte range from a `Range: bytes=start-end` header, clamped to [length]. */
    data class ByteRange(val start: Long, val endInclusive: Long) {
        val contentLength: Long get() = endInclusive - start + 1
    }

    fun parseRange(header: String?, length: Long): ByteRange? {
        if (header == null || length <= 0) return null
        val spec = header.trim()
        if (!spec.startsWith("bytes=")) return null
        val range = spec.removePrefix("bytes=").split(',').firstOrNull()?.trim() ?: return null
        return try {
            if (range.startsWith("-")) {
                val suffix = range.drop(1).toLong()
                if (suffix <= 0) return null
                val start = maxOf(0L, length - suffix)
                ByteRange(start, length - 1)
            } else {
                val dash = range.indexOf('-')
                if (dash < 0) return null
                val start = range.substring(0, dash).toLong()
                val end = range.substring(dash + 1).takeIf { it.isNotEmpty() }?.toLong() ?: (length - 1)
                if (start < 0 || start >= length || end < start) return null
                ByteRange(start, minOf(end, length - 1))
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Minimal JSON string escaping for the hand-built responses. */
    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        206 -> "Partial Content"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        412 -> "Precondition Failed"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        423 -> "Locked"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }
}
