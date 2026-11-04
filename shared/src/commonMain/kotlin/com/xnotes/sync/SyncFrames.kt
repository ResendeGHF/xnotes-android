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
        when (msg) {
            is SyncMsg.Hello -> writeHello(w, msg)
            is SyncMsg.HelloAck -> writeHelloAck(w, msg)
            is SyncMsg.PairRequest -> writePairRequest(w, msg)
            is SyncMsg.PairResult -> writePairResult(w, msg)
            is SyncMsg.Unknown -> writeUnknown(w, msg)
        }
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
    fun decode(bytes: ByteArray): SyncMsg {
        val p = JsonPull(StringSource(bytes.decodeToString()))
        return readMsg(p)
    }

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

    private fun readMsg(p: JsonPull): SyncMsg {
        var t: String? = null
        var seq = 0
        var replyTo = -1
        var app = ""
        var version = 0
        var deviceName = ""
        var hostFp = ""
        var companionFp = ""
        var paired = false
        var codeRequired = false
        var code = ""
        var ok = false
        var reason: String? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "t" -> t = stringOrNull(p)
                "seq" -> seq = intOr(p, 0)
                "in_reply_to" -> replyTo = intOr(p, -1)
                "app" -> app = stringOr(p, "")
                "version" -> version = intOr(p, 0)
                "device_name" -> deviceName = stringOr(p, "")
                "host_cert_fp" -> hostFp = stringOr(p, "")
                "companion_cert_fp" -> companionFp = stringOr(p, "")
                "paired" -> paired = boolOr(p, false)
                "code_required" -> codeRequired = boolOr(p, false)
                "code" -> code = stringOr(p, "")
                "ok" -> ok = boolOr(p, false)
                "reason" -> reason = stringOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return when (t) {
            "hello" -> SyncMsg.Hello(seq, app, version, deviceName, hostFp)
            "hello_ack" -> SyncMsg.HelloAck(seq, replyTo, companionFp, paired, codeRequired)
            "pair_request" -> SyncMsg.PairRequest(seq, deviceName, hostFp, code)
            "pair_result" -> SyncMsg.PairResult(seq, replyTo, ok, reason)
            else -> SyncMsg.Unknown(t ?: "", seq)
        }
    }

    private fun stringOr(p: JsonPull, def: String): String = stringOrNull(p) ?: def

    private fun stringOrNull(p: JsonPull): String? =
        if (p.peek() == JsonPull.Token.STRING) p.nextString() else {
            p.skipValue()
            null
        }

    private fun intOr(p: JsonPull, def: Int): Int =
        if (p.peek() == JsonPull.Token.NUMBER) p.nextInt() else {
            p.skipValue()
            def
        }

    private fun boolOr(p: JsonPull, def: Boolean): Boolean =
        if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else {
            p.skipValue()
            def
        }

    private fun writeHello(w: JsonWrite, m: SyncMsg.Hello) {
        w.beginObject()
        w.name("t").value("hello")
        w.name("seq").value(m.seq)
        w.name("app").value(m.app)
        w.name("version").value(m.version)
        w.name("device_name").value(m.deviceName)
        w.name("host_cert_fp").value(m.hostCertFp)
        w.endObject()
    }

    private fun writeHelloAck(w: JsonWrite, m: SyncMsg.HelloAck) {
        w.beginObject()
        w.name("t").value("hello_ack")
        w.name("seq").value(m.seq)
        w.name("in_reply_to").value(m.inReplyTo)
        w.name("companion_cert_fp").value(m.companionCertFp)
        w.name("paired").value(m.paired)
        w.name("code_required").value(m.codeRequired)
        w.endObject()
    }

    private fun writePairRequest(w: JsonWrite, m: SyncMsg.PairRequest) {
        w.beginObject()
        w.name("t").value("pair_request")
        w.name("seq").value(m.seq)
        w.name("device_name").value(m.deviceName)
        w.name("host_cert_fp").value(m.hostCertFp)
        w.name("code").value(m.code)
        w.endObject()
    }

    private fun writePairResult(w: JsonWrite, m: SyncMsg.PairResult) {
        w.beginObject()
        w.name("t").value("pair_result")
        w.name("seq").value(m.seq)
        w.name("in_reply_to").value(m.inReplyTo)
        w.name("ok").value(m.ok)
        m.reason?.let { w.name("reason").value(it) }
        w.endObject()
    }

    private fun writeUnknown(w: JsonWrite, m: SyncMsg.Unknown) {
        w.beginObject()
        w.name("t").value(m.t)
        w.name("seq").value(m.seq)
        w.endObject()
    }

    private class StringSink(val sb: StringBuilder) : CharSink {
        override fun write(s: String) {
            sb.append(s)
        }

        override fun write(c: Int) {
            sb.append(c.toChar())
        }

        override fun write(chars: CharArray, offset: Int, length: Int) {
            sb.append(chars, offset, length)
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

}
