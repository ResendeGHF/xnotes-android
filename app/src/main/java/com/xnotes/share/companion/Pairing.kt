package com.xnotes.share.companion

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * SYNC.md v1 pairing: TOFU + 6-digit short authentication string.
 *
 * The companion shows a random code; the host user types it into Connect and it
 * travels inside TLS in `pair_request`. The companion accepts only the
 * currently-displayed code (single use, few attempts), then both sides
 * persistently pin each other's certificate fingerprints. A relay MITM would
 * have to present a different cert, whose fingerprint the user sees at the
 * desktop approval step.
 *
 * Pure JVM apart from the [File] handle, so it unit tests on the host.
 */
object Pairing {
    /** A fresh 6-digit code, zero-padded. */
    fun newCode(rng: SecureRandom = SecureRandom()): String =
        "%06d".format(rng.nextInt(1_000_000))

    /** Canonical fingerprint: lowercase hex SHA-256 of the cert DER. */
    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }

    /** Display form of a fingerprint: `AB:CD:…`. */
    fun displayFingerprint(hex: String): String =
        hex.chunked(2).joinToString(":").uppercase()

    /**
     * Mutual-confirm short authentication string for Android-to-Android
     * pairing: both sides display it and each confirms the match.
     *
     * Derived from both certificate fingerprints (order-independent), so a
     * relay MITM presenting its own cert cannot produce the same SAS on both
     * ends. Six decimal digits, zero-padded.
     */
    fun sasFor(fpA: String, fpB: String): String {
        val (x, y) = if (fpA.lowercase() <= fpB.lowercase()) fpA.lowercase() to fpB.lowercase()
            else fpB.lowercase() to fpA.lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$x|$y".toByteArray(Charsets.UTF_8))
        val n = ((digest[0].toInt() and 0xFF) shl 16) or
            ((digest[1].toInt() and 0xFF) shl 8) or
            (digest[2].toInt() and 0xFF)
        return "%06d".format(n % 1_000_000)
    }
}

/**
 * Persistently pinned companion/host certificate fingerprints, one JSON file:
 * `{"pins": [{"name": …, "fp": …}]}`. The host pins its companion; the
 * companion pins its host(s). Tmp+rename writes, so a crash never half-writes.
 */
class CompanionPins private constructor(private val file: File) {
    data class Pin(val name: String, val fp: String)

    private val lock = Any()
    private var pins: LinkedHashMap<String, Pin> = LinkedHashMap()

    init {
        reloadLocked()
    }

    /** Fingerprint pinned under [name], or null. */
    fun fpFor(name: String): String? = synchronized(lock) { pins[name]?.fp }

    /** Name pinned for [fp], or null. */
    fun nameFor(fp: String): String? = synchronized(lock) { pins.values.firstOrNull { it.fp == fp }?.name }

    /** Trust [fp] as [name] (re-pinning replaces). Persists immediately. */
    fun pin(name: String, fp: String) {
        synchronized(lock) {
            pins[name] = Pin(name, fp.lowercase())
            saveLocked()
        }
    }

    /** Forget [name]. True when something was removed. */
    fun unpin(name: String): Boolean = synchronized(lock) {
        (pins.remove(name) != null).also { if (it) saveLocked() }
    }

    fun all(): List<Pin> = synchronized(lock) { pins.values.toList() }

    private fun reloadLocked() {
        pins = LinkedHashMap()
        if (!file.isFile) return
        try {
            val pinsJson = JSONObject(file.readText()).optJSONArray("pins") ?: return
            for (i in 0 until pinsJson.length()) {
                val o = pinsJson.optJSONObject(i) ?: continue
                val name = o.optString("name").ifEmpty { continue }
                val fp = o.optString("fp").lowercase().ifEmpty { continue }
                if (fp.all { it in '0'..'9' || it in 'a'..'f' } && fp.length == 64) {
                    pins[name] = Pin(name, fp)
                }
            }
        } catch (_: Exception) {
            pins = LinkedHashMap()
        }
    }

    private fun saveLocked() {
        val pinsJson = org.json.JSONArray()
        for (p in pins.values) {
            pinsJson.put(JSONObject().put("name", p.name).put("fp", p.fp))
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(JSONObject().put("pins", pinsJson).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        fun of(dir: File): CompanionPins {
            dir.mkdirs()
            return CompanionPins(File(dir, "companion_pins.json"))
        }
    }
}
