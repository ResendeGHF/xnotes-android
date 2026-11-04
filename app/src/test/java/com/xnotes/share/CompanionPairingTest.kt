package com.xnotes.share

import com.xnotes.share.companion.CompanionPins
import com.xnotes.share.companion.Frames
import com.xnotes.share.companion.Pairing
import java.io.File
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPairingTest {
    @Test fun framesRoundTrip() {
        val msg = JSONObject().put("t", "hello").put("seq", 3).put("n", 42)
        val bytes = Frames.encode(msg)
        val dec = Frames.Decoder()
        // Split anywhere, even inside the length: still one message.
        val parts = listOf(bytes.sliceArray(0 until 2), bytes.sliceArray(2 until bytes.size))
        val out = parts.flatMap { dec.feed(it) }
        assertEquals(1, out.size)
        assertEquals("hello", out[0].getString("t"))
        assertEquals(3, out[0].getInt("seq"))
    }

    @Test fun decoderBatches() {
        val a = Frames.encode(JSONObject().put("t", "a"))
        val b = Frames.encode(JSONObject().put("t", "b"))
        val out = Frames.Decoder().feed(a + b)
        assertEquals(listOf("a", "b"), out.map { it.getString("t") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoderRejectsAbsurdLength() {
        Frames.Decoder().feed(byteArrayOf(127, 127, 127, 127, 1, 2, 3))
    }

    @Test fun codeIsSixDigits() {
        val rng = SecureRandom(byteArrayOf(1, 2, 3))
        repeat(50) {
            val code = Pairing.newCode(rng)
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test fun fingerprintIsLowerHex64() {
        val fp = Pairing.fingerprint(ByteArray(10) { it.toByte() })
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("AB:CD", Pairing.displayFingerprint("abcd").let { it.substring(0, 5) })
    }

    @Test fun pinsPersistAndRepin() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pins-${System.nanoTime()}").apply { mkdirs() }
        val pins = CompanionPins.of(dir)
        val fp = "ab".repeat(32)
        pins.pin("desktop", fp.uppercase())
        assertEquals(fp, pins.fpFor("desktop"))
        assertEquals("desktop", pins.nameFor(fp))
        // Reload from disk.
        val pins2 = CompanionPins.of(dir)
        assertEquals(fp, pins2.fpFor("desktop"))
        // Re-pin replaces, unpin forgets.
        pins2.pin("desktop", "cd".repeat(32))
        assertEquals("cd".repeat(32), CompanionPins.of(dir).fpFor("desktop"))
        assertTrue(pins2.unpin("desktop"))
        assertNull(CompanionPins.of(dir).fpFor("desktop"))
    }
}
