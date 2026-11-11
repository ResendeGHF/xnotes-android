package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import kotlin.math.roundToInt

/**
 * A post-it style sticky note pinned on the page.
 *
 * Collapsed it is a small rounded icon ([iconRect]) filled with its paper
 * [color] (so the icon's outline is the paper color itself); expanded it is a
 * square of paper ([rect]) holding typed [text] plus freehand [ink] in
 * note-local coordinates (offsets from [rect].topLeft, so a move never touches
 * them).
 *
 * Ink strokes keep their own tool/config; they paint clipped to the paper.
 * [measurer] lays the text out for the GL canvas (like [TextItem]); the paged
 * canvas draws text through [Renderer] and needs no measurer.
 */
class StickyNoteItem(
    var rect: Rect,
    var color: Rgba = DEFAULT_COLOR,
    var collapsed: Boolean = false,
    var text: String = "",
    val ink: MutableList<Stroke> = mutableListOf(),
    val measurer: TextMeasurer,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override val mirrorable = false
    override var locked = false
    override var link: String? = null
    override var group: Int? = null
    override var sync: SyncMark? = null

    /** Collapsed icon box anchored at the paper's top-left, or the paper itself. */
    fun iconRect(): Rect = Rect(rect.x, rect.y, ICON_SIZE, ICON_SIZE)

    override fun bounds(): Rect = if (collapsed) iconRect() else rect

    override fun paintBounds(): Rect = bounds().outset(3.0)

    /** Collapse-button hit box (top-left of the expanded paper). */
    fun collapseHit(): Rect = Rect(rect.x, rect.y, CONTROL_HIT, CONTROL_HIT)

    fun isCollapseHit(p: Pt): Boolean = !collapsed && collapseHit().contains(p)

    /** Paper-color control next to the collapse button: tap it to cycle the paper color. */
    fun colorHit(): Rect = Rect(rect.x + CONTROL_HIT, rect.y, CONTROL_HIT, CONTROL_HIT)

    fun isColorHit(p: Pt): Boolean = !collapsed && colorHit().contains(p)

    /** The text box inside the paper, under the control row. */
    fun textBox(): Rect = Rect(
        rect.x + PAD, rect.y + TOP_BAR,
        (rect.w - PAD * 2.0).coerceAtLeast(1.0), (rect.h - TOP_BAR - PAD).coerceAtLeast(1.0),
    )

    /** The paper, always opaque: the icon's fill and outline are one color. */
    fun paperColor(): Rgba = color.withAlpha(255)

    /** The icon's outline: the paper color itself. */
    fun edgeColor(): Rgba = paperColor()

    fun foldColor(): Rgba = shade(color, 0.16)

    fun detailColor(): Rgba = shade(color, 0.34)

    fun borderColor(): Rgba = shade(color, BORDER_SHADE)

    fun textColor(): Rgba = if (color.luminance() > 0.45) INK_ON_LIGHT else INK_ON_DARK

    private fun shade(c: Rgba, toBlack: Double): Rgba {
        val f = toBlack.coerceIn(0.0, 1.0)
        return Rgba(
            (c.r * (1.0 - f)).roundToInt().coerceIn(0, 255),
            (c.g * (1.0 - f)).roundToInt().coerceIn(0, 255),
            (c.b * (1.0 - f)).roundToInt().coerceIn(0, 255),
            255,
        )
    }

    override fun paint(r: Renderer) {
        if (collapsed) {
            paintIcon(r)
            return
        }
        paintPaper(r)
    }

    /** Small rounded post-it glyph: solid paper, folded corner, two ruled lines. */
    private fun paintIcon(r: Renderer) {
        val b = iconRect()
        val edge = edgeColor()
        val body = iconOutline(b)
        r.fillPolygon(body, edge)
        r.strokePolygon(body, Pen(edge, ICON_EDGE_WIDTH, cosmetic = false))
        // Folded corner in a deeper shade of the paper.
        val f = ICON_FOLD
        r.fillPolygon(
            listOf(Pt(b.right - f, b.top), Pt(b.right, b.top + f), Pt(b.right - f, b.top + f)),
            foldColor(),
        )
        // Two minimalist ruled lines.
        val linePen = Pen(detailColor(), 1.8, cosmetic = false)
        val y1 = b.top + ICON_SIZE * 0.55
        val y2 = b.top + ICON_SIZE * 0.72
        val x0 = b.left + 8.0
        val x1 = b.right - 8.0
        r.strokePolyline(listOf(Pt(x0, y1), Pt(x1, y1)), linePen)
        r.strokePolyline(listOf(Pt(x0, y2), Pt(x1 - 7.0, y2)), linePen)
    }

    private fun paintPaper(r: Renderer) {
        val b = rect
        val paper = paperColor()
        r.fillPolygon(
            listOf(b.topLeft, Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom)),
            paper,
        )
        r.strokePolygon(
            listOf(b.topLeft, Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom)),
            Pen(borderColor(), BORDER_WIDTH, cosmetic = false),
        )
        // Folded top-right corner.
        val f = FOLD
        r.fillPolygon(
            listOf(Pt(b.right - f, b.top), Pt(b.right, b.top + f), Pt(b.right - f, b.top + f)),
            foldColor(),
        )
        r.strokePolyline(
            listOf(Pt(b.right - f, b.top), Pt(b.right, b.top + f)),
            Pen(detailColor(), 1.5, cosmetic = false),
        )
        // Collapse ×, top-left.
        val fg = textColor()
        val c = Pt(b.x + CONTROL_HIT / 2.0, b.y + CONTROL_HIT / 2.0)
        val h = 5.0
        val pen = Pen(fg.withAlpha(190), 2.0, cosmetic = false)
        r.strokePolyline(listOf(Pt(c.x - h, c.y - h), Pt(c.x + h, c.y + h)), pen)
        r.strokePolyline(listOf(Pt(c.x - h, c.y + h), Pt(c.x + h, c.y - h)), pen)
        // Color dot beside it: paper fill ringed in the text color.
        val d = Pt(b.x + CONTROL_HIT + CONTROL_HIT / 2.0, b.y + CONTROL_HIT / 2.0)
        r.fillCircle(d, DOT_RADIUS, paper)
        r.strokePolygon(circleOutline(d, DOT_RADIUS), Pen(fg.withAlpha(190), 1.8, cosmetic = false))

        // Typed text under the control row.
        if (text.isNotEmpty()) {
            val box = textBox()
            if (box.w > 4.0 && box.h > 4.0) {
                r.save()
                r.clipRect(box)
                r.drawText(
                    text, box, FontSpec(TEXT_SIZE, FontFace.SANS, bold = false), textColor(),
                    TextFlags(wordWrap = true, alignLeft = true, alignTop = true, hAlign = HAlign.LEFT),
                )
                r.restore()
            }
        }
        // Freehand ink, clipped to the paper.
        if (ink.isNotEmpty()) {
            r.save()
            r.clipRect(b)
            r.translate(b.x, b.y)
            for (s in ink) s.paint(r)
            r.restore()
        }
    }

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) {
            val old = rect
            val next = handle.rect
            if (old.w > 0.0 && old.h > 0.0 && next.w > 0.0 && next.h > 0.0) {
                val sx = next.w / old.w
                val sy = next.h / old.h
                if (sx != 1.0 || sy != 1.0) {
                    for (s in ink) scaleStrokeLocal(s, sx, sy)
                }
            }
            rect = next
        }
    }

    override fun snapshotGeometry(): GeometrySnapshot =
        StickySnapshot(rect, ink.map { Stroke(it) })

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is StickySnapshot) return
        rect = snap.rect
        ink.clear()
        for (s in snap.ink) ink.add(Stroke(s))
    }

    override fun applyTransform(t: Affine) {
        val old = rect
        val corners = listOf(
            t.apply(Pt(old.left, old.top)), t.apply(Pt(old.right, old.top)),
            t.apply(Pt(old.right, old.bottom)), t.apply(Pt(old.left, old.bottom)),
        )
        val next = Rect.bounding(corners)
        if (old.w > 0.0 && old.h > 0.0 && next.w > 0.0 && next.h > 0.0) {
            val sx = next.w / old.w
            val sy = next.h / old.h
            val k = t.linearScale
            for (s in ink) {
                scaleStrokeLocal(s, sx, sy)
                if (k != 1.0) {
                    s.config = s.config.copy(
                        baseWidth = s.config.baseWidth * k,
                        dashLength = s.config.dashLength * k,
                        dashGap = s.config.dashGap * k,
                    )
                    s.invalidate()
                }
            }
        }
        rect = next
    }

    companion object {
        const val KIND = "sticky"

        val DEFAULT_COLOR = Rgba(255, 241, 118, 255)

        /** Sticky paper presets for the picker dot and the edit dialog. */
        val PALETTE = listOf(
            Rgba(255, 241, 118, 255), // yellow
            Rgba(255, 205, 210, 255), // pink
            Rgba(200, 230, 201, 255), // green
            Rgba(187, 222, 251, 255), // blue
            Rgba(255, 224, 178, 255), // orange
            Rgba(225, 190, 231, 255), // purple
        )

        const val DEFAULT_WIDTH = 300.0
        const val DEFAULT_HEIGHT = 300.0
        const val MIN_WIDTH = 120.0
        const val MIN_HEIGHT = 120.0

        /** Collapsed icon footprint, in page px: small and rounded. */
        const val ICON_SIZE = 36.0
        const val ICON_RADIUS = 9.0
        const val ICON_FOLD = 8.0
        const val ICON_EDGE_WIDTH = 2.0

        const val FOLD = 18.0
        const val BORDER_WIDTH = 2.0
        const val BORDER_SHADE = 0.28
        const val PAD = 12.0
        const val TOP_BAR = 30.0
        /** Each top control (×, color dot) hit box, in page px. */
        const val CONTROL_HIT = 28.0
        const val DOT_RADIUS = 7.0
        const val TEXT_SIZE = 13.0

        val INK_ON_LIGHT = Rgba(35, 35, 35, 255)
        val INK_ON_DARK = Rgba(245, 245, 245, 255)

        /** Box of the default size centred on [center]. */
        fun boxAt(center: Pt): Rect =
            Rect(center.x - DEFAULT_WIDTH / 2.0, center.y - DEFAULT_HEIGHT / 2.0, DEFAULT_WIDTH, DEFAULT_HEIGHT)

        /**
         * Shift [rect] minimally so the expanded paper stays inside [bounds] (the page
         * footprint on a note, the viewport on a canvas). When the icon was parked at
         * the far right or bottom, an unshifted expand would open off-screen; moving
         * the paper left/up keeps the whole note visible. Oversized paper aligns its
         * top-left with the bounds so at least its origin stays in view.
         */
        fun fitExpanded(rect: Rect, bounds: Rect): Rect {
            var x = rect.x
            var y = rect.y
            x = if (rect.w >= bounds.w) bounds.x else (x.coerceIn(bounds.left, bounds.right - rect.w))
            y = if (rect.h >= bounds.h) bounds.y else (y.coerceIn(bounds.top, bounds.bottom - rect.h))
            return if (x == rect.x && y == rect.y) rect else Rect(x, y, rect.w, rect.h)
        }

        /** A 24-gon ring standing in for a stroked circle (the paged renderer has no strokeCircle). */
        internal fun circleOutline(c: Pt, radius: Double, sides: Int = 24): List<Pt> =
            List(sides) { i ->
                val a = 2.0 * Math.PI * i / sides
                Pt(c.x + radius * kotlin.math.cos(a), c.y + radius * kotlin.math.sin(a))
            }

        /**
         * The collapsed icon's silhouette: a rounded rect whose top-right corner is
         * folded in (a diagonal cut), clockwise from the top edge.
         */
        fun iconOutline(b: Rect): List<Pt> {
            val radius = ICON_RADIUS.coerceIn(0.0, minOf(b.w, b.h) / 2.0)
            val f = ICON_FOLD
            val seg = 5
            val out = ArrayList<Pt>(3 * (seg + 1) + 2)
            // Top edge, into the fold.
            out.add(Pt(b.left + radius, b.top))
            out.add(Pt(b.right - f, b.top))
            // Fold diagonal.
            out.add(Pt(b.right, b.top + f))
            // Right edge down to the bottom-right arc.
            out.add(Pt(b.right, b.bottom - radius))
            fun arc(cx: Double, cy: Double, from: Double, to: Double) {
                for (i in 0..seg) {
                    val a = from + (to - from) * i / seg
                    out.add(Pt(cx + radius * kotlin.math.cos(a), cy + radius * kotlin.math.sin(a)))
                }
            }
            // Bottom-right, bottom-left, top-left arcs (clockwise).
            arc(b.right - radius, b.bottom - radius, 0.0, Math.PI / 2.0)
            arc(b.left + radius, b.bottom - radius, Math.PI / 2.0, Math.PI)
            arc(b.left + radius, b.top + radius, Math.PI, 3.0 * Math.PI / 2.0)
            return out
        }

        internal fun scaleStrokeLocal(s: Stroke, sx: Double, sy: Double) {
            if (sx == 1.0 && sy == 1.0) return
            val pts = s.samples
            if (pts.isEmpty()) return
            val moved = pts.map { com.xnotes.core.stroke.Sample(it.x * sx, it.y * sy, it.pressure, it.t) }
            s.setSamples(moved)
        }
    }
}

/** Geometry snapshot: paper box plus a deep copy of the note-local ink. */
private data class StickySnapshot(val rect: Rect, val ink: List<Stroke>) : GeometrySnapshot
