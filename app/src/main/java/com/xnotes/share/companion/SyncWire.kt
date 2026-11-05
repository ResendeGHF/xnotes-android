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
        return JSONObject()
            .put("tool", stroke.tool.id)
            .put("width", stroke.config.baseWidth)
            .put("color", hexColor(stroke.config.rgba))
            .put("pressure", stroke.config.pressureEnabled)
            .put("samples", samples)
    }

    fun toNative(o: JSONObject): Stroke? {
        val tool = Tool.fromId(o.optString("tool")) ?: return null
        val color = RemoteLiveStrokes.parseColor(o.optString("color")) ?: return null
        val cfg = ToolConfig(
            baseWidth = o.optDouble("width", 3.0),
            rgba = color,
            pressureEnabled = o.optBoolean("pressure", true),
        )
        val samples = ArrayList<Sample>()
        val arr = o.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONArray(i) ?: continue
            if (t.length() < 2) continue
            samples += Sample(t.optDouble(0), t.optDouble(1), if (t.length() > 2) t.optDouble(2) else 1.0)
        }
        if (samples.isEmpty()) return null
        return Stroke(tool, cfg, samples)
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
