package com.xnotes.sync

import com.xnotes.core.platform.formatDecimal
import okio.ByteString.Companion.encodeUtf8

/**
 * Content fingerprint of a stroke (SYNC.md): SHA-256 hex, first 16 chars, of
 * `tool|width(%.3f)|color|samples`, samples as `x.2f,y.2f,p.3f` joined by `;`.
 * Retries and double-delivery converge on the same id.
 */
object SyncFingerprint {
    fun of(tool: String, width: Double, color: String, samples: List<WireSample>): String {
        val sb = StringBuilder()
        sb.append(tool).append('|').append(formatDecimal(width, 3)).append('|').append(color).append('|')
        samples.forEachIndexed { i, s ->
            if (i > 0) sb.append(';')
            sb.append(formatDecimal(s.x, 2)).append(',').append(formatDecimal(s.y, 2)).append(',').append(formatDecimal(s.p, 3))
        }
        return sb.toString().encodeUtf8().sha256().hex().take(16)
    }

    /** Canonical test vector (SYNC.md): `pen|3.000|#00e676|…` → `11812f53989ff4dc`. */
    fun checkVector(): Boolean =
        of("pen", 3.0, "#00e676", listOf(WireSample(10.0, 20.0, 0.5), WireSample(11.5, 21.0, 0.6))) == "11812f53989ff4dc"
}
