package com.xnotes.sync

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.platform.fileOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    private class FakeHost : SyncEngineHost {
        val out = ArrayList<SyncMsg>()
        var pairRequest: Pair<String, String>? = null
        var paired: String? = null
        var indexChanged = 0
        var fileReady: String? = null
        var fileHeadReady: String? = null
        var fileGone: String? = null
        var error: String? = null
        var liveDirty = 0
        val strokes = ArrayList<Triple<String, Int, WireStroke>>()
        val erases = ArrayList<Triple<String, Int, List<String>>>()
        val pageAdds = ArrayList<Triple<String, Int, Pair<Double, Double>>>()
        val pageRemoves = ArrayList<Pair<String, Int>>()
        data class RoomEditRec(val file: String, val page: Int, val sideways: Boolean, val open: Boolean, val a: Double, val b: Double)
        data class RoomPreviewRec(val file: String, val page: Int, val sideways: Boolean, val split: Double, val resume: Double, val shift: Double)
        val roomEdits = ArrayList<RoomEditRec>()
        val roomPreviews = ArrayList<RoomPreviewRec>()
        val roomPreviewClears = ArrayList<String>()
        val images = ArrayList<ImageRec>()
        val texts = ArrayList<Pair<String, WireText>>()
        val textRemoves = ArrayList<Pair<String, List<String>>>()
        val tables = ArrayList<Pair<String, WireTable>>()
        val tableRemoves = ArrayList<Pair<String, List<String>>>()
        val audios = ArrayList<Triple<String, WireAudio, ByteArray>>()
        val audioRemoves = ArrayList<Pair<String, List<String>>>()
        val imageRemoves = ArrayList<Pair<String, List<String>>>()
        val shapeRemoves = ArrayList<Pair<String, List<String>>>()
        var resynced: Pair<String, Long>? = null
        data class ImageRec(val file: String, val page: Int, val image: WireImage, val bytes: ByteArray)

        override fun onBytes(data: ByteArray) {
            SyncFrames.Decoder().feed(data).forEach { out += it }
        }

        override fun onPairRequest(deviceName: String, fp: String) {
            pairRequest = deviceName to fp
        }

        override fun onPaired(deviceName: String) {
            paired = deviceName
        }

        override fun onIndexChanged() {
            indexChanged++
        }

        override fun onFileReady(id: String) {
            fileReady = id
        }

        override fun onFileHeadReady(id: String) {
            fileHeadReady = id
        }
        override fun onFileGone(id: String) {
            fileGone = id
        }

        override fun onDeleteResult(ok: Boolean, reason: String?) = Unit
        override fun onCreateResult(ok: Boolean, id: String?, reason: String?) = Unit

        override fun onLinkResult(id: String, ok: Boolean, reason: String?) = Unit

        override fun onError(reason: String) {
            error = reason
        }

        override fun onLiveDirty() {
            liveDirty++
        }

        override fun applyRemoteStroke(fileId: String, page: Int, stroke: WireStroke) {
            strokes += Triple(fileId, page, stroke)
        }

        override fun applyRemoteErase(fileId: String, page: Int, ids: List<String>) {
            erases += Triple(fileId, page, ids)
        }
        override fun applyRemotePageAdd(fileId: String, index: Int, width: Double, height: Double) {
            pageAdds += Triple(fileId, index, width to height)
        }

        override fun applyRemotePageRemove(fileId: String, index: Int) {
            pageRemoves += fileId to index
        }

        override fun applyRemoteRoomEdit(fileId: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double) {
            roomEdits += RoomEditRec(fileId, page, sideways, open, a, b)
        }

        override fun previewRemoteRoom(fileId: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) {
            roomPreviews += RoomPreviewRec(fileId, page, sideways, split, resume, shift)
        }

        override fun clearRemoteRoomPreview(fileId: String) {
            roomPreviewClears += fileId
        }

        override fun applyRemoteImage(fileId: String, page: Int, image: WireImage, bytes: ByteArray) {
            images += ImageRec(fileId, page, image, bytes)
        }

        override fun applyRemoteText(fileId: String, page: Int, text: WireText) {
            texts += fileId to text
        }

        override fun applyRemoteTextRemove(fileId: String, page: Int, ids: List<String>) {
            textRemoves += fileId to ids
        }

        override fun applyRemoteTable(fileId: String, page: Int, table: WireTable) {
            tables += fileId to table
        }

        override fun applyRemoteTableRemove(fileId: String, page: Int, ids: List<String>) {
            tableRemoves += fileId to ids
        }

        override fun applyRemoteAudio(fileId: String, page: Int, audio: WireAudio, bytes: ByteArray, peaks: ByteArray) {
            audios += Triple(fileId, audio, bytes)
        }

        override fun applyRemoteAudioRemove(fileId: String, page: Int, ids: List<String>) {
            audioRemoves += fileId to ids
        }

        override fun applyRemoteImageRemove(fileId: String, page: Int, ids: List<String>) {
            imageRemoves += fileId to ids
        }

        val shapes = ArrayList<ShapeRec>()
        data class ShapeRec(val file: String, val page: Int, val shape: com.xnotes.sync.WireShape)

        override fun applyRemoteShape(fileId: String, page: Int, shape: com.xnotes.sync.WireShape) {
            shapes += ShapeRec(fileId, page, shape)
        }

        override fun applyRemoteShapeRemove(fileId: String, page: Int, ids: List<String>) {
            shapeRemoves += fileId to ids
        }

        val markups = ArrayList<MarkupRec>()
        data class MarkupRec(val file: String, val page: Int, val added: List<TextMarkup>, val removedIds: List<String>)

        override fun applyRemoteMarkups(fileId: String, page: Int, added: List<TextMarkup>, removedIds: List<String>) {
            markups += MarkupRec(fileId, page, added, removedIds)
        }

        val pageStyles = ArrayList<PageStyleRec>()
        data class PageStyleRec(val file: String, val entries: List<PageStyleEntry>, val docStyle: WirePageStyle?, val docMargins: WireMargins?)

        override fun applyRemotePageStyle(fileId: String, entries: List<PageStyleEntry>, docStyle: WirePageStyle?, docMargins: WireMargins?) {
            pageStyles += PageStyleRec(fileId, entries, docStyle, docMargins)
        }

        override fun applyResync(fileId: String, version: Long) {
            resynced = fileId to version
        }

        fun lastSent(): SyncMsg = out.removeLast()
    }

    private fun engine(host: FakeHost = FakeHost(), dir: File? = null): Pair<CompanionEngine, FakeHost> {
        val d = dir ?: File(System.getProperty("java.io.tmpdir"), "sync-eng-${System.nanoTime()}").apply { mkdirs() }
        return CompanionEngine(host, fileOf(d.path), "linux-test", "aa".repeat(32)) to host
    }

    private fun engineWithDisk(host: FakeHost = FakeHost(), dir: File? = null): Triple<CompanionEngine, FakeHost, File> {
        val d = dir ?: File(System.getProperty("java.io.tmpdir"), "sync-eng-${System.nanoTime()}").apply { mkdirs() }
        val cache = File(d, "file-cache").apply { mkdirs() }
        return Triple(CompanionEngine(host, fileOf(d.path), "linux-test", "aa".repeat(32), fileOf(cache.path)), host, cache)
    }

    @Test fun diskCacheSurvivesRestart() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sync-disk-${System.nanoTime()}").apply { mkdirs() }
        val (e1, h1) = engineWithDisk(dir = dir).let { it.first to it.second }
        e1.onConnected()
        e1.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 41L, 10L))),
            ),
        )
        e1.requestOpen("u1")
        assertTrue(h1.lastSent() is SyncMsg.Fetch)
        e1.bytesReceived(
            SyncFrames.encode(SyncMsg.FileChunk(2, "u1", 41L, 10L, 0L, base64Of("0123456789".toByteArray()), last = true)),
        )
        assertEquals("u1", h1.fileReady)
        // A fresh engine over the same dir serves the unchanged file without any fetch.
        val (e2, h2) = engineWithDisk(dir = dir).let { it.first to it.second }
        e2.onConnected()
        e2.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 41L, 10L))),
            ),
        )
        h2.out.clear()
        e2.requestOpen("u1")
        assertEquals("u1", h2.fileReady)
        assertTrue(h2.out.none { it is SyncMsg.Fetch })
        assertEquals("0123456789", e2.fileBytes("u1")!!.decodeToString())
        // A newer advertised version opens the stale bytes at once and catches up with just the edits.
        e2.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(2, -1, true, listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 42L, 10L))),
            ),
        )
        h2.out.clear()
        h2.fileReady = null
        e2.requestOpen("u1")
        assertEquals("u1", h2.fileReady)
        val catchup = h2.out.single()
        assertTrue(catchup is SyncMsg.FetchOps)
        assertEquals(41L, (catchup as SyncMsg.FetchOps).sinceVersion)
    }

    @Test fun fingerprintVector() {
        assertTrue(SyncFingerprint.checkVector())
    }

    @Test fun pairingFlowPinsAndSubscribes() {
        val (e, h) = engine()
        e.onConnected()
        // Unknown host: hello answered unpaired, code required.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Hello(1, "xnotes-android", 1, "Pixel 8", "bb".repeat(32))))
        val ack = h.lastSent()
        assertTrue(ack is SyncMsg.HelloAck && !ack.paired && ack.codeRequired)
        val code = e.beginPairing()
        assertEquals(6, code.length)
        e.bytesReceived(SyncFrames.encode(SyncMsg.PairRequest(2, "Pixel 8", "bb".repeat(32), "000000")))
        // Bad code: rejected at once, no popup.
        assertEquals(false, (h.lastSent() as SyncMsg.PairResult).ok)
        assertNull(h.pairRequest)
        // Right code: popup, desktop approves, pinned + subscribed.
        e.bytesReceived(SyncFrames.encode(SyncMsg.PairRequest(3, "Pixel 8", "bb".repeat(32), code)))
        assertEquals("Pixel 8" to "bb".repeat(32), h.pairRequest)
        e.decidePairing(true)
        assertTrue(h.lastSent() is SyncMsg.IndexSubscribe)
        val result = h.lastSent() as SyncMsg.PairResult
        assertEquals(true, result.ok)
        assertEquals("Pixel 8", h.paired)
        // Reconnect: pinned host is recognized, no code needed.
        val keepDir = File(System.getProperty("java.io.tmpdir"), "sync-eng-keep-${System.nanoTime()}").apply { mkdirs() }
        val (e0, h0) = engine(FakeHost(), keepDir)
        e0.onConnected()
        h0.out.clear()
        e0.bytesReceived(SyncFrames.encode(SyncMsg.Hello(8, "xnotes-android", 1, "Pixel 8", "bb".repeat(32))))
        e0.bytesReceived(SyncFrames.encode(SyncMsg.PairRequest(9, "Pixel 8", "bb".repeat(32), e0.beginPairing())))
        e0.decidePairing(true)
        val (e2, h2) = engine(FakeHost(), keepDir)
        e2.onConnected()
        h2.out.clear()
        e2.bytesReceived(SyncFrames.encode(SyncMsg.Hello(1, "xnotes-android", 1, "Pixel 8", "bb".repeat(32))))
        val ack2 = h2.out[h2.out.size - 2] as SyncMsg.HelloAck
        assertEquals(true, ack2.paired)
        assertEquals(false, ack2.codeRequired)
        assertEquals("Pixel 8", h2.paired)
        assertTrue(h2.lastSent() is SyncMsg.IndexSubscribe)
    }

    @Test fun indexFetchAndOpApply() {
        val (e, h) = engine()
        e.onConnected()
        h.out.clear()
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 41L, 10L))),
            ),
        )
        assertEquals(1, h.indexChanged)
        assertEquals(listOf("u1"), e.files().map { it.id })
        // Fetch the file in one chunk.
        e.requestOpen("u1")
        assertTrue(h.lastSent() is SyncMsg.Fetch)
        e.bytesReceived(
            SyncFrames.encode(SyncMsg.FileChunk(2, "u1", 41L, 10L, 0L, base64Of("0123456789".toByteArray()), last = true)),
        )
        assertEquals("u1", h.fileReady)
        assertEquals("0123456789", e.fileBytes("u1")!!.decodeToString())
        assertEquals(41L, e.fileVersion("u1"))
        // A host commit applies and moves the head; a replayed batch is dropped.
        val stroke = WireStroke("pen", 3.0, "#00e676", true, listOf(WireSample(1.0, 2.0, 0.5)))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(3, "u1", 41L, 42L, "d-1", listOf(SyncOp.AddStroke(0, stroke)))))
        assertEquals(1, h.strokes.size)
        assertEquals(42L, e.fileVersion("u1"))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(4, "u1", 42L, 43L, "d-1", listOf(SyncOp.AddStroke(0, stroke)))))
        assertEquals(1, h.strokes.size)
        // Erase by fingerprint.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(5, "u1", 43L, 44L, "d-2", listOf(SyncOp.EraseStrokes(0, listOf("11812f53989ff4dc"))))))
        assertEquals(listOf(Triple("u1", 0, listOf("11812f53989ff4dc"))), h.erases)
        // Resync drops state and refetches.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Resync(6, "u1", 50L)))
        assertEquals("u1" to 50L, h.resynced)
        assertTrue(h.lastSent() is SyncMsg.Fetch)
    }

    @Test fun markupOpsApplyAndRoundTrip() {
        val (e, h) = engine()
        e.onConnected()
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 41L, 10L))),
            ),
        )
        val markup = WireMarkup(
            "m1", "highlight", "#00e676", 0.5,
            listOf(WireQuad(1.0, 2.0, 3.0, 4.0, 0)),
            "hello", null, 1000L, 2000L, link = null,
        )
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(3, "u1", 41L, 42L, "d-m1", listOf(SyncOp.AddMarkup(0, markup)))))
        assertEquals(1, h.markups.size)
        val rec = h.markups.single()
        assertEquals("u1", rec.file)
        assertEquals(0, rec.page)
        assertEquals(1, rec.added.size)
        assertEquals("m1", rec.added.single().id)
        assertEquals(MarkupType.HIGHLIGHT, rec.added.single().type)
        assertEquals(Rgba(0, 230, 118, 255), rec.added.single().color)
        assertTrue(rec.removedIds.isEmpty())
        // The wire form round-trips through the JSON codec.
        val frame = SyncFrames.encode(SyncMsg.Op(4, "u1", 42L, 43L, "d-m2", listOf(SyncOp.AddMarkup(0, markup))))
        val back = SyncFrames.Decoder().feed(frame).single()
        assertEquals(markup, ((back as SyncMsg.Op).ops.single() as SyncOp.AddMarkup).markup)
        // Removal by id.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(5, "u1", 43L, 44L, "d-m3", listOf(SyncOp.RemoveMarkup(0, listOf("m1"))))))
        assertEquals(2, h.markups.size)
        assertEquals(listOf("m1"), h.markups.last().removedIds)
    }

    @Test fun pageStyleOpsApplyAndRoundTrip() {
        val (e, h) = engine()
        e.onConnected()
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 41L, 10L))),
            ),
        )
        val style = WirePageStyle("#ffffff", "lines", "#0000ff80", 64.0, null, mapOf("a" to 1.5), mapOf("c" to "#ff0000"), true, false)
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Op(
                    3, "u1", 41L, 42L, "d-s1",
                    listOf(
                        SyncOp.PageStyle(
                            listOf(PageStyleEntry(0, style, WireMargins(0.1, 0.1, 0.1, 0.1))),
                            style, null,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, h.pageStyles.size)
        val rec = h.pageStyles.single()
        assertEquals("u1", rec.file)
        assertEquals(1, rec.entries.size)
        assertEquals(0, rec.entries.single().index)
        assertEquals(style, rec.entries.single().style)
        assertEquals(WireMargins(0.1, 0.1, 0.1, 0.1), rec.entries.single().margins)
        assertEquals(style, rec.docStyle)
        assertNull(rec.docMargins)
        // Round-trips through the JSON codec.
        val back = SyncFrames.Decoder().feed(
            SyncFrames.encode(SyncMsg.Op(4, "u1", 42L, 43L, "d-s2", listOf(SyncOp.PageStyle(listOf(PageStyleEntry(1, style, null)), null, WireMargins(0.05, 0.05, 0.05, 0.05))))),
        ).single()
        val rt = ((back as SyncMsg.Op).ops.single() as SyncOp.PageStyle)
        assertEquals(1, rt.entries.size)
        assertEquals(1, rt.entries.single().index)
        assertEquals(style, rt.entries.single().style)
        assertEquals(WireMargins(0.05, 0.05, 0.05, 0.05), rt.docMargins)
    }

    @Test fun linkRequestAndResultRoundTrip() {
        val (e, h) = engine()
        e.onConnected()
        // The guest asks; the request encodes.
        e.requestLinkAccess("f9")
        val sent = h.lastSent()
        assertTrue(sent is SyncMsg.LinkRequest && sent.id == "f9")
        // The host answers; the result surfaces with its id.
        var got: Triple<String, Boolean, String?>? = null
        val h2 = object : SyncEngineHost by h {
            override fun onLinkResult(id: String, ok: Boolean, reason: String?) {
                got = Triple(id, ok, reason)
            }
        }
        val d = File(System.getProperty("java.io.tmpdir"), "sync-eng-${System.nanoTime()}").apply { mkdirs() }
        val e2 = CompanionEngine(h2, fileOf(d.path), "linux-test", "aa".repeat(32))
        e2.onConnected()
        e2.bytesReceived(SyncFrames.encode(SyncMsg.LinkResult(3, 1, "f9", true, null)))
        assertEquals(Triple("f9", true, null), got)
    }

    @Test fun pageOpsApplyAndEchoIsDropped() {
        val (e, h) = engine()
        e.onConnected()
        h.out.clear()
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 7L, 10L)))))
        assertEquals(7L, e.fileVersion("u1"))
        // A host page add applies and moves the head.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(2, "u1", 7L, 8L, "p-1", listOf(SyncOp.AddPage(1, 595.0, 842.0)))))
        assertEquals(listOf(Triple("u1", 1, 595.0 to 842.0)), h.pageAdds)
        assertEquals(8L, e.fileVersion("u1"))
        // A host page remove applies too.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(3, "u1", 8L, 9L, "p-2", listOf(SyncOp.RemovePage(1)))))
        assertEquals(listOf("u1" to 1), h.pageRemoves)
        assertEquals(9L, e.fileVersion("u1"))
        // Our own submitted batch is remembered: the host's fan-out echo
        // (same dedupe, or with the :fanned suffix) moves the head but never
        // re-applies.
        e.submitOp("u1", 9L, "l-1", listOf(SyncOp.AddStroke(0, WireStroke("pen", 3.0, "#000000", true, emptyList()))))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(4, "u1", 9L, 10L, "l-1", listOf(SyncOp.AddStroke(0, WireStroke("pen", 3.0, "#000000", true, emptyList()))))))
        assertTrue(h.strokes.isEmpty())
        assertEquals(10L, e.fileVersion("u1"))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(5, "u1", 10L, 11L, "l-1:fanned", listOf(SyncOp.AddStroke(0, WireStroke("pen", 3.0, "#000000", true, emptyList()))))))
        assertTrue(h.strokes.isEmpty())
        assertEquals(11L, e.fileVersion("u1"))
    }

    @Test fun indexDeltaAdvancesHeadWithoutPruning() {
        val (e, h) = engine()
        e.onConnected()
        h.out.clear()
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "a.xnote", 0, 7L, 10L), SyncFileEntry("u2", "b.xnote", 0, 3L, 5L))),
            ),
        )
        assertEquals(7L, e.fileVersion("u1"))
        // A delta for u1 moves its head but must not retire u2.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(2, -1, false, listOf(SyncFileEntry("u1", "a.xnote", 0, 9L, 12L)))))
        assertEquals(9L, e.fileVersion("u1"))
        assertEquals(3L, e.fileVersion("u2"))
        assertEquals(listOf("u1", "u2"), e.files().map { it.id }.sorted())
        // A full listing still retires what it omits.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(3, -1, true, listOf(SyncFileEntry("u1", "a.xnote", 0, 9L, 12L)))))
        assertEquals(listOf("u1"), e.files().map { it.id })
    }

    @Test fun renamePinKeepsGrant() {
        val (e, h) = engine()
        e.onConnected()
        h.out.clear()
        // Unknown name: nothing happens.
        assertFalse(e.renamePin("ghost", "poltergeist"))
        // Pair a host through the full flow, then rename it.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Hello(1, "xnotes-android", 1, "Pixel 8", "bb".repeat(32))))
        e.bytesReceived(SyncFrames.encode(SyncMsg.PairRequest(2, "Pixel 8", "bb".repeat(32), e.beginPairing())))
        e.decidePairing(true)
        assertEquals("Pixel 8", e.pairedDevice())
        assertTrue(e.renamePin("Pixel 8", "Tablet"))
        assertEquals("Tablet", e.pairedDevice())
        assertEquals(listOf("Tablet"), e.pins().map { it.name })
        // Old name is gone.
        assertFalse(e.renamePin("Pixel 8", "Phone"))
        h.out.clear()
    }

    @Test fun pageOpsRoundTripCodec() {
        val ops = listOf(SyncOp.AddPage(2, 595.0, 842.0), SyncOp.RemovePage(0))
        val msg = SyncMsg.Op(1, "u1", 3L, 4L, "d", ops)
        val back = SyncFrames.Decoder().feed(SyncFrames.encode(msg)).single() as SyncMsg.Op
        assertEquals(ops, back.ops)
    }

    @Test fun roomOpsRoundTripCodec() {
        val ops = listOf(SyncOp.OpenRoom(1, false, 200.0, 100.0), SyncOp.CloseRoom(0, true, 10.0, 60.0))
        val msg = SyncMsg.Op(1, "u1", 3L, 4L, "d", ops)
        val back = SyncFrames.Decoder().feed(SyncFrames.encode(msg)).single() as SyncMsg.Op
        assertEquals(ops, back.ops)
        val live = SyncMsg.RoomLive(2, "u1", 1, false, 200.0, 200.0, 100.0, "r-1", done = false)
        val liveBack = SyncFrames.Decoder().feed(SyncFrames.encode(live)).single()
        assertEquals(live, liveBack)
        val done = SyncMsg.RoomLive(3, "u1", 1, false, 0.0, 0.0, 0.0, "r-1", done = true)
        assertEquals(done, SyncFrames.Decoder().feed(SyncFrames.encode(done)).single())
    }

    @Test fun roomOpAppliesAndPreviewClears() {
        val (e, h) = engine()
        e.onConnected()
        h.out.clear()
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 7L, 10L)))))
        // Live preview frames reach the host and repaint.
        e.bytesReceived(SyncFrames.encode(SyncMsg.RoomLive(2, "u1", 0, false, 200.0, 200.0, 50.0, "r-9", done = false)))
        assertEquals(1, h.roomPreviews.size)
        assertEquals(SyncEngineTest.FakeHost.RoomPreviewRec("u1", 0, false, 200.0, 200.0, 50.0), h.roomPreviews.single())
        assertEquals(1, h.liveDirty)
        // Done drops the preview.
        e.bytesReceived(SyncFrames.encode(SyncMsg.RoomLive(3, "u1", 0, false, 0.0, 0.0, 0.0, "r-9", done = true)))
        assertEquals(listOf("u1"), h.roomPreviewClears)
        // The commit clears any preview and applies.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(4, "u1", 7L, 8L, "r-10", listOf(SyncOp.OpenRoom(0, false, 200.0, 50.0)))))
        assertEquals(listOf("u1", "u1"), h.roomPreviewClears)
        assertEquals(listOf(SyncEngineTest.FakeHost.RoomEditRec("u1", 0, false, true, 200.0, 250.0)), h.roomEdits)
        assertEquals(8L, e.fileVersion("u1"))
        // Unknown op names still skip cleanly alongside room ops.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(5, "u1", 8L, 9L, "r-11", listOf(SyncOp.CloseRoom(0, true, 10.0, 60.0)))))
        assertEquals(2, h.roomEdits.size)
        assertEquals(SyncEngineTest.FakeHost.RoomEditRec("u1", 0, true, false, 10.0, 60.0), h.roomEdits.last())
    }

    @Test fun imageOpRoundTripAppliesAfterChunks() {
        val (e, h) = engine()
        e.onConnected()
        h.out.clear()
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 7L, 10L)))))
        val bytes = ByteArray(300 * 1024) { (it % 251).toByte() } // spans two chunks
        // Op before bytes: held, then applied once the last chunk lands.
        val img = WireImage("i-1", 100, 80, 10.0, 20.0, 50.0, 40.0, 0, 0.0, false, false, null)
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(2, "u1", 7L, 8L, "i-1", listOf(SyncOp.AddImage(0, img)))))
        assertTrue(h.images.isEmpty())
        e.sendImageData("u1", "i-1", bytes)
        // Drain what the engine queued for the socket back into itself.
        val frames = h.out.toList()
        h.out.clear()
        for (m in frames) e.bytesReceived(SyncFrames.encode(m))
        assertEquals(1, h.images.size)
        assertEquals("u1", h.images.single().file)
        assertEquals(img, h.images.single().image)
        assertTrue(h.images.single().bytes.contentEquals(bytes))
        assertEquals(8L, e.fileVersion("u1"))
    }

    @Test fun decoderEmitsCoalescedBurst() {
        val frames = (1..60).map {
            SyncFrames.encode(
                SyncMsg.Live(it, "u1", 0, "s-1", "pen", 3.0, "#00e676", listOf(WireSample(it.toDouble(), 100.0, 0.5)), done = false),
            )
        }
        val blob = frames.fold(ByteArray(0)) { acc, f -> acc + f }
        val out = SyncFrames.Decoder().feed(blob)
        assertEquals(60, out.size)
        assertEquals((1..60).toList(), out.map { (it as SyncMsg.Live).seq })
        // And split across an arbitrary byte boundary.
        val half = blob.size / 2
        val d2 = SyncFrames.Decoder()
        val first = d2.feed(blob.sliceArray(0 until half))
        val rest = d2.feed(blob.sliceArray(half until blob.size))
        assertEquals((1..60).toList(), (first + rest).map { (it as SyncMsg.Live).seq })
    }

    @Test fun imageOpCodecRoundTrip() {
        val img = WireImage("i-7", 640, 480, 1.0, 2.0, 3.0, 4.0, 90, 0.5, true, false, listOf(0.0, 0.0, 1.0, 0.5))
        val msg = SyncMsg.Op(1, "u1", 3L, 4L, "d", listOf(SyncOp.AddImage(2, img)))
        val back = SyncFrames.Decoder().feed(SyncFrames.encode(msg)).single() as SyncMsg.Op
        assertEquals(listOf(SyncOp.AddImage(2, img)), back.ops)
    }

    @Test fun liveFrameUpdatesOverlay() {
        // Two Android-style live frames then draw: the run must paint.
        val (e, h) = engine()
        val state = com.xnotes.canvas.CanvasState(
            com.xnotes.core.model.Document.blank(1),
            com.xnotes.core.FakeSurfaceFactory(),
            com.xnotes.canvas.TestPalette(),
        )
        state.viewportW = 800
        state.viewportH = 600
        state.relayout()
        val overlay = com.xnotes.canvas.RemoteLiveStrokes(state)
        overlay.showingFile = "u1"
        e.setLiveOverlay("u1", overlay)
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Live(1, "u1", 0, "s-1", "pen", 3.0, "#00e676", listOf(WireSample(10.0, 20.0, 0.5)), done = false),
            ),
        )
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Live(2, "u1", 0, "s-1", "pen", 3.0, "#00e676", listOf(WireSample(11.5, 21.0, 0.6)), done = false),
            ),
        )
        assertEquals(2, h.liveDirty)
        val r = com.xnotes.core.FakeRenderer()
        // Draw under the same content transform drawOverlay uses.
        val o = state.origin()
        r.withSave {
            r.translate(o.x, o.y)
            r.scale(state.zoom, state.zoom)
            overlay.draw(r)
        }
        assertTrue("live run must paint, got ${r.ops} ribbons=${r.ribbonRuns}", r.ops.any { it == "strokePolyline" || it == "fillPath" || it == "strokePath" } || r.ribbonRuns.isNotEmpty() || r.pathFills.isNotEmpty())
        // The same run must flag partial damage (never a silent full frame).
        val damage = overlay.dirtyViewportRect()
        assertTrue("live run produced no damage", damage != null && damage.w > 0 && damage.h > 0)
        // Commit drops the run: no double draw.
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Op(3, "u1", 7L, 8L, "s-1", listOf(SyncOp.AddStroke(0, WireStroke("pen", 3.0, "#00e676", true, emptyList())))),
            ),
        )
        val r2 = com.xnotes.core.FakeRenderer()
        r2.withSave {
            r2.translate(o.x, o.y)
            r2.scale(state.zoom, state.zoom)
            overlay.draw(r2)
        }
        assertTrue("run must be gone after commit, got ${r2.ops}", r2.ops.none { it == "strokePolyline" || it == "fillPath" || it == "strokePath" } && r2.ribbonRuns.isEmpty())
    }

    @Test fun strokeStyleCarriesPencilKnobs() {
        val before = WireStroke(
            "pencil", 2.5, "#0a141e", false, listOf(WireSample(1.0, 2.0, 0.5)),
            WireStrokeStyle(pencilP = 1.2, pencilD = 0.8, pencilG = 2.0, pencilS = 1.1, taper = true, taperMin = 0.5),
        )
        val enc = SyncFrames.encode(SyncMsg.Op(1, "u1", 0L, 1L, "d1", listOf(SyncOp.AddStroke(0, before))))
        val back = ((SyncFrames.Decoder().feed(enc).single() as SyncMsg.Op).ops.single() as SyncOp.AddStroke).stroke
        assertEquals("pencil", back.tool)
        assertEquals(1.2, back.style?.pencilP)
        assertEquals(0.8, back.style?.pencilD)
        assertEquals(2.0, back.style?.pencilG)
        assertEquals(1.1, back.style?.pencilS)
        assertEquals(true, back.style?.taper)
        // Old peers ignore unknown keys: a legacy frame without style still parses.
        val legacyJson = """{"t":"op","seq":2,"file":"u1","base_version":0,"version":1,"dedupe":"d2","ops":[{"op":"add_stroke","page":0,"stroke":{"tool":"pen","width":3.0,"color":"#00e676","pressure":true,"samples":[[1.0,2.0,0.5]]}}]}"""
        val legacyBytes = legacyJson.encodeToByteArray()
        val legacyFrame = ByteArray(4 + legacyBytes.size).also {
            val n = legacyBytes.size
            it[0] = (n ushr 24).toByte()
            it[1] = (n ushr 16).toByte()
            it[2] = (n ushr 8).toByte()
            it[3] = n.toByte()
            legacyBytes.copyInto(it, 4)
        }
        val legacy = SyncFrames.Decoder().feed(legacyFrame).single()
        val legacyStroke = ((legacy as SyncMsg.Op).ops.single() as SyncOp.AddStroke).stroke
        assertEquals(null, legacyStroke.style)
    }

    @Test fun pencilFieldIsDeterministic() {
        val a = com.xnotes.core.stroke.PencilField.stippleMask(0.5)
        val b = com.xnotes.core.stroke.PencilField.stippleMask(0.5)
        assertTrue(a.contentEquals(b))
        assertEquals(256 * 256, a.size)
        assertTrue(a.any { it } && a.any { !it })
        // Denser graphite keeps every grain of a lighter one (nesting, no reshuffle).
        val light = com.xnotes.core.stroke.PencilField.stippleMask(0.2)
        val heavy = com.xnotes.core.stroke.PencilField.stippleMask(0.9)
        for (i in light.indices) if (light[i]) assertTrue(heavy[i])
    }

    @Test fun contentOpsRoundTrip() {
        val ops = listOf(
            SyncOp.AddText(0, WireText("u1", 10.0, 20.0, 100.0, 30.0, "hi", "#ff0000", 14.0, "sans", bold = true, align = "center", link = "L")),
            SyncOp.RemoveText(0, listOf("u1")),
            SyncOp.AddTable(1, WireTable("t1", 0.0, 0.0, 200.0, 100.0, listOf(0.5, 0.5), listOf(1.0), "#000000", 2.0, listOf(listOf("a", "b")))),
            SyncOp.RemoveTable(1, listOf("t1")),
            SyncOp.AddAudio(2, WireAudio("a1", "u2", 2, 5.0, 5.0, 120.0, 40.0, "rec", 7, "m4a", 3000)),
            SyncOp.RemoveAudio(2, listOf("a1")),
            SyncOp.RemoveImage(0, listOf("img-9")),
            SyncOp.AddShape(
                0, WireShape(
                    "sh1", "rectangle", listOf(1.0, 2.0), listOf(30.0, 40.0),
                    "#00e676", 3.0, "#ffffff", neon = true, rotation = 0.5, flip = "x",
                    adj = listOf(0.25), headEnd = "open", liveId = "s-9", locked = true,
                ),
            ),
            SyncOp.RemoveShape(0, listOf("sh1")),
        )
        val back = ((SyncFrames.Decoder().feed(SyncFrames.encode(SyncMsg.Op(1, "f", 0L, 1L, "d", ops))).single() as SyncMsg.Op).ops)
        assertEquals(ops, back)
    }

    @Test fun contentOpsRouteToHost() {
        val (e, h) = engine()
        e.onConnected()
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 7L, 10L)))))
        assertEquals(7L, e.fileVersion("u1"))
        val text = WireText("t1", 10.0, 20.0, 100.0, 30.0, "hi", "#ff0000", 14.0, "mono", bold = true, align = "center", link = "L")
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(2, "u1", 7L, 8L, "d1", listOf(SyncOp.AddText(0, text)))))
        assertEquals("t1", h.texts.single().second.uid)
        assertEquals(true, h.texts.single().second.bold)
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(3, "u1", 8L, 9L, "d2", listOf(SyncOp.RemoveText(0, listOf("t1"))))))
        assertEquals(listOf("t1"), h.textRemoves.single().second)
        val table = WireTable("tb1", 0.0, 0.0, 200.0, 100.0, listOf(0.5, 0.5), listOf(1.0), "#000000", 2.0, listOf(listOf("a", "b")))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(4, "u1", 9L, 10L, "d3", listOf(SyncOp.AddTable(0, table)))))
        assertEquals("tb1", h.tables.single().second.uid)
        val audBytes = ByteArray(16) { it.toByte() }
        e.bytesReceived(SyncFrames.encode(SyncMsg.AudioChunk(90, "u1", "a1", audBytes.size.toLong(), 0, base64Of(audBytes), last = true)))
        e.bytesReceived(SyncFrames.encode(SyncMsg.AudioPeaks(91, "u1", "a1", 4L, 0, base64Of(ByteArray(4) { 9 }), last = true)))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(5, "u1", 10L, 11L, "d4", listOf(SyncOp.AddAudio(1, WireAudio("a1", "au1", 1, 5.0, 5.0, 120.0, 40.0, "n", 3, "m4a", 1000))))))
        assertEquals("au1", h.audios.single().second.uid)
        // Unknown ops never break the batch: known siblings still apply.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(6, "u1", 11L, 12L, "d5",
            listOf(SyncOp.Unknown("future_op"), SyncOp.RemoveImage(0, listOf("img-9"))))))
        assertEquals(listOf("img-9"), h.imageRemoves.single().second)
        assertEquals(12L, e.fileVersion("u1"))
    }

    @Test fun shapeOpsRouteToHostAndSnapDropsItsLiveRun() {
        val (e, h) = engine()
        e.onConnected()
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 7L, 10L)))))
        val state = com.xnotes.canvas.CanvasState(
            com.xnotes.core.model.Document.blank(1),
            com.xnotes.core.FakeSurfaceFactory(),
            com.xnotes.canvas.TestPalette(),
        )
        state.viewportW = 800
        state.viewportH = 600
        state.relayout()
        val overlay = com.xnotes.canvas.RemoteLiveStrokes(state)
        overlay.showingFile = "u1"
        e.setLiveOverlay("u1", overlay)
        // The pre-snap live ink is on screen...
        e.bytesReceived(SyncFrames.encode(SyncMsg.Live(2, "u1", 0, "s-9", "pen", 3.0, "#00e676", listOf(WireSample(10.0, 20.0, 0.5)), done = false)))
        e.bytesReceived(SyncFrames.encode(SyncMsg.Live(2, "u1", 0, "s-9", "pen", 3.0, "#00e676", listOf(WireSample(11.5, 21.0, 0.6)), done = false)))
        val r = com.xnotes.core.FakeRenderer()
        val o = state.origin()
        r.withSave {
            r.translate(o.x, o.y)
            r.scale(state.zoom, state.zoom)
            overlay.draw(r)
        }
        assertTrue("live run must paint", r.ribbonRuns.isNotEmpty() || r.ops.any { it == "strokePolyline" })
        // ...then the snap lands as a shape carrying the live id: the run vanishes at once.
        val shape = WireShape("sh1", "rectangle", listOf(1.0, 2.0), listOf(30.0, 40.0), "#00e676", 3.0, null, liveId = "s-9")
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(3, "u1", 7L, 8L, "d1", listOf(SyncOp.AddShape(0, shape)))))
        assertEquals("sh1", h.shapes.single().shape.uid)
        assertEquals("rectangle", h.shapes.single().shape.kind)
        val r2 = com.xnotes.core.FakeRenderer()
        r2.withSave {
            r2.translate(o.x, o.y)
            r2.scale(state.zoom, state.zoom)
            overlay.draw(r2)
        }
        assertTrue("pre-snap ink must be gone", r2.ribbonRuns.isEmpty() && r2.ops.none { it == "strokePolyline" })
        // A committed remove routes by uid too.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(4, "u1", 8L, 9L, "d2", listOf(SyncOp.RemoveShape(0, listOf("sh1"))))))
        assertEquals(listOf("sh1"), h.shapeRemoves.single().second)
    }

    @Test fun staleOpenCatchesUpThroughOpsWithoutRefetch() {
        val (e, h) = engine()
        e.onConnected()
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(1, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 7L, 10L)))))
        // Prime the cache at v7 with a full fetch.
        e.requestOpen("u1")
        e.bytesReceived(SyncFrames.encode(SyncMsg.FileChunk(2, "u1", 7L, 10L, 0L, base64Of("0123456789".toByteArray()), last = true)))
        assertEquals("u1", h.fileReady)
        h.fileReady = null
        h.out.clear()
        // The head moves to v9 while we look away: reopening hands over the stale
        // bytes at once and asks for just the edits.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Index(3, -1, true, listOf(SyncFileEntry("u1", "n.xnote", 0, 9L, 10L)))))
        e.requestOpen("u1")
        assertEquals("u1", h.fileReady)
        val asking = h.out.single() as SyncMsg.FetchOps
        assertEquals(7L, asking.sinceVersion)
        // The open binds at the stale version; the two missing ops buffer, then replay in order.
        e.noteOpened("u1", 7L)
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(4, "u1", 7L, 8L, "d8", listOf(SyncOp.AddText(0, textOf("t8"))))))
        assertTrue(h.texts.isEmpty())
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(5, "u1", 8L, 9L, "d9", listOf(SyncOp.AddText(0, textOf("t9"))))))
        assertEquals(listOf("t8", "t9"), h.texts.map { it.second.uid })
        assertEquals(9L, e.openVersion("u1"))
        // Steady state keeps gating on the doc version: a skipped op resyncs.
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(6, "u1", 11L, 12L, "d12", listOf(SyncOp.AddText(0, textOf("t12"))))))
        assertTrue(h.out.last() is SyncMsg.Fetch)
        // ...while the chained op applies.
        e.noteOpened("u1", 12L)
        e.bytesReceived(SyncFrames.encode(SyncMsg.Op(7, "u1", 12L, 13L, "d13", listOf(SyncOp.AddText(0, textOf("t13"))))))
        assertTrue(h.texts.any { it.second.uid == "t13" })
    }

    @Test fun fileHeadRoundTripsAndNotifies() {
        val (e, h) = engine()
        e.onConnected()
        val head = SyncMsg.FileHead(9, "u1", 7L, 100L, "{}", "flow", null, mapOf("t" to "{}"), mapOf("p" to "aGk="))
        val back = SyncFrames.Decoder().feed(SyncFrames.encode(head)).single() as SyncMsg.FileHead
        assertEquals(head, back)
        e.bytesReceived(SyncFrames.encode(head))
        assertEquals("u1", h.fileHeadReady)
        val kept = e.takeFileHead("u1", 7L)
        assertEquals("{}", kept?.manifest)
        assertEquals("flow", kept?.flow)
        assertNull(e.takeFileHead("u1", 7L))
    }

    private fun textOf(uid: String) = WireText(uid, 10.0, 20.0, 100.0, 30.0, "hi", "#ff0000", 14.0, "sans")

    @Test fun liveFrameUpdatesOverlayOld() {
        val (e, h) = engine()
        var overlays = 0
        e.setLiveOverlay("u1", object : com.xnotes.canvas.RemoteLiveStrokes(
            com.xnotes.canvas.CanvasState(
                com.xnotes.core.model.Document(),
                com.xnotes.core.FakeSurfaceFactory(),
                com.xnotes.canvas.TestPalette(),
            ),
        ) {
            override fun update(
                file: String,
                page: Int,
                strokeId: String,
                tool: String,
                width: Double,
                color: String,
                samples: List<WireSample>,
                done: Boolean,
                style: com.xnotes.sync.WireStrokeStyle?,
            ) {
                overlays++
                super.update(file, page, strokeId, tool, width, color, samples, done, style)
            }
        })
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Live(1, "u1", 0, "s-1", "pen", 3.0, "#00e676", listOf(WireSample(1.0, 1.0, 1.0)), done = false),
            ),
        )
        assertEquals(1, overlays)
        assertEquals(1, h.liveDirty)
    }
}
