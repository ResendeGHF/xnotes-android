package com.xnotes.ui

import com.xnotes.platform.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The last listing seen of each folder (names, sizes, dates, colours), keyed
 * by parent folder identity ("authority|documentId"). Memory-first, mirrored
 * to disk in batches — so a folder paints its rows on the first frame after
 * a restart, and the live provider query only corrects it in place.
 *
 * Rows are the listing's free metadata only: nothing here costs a file open.
 * Vault contents are never stored (callers skip sealed parents and vault
 * roots): names of encrypted folders must not reach plaintext disk.
 */
class FolderListings(private val store: JsonStore) {

    class Row(
        val name: String,
        val childDocId: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
        val created: Long,
        val colorHex: String?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Row && name == other.name && childDocId == other.childDocId && isDir == other.isDir &&
                size == other.size && modified == other.modified && created == other.created &&
                colorHex == other.colorHex

        override fun hashCode(): Int =
            arrayOf(name, childDocId, isDir, size, modified, created, colorHex).contentHashCode()
    }

    private val parents = object : LinkedHashMap<String, List<Row>>(MAX_PARENTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Row>>?) = size > MAX_PARENTS
    }
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "folder-listings").apply { isDaemon = true } }
    private var scheduled = false

    init {
        restore(store.read())
    }

    /** Remember [rows] as the last listing of [parentKey]. Call off the main thread. */
    @Synchronized
    fun note(parentKey: String, rows: List<Row>) {
        val kept = rows.take(MAX_ROWS)
        if (parents[parentKey] == kept) return // unchanged: no write
        parents[parentKey] = kept
        writeSoon()
    }

    /** The last listing of [parentKey], or null when never listed. Instant. */
    @Synchronized
    fun get(parentKey: String): List<Row>? = parents[parentKey]?.toList()

    /** Forget one folder's listing (its contents changed shape). */
    @Synchronized
    fun dropParent(parentKey: String) {
        if (parents.remove(parentKey) != null) writeSoon()
    }

    /** Forget every listing whose parent matches [predicate] — a deleted subtree. */
    @Synchronized
    fun dropMatching(predicate: (String) -> Boolean) {
        val doomed = parents.keys.filter(predicate)
        if (doomed.isEmpty()) return
        for (k in doomed) parents.remove(k)
        writeSoon()
    }

    @Synchronized
    fun clear() {
        if (parents.isNotEmpty()) {
            parents.clear()
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
        for ((k, rows) in parents) {
            val a = JSONArray()
            for (r in rows) {
                val e = JSONObject()
                    .put("n", r.name)
                    .put("i", r.childDocId)
                    .put("d", r.isDir)
                    .put("s", r.size)
                    .put("m", r.modified)
                    .put("c", r.created)
                if (r.colorHex != null) e.put("h", r.colorHex)
                a.put(e)
            }
            o.put(k, a)
        }
        return o
    }

    private fun restore(o: JSONObject) {
        try {
            if (o.optInt("version", 1) != 1) return
            for (key in o.keys()) {
                val a = o.optJSONArray(key) ?: continue
                val rows = ArrayList<Row>(a.length().coerceAtMost(MAX_ROWS))
                for (i in 0 until a.length().coerceAtMost(MAX_ROWS)) {
                    val e = a.optJSONObject(i) ?: continue
                    val name = e.optString("n").ifEmpty { continue }
                    val id = e.optString("i").ifEmpty { continue }
                    rows.add(
                        Row(
                            name, id, e.optBoolean("d"),
                            e.optLong("s"), e.optLong("m"), e.optLong("c"),
                            e.optString("h").ifEmpty { null },
                        ),
                    )
                }
                if (rows.isNotEmpty()) parents[key] = rows
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        private const val WRITE_DELAY_MS = 1500L
        private const val MAX_PARENTS = 32
        private const val MAX_ROWS = 400
    }
}
