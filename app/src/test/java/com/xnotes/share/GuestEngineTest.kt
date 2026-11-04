package com.xnotes.share

import com.xnotes.share.companion.CompanionPins
import com.xnotes.share.companion.GuestEngine
import com.xnotes.share.companion.Pairing
import com.xnotes.share.companion.PairingClient
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest-side session: index, version-aware fetch, echo drops, SAS dialing. */
class GuestEngineTest {
    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    private class FakeHost : GuestEngine.GuestHost {
        val sent = ArrayList<JSONObject>()
        var indexChanged = 0
        var fileReady: Triple<String, String, Boolean>? = null
        var result: Pair<Boolean, String>? = null
        var error: String? = null
        override fun openDocument(cacheUri: String): com.xnotes.core.model.Document? = null
        override fun repaintDocument(cacheUri: String) = Unit
        override fun repaintLive(cacheUri: String) = Unit
        override fun editorState(cacheUri: String): com.xnotes.canvas.CanvasState? = null
        override fun structureChanged(cacheUri: String) = Unit
        override fun previewRoom(cacheUri: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) = Unit
        override fun clearRoomPreview(cacheUri: String) = Unit
        override fun applyRoomEdit(cacheUri: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean = true
        override fun liveOverlay(cacheUri: String): com.xnotes.canvas.RemoteLiveStrokes? = null
        override fun onIndexChanged(hostFp: String) {
            indexChanged++
        }
        override fun onFileReady(hostFp: String, remoteId: String, cacheUri: String, version: Long, openNow: Boolean) {
            fileReady = Triple(remoteId, cacheUri, openNow)
        }
        override fun onResult(hostFp: String, ok: Boolean, reason: String) {
            result = ok to reason
        }
        override fun onError(reason: String) {
            error = reason
        }
    }

    private fun engine(hostFp: String = "ab".repeat(32)): Triple<GuestEngine, FakeHost, ArrayList<JSONObject>> {
        val h = FakeHost()
        val sent = ArrayList<JSONObject>()
        val e = GuestEngine(hostFp, GuestEngine.Sender { sent += it }, h, tempDir("guest"), "Friend")
        return Triple(e, h, sent)
    }

    private fun indexMsg(vararg ids: String): JSONObject {
        val files = JSONArray()
        for ((i, id) in ids.withIndex()) {
            files.put(JSONObject().put("id", id).put("path", "n$i").put("kind", 0).put("version", 2L).put("size", 10L))
        }
        return JSONObject().put("t", "index").put("full", true).put("files", files)
    }

    @Test fun indexStoredAndReported() {
        val (e, h, _) = engine()
        e.onMessage(indexMsg("r1", "r2"))
        assertEquals(1, h.indexChanged)
        assertEquals(2, e.remoteIndex().size)
    }

    @Test fun startSubscribes() {
        val (e, _, sent) = engine()
        e.start()
        assertEquals("index_subscribe", sent[0].getString("t"))
    }

    @Test fun fetchSkipsSocketOnCacheHit() {
        val (e, h, sent) = engine()
        e.onMessage(indexMsg("r1"))
        // Seed the cache: fetch once, feed chunks, then re-fetch.
        e.fetch("r1", openNow = true)
        assertEquals("fetch", sent.last().getString("t"))
        val payload = "hello-cache".toByteArray()
        e.onMessage(
            JSONObject().put("t", "file").put("id", "r1").put("version", 2L)
                .put("total", payload.size.toLong()).put("offset", 0)
                .put("data", java.util.Base64.getEncoder().encodeToString(payload)).put("last", true),
        )
        // Wait for the IO thread to finish writing.
        Thread.sleep(500)
        assertNotNull(h.fileReady)
        assertEquals("r1", h.fileReady!!.first)
        val cacheUri = h.fileReady!!.second
        assertTrue(cacheUri.startsWith("file://"))
        assertTrue(File(cacheUri.removePrefix("file://")).isFile)
        sent.clear()
        h.fileReady = null
        // Same advertised version → instant cache hit, no socket traffic.
        e.fetch("r1", openNow = false)
        assertTrue(sent.none { it.optString("t") == "fetch" })
        assertEquals("r1", h.fileReady!!.first)
        assertEquals(false, h.fileReady!!.third)
        assertEquals(cacheUri, e.boundUri("r1"))
    }

    @Test fun ownEchoAdvancesVersionWithoutApply() {
        val (e, h, sent) = engine()
        e.onMessage(indexMsg("r1"))
        e.fetch("r1", openNow = false)
        sent.clear()
        // Local stroke submit with a dedupe…
        val stroke = com.xnotes.core.model.Stroke(
            com.xnotes.core.tools.Tool.PEN,
            com.xnotes.core.tools.ToolConfig(baseWidth = 3.0, rgba = com.xnotes.core.model.Rgba(0, 230, 118, 255)),
            listOf(com.xnotes.core.stroke.Sample(10.0, 20.0, 0.5)),
        )
        // No binding yet (fetch never completed): submit is a safe no-op.
        e.submitLocalStroke("file:///nope", 0, stroke, "d1")
        assertTrue(sent.isEmpty())
    }

    @Test fun dialerSasBranchConfirmsWithCertDerivedCode() {
        val dir = tempDir("gsas")
        val pins = CompanionPins.of(dir)
        val ownFp = "aa".repeat(32)
        val peerFp = "bb".repeat(32)
        val sent = ArrayList<JSONObject>()
        var sasShown: Pair<String, String>? = null
        var done: String? = null
        val ui = object : com.xnotes.share.companion.PairingUi {
            override fun showCodeEntry() = Unit
            override fun showStatus(s: String) = Unit
            override fun pairingDone(name: String) {
                done = name
            }
            override fun pairingFailed(reason: String) = Unit
        }
        val pc = PairingClient("Friend", ownFp, pins, ui, PairingClient.Sender { sent += it })
        pc.onSasPeer = { sas, name -> sasShown = sas to name }
        pc.onConnected(peerFp)
        // Host answers with SAS mode (unpinned).
        pc.onMessage(
            JSONObject().put("t", "hello_ack").put("paired", false)
                .put("pair_mode", "sas").put("sas", Pairing.sasFor(ownFp, peerFp))
                .put("companion_name", "Friend"),
        )
        assertNotNull(sasShown)
        assertEquals(Pairing.sasFor(ownFp, peerFp), sasShown!!.first)
        pc.confirmSas()
        val confirm = sent.last()
        assertEquals("pair_confirm", confirm.getString("t"))
        assertEquals(Pairing.sasFor(ownFp, peerFp), confirm.getString("sas"))
        // Host accepts → pinned + done.
        pc.onMessage(JSONObject().put("t", "pair_result").put("ok", true).put("companion_name", "Friend"))
        assertEquals("Friend", done)
        assertEquals(peerFp, pins.fpFor("Friend"))
        pc.close()
    }
}
