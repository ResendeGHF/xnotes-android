package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer

/**
 * Room the Insert space tool opened on a page: a band [size] content px down from [at] px below the
 * page's top edge, across its whole width. On a page turned a quarter since, the band stands
 * [sideways]: [size] wide from [at] px right of its left edge, down its whole height.
 */
data class Room(val at: Double, val size: Double, val sideways: Boolean = false) {
    val end: Double get() = at + size
}

/**
 * The arithmetic of a page's rooms ([Page.rooms]). Display space is the page as it stands, room
 * and all; source space is the page as it was before any room opened, which is where its PDF page
 * and the marks on its text lie. Rooms on one axis never touch: a cut inside a room or on its edge
 * grows that room.
 */
object Rooms {

    /** Shorter than this, in content px, a room or a band is nothing. */
    const val EPS = 1e-6

    /** The rooms on one axis, in order. */
    fun on(rooms: List<Room>, sideways: Boolean): List<Room> = rooms.filter { it.sideways == sideways }

    /** All the room on one axis. */
    fun total(rooms: List<Room>, sideways: Boolean): Double = rooms.sumOf { if (it.sideways == sideways) it.size else 0.0 }

    /** The room a position [along] its axis lies in, counting [slop] past either edge, or null. */
    fun at(rooms: List<Room>, along: Double, sideways: Boolean, slop: Double = 0.0): Room? =
        rooms.firstOrNull { it.sideways == sideways && along >= it.at - slop && along <= it.end + slop }

    /** [rooms] with [size] more room at [at]: a cut inside a room or on its edge grows that room, and every room after moves on. */
    fun opened(rooms: List<Room>, at: Double, size: Double, sideways: Boolean = false): List<Room> {
        if (size <= EPS) return rooms
        val grown = at(rooms, at, sideways)
        val out = ArrayList<Room>(rooms.size + 1)
        for (r in rooms) {
            out += when {
                r.sideways != sideways -> r
                r === grown -> r.copy(size = r.size + size)
                r.at > at -> r.copy(at = r.at + size)
                else -> r
            }
        }
        if (grown == null) out += Room(at, size, sideways)
        return out.sortedWith(compareBy<Room>({ it.sideways }, { it.at }))
    }

    /** [rooms] with the strip [from] to [to] of [room] closed, clamped to it: a room closed to nothing is gone, and every room after moves back. */
    fun closed(rooms: List<Room>, room: Room, from: Double, to: Double): List<Room> {
        val a = maxOf(from, room.at)
        val b = minOf(to, room.end)
        val shut = b - a
        if (shut <= EPS) return rooms
        val out = ArrayList<Room>(rooms.size)
        for (r in rooms) {
            when {
                r == room -> if (r.size - shut > EPS) out += r.copy(size = r.size - shut)
                r.sideways == room.sideways && r.at > room.at -> out += r.copy(at = r.at - shut)
                else -> out += r
            }
        }
        return out
    }

    /** A display position along an axis as the source position it shows; a position in a room maps to where that room opened. */
    fun toSource(rooms: List<Room>, along: Double, sideways: Boolean): Double {
        var shift = 0.0
        for (r in rooms) {
            if (r.sideways != sideways) continue
            if (along >= r.end) shift += r.size else if (along > r.at) return r.at - shift else break
        }
        return along - shift
    }

    /** A source position along an axis as the display position it shows at; a position exactly where a room opened stays above it. */
    fun toDisplay(rooms: List<Room>, along: Double, sideways: Boolean): Double {
        var shift = 0.0
        for (r in rooms) {
            if (r.sideways != sideways) continue
            if (along > r.at - shift) shift += r.size else break
        }
        return along + shift
    }

    /** One stretch of source between two cuts, shown [shift] further along. */
    class Band(val from: Double, val to: Double, val shift: Double)

    /** The source's [length] along an axis cut into the bands its rooms open between, empty ones left out. */
    fun bands(rooms: List<Room>, length: Double, sideways: Boolean): List<Band> {
        val out = ArrayList<Band>()
        var from = 0.0
        var shift = 0.0
        for (r in rooms) {
            if (r.sideways != sideways) continue
            val cut = r.at - shift
            if (cut - from > EPS && cut > 0.0) out += Band(maxOf(from, 0.0), minOf(cut, length), shift)
            from = maxOf(from, cut)
            shift += r.size
        }
        if (length - from > EPS) out += Band(maxOf(from, 0.0), length, shift)
        return out.filter { it.to - it.from > EPS }
    }

    /** One rectangle of source, shown moved by [dx], [dy]. */
    class Tile(val source: Rect, val dx: Double, val dy: Double) {
        val display: Rect get() = source.translate(dx, dy)
    }

    /** A [sourceW] by [sourceH] source cut into the tiles its rooms open between. One tile without rooms. */
    fun tiles(rooms: List<Room>, sourceW: Double, sourceH: Double): List<Tile> {
        val xs = bands(rooms, sourceW, true)
        val ys = bands(rooms, sourceH, false)
        val out = ArrayList<Tile>(xs.size * ys.size)
        for (y in ys) for (x in xs) out += Tile(Rect(x.from, y.from, x.to - x.from, y.to - y.from), x.shift, y.shift)
        return out
    }

    /** [rooms] scaled by [k] with their page. */
    fun scaled(rooms: List<Room>, k: Double): List<Room> = if (k == 1.0) rooms else rooms.map { Room(it.at * k, it.size * k, it.sideways) }
}

/**
 * [p], a point of this page as it stands, on the page as it was before any room: where its PDF and
 * the marks on its text are measured. A point in room is on neither side of it, so it is null,
 * unless [clamp] takes it to where that room opened, as a drag across room wants.
 */
fun Page.sourcePoint(p: Pt, clamp: Boolean = false): Pt? {
    if (rooms.isEmpty()) return p
    if (!clamp && rooms.any { r -> val v = if (r.sideways) p.x else p.y; v > r.at && v < r.end }) return null
    return Pt(Rooms.toSource(rooms, p.x, true), Rooms.toSource(rooms, p.y, false))
}

/** A point of the page as it was before any room, where this page shows it. */
fun Page.displayPoint(p: Pt): Pt = if (rooms.isEmpty()) p else Pt(Rooms.toDisplay(rooms, p.x, true), Rooms.toDisplay(rooms, p.y, false))

/** Run [paint], which draws in this page's source space, once for each tile its room cuts that into, each clipped to where it shows. */
inline fun Page.paintInBands(r: Renderer, paint: () -> Unit) {
    if (rooms.isEmpty()) return paint()
    for (t in Rooms.tiles(rooms, sourceWidth, sourceHeight)) {
        r.save()
        r.clipRect(t.display)
        r.translate(t.dx, t.dy)
        paint()
        r.restore()
    }
}

/** The page's width without the room inserted into it: what its PDF page and margins measure against. */
val Page.sourceWidth: Double get() = width - Rooms.total(rooms, true)

/** The page's height without the room inserted into it. */
val Page.sourceHeight: Double get() = height - Rooms.total(rooms, false)
