package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocKeys
import com.xnotes.platform.JsonStore
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Which documents carry which colour, learned while browsing (each folder's
 * sidecar is read anyway) and topped up on every colour edit — so picking a
 * colour lists matches instantly instead of walking the whole tree over SAF
 * again. Only coloured items are tracked; uncoloured ones never enter.
 *
 * [find] answers from memory; callers verify hits against fresh folder
 * listings (a handful of bulk queries), which drops anything deleted or
 * moved away and heals renames. Everything else (explicit deletes, moves,
 * renames, uncolouring, vault locks) updates the index at once through
 * [noteFolder], [drop], [moved] and [removeMatching.
 *
 * Rows for locked-vault contents stay in RAM only and are never written to
 * disk: vault filenames must not leak into a plaintext config file.
 */
class ColorIndex(private val store: JsonStore) {

    private data class Row(
        val parentDocId: String,
        val colorHex: String,
        /** Covering vault folder id, when inside one; RAM-only, never persisted. */
        val vault: String?,
    )

    private val rows = HashMap<String, Row>()
    private val warmed = HashSet<String>()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "color-index").apply { isDaemon = true } }
    private var scheduled = false

    init {
        restore(store.read())
        while (rows.size > MAX_ROWS) {
            val it = rows.entries.iterator()
            it.next()
            it.remove()
        }
    }

    /**
     * Upsert this folder's coloured rows; drop rows that lost their colour.
     * [vault] is the covering vault folder id when inside one, else null.
     * Call off the main thread (it only touches memory, but callers browse on IO).
     */
    @Synchronized
    fun noteFolder(authority: String, parentDocId: String, entries: List<BrowseEntry>, keyOf: (BrowseEntry) -> String, vault: String?) {
        var changed = false
        for (e in entries) {
            val key = keyOf(e)
            if (!key.startsWith("$authority|")) continue
            val hex = e.color?.let { Rgba.toHex(it) }
            if (hex == null) {
                if (rows.remove(key) != null) changed = true
            } else {
                val row = Row(e.parentDocId, hex, vault)
                if (rows.put(key, row) != row) changed = true
            }
        }
        if (changed) {
            while (rows.size > MAX_ROWS) {
                val it = rows.entries.iterator()
                it.next()
                it.remove()
            }
            writeSoon()
        }
    }

    /** Forget one document (uncoloured or gone). */
    @Synchronized
    fun drop(key: String) {
        if (rows.remove(key) != null) writeSoon()
    }

    /** Forget every row matching [predicate] (keyed by document identity, e.g. a deleted subtree). */
    @Synchronized
    fun removeMatching(predicate: (String) -> Boolean) {
        if (rows.keys.removeAll(predicate)) writeSoon()
    }

    /**
     * Carry rows across a rename or move ([DocKeys.moved] semantics), fixing
     * each moved row's parent folder as well. [newParentDocId] is the moved
     * item's own new parent (its descendants re-derive theirs by prefix).
     */
    @Synchronized
    fun moved(from: String, to: String, newParentDocId: String?) {
        var changed = false
        val auth = from.substringBefore('|', "")
        val moves = rows.keys.mapNotNull { k -> DocKeys.moved(k, from, to)?.let { k to it } }
        for ((old, new) in moves) {
            val r = rows.remove(old) ?: continue
            val np = if (old == from && newParentDocId != null) {
                newParentDocId
            } else {
                DocKeys.moved("$auth|${r.parentDocId}", from, to)?.substringAfter('|') ?: r.parentDocId
            }
            rows[new] = r.copy(parentDocId = np)
            changed = true
        }
        if (changed) writeSoon()
    }

    /** Forget everything learned under a locked vault (its names must not linger anywhere). */
    @Synchronized
    fun dropVault(vaultDocId: String) {
        if (rows.values.removeAll { it.vault == vaultDocId }) writeSoon()
    }

    /** Parent folder ids holding [colorHex] under [authority] (a tree's SAF authority). */
    @Synchronized
    fun parentsWith(authority: String, colorHex: String): Set<String> {
        val prefix = "$authority|"
        val out = HashSet<String>()
        for ((k, r) in rows) {
            if (k.startsWith(prefix) && r.colorHex == colorHex) out.add(r.parentDocId)
        }
        return out
    }

    @Synchronized
    fun markWarmed(authority: String) {
        if (warmed.add(authority)) writeSoon()
    }

    @Synchronized
    fun isWarmed(authority: String): Boolean = warmed.contains(authority)

    @Synchronized
    fun clear() {
        if (rows.isNotEmpty() || warmed.isNotEmpty()) {
            rows.clear()
            warmed.clear()
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
        val w = org.json.JSONArray()
        for (a in warmed) w.put(a)
        o.put("warmed", w)
        val r = JSONObject()
        for ((k, row) in rows) {
            if (row.vault != null) continue
            r.put(
                k,
                JSONObject()
                    .put("parent", row.parentDocId)
                    .put("color", row.colorHex),
            )
        }
        o.put("rows", r)
        return o
    }

    private fun restore(o: JSONObject) {
        try {
            if (o.optInt("version", 1) != 1) return
            val w = o.optJSONArray("warmed") ?: org.json.JSONArray()
            for (i in 0 until w.length()) warmed.add(w.optString(i))
            val r = o.optJSONObject("rows") ?: return
            val keys = r.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val e = r.optJSONObject(k) ?: continue
                rows[k] = Row(e.optString("parent"), e.optString("color"), null)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val WRITE_DELAY_MS = 1500L

        /** Cap on tracked rows; colour use is sparse, this is only a backstop against unbounded growth. */
        private const val MAX_ROWS = 5000
    }
}
