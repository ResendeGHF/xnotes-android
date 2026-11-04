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
        override fun liveOverlay(uri: String): RemoteLiveStrokes? = null
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
