package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** The animation link survives only inside the PNG bytes: no model or codec change involved. */
class MathAnimPngTest {

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(intBytes(data.size))
        val tb = type.toByteArray(Charsets.US_ASCII)
        out.write(tb)
        out.write(data)
        val crc = CRC32()
        crc.update(tb)
        crc.update(data)
        out.write(intBytes(crc.value.toInt()))
    }

    private fun minimalPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream()
        ihdr.write(intBytes(1))
        ihdr.write(intBytes(1))
        ihdr.write(byteArrayOf(8, 2, 0, 0, 0))
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", byteArrayOf())
        chunk(out, "IEND", byteArrayOf())
        return out.toByteArray()
    }

    @Test
    fun plainPngHasNoSpec() {
        val png = minimalPng()
        assertNull(MathAnimPng.readSpec(png))
        assertFalse(MathAnimPng.hasSpec("/nonexistent/path.png"))
    }

    @Test
    fun embedRoundTrip() {
        val json = """{"v":1,"tab":"GRAPH_2D","exprs":["sin(x+t)"],"tmin":0.0,"tmax":6.28,"θ":"π"}"""
        val embedded = MathAnimPng.embed(minimalPng(), json)
        assertEquals(json, MathAnimPng.readSpec(embedded))
        // Still a PNG a plain decoder accepts: signature + IHDR intact.
        assertEquals(137.toByte(), embedded[0])
        assertTrue(MathAnimPng.readSpec(embedded)!!.contains("sin(x+t)"))
    }

    @Test
    fun nonPngPassesThrough() {
        val bytes = "not a png".toByteArray()
        assertEquals(9, MathAnimPng.embed(bytes, "{}").size)
        assertNull(MathAnimPng.readSpec(bytes))
    }

    @Test
    fun badgeGeometryStaysInside() {
        val (bx, by, r) = MathAnimPng.badge(600.0, 300.0, 2.0)
        assertTrue(bx > 0 && by > 0 && bx < 600.0 && by < 300.0 && r > 0)
        assertTrue(MathAnimPng.hitsBadge(600.0, 300.0, 2.0, bx, by))
        assertFalse(MathAnimPng.hitsBadge(600.0, 300.0, 2.0, 10.0, 10.0))
    }
}
