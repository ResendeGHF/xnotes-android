package com.xnotes.sync

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.TextQuad
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

/** Full stroke style (pencil/taper/highlighter/dashed/speed/calligraphy/neon). All nullable for backward compat. */
data class WireStrokeStyle(
    val pmin: Double? = null,
    val dir: Double? = null,
    val speed: Double? = null,
    val taper: Boolean? = null,
    val taperMin: Double? = null,
    val neon: Boolean? = null,
    val neonS: Double? = null,
    val dashL: Double? = null,
    val dashG: Double? = null,
    val hlAlpha: Double? = null,
    val hlInv: Boolean? = null,
    val pencilP: Double? = null,
    val pencilD: Double? = null,
    val pencilG: Double? = null,
    val pencilS: Double? = null,
)

/** A stroke as it crosses the wire. */
data class WireStroke(
    val tool: String,
    val width: Double,
    val color: String,
    val pressure: Boolean,
    val samples: List<WireSample>,
    val style: WireStrokeStyle? = null,
    /** Recording moment the stroke was added in, for replay fade/seek. Null when none. */
    val sync: WireSyncMark? = null,
)

/** A recording moment: the recording's id and ms into it. Rides strokes. */
data class WireSyncMark(val rid: Int, val ms: Int)

/** A text box as it crosses the wire. `uid` is its stable id (see SYNC.md). */
data class WireText(
    val uid: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val text: String,
    /** `#rrggbb` or `#rrggbbaa`. */
    val color: String,
    val pointSize: Double,
    val face: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val align: String = "left",
    val math: Boolean = false,
    val link: String? = null,
)

/** A table as it crosses the wire. `uid` is its stable id (see SYNC.md). */
data class WireTable(
    val uid: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val cols: List<Double>,
    val rows: List<Double>,
    /** `#rrggbb` or `#rrggbbaa`. */
    val strokeColor: String,
    val strokeWidth: Double,
    val cells: List<List<String>> = emptyList(),
    val link: String? = null,
)

/**
 * An audio pill as it crosses the wire. The bytes travel separately in
 * `audio_data` chunks keyed by [audioId]; the op applies once they are
 * complete. `uid` is its stable id (see SYNC.md); `rid` its recording id.
 */
data class WireAudio(
    val audioId: String,
    val uid: String,
    val page: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val name: String,
    val rid: Int,
    val ext: String,
    val durationMs: Int,
)

/**
 * An image as it crosses the wire. The bytes travel separately in `image_data`
 * chunks keyed by [imageId]; the op applies once they are complete.
 */
data class WireImage(
    val imageId: String,
    val width: Int,
    val height: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val orientation: Int,
    val angle: Double,
    val flipX: Boolean,
    val flipY: Boolean,
    /** Part fractions [l, t, r, b], or null for the whole image. */
    val part: List<Double>?,
    /** Stable id for moves/removes; empty from old peers (then [imageId] is used). */
    val uid: String = "",
)

/** One box of a text markup, in points as displayed. */
data class WireQuad(val l: Double, val t: Double, val r: Double, val b: Double, val q: Int)

/** A page style snapshot for the wire; null = inherit (leave the field empty). */
data class WirePageStyle(
    val pageColor: String?,
    val template: String?,
    /** `#rrggbb` or `#rrggbbaa` (alpha = line opacity). */
    val patternColor: String?,
    val spacing: Double?,
    val accentColor: String?,
    val params: Map<String, Double>,
    /** Named colors as `#rrggbb`. */
    val colors: Map<String, String>,
    val invertColors: Boolean?,
    val invertImages: Boolean?,
)

/** Page margins for the wire (fractions of W/H); null = inherit. */
data class WireMargins(val l: Double?, val t: Double?, val r: Double?, val b: Double?)

/** One page's style assignment in a page_style op (index into the current pages). */
data class PageStyleEntry(val index: Int, val style: WirePageStyle?, val margins: WireMargins?)

/** A PDF text markup (highlight/underline/strikeout/squiggly) for the wire. */
data class WireMarkup(
    val id: String,
    /** MarkupType id ("highlight", "underline", "strikeout", "squiggly"). */
    val type: String,
    /** Opaque ink color as `#rrggbb`. */
    val color: String,
    val intensity: Double,
    val quads: List<WireQuad>,
    val text: String,
    val note: String?,
    val created: Long,
    val modified: Long,
    /** Note-link target (a stable file id on the host) for `note_link` markups. */
    val link: String?,
)

/** Committed ops inside an `op` frame. */
sealed interface SyncOp {
    data class AddStroke(val page: Int, val stroke: WireStroke) : SyncOp
    data class EraseStrokes(val page: Int, val ids: List<String>) : SyncOp
    /** Mark PDF text on [page] (highlight/underline/strikeout/squiggly). */
    data class AddMarkup(val page: Int, val markup: WireMarkup) : SyncOp
    /** Take text markups off [page] by id. */
    data class RemoveMarkup(val page: Int, val ids: List<String>) : SyncOp
    /**
     * Set page styles: [entries] assign style/margins per page index;
     * [docStyle]/[docMargins] replace the document-wide defaults.
     * Null fields inside a snapshot leave that field alone (inherit).
     */
    data class PageStyle(val entries: List<PageStyleEntry>, val docStyle: WirePageStyle?, val docMargins: WireMargins?) : SyncOp
    /** Insert a blank page at [index] ([width]/[height] in page px, from the sender's page). */
    data class AddPage(val index: Int, val width: Double, val height: Double) : SyncOp
    /** Remove the page at [index]; no-op when it would empty the note or is out of range. */
    data class RemovePage(val index: Int) : SyncOp
    /** Open [size] of room at [at] on [page] ([sideways] across the page). */
    data class OpenRoom(val page: Int, val sideways: Boolean, val at: Double, val size: Double) : SyncOp
    /** Close the strip [a] to [b] of a room on [page]. */
    data class CloseRoom(val page: Int, val sideways: Boolean, val a: Double, val b: Double) : SyncOp
    /** Place an image on [page]; its bytes arrive in `image_data` chunks keyed by id. */
    data class AddImage(val page: Int, val image: WireImage) : SyncOp
    /** Take image pills off [page] by image id (or uid). */
    data class RemoveImage(val page: Int, val ids: List<String>) : SyncOp
    /** Place a text box on [page] (full snapshot, upserted by uid). */
    data class AddText(val page: Int, val text: WireText) : SyncOp
    /** Take text boxes off [page] by uid. */
    data class RemoveText(val page: Int, val ids: List<String>) : SyncOp
    /** Place a table on [page] (full snapshot, upserted by uid). */
    data class AddTable(val page: Int, val table: WireTable) : SyncOp
    /** Take tables off [page] by uid. */
    data class RemoveTable(val page: Int, val ids: List<String>) : SyncOp
    /** Place an audio pill on [page]; its bytes arrive in `audio_data` chunks keyed by id. */
    data class AddAudio(val page: Int, val audio: WireAudio) : SyncOp
    /** Take audio pills off [page] by audio id (or uid). */
    data class RemoveAudio(val page: Int, val ids: List<String>) : SyncOp
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
    var style: WireStrokeStyle? = null
    var done = false
    var sideways = false
    var split = 0.0
    var resume = 0.0
    var shift = 0.0
    var liveId = ""
    var imageId = ""
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
            "style" -> style = styleOr(p)
            "done" -> done = boolOr(p, false)
            "sideways" -> sideways = boolOr(p, false)
            "split" -> split = doubleOr(p, 0.0)
            "resume" -> resume = doubleOr(p, 0.0)
            "shift" -> shift = doubleOr(p, 0.0)
            "live_id" -> liveId = stringOr(p, "")
            "image_id" -> imageId = stringOr(p, "")
            "audio_id" -> imageId = stringOr(p, "")
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
        "image_data" -> SyncMsg.ImageChunk(seq, id, imageId, total, offset, data, last)
        "audio_data" -> SyncMsg.AudioChunk(seq, id, imageId, total, offset, data, last)
        "audio_peaks" -> SyncMsg.AudioPeaks(seq, id, imageId, total, offset, data, last)
        "op" -> SyncMsg.Op(seq, id, baseVersion, version, dedupe, ops ?: emptyList())
        "op_ack" -> SyncMsg.OpAck(seq, replyTo, id, version)
        "resync" -> SyncMsg.Resync(seq, id, version)
        "live" -> SyncMsg.Live(seq, id, page, strokeId, tool, width, color, samples ?: emptyList(), done, style)
        "room_live" -> SyncMsg.RoomLive(seq, id, page, sideways, split, resume, shift, liveId, done)
        "presence" -> SyncMsg.Presence(seq, id, page)
        "heartbeat" -> SyncMsg.Heartbeat(seq)
        "index_subscribe" -> SyncMsg.IndexSubscribe(seq)
        "delete_request" -> SyncMsg.DeleteRequest(seq, id, deviceName)
        "delete_result" -> SyncMsg.DeleteResult(seq, replyTo, ok, reason)
        "create_request" -> SyncMsg.CreateRequest(seq, deviceName, dirId, name, kind)
        "create_result" -> SyncMsg.CreateResult(seq, replyTo, ok, id.takeIf { it.isNotEmpty() }, reason)
        "link_request" -> SyncMsg.LinkRequest(seq, id, deviceName)
        "link_result" -> SyncMsg.LinkResult(seq, replyTo, id, ok, reason)
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
        is SyncMsg.ImageChunk -> {
            w.beginObject()
            w.name("t").value("image_data")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("image_id").value(msg.imageId)
            w.name("total").value(msg.total.toDouble())
            w.name("offset").value(msg.offset.toDouble())
            w.name("data").value(msg.data)
            w.name("last").value(msg.last)
            w.endObject()
        }
        is SyncMsg.AudioChunk -> {
            w.beginObject()
            w.name("t").value("audio_data")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("audio_id").value(msg.audioId)
            w.name("total").value(msg.total.toDouble())
            w.name("offset").value(msg.offset.toDouble())
            w.name("data").value(msg.data)
            w.name("last").value(msg.last)
            w.endObject()
        }
        is SyncMsg.AudioPeaks -> {
            w.beginObject()
            w.name("t").value("audio_peaks")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("audio_id").value(msg.audioId)
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
            msg.style?.let { writeStrokeStyle(w, it) }
            w.endObject()
        }
        is SyncMsg.RoomLive -> {
            w.beginObject()
            w.name("t").value("room_live")
            w.name("seq").value(msg.seq)
            w.name("file").value(msg.file)
            w.name("page").value(msg.page)
            if (msg.sideways) w.name("sideways").value(true)
            w.name("split").value(msg.split)
            w.name("resume").value(msg.resume)
            w.name("shift").value(msg.shift)
            w.name("live_id").value(msg.liveId)
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
        is SyncMsg.LinkRequest -> {
            w.beginObject()
            w.name("t").value("link_request")
            w.name("seq").value(msg.seq)
            w.name("id").value(msg.id)
            w.name("device").value(msg.device)
            w.endObject()
        }
        is SyncMsg.LinkResult -> {
            w.beginObject()
            w.name("t").value("link_result")
            w.name("seq").value(msg.seq)
            w.name("in_reply_to").value(msg.inReplyTo)
            w.name("id").value(msg.id)
            w.name("ok").value(msg.ok)
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
            op.stroke.style?.let { writeStrokeStyle(w, it) }
            op.stroke.sync?.let {
                w.name("sync").beginObject()
                w.name("rid").value(it.rid)
                w.name("ms").value(it.ms.toDouble())
                w.endObject()
            }
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
        is SyncOp.AddMarkup -> {
            w.beginObject()
            w.name("op").value("add_markup")
            w.name("page").value(op.page)
            w.name("markup").beginObject()
            w.name("id").value(op.markup.id)
            w.name("type").value(op.markup.type)
            w.name("color").value(op.markup.color)
            w.name("intensity").value(op.markup.intensity)
            w.name("quads").beginArray()
            for (q in op.markup.quads) {
                w.beginArray()
                w.value(q.l)
                w.value(q.t)
                w.value(q.r)
                w.value(q.b)
                w.value(q.q.toDouble())
                w.endArray()
            }
            w.endArray()
            w.name("text").value(op.markup.text)
            op.markup.note?.let { w.name("note").value(it) }
            op.markup.link?.let { w.name("link").value(it) }
            w.name("created").value(op.markup.created.toDouble())
            w.name("modified").value(op.markup.modified.toDouble())
            w.endObject()
            w.endObject()
        }
        is SyncOp.RemoveMarkup -> {
            w.beginObject()
            w.name("op").value("remove_markup")
            w.name("page").value(op.page)
            w.name("ids").beginArray()
            for (id in op.ids) w.value(id)
            w.endArray()
            w.endObject()
        }
        is SyncOp.PageStyle -> {
            w.beginObject()
            w.name("op").value("page_style")
            w.name("entries").beginArray()
            for (e in op.entries) {
                w.beginObject()
                w.name("index").value(e.index)
                e.style?.let { writePageStyleBody(w, it) }
                e.margins?.let {
                    w.name("margins").beginObject()
                    writeMarginsBody(w, it)
                    w.endObject()
                }
                w.endObject()
            }
            w.endArray()
            op.docStyle?.let {
                w.name("doc_style").beginObject()
                writePageStyleBody(w, it)
                w.endObject()
            }
            op.docMargins?.let {
                w.name("doc_margins").beginObject()
                writeMarginsBody(w, it)
                w.endObject()
            }
            w.endObject()
        }
        is SyncOp.AddPage -> {
            w.beginObject()
            w.name("op").value("add_page")
            w.name("index").value(op.index)
            w.name("width").value(op.width)
            w.name("height").value(op.height)
            w.endObject()
        }
        is SyncOp.RemovePage -> {
            w.beginObject()
            w.name("op").value("remove_page")
            w.name("index").value(op.index)
            w.endObject()
        }
        is SyncOp.OpenRoom -> {
            w.beginObject()
            w.name("op").value("open_room")
            w.name("page").value(op.page)
            if (op.sideways) w.name("sideways").value(true)
            w.name("at").value(op.at)
            w.name("size").value(op.size)
            w.endObject()
        }
        is SyncOp.CloseRoom -> {
            w.beginObject()
            w.name("op").value("close_room")
            w.name("page").value(op.page)
            if (op.sideways) w.name("sideways").value(true)
            w.name("a").value(op.a)
            w.name("b").value(op.b)
            w.endObject()
        }
        is SyncOp.AddImage -> {
            w.beginObject()
            w.name("op").value("add_image")
            w.name("page").value(op.page)
            w.name("image_id").value(op.image.imageId)
            w.name("width").value(op.image.width.toDouble())
            w.name("height").value(op.image.height.toDouble())
            w.name("rect").beginArray()
            w.value(op.image.x)
            w.value(op.image.y)
            w.value(op.image.w)
            w.value(op.image.h)
            w.endArray()
            w.name("orientation").value(op.image.orientation)
            w.name("angle").value(op.image.angle)
            if (op.image.flipX) w.name("flip_x").value(true)
            if (op.image.flipY) w.name("flip_y").value(true)
            op.image.part?.let { p ->
                w.name("part").beginArray()
                for (v in p) w.value(v)
                w.endArray()
            }
            if (op.image.uid.isNotEmpty()) w.name("uid").value(op.image.uid)
            w.endObject()
        }
        is SyncOp.RemoveImage -> {
            w.beginObject()
            w.name("op").value("remove_image")
            w.name("page").value(op.page)
            w.name("ids").beginArray()
            for (id in op.ids) w.value(id)
            w.endArray()
            w.endObject()
        }
        is SyncOp.AddText -> {
            w.beginObject()
            w.name("op").value("add_text")
            w.name("page").value(op.page)
            w.name("text").beginObject()
            w.name("uid").value(op.text.uid)
            w.name("x").value(op.text.x)
            w.name("y").value(op.text.y)
            w.name("width").value(op.text.width)
            w.name("height").value(op.text.height)
            w.name("text").value(op.text.text)
            w.name("color").value(op.text.color)
            w.name("point_size").value(op.text.pointSize)
            w.name("face").value(op.text.face)
            if (op.text.bold) w.name("bold").value(true)
            if (op.text.italic) w.name("italic").value(true)
            if (op.text.underline) w.name("underline").value(true)
            if (op.text.strike) w.name("strike").value(true)
            w.name("align").value(op.text.align)
            if (op.text.math) w.name("math").value(true)
            op.text.link?.let { w.name("link").value(it) }
            w.endObject()
            w.endObject()
        }
        is SyncOp.RemoveText -> {
            w.beginObject()
            w.name("op").value("remove_text")
            w.name("page").value(op.page)
            w.name("ids").beginArray()
            for (id in op.ids) w.value(id)
            w.endArray()
            w.endObject()
        }
        is SyncOp.AddTable -> {
            w.beginObject()
            w.name("op").value("add_table")
            w.name("page").value(op.page)
            w.name("table").beginObject()
            w.name("uid").value(op.table.uid)
            w.name("x").value(op.table.x)
            w.name("y").value(op.table.y)
            w.name("w").value(op.table.w)
            w.name("h").value(op.table.h)
            w.name("cols").beginArray()
            for (v in op.table.cols) w.value(v)
            w.endArray()
            w.name("rows").beginArray()
            for (v in op.table.rows) w.value(v)
            w.endArray()
            w.name("stroke_color").value(op.table.strokeColor)
            w.name("stroke_width").value(op.table.strokeWidth)
            if (op.table.cells.isNotEmpty()) {
                w.name("cells").beginArray()
                for (row in op.table.cells) {
                    w.beginArray()
                    for (c in row) w.value(c)
                    w.endArray()
                }
                w.endArray()
            }
            op.table.link?.let { w.name("link").value(it) }
            w.endObject()
            w.endObject()
        }
        is SyncOp.RemoveTable -> {
            w.beginObject()
            w.name("op").value("remove_table")
            w.name("page").value(op.page)
            w.name("ids").beginArray()
            for (id in op.ids) w.value(id)
            w.endArray()
            w.endObject()
        }
        is SyncOp.AddAudio -> {
            w.beginObject()
            w.name("op").value("add_audio")
            w.name("page").value(op.page)
            w.name("audio").beginObject()
            w.name("audio_id").value(op.audio.audioId)
            w.name("uid").value(op.audio.uid)
            w.name("page").value(op.audio.page)
            w.name("x").value(op.audio.x)
            w.name("y").value(op.audio.y)
            w.name("w").value(op.audio.w)
            w.name("h").value(op.audio.h)
            w.name("name").value(op.audio.name)
            w.name("rid").value(op.audio.rid)
            w.name("ext").value(op.audio.ext)
            w.name("duration_ms").value(op.audio.durationMs)
            w.endObject()
            w.endObject()
        }
        is SyncOp.RemoveAudio -> {
            w.beginObject()
            w.name("op").value("remove_audio")
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

/** Wire `style` object for strokes/live (pencil/taper/highlighter/dashed/speed/calligraphy/neon). Nulls omitted; old peers skip it. */
private fun writeStrokeStyle(w: JsonWrite, s: WireStrokeStyle) {
    w.name("style").beginObject()
    s.pmin?.let { w.name("pmin").value(it) }
    s.dir?.let { w.name("dir").value(it) }
    s.speed?.let { w.name("speed").value(it) }
    s.taper?.let { w.name("taper").value(it) }
    s.taperMin?.let { w.name("taper_min").value(it) }
    s.neon?.let { w.name("neon").value(it) }
    s.neonS?.let { w.name("neon_s").value(it) }
    s.dashL?.let { w.name("dash_l").value(it) }
    s.dashG?.let { w.name("dash_g").value(it) }
    s.hlAlpha?.let { w.name("hl_alpha").value(it) }
    s.hlInv?.let { w.name("hl_inv").value(it) }
    s.pencilP?.let { w.name("pencil_p").value(it) }
    s.pencilD?.let { w.name("pencil_d").value(it) }
    s.pencilG?.let { w.name("pencil_g").value(it) }
    s.pencilS?.let { w.name("pencil_s").value(it) }
    w.endObject()
}

private fun styleOr(p: JsonPull): WireStrokeStyle? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var pmin: Double? = null
    var dir: Double? = null
    var speed: Double? = null
    var taper: Boolean? = null
    var taperMin: Double? = null
    var neon: Boolean? = null
    var neonS: Double? = null
    var dashL: Double? = null
    var dashG: Double? = null
    var hlAlpha: Double? = null
    var hlInv: Boolean? = null
    var pencilP: Double? = null
    var pencilD: Double? = null
    var pencilG: Double? = null
    var pencilS: Double? = null
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "pmin" -> pmin = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "dir" -> dir = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "speed" -> speed = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "taper" -> taper = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else { p.skipValue(); null }
            "taper_min" -> taperMin = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "neon" -> neon = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else { p.skipValue(); null }
            "neon_s" -> neonS = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "dash_l" -> dashL = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "dash_g" -> dashG = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "hl_alpha" -> hlAlpha = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "hl_inv" -> hlInv = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else { p.skipValue(); null }
            "pencil_p" -> pencilP = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "pencil_d" -> pencilD = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "pencil_g" -> pencilG = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            "pencil_s" -> pencilS = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else { p.skipValue(); null }
            else -> p.skipValue()
        }
    }
    p.endObject()
    val out = WireStrokeStyle(pmin, dir, speed, taper, taperMin, neon, neonS, dashL, dashG, hlAlpha, hlInv, pencilP, pencilD, pencilG, pencilS)
    return out.takeIf { it != WireStrokeStyle() }
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

/** Page-style keys inside an entry or doc_style object (no wrapper of its own). */
private fun writePageStyleBody(w: JsonWrite, s: WirePageStyle) {    s.pageColor?.let { w.name("page_color").value(it) }
    s.template?.let { w.name("template").value(it) }
    s.patternColor?.let { w.name("pattern_color").value(it) }
    s.spacing?.let { w.name("spacing").value(it) }
    s.accentColor?.let { w.name("accent_color").value(it) }
    if (s.params.isNotEmpty()) {
        w.name("params").beginObject()
        for ((k, v) in s.params) w.name(k).value(v)
        w.endObject()
    }
    if (s.colors.isNotEmpty()) {
        w.name("colors").beginObject()
        for ((k, v) in s.colors) w.name(k).value(v)
        w.endObject()
    }
    s.invertColors?.let { w.name("invert_colors").value(it) }
    s.invertImages?.let { w.name("invert_images").value(it) }
}

/** A `markup` object inside an add_markup op, or null when malformed. */
private fun markupOr(p: JsonPull): WireMarkup? {    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var id = ""
    var type = ""
    var color = ""
    var intensity = 0.5
    var quads: List<WireQuad>? = null
    var text = ""
    var note: String? = null
    var link: String? = null
    var created = 0L
    var modified = 0L
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "id" -> id = stringOr(p, "")
            "type" -> type = stringOr(p, "")
            "color" -> color = stringOr(p, "")
            "intensity" -> intensity = doubleOr(p, 0.5)
            "quads" -> {
                if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
                    p.skipValue()
                } else {
                    val list = ArrayList<WireQuad>()
                    p.beginArray()
                    while (p.hasNext()) {
                        val nums = numberListOr(p)
                        if (nums != null && nums.size >= 5) {
                            list += WireQuad(nums[0], nums[1], nums[2], nums[3], nums[4].toInt())
                        }
                    }
                    p.endArray()
                    quads = list
                }
            }
            "text" -> text = stringOr(p, "")
            "note" -> note = stringOrNull(p)
            "link" -> link = stringOrNull(p)
            "created" -> created = longOr(p, 0L)
            "modified" -> modified = longOr(p, 0L)
            else -> p.skipValue()
        }
    }
    p.endObject()
    if (id.isEmpty() || quads == null) return null
    return WireMarkup(id, type, color, intensity, quads, text, note, created, modified, link)
}

/** A `sync` object inside a stroke: the recording moment, or null when malformed. */
private fun syncOr(p: JsonPull): WireSyncMark? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var rid = -1
    var ms = 0
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "rid" -> rid = intOr(p, -1)
            "ms" -> ms = longOr(p, 0L).toInt()
            else -> p.skipValue()
        }
    }
    p.endObject()
    return if (rid < 0) null else WireSyncMark(rid, ms)
}

/** A `text` object inside an add_text op, or null when malformed. */
private fun textOr(p: JsonPull): WireText? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var uid = ""
    var x = 0.0
    var y = 0.0
    var width = 0.0
    var height = 0.0
    var text = ""
    var color = ""
    var pointSize = 13.0
    var face = ""
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var align = "left"
    var math = false
    var link: String? = null
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "uid" -> uid = stringOr(p, "")
            "x" -> x = doubleOr(p, 0.0)
            "y" -> y = doubleOr(p, 0.0)
            "width" -> width = doubleOr(p, 0.0)
            "height" -> height = doubleOr(p, 0.0)
            "text" -> text = stringOr(p, "")
            "color" -> color = stringOr(p, "")
            "point_size" -> pointSize = doubleOr(p, 13.0)
            "face" -> face = stringOr(p, "")
            "bold" -> bold = boolOr(p, false)
            "italic" -> italic = boolOr(p, false)
            "underline" -> underline = boolOr(p, false)
            "strike" -> strike = boolOr(p, false)
            "align" -> align = stringOr(p, "left")
            "math" -> math = boolOr(p, false)
            "link" -> link = stringOrNull(p)
            else -> p.skipValue()
        }
    }
    p.endObject()
    if (uid.isEmpty()) return null
    return WireText(uid, x, y, width, height, text, color, pointSize, face, bold, italic, underline, strike, align, math, link)
}

/** A `table` object inside an add_table op, or null when malformed. */
private fun tableOr(p: JsonPull): WireTable? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var uid = ""
    var x = 0.0
    var y = 0.0
    var w = 0.0
    var h = 0.0
    var cols: List<Double> = emptyList()
    var rows: List<Double> = emptyList()
    var strokeColor = ""
    var strokeWidth = 2.0
    var cells: List<List<String>> = emptyList()
    var link: String? = null
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "uid" -> uid = stringOr(p, "")
            "x" -> x = doubleOr(p, 0.0)
            "y" -> y = doubleOr(p, 0.0)
            "w" -> w = doubleOr(p, 0.0)
            "h" -> h = doubleOr(p, 0.0)
            "cols" -> cols = numberListOr(p) ?: emptyList()
            "rows" -> rows = numberListOr(p) ?: emptyList()
            "stroke_color" -> strokeColor = stringOr(p, "")
            "stroke_width" -> strokeWidth = doubleOr(p, 2.0)
            "cells" -> cells = stringsGridOrNull(p) ?: emptyList()
            "link" -> link = stringOrNull(p)
            else -> p.skipValue()
        }
    }
    p.endObject()
    if (uid.isEmpty() || cols.isEmpty() || rows.isEmpty()) return null
    return WireTable(uid, x, y, w, h, cols, rows, strokeColor, strokeWidth, cells, link)
}

/** An `audio` object inside an add_audio op, or null when malformed. */
private fun audioOr(p: JsonPull): WireAudio? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var audioId = ""
    var uid = ""
    var page = 0
    var x = 0.0
    var y = 0.0
    var w = 0.0
    var h = 0.0
    var name = ""
    var rid = 0
    var ext = ""
    var durationMs = 0
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "audio_id" -> audioId = stringOr(p, "")
            "uid" -> uid = stringOr(p, "")
            "page" -> page = intOr(p, 0)
            "x" -> x = doubleOr(p, 0.0)
            "y" -> y = doubleOr(p, 0.0)
            "w" -> w = doubleOr(p, 0.0)
            "h" -> h = doubleOr(p, 0.0)
            "name" -> name = stringOr(p, "")
            "rid" -> rid = intOr(p, 0)
            "ext" -> ext = stringOr(p, "")
            "duration_ms" -> durationMs = intOr(p, 0)
            else -> p.skipValue()
        }
    }
    p.endObject()
    if (audioId.isEmpty()) return null
    return WireAudio(audioId, uid.ifEmpty { audioId }, page, x, y, w, h, name, rid, ext, durationMs)
}

/** A grid of strings (table cells), or null when not an array. */
private fun stringsGridOrNull(p: JsonPull): List<List<String>>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<List<String>>()
    p.beginArray()
    while (p.hasNext()) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            continue
        }
        val row = ArrayList<String>()
        p.beginArray()
        while (p.hasNext()) stringOrNull(p)?.let { row += it }
        p.endArray()
        out += row
    }
    p.endArray()
    return out
}

/** An `entries` array inside a page_style op. */
private fun entriesOr(p: JsonPull): ArrayList<PageStyleEntry>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<PageStyleEntry>()
    p.beginArray()
    while (p.hasNext()) {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            continue
        }
        var index = -1
        var style: WirePageStyle? = null
        var margins: WireMargins? = null
        // Style keys sit flat in the entry; collect them, then build.
        var pageColor: String? = null
        var template: String? = null
        var patternColor: String? = null
        var spacing: Double? = null
        var accentColor: String? = null
        var params: Map<String, Double> = emptyMap()
        var colors: Map<String, String> = emptyMap()
        var invertColors: Boolean? = null
        var invertImages: Boolean? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "index" -> index = intOr(p, -1)
                "page_color" -> pageColor = stringOrNull(p)
                "template" -> template = stringOrNull(p)
                "pattern_color" -> patternColor = stringOrNull(p)
                "spacing" -> spacing = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                    p.skipValue()
                    null
                }
                "accent_color" -> accentColor = stringOrNull(p)
                "params" -> params = stringDoubleMapOr(p) ?: emptyMap()
                "colors" -> colors = stringStringMapOr(p) ?: emptyMap()
                "invert_colors" -> invertColors = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else {
                    p.skipValue()
                    null
                }
                "invert_images" -> invertImages = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else {
                    p.skipValue()
                    null
                }
                "margins" -> margins = marginsOr(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        if (index >= 0) {
            val hasStyle = pageColor != null || template != null || patternColor != null || spacing != null ||
                accentColor != null || params.isNotEmpty() || colors.isNotEmpty() ||
                invertColors != null || invertImages != null
            if (hasStyle) {
                style = WirePageStyle(pageColor, template, patternColor, spacing, accentColor, params, colors, invertColors, invertImages)
            }
            out += PageStyleEntry(index, style, margins)
        }
    }
    p.endArray()
    return out
}

/** A style-keys object (entry body already consumed key by key is not reusable here). */
private fun styleBodyOr(p: JsonPull): WirePageStyle? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    p.beginObject()
    var pageColor: String? = null
    var template: String? = null
    var patternColor: String? = null
    var spacing: Double? = null
    var accentColor: String? = null
    var params: Map<String, Double> = emptyMap()
    var colors: Map<String, String> = emptyMap()
    var invertColors: Boolean? = null
    var invertImages: Boolean? = null
    while (p.hasNext()) {
        when (p.nextName()) {
            "page_color" -> pageColor = stringOrNull(p)
            "template" -> template = stringOrNull(p)
            "pattern_color" -> patternColor = stringOrNull(p)
            "spacing" -> spacing = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                p.skipValue()
                null
            }
            "accent_color" -> accentColor = stringOrNull(p)
            "params" -> params = stringDoubleMapOr(p) ?: emptyMap()
            "colors" -> colors = stringStringMapOr(p) ?: emptyMap()
            "invert_colors" -> invertColors = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else {
                p.skipValue()
                null
            }
            "invert_images" -> invertImages = if (p.peek() == JsonPull.Token.BOOLEAN) p.nextBoolean() else {
                p.skipValue()
                null
            }
            else -> p.skipValue()
        }
    }
    p.endObject()
    return WirePageStyle(pageColor, template, patternColor, spacing, accentColor, params, colors, invertColors, invertImages)
}

/** Margin keys inside a margins object (no wrapper of its own); nulls omitted. */
private fun writeMarginsBody(w: JsonWrite, m: WireMargins) {
    m.l?.let { w.name("l").value(it) }
    m.t?.let { w.name("t").value(it) }
    m.r?.let { w.name("r").value(it) }
    m.b?.let { w.name("b").value(it) }
}

/** A `margins` object, or null when malformed. */
private fun marginsOr(p: JsonPull): WireMargins? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    var l: Double? = null
    var t: Double? = null
    var r: Double? = null
    var b: Double? = null
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "l" -> l = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                p.skipValue()
                null
            }
            "t" -> t = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                p.skipValue()
                null
            }
            "r" -> r = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                p.skipValue()
                null
            }
            "b" -> b = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
                p.skipValue()
                null
            }
            else -> p.skipValue()
        }
    }
    p.endObject()
    return WireMargins(l, t, r, b)
}

private fun stringDoubleMapOr(p: JsonPull): Map<String, Double>? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    val out = LinkedHashMap<String, Double>()
    p.beginObject()
    while (p.hasNext()) {
        val k = p.nextName()
        out[k] = if (p.peek() == JsonPull.Token.NUMBER) p.nextDouble() else {
            p.skipValue()
            Double.NaN
        }
    }
    p.endObject()
    return out.filterValues { !it.isNaN() }
}

private fun stringStringMapOr(p: JsonPull): Map<String, String>? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    val out = LinkedHashMap<String, String>()
    p.beginObject()
    while (p.hasNext()) {
        val k = p.nextName()
        stringOrNull(p)?.let { out[k] = it }
    }
    p.endObject()
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
        var height = 0.0
        var color = ""
        var pressure = false
        var samples: List<WireSample>? = null
        var strokeStyle: WireStrokeStyle? = null
        var strokeSync: WireSyncMark? = null
        var ids: List<String>? = null
        var index = 0
        var sideways = false
        var at = 0.0
        var size = 0.0
        var a = 0.0
        var b = 0.0
        var imageId = ""
        var rect: List<Double>? = null
        var orientation = 0
        var angle = 0.0
        var flipX = false
        var flipY = false
        var part: List<Double>? = null
        var markup: WireMarkup? = null
        var text: WireText? = null
        var table: WireTable? = null
        var audio: WireAudio? = null
        var entries: ArrayList<PageStyleEntry>? = null
        var docStyle: WirePageStyle? = null
        var docMargins: WireMargins? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "op" -> name = stringOrNull(p)
                "page" -> page = intOr(p, 0)
                "index" -> index = intOr(p, 0)
                "width" -> width = doubleOr(p, 0.0)
                "height" -> height = doubleOr(p, 0.0)
                "sideways" -> sideways = boolOr(p, false)
                "at" -> at = doubleOr(p, 0.0)
                "size" -> size = doubleOr(p, 0.0)
                "a" -> a = doubleOr(p, 0.0)
                "b" -> b = doubleOr(p, 0.0)
                "image_id" -> imageId = stringOr(p, "")
                "rect" -> rect = numberListOr(p)
                "orientation" -> orientation = intOr(p, 0)
                "angle" -> angle = doubleOr(p, 0.0)
                "flip_x" -> flipX = boolOr(p, false)
                "flip_y" -> flipY = boolOr(p, false)
                "part" -> part = numberListOr(p)
                "markup" -> markup = markupOr(p)
                "text" -> text = textOr(p)
                "table" -> table = tableOr(p)
                "audio" -> audio = audioOr(p)
                "entries" -> entries = entriesOr(p)
                "doc_style" -> docStyle = if (p.peek() == JsonPull.Token.BEGIN_OBJECT) styleBodyOr(p) else {
                    p.skipValue()
                    null
                }
                "doc_margins" -> docMargins = marginsOr(p)
                "stroke" -> if (p.peek() == JsonPull.Token.BEGIN_OBJECT) {
                    p.beginObject()
                    while (p.hasNext()) {
                        when (p.nextName()) {
                            "tool" -> tool = stringOr(p, "")
                            "width" -> width = doubleOr(p, 0.0)
                            "color" -> color = stringOr(p, "")
                            "pressure" -> pressure = boolOr(p, false)
                            "samples" -> samples = sampleListOr(p)
                            "style" -> strokeStyle = styleOr(p)
                            "sync" -> strokeSync = syncOr(p)
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
            "add_stroke" -> SyncOp.AddStroke(page, WireStroke(tool, width, color, pressure, samples ?: emptyList(), strokeStyle, strokeSync))
            "erase_strokes" -> SyncOp.EraseStrokes(page, ids ?: emptyList())
            "add_markup" -> markup?.let { SyncOp.AddMarkup(page, it) } ?: SyncOp.Unknown("add_markup")
            "remove_markup" -> SyncOp.RemoveMarkup(page, ids ?: emptyList())
            "page_style" -> SyncOp.PageStyle(entries ?: emptyList(), docStyle, docMargins)
            "add_page" -> SyncOp.AddPage(index, width, height)
            "remove_page" -> SyncOp.RemovePage(index)
            "open_room" -> SyncOp.OpenRoom(page, sideways, at, size)
            "close_room" -> SyncOp.CloseRoom(page, sideways, a, b)
            "add_image" -> {
                val r = rect ?: emptyList()
                SyncOp.AddImage(
                    page,
                    WireImage(
                        imageId, width.toInt(), height.toInt(),
                        r.getOrElse(0) { 0.0 }, r.getOrElse(1) { 0.0 },
                        r.getOrElse(2) { 0.0 }, r.getOrElse(3) { 0.0 },
                        orientation, angle, flipX, flipY,
                        part?.takeIf { it.size == 4 },
                    ),
                )
            }
            "remove_image" -> SyncOp.RemoveImage(page, ids ?: emptyList())
            "add_text" -> text?.let { SyncOp.AddText(page, it) } ?: SyncOp.Unknown("add_text")
            "remove_text" -> SyncOp.RemoveText(page, ids ?: emptyList())
            "add_table" -> table?.let { SyncOp.AddTable(page, it) } ?: SyncOp.Unknown("add_table")
            "remove_table" -> SyncOp.RemoveTable(page, ids ?: emptyList())
            "add_audio" -> audio?.let { SyncOp.AddAudio(page, it) } ?: SyncOp.Unknown("add_audio")
            "remove_audio" -> SyncOp.RemoveAudio(page, ids ?: emptyList())
            else -> SyncOp.Unknown(name ?: "")
        }
    }
    p.endArray()
    return out
}

/** A JSON array of numbers, or null when not an array. */
private fun numberListOr(p: JsonPull): List<Double>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<Double>()
    p.beginArray()
    while (p.hasNext()) {
        if (p.peek() == JsonPull.Token.NUMBER) out += p.nextDouble() else p.skipValue()
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

/** Wire form of a page style snapshot, for page_style ops. */
fun com.xnotes.core.model.PageStyle.toWirePageStyle(): WirePageStyle = WirePageStyle(
    pageColor = pageColor?.let { hex6(it.r, it.g, it.b) },
    template = template,
    patternColor = patternColor?.let { hex8(it.r, it.g, it.b, it.a) },
    spacing = spacing,
    accentColor = accentColor?.let { hex6(it.r, it.g, it.b) },
    params = params.orEmpty(),
    colors = colors.orEmpty().mapValues { hex8(it.value.r, it.value.g, it.value.b, it.value.a) },
    invertColors = null,
    invertImages = null,
)

/** Model form of a received style snapshot (linux PageStyle has no invert fields; they are dropped). */
fun WirePageStyle.toPageStyle(): com.xnotes.core.model.PageStyle = com.xnotes.core.model.PageStyle(
    pageColor = pageColor?.let { parseMarkupColor(it) },
    template = template,
    patternColor = patternColor?.let { parseMarkupColor(it) },
    spacing = spacing,
    accentColor = accentColor?.let { parseMarkupColor(it) },
    params = params.ifEmpty { null },
    colors = colors.mapNotNull { (k, v) -> parseMarkupColor(v)?.let { k to it } }.toMap().ifEmpty { null },
)

/** Wire form of page margins. */
fun com.xnotes.core.model.PageMargins.toWireMargins(): WireMargins = WireMargins(left, top, right, bottom)

/** Model form of received margins. */
fun WireMargins.toPageMargins(): com.xnotes.core.model.PageMargins =
    com.xnotes.core.model.PageMargins(left = l, top = t, right = r, bottom = b)

/** Bytes of a base64 chunk, or null when malformed. */
internal fun bytesOfBase64(s: String): ByteArray? = try {
    s.decodeBase64()?.toByteArray()
} catch (_: Exception) {
    null
}

/** Wire form of a markup, for add_markup ops. */
fun TextMarkup.toWireMarkup(): WireMarkup = WireMarkup(
    id = id,
    type = type.id,
    color = hex6(color.r, color.g, color.b),
    intensity = intensity,
    quads = quads.map { WireQuad(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble(), it.quarter) },
    text = text,
    note = note,
    created = created,
    modified = modified,
    link = link,
)

/** Model form of a received markup, or null when the type/color is unknown. */
fun WireMarkup.toTextMarkup(): TextMarkup? {
    val type = MarkupType.fromId(type) ?: return null
    val color = parseMarkupColor(color) ?: return null
    return TextMarkup(
        id, type, color, intensity,
        quads.map { TextQuad(it.l.toFloat(), it.t.toFloat(), it.r.toFloat(), it.b.toFloat(), it.q) },
        text, note, created, modified, link,
    )
}

private fun hex6(r: Int, g: Int, b: Int): String {
    // Locale-independent lowercase hex (String.format is locale-sensitive on JVM).
    val c = "0123456789abcdef"
    fun h(v: Int) = "${c[(v shr 4) and 0xF]}${c[v and 0xF]}"
    return "#${h(r)}${h(g)}${h(b)}"
}

private fun hex8(r: Int, g: Int, b: Int, a: Int): String {
    val c = "0123456789abcdef"
    fun h(v: Int) = "${c[(v shr 4) and 0xF]}${c[v and 0xF]}"
    return "#${h(r)}${h(g)}${h(b)}${h(a)}"
}

private fun parseMarkupColor(s: String): Rgba? {
    val h = s.removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    val v = h.toLongOrNull(16) ?: return null
    return if (h.length == 6) {
        Rgba((v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF, 255)
    } else {
        Rgba((v shr 24).toInt() and 0xFF, (v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF)
    }
}
