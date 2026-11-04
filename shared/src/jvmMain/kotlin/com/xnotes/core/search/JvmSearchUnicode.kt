package com.xnotes.core.search

import java.text.Normalizer

/** The search fold's Unicode primitives on the JVM, straight from `java.lang`. */
object JvmSearchUnicode : SearchUnicode {
    override fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
    override fun nfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
    override fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    override fun category(cp: Int): Int = when (Character.getType(cp)) {
        Character.CONTROL.toInt() -> SearchUnicode.CAT_CONTROL
        Character.FORMAT.toInt() -> SearchUnicode.CAT_FORMAT
        Character.SURROGATE.toInt() -> SearchUnicode.CAT_SURROGATE
        Character.UNASSIGNED.toInt() -> SearchUnicode.CAT_UNASSIGNED
        Character.PRIVATE_USE.toInt() -> SearchUnicode.CAT_PRIVATE_USE
        Character.NON_SPACING_MARK.toInt() -> SearchUnicode.CAT_NON_SPACING_MARK
        Character.COMBINING_SPACING_MARK.toInt() -> SearchUnicode.CAT_SPACING_MARK
        Character.ENCLOSING_MARK.toInt() -> SearchUnicode.CAT_ENCLOSING_MARK
        else -> SearchUnicode.CAT_OTHER
    }

    override fun script(cp: Int): Int = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.LATIN -> SearchUnicode.SCRIPT_LATIN
        Character.UnicodeScript.GREEK -> SearchUnicode.SCRIPT_GREEK
        Character.UnicodeScript.HEBREW -> SearchUnicode.SCRIPT_HEBREW
        Character.UnicodeScript.ARABIC -> SearchUnicode.SCRIPT_ARABIC
        else -> SearchUnicode.SCRIPT_OTHER
    }

    override fun isLetterOrDigit(cp: Int): Boolean = Character.isLetterOrDigit(cp)
    override fun isWhitespace(cp: Int): Boolean = Character.isWhitespace(cp)
    override fun isSpaceChar(cp: Int): Boolean = Character.isSpaceChar(cp)
    override fun toLowerCase(c: Char): Char = Character.toLowerCase(c)
}

actual val defaultSearchUnicode: SearchUnicode = JvmSearchUnicode
