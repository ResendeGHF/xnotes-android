package com.xnotes.share.companion

import java.io.File
import java.util.ArrayDeque
import org.json.JSONObject

/**
 * Shared per-file commit versions + replay dedupes for the sync engines.
 *
 * One instance is shared by every [HostEngine] serving this device's files
 * (the dialed Linux companion and each inbound Android peer), so concurrent
 * editors of the same file see one monotonic version. Tmp+rename persists,
 * like before; all access is synchronized on an internal lock.
 *
 * Pure JVM, so it unit tests on the host.
 */
class SyncVersions(private val dir: File) {
    private val lock = Any()
    private val versions = HashMap<String, Long>()
    private val seenDedupes = HashMap<String, ArrayDeque<String>>()

    init {
        load()
    }

    /** Head version of [id], registering new files at 1 (and only then persisting). */
    fun head(id: String): Long = synchronized(lock) {
        versions[id] ?: run {
            versions[id] = 1L
            saveLocked()
            1L
        }
    }

    /** Bump [id] to the next version and persist. Returns the new head. */
    fun bump(id: String): Long = synchronized(lock) {
        val next = (versions[id] ?: 1L) + 1
        versions[id] = next
        saveLocked()
        next
    }

    /**
     * Replay guard: true the first time [dedupe] is seen for [file], false on
     * repeats. Empty dedupes always pass (old peers omit them).
     */
    fun markDedupe(file: String, dedupe: String): Boolean = synchronized(lock) {
        if (dedupe.isEmpty()) return true
        val seen = seenDedupes.getOrPut(file) { ArrayDeque() }
        if (dedupe in seen) return false
        seen.addLast(dedupe)
        while (seen.size > DEDUPE_KEPT) seen.removeFirst()
        true
    }

    /** Forget all state for [id] (file deleted). */
    fun drop(id: String) {
        synchronized(lock) {
            versions.remove(id)
            seenDedupes.remove(id)
            saveLocked()
        }
    }

    private fun load() {
        try {
            val f = File(dir, VERSIONS)
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            val names = o.names() ?: return
            synchronized(lock) {
                for (i in 0 until names.length()) {
                    val id = names.getString(i)
                    versions[id] = o.optLong(id, 1L).coerceAtLeast(1L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveLocked() {
        try {
            dir.mkdirs()
            val o = JSONObject()
            for ((id, v) in versions) o.put(id, v)
            val tmp = File(dir, "$VERSIONS.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(dir, VERSIONS))) {
                File(dir, VERSIONS).delete()
                tmp.renameTo(File(dir, VERSIONS))
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEDUPE_KEPT = 64
        const val VERSIONS = "sync_versions.json"
    }
}
