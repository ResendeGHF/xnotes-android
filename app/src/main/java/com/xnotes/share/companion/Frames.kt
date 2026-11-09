package com.xnotes.share.companion

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
        private var pending = ByteArray(0)
        private var at = 0

        /** Bytes fed so far that do not yet make a frame (for close-time logs). */
        val buffered: Int get() = pending.size - at

        fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<JSONObject> {
            if (at > 0) {
                // A previous feed ended mid-message: drop the consumed prefix once here
                // instead of copying it on every feed.
                pending = pending.copyOfRange(at, pending.size)
                at = 0
            }
            pending += bytes.sliceArray(from until to)
            val out = ArrayList<JSONObject>()
            while (pending.size - at >= 4) {
                val n = ByteBuffer.wrap(pending, at, 4).order(ByteOrder.BIG_ENDIAN).int
                require(n in 1..MAX_FRAME) { "bad frame length $n" }
                if (pending.size - at - 4 < n) break
                out += JSONObject(String(pending, at + 4, n, Charsets.UTF_8))
                at += 4 + n
            }
            if (at >= pending.size) {
                pending = ByteArray(0)
                at = 0
            } else if (at > 65536) {
                pending = pending.copyOfRange(at, pending.size)
                at = 0
            }
            return out
        }
    }
}
