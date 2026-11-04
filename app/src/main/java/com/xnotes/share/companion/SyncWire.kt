package com.xnotes.share.companion

import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import java.security.MessageDigest
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
    fun fingerprintOf(tool: String, width: Double, color: String, samples: List<Triple<Double, Double, Double>>): String {
        val sb = StringBuilder()
        sb.append(tool).append('|').append(f3(width)).append('|').append(color).append('|')
        samples.forEachIndexed { i, (x, y, p) ->
            if (i > 0) sb.append(';')
            sb.append(f2(x)).append(',').append(f2(y)).append(',').append(f3(p))
        }
        return MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    fun fingerprintOf(stroke: Stroke): String =
        fingerprintOf(
            stroke.tool.id, stroke.config.baseWidth, hexColor(stroke.config.rgba),
            stroke.samples.map { Triple(it.x, it.y, it.pressure) },
        )

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

    private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun f3(v: Double): String = String.format(Locale.US, "%.3f", v)
}
