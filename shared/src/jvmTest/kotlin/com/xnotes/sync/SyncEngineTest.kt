package com.xnotes.sync

import com.xnotes.core.platform.fileOf
import java.io.File
import org.junit.Assert.assertEquals
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

        override fun onFileGone(id: String) {
            fileGone = id
        }

        override fun onDeleteResult(ok: Boolean, reason: String?) = Unit
        override fun onCreateResult(ok: Boolean, id: String?, reason: String?) = Unit

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

        override fun applyResync(fileId: String, version: Long) {
            resynced = fileId to version
        }

        fun lastSent(): SyncMsg = out.removeLast()
    }

    private fun engine(host: FakeHost = FakeHost(), dir: File? = null): Pair<CompanionEngine, FakeHost> {
        val d = dir ?: File(System.getProperty("java.io.tmpdir"), "sync-eng-${System.nanoTime()}").apply { mkdirs() }
        return CompanionEngine(host, fileOf(d.path), "linux-test", "aa".repeat(32)) to host
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
        e.liveOverlay = overlay
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
        assertTrue("live run must paint, got ${r.ops}", r.ops.any { it == "strokePolyline" })
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
        assertTrue("run must be gone after commit, got ${r2.ops}", r2.ops.none { it == "strokePolyline" })
    }

    @Test fun liveFrameUpdatesOverlayOld() {
        val (e, h) = engine()
        var overlays = 0
        e.liveOverlay = object : com.xnotes.canvas.RemoteLiveStrokes(
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
            ) {
                overlays++
                super.update(file, page, strokeId, tool, width, color, samples, done)
            }
        }
        e.bytesReceived(
            SyncFrames.encode(
                SyncMsg.Live(1, "u1", 0, "s-1", "pen", 3.0, "#00e676", listOf(WireSample(1.0, 1.0, 1.0)), done = false),
            ),
        )
        assertEquals(1, overlays)
        assertEquals(1, h.liveDirty)
    }
}
