package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Remembers each note's last view — zoom, scroll and its View-menu overrides — keyed by
 * document identity, so a note in the granted folder reopens exactly where (and how) the
 * user left it. Held in memory and mirrored to a small JSON file ([JsonStore.viewStates]);
 * [clear]ed when the user forgets the folder, since the keys are only meaningful for that
 * folder's documents.
 *
 * File writes run on a single background worker (coalesced), never on the calling thread:
 * view state is mirrored on tab switches and closes, which must not hitch the UI.
 */
class ViewStateStore(private val store: JsonStore) {

    class View(val zoom: Double, val scrollX: Double, val scrollY: Double, val overrides: ViewOverrides)

    private val views: MutableMap<String, View> = load()

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "view-states").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pending = false
    private var scheduled = false

    private fun load(): MutableMap<String, View> {
        val out = HashMap<String, View>()
        val o = store.read()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            out[key] = View(
                e.optDouble("zoom", 0.0),
                e.optDouble("scrollX", 0.0),
                e.optDouble("scrollY", 0.0),
                ViewOverridesJson.read(e),
            )
        }
        return out
    }

    fun get(key: String): View? = synchronized(lock) { views[key] }

    fun put(key: String, zoom: Double, scrollX: Double, scrollY: Double, overrides: ViewOverrides) {
        synchronized(lock) { views[key] = View(zoom, scrollX, scrollY, overrides) }
        scheduleWrite()
    }

    /** Forget one note's remembered view (e.g. when its file is deleted). */
    fun remove(key: String) {
        val removed = synchronized(lock) { views.remove(key) != null }
        if (removed) scheduleWrite()
    }

    /** Forget the remembered view for every note whose key matches [predicate] — a deleted file, or a deleted folder's whole subtree. */
    fun removeMatching(predicate: (String) -> Boolean) {
        val removed = synchronized(lock) { views.keys.removeAll(predicate) }
        if (removed) scheduleWrite()
    }

    /** Carry remembered views across a rename or move that changed the document id, a folder's descendants included. */
    fun rekeyTree(from: String, to: String) {
        synchronized(lock) {
            val moves = views.keys.mapNotNull { k -> com.xnotes.core.util.DocKeys.moved(k, from, to)?.let { k to it } }
            if (moves.isEmpty()) return
            val values = moves.map { (old, _) -> views.remove(old) }
            moves.forEachIndexed { i, (_, new) -> values[i]?.let { views[new] = it } }
        }
        scheduleWrite()
    }

    /** Forget every remembered view (e.g. when the granted folder is released). */
    fun clear() {
        synchronized(lock) { views.clear() }
        // Clearing must take effect even if the process dies right after: write at once.
        writer.execute { runCatching { store.write(JSONObject()) } }
    }

    /** Block (bounded) until queued writes have landed. Pause/close path only. */
    fun flush(timeoutMs: Long = 2000L) {
        val done = CountDownLatch(1)
        writer.execute { done.countDown() }
        runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun scheduleWrite() {
        val submit = synchronized(lock) {
            pending = true
            if (!scheduled) {
                scheduled = true
                true
            } else false
        }
        if (submit) writer.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val snapshot = synchronized(lock) {
                if (!pending) null else {
                    pending = false
                    toJsonLocked()
                }
            }
            if (snapshot == null) {
                synchronized(lock) {
                    if (!pending) {
                        scheduled = false
                        return
                    }
                }
                continue
            }
            val payload = snapshot
            runCatching { store.write(payload) }
        }
    }

    private fun toJsonLocked(): JSONObject {
        val o = JSONObject()
        for ((k, v) in views) {
            val e = JSONObject().put("zoom", v.zoom).put("scrollX", v.scrollX).put("scrollY", v.scrollY)
            o.put(k, ViewOverridesJson.write(e, v.overrides))
        }
        return o
    }

    private fun toJson(): JSONObject = synchronized(lock) { toJsonLocked() }
}
