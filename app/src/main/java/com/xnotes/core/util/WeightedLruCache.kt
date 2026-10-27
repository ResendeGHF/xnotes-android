package com.xnotes.core.util

/** Small synchronized cache bounded by retained geometry, rather than only entry count. */
internal class WeightedLruCache<K, V>(private val limit: Int, private val weigh: (K, V) -> Int) {
    private val entries = LinkedHashMap<K, Pair<V, Int>>(16, 0.75f, true)
    private var weight = 0

    @Synchronized
    fun getOrPut(key: K, create: () -> V): V {
        entries[key]?.let { return it.first }
        val value = create()
        val size = weigh(key, value).coerceAtLeast(1)
        if (size > limit) return value
        val iterator = entries.entries.iterator()
        while (weight > limit - size && iterator.hasNext()) {
            weight -= iterator.next().value.second
            iterator.remove()
        }
        entries[key] = value to size
        weight += size
        return value
    }
}
