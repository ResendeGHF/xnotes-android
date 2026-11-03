package com.xnotes.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareProtocolTest {

    @Test
    fun bearerParsing() {
        assertEquals("abc", ShareProtocol.bearerToken("Bearer abc"))
        assertEquals("abc", ShareProtocol.bearerToken("bearer abc  "))
        assertNull(ShareProtocol.bearerToken(null))
        assertNull(ShareProtocol.bearerToken("Basic abc"))
        assertNull(ShareProtocol.bearerToken("Bearer "))
    }

    @Test
    fun tokenCompareIsExact() {
        assertTrue(ShareProtocol.tokensEqual("abc", "abc"))
        assertFalse(ShareProtocol.tokensEqual("abc", "abd"))
        assertFalse(ShareProtocol.tokensEqual("abc", "abcd"))
    }

    @Test
    fun queryParamsDecode() {
        val p = ShareProtocol.parseParams("uri=content%3A%2F%2Fx%2F1&holder=laptop+x")
        assertEquals("content://x/1", p["uri"])
        assertEquals("laptop x", p["holder"])
        assertTrue(ShareProtocol.parseParams(null).isEmpty())
        assertTrue(ShareProtocol.parseParams("").isEmpty())
    }

    @Test
    fun rangeParsing() {
        assertEquals(ShareProtocol.ByteRange(0, 99), ShareProtocol.parseRange("bytes=0-99", 1000))
        assertEquals(ShareProtocol.ByteRange(900, 999), ShareProtocol.parseRange("bytes=900-", 1000))
        assertEquals(ShareProtocol.ByteRange(900, 999), ShareProtocol.parseRange("bytes=-100", 1000))
        // Clamped, not rejected, when the end runs past the entity.
        assertEquals(ShareProtocol.ByteRange(0, 999), ShareProtocol.parseRange("bytes=0-9999", 1000))
        assertNull(ShareProtocol.parseRange("bytes=9999-", 1000))
        assertNull(ShareProtocol.parseRange("items=0-1", 1000))
        assertNull(ShareProtocol.parseRange("bytes=abc", 1000))
    }

    @Test
    fun etagIsStable() {
        assertEquals("\"12-34\"", ShareProtocol.etagFor(12, 34))
        assertEquals(ShareProtocol.etagFor(12, 34), ShareProtocol.etagFor(12, 34))
        assertFalse(ShareProtocol.etagFor(12, 34) == ShareProtocol.etagFor(12, 35))
    }

    @Test
    fun jsonEscapesControlChars() {
        assertEquals("\"a\\\"b\\\\c\\n\"", ShareProtocol.jsonString("a\"b\\c\n"))
    }
}
