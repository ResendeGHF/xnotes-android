package com.xnotes.sync

import com.xnotes.core.platform.formatDecimal
import com.xnotes.core.stroke.StrokeFingerprint
import okio.ByteString.Companion.encodeUtf8

/**
 * Content fingerprint of a stroke (SYNC.md): SHA-256 hex, first 16 chars, of
 * `tool|width(%.3f)|color|samples`, samples as `x.2f,y.2f,p.3f` joined by `;`.
 * Retries and double-delivery converge on the same id. Canonical format lives
 * in [StrokeFingerprint]; this stays as the wire-sample entry point.
 */
object SyncFingerprint {
    fun of(tool: String, width: Double, color: String, samples: List<WireSample>): String =
        StrokeFingerprint.of(tool, width, color, samples.map { Triple(it.x, it.y, it.p) })

    /** Canonical test vector (SYNC.md): `pen|3.000|#00e676|…` → `11812f53989ff4dc`. */
    fun checkVector(): Boolean =
        of("pen", 3.0, "#00e676", listOf(WireSample(10.0, 20.0, 0.5), WireSample(11.5, 21.0, 0.6))) == "11812f53989ff4dc"
}
