package com.xnotes.core.pal

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.resolvedInvertColors
import com.xnotes.core.model.resolvedInvertImages
import com.xnotes.core.vector.PathFlattener
import com.xnotes.core.vector.VectorContour

/** Polygon fill rule (spec 01 §1). Both are required. */
enum class FillRule { NONZERO, EVEN_ODD }

/**
 * How a layer composites onto what's beneath it. `SRC_OVER` is normal alpha
 * blending; `MULTIPLY` darkens (the highlighter — it can't lighten dark ink, so
 * text stays legible). `SCREEN` is its mirror image, lightening instead, which is what
 * highlights a dark page. A renderer without real separable blends falls back to `SRC_OVER`.
 */
enum class BlendMode { SRC_OVER, MULTIPLY, SCREEN }

/**
 * An outline pen. When [cosmetic] is true the [width] is in *device* pixels and
 * does **not** scale with the current transform (selection outlines, page
 * borders, resize handles stay ~1–1.3 px regardless of zoom — spec 01 §1). When
 * false the width is in the current coordinate system (page pixels), so shape
 * outlines thicken with zoom like the content they belong to.
 */
data class Pen(
    val color: Rgba,
    val width: Double = 1.0,
    val cosmetic: Boolean = true,
    val dashed: Boolean = false,
    /** Dash on/off run lengths used when [dashed]; device px when [cosmetic], else content px. */
    val dashOn: Double = 6.0,
    val dashGap: Double = 4.0,
    /** How far into the dash pattern this line starts, in the same units as [dashOn]. Lets a line
     *  be drawn in two pieces without the rhythm restarting at the seam: the wet cache bakes a
     *  dashed pen's settled run and hands the tail the arc the run already spent. */
    val dashPhase: Double = 0.0,
    /** Soft outward glow (page-px blur) on the stroke — the neon halo for shapes. 0 = crisp. */
    val glowRadius: Double = 0.0,
)

/**
 * What a stretch of drawing is, for a backend that records a document's structure (a tagged PDF
 * a screen reader can follow). Painters announce it with [Renderer.beginMark]; every other
 * backend ignores it. Marks that stand for one thing on the page are instances, so two images
 * drawn back to back stay two images.
 */
sealed class Mark {
    /** Paper, ruling, highlights, chips, rules: what a reader skips. */
    object Decoration : Mark()

    /** Characters [start, end) of flow paragraph [para]. */
    class FlowText(val para: Int, val start: Int, val end: Int) : Mark()

    /** The bullet, number or box of flow list paragraph [para]. */
    class FlowMarker(val para: Int) : Mark()

    /** The title of the callout flow paragraph [para] opens. */
    class FlowTitle(val para: Int) : Mark()

    /** Handwriting and shapes. */
    object Drawing : Mark()

    /** One inserted image. */
    class Image : Mark()

    /** One text box, occupying [bounds] on the page. */
    class TextBox(val bounds: Rect) : Mark()
}

/**
 * Immediate-mode 2D vector painter (spec 01 §1). The application issues draw
 * calls every frame; the renderer retains no scene. The same interface draws to
 * the screen, to offscreen [RasterSurface]s, and to PDF export.
 *
 * Only translation, uniform/axis scaling and quarter-turn [rotate] are used —
 * never free rotation or shear. The one exception is [drawImage], which turns a
 * placed bitmap about its own centre because pixels cannot be rotated by moving
 * points the way ink and shapes are.
 */
interface Renderer {
    // --- transform / clip stack ---
    fun save()
    fun restore()
    /**
     * Rotate the coordinate system clockwise by [degrees] — always a multiple of 90
     * (the rotated-page view), so implementations stay exact and axis-aligned. The
     * default no-op is for backends that never draw rotated views (PDF export).
     */
    fun rotate(degrees: Double) {}

    /**
     * Begin an offscreen layer over [bounds] that is composited at [alpha] when
     * the matching [restore] runs. Content drawn into the layer is accumulated
     * opaquely first, so overlapping fills don't compound — used to render a
     * translucent stroke (e.g. the highlighter) uniformly. Pair with [restore].
     */
    fun saveLayerAlpha(bounds: Rect, alpha: Double)

    /**
     * Like [saveLayerAlpha] but the layer composites with [blend] when [restore]
     * runs. Used to multiply the highlighter onto the page so it tints light areas
     * and preserves dark ink. Default falls back to plain alpha compositing.
     */
    fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) = saveLayerAlpha(bounds, alpha)

    fun translate(dx: Double, dy: Double)
    fun scale(sx: Double, sy: Double)

    /** Intersect the clip region with an axis-aligned rectangle (content space). */
    fun clipRect(rect: Rect)

    /** Intersect the clip region with a convex polygon (content space): what room left of an item it cut. */
    fun clipPolygon(points: List<Pt>) {
        if (points.size >= 3) clipRect(Rect.bounding(points))
    }

    /**
     * Clear the current clip region to fully transparent (replace, not composite).
     * Lets a cached surface be repaired in place: clip to a dirty rect, [clear] it,
     * then repaint only the items there — instead of rebuilding the whole page
     * (used by the eraser).
     */
    fun clear()

    // --- fills ---
    fun fillBackground(rect: Rect, color: Rgba)
    fun fillRect(rect: Rect, color: Rgba)
    fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule = FillRule.NONZERO)
    fun fillCircle(center: Pt, radius: Double, color: Rgba)
    fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba)

    /**
     * Like [fillPolygon]/[fillCircle] but with a soft Gaussian glow of [blurRadius]
     * page pixels — the neon halo. When [inner] is true the blur is confined to the
     * shape's interior (edges fade *inward* to transparent, nothing spills outside) —
     * used for the white-hot core so the ink colour stays crisp at the tube's rim.
     * [pts] is a packed interleaved x,y polygon (stroke geometry is stored packed).
     * A renderer with no blur primitive (or export target) falls back to a crisp
     * fill, so the ink stays visible.
     */
    fun fillPolygonGlow(pts: FloatArray, color: Rgba, rule: FillRule, blurRadius: Double, inner: Boolean = false) =
        fillPolygon(unpackPts(pts), color, rule)

    fun fillCircleGlow(center: Pt, radius: Double, color: Rgba, blurRadius: Double, inner: Boolean = false) =
        fillCircle(center, radius, color)

    /**
     * Fill an ink ribbon as a circular brush disc swept along [centers] (packed interleaved x,y)
     * with per-point [radii]: a disc at every centre, plus the [Geometry.ribbonQuad] bridging each
     * consecutive pair, all in [color]. Because the nib is a disc, round caps and joins fall out
     * for free on every pen, and because every piece is convex and merely filled (never one
     * self-overlapping outline) a sharp turn can't leave a winding-cancelled gap. The default
     * fills each piece on its own — correct for an opaque single-colour ribbon since the overlaps
     * just repaint the same colour; an anti-aliasing backend should override to union them into
     * one path so shared edges don't seam.
     */
    fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, color: Rgba) =
        fillDiskRibbon(centers, radii, 0, minOf(centers.size / 2, radii.size), color)

    /**
     * [fillDiskRibbon] over the [count] points starting at [from], so a run of a ribbon can be
     * drawn without slicing its arrays. The wet cache paints in two runs — the settled prefix into
     * a raster, the moving tail live over it — and a live stroke's arrays are over-allocated, so
     * neither piece can be described by an array's own length.
     */
    fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count <= 0) return
        fun pt(i: Int) = Pt(centers[2 * i].toDouble(), centers[2 * i + 1].toDouble())
        val end = from + count
        for (i in from until end - 1) {
            val q = Geometry.ribbonQuad(pt(i), radii[i].toDouble(), pt(i + 1), radii[i + 1].toDouble())
            if (q.size >= 3) fillPolygon(q, color, FillRule.NONZERO)
        }
        for (i in from until end) if (radii[i] > 0f) fillCircle(pt(i), radii[i].toDouble(), color)
    }

    /**
     * Fill a pencil run as ONE union path: the swept ribbon quads over
     * [from, from+count) with [radii] scaled by [radiusScale] about the
     * centreline, plus round discs exactly where the run truly needs them.
     *
     * A single path paints every pixel exactly once, so one call never
     * double-darkens — not at folds, caps or joins:
     * - end discs only when [capStart]/[capEnd] (the run touches the stroke's
     *   own first/last point; a single-point run is one such disc);
     * - join discs only for stations strictly inside the run that turn harder
     *   than [RIBBON_JOIN_DISC_ANGLE].
     * Quads share whole edges exactly like the pen's, so neighbouring runs
     * butt-join with no gap. The default fills piece by piece (a flat
     * fallback); anti-aliasing backends override to emit one path.
     *
     * [overwrite]: draw replacing instead of compositing (Porter-Duff SRC).
     * The stroke paints its bands inside an isolating layer: where bands
     * cross at a fold the later band wins instead of compounding, so folds
     * never darken. Backends without an overwrite mode ignore the flag. The
     * default fills piece by piece (a flat fallback).
     *
     * [endJoin]: the run's last station turns hard on the full ribbon (a
     * bake-then-tail seam), so it gets its own join disc even though it is
     * not the stroke's end: the next run starts past it and draws none.
     *
     * [ox]/[oy] shift the sampled grain into page space (sticky paper offset
     * added to the note-local geometry) without moving anything, so a note's
     * committed ink wears the same tooth as the live page stroke. Backends
     * without a grain phase ignore them.
     *
     * [density]: graphite fraction in `[0, 1]` — how much of the run is
     * opaque grain rather than paper. Backends with stipple tiles threshold
     * the shared field at this level; without one, it doubles as the fill
     * alpha (a print-safe average of the same tooth).
     *
     * [shade]: grain colour mix in `[0, 1]` — 1 is the full ink colour, lower
     * mixes toward white for lighter graphite. Stipple backends tint their
     * grains with it; others fold it into the fill alpha.
     */
    fun fillRibbonUnion(
        centers: FloatArray,
        radii: FloatArray,
        from: Int,
        count: Int,
        color: Rgba,
        radiusScale: Float = 1f,
        capStart: Boolean = true,
        capEnd: Boolean = true,
        overwrite: Boolean = false,
        endJoin: Boolean = false,
        ox: Double = 0.0,
        oy: Double = 0.0,
        density: Double = 1.0,
        shade: Double = 1.0,
        grainSize: Double = 1.0,
    ) {
        if (count <= 0) return
        fun pt(i: Int) = Pt(centers[2 * i].toDouble(), centers[2 * i + 1].toDouble())
        fun r(i: Int) = radii[i].toDouble() * radiusScale
        val end = from + count
        if (count == 1) {
            if ((capStart || capEnd) && r(from) > 0.0) fillCircle(pt(from), r(from), color)
            return
        }
        for (i in from until end - 1) {
            if (r(i) <= 0.0 && r(i + 1) <= 0.0) continue
            val q = Geometry.ribbonQuad(pt(i), r(i), pt(i + 1), r(i + 1))
            if (q.size >= 3) fillPolygon(q, color, FillRule.NONZERO)
        }
        if (capStart && r(from) > 0.0) fillCircle(pt(from), r(from), color)
        if (capEnd && r(end - 1) > 0.0) fillCircle(pt(end - 1), r(end - 1), color)
        for (i in ribbonJoinStations(centers, from, count)) {
            if (r(i) > 0.0) fillCircle(pt(i), r(i), color)
        }
        // A hard turn exactly on the run's last station (a bake-then-tail
        // seam): the next run starts past it, so this run owns its disc.
        if (endJoin && !capEnd && r(end - 1) > 0.0) fillCircle(pt(end - 1), r(end - 1), color)
    }

    // --- outlines (cosmetic for chrome, page-space for shapes) ---
    fun strokeRect(rect: Rect, pen: Pen)
    fun strokePolyline(points: List<Pt>, pen: Pen)

    /** [strokePolyline] over a packed interleaved x,y polyline (the dashed pen's centerline). */
    fun strokePolyline(pts: FloatArray, pen: Pen) = strokePolyline(pts, 0, pts.size / 2, pen)

    /** [strokePolyline] over [count] packed points starting at [from]; see [fillDiskRibbon]. */
    fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) =
        strokePolyline(List(count) { Pt(pts[2 * (from + it)].toDouble(), pts[2 * (from + it) + 1].toDouble()) }, pen)

    fun strokePolygon(points: List<Pt>, pen: Pen)
    fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen)

    // --- paths of lines and cubics (shapes) ---

    /** Fill [contours] as one path under [rule]. The default fills a flattened copy of each on its own. */
    fun fillPath(contours: List<VectorContour>, color: Rgba, rule: FillRule = FillRule.NONZERO) {
        for (c in contours) {
            val pts = PathFlattener.flatten(c, PATH_FALLBACK_TOLERANCE)
            if (pts.size >= 3) fillPolygon(pts, color, rule)
        }
    }

    /** Stroke [contours] with [pen]. The default strokes a flattened copy of each. */
    fun strokePath(contours: List<VectorContour>, pen: Pen) {
        for (c in contours) {
            val pts = PathFlattener.flatten(c, PATH_FALLBACK_TOLERANCE)
            if (c.closed) strokePolygon(pts, pen) else strokePolyline(pts, pen)
        }
    }

    // --- raster + text ---
    /** Draw [raster] scaled into [dest] (optionally only [src] sub-rect), smooth sampling. */
    fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect? = null)

    /**
     * Like [drawRaster] but composites the bitmap at [alpha] with [blend]. Lets a pre-rendered
     * (opaque) highlighter ribbon be blitted so it MULTIPLY-darkens against the live page each
     * frame without re-tessellating the stroke. Default ignores [alpha]/[blend] and draws opaque,
     * so backends without a real blend (export/tests) stay correct for the paths that use them.
     */
    fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect? = null) =
        drawRaster(raster, dest, src)

    /**
     * Draw [image] (its encoded source) scaled into [dest], turned [orientation] degrees clockwise
     * (0/90/180/270), mirrored by [flip] inside [dest], and then turned [angle] radians clockwise
     * about [dest]'s centre. The backend decodes only as large as [dest] needs, so a big photo is
     * never fully decoded into memory. A placed bitmap is the one thing that cannot rotate by moving
     * its own points, which is why this is the single primitive that takes a free angle. [part] is
     * the share of the image that fills [dest] (fractions of the image as stored, left top right
     * bottom), null for all of it. Default is a no-op for backends that don't place images.
     */
    fun drawImage(image: ImageData, dest: Rect, orientation: Int = 0, angle: Double = 0.0, flip: Flip = Flip.NONE, part: Rect? = null) {}

    fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags = TextFlags())

    /**
     * [drawText] turned [angle] radians clockwise about [rect]'s centre: a shape's label, which
     * turns with its shape. Laid-out text is the other thing that cannot turn by moving points.
     * The default draws it upright, for backends that never meet a turned shape.
     */
    fun drawTextTurned(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags, angle: Double) =
        drawText(text, rect, font, color, flags)

    /**
     * Draw a single pre-positioned line fragment with its left edge at [x] and its
     * baseline at [baseline] (content space, no wrapping). The flow-text painter
     * places these from [TextMeasurer.advances] prefix sums, so the two must share
     * one paint. Default is a no-op for backends that never see flow text.
     */
    fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {}

    /**
     * True for a backend that writes real text a reader can select and copy (PDF
     * export). Painters then also hand it what the screen never draws: the spaces
     * between words, as runs of their own, so copied text keeps its word breaks
     * and a code block its indentation.
     */
    val writesText: Boolean get() = false

    /**
     * When true, placed images ([drawImage]) and plain raster blits ([drawRaster]) paint
     * lightness-inverted: the "invert images" half of the color-inversion display mode. Backends
     * without image content ignore it. [drawRasterBlended] (highlighter compositing) and
     * wrapping decorators forward or bypass it explicitly, never applying it twice.
     */
    var invertImageContent: Boolean
        get() = false
        set(_) {}

    /** What the drawing from here to [endMark] is. Only a structure-recording backend cares. */
    fun beginMark(mark: Mark) {}

    fun endMark() {}

    /**
     * A list bullet: a dot of [radius] at [center], on a line whose baseline is at
     * [baseline] and whose text is set in [font]. A backend writing real text makes it
     * a glyph that copies as "•"; everything else just paints the dot.
     */
    fun drawBullet(center: Pt, radius: Double, baseline: Double, font: FontSpec, color: Rgba) =
        fillCircle(center, radius, color)

    /**
     * A checklist box outlined [stroke] wide, filled inside when [checked]. A backend
     * writing real text makes it a glyph that copies as "☐" or "☑".
     */
    fun drawCheckbox(box: Rect, stroke: Double, checked: Boolean, baseline: Double, font: FontSpec, color: Rgba) {
        strokeRect(box, Pen(color, width = stroke, cosmetic = false))
        // Checked state is a solid inner fill: legible on any paper without knowing it.
        if (checked) fillRect(box.outset(-box.w * 0.25), color)
    }

    /**
     * Draw the formula [latex] sets at [sizePt], its left edge at [x] and its own
     * baseline on [baseline] so it sits on the line like a word. The backend that
     * draws this must be the one behind [MathTypesetter], which already measured
     * the same string, or the text will wrap around a box that is not there.
     * False when it cannot draw the formula yet, having gone to set it elsewhere:
     * the caller then shows [drawMathPending] where it goes. Default is a no-op,
     * like [drawTextRun], for backends that never see maths.
     */
    fun drawMath(
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean = false,
    ): Boolean = true

    /** Stand in at [box], tinted from [color], for [latex] while it is still being set. */
    fun drawMathPending(latex: String, sizePt: Double, display: Boolean, box: Rect, color: Rgba) {}

    /** Run [block] between matching [save]/[restore] calls. */
    fun withSave(block: () -> Unit) {
        save()
        try {
            block()
        } finally {
            restore()
        }
    }
}

/** Content px a curve may stray when a backend without real paths flattens one. */
private const val PATH_FALLBACK_TOLERANCE = 0.1

/**
 * Turn angle, in radians, past which a ribbon station gets its own join disc
 * in [Renderer.fillRibbonUnion]. Matches the GL tessellator's own threshold
 * ([com.xnotes.core.infinite.StrokeTessellator.JOIN_DISC_ANGLE]) so the
 * vector silhouette and the live mesh agree.
 */
const val RIBBON_JOIN_DISC_ANGLE = 0.18

/** Turn angle at station [i] of packed interleaved [centers], in radians. */
fun ribbonJoinTurn(centers: FloatArray, i: Int): Double {
    val ux = centers[2 * i].toDouble() - centers[2 * i - 2].toDouble()
    val uy = centers[2 * i + 1].toDouble() - centers[2 * i - 1].toDouble()
    val vx = centers[2 * i + 2].toDouble() - centers[2 * i].toDouble()
    val vy = centers[2 * i + 3].toDouble() - centers[2 * i + 1].toDouble()
    if (kotlin.math.hypot(ux, uy) < 1e-9 || kotlin.math.hypot(vx, vy) < 1e-9) return kotlin.math.PI
    return kotlin.math.abs(kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy))
}

/**
 * Slice-local stations of `[from, from+count)` that turn hard enough for
 * their own join disc, in `[1, count-2]`: the ends never qualify (a station
 * needs both neighbours in the slice to measure a turn). Turns ACCUMULATE
 * along the run and reset at every disc, so a long gentle curve still gets
 * discs before its outer edge can notch — dense samples make any single
 * station's turn tiny even when the ribbon bends hard overall. Pure function
 * of the slice, so unit tests pin the bounds the backends share.
 */
fun ribbonJoinStations(centers: FloatArray, from: Int, count: Int): List<Int> {
    if (count < 3) return emptyList()
    val end = from + count
    val out = ArrayList<Int>()
    var acc = 0.0
    for (i in from + 1 until end - 1) {
        acc += ribbonJoinTurn(centers, i)
        if (acc > RIBBON_JOIN_DISC_ANGLE) {
            out.add(i)
            acc = 0.0
        }
    }
    return out
}

/** Packed interleaved x,y → [Pt] list, for the default (non-hot) packed-primitive fallbacks. */
private fun unpackPts(pts: FloatArray): List<Pt> =
    List(pts.size / 2) { Pt(pts[2 * it].toDouble(), pts[2 * it + 1].toDouble()) }

/**
 * A [Renderer] that paints everything through [next] with its colours lightness-inverted
 * (alpha untouched): the canvas color-inversion display mode and its PDF export.
 *
 * Vector ink, fills, ruling and text go through inverted; placed images are forwarded
 * untouched — the underlying renderer inverts those itself only when its own
 * image-inversion flag ([Renderer.invertImageContent]) is set, which is how "invert images"
 * stays optional while every other colour always inverts. Pre-rasterized effect bitmaps handed back via
 * [drawRaster]/[drawRasterBlended] are already inverted at raster time, so they too
 * forward untouched.
 */
class InvertingRenderer(private val next: Renderer) : Renderer {
    override fun save() = next.save()
    override fun restore() = next.restore()
    override fun rotate(degrees: Double) = next.rotate(degrees)
    override fun saveLayerAlpha(bounds: Rect, alpha: Double) = next.saveLayerAlpha(bounds, alpha)
    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) =
        next.saveLayerBlended(bounds, alpha, blend)
    override fun translate(dx: Double, dy: Double) = next.translate(dx, dy)
    override fun scale(sx: Double, sy: Double) = next.scale(sx, sy)
    override fun clipRect(rect: Rect) = next.clipRect(rect)
    override fun clipPolygon(points: List<Pt>) = next.clipPolygon(points)
    override fun clear() = next.clear()

    override fun fillBackground(rect: Rect, color: Rgba) = next.fillBackground(rect, color.inverted())
    override fun fillRect(rect: Rect, color: Rgba) = next.fillRect(rect, color.inverted())
    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) =
        next.fillPolygon(points, color.inverted(), rule)
    override fun fillCircle(center: Pt, radius: Double, color: Rgba) =
        next.fillCircle(center, radius, color.inverted())
    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) =
        next.fillEllipse(center, rx, ry, color.inverted())
    override fun fillPolygonGlow(pts: FloatArray, color: Rgba, rule: FillRule, blurRadius: Double, inner: Boolean) =
        next.fillPolygonGlow(pts, color.inverted(), rule, blurRadius, inner)
    override fun fillCircleGlow(center: Pt, radius: Double, color: Rgba, blurRadius: Double, inner: Boolean) =
        next.fillCircleGlow(center, radius, color.inverted(), blurRadius, inner)
    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) =
        next.fillDiskRibbon(centers, radii, from, count, color.inverted())
    override fun fillRibbonUnion(
        centers: FloatArray,
        radii: FloatArray,
        from: Int,
        count: Int,
        color: Rgba,
        radiusScale: Float,
        capStart: Boolean,
        capEnd: Boolean,
        overwrite: Boolean,
        endJoin: Boolean,
        ox: Double,
        oy: Double,
        density: Double,
        shade: Double,
        grainSize: Double,
    ) = next.fillRibbonUnion(
        centers, radii, from, count, color.inverted(), radiusScale,
        capStart, capEnd, overwrite, endJoin, ox, oy, density, shade, grainSize,
    )

    override fun strokeRect(rect: Rect, pen: Pen) = next.strokeRect(rect, pen.inverted())
    override fun strokePolyline(points: List<Pt>, pen: Pen) = next.strokePolyline(points, pen.inverted())
    override fun strokePolygon(points: List<Pt>, pen: Pen) = next.strokePolygon(points, pen.inverted())
    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) =
        next.strokeEllipse(center, rx, ry, pen.inverted())
    override fun fillPath(contours: List<VectorContour>, color: Rgba, rule: FillRule) =
        next.fillPath(contours, color.inverted(), rule)
    override fun strokePath(contours: List<VectorContour>, pen: Pen) =
        next.strokePath(contours, pen.inverted())

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) = next.drawRaster(raster, dest, src)
    override fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect?) =
        next.drawRasterBlended(raster, dest, alpha, blend, src)
    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double, flip: Flip, part: Rect?) =
        next.drawImage(image, dest, orientation, angle, flip, part)

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) =
        next.drawText(text, rect, font, color.inverted(), flags)
    override fun drawTextTurned(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags, angle: Double) =
        next.drawTextTurned(text, rect, font, color.inverted(), flags, angle)
    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) =
        next.drawTextRun(text, x, baseline, font, color.inverted())

    override val writesText: Boolean get() = next.writesText
    override var invertImageContent: Boolean
        get() = next.invertImageContent
        set(value) {
            next.invertImageContent = value
        }
    override fun beginMark(mark: Mark) = next.beginMark(mark)
    override fun endMark() = next.endMark()
    override fun drawBullet(center: Pt, radius: Double, baseline: Double, font: FontSpec, color: Rgba) =
        next.drawBullet(center, radius, baseline, font, color.inverted())
    override fun drawCheckbox(box: Rect, stroke: Double, checked: Boolean, baseline: Double, font: FontSpec, color: Rgba) =
        next.drawCheckbox(box, stroke, checked, baseline, font, color.inverted())
    override fun drawMath(latex: String, x: Double, baseline: Double, sizePt: Double, color: Rgba, display: Boolean): Boolean =
        next.drawMath(latex, x, baseline, sizePt, color.inverted(), display)
    override fun drawMathPending(latex: String, sizePt: Double, display: Boolean, box: Rect, color: Rgba) =
        next.drawMathPending(latex, sizePt, display, box, color.inverted())

    private fun Pen.inverted(): Pen = copy(color = color.inverted())
}

/**
 * [this] prepared for painting [page] of [doc] under the note's color-inversion mode:
 * image/raster inversion armed, and vector/ruling/text colours lightness-inverted, when the page
 * is inverted. Pre-inverted bitmaps (effect rasters, finished cache surfaces) and the
 * highlighter composite always go through an unflagged, unwrapped renderer, so nothing
 * inverts twice. Call [clearPageInvert] after painting through a renderer shared with
 * other pages. Pure platform-wise, so the shared engine and the app use the same one.
 */
fun Renderer.forInvertedPage(doc: Document, page: Page): Renderer {
    val invert = page.resolvedInvertColors(doc)
    invertImageContent = invert && page.resolvedInvertImages(doc)
    return if (invert) InvertingRenderer(this) else this
}

/** Clear the image/raster inversion [forInvertedPage] may have armed on shared [this]. */
fun Renderer.clearPageInvert() {
    invertImageContent = false
}
