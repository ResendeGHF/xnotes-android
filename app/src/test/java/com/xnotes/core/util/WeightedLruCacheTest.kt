package com.xnotes.core.util

import org.junit.Assert.*
import org.junit.Test

class WeightedLruCacheTest {
    @Test fun retainsRecentlyUsedEntriesWithinBudget() {
        val cache = WeightedLruCache<String, String>(4) { _, value -> value.length }
        cache.getOrPut("a") { "aa" }
        cache.getOrPut("b") { "bb" }
        assertEquals("aa", cache.getOrPut("a") { error("cache miss") })
        cache.getOrPut("c") { "cc" }
        assertEquals("aa", cache.getOrPut("a") { error("recent entry evicted") })
        assertEquals("new", cache.getOrPut("b") { "new" })
    }

    @Test fun oversizedEntriesDoNotEvictUsefulEntries() {
        val cache = WeightedLruCache<String, String>(2) { _, value -> value.length }
        cache.getOrPut("a") { "aa" }
        assertEquals("large", cache.getOrPut("large") { "large" })
        assertEquals("aa", cache.getOrPut("a") { error("entry evicted") })
        assertEquals("fresh", cache.getOrPut("large") { "fresh" })
    }
}
