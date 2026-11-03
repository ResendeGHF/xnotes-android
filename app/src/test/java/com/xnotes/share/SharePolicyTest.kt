package com.xnotes.share

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePolicyTest {

    @Test
    fun emptyByDefault() {
        val policy = SharePolicy.of(Files.createTempDirectory("share-pol").toFile())
        assertTrue(policy.sharedUris().isEmpty())
        assertFalse(policy.isShared("content://x/1"))
    }

    @Test
    fun selectAndUnselectRoundTrip() {
        val policy = SharePolicy.of(Files.createTempDirectory("share-pol2").toFile())
        policy.setShared("content://x/1", true)
        policy.setShared("content://x/2", true)
        assertTrue(policy.isShared("content://x/1"))
        policy.setShared("content://x/1", false)
        assertFalse(policy.isShared("content://x/1"))
        assertEquals(setOf("content://x/2"), policy.sharedUris())
    }

    @Test
    fun pruneMissingDropsStaleUris() {
        val policy = SharePolicy.of(Files.createTempDirectory("share-pol3").toFile())
        policy.setShared("content://x/1", true)
        policy.setShared("content://x/gone", true)
        assertEquals(1, policy.pruneMissing(setOf("content://x/1")))
        assertEquals(setOf("content://x/1"), policy.sharedUris())
    }

    @Test
    fun sameDirSharesOneInstance() {
        val dir = Files.createTempDirectory("share-pol4").toFile()
        val a = SharePolicy.of(dir)
        val b = SharePolicy.of(dir)
        a.setShared("content://x/9", true)
        assertTrue(b.isShared("content://x/9"))
    }
}
