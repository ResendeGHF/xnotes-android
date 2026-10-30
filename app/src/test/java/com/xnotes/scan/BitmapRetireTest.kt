package com.xnotes.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the retire-a-generation-late bitmap policy. */
class BitmapRetireTest {

    @Test
    fun `oldest generation is disposed once over budget`() {
        val disposed = ArrayList<String>()
        val retire = BitmapRetire<String>(2) { disposed.add(it) }
        retire.retire("a")
        retire.retire("b")
        assertEquals(0, disposed.size)
        retire.retire("c")
        assertEquals(listOf("a"), disposed)
        retire.retire("d")
        assertEquals(listOf("a", "b"), disposed)
        retire.clear()
        assertEquals(listOf("a", "b", "c", "d"), disposed)
    }

    @Test
    fun `nulls are ignored`() {
        var n = 0
        val retire = BitmapRetire<String>(2) { n++ }
        retire.retire(null)
        retire.retire("a")
        assertEquals(0, n)
        assertEquals(1, retire.parked())
    }
}
