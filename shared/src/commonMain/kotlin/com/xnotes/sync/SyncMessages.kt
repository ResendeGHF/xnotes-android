package com.xnotes.sync

import com.xnotes.format.JsonPull
import com.xnotes.format.JsonWrite
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** One file or folder in the shared index. `kind`: 0 file, 1 dir. */
data class SyncFileEntry(
    val id: String,
    val path: String,
    val kind: Int,
    val version: Long,
    val size: Long,
)

/** One stroke sample in page px. */
data class WireSample(val x: Double, val y: Double, val p: Double)

/** A stroke as it crosses the wire. */
data class WireStroke(
    val tool: String,
    val width: Double,
    val color: String,
    val pressure: Boolean,
    val samples: List<WireSample>,
)

/** Committed ops inside an `op` frame. */
sealed interface SyncOp {
    data class AddStroke(val page: Int, val stroke: WireStroke) : SyncOp
    data class EraseStrokes(val page: Int, val ids: List<String>) : SyncOp
    data class Unknown(val name: String) : SyncOp
}

// --- message data (handshake lives in SyncFrames.kt) ---

internal fun parseSyncBody(p: JsonPull): SyncMsg {
    var t: String? = null
    var seq = 0
    var replyTo = -1
    var app = ""
    var hostFp = ""
    var companionFp = ""
    var paired = false
    var codeRequired = false
    var full = false
    var files: List<SyncFileEntry>? = null
    var id = ""
    var offset = 0L
    var length = 0L
    var version = 0L
    var total = 0L
    var data = ""
    var last = false
    var baseVersion = 0L
    var dedupe = ""
    var ops: List<SyncOp>? = null
    var page = 0
    var strokeId = ""
    var tool = ""
    var width = 0.0
    var color = ""
    var samples: List<WireSample>? = null
    var done = false
    var dirId: String? = null
    var name = ""
    var kind = 0
    var ok = false
    var reason: String? = null
    var deviceName = ""
    var code = ""
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "t" -> t = stringOrNull(p)
            "seq" -> seq = intOr(p, 0)
            "in_reply_to" -> replyTo = intOr(p, -1)
            "app" -> app = stringOr(p, "")
            "host_cert_fp" -> hostFp = stringOr(p, "")
            "companion_cert_fp" -> companionFp = stringOr(p, "")
            "paired" -> paired = boolOr(p, false)
            "code_required" -> codeRequired = boolOr(p, false)
            "full" -> full = boolOr(p, false)
            "files" -> files = fileListOr(p)
            "id" -> id = stringOr(p, "")
            "offset" -> offset = longOr(p, 0L)
            "length" -> length = longOr(p, 0L)
            "version" -> version = longOr(p, 0L)
            "total" -> total = longOr(p, 0L)
            "data" -> data = stringOr(p, "")
            "last" -> last = boolOr(p, false)
            "file" -> id = stringOr(p, "")
            "base_version" -> baseVersion = longOr(p, 0L)
            "dedupe" -> dedupe = stringOr(p, "")
            "ops" -> ops = opListOr(p)
            "page" -> page = intOr(p, 0)
            "stroke_id" -> strokeId = stringOr(p, "")
            "tool" -> tool = stringOr(p, "")
            "width" -> width = doubleOr(p, 0.0)
            "color" -> color = stringOr(p, "")
            "pressure" -> Unit // parsed inside stroke objects; ignored at top level
            "samples" -> samples = sampleListOr(p)
            "done" -> done = boolOr(p, false)
            "dir_id" -> dirId = stringOrNull(p)
            "name" -> name = stringOr(p, "")
            "kind" -> kind = intOr(p, 0)
            "ok" -> ok = boolOr(p, false)
            "reason" -> reason = stringOrNull(p)
            "device_name" -> deviceName = stringOr(p, "")
            "device" -> deviceName = stringOr(p, "")
            "code" -> code = stringOr(p, "")
            else -> p.skipValue()
        }
    }
    p.endObject()
    return when (t) {
        "hello" -> SyncMsg.Hello(seq, app, version.toInt(), deviceName, hostFp)
        "hello_ack" -> SyncMsg.HelloAck(seq, replyTo, companionFp, paired, codeRequired)
        "pair_request" -> SyncMsg.PairRequest(seq, deviceName, hostFp, code)
        "pair_result" -> SyncMsg.PairResult(seq, replyTo, ok, reason)
        "index" -> SyncMsg.Index(seq, replyTo, full, files ?: emptyList())
        "fetch" -> SyncMsg.Fetch(seq, id, offset, length)
        "file" -> SyncMsg.FileChunk(seq, id, version, total, offset, data, last)
        "op" -> SyncMsg.Op(seq, id, baseVersion, version, dedupe, ops ?: emptyList())
        "op_ack" -> SyncMsg.OpAck(seq, replyTo, id, version)
        "resync" -> SyncMsg.Resync(seq, id, version)
        "live" -> SyncMsg.Live(seq, id, page, strokeId, tool, width, color, samples ?: emptyList(), done)
        "presence" -> SyncMsg.Presence(seq, id, page)
        "heartbeat" -> SyncMsg.Heartbeat(seq)
        "index_subscribe" -> SyncMsg.IndexSubscribe(seq)
        "delete_request" -> SyncMsg.DeleteRequest(seq, id, deviceName)
        "delete_result" -> SyncMsg.DeleteResult(seq, replyTo, ok, reason)
        "create_request" -> SyncMsg.CreateRequest(seq, deviceName, dirId, name, kind)
        "create_result" -> SyncMsg.CreateResult(seq, replyTo, ok, id.takeIf { it.isNotEmpty() }, reason)
        else -> SyncMsg.Unknown(t ?: "", seq)
    }
}

internal fun writeHandshake(w: JsonWrite, msg: SyncMsg) {
    when (msg) {
        is SyncMsg.Hello -> {
            w.beginObject()
            w.name("t").value("hello")
            w.name("seq").value(msg.seq)
            w.name("app").value(msg.app)
            w.name("version").value(msg.version)
            w.name("device_name").value(msg.deviceName)
            w.name("host_cert_fp").value(msg.hostCertFp)
            w.endObject()
        }
        is SyncMsg.HelloAck -> {
            w.beginObject()
            w.name("t").value("hello_ack")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("companion_cert_fp").value(msg.companionCertFp)
            w.name("paired").value(msg.paired)
            w.name("code_required").value(msg.codeRequired)
            w.endObject()
        }
        is SyncMsg.PairRequest -> {
            w.beginObject()
            w.name("t").value("pair_request")
            w.name("seq").value(msg.seq)
            w.name("device_name").value(msg.deviceName)
            w.name("host_cert_fp").value(msg.hostCertFp)
            w.name("code").value(msg.code)
            w.endObject()
        }
        is SyncMsg.PairResult -> {
            w.beginObject()
            w.name("t").value("pair_result")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("ok").value(msg.ok)
            msg.reason?.let { w.name("reason").value(it) }
            w.endObject()
        }
        else -> Unit
    }
}

internal fun writeSyncBody(w: JsonWrite, msg: SyncMsg) {
    writeHandshake(w, msg)
    when (msg) {
        is SyncMsg.Index -> {
            w.beginObject()
            w.name("t").value("index")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("full").value(msg.full)
            w.name("files").beginArray()
            for (f in msg.files) {
                w.beginObject()
                w.name("id").value(f.id)
                w.name("path").value(f.path)
                w.name("kind").value(f.kind)
                w.name("version").value(f.version.toDouble())
                w.name("size").value(f.size.toDouble())
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        is SyncMsg.Fetch -> {
            w.beginObject()
            w.name("t").value("fetch")
            w.name("seq").value(msg.seq)
            w.name("id").value(msg.id)
            w.name("offset").value(msg.offset.toDouble())
            w.name("length").value(msg.length.toDouble())
            w.endObject()
        }
        is SyncMsg.FileChunk -> {
            w.beginObject()
            w.name("t").value("file")
            w.name("seq").value(msg.seq)
            w.name("id").value(msg.id)
            w.name("version").value(msg.version.toDouble())
            w.name("total").value(msg.total.toDouble())
            w.name("offset").value(msg.offset.toDouble())
            w.name("data").value(msg.data)
            w.name("last").value(msg.last)
            w.endObject()
        }
        is SyncMsg.Op -> {
            w.beginObject()
            w.name("t").value("op")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("base_version").value(msg.baseVersion.toDouble())
            w.name("version").value(msg.version.toDouble())
            w.name("dedupe").value(msg.dedupe)
            w.name("ops").beginArray()
            for (op in msg.ops) writeOp(w, op)
            w.endArray()
            w.endObject()
        }
        is SyncMsg.OpAck -> {
            w.beginObject()
            w.name("t").value("op_ack")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("file").value(msg.file)
            w.name("version").value(msg.version.toDouble())
            w.endObject()
        }
        is SyncMsg.Resync -> {
            w.beginObject()
            w.name("t").value("resync")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("version").value(msg.version.toDouble())
            w.endObject()
        }
        is SyncMsg.Live -> {
            w.beginObject()
            w.name("t").value("live")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("page").value(msg.page)
            w.name("stroke_id").value(msg.strokeId)
            w.name("tool").value(msg.tool)
            w.name("width").value(msg.width)
            w.name("color").value(msg.color)
            w.name("samples").beginArray()
            for (s in msg.samples) {
                w.beginArray()
                w.value(s.x)
                w.value(s.y)
                w.value(s.p)
                w.endArray()
            }
            w.endArray()
            w.name("done").value(msg.done)
            w.endObject()
        }
        is SyncMsg.Presence -> {
            w.beginObject()
            w.name("t").value("presence")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("page").value(msg.page)
            w.endObject()
        }
        is SyncMsg.Heartbeat -> {
            w.beginObject()
            w.name("t").value("heartbeat")
            w.name("seq").value(msg.seq)
            w.endObject()
        }
        is SyncMsg.IndexSubscribe -> {
            w.beginObject()
            w.name("t").value("index_subscribe")
            w.name("seq").value(msg.seq)
            w.endObject()
        }
        is SyncMsg.DeleteRequest -> {
            w.beginObject()
            w.name("t").value("delete_request")
            w.name("seq").value(msg.seq)
            w.name("id").value(msg.id)
            w.name("device").value(msg.device)
            w.endObject()
        }
        is SyncMsg.DeleteResult -> {
            w.beginObject()
            w.name("t").value("delete_result")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("ok").value(msg.ok)
            msg.reason?.let { w.name("reason").value(it) }
            w.endObject()
        }
        is SyncMsg.CreateRequest -> {
            w.beginObject()
            w.name("t").value("create_request")
            w.name("seq").value(msg.seq)
            w.name("device").value(msg.device)
            msg.dirId?.let { w.name("dir_id").value(it) }
            w.name("name").value(msg.name)
            w.name("kind").value(msg.kind)
            w.endObject()
        }
        is SyncMsg.CreateResult -> {
            w.beginObject()
            w.name("t").value("create_result")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("ok").value(msg.ok)
            msg.id?.let { w.name("id").value(it) }
            msg.reason?.let { w.name("reason").value(it) }
            w.endObject()
        }
        else -> Unit // handshake messages are written by SyncFrames
    }
}

private fun writeOp(w: JsonWrite, op: SyncOp) {
    when (op) {
        is SyncOp.AddStroke -> {
            w.beginObject()
            w.name("op").value("add_stroke")
            w.name("page").value(op.page)
            w.name("stroke").beginObject()
            w.name("tool").value(op.stroke.tool)
            w.name("width").value(op.stroke.width)
            w.name("color").value(op.stroke.color)
            w.name("pressure").value(op.stroke.pressure)
            w.name("samples").beginArray()
            for (s in op.stroke.samples) {
                w.beginArray()
                w.value(s.x)
                w.value(s.y)
                w.value(s.p)
                w.endArray()
            }
            w.endArray()
            w.endObject()
            w.endObject()
        }
        is SyncOp.EraseStrokes -> {
            w.beginObject()
            w.name("op").value("erase_strokes")
            w.name("page").value(op.page)
            w.name("ids").beginArray()
            for (id in op.ids) w.value(id)
            w.endArray()
            w.endObject()
        }
        is SyncOp.Unknown -> {
            w.beginObject()
            w.name("op").value(op.name)
            w.endObject()
        }
    }
}

private fun fileListOr(p: JsonPull): List<SyncFileEntry>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<SyncFileEntry>()
    p.beginArray()
    while (p.hasNext()) {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            continue
        }
        var id = ""
        var path = ""
        var kind = 0
        var version = 0L
        var size = 0L
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "id" -> id = stringOr(p, "")
                "path" -> path = stringOr(p, "")
                "kind" -> kind = intOr(p, 0)
                "version" -> version = longOr(p, 0L)
                "size" -> size = longOr(p, 0L)
                else -> p.skipValue()
            }
        }
        p.endObject()
        if (id.isNotEmpty()) out += SyncFileEntry(id, path, kind, version, size)
    }
    p.endArray()
    return out
}

private fun sampleListOr(p: JsonPull): List<WireSample>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<WireSample>()
    p.beginArray()
    while (p.hasNext()) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            continue
        }
        var x = 0.0
        var y = 0.0
        var pr = 1.0
        var i = 0
        p.beginArray()
        while (p.hasNext()) {
            val v = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                p.skipValue()
                0.0
            }
            when (i++) {
                0 -> x = v
                1 -> y = v
                2 -> pr = v
            }
        }
        p.endArray()
        out += WireSample(x, y, pr)
    }
    p.endArray()
    return out
}

private fun opListOr(p: JsonPull): List<SyncOp>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<SyncOp>()
    p.beginArray()
    while (p.hasNext()) {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            continue
        }
        var name: String? = null
        var page = 0
        var tool = ""
        var width = 0.0
        var color = ""
        var pressure = false
        var samples: List<WireSample>? = null
        var ids: List<String>? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "op" -> name = stringOrNull(p)
                "page" -> page = intOr(p, 0)
                "stroke" -> if (p.peek() == JsonPull.Token.BEGIN_OBJECT) {
                    p.beginObject()
                    while (p.hasNext()) {
                        when (p.nextName()) {
                            "tool" -> tool = stringOr(p, "")
                            "width" -> width = doubleOr(p, 0.0)
                            "color" -> color = stringOr(p, "")
                            "pressure" -> pressure = boolOr(p, false)
                            "samples" -> samples = sampleListOr(p)
                            else -> p.skipValue()
                        }
                    }
                    p.endObject()
                } else p.skipValue()
                "ids" -> ids = if (p.peek() == JsonPull.Token.BEGIN_ARRAY) {
                    val list = ArrayList<String>()
                    p.beginArray()
                    while (p.hasNext()) stringOrNull(p)?.let { list += it }
                    p.endArray()
                    list
                } else {
                    p.skipValue()
                    null
                }
                else -> p.skipValue()
            }
        }
        p.endObject()
        out += when (name) {
            "add_stroke" -> SyncOp.AddStroke(page, WireStroke(tool, width, color, pressure, samples ?: emptyList()))
            "erase_strokes" -> SyncOp.EraseStrokes(page, ids ?: emptyList())
            else -> SyncOp.Unknown(name ?: "")
        }
    }
    p.endArray()
    return out
}

internal fun stringOr(p: JsonPull, def: String): String = stringOrNull(p) ?: def

internal fun stringOrNull(p: JsonPull): String? =
    if (p.peek() == JsonPull.Token.STRING) p.nextString() else {
        p.skipValue()
        null
    }

internal fun intOr(p: JsonPull, def: Int): Int =
    if (p.peek() == JsonPull.Token.NUMBER) p.nextInt() else {
        p.skipValue()
        def
    }

internal fun longOr(p: JsonPull, def: Long): Long =
    if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble().toLong() else {
        p.skipValue()
        def
    }

internal fun doubleOr(p: JsonPull, def: Double): Double =
    if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
        p.skipValue()
        def
    }

internal fun boolOr(p: JsonPull, def: Boolean): Boolean =
    if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else {
        p.skipValue()
        def
    }

/** Base64 of [bytes] for file chunks. */
internal fun base64Of(bytes: ByteArray): String = bytes.toByteString().base64()

/** Bytes of a base64 chunk, or null when malformed. */
internal fun bytesOfBase64(s: String): ByteArray? = try {
    s.decodeBase64()?.toByteArray()
} catch (_: Exception) {
    null
}
