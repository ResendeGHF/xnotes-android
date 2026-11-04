package com.xnotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFramesTest {
    @Test fun helloRoundTrips() {
        val msg = SyncMsg.Hello(7, "xnotes-android", 1, "Pixel 8", "ab".repeat(32))
        val back = SyncFrames.decode(SyncFrames.encode(msg).sliceArray(4 until SyncFrames.encode(msg).size))
        assertEquals(msg, back)
    }

    @Test fun pairRequestRoundTrips() {
        val msg = SyncMsg.PairRequest(3, "Pixel 8", "cd".repeat(32), "482916")
        assertEquals(msg, SyncFrames.decode(SyncFrames.encode(msg).drop(4).toByteArray()))
    }

    @Test fun helloAckRoundTrips() {
        val msg = SyncMsg.HelloAck(4, 7, "ef".repeat(32), paired = false, codeRequired = true)
        assertEquals(msg, SyncFrames.decode(SyncFrames.encode(msg).drop(4).toByteArray()))
    }

    @Test fun pairResultRoundTrips() {
        val ok = SyncMsg.PairResult(5, 3, ok = true, reason = null)
        assertEquals(ok, SyncFrames.decode(SyncFrames.encode(ok).drop(4).toByteArray()))
        val denied = SyncMsg.PairResult(6, 3, ok = false, reason = "bad code")
        assertEquals(denied, SyncFrames.decode(SyncFrames.encode(denied).drop(4).toByteArray()))
    }

    @Test fun decoderSplitsAnywhereAndBatches() {
        val a = SyncFrames.encode(SyncMsg.Hello(1, "a", 1, "d", "f"))
        val b = SyncFrames.encode(SyncMsg.PairResult(2, 1, true, null))
        val dec = SyncFrames.Decoder()
        val out = ArrayList<SyncMsg>()
        // One byte at a time, then both at once.
        for (i in a.indices) out += dec.feed(a.sliceArray(i..i))
        out += dec.feed(b)
        assertEquals(2, out.size)
        assertTrue(out[0] is SyncMsg.Hello)
        assertTrue(out[1] is SyncMsg.PairResult)
    }

    @Test fun unknownTypeDecodesAsUnknown() {
        val raw = """{"t":"future_thing","seq":9,"extra":[1,{"a":true}]}""".encodeToByteArray()
        val frame = ByteArray(4 + raw.size)
        frame[3] = raw.size.toByte()
        raw.copyInto(frame, 4)
        val back = SyncFrames.decode(frame.drop(4).toByteArray())
        assertEquals(SyncMsg.Unknown("future_thing", 9), back)
    }

    @Test fun opRoundTrips() {
        val msg = SyncMsg.Op(
            9, "notes/todo.xnote", 41L, 42L, "dd-1",
            listOf(
                SyncOp.AddStroke(
                    2,
                    WireStroke("pen", 3.0, "#00e676", true, listOf(WireSample(10.0, 20.0, 0.5), WireSample(11.5, 21.0, 0.6))),
                ),
                SyncOp.EraseStrokes(2, listOf("11812f53989ff4dc")),
            ),
        )
        assertEquals(msg, SyncFrames.decode(SyncFrames.encode(msg).drop(4).toByteArray()))
    }

    @Test fun liveRoundTrips() {
        val msg = SyncMsg.Live(10, "notes/todo.xnote", 2, "s-1", "pen", 3.0, "#00e676", listOf(WireSample(1.0, 2.0, 1.0)), false)
        assertEquals(msg, SyncFrames.decode(SyncFrames.encode(msg).drop(4).toByteArray()))
    }

    @Test fun indexRoundTrips() {
        val msg = SyncMsg.Index(
            11, -1, true,
            listOf(SyncFileEntry("u1", "notes/todo.xnote", 0, 41L, 9000L), SyncFileEntry("d1", "notes", 1, 0L, 0L)),
        )
        assertEquals(msg, SyncFrames.decode(SyncFrames.encode(msg).drop(4).toByteArray()))
    }

    @Test fun fileChunkRoundTrips() {
        val msg = SyncMsg.FileChunk(12, "u1", 41L, 1000000L, 0L, "aGVsbG8=", last = false)
        assertEquals(msg, SyncFrames.decode(SyncFrames.encode(msg).drop(4).toByteArray()))
    }

    @Test fun presenceAndHeartbeatRoundTrip() {
        assertEquals(SyncMsg.Presence(13, "u1", 2), SyncFrames.decode(SyncFrames.encode(SyncMsg.Presence(13, "u1", 2)).drop(4).toByteArray()))
        assertEquals(SyncMsg.Heartbeat(14), SyncFrames.decode(SyncFrames.encode(SyncMsg.Heartbeat(14)).drop(4).toByteArray()))
    }

    @Test fun createDeleteRoundTrip() {
        assertEquals(
            SyncMsg.DeleteRequest(15, "u9"),
            SyncFrames.decode(SyncFrames.encode(SyncMsg.DeleteRequest(15, "u9")).drop(4).toByteArray()),
        )
        assertEquals(
            SyncMsg.CreateRequest(16, "d1", "new note", 0),
            SyncFrames.decode(SyncFrames.encode(SyncMsg.CreateRequest(16, "d1", "new note", 0)).drop(4).toByteArray()),
        )
        assertEquals(
            SyncMsg.CreateResult(17, 16, true, "u10", null),
            SyncFrames.decode(SyncFrames.encode(SyncMsg.CreateResult(17, 16, true, "u10", null)).drop(4).toByteArray()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoderRejectsAbsurdLength() {
        SyncFrames.Decoder().feed(byteArrayOf(127, 127, 127, 127, 1, 2, 3))
    }
}
