package com.xnotes.share

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-file share policy: the set of SAF document URIs the LAN server may
 * expose. Nothing is shared until the user ticks it in the policy screen, so
 * a newly imported note never leaks onto the network by default.
 *
 * Instances are process singletons per directory ([of]), so the settings UI
 * and the running server always see the same set without re-reading the
 * file. The file itself is tiny (`{"shared": [...]}`), written atomically.
 * Pure JVM apart from the [File] handle, so it unit tests on the host.
 */
class SharePolicy private constructor(private val file: File) {

    private val lock = Any()
    private var shared: LinkedHashSet<String> = LinkedHashSet()

    init {
        reloadLocked()
    }

    /** True when [uri] is selected for sharing. */
    fun isShared(uri: String): Boolean = synchronized(lock) { uri in shared }

    /** Select ([shared] = true) or unselect [uri]. Persists immediately. */
    fun setShared(uri: String, shared: Boolean) {
        synchronized(lock) {
            val changed = if (shared) this.shared.add(uri) else this.shared.remove(uri)
            if (changed) saveLocked()
        }
    }

    /** Snapshot of the selected URIs, in selection order. */
    fun sharedUris(): Set<String> = synchronized(lock) { LinkedHashSet(shared) }

    /** Drop selections whose URI is no longer among [known]. Returns how many were dropped. */
    fun pruneMissing(known: Set<String>): Int = synchronized(lock) {
        val before = shared.size
        shared.retainAll(known)
        if (shared.size != before) saveLocked()
        before - shared.size
    }

    private fun reloadLocked() {
        shared = LinkedHashSet()
        try {
            if (!file.exists()) return
            val array = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("shared") ?: return
            for (i in 0 until array.length()) {
                array.optString(i)?.takeIf { it.isNotBlank() }?.let(shared::add)
            }
        } catch (_: Exception) {
            shared = LinkedHashSet()
        }
    }

    private fun saveLocked() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSONObject().put("shared", JSONArray(shared.toList())).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Best-effort: the in-memory set is still authoritative for this process.
        }
    }

    companion object {
        private const val FILE_NAME = "policy.json"
        private val instances = HashMap<String, SharePolicy>()

        /** The process-wide policy for [dir] (the service's private `files/share/`). */
        fun of(dir: File): SharePolicy = synchronized(instances) {
            val key = File(dir, FILE_NAME).absolutePath
            instances.getOrPut(key) { SharePolicy(File(key)) }
        }
    }
}
