package com.xnotes.share.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * SYNC.md v1 framing: 4-byte big-endian length + UTF-8 JSON, 1..16 MiB.
 * Pure JVM (org.json), so it unit tests on the host.
 */
object Frames {
    /** Largest frame accepted, 16 MiB (a big file chunk still fits). */
    const val MAX_FRAME = 16 * 1024 * 1024

    /** [msg] as wire bytes. */
    fun encode(msg: JSONObject): ByteArray {
        val body = msg.toString().toByteArray(Charsets.UTF_8)
        require(body.size in 1..MAX_FRAME) { "frame size ${body.size}" }
        return ByteBuffer.allocate(4 + body.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(body.size).put(body).array()
    }

    /**
     * Incremental decoder for socket reads: feed whatever arrived, take whole
     * messages. Malformed lengths throw; callers close the connection then.
     */
    class Decoder {
        private val pending = ByteArrayOutputStream()

        /** Bytes fed so far that do not yet make a frame (for close-time logs). */
        val buffered: Int get() = pending.size()

        fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<JSONObject> {
            pending.write(bytes, from, to - from)
            val out = ArrayList<JSONObject>()
            val buf = pending.toByteArray()
            var at = 0
            while (buf.size - at >= 4) {
                val n = ByteBuffer.wrap(buf, at, 4).order(ByteOrder.BIG_ENDIAN).int
                require(n in 1..MAX_FRAME) { "bad frame length $n" }
                if (buf.size - at - 4 < n) break
                out += JSONObject(String(buf, at + 4, n, Charsets.UTF_8))
                at += 4 + n
            }
            pending.reset()
            if (at < buf.size) pending.write(buf, at, buf.size - at)
            return out
        }
    }
}
