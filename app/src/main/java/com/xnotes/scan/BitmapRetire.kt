package com.xnotes.scan

/**
 * Retires superseded bitmaps a couple of generations late. A bitmap handed to
 * composition can still be mid-draw on the render thread when its replacement
 * lands, so recycling it immediately risks a native "used a recycled bitmap"
 * crash. Retiring keeps the last [maxKept] generations alive and recycles older
 * ones, which no frame can still reference. Generic over the handle type so the
 * policy unit-tests without Android bitmaps.
 */
class BitmapRetire<T>(private val maxKept: Int = 2, private val dispose: (T) -> Unit) {
    private val retired = ArrayDeque<T>()

    /** The replacement for [current] is live: park [current] for later disposal. */
    fun retire(current: T?) {
        if (current == null) return
        retired.add(current)
        while (retired.size > maxKept) dispose(retired.removeFirst())
    }

    /** Free everything parked (the owner itself is going away). */
    fun clear() {
        while (retired.isNotEmpty()) dispose(retired.removeFirst())
    }

    /** How many generations are parked (tests). */
    fun parked(): Int = retired.size
}
