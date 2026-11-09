package com.xnotes.sync

import com.xnotes.core.model.TextMarkup
import com.xnotes.core.platform.fileOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-compat: a byte-exact `file` chunk as the Android host emits it
 * (integer numbers, base64 data) must parse and reassemble into the
 * original bytes. Guards the contract both trees implement (SYNC.md v1).
 */
class FetchWireCompatTest {
    private class H : SyncEngineHost {
        val out = ArrayList<ByteArray>()
        var ready = ArrayList<String>()
        override fun onBytes(data: ByteArray) { out += data }
        override fun onPairRequest(deviceName: String, fp: String) = Unit
        override fun onPaired(deviceName: String) = Unit
        override fun onIndexChanged() = Unit
        override fun onFileReady(id: String) { ready += id }
        override fun onFileHeadReady(id: String) = Unit
        override fun onFileGone(id: String) = Unit
        override fun onDeleteResult(ok: Boolean, reason: String?) = Unit
        override fun onCreateResult(ok: Boolean, id: String?, reason: String?) = Unit
        override fun onLinkResult(id: String, ok: Boolean, reason: String?) = Unit
        override fun onError(reason: String) = Unit
        override fun onLiveDirty() = Unit
        override fun applyRemoteStroke(fileId: String, page: Int, stroke: WireStroke) = Unit
        override fun applyRemoteErase(fileId: String, page: Int, ids: List<String>) = Unit
        override fun applyRemotePageAdd(fileId: String, index: Int, width: Double, height: Double) = Unit
        override fun applyRemotePageRemove(fileId: String, index: Int) = Unit
        override fun applyRemoteRoomEdit(fileId: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double) = Unit
        override fun previewRemoteRoom(fileId: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) = Unit
        override fun clearRemoteRoomPreview(fileId: String) = Unit
        override fun applyRemoteImage(fileId: String, page: Int, image: WireImage, bytes: ByteArray) = Unit
        override fun applyRemoteMarkups(fileId: String, page: Int, added: List<TextMarkup>, removedIds: List<String>) = Unit
        override fun applyRemotePageStyle(fileId: String, entries: List<PageStyleEntry>, docStyle: WirePageStyle?, docMargins: WireMargins?) = Unit
        override fun applyResync(fileId: String, version: Long) = Unit
        override fun applyRemoteText(fileId: String, page: Int, text: WireText) = Unit
        override fun applyRemoteTextRemove(fileId: String, page: Int, ids: List<String>) = Unit
        override fun applyRemoteTable(fileId: String, page: Int, table: WireTable) = Unit
        override fun applyRemoteTableRemove(fileId: String, page: Int, ids: List<String>) = Unit
        override fun applyRemoteAudio(fileId: String, page: Int, audio: WireAudio, bytes: ByteArray, peaks: ByteArray) = Unit
        override fun applyRemoteAudioRemove(fileId: String, page: Int, ids: List<String>) = Unit
        override fun applyRemoteImageRemove(fileId: String, page: Int, ids: List<String>) = Unit
        override fun applyRemoteShape(fileId: String, page: Int, shape: com.xnotes.sync.WireShape) = Unit
        override fun applyRemoteShapeRemove(fileId: String, page: Int, ids: List<String>) = Unit
    }

    @Test fun noEagerRefetchPerChunk() {
        val d = File(System.getProperty("java.io.tmpdir"), "sync-wirecompat2-${System.nanoTime()}").apply { mkdirs() }
        val h = H()
        val e = CompanionEngine(h, fileOf(d.path), "linux-test", "bb".repeat(32))
        e.onConnected()
        e.requestOpen("f")
        assertEquals(1, h.out.size)
        // Two-chunk file sent as two frames: the second chunk must NOT trigger
        // another Fetch (the host already streams the remainder).
        val half = ByteArray(300 * 1024) { (it % 251).toByte() }
        val enc = okio.ByteString.of(*half).base64()
        val c1 = """{"t":"file","seq":1,"id":"f","version":7,"total":614400,"offset":0,"data":"$enc","last":false}"""
        val c2 = """{"t":"file","seq":2,"id":"f","version":7,"total":614400,"offset":307200,"data":"$enc","last":true}"""
        e.bytesReceived(framed(c1))
        assertEquals("no follow-up fetch after a non-final chunk", 1, h.out.size)
        e.bytesReceived(framed(c2))
        assertTrue("f" in h.ready)
        assertEquals(614400, e.fileBytes("f")!!.size)
    }

    private fun framed(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return java.nio.ByteBuffer.allocate(4 + body.size).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(body.size).put(body).array()
    }

    @Test fun androidFileChunkReassembles() {
        val d = File(System.getProperty("java.io.tmpdir"), "sync-wirecompat-${System.nanoTime()}").apply { mkdirs() }
        val h = H()
        val e = CompanionEngine(h, fileOf(d.path), "linux-test", "bb".repeat(32))
        e.onConnected()
        e.requestOpen("small")
        h.out.clear()
        // Exact bytes of an Android HostEngine file chunk (framed).
        val body = """{"total":100,"t":"file","offset":0,"data":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiYw==","last":true,"id":"small","version":1,"seq":2}""".toByteArray(Charsets.UTF_8)
        val frame = java.nio.ByteBuffer.allocate(4 + body.size).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(body.size).put(body).array()
        e.bytesReceived(frame)
        assertTrue("expected onFileReady, got ${h.ready}", "small" in h.ready)
        assertEquals((0 until 100).map { it.toByte() }, e.fileBytes("small")!!.toList())
    }
}
