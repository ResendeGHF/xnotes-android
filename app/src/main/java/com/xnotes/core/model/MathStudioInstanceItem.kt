package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.HAlign
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import kotlin.math.roundToInt

/**
 * A floating Math Studio instance pinned on the page.
 *
 * Rendered as a small rounded icon (like a collapsed sticky) with a calculator glyph,
 * colored with [color] and labeled with [name]. Tapping it opens Math Studio with
 * the workspace stored in [studioJson] — every calculation, plot and data series for
 * that instance. The JSON is the same opaque blob that [Document.mathStudioJson] used
 * to store for the single global studio, now per-instance so a single note can hold
 * many independent studios.
 *
 * The item is intentionally not resizable — only movable — so it stays a recognizable
 * icon. Its [rect] is always an [ICON_SIZE] square anchored at its top-left.
 */
class MathStudioInstanceItem(
    var rect: Rect,
    var name: String = DEFAULT_NAME,
    var color: Rgba = DEFAULT_COLOR,
    var studioJson: String = "",
) : CanvasItem {

    override val kind = KIND
    override val resizable = false
    override val mirrorable = false
    override var locked = false
    override var link: String? = null
    override var group: Int? = null
    override var sync: SyncMark? = null

    fun iconRect(): Rect = Rect(rect.x, rect.y, ICON_SIZE, ICON_SIZE)

    override fun bounds(): Rect = iconRect()

    private fun labelRect(): Rect? {
        if (name.isBlank() || name == DEFAULT_NAME) return null
        val b = iconRect()
        return Rect(b.left - 12, b.bottom + 2, b.w + 24, 12.0)
    }

    override fun paintBounds(): Rect {
        val b = bounds().outset(4.0)
        val l = labelRect() ?: return b
        return b.union(l.outset(2.0))
    }

    override fun paint(r: Renderer) {
        val b = iconRect()
        val paper = paperColor()
        val body = iconOutline(b)
        r.fillPolygon(body, paper)
        r.strokePolygon(body, com.xnotes.core.pal.Pen(edgeColor(), ICON_EDGE_WIDTH, cosmetic = false))
        // Folded corner
        val f = ICON_FOLD
        r.fillPolygon(
            listOf(Pt(b.right - f, b.top), Pt(b.right, b.top + f), Pt(b.right - f, b.top + f)),
            foldColor(),
        )
        // Calculator glyph — simple centered rectangle with display and buttons.
        val fg = textColor()
        val cx = b.center.x
        val cy = b.center.y
        val s = ICON_SIZE * 0.62
        val half = s / 2.0
        // Outer calculator body (inset)
        val calcRect = Rect(cx - half, cy - half + 2.0, s, s * 0.92)
        // Display bar
        r.fillPolygon(
            listOf(
                Pt(calcRect.left + 3, calcRect.top + 4),
                Pt(calcRect.right - 3, calcRect.top + 4),
                Pt(calcRect.right - 3, calcRect.top + 10),
                Pt(calcRect.left + 3, calcRect.top + 10),
            ),
            fg.withAlpha(18),
        )
        r.strokePolygon(
            listOf(
                Pt(calcRect.left + 3, calcRect.top + 4),
                Pt(calcRect.right - 3, calcRect.top + 4),
                Pt(calcRect.right - 3, calcRect.top + 10),
                Pt(calcRect.left + 3, calcRect.top + 10),
            ),
            com.xnotes.core.pal.Pen(fg.withAlpha(90), 1.0, cosmetic = false),
        )
        // Button grid 3x3
        val btnSize = 4.5
        val gapX = (calcRect.w - 6 - btnSize * 3) / 2.0
        val gapY = 4.0
        val startX = calcRect.left + 3
        val startY = calcRect.top + 14
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val bx = startX + col * (btnSize + gapX)
                val by = startY + row * (btnSize + gapY)
                r.fillPolygon(
                    listOf(
                        Pt(bx, by), Pt(bx + btnSize, by),
                        Pt(bx + btnSize, by + btnSize), Pt(bx, by + btnSize),
                    ),
                    fg.withAlpha(85),
                )
            }
        }

        // Name label below icon — inverse of page colour (dark on light, light on dark via inversion).
        // Draw with halo for contrast on any page colour, and keep inside paintBounds for immediate show.
        labelRect()?.let { lb ->
            val label = name.take(18)
            val font = FontSpec(7.0, FontFace.SANS, bold = false)
            val flags = TextFlags(wordWrap = false, alignLeft = false, alignTop = true, hAlign = HAlign.CENTER)
            val labelColor = Rgba(35, 35, 35, 255)
            val haloColor = Rgba(255, 255, 255, 220)
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val haloBox = Rect(lb.x + dx * 0.6, lb.y + dy * 0.6, lb.w, lb.h)
                    r.drawText(label, haloBox, font, haloColor, flags)
                }
            }
            r.drawText(label, lb, font, labelColor, flags)
        }
    }

    fun paperColor(): Rgba = color.withAlpha(255)
    fun edgeColor(): Rgba = paperColor()
    fun foldColor(): Rgba = shade(color, 0.16)
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

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun snapshotGeometry(): GeometrySnapshot = InstanceSnapshot(rect, name, color, studioJson)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is InstanceSnapshot) return
        rect = snap.rect
        name = snap.name
        color = snap.color
        studioJson = snap.studioJson
    }

    override fun applyTransform(t: Affine) {
        val old = rect
        val corners = listOf(
            t.apply(Pt(old.left, old.top)), t.apply(Pt(old.right, old.top)),
            t.apply(Pt(old.right, old.bottom)), t.apply(Pt(old.left, old.bottom)),
        )
        rect = Rect.bounding(corners)
        // Keep it an icon square after any transform
        val size = ICON_SIZE
        rect = Rect(rect.center.x - size / 2.0, rect.center.y - size / 2.0, size, size)
    }

    companion object {
        const val KIND = "math_instance"

        val DEFAULT_COLOR = Rgba(66, 133, 244, 255) // Polars blue
        const val DEFAULT_NAME = "Math Studio"

        val PALETTE = listOf(
            Rgba(66, 133, 244, 255), // blue
            Rgba(52, 168, 83, 255),  // green
            Rgba(234, 67, 53, 255),  // red
            Rgba(251, 188, 4, 255),  // yellow
            Rgba(154, 66, 245, 255), // purple
            Rgba(255, 109, 0, 255),  // orange
        )

        const val ICON_SIZE = 48.0
        const val ICON_RADIUS = 10.0
        const val ICON_FOLD = 9.0
        const val ICON_EDGE_WIDTH = 2.0

        val INK_ON_LIGHT = Rgba(35, 35, 35, 255)
        val INK_ON_DARK = Rgba(245, 245, 245, 255)

        fun boxAt(center: Pt, name: String = DEFAULT_NAME, color: Rgba = DEFAULT_COLOR, json: String = ""): Rect =
            Rect(center.x - ICON_SIZE / 2.0, center.y - ICON_SIZE / 2.0, ICON_SIZE, ICON_SIZE)

        fun iconOutline(b: Rect): List<Pt> {
            val radius = ICON_RADIUS.coerceIn(0.0, minOf(b.w, b.h) / 2.0)
            val f = ICON_FOLD
            val seg = 5
            val out = ArrayList<Pt>(3 * (seg + 1) + 2)
            out.add(Pt(b.left + radius, b.top))
            out.add(Pt(b.right - f, b.top))
            out.add(Pt(b.right, b.top + f))
            out.add(Pt(b.right, b.bottom - radius))
            fun arc(cx: Double, cy: Double, from: Double, to: Double) {
                for (i in 0..seg) {
                    val a = from + (to - from) * i / seg
                    out.add(Pt(cx + radius * kotlin.math.cos(a), cy + radius * kotlin.math.sin(a)))
                }
            }
            arc(b.right - radius, b.bottom - radius, 0.0, Math.PI / 2.0)
            arc(b.left + radius, b.bottom - radius, Math.PI / 2.0, Math.PI)
            arc(b.left + radius, b.top + radius, Math.PI, 3.0 * Math.PI / 2.0)
            return out
        }
    }
}

private data class InstanceSnapshot(
    val rect: Rect,
    val name: String,
    val color: Rgba,
    val studioJson: String,
) : GeometrySnapshot
