package com.xnotes.core.stroke

import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.platform.formatDecimal
import okio.ByteString.Companion.encodeUtf8

/**
 * Content fingerprint of a stroke for sync (SYNC.md): SHA-256 hex, first 16
 * chars, of `tool|width(%.3f)|color|samples`, samples as `x.2f,y.2f,p.3f`
 * joined by `;`. Retries and double-delivery converge on the same id.
 *
 * Canonical home of the format: the sync layers and the canvas controllers
 * (which detect committed stroke replacements) all delegate here, so local
 * matching can never drift from what the wire carries.
 */
object StrokeFingerprint {
    fun of(toolId: String, width: Double, colorHex: String, samples: List<Triple<Double, Double, Double>>): String {
        val sb = StringBuilder()
        sb.append(toolId).append('|').append(formatDecimal(width, 3)).append('|').append(colorHex).append('|')
        samples.forEachIndexed { i, (x, y, p) ->
            if (i > 0) sb.append(';')
            sb.append(formatDecimal(x, 2)).append(',').append(formatDecimal(y, 2)).append(',').append(formatDecimal(p, 3))
        }
        return sb.toString().encodeUtf8().sha256().hex().take(16)
    }

    fun of(stroke: Stroke): String =
        of(
            stroke.tool.id, stroke.config.baseWidth, stroke.config.rgba.syncHex(),
            stroke.samples.map { Triple(it.x, it.y, it.pressure) },
        )

    /** Lowercase `#rrggbb` of [Rgba], locale-independent. */
    fun Rgba.syncHex(): String = "#${hex2(r)}${hex2(g)}${hex2(b)}"

    private fun hex2(v: Int): String {
        val c = "0123456789abcdef"
        return "${c[(v shr 4) and 0xF]}${c[v and 0xF]}"
    }
}
