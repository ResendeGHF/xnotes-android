package com.xnotes.share

import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.format.DocumentCodec
import com.xnotes.share.ShareServer.ShareBackend
import com.xnotes.share.companion.HostEngine
import com.xnotes.share.companion.SyncFiles
import com.xnotes.share.companion.SyncWire
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEngineTest {
    private class FakeBackend(val files: MutableMap<String, ByteArray> = LinkedHashMap()) : ShareBackend {
        val dirs = HashMap<String, MutableList<ShareBackend.Entry>>()

        fun addDir(uri: String, name: String, parent: String? = null, size: Long = 0L) {
            val e = ShareBackend.Entry(uri, name, size, 1L, "dir", isDir = true)
            dirs.getOrPut(parent ?: "") { ArrayList() }.add(e)
        }

        fun addFile(uri: String, name: String, parent: String? = null, bytes: ByteArray = ByteArray(10)) {
            files[uri] = bytes
            dirs.getOrPut(parent ?: "") { ArrayList() }.add(ShareBackend.Entry(uri, name, bytes.size.toLong(), 1L, "note"))
        }

        override fun listEntries(dirUri: String?, onlyShared: Boolean): List<ShareBackend.Entry> =
            dirs[dirUri ?: ""] ?: emptyList()

        override fun browseRootSet(): Boolean = true
        override fun isShared(uri: String): Boolean = true
        override fun monitor(): Any = this
        override fun versionOf(uri: String): String? = "v0"
        override fun pdfBytes(uri: String): ShareServer.PdfSnapshot? = null
        override fun rawBytes(uri: String): ShareServer.RawSnapshot? =
            files[uri]?.let { ShareServer.RawSnapshot(it, "v0", "f", "application/x-xnote") }
        override fun replacePdf(uri: String, bytes: ByteArray, baseVersion: String) = ShareServer.ReplaceResult.Rejected(501, "x")
    }

    private class FakeFiles(val files: MutableMap<String, ByteArray>) : SyncFiles {
        override fun read(id: String): ByteArray? = files[id]
        override fun write(id: String, bytes: ByteArray): Boolean {
            files[id] = bytes
            return true
        }
        override fun delete(id: String): Boolean = files.remove(id) != null
        var seq = 0
        override fun create(dirId: String?, name: String, isDir: Boolean): String {
            val id = "new-${seq++}"
            if (!isDir) files[id] = ByteArray(10)
            return id
        }
    }

    private class FakeHost : HostEngine.HostEngineHost {
        val sent = ArrayList<JSONObject>()
        val docs = HashMap<String, Document>()
        var approval: ((kind: String, device: String, name: String, respond: (Boolean) -> Unit) -> Unit)? = null
        var error: String? = null

        override fun send(msg: JSONObject) {
            sent += msg
        }

        override fun openDocument(uri: String): Document? = docs[uri]
        override fun snapshotBytes(doc: Document): ByteArray? = null
        override fun repaintDocument(uri: String) = Unit
        override fun repaintLive(uri: String) {
            liveRepaints += uri
        }
        override fun editorState(uri: String): com.xnotes.canvas.CanvasState? = states[uri]
        override fun structureChanged(uri: String) {
            structured += uri
        }
        val structured = ArrayList<String>()
        override fun liveOverlay(uri: String): RemoteLiveStrokes? = overlays[uri]
        override fun previewRoom(uri: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) {
            roomPreviews += RoomPreview(uri, page, sideways, split, resume, shift)
        }
        override fun clearRoomPreview(uri: String) {
            roomClears += uri
        }
        override fun applyRoomEdit(uri: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean {
            roomEdits += RoomEdit(uri, page, sideways, open, a, b)
            return true
        }

        data class RoomPreview(val uri: String, val page: Int, val sideways: Boolean, val split: Double, val resume: Double, val shift: Double)
        data class RoomEdit(val uri: String, val page: Int, val sideways: Boolean, val open: Boolean, val a: Double, val b: Double)
        val roomPreviews = ArrayList<RoomPreview>()
        val roomClears = ArrayList<String>()
        val roomEdits = ArrayList<RoomEdit>()

        val liveRepaints = ArrayList<String>()
        val states = HashMap<String, com.xnotes.canvas.CanvasState>()
        val overlays = HashMap<String, RemoteLiveStrokes>()
        override fun onApproval(kind: String, device: String, name: String, respond: (Boolean) -> Unit) {
            approval?.invoke(kind, device, name, respond)
        }

        override fun onError(reason: String) {
            error = reason
        }

        fun lastOfType(t: String): JSONObject = sent.last { it.optString("t") == t }
    }

    private data class Fixture(val engine: HostEngine, val host: FakeHost, val backend: FakeBackend, val policy: SharePolicy)

    private fun engine(host: FakeHost = FakeHost(), backend: FakeBackend = FakeBackend(), dir: File? = null): Fixture {
        val d = dir ?: File(System.getProperty("java.io.tmpdir"), "sync-host-${System.nanoTime()}").apply { mkdirs() }
        val policy = SharePolicy.of(File(d, "share"))
        val versions = File(d, "versions.json")
        val e = HostEngine(backend, FakeFiles(backend.files), policy, versions, host, "Pixel 8")
        return Fixture(e, host, backend, policy)
    }

    private fun stroke(id: String = "#00e676"): Stroke =
        Stroke(Tool.PEN, ToolConfig(baseWidth = 3.0, rgba = Rgba(0, 230, 118, 255)), listOf(Sample(10.0, 20.0, 0.5), Sample(11.5, 21.0, 0.6)))

    @Test fun fingerprintVectorMatchesCompanion() {
        assertEquals(
            "11812f53989ff4dc",
            SyncWire.fingerprintOf("pen", 3.0, "#00e676", listOf(Triple(10.0, 20.0, 0.5), Triple(11.5, 21.0, 0.6))),
        )
    }

    @Test fun indexListsSharedSubtreeByPath() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        val policy = f.policy
        b.addDir("d1", "notes")
        b.addFile("f1", "todo.xnote", parent = "d1", bytes = ByteArray(100))
        b.addFile("f2", "other.xnote", bytes = ByteArray(50))
        policy.setShared("d1", true)
        val idx = e.buildIndex()
        assertEquals(listOf("notes", "notes/todo.xnote"), idx.map { it.path })
        assertEquals(0, idx.first { it.id == "f1" }.kind)
        assertEquals(1, idx.first { it.id == "d1" }.kind)
    }

    @Test fun opAppliesToOpenDocAndBroadcasts() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        val policy = f.policy
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        val doc = Document(mutableListOf(Page(300.0, 200.0)))
        h.docs["f1"] = doc
        val wire = SyncWire.toWire(stroke())
        e.onMessage(JSONObject().put("t", "op").put("seq", 1).put("file", "f1").put("base_version", 1).put("dedupe", "a")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "add_stroke").put("page", 0).put("stroke", wire))))
        assertEquals(1, doc.pages[0].items.size)
        val fwd = h.lastOfType("op")
        assertEquals("f1", fwd.getString("file"))
        // Same dedupe twice: second is dropped.
        e.onMessage(JSONObject().put("t", "op").put("seq", 2).put("file", "f1").put("base_version", 2).put("dedupe", "a")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "add_stroke").put("page", 0).put("stroke", wire))))
        assertEquals(1, doc.pages[0].items.size)
    }

    @Test fun pageOpsApplyAndFanSameDedupe() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        f.policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        val doc = Document(mutableListOf(Page(300.0, 200.0)))
        h.docs["f1"] = doc
        e.onMessage(JSONObject().put("t", "op").put("seq", 1).put("file", "f1").put("base_version", 1).put("dedupe", "pg1")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "add_page").put("index", 1).put("width", 300.0).put("height", 200.0))))
        assertEquals(2, doc.pages.size)
        assertEquals(300.0, doc.pages[1].width, 0.001)
        val fwd = h.lastOfType("op")
        // Same dedupe end to end: the sender recognizes its echo and drops it.
        assertEquals("pg1", fwd.getString("dedupe"))
        e.onMessage(JSONObject().put("t", "op").put("seq", 2).put("file", "f1").put("base_version", 2).put("dedupe", "pg2")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "remove_page").put("index", 1))))
        assertEquals(1, doc.pages.size)
        // Never empties the note.
        e.onMessage(JSONObject().put("t", "op").put("seq", 3).put("file", "f1").put("base_version", 3).put("dedupe", "pg3")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "remove_page").put("index", 0))))
        assertEquals(1, doc.pages.size)
    }

    @Test fun opFinishesLiveRunAndLiveRepaintsPartial() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        f.policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        val doc = Document(mutableListOf(Page(300.0, 200.0)))
        h.docs["f1"] = doc
        val overlay = RemoteLiveStrokes(
            com.xnotes.canvas.CanvasState(doc, com.xnotes.core.FakeSurfaceFactory(), com.xnotes.ui.theme.Palette.DEFAULT),
        )
        overlay.update("f1", 0, "run-1", 3.0, com.xnotes.core.model.Rgba(0, 0, 0, 255),
            listOf(com.xnotes.core.geometry.Pt(1.0, 1.0), com.xnotes.core.geometry.Pt(2.0, 2.0)), false)
        h.overlays["f1"] = overlay
        e.onMessage(JSONObject().put("t", "live").put("seq", 1).put("file", "f1").put("page", 0)
            .put("stroke_id", "run-2").put("width", 3.0).put("color", "#000000")
            .put("samples", org.json.JSONArray().put(org.json.JSONArray().put(5.0).put(5.0).put(1.0))).put("done", false))
        assertEquals(listOf("f1"), h.liveRepaints)
        // The commit drops its live run even without a done frame.
        val wire = SyncWire.toWire(stroke())
        e.onMessage(JSONObject().put("t", "op").put("seq", 2).put("file", "f1").put("base_version", 1).put("dedupe", "run-1")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "add_stroke").put("page", 0).put("stroke", wire))))
        assertEquals(1, doc.pages[0].items.size)
    }

    @Test fun roomOpsApplyAndPreviewStreams() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        f.policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        h.docs["f1"] = Document(mutableListOf(Page(300.0, 200.0)))
        e.onMessage(JSONObject().put("t", "room_live").put("seq", 1).put("file", "f1").put("page", 0)
            .put("split", 100.0).put("resume", 100.0).put("shift", 40.0).put("live_id", "r-1").put("done", false))
        assertEquals(listOf(FakeHost.RoomPreview("f1", 0, false, 100.0, 100.0, 40.0)), h.roomPreviews)
        assertEquals(listOf("f1"), h.liveRepaints)
        e.onMessage(JSONObject().put("t", "room_live").put("seq", 2).put("file", "f1").put("page", 0)
            .put("split", 0.0).put("resume", 0.0).put("shift", 0.0).put("live_id", "r-1").put("done", true))
        assertEquals(listOf("f1"), h.roomClears)
        e.onMessage(JSONObject().put("t", "op").put("seq", 3).put("file", "f1").put("base_version", 1).put("dedupe", "r-2")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "open_room").put("page", 0).put("at", 100.0).put("size", 40.0))))
        assertEquals(listOf(FakeHost.RoomEdit("f1", 0, false, true, 100.0, 140.0)), h.roomEdits)
        assertEquals(listOf("f1", "f1"), h.roomClears)
        val fwd = h.lastOfType("op")
        assertEquals("r-2", fwd.getString("dedupe"))
        e.onMessage(JSONObject().put("t", "op").put("seq", 4).put("file", "f1").put("base_version", 2).put("dedupe", "r-3")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "close_room").put("page", 0).put("sideways", true).put("a", 10.0).put("b", 60.0))))
        assertEquals(FakeHost.RoomEdit("f1", 0, true, false, 10.0, 60.0), h.roomEdits.last())
    }

    @Test fun imageBytesThenOpAppliesToOpenDoc() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        f.policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        val doc = Document(mutableListOf(Page(300.0, 200.0)))
        h.docs["f1"] = doc
        val bytes = ByteArray(300 * 1024) { (it % 251).toByte() } // spans two chunks
        e.sendImageBytes("f1", "i-1", bytes)
        val chunks = h.sent.filter { it.optString("t") == "image_data" }
        assertEquals(2, chunks.size)
        h.sent.clear()
        for (c in chunks) e.onMessage(c)
        e.onMessage(JSONObject().put("t", "op").put("seq", 1).put("file", "f1").put("base_version", 1).put("dedupe", "i-1")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "add_image").put("page", 0).put("image_id", "i-1")
                .put("width", 100).put("height", 80)
                .put("rect", org.json.JSONArray().put(10.0).put(20.0).put(50.0).put(40.0))
                .put("orientation", 0).put("angle", 0.0))))
        val items = doc.pages[0].items.filterIsInstance<com.xnotes.core.model.ImageItem>()
        assertEquals(1, items.size)
        assertEquals(com.xnotes.core.geometry.Rect(10.0, 20.0, 50.0, 40.0), items.single().rect)
        assertTrue(items.single().image.file.readBytes().contentEquals(bytes))
    }

    @Test fun imageOpBeforeBytesWaitsForThem() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        f.policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        val doc = Document(mutableListOf(Page(300.0, 200.0)))
        h.docs["f1"] = doc
        val op = JSONObject().put("t", "op").put("seq", 1).put("file", "f1").put("base_version", 1).put("dedupe", "i-9")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "add_image").put("page", 0).put("image_id", "i-9")
                .put("width", 10).put("height", 10)
                .put("rect", org.json.JSONArray().put(0.0).put(0.0).put(5.0).put(5.0))
                .put("orientation", 0).put("angle", 0.0)))
        e.onMessage(op)
        assertTrue(doc.pages[0].items.isEmpty())
        e.sendImageBytes("f1", "i-9", ByteArray(10) { 7 })
        val chunks = h.sent.filter { it.optString("t") == "image_data" }
        h.sent.clear()
        for (c in chunks) e.onMessage(c)
        assertEquals(1, doc.pages[0].items.filterIsInstance<com.xnotes.core.model.ImageItem>().size)
    }

    @Test fun staleBaseGetsResync() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        val policy = f.policy
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        e.onMessage(JSONObject().put("t", "op").put("seq", 1).put("file", "f1").put("base_version", 99).put("dedupe", "a").put("ops", org.json.JSONArray()))
        val rs = h.lastOfType("resync")
        assertEquals("f1", rs.getString("file"))
    }

    @Test fun eraseByFingerprint() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        val policy = f.policy
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        val doc = Document(mutableListOf(Page(300.0, 200.0, mutableListOf(stroke()))))
        h.docs["f1"] = doc
        val fp = SyncWire.fingerprintOf(stroke())
        e.onMessage(JSONObject().put("t", "op").put("seq", 1).put("file", "f1").put("base_version", 1).put("dedupe", "e1")
            .put("ops", org.json.JSONArray().put(JSONObject().put("op", "erase_strokes").put("page", 0).put("ids", org.json.JSONArray().put(fp)))))
        assertTrue(doc.pages[0].items.isEmpty())
    }

    @Test fun fetchStreamsChunks() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        val policy = f.policy
        val big = ByteArray(HostEngine.CHUNK.toInt() + 100) { it.toByte() }
        b.addFile("f1", "todo.xnote", bytes = big)
        policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        h.sent.clear()
        e.onMessage(JSONObject().put("t", "fetch").put("seq", 1).put("id", "f1"))
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && h.sent.none { it.optString("t") == "file" }) Thread.sleep(20)
        val chunks = h.sent.filter { it.optString("t") == "file" }
        assertEquals(2, chunks.size)
        assertEquals(true, chunks.last().optBoolean("last"))
        val joined = chunks.flatMap { java.util.Base64.getDecoder().decode(it.getString("data")).asList() }.toByteArray()
        assertTrue(big.contentEquals(joined))
    }

    @Test fun deleteAsksAndAppliesApproval() {
        val f = engine()
        val (e, h, b) = Triple(f.engine, f.host, f.backend)
        val policy = f.policy
        b.addFile("f1", "todo.xnote", bytes = ByteArray(100))
        policy.setShared("f1", true)
        e.onMessage(JSONObject().put("t", "index_subscribe").put("seq", 0))
        var asked: Triple<String, String, String>? = null
        h.approval = { kind, device, name, respond ->
            asked = Triple(kind, device, name)
            respond(true)
        }
        e.onMessage(JSONObject().put("t", "delete_request").put("seq", 1).put("id", "f1").put("device", "Desktop"))
        assertEquals(Triple("delete", "Desktop", "todo.xnote"), asked)
        assertEquals(true, h.lastOfType("delete_result").optBoolean("ok"))
    }
}
