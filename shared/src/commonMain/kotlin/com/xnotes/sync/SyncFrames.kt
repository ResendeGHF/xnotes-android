package com.xnotes.sync

import com.xnotes.format.CharSink
import com.xnotes.format.CharSource
import com.xnotes.format.JsonPull
import com.xnotes.format.JsonWrite

/**
 * SYNC.md v1 framing, companion side: 4-byte big-endian length + UTF-8 JSON,
 * 1..16 MiB. Messages are [SyncMsg]; unknown `t` values decode as
 * [SyncMsg.Unknown] and are ignored by the engine (forward compat).
 */
object SyncFrames {
    /** Largest frame accepted, 16 MiB (a big file chunk still fits). */
    const val MAX_FRAME = 16 * 1024 * 1024

    /** A message as wire bytes. */
    fun encode(msg: SyncMsg): ByteArray {
        val sink = StringSink(StringBuilder())
        val w = JsonWrite(sink)
        writeSyncBody(w, msg)
        sink.flush()
        val body = sink.sb.toString().encodeToByteArray()
        require(body.size in 1..MAX_FRAME) { "frame size ${body.size}" }
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, 4)
        return out
    }

    /** One message from its JSON bytes. */
    fun decode(bytes: ByteArray): SyncMsg = parseSyncBody(JsonPull(StringSource(bytes.decodeToString())))

    /**
     * Incremental decoder for socket reads: feed whatever arrived, take whole
     * messages. Malformed lengths throw; callers close the connection then.
     */
    class Decoder {
        private var pending = ByteArray(0)

        fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<SyncMsg> {
            pending += bytes.sliceArray(from until to)
            val out = ArrayList<SyncMsg>()
            var at = 0
            while (pending.size - at >= 4) {
                val n = ((pending[at].toInt() and 0xFF) shl 24) or
                    ((pending[at + 1].toInt() and 0xFF) shl 16) or
                    ((pending[at + 2].toInt() and 0xFF) shl 8) or
                    (pending[at + 3].toInt() and 0xFF)
                require(n in 1..MAX_FRAME) { "bad frame length $n" }
                if (pending.size - at - 4 < n) break
                out += decode(pending.sliceArray(at + 4 until at + 4 + n))
                at += 4 + n
            }
            pending = if (at < pending.size) pending.sliceArray(at until pending.size) else ByteArray(0)
            return out
        }
    }

    private class StringSink(val sb: StringBuilder) : CharSink {
        override fun write(s: String) {
            sb.append(s)
        }

        override fun write(c: Int) {
            sb.append(c.toChar())
        }

        override fun write(chars: CharArray, offset: Int, length: Int) {
            sb.appendRange(chars, offset, offset + length)
        }

        override fun flush() = Unit
    }

    private class StringSource(val s: String) : CharSource {
        private var at = 0

        override fun read(into: CharArray): Int {
            if (at >= s.length) return -1
            val n = minOf(into.size, s.length - at)
            s.toCharArray(into, 0, at, at + n)
            at += n
            return n
        }
    }
}

/** SYNC.md v1 handshake messages (index/fetch/op/live follow in later slices). */
sealed interface SyncMsg {
    val seq: Int

    data class Hello(
        override val seq: Int,
        val app: String,
        val version: Int,
        val deviceName: String,
        val hostCertFp: String,
    ) : SyncMsg

    data class HelloAck(
        override val seq: Int,
        val inReplyTo: Int,
        val companionCertFp: String,
        val paired: Boolean,
        val codeRequired: Boolean,
    ) : SyncMsg

    data class PairRequest(
        override val seq: Int,
        val deviceName: String,
        val hostCertFp: String,
        val code: String,
    ) : SyncMsg

    data class PairResult(
        override val seq: Int,
        val inReplyTo: Int,
        val ok: Boolean,
        val reason: String?,
    ) : SyncMsg

    data class Unknown(val t: String, override val seq: Int) : SyncMsg

    data class Index(
        override val seq: Int,
        val inReplyTo: Int,
        val full: Boolean,
        val files: List<SyncFileEntry>,
    ) : SyncMsg

    data class Fetch(
        override val seq: Int,
        val id: String,
        val offset: Long,
        val length: Long,
    ) : SyncMsg

    data class FileChunk(
        override val seq: Int,
        val id: String,
        val version: Long,
        val total: Long,
        val offset: Long,
        val data: String,
        val last: Boolean,
    ) : SyncMsg

    /**
     * One chunk of an image's encoded bytes, keyed by [imageId]. Chunks precede
     * the `add_image` op on the wire; the op applies once all bytes are in.
     */
    data class ImageChunk(
        override val seq: Int,
        val file: String,
        val imageId: String,
        val total: Long,
        val offset: Long,
        val data: String,
        val last: Boolean,
    ) : SyncMsg

    data class Op(
        override val seq: Int,
        val file: String,
        val baseVersion: Long,
        val version: Long,
        val dedupe: String,
        val ops: List<SyncOp>,
    ) : SyncMsg

    data class OpAck(
        override val seq: Int,
        val inReplyTo: Int,
        val file: String,
        val version: Long,
    ) : SyncMsg

    data class Resync(
        override val seq: Int,
        val file: String,
        val version: Long,
    ) : SyncMsg

    data class Live(
        override val seq: Int,
        val file: String,
        val page: Int,
        val strokeId: String,
        val tool: String,
        val width: Double,
        val color: String,
        val samples: List<WireSample>,
        val done: Boolean,
    ) : SyncMsg

    /**
     * A room drag's live preview: the cut ([split], [resume]) and [shift] the far
     * side shows without touching its model, keyed by [liveId]; [done] drops it.
     * The matching committed room op (same id as dedupe) also drops it.
     */
    data class RoomLive(
        override val seq: Int,
        val file: String,
        val page: Int,
        val sideways: Boolean,
        val split: Double,
        val resume: Double,
        val shift: Double,
        val liveId: String,
        val done: Boolean,
    ) : SyncMsg

    data class Presence(
        override val seq: Int,
        val file: String,
        val page: Int,
    ) : SyncMsg

    data class Heartbeat(override val seq: Int) : SyncMsg

    data class IndexSubscribe(override val seq: Int) : SyncMsg

    data class DeleteRequest(override val seq: Int, val id: String, val device: String) : SyncMsg

    data class DeleteResult(
        override val seq: Int,
        val inReplyTo: Int,
        val ok: Boolean,
        val reason: String?,
    ) : SyncMsg

    data class CreateRequest(
        override val seq: Int,
        val device: String,
        val dirId: String?,
        val name: String,
        val kind: Int,
    ) : SyncMsg

    data class CreateResult(
        override val seq: Int,
        val inReplyTo: Int,
        val ok: Boolean,
        val id: String?,
        val reason: String?,
    ) : SyncMsg

    /**
     * Ask the host for access to [id] (a note link's target): the host shows
     * an approval, grants on allow, and answers [LinkResult].
     */
    data class LinkRequest(override val seq: Int, val id: String, val device: String) : SyncMsg

    data class LinkResult(
        override val seq: Int,
        val inReplyTo: Int,
        val id: String,
        val ok: Boolean,
        val reason: String?,
    ) : SyncMsg
}
