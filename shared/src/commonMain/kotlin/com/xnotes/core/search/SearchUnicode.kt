package com.xnotes.core.search

import kotlin.concurrent.Volatile

/** Unicode primitives the search fold needs; the platform provides them. */
interface SearchUnicode {
    fun nfkc(s: String): String
    fun nfd(s: String): String
    fun nfc(s: String): String

    /** Category bucket of [cp]: one of `CAT_*`. */
    fun category(cp: Int): Int

    /** Script bucket of [cp]: one of `SCRIPT_*`. */
    fun script(cp: Int): Int

    fun isLetterOrDigit(cp: Int): Boolean
    fun isWhitespace(cp: Int): Boolean
    fun isSpaceChar(cp: Int): Boolean
    fun toLowerCase(c: Char): Char

    companion object {
        const val CAT_OTHER = 0
        const val CAT_CONTROL = 1
        const val CAT_FORMAT = 2
        const val CAT_SURROGATE = 3
        const val CAT_UNASSIGNED = 4
        const val CAT_PRIVATE_USE = 5
        const val CAT_NON_SPACING_MARK = 6
        const val CAT_SPACING_MARK = 7
        const val CAT_ENCLOSING_MARK = 8

        const val SCRIPT_OTHER = 0
        const val SCRIPT_LATIN = 1
        const val SCRIPT_GREEK = 2
        const val SCRIPT_HEBREW = 3
        const val SCRIPT_ARABIC = 4

        /**
         * The platform's primitives, set once at startup. Null until then, when
         * [defaultSearchUnicode] answers: the JVM's on the JVM, an ASCII fallback on native.
         */
        @Volatile
        var current: SearchUnicode? = null
    }
}

/** The primitives in force: the platform's once set, else the default. */
internal fun unicode(): SearchUnicode = SearchUnicode.current ?: defaultSearchUnicode

/** The platform's default primitives. */
expect val defaultSearchUnicode: SearchUnicode

/** ASCII-only fallback: matching still works, accents never fold. */
internal object AsciiUnicode : SearchUnicode {
    override fun nfkc(s: String): String = s
    override fun nfd(s: String): String = s
    override fun nfc(s: String): String = s
    override fun category(cp: Int): Int = when {
        cp < 0 || cp > 0x10FFFF -> SearchUnicode.CAT_UNASSIGNED
        cp < 0x20 || cp == 0x7F -> SearchUnicode.CAT_CONTROL
        cp in 0xD800..0xDFFF -> SearchUnicode.CAT_SURROGATE
        cp in 0xE000..0xF8FF -> SearchUnicode.CAT_PRIVATE_USE
        else -> SearchUnicode.CAT_OTHER
    }
    override fun script(cp: Int): Int = if (cp < 0x80) SearchUnicode.SCRIPT_LATIN else SearchUnicode.SCRIPT_OTHER
    override fun isLetterOrDigit(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code
    override fun isWhitespace(cp: Int): Boolean = cp == ' '.code || cp in 0x09..0x0D
    override fun isSpaceChar(cp: Int): Boolean = cp == ' '.code
    override fun toLowerCase(c: Char): Char =
        if (c in 'A'..'Z') (c.code + 32).toChar() else c
}

// --- code-point helpers (java.lang.Character equivalents) ---

internal fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

internal fun String.codePointBefore(index: Int): Int {
    val low = this[index - 1]
    if (low.isLowSurrogate() && index - 2 >= 0) {
        val high = this[index - 2]
        if (high.isHighSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return low.code
}

internal fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

internal fun isBmpCodePoint(cp: Int): Boolean = cp in 0..0xFFFF

internal fun highSurrogate(cp: Int): Char = ((0xD800 + ((cp - 0x10000) ushr 10)) and 0xFFFF).toChar()

internal fun lowSurrogate(cp: Int): Char = ((0xDC00 + ((cp - 0x10000) and 0x3FF)) and 0xFFFF).toChar()

internal fun Char.isHighSurrogate(): Boolean = code in 0xD800..0xDBFF

internal fun Char.isLowSurrogate(): Boolean = code in 0xDC00..0xDFFF

internal fun Char.isSurrogate(): Boolean = code in 0xD800..0xDFFF
