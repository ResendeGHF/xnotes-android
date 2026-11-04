package com.xnotes.core.search

import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.withLock

/**
 * How search reads characters, one at a time. Building a [SearchText] sorts each source character
 * with [kind]: kept as written, dropped, a space, or spelt out as its compatibility form. Matching
 * then compares each kept char by its [fold]: its compatibility form, accents off a Latin, Greek,
 * Hebrew or Arabic letter, curly quotes, hyphens and slashes straightened, and case unless it
 * matters. Accents are only stripped where a reader would call them accents: Indic vowel signs,
 * Thai marks, kana voicing and Cyrillic letters such as й stay as they are.
 */
internal object SearchFold {
    const val KEEP = 1
    const val DROP = 2
    const val SPACE = 3
    const val EXPAND = 4

    /** What a lone accent folds to: nothing, so matching steps over it. */
    const val IGNORE = '\uFFFF'

    /** A fold not worked out yet; U+FFFE itself folds to this too, and is just worked out again. */
    private const val UNSET = '\uFFFE'

    private val lock = Lock()
    private val kinds = ByteArray(0x10000)
    private val wideKinds = HashMap<Int, Int>()
    private val expansions = HashMap<Int, String>()
    private val exact = CharArray(0x10000) { UNSET }
    private val caseless = CharArray(0x10000) { UNSET }

    private val strokes = mapOf(
        'ø' to 'o', 'Ø' to 'O', 'ł' to 'l', 'Ł' to 'L', 'đ' to 'd', 'Đ' to 'D',
        'ħ' to 'h', 'Ħ' to 'H', 'ŧ' to 't', 'Ŧ' to 'T',
    )

    fun kind(cp: Int): Int = withLock(lock) {
        val uni = unicode()
        if (cp < 0x80) {
            return@withLock when {
                uni.isWhitespace(cp) -> SPACE
                cp < 0x20 || cp == 0x7F -> DROP
                else -> KEEP
            }
        }
        if (cp < 0x10000) {
            val k = kinds[cp].toInt()
            if (k != 0) return@withLock k
            return@withLock computeKind(cp, uni).also { kinds[cp] = it.toByte() }
        }
        wideKinds.getOrPut(cp) { computeKind(cp, uni) }
    }

    /** The letters an [EXPAND] character is spelt out as. */
    fun expansion(cp: Int): String = withLock(lock) {
        expansions[cp] ?: unicode().nfkc(cpToString(cp)).also { expansions[cp] = it }
    }

    fun fold(c: Char, matchCase: Boolean): Char = withLock(lock) {
        val table = if (matchCase) exact else caseless
        val f = table[c.code]
        if (f != UNSET) return@withLock f
        computeFold(c, matchCase, unicode()).also { table[c.code] = it }
    }

    /** A letter, digit or mark: what a whole word may not touch. */
    fun isWordChar(cp: Int): Boolean = withLock(lock) {
        val uni = unicode()
        if (uni.isLetterOrDigit(cp)) return@withLock true
        val t = uni.category(cp)
        t == SearchUnicode.CAT_NON_SPACING_MARK || t == SearchUnicode.CAT_SPACING_MARK ||
            t == SearchUnicode.CAT_ENCLOSING_MARK
    }

    private fun cpToString(cp: Int): String =
        if (cp <= 0xFFFF) cp.toChar().toString() else charArrayOf(highSurrogate(cp), lowSurrogate(cp)).concatToString()

    private fun computeKind(cp: Int, uni: SearchUnicode): Int {
        if (uni.isWhitespace(cp) || uni.isSpaceChar(cp) || cp == NEXT_LINE) return SPACE
        if (cp == TATWEEL) return DROP
        when (uni.category(cp)) {
            SearchUnicode.CAT_CONTROL, SearchUnicode.CAT_FORMAT -> return DROP
            SearchUnicode.CAT_SURROGATE, SearchUnicode.CAT_UNASSIGNED, SearchUnicode.CAT_PRIVATE_USE -> return KEEP
        }
        val s = cpToString(cp)
        val k = uni.nfkc(s)
        if (k == s || (k.length == 1 && cp < 0x10000)) return KEEP
        // A spacing accent such as ´ or ¨ is a space plus the accent: drop it, or keep it whole.
        if (k[0] == ' ' && k.length > 1 && k.drop(1).all { isMark(it.code) }) {
            return if (k.drop(1).all { isAccent(it.code) }) DROP else KEEP
        }
        expansions[cp] = k
        return EXPAND
    }

    private fun computeFold(c: Char, matchCase: Boolean, uni: SearchUnicode): Char {
        if (c.isSurrogate()) return c
        if (isAccent(c.code)) return IGNORE
        var k = uni.nfkc(c.toString())
        if (k.length != 1) k = c.toString()
        var f = k[0]
        val d = uni.nfd(k)
        if (d.length > 1 && takesAccents(d[0])) {
            val bare = buildString { for (ch in d) if (!isAccent(ch.code)) append(ch) }
            val r = uni.nfc(bare)
            if (r.length == 1) f = r[0]
        }
        f = strokes[f] ?: f
        f = when (f) {
            '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
            '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
            '\u2010', '\u2212' -> '-'
            '\u2044', '\u2215' -> '/'
            else -> f
        }
        if (!matchCase) {
            f = uni.toLowerCase(f)
            if (f == 'ς') f = 'σ'
        }
        return f
    }

    /** Letters of the scripts whose marks are accents a search looks through. */
    private fun takesAccents(base: Char): Boolean = when (unicode().script(base.code)) {
        SearchUnicode.SCRIPT_LATIN, SearchUnicode.SCRIPT_GREEK,
        SearchUnicode.SCRIPT_HEBREW, SearchUnicode.SCRIPT_ARABIC -> true
        else -> false
    }

    /** An accent: the generic combining diacritics, Hebrew points or Arabic vowel marks. */
    private fun isAccent(cp: Int): Boolean {
        val t = unicode().category(cp)
        if (t != SearchUnicode.CAT_NON_SPACING_MARK && t != SearchUnicode.CAT_ENCLOSING_MARK) return false
        return cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF || cp in 0x20D0..0x20FF ||
            cp in 0xFE20..0xFE2F || cp in 0x0591..0x05C7 || cp in 0x0610..0x061A || cp in 0x064B..0x065F ||
            cp == 0x0670 || cp in 0x06D6..0x06ED
    }

    private fun isMark(cp: Int): Boolean {
        val t = unicode().category(cp)
        return t == SearchUnicode.CAT_NON_SPACING_MARK || t == SearchUnicode.CAT_SPACING_MARK ||
            t == SearchUnicode.CAT_ENCLOSING_MARK
    }

    private const val NEXT_LINE = 0x85
    private const val TATWEEL = 0x640
}
