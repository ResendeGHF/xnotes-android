@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.capi.c.xn_host
import com.xnotes.core.search.SearchUnicode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value

/**
 * The search fold's Unicode primitives through the host. Normalization runs on the worker thread
 * that scans; `QString` string math is thread-safe, so no locking is needed.
 */
internal class HostSearchUnicode(private val host: xn_host) : SearchUnicode {
    override fun nfkc(s: String): String = normalize(FORM_NFKC, s)
    override fun nfd(s: String): String = normalize(FORM_NFD, s)
    override fun nfc(s: String): String = normalize(FORM_NFC, s)

    private fun normalize(form: Int, s: String): String {
        val norm = host.text_normalize ?: return s
        if (s.isEmpty()) return s
        return memScoped {
            val n = s.length
            val input = allocArray<UShortVar>(n)
            for (i in 0 until n) input[i] = s[i].code.toUShort()
            val need = norm.invoke(host.ctx, form, input, n, null, 0)
            if (need <= 0) return@memScoped s
            val out = allocArray<UShortVar>(need)
            val got = norm.invoke(host.ctx, form, input, n, out, need)
            if (got <= 0 || got > need) return@memScoped s
            CharArray(got) { out[it].toInt().toChar() }.concatToString()
        }
    }

    override fun category(cp: Int): Int = info(cp, 0)
    override fun script(cp: Int): Int = info(cp, 1)

    private fun info(cp: Int, which: Int): Int {
        val query = host.text_char_info ?: return SearchUnicode.CAT_OTHER
        return memScoped {
            val category = alloc<IntVar>()
            val script = alloc<IntVar>()
            val flags = alloc<IntVar>()
            query.invoke(host.ctx, cp, category.ptr, script.ptr, flags.ptr)
            when (which) {
                0 -> category.value
                1 -> script.value
                else -> flags.value
            }
        }
    }

    override fun isLetterOrDigit(cp: Int): Boolean = info(cp, 2) and 1 != 0
    override fun isWhitespace(cp: Int): Boolean = info(cp, 2) and 2 != 0
    override fun isSpaceChar(cp: Int): Boolean = info(cp, 2) and 4 != 0

    override fun toLowerCase(c: Char): Char {
        val lower = host.text_to_lower ?: return c
        return lower.invoke(host.ctx, c.code).toChar()
    }

    private companion object {
        const val FORM_NFD = 0
        const val FORM_NFC = 1
        const val FORM_NFKC = 2
    }
}
