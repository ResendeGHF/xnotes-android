package com.xnotes.share.companion

import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeFingerprint
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * SYNC.md v1 stroke wire form, host side: conversion between native strokes and
 * `{tool,width,color,pressure,samples:[[x,y,p]...]}`, plus the content fingerprint
 * (`SHA-256 hex, first 16`, of the canonical form — must match the companion).
 *
 * Pure JVM, so it unit tests on the host.
 */
object SyncWire {
    /** Canonical fingerprint of a stroke; retries and double-delivery converge on it. */
    fun fingerprintOf(tool: String, width: Double, color: String, samples: List<Triple<Double, Double, Double>>): String =
        StrokeFingerprint.of(tool, width, color, samples)

    fun fingerprintOf(stroke: Stroke): String = StrokeFingerprint.of(stroke)

    fun toWire(stroke: Stroke): JSONObject {
        val samples = JSONArray()
        for (s in stroke.samples) samples.put(JSONArray().put(s.x).put(s.y).put(s.pressure))
        val o = JSONObject()
            .put("tool", stroke.tool.id)
            .put("width", stroke.config.baseWidth)
            .put("color", hexColor(stroke.config.rgba))
            .put("pressure", stroke.config.pressureEnabled)
            .put("samples", samples)
            .put("style", toWireStyle(stroke.tool, stroke.config))
        stroke.sync?.let { o.put("sync", JSONObject().put("rid", it.rid).put("ms", it.ms)) }
        return o
    }

    /**
     * Full stroke style for the wire (pencil/taper/highlighter/dashed/speed/
     * calligraphy/neon). Old peers ignore the `style` object and fall back to
     * tool defaults + width/color/pressure, so this stays backward compatible.
     */
    fun toWireStyle(tool: Tool, cfg: ToolConfig): JSONObject {
        val o = JSONObject()
        o.put("pmin", cfg.pressureMinFactor)
        o.put("dir", cfg.directionStrength)
        o.put("speed", cfg.speedStrength)
        if (cfg.taperEnabled) o.put("taper", true)
        o.put("taper_min", cfg.taperMinFactor)
        if (cfg.neon) o.put("neon", true)
        o.put("neon_s", cfg.neonStrength)
        o.put("dash_l", cfg.dashLength)
        o.put("dash_g", cfg.dashGap)
        o.put("hl_alpha", cfg.highlighterAlpha)
        if (cfg.highlighterInverse) o.put("hl_inv", true)
        o.put("pencil_p", cfg.pencilPressure)
        o.put("pencil_d", cfg.pencilDensity)
        o.put("pencil_g", cfg.pencilGrainSize)
        o.put("pencil_s", cfg.pencilShadeResponse)
        // Tool id already travels alongside; defaults below fill anything missing.
        return o
    }

    /** Rebuild a ToolConfig from a `style` object + legacy width/color/pressure. */
    fun styleFrom(tool: Tool, width: Double, color: Rgba, pressure: Boolean, o: JSONObject?): ToolConfig {
        val base = try {
            com.xnotes.core.tools.ToolDefaults.configFor(tool)
        } catch (_: Exception) {
            ToolConfig()
        }.copy(baseWidth = width, rgba = color, pressureEnabled = pressure)
        if (o == null) return base
        return base.copy(
            pressureMinFactor = if (o.isNull("pmin")) base.pressureMinFactor else o.optDouble("pmin", base.pressureMinFactor),
            directionStrength = if (o.isNull("dir")) base.directionStrength else o.optDouble("dir", base.directionStrength),
            speedStrength = if (o.isNull("speed")) base.speedStrength else o.optDouble("speed", base.speedStrength),
            taperEnabled = if (o.isNull("taper")) base.taperEnabled else o.optBoolean("taper", base.taperEnabled),
            taperMinFactor = if (o.isNull("taper_min")) base.taperMinFactor else o.optDouble("taper_min", base.taperMinFactor),
            neon = if (o.isNull("neon")) base.neon else o.optBoolean("neon", base.neon),
            neonStrength = if (o.isNull("neon_s")) base.neonStrength else o.optDouble("neon_s", base.neonStrength),
            dashLength = if (o.isNull("dash_l")) base.dashLength else o.optDouble("dash_l", base.dashLength),
            dashGap = if (o.isNull("dash_g")) base.dashGap else o.optDouble("dash_g", base.dashGap),
            highlighterAlpha = if (o.isNull("hl_alpha")) base.highlighterAlpha else o.optDouble("hl_alpha", base.highlighterAlpha),
            highlighterInverse = if (o.isNull("hl_inv")) base.highlighterInverse else o.optBoolean("hl_inv", base.highlighterInverse),
            pencilPressure = if (o.isNull("pencil_p")) base.pencilPressure else o.optDouble("pencil_p", base.pencilPressure),
            pencilDensity = if (o.isNull("pencil_d")) base.pencilDensity else o.optDouble("pencil_d", base.pencilDensity),
            pencilGrainSize = if (o.isNull("pencil_g")) base.pencilGrainSize else o.optDouble("pencil_g", base.pencilGrainSize),
            pencilShadeResponse = if (o.isNull("pencil_s")) base.pencilShadeResponse else o.optDouble("pencil_s", base.pencilShadeResponse),
        )
    }

    fun toNative(o: JSONObject): Stroke? {
        val tool = Tool.fromId(o.optString("tool")) ?: return null
        val color = RemoteLiveStrokes.parseColor(o.optString("color")) ?: return null
        val width = o.optDouble("width", 3.0)
        val pressure = o.optBoolean("pressure", true)
        val cfg = styleFrom(tool, width, color, pressure, o.optJSONObject("style"))
        val sync = o.optJSONObject("sync")?.let {
            com.xnotes.core.model.SyncMark(it.optInt("rid", -1).takeIf { r -> r >= 0 } ?: return null, it.optInt("ms", 0))
        }
        val samples = ArrayList<Sample>()
        val arr = o.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONArray(i) ?: continue
            if (t.length() < 2) continue
            samples += Sample(t.optDouble(0), t.optDouble(1), if (t.length() > 2) t.optDouble(2) else 1.0)
        }
        if (samples.isEmpty()) return null
        return Stroke(tool, cfg, samples).also { it.sync = sync }
    }

    fun hexColor(c: Rgba): String =
        "#%02x%02x%02x".format(Locale.US, c.r and 0xFF, c.g and 0xFF, c.b and 0xFF)

    /** `#rrggbbaa` (alpha = line opacity), locale-independent. */
    fun hexAlpha(c: Rgba): String =
        "#%02x%02x%02x%02x".format(Locale.US, c.r and 0xFF, c.g and 0xFF, c.b and 0xFF, c.a and 0xFF)

    /** Wire form of a PDF text markup, for add_markup ops. */
    fun toWireMarkup(m: com.xnotes.core.model.TextMarkup): JSONObject {
        val quads = JSONArray()
        for (q in m.quads) {
            quads.put(JSONArray().put(q.left.toDouble()).put(q.top.toDouble()).put(q.right.toDouble()).put(q.bottom.toDouble()).put(q.quarter))
        }
        val o = JSONObject()
            .put("id", m.id).put("type", m.type.id)
            .put("color", hexColor(m.color)).put("intensity", m.intensity)
            .put("quads", quads).put("text", m.text)
            .put("created", m.created).put("modified", m.modified)
        if (m.note != null) o.put("note", m.note)
        if (m.link != null) o.put("link", m.link)
        return o
    }

    /** Model form of a received markup, or null when malformed. */
    fun markupFrom(o: JSONObject): com.xnotes.core.model.TextMarkup? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val type = com.xnotes.core.model.MarkupType.fromId(o.optString("type")) ?: return null
        val color = com.xnotes.canvas.RemoteLiveStrokes.parseColor(o.optString("color")) ?: return null
        val quadsArr = o.optJSONArray("quads") ?: return null
        val quads = ArrayList<com.xnotes.core.pdf.TextQuad>(quadsArr.length())
        for (i in 0 until quadsArr.length()) {
            val q = quadsArr.optJSONArray(i) ?: return null
            if (q.length() < 5) return null
            quads += com.xnotes.core.pdf.TextQuad(
                q.optDouble(0).toFloat(), q.optDouble(1).toFloat(), q.optDouble(2).toFloat(),
                q.optDouble(3).toFloat(), q.optInt(4),
            )
        }
        if (quads.isEmpty()) return null
        return com.xnotes.core.model.TextMarkup(
            id, type, color, o.optDouble("intensity", 0.5), quads,
            o.optString("text"), o.optString("note").ifEmpty { null },
            o.optLong("created", 0L), o.optLong("modified", 0L),
            o.optString("link").ifEmpty { null },
        )
    }

    /** Wire form of a page style snapshot, for page_style ops. */
    fun toWirePageStyle(s: com.xnotes.core.model.PageStyle): JSONObject {
        val o = JSONObject()
        s.pageColor?.let { o.put("page_color", hexColor(it)) }
        s.template?.let { o.put("template", it) }
        s.patternColor?.let { o.put("pattern_color", hexAlpha(it)) }
        s.spacing?.let { o.put("spacing", it) }
        s.accentColor?.let { o.put("accent_color", hexColor(it)) }
        if (!s.params.isNullOrEmpty()) {
            val p = JSONObject()
            for ((k, v) in s.params!!) p.put(k, v)
            o.put("params", p)
        }
        if (!s.colors.isNullOrEmpty()) {
            val c = JSONObject()
            for ((k, v) in s.colors!!) c.put(k, hexAlpha(v))
            o.put("colors", c)
        }
        s.invertColors?.let { o.put("invert_colors", it) }
        s.invertImages?.let { o.put("invert_images", it) }
        return o
    }

    /** Model form of a received style snapshot. */
    fun pageStyleFrom(o: JSONObject): com.xnotes.core.model.PageStyle {
        fun hex(key: String): Rgba? =
            if (o.isNull(key)) null else com.xnotes.canvas.RemoteLiveStrokes.parseColor(o.optString(key))
        val params = o.optJSONObject("params")?.let { p ->
            LinkedHashMap<String, Double>().also { m ->
                val names = p.names() ?: return@also
                for (i in 0 until names.length()) {
                    val k = names.getString(i)
                    if (!p.isNull(k)) m[k] = p.optDouble(k)
                }
            }
        }
        val colors = o.optJSONObject("colors")?.let { p ->
            LinkedHashMap<String, Rgba>().also { m ->
                val names = p.names() ?: return@also
                for (i in 0 until names.length()) {
                    val k = names.getString(i)
                    if (!p.isNull(k)) com.xnotes.canvas.RemoteLiveStrokes.parseColor(p.optString(k))?.let { m[k] = it }
                }
            }
        }
        return com.xnotes.core.model.PageStyle(
            pageColor = hex("page_color"),
            template = o.optString("template").ifEmpty { null },
            patternColor = hex("pattern_color"),
            spacing = if (o.isNull("spacing")) null else o.optDouble("spacing"),
            accentColor = hex("accent_color"),
            params = params?.ifEmpty { null },
            colors = colors?.ifEmpty { null },
            invertColors = if (o.isNull("invert_colors")) null else o.optBoolean("invert_colors"),
            invertImages = if (o.isNull("invert_images")) null else o.optBoolean("invert_images"),
        )
    }

    /** Fingerprint key for an image/audio payload (first 16 hex of SHA-256). */
    fun payloadId(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Canonical two decimals, locale-independent; must match the Linux writer byte for byte. */
    fun canonical2(v: Double): String {
        if (!v.isFinite()) return "0.00"
        val r = kotlin.math.round(v * 100.0) / 100.0
        val n = if (r == 0.0) 0.0 else r
        val neg = n < 0.0
        val a = kotlin.math.abs(n)
        val whole = a.toLong()
        val frac = ((a - whole) * 100.0 + 0.5).toLong().coerceIn(0, 99)
        return (if (neg) "-" else "") + "$whole." + (if (frac < 10) "0$frac" else "$frac")
    }

    /**
     * Stable id for an item that predates per-item uids, so peers that read the
     * same bytes derive the same id. Mirrors the Linux derivation exactly.
     */
    fun legacyUid(kind: String, pos: DoubleArray, size: DoubleArray, index: Int, parts: List<String>): String {
        val all = ArrayList<String>()
        all += kind
        all += "x" + canonical2(pos[0])
        all += "y" + canonical2(pos[1])
        all += "w" + canonical2(size[0])
        all += "h" + canonical2(size[1])
        all += "i$index"
        all.addAll(parts)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(all.joinToString("|").toByteArray(Charsets.UTF_8))
        return "legacy-" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Wire form of a text box, for add_text ops. */
    fun toWireText(t: com.xnotes.core.model.TextItem): JSONObject =
        JSONObject()
            .put("uid", t.uid).put("x", t.pos.x).put("y", t.pos.y)
            .put("width", t.width).put("height", t.height).put("text", t.text)
            .put("color", hexAlpha(t.rgba)).put("point_size", t.pointSize)
            .put("face", t.face.id)
            .put("bold", t.bold).put("italic", t.italic)
            .put("underline", t.underline).put("strike", t.strike)
            .put("align", t.align.name.lowercase()).put("math", t.math)
            .put("link", t.link ?: "")

    /** Wire form of a table, for add_table ops. */
    fun toWireTable(t: com.xnotes.core.model.TableItem): JSONObject {
        val o = JSONObject()
            .put("uid", t.uid).put("x", t.rect.x).put("y", t.rect.y)
            .put("w", t.rect.w).put("h", t.rect.h)
            .put("cols", org.json.JSONArray(t.colFractions))
            .put("rows", org.json.JSONArray(t.rowFractions))
            .put("stroke_color", hexColor(t.strokeRgba)).put("stroke_width", t.strokeWidth)
            .put("link", t.link ?: "")
        if (t.hasCells()) {
            val cells = org.json.JSONArray()
            for (r in 0 until t.rows) {
                val row = org.json.JSONArray()
                for (c in 0 until t.cols) row.put(t.cell(c, r))
                cells.put(row)
            }
            o.put("cells", cells)
        }
        return o
    }

    /** Wire form of an audio pill, for add_audio ops (bytes travel separately). */
    fun toWireAudio(
        audioId: String,
        item: com.xnotes.core.model.AudioItem,
        page: Int,
    ): JSONObject =
        JSONObject()
            .put("audio_id", audioId).put("uid", item.uid).put("page", page)
            .put("x", item.rect.x).put("y", item.rect.y)
            .put("w", item.rect.w).put("h", item.rect.h)
            .put("name", item.name).put("rid", item.rid)
            .put("ext", item.audio.ext).put("duration_ms", item.audio.durationMs)

    /** Wire form of page margins. */
    fun toWireMargins(m: com.xnotes.core.model.PageMargins): JSONObject {
        val o = JSONObject()
        m.left?.let { o.put("l", it) }
        m.top?.let { o.put("t", it) }
        m.right?.let { o.put("r", it) }
        m.bottom?.let { o.put("b", it) }
        return o
    }

    /** Model form of received margins. */
    fun pageMarginsFrom(o: JSONObject): com.xnotes.core.model.PageMargins =
        com.xnotes.core.model.PageMargins(
            left = if (o.isNull("l")) null else o.optDouble("l"),
            top = if (o.isNull("t")) null else o.optDouble("t"),
            right = if (o.isNull("r")) null else o.optDouble("r"),
            bottom = if (o.isNull("b")) null else o.optDouble("b"),
        )
}
