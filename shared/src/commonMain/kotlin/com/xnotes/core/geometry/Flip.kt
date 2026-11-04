package com.xnotes.core.geometry

/**
 * Which ways a drawing is mirrored inside its box: left to right ([x]), upside down ([y]), both or
 * neither. It mirrors about the box's centre lines, so the box itself stays put. A file names one
 * by [id], the way PowerPoint's flipH/flipV and ODF's mirror-horizontal/vertical store it.
 */
enum class Flip(val x: Boolean, val y: Boolean, val id: String) {
    NONE(false, false, ""),
    X(true, false, "x"),
    Y(false, true, "y"),
    XY(true, true, "xy");

    /** This flip with [other]'s axes mirrored once more. */
    fun toggled(other: Flip): Flip = of(x != other.x, y != other.y)

    companion object {
        fun of(x: Boolean, y: Boolean): Flip = when {
            x && y -> XY
            x -> X
            y -> Y
            else -> NONE
        }

        fun fromId(id: String?): Flip = entries.firstOrNull { it.id == id } ?: NONE
    }
}
