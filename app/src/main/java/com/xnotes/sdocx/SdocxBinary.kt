package com.xnotes.sdocx

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Little-endian binary reader for Samsung Notes (.sdocx) structures.
 * Ports the Dart `SdocxReader` (itself mirroring twangodev/sdocx + bxff research).
 */
class SdocxReader(val data: ByteArray, var offset: Int = 0) {
    val remaining: Int get() = data.size - offset
    val isEof: Boolean get() = remaining <= 0

    fun seek(pos: Int) {
        if (pos < 0 || pos > data.size) throw SdocxFormatException("seek out of range: $pos")
        offset = pos
    }

    fun skip(n: Int) {
        if (offset + n > data.size) throw SdocxFormatException("truncated skip ($n bytes)")
        offset += n
    }

    fun readBytes(n: Int): ByteArray {
        if (offset + n > data.size) throw SdocxFormatException("truncated read (need $n, have $remaining)")
        val out = data.copyOfRange(offset, offset + n)
        offset += n
        return out
    }

    fun readU8(): Int = readBytes(1)[0].toInt() and 0xFF

    fun readU16(): Int {
        val b = readBytes(2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    fun readI16(): Int {
        val v = readU16()
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun readU32(): Long {
        val b = readBytes(4)
        return ((b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)) and 0xFFFFFFFFL
    }

    fun readI32(): Int = readU32().toInt()

    fun readI64(): Long {
        val b = readBytes(8)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun readF32(): Float {
        val b = readBytes(4)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float
    }

    fun readF64(): Double {
        val b = readBytes(8)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).double
    }

    /** Length-prefixed UTF-16LE string with u16 code-unit count. 0xFFFF is the null sentinel. */
    fun readUtf16U16(): String {
        val units = readU16()
        if (units == 0xFFFF) throw SdocxFormatException("null string sentinel")
        if (units == 0) return ""
        val bytes = readBytes(units * 2)
        return utf16LeToString(bytes)
    }

    fun readUtf16U32(): String {
        val units = readU32()
        if (units == 0L) return ""
        if (units > 1_000_000) throw SdocxFormatException("unreasonable string length $units")
        val bytes = readBytes((units * 2).toInt())
        return utf16LeToString(bytes)
    }

    fun readNullableUtf16U16(): String? {
        if (remaining < 2) throw SdocxFormatException("truncated nullable string length")
        val units = readU16()
        if (units == 0xFFFF) return null
        if (units == 0) return ""
        val bytes = readBytes(units * 2)
        return utf16LeToString(bytes)
    }

    /** Length-prefixed UTF-8 string with u16 byte length; stops at first NUL (Samsung UUIDs). */
    fun readUtf8U16(): String {
        val len = readU16()
        if (len == 0) return ""
        val bytes = readBytes(len)
        val nul = bytes.indexOf(0.toByte())
        val effective = if (nul >= 0) bytes.copyOfRange(0, nul) else bytes
        return String(effective, Charsets.UTF_8)
    }

    companion object {
        fun utf16LeToString(bytes: ByteArray): String {
            val units = CharArray(bytes.size / 2) { i ->
                ((bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toChar()
            }
            return String(units)
        }
    }
}

/** Length-prefixed bit mask: 1 byte count + that many bytes. */
class SdocxMask(val bytes: ByteArray) {
    fun contains(bit: Int): Boolean {
        val idx = bit / 8
        if (idx >= bytes.size) return false
        return (bytes[idx].toInt() and (1 shl (bit % 8))) != 0
    }

    val lowU32: Int
        get() {
            var v = 0
            for (i in 0 until minOf(bytes.size, 4)) v = v or ((bytes[i].toInt() and 0xFF) shl (i * 8))
            return v
        }

    val isEmpty: Boolean get() = bytes.all { it == 0.toByte() }

    companion object {
        fun read(r: SdocxReader): SdocxMask {
            val count = r.readU8()
            return SdocxMask(r.readBytes(count))
        }

        fun of(vararg bits: Int): SdocxMask {
            if (bits.isEmpty()) return SdocxMask(byteArrayOf(0))
            val max = bits.max()
            val n = max / 8 + 1
            val b = ByteArray(n)
            for (bit in bits) b[bit / 8] = (b[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            return SdocxMask(b)
        }

        fun singleByte(value: Int): SdocxMask = SdocxMask(byteArrayOf(value.toByte()))
    }
}

class SdocxFormatException(message: String) : Exception(message)

/**
 * Little-endian binary writer for Samsung Notes (.sdocx) structures.
 * Mirror of [SdocxReader].
 */
class SdocxWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()
    fun size(): Int = out.size()

    fun writeBytes(b: ByteArray) = out.write(b)
    fun writeU8(v: Int) = out.write(v and 0xFF)

    fun writeU16(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    fun writeI16(v: Int) = writeU16(v and 0xFFFF)

    fun writeU32(v: Long) {
        val x = v and 0xFFFFFFFFL
        out.write((x and 0xFF).toInt())
        out.write(((x ushr 8) and 0xFF).toInt())
        out.write(((x ushr 16) and 0xFF).toInt())
        out.write(((x ushr 24) and 0xFF).toInt())
    }

    fun writeI32(v: Int) = writeU32((v.toLong() and 0xFFFFFFFFL))

    fun writeI64(v: Long) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        out.write(buf)
    }

    fun writeF32(v: Float) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
        out.write(buf)
    }

    fun writeF64(v: Double) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
        out.write(buf)
    }

    /** UTF-16LE string with u16 code-unit count prefix. */
    fun writeUtf16U16(s: String) {
        writeU16(s.length)
        for (c in s) writeU16(c.code)
    }

    fun writeUtf16U32(s: String) {
        writeU32(s.length.toLong())
        for (c in s) writeU16(c.code)
    }

    fun writeNullableUtf16U16(s: String?) {
        if (s == null) {
            writeU16(0xFFFF)
            return
        }
        writeUtf16U16(s)
    }

    fun writeUtf8U16(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeU16(b.size)
        out.write(b)
    }

    fun writeMask(mask: SdocxMask) {
        writeU8(mask.bytes.size)
        writeBytes(mask.bytes)
    }

    fun writeMaskBits(vararg bits: Int) = writeMask(SdocxMask.of(*bits))
}
