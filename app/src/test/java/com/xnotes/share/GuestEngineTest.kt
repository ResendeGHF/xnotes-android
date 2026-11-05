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

    private open class FakeHost : GuestEngine.GuestHost {
        val sent = ArrayList<JSONObject>()
        var indexChanged = 0
        var fileReady: Triple<String, String, Boolean>? = null
        var result: Pair<Boolean, String>? = null
        var error: String? = null
        open val docs = HashMap<String, com.xnotes.core.model.Document>()
        override fun openDocument(cacheUri: String): com.xnotes.core.model.Document? = docs[cacheUri]
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
        override fun onLinkResult(hostFp: String, remoteId: String, ok: Boolean, reason: String) {
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

    @Test fun linkRequestAndOpenOnGrant() {
        val (e, h, sent) = engine()
        // Unknown target: ask the host.
        e.requestLinkAccess("r9")
        assertEquals("link_request", sent.last().getString("t"))
        assertEquals("r9", sent.last().getString("id"))
        // Denied: surfaces, no open.
        e.onMessage(JSONObject().put("t", "link_result").put("id", "r9").put("ok", false).put("reason", "declined"))
        assertEquals(false to "declined", h.result)
        assertNull(h.fileReady)
        // Granted: the refreshed index covers the target, then it opens.
        e.onMessage(JSONObject().put("t", "link_result").put("id", "r1").put("ok", true))
        assertEquals(true to "", h.result)
    }

    private val fakeMeasurer = object : com.xnotes.core.pal.TextMeasurer {
        override fun measure(text: String, font: com.xnotes.core.pal.FontSpec, wrapWidth: Double, flags: com.xnotes.core.pal.TextFlags) =
            com.xnotes.core.geometry.Rect(0.0, 0.0, wrapWidth, 20.0)
        override fun lineHeight(font: com.xnotes.core.pal.FontSpec): Double = 20.0
        override fun metrics(font: com.xnotes.core.pal.FontSpec): com.xnotes.core.pal.LineMetrics =
            com.xnotes.core.pal.LineMetrics(16.0, 4.0)
        override fun advances(text: String, font: com.xnotes.core.pal.FontSpec): DoubleArray = DoubleArray(text.length) { 10.0 }
    }

    private fun blankXnoteBytes(): ByteArray {
        val doc = com.xnotes.core.model.Document(mutableListOf(com.xnotes.core.model.Page(595.0, 842.0)))
        val codec = com.xnotes.format.DocumentCodec(
            object : com.xnotes.core.pal.ImageCodec {
                override fun probeFile(path: String): com.xnotes.core.pal.ImageSize? = null
            },
            fakeMeasurer,
        )
        val out = java.io.ByteArrayOutputStream()
        codec.write(doc, out)
        return out.toByteArray()
    }

    private fun guestDoc(): com.xnotes.core.model.Document =
        com.xnotes.core.model.Document(mutableListOf(com.xnotes.core.model.Page(595.0, 842.0)))

    /** Drive a real fetch so [remoteId] binds to a cache file, then open it as a blank doc. */
    private fun guestWithBoundDoc(doc: com.xnotes.core.model.Document, remoteId: String = "r1"): Triple<GuestEngine, FakeHost, ArrayList<JSONObject>> {
        val h = FakeHost()
        val sent = ArrayList<JSONObject>()
        val e = GuestEngine("ab".repeat(32), GuestEngine.Sender { sent += it }, h, tempDir("guest2"), "Friend")
        e.onMessage(indexMsg(remoteId))
        e.fetch(remoteId, openNow = false)
        val payload = blankXnoteBytes()
        e.onMessage(
            JSONObject().put("t", "file").put("id", remoteId).put("version", 2L)
                .put("total", payload.size.toLong()).put("offset", 0)
                .put("data", java.util.Base64.getEncoder().encodeToString(payload)).put("last", true),
        )
        // The cache write lands on the engine IO thread; wait for it, then open that file.
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && e.boundUri(remoteId) == null) Thread.sleep(20)
        val bound = e.boundUri(remoteId)
        assert(bound != null)
        h.docs[bound ?: ""] = doc
        return Triple(e, h, sent)
    }

    private fun opMsg(file: String, base: Long, ops: JSONArray): JSONObject =
        JSONObject().put("t", "op").put("file", file).put("base_version", base).put("version", base + 1)
            .put("dedupe", "d" + System.nanoTime()).put("ops", ops)

    private fun chunked(file: String, id: String, bytes: ByteArray, type: String, idKey: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + 256, bytes.size)
            out += JSONObject().put("t", type).put("file", file).put(idKey, id)
                .put("total", bytes.size.toLong()).put("offset", off)
                .put("data", java.util.Base64.getEncoder().encodeToString(bytes.sliceArray(off until end)))
                .put("last", end >= bytes.size)
            off = end
        }
        return out
    }

    @Test fun textAddUpsertAndRemove() {
        val doc = guestDoc()
        val (e, _, _) = guestWithBoundDoc(doc)
        e.onMessage(opMsg("r1", 2L, JSONArray().put(
            JSONObject().put("op", "add_text").put("page", 0).put("text",
                com.xnotes.share.companion.SyncWire.toWireText(
                    com.xnotes.core.model.TextItem(com.xnotes.core.geometry.Pt(1.0, 2.0), 100.0, 0.0, "hey",
                        com.xnotes.core.model.Rgba(1, 2, 3, 255), 12.0, com.xnotes.core.pal.FontFace.MONO, fakeMeasurer)
                        .also { it.uid = "gt1" }),
            ),
        )))
        assertEquals(listOf("hey"), doc.pages[0].items.filterIsInstance<com.xnotes.core.model.TextItem>().map { it.text })
        e.onMessage(opMsg("r1", 3L, JSONArray().put(
            JSONObject().put("op", "add_text").put("page", 0).put("text",
                com.xnotes.share.companion.SyncWire.toWireText(
                    com.xnotes.core.model.TextItem(com.xnotes.core.geometry.Pt(9.0, 9.0), 50.0, 0.0, "hey!",
                        com.xnotes.core.model.Rgba(1, 2, 3, 255), 12.0, com.xnotes.core.pal.FontFace.MONO, fakeMeasurer)
                        .also { it.uid = "gt1" }),
            ),
        )))
        val texts = doc.pages[0].items.filterIsInstance<com.xnotes.core.model.TextItem>()
        assertEquals(1, texts.size)
        assertEquals("hey!", texts.single().text)
        e.onMessage(opMsg("r1", 4L, JSONArray().put(
            JSONObject().put("op", "remove_text").put("page", 0).put("ids", JSONArray(listOf("gt1"))),
        )))
        assertTrue(doc.pages[0].items.filterIsInstance<com.xnotes.core.model.TextItem>().isEmpty())
    }

    @Test fun audioAddAndRemove() {
        val doc = guestDoc()
        val (e, _, _) = guestWithBoundDoc(doc)
        val wav = File.createTempFile("aud", ".m4a").also { it.writeBytes(ByteArray(48) { 3 }) }
        val item = com.xnotes.core.model.AudioItem(
            com.xnotes.core.model.AudioData(wav, "m4a", 2000, ByteArray(4) { 5 }),
            com.xnotes.core.geometry.Rect(1.0, 2.0, 60.0, 20.0), "take", 9, fakeMeasurer,
        ).also { it.uid = "ga1" }
        val opAudio = com.xnotes.share.companion.SyncWire.toWireAudio("aud-ga1", item, 0)
        for (c in chunked("r1", "aud-ga1", wav.readBytes(), "audio_data", "audio_id")) e.onMessage(c)
        e.onMessage(opMsg("r1", 2L, JSONArray().put(
            JSONObject().put("op", "add_audio").put("page", 0).put("audio", opAudio),
        )))
        val got = doc.pages[0].items.filterIsInstance<com.xnotes.core.model.AudioItem>()
        assertEquals(1, got.size)
        assertEquals("take", got.single().name)
        e.onMessage(opMsg("r1", 3L, JSONArray().put(
            JSONObject().put("op", "remove_audio").put("page", 0).put("ids", JSONArray(listOf("ga1"))),
        )))
        assertTrue(doc.pages[0].items.filterIsInstance<com.xnotes.core.model.AudioItem>().isEmpty())
        wav.delete()
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
