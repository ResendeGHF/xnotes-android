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
        var resynced: Pair<String, Long>? = null

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

    @Test fun pageOpsRoundTripCodec() {
        val ops = listOf(SyncOp.AddPage(2, 595.0, 842.0), SyncOp.RemovePage(0))
        val msg = SyncMsg.Op(1, "u1", 3L, 4L, "d", ops)
        val back = SyncFrames.Decoder().feed(SyncFrames.encode(msg)).single() as SyncMsg.Op
        assertEquals(ops, back.ops)
    }

    @Test fun liveFrameUpdatesOverlay() {
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
