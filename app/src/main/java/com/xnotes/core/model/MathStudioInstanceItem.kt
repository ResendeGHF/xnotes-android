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
        // Modern minimalist calculator — inset card darker than paper, centered.
        val calcBody = calcBodyColor()
        val calcContent = calcContentColor()
        val cx = b.center.x
        val cy = b.center.y
        // Slightly smaller, vertically centered, with breathing room from paper edges.
        val calcW = ICON_SIZE * 0.56 // 27.0
        val calcH = ICON_SIZE * 0.60 // 29.0
        val calcRect = Rect(cx - calcW / 2.0, cy - calcH / 2.0 + 0.5, calcW, calcH)
        val calcOutline = roundedRect(calcRect, 5.0)
        r.fillPolygon(calcOutline, calcBody)
        r.strokePolygon(calcOutline, com.xnotes.core.pal.Pen(shade(calcBody, 0.12), 0.9, cosmetic = false))
        // Display — inset, light overlay on dark calc, dark overlay on light calc.
        val displayRect = Rect(calcRect.left + 3.5, calcRect.top + 4.5, calcRect.w - 7.0, 7.0)
        val displayOutline = roundedRect(displayRect, 1.8)
        r.fillPolygon(displayOutline, calcContent.withAlpha(14))
        r.strokePolygon(displayOutline, com.xnotes.core.pal.Pen(calcContent.withAlpha(70), 0.85, cosmetic = false))
        // Minimal button grid: 2 rows × 3 cols = 6 pill-like keys, more breathing room.
        val btnW = 5.2
        val btnH = 5.0
        val gapX = (calcRect.w - 7.0 - btnW * 3) / 2.0
        val gapY = 3.8
        val startX = calcRect.left + 3.5
        val startY = calcRect.top + 14.5
        for (row in 0 until 2) {
            for (col in 0 until 3) {
                val bx = startX + col * (btnW + gapX)
                val by = startY + row * (btnH + gapY)
                val btnRect = Rect(bx, by, btnW, btnH)
                val btnOutline = roundedRect(btnRect, 1.2)
                r.fillPolygon(btnOutline, calcContent.withAlpha(72))
            }
        }
        // Wide bottom key (equals / enter) — single pill, visually heavier.
        val wideRect = Rect(calcRect.left + 3.5, startY + 2 * (btnH + gapY) - 0.5, calcRect.w - 7.0, 4.2)
        val wideOutline = roundedRect(wideRect, 1.4)
        r.fillPolygon(wideOutline, calcContent.withAlpha(92))

        // Name label below icon — always drawn when not default, now inside paintBounds.
        labelRect()?.let { lb ->
            val label = name.take(18)
            r.drawText(
                label, lb, FontSpec(7.0, FontFace.SANS, bold = false), textColor(),
                TextFlags(wordWrap = false, alignLeft = false, alignTop = true, hAlign = HAlign.CENTER),
            )
        }
    }

    fun paperColor(): Rgba = color.withAlpha(255)
    fun edgeColor(): Rgba = paperColor()
    fun foldColor(): Rgba = shade(color, 0.16)
    fun textColor(): Rgba = if (color.luminance() > 0.45) INK_ON_LIGHT else INK_ON_DARK
    fun calcBodyColor(): Rgba = shade(color, 0.26)
    fun calcContentColor(): Rgba = if (calcBodyColor().luminance() > 0.45) INK_ON_LIGHT else INK_ON_DARK

    private fun shade(c: Rgba, toBlack: Double): Rgba {
        val f = toBlack.coerceIn(0.0, 1.0)
        return Rgba(
            (c.r * (1.0 - f)).roundToInt().coerceIn(0, 255),
            (c.g * (1.0 - f)).roundToInt().coerceIn(0, 255),
            (c.b * (1.0 - f)).roundToInt().coerceIn(0, 255),
            255,
        )
    }

    private fun roundedRect(r: Rect, radius: Double): List<Pt> {
        val rad = radius.coerceIn(0.0, minOf(r.w, r.h) / 2.0)
        if (rad <= 0.25) return listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom))
        val seg = 3
        val out = ArrayList<Pt>(4 * seg + 4)
        fun arc(cx: Double, cy: Double, from: Double, to: Double) {
            for (i in 0..seg) {
                val a = from + (to - from) * i / seg
                out.add(Pt(cx + rad * kotlin.math.cos(a), cy + rad * kotlin.math.sin(a)))
            }
        }
        // Top edge
        out.add(Pt(r.left + rad, r.top))
        out.add(Pt(r.right - rad, r.top))
        arc(r.right - rad, r.top + rad, -Math.PI / 2.0, 0.0)
        out.add(Pt(r.right, r.top + rad))
        out.add(Pt(r.right, r.bottom - rad))
        arc(r.right - rad, r.bottom - rad, 0.0, Math.PI / 2.0)
        out.add(Pt(r.right - rad, r.bottom))
        out.add(Pt(r.left + rad, r.bottom))
        arc(r.left + rad, r.bottom - rad, Math.PI / 2.0, Math.PI)
        out.add(Pt(r.left, r.bottom - rad))
        out.add(Pt(r.left, r.top + rad))
        arc(r.left + rad, r.top + rad, Math.PI, 3.0 * Math.PI / 2.0)
        return out
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
