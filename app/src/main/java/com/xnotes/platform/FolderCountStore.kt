package com.xnotes.platform

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How many items each folder holds as the explorer would list them, keyed by
 * folder identity ([com.xnotes.core.util.DocKeys]-style "authority|documentId").
 * Each entry carries the folder's modified time when it was counted, so a
 * folder changed since is counted again. Memory-first, mirrored to disk in
 * batches — so folder rows show their counts on the first paint after a
 * restart instead of flipping from the date once a provider query lands.
 */
class FolderCountStore(private val store: JsonStore) {

    private data class Row(val modified: Long, val count: Int)

    private val rows = HashMap<String, Row>()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "folder-counts").apply { isDaemon = true } }
    private var scheduled = false

    init {
        restore(store.read())
    }

    /** The count for [key] while it was last modified at [modified], else null. Instant. */
    @Synchronized
    fun get(key: String, modified: Long): Int? =
        rows[key]?.takeIf { it.modified == modified }?.count

    @Synchronized
    fun put(key: String, modified: Long, count: Int) {
        val old = rows[key]
        if (old != null && old.modified == modified && old.count == count) return
        rows[key] = Row(modified, count)
        writeSoon()
    }

    /** Forget one folder's count (renamed/moved away; its new key recounts on demand). */
    @Synchronized
    fun remove(key: String) {
        if (rows.remove(key) != null) writeSoon()
    }

    /** Forget every count matching [predicate] — a deleted folder's whole subtree. */
    @Synchronized
    fun removeMatching(predicate: (String) -> Boolean) {
        if (rows.keys.removeAll(predicate)) writeSoon()
    }

    @Synchronized
    fun clear() {
        if (rows.isNotEmpty()) {
            rows.clear()
            writeSoon()
        }
    }

    private fun writeSoon() {
        if (scheduled) return
        scheduled = true
        writer.schedule({ snapshot()?.let { store.write(it) } }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun snapshot(): JSONObject? {
        scheduled = false
        val o = JSONObject()
        o.put("version", 1)
        for ((k, r) in rows) {
            o.put(k, JSONObject().put("modified", r.modified).put("count", r.count))
        }
        return o
    }

    private fun restore(o: JSONObject) {
        try {
            if (o.optInt("version", 1) != 1) return
            for (key in o.keys()) {
                val e = o.optJSONObject(key) ?: continue
                rows[key] = Row(e.optLong("modified", 0L), e.optInt("count", 0))
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        private const val WRITE_DELAY_MS = 1500L
    }
}
