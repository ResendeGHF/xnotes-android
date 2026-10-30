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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A tappable link to pages of another note (or the same note), placed freely on
 * a page like an image: it moves with long-press drag and resizes by its corners.
 *
 * - [displayName] is the custom label painted on the button.
 * - [targetUri] is the SAF document URI of the linked note, or "" for a link
 *   within the same document ("jump" instead of opening another tab).
 * - [targetName] is the linked file's display name (for the button + dialogs).
 * - [pages] are 0-based page indices in the linked document (one or more).
 * - [color] tints the button border and its text.
 * - [rounded] draws a rounded button instead of a sharp rectangle.
 *
 * The painted text always scales with the box and is truncated to fit inside it,
 * so shrinking the button can never push text outside of it.
 */
class NoteLinkItem(
    var rect: Rect,
    var displayName: String = "",
    var targetUri: String = "",
    var targetName: String = "",
    pages: List<Int> = emptyList(),
    var color: Rgba = DEFAULT_COLOR,
    var rounded: Boolean = false,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override val mirrorable = false
    override var locked = false
    override var group: Int? = null

    var pages: List<Int> = pages.filter { it >= 0 }.distinct().sorted()
        set(value) {
            field = value.filter { it >= 0 }.distinct().sorted()
        }

    /** True when this links within its own document (tap jumps instead of opening a tab). */
    val isSelf: Boolean get() = targetUri.isBlank()

    /** First linked page, or null when no pages are set. */
    val firstPage: Int? get() = pages.firstOrNull()

    /** The label painted on the button: the custom name, else the file name, else a fallback. */
    fun label(): String = displayName.ifBlank { targetName.ifBlank { "Note link" } }

    /** Human 1-based "p. 5" / "pp. 5-7" summary of [pages], or "" when empty. */
    fun pagesLabel(): String = formatPages(pages)

    /** The button outline in page space: sharp corners, or arcs when [rounded]. */
    fun outline(): List<Pt> = if (rounded) roundedOutline(rect, cornerRadius(rect)) else listOf(
        rect.topLeft, Pt(rect.right, rect.top), Pt(rect.right, rect.bottom), Pt(rect.left, rect.bottom),
    )

    override fun paint(r: Renderer) {
        val outline = outline()
        r.fillPolygon(outline, Rgba(color.r, color.g, color.b, FILL_ALPHA))
        r.strokePolygon(outline, Pen(color, width = BORDER_WIDTH, cosmetic = false))
        val text = fittedText(rect.w - PAD * 2.0, rect.h - PAD * 2.0)
        if (text.isEmpty()) return
        val box = Rect(
            rect.x + PAD, rect.y + PAD,
            (rect.w - PAD * 2.0).coerceAtLeast(1.0), (rect.h - PAD * 2.0).coerceAtLeast(1.0),
        )
        // Belt and braces: the text is already measured to fit, but the clip
        // guarantees no glyph can ever paint outside the button on any backend.
        r.save()
        r.clipRect(box)
        r.drawText(
            text, box, FontSpec(textSize(box.w, box.h), FontFace.SANS, bold = true), color,
            TextFlags(wordWrap = true, alignLeft = true, alignTop = true, hAlign = HAlign.CENTER),
        )
        r.restore()
    }

    /**
     * The text that fits on one line in an [availW] x [availH] box: the label plus
     * the page summary ("Label · p. 5"), truncated with an ellipsis so it never
     * overflows. Always a single line, so every backend (canvas and PDF export)
     * lays it out identically. Public for tests.
     */
    fun fittedText(availW: Double, availH: Double): String {
        val size = textSize(availW, availH)
        if (size <= 0.0 || availW <= 0.0 || availH <= 0.0) return ""
        val perLine = (availW / (size * CHAR_WIDTH)).toInt().coerceAtLeast(1)
        val sub = pagesLabel()
        val single = if (sub.isEmpty()) label() else "${label()} · $sub"
        return truncate(single, perLine)
    }

    /** The point size for an [availW] x [availH] box, clamped to a readable range. */
    fun textSize(availW: Double, availH: Double): Double {
        if (availW <= 0.0 || availH <= 0.0) return 0.0
        return minOf(availW / WIDTH_EMS, availH / HEIGHT_EMS).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    }

    override fun bounds(): Rect = rect

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) rect = handle.rect
    }

    override fun snapshotGeometry(): GeometrySnapshot = NoteLinkSnapshot(rect)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is NoteLinkSnapshot) rect = snap.rect
    }

    override fun applyTransform(t: Affine) {
        // Upright box like an image: no rotation support, just re-fit the corners.
        val corners = listOf(
            t.apply(Pt(rect.left, rect.top)), t.apply(Pt(rect.right, rect.top)),
            t.apply(Pt(rect.right, rect.bottom)), t.apply(Pt(rect.left, rect.bottom)),
        )
        rect = Rect.bounding(corners)
    }

    companion object {
        const val KIND = "notelink"
        val DEFAULT_COLOR = Rgba(64, 156, 255)
        const val FILL_ALPHA = 28
        const val BORDER_WIDTH = 2.5

        const val DEFAULT_WIDTH = 220.0
        const val DEFAULT_HEIGHT = 64.0
        const val MIN_WIDTH = 60.0
        const val MIN_HEIGHT = 32.0

        /** Text layout constants: point size targets this many ems across/along the box. */
        const val WIDTH_EMS = 9.0
        const val HEIGHT_EMS = 2.5
        const val MIN_TEXT_SIZE = 8.0
        const val MAX_TEXT_SIZE = 24.0
        const val LINE_HEIGHT = 1.25
        const val CHAR_WIDTH = 0.64
        const val PAD = 8.0

        /** Corner radius of a rounded button, relative to the box's smaller side. */
        const val CORNER_FRACTION = 0.28
        const val CORNER_SEGMENTS = 5

        /** Box of the default size centred on [center]. */
        fun boxAt(center: Pt): Rect =
            Rect(center.x - DEFAULT_WIDTH / 2.0, center.y - DEFAULT_HEIGHT / 2.0, DEFAULT_WIDTH, DEFAULT_HEIGHT)

        /** Corner radius for [rect]'s rounded outline, clamped so arcs never overlap. */
        fun cornerRadius(rect: Rect): Double =
            (minOf(rect.w, rect.h) * CORNER_FRACTION).coerceIn(0.0, minOf(rect.w, rect.h) / 2.0)

        /** Clockwise rounded-rectangle outline of [rect] with corner [radius]. */
        fun roundedOutline(rect: Rect, radius: Double): List<Pt> {
            val r = radius.coerceIn(0.0, minOf(rect.w, rect.h) / 2.0)
            if (r <= 0.0) return listOf(rect.topLeft, Pt(rect.right, rect.top), Pt(rect.right, rect.bottom), Pt(rect.left, rect.bottom))
            val out = ArrayList<Pt>(4 * (CORNER_SEGMENTS + 1))
            // Corner centres clockwise from the top-right, with their start angles.
            val corners = listOf(
                Triple(Pt(rect.right - r, rect.top + r), -PI / 2.0, 0.0),
                Triple(Pt(rect.right - r, rect.bottom - r), 0.0, PI / 2.0),
                Triple(Pt(rect.left + r, rect.bottom - r), PI / 2.0, PI),
                Triple(Pt(rect.left + r, rect.top + r), PI, 3.0 * PI / 2.0),
            )
            for ((centre, from, to) in corners) {
                for (i in 0..CORNER_SEGMENTS) {
                    val a = from + (to - from) * i / CORNER_SEGMENTS
                    out.add(Pt(centre.x + r * cos(a), centre.y + r * sin(a)))
                }
            }
            return out
        }

        /** [s] shortened to [max] characters with an ellipsis, or unchanged when it fits. */
        fun truncate(s: String, max: Int): String {
            if (max <= 0) return ""
            if (s.length <= max) return s
            if (max == 1) return "…"
            return s.take(max - 1) + "…"
        }

        /** "p. 5" for one page, "pp. 5, 7-9" for several (1-based for display). */
        fun formatPages(pages: List<Int>): String {
            if (pages.isEmpty()) return ""
            val ones = pages.sorted().distinct().map { it + 1 }
            // Collapse runs: 5,6,7 -> "5-7".
            val parts = ArrayList<String>()
            var runStart = ones[0]
            var runEnd = ones[0]
            for (n in ones.drop(1)) {
                if (n == runEnd + 1) {
                    runEnd = n
                } else {
                    parts.add(if (runStart == runEnd) "$runStart" else "$runStart-$runEnd")
                    runStart = n
                    runEnd = n
                }
            }
            parts.add(if (runStart == runEnd) "$runStart" else "$runStart-$runEnd")
            val body = parts.joinToString(", ")
            return if (ones.size == 1) "p. $body" else "pp. $body"
        }

        /**
         * Parse a user-typed page spec (1-based, e.g. "1, 3-5") into 0-based indices.
         * Out-of-range/high values are kept (validated against the target on follow/export);
         * unparseable tokens are dropped. Empty input yields an empty list.
         */
        fun parsePages(spec: String): List<Int> {
            val out = LinkedHashSet<Int>()
            for (raw in spec.split(',', ';', ' ', '\n', '\t')) {
                val t = raw.trim()
                if (t.isEmpty()) continue
                val dash = t.indexOfFirst { it == '-' || it == '–' || it == '—' }
                if (dash >= 0) {
                    val a = t.substring(0, dash).trim().toIntOrNull()
                    val b = t.substring(dash + 1).trim().toIntOrNull()
                    if (a != null && b != null && a >= 1 && b >= 1) {
                        val lo = minOf(a, b)
                        val hi = maxOf(a, b)
                        // Guard against absurd ranges blowing up the list.
                        if (hi - lo > 10_000) continue
                        for (n in lo..hi) out.add(n - 1)
                    }
                } else {
                    t.toIntOrNull()?.takeIf { it >= 1 }?.let { out.add(it - 1) }
                }
            }
            return out.sorted()
        }

        /** 1-based spec for [pages], e.g. "5" or "1, 3-5" ("" when empty). */
        fun specFor(pages: List<Int>): String {
            if (pages.isEmpty()) return ""
            val ones = pages.sorted().distinct().map { it + 1 }
            val parts = ArrayList<String>()
            var runStart = ones[0]
            var runEnd = ones[0]
            for (n in ones.drop(1)) {
                if (n == runEnd + 1) {
                    runEnd = n
                } else {
                    parts.add(if (runStart == runEnd) "$runStart" else "$runStart-$runEnd")
                    runStart = n
                    runEnd = n
                }
            }
            parts.add(if (runStart == runEnd) "$runStart" else "$runStart-$runEnd")
            return parts.joinToString(", ")
        }
    }
}

/** Snapshot of a note link's geometry (only its box moves/resizes). */
private data class NoteLinkSnapshot(val rect: Rect) : GeometrySnapshot
