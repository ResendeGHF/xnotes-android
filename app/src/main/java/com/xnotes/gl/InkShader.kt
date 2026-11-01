package com.xnotes.gl

import android.opengl.GLES30
import com.xnotes.core.infinite.CanvasProjection
import com.xnotes.core.model.Rgba

/**
 * Draws committed geometry. Zoom and scroll arrive as uniforms and the vertices never move, which
 * is the whole point of the design: nothing is rasterized at a scale that can then be wrong, so a
 * pinch has no blur to resolve when it settles.
 *
 * The vertex shader reassembles a world position from the chunk index and local offset the store
 * split it into. Every intermediate stays small: the chunk difference is a handful of units for
 * anything on screen, and the local offset is under one chunk, so the arithmetic is as exact a
 * hundred million pixels out as it is at the origin.
 */
class InkShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen
    val attribLocal = program.attrib("aLocal")
    val attribChunk = program.attrib("aChunk")
    val attribColor = program.attrib("aColor")
    val attribOffset = program.attrib("aOffset")

    fun release() = program.release()

    /**
     * Bind the program for a frame. [camChunkX]/[camChunkY] are the chunk the viewport's origin
     * falls in, and [localScrollX]/[localScrollY] the scroll expressed inside that chunk, so both
     * the uniform and the attribute stay under a chunk's span.
     */
    fun begin(
        camChunkX: Double,
        camChunkY: Double,
        localScrollX: Double,
        localScrollY: Double,
        zoom: Double,
        viewportW: Double,
        viewportH: Double,
        originX: Double = 0.0,
        originY: Double = 0.0,
    ) {
        program.use()
        program.set("uOrigin", originX.toFloat(), originY.toFloat())
        program.set("uCamChunk", camChunkX.toFloat(), camChunkY.toFloat())
        program.set("uChunkSize", GeometryStore.CHUNK_SIZE.toFloat())
        program.set("uLocalScroll", localScrollX.toFloat(), localScrollY.toFloat())
        program.set("uZoom", zoom.toFloat())
        program.set("uViewport", viewportW.toFloat(), viewportH.toFloat())
        program.set("uOffsetScale", (1.0 / GeometryStore.OFFSET_SCALE).toFloat())
        program.set("uMinHalfPx", MIN_HALF_WIDTH_PX)
        setWidthScale(1.0)
        clearLift()
        clearOverride()
        setPencil(false)
    }

    /**
     * Draw this batch mapped, for a selection being dragged.
     *
     * A drag used to move the model itself, which meant re-tessellating and re-uploading every
     * selected item on every touch sample. The vertices never needed to move: the whole design puts
     * the view in a uniform, and a drag is the same kind of thing.
     *
     * [pivotX]/[pivotY] are in the camera's own chunk frame, the same frame the vertices rebuild
     * themselves in, so nothing large is ever subtracted from anything large.
     */
    fun setLift(
        pivotX: Double,
        pivotY: Double,
        a: Double,
        b: Double,
        c: Double,
        d: Double,
        linearScale: Double,
        dx: Double,
        dy: Double,
    ) {
        program.set("uPivot", pivotX.toFloat(), pivotY.toFloat())
        program.setMat2("uLin", a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat())
        program.set("uLinScale", linearScale.toFloat())
        program.set("uLifted", 1f)
        program.set("uTranslate", dx.toFloat(), dy.toFloat())
    }

    fun clearLift() {
        program.set("uLifted", 0f)
        program.set("uTranslate", 0f, 0f)
    }

    /**
     * Narrow the ribbon about its own centreline. Neon's white-hot core is the body's very
     * geometry at a fraction of the width, so scaling the stored spine offset draws it from the
     * same vertices rather than from a second tessellation and a second copy in the buffer.
     */
    fun setWidthScale(scale: Double) {
        program.set("uWidthScale", scale.toFloat())
    }

    /** Draw with [color] instead of the baked vertex colour, for neon's body and core layers. */
    fun setOverride(color: Rgba) {
        program.set("uOverride", color.r / 255f, color.g / 255f, color.b / 255f, color.a / 255f)
        program.set("uOverrideMix", 1f)
    }

    /** Draw the next pencil runs with the paper grain on; off again afterwards. */
    fun setPencil(enabled: Boolean) {
        program.set("uPencil", if (enabled) 1f else 0f)
    }

    /** Per-stroke grain parameters for the pencil branch (see [setPencil]). */
    fun setPencilParams(seed: Int, tipMix: Float) {
        program.set("uSeed", seed)
        program.set("uTipMix", tipMix)
    }

    fun clearOverride() {
        program.set("uOverrideMix", 0f)
    }

    fun disableAttributes() {
        if (attribLocal >= 0) GLES30.glDisableVertexAttribArray(attribLocal)
        if (attribChunk >= 0) GLES30.glDisableVertexAttribArray(attribChunk)
        if (attribColor >= 0) GLES30.glDisableVertexAttribArray(attribColor)
        if (attribOffset >= 0) GLES30.glDisableVertexAttribArray(attribOffset)
    }

    companion object {
        /**
         * Narrowest half-width a line is ever rasterized at, in device pixels. Below this a line
         * is pushed back out to it and the width it gained is taken out of its alpha, so it fades
         * evenly instead of breaking into a crawling dotted shimmer.
         */
        val MIN_HALF_WIDTH_PX = CanvasProjection.MIN_HALF_WIDTH_PX.toFloat()

        /**
         * Graphite field supertile, in content px. Every octave period divides
         * it, and the canvas chunk is a multiple of it, so wrapping the
         * camera-relative page position keeps the grain glued to the paper.
         * Matches [com.xnotes.core.stroke.PencilGrain.FIELD_TILE]; pinned by test.
         */
        const val PENCIL_FIELD_TILE = 128.0

        private val VERTEX_SRC = """#version 300 es
            in vec2 aLocal;
            in vec2 aChunk;
            in vec4 aColor;
            in vec2 aOffset;

            uniform vec2 uCamChunk;
            uniform float uChunkSize;
            uniform vec2 uLocalScroll;
            uniform float uZoom;
            uniform vec2 uViewport;
            uniform vec2 uOrigin;
            uniform float uOffsetScale;
            uniform float uMinHalfPx;
            uniform float uWidthScale;
            uniform vec2 uTranslate;
            uniform vec2 uPivot;
            uniform mat2 uLin;
            uniform float uLinScale;
            uniform float uLifted;
            uniform vec4 uOverride;
            uniform float uOverrideMix;

            out vec4 vColor;
            // Pencil control, straight from the vertex bytes: x = across-ribbon edge
            // (0 centre, 1 rail/ring), y = graphite density. Only read when uPencil
            // is set; the ink colour itself arrives as the override.
            out vec2 vPencil;
            // Page-space position, camera-relative so every intermediate stays
            // small and exact. The field wraps it into its supertile per pixel,
            // which keeps the grain glued to the paper: live runs and the
            // committed stroke sample the identical page-anchored field.
            out highp vec2 vPage;

            void main() {
                // Rebuild the position relative to the camera's own chunk, so nothing large is ever
                // subtracted from anything large.
                // The vertex sits its own offset out from the line's centre, so the centre is
                // recoverable and the width can be scaled without touching the buffer.
                vec2 spine = aOffset * uOffsetScale;
                vec2 centre = aLocal - spine;
                spine *= uWidthScale;

                // A dragged selection is mapped here rather than in the model. The centre goes
                // straight through the map. The spine cannot: the model scales every width by one
                // scalar and lays it across the mapped ribbon, so recover the ribbon's direction
                // from the spine, map that, and re-lay the spine across it at the scaled length.
                // Nothing under a drag has uLifted set, and that path is untouched.
                vec2 pos = (aChunk - uCamChunk) * uChunkSize + centre;
                if (uLifted > 0.5) {
                    vec2 rel = pos - uPivot;
                    pos = uPivot + uLin * rel;
                    float spineLen = length(spine);
                    if (spineLen > 0.0) {
                        vec2 mapped = uLin * vec2(spine.y, -spine.x);
                        vec2 across = vec2(-mapped.y, mapped.x);
                        float acrossLen = length(across);
                        if (acrossLen > 0.0) spine = across * (spineLen * uLinScale / acrossLen);
                    }
                }
                vec2 world = pos + spine + uTranslate;

                // Zoomed out far enough a stroke is thinner than a pixel, and a sub-pixel line does
                // not simply get fainter: it breaks into a dotted shimmer that crawls as the canvas
                // pans. Push the vertex back out to a pixel and take the width it gained straight
                // out of the alpha, so the line stays a line and only its weight drops. A fill has
                // no spine, so its offset is zero and none of this touches it.
                float fade = 1.0;
                float reach = length(spine) * uZoom;
                if (reach > 0.0 && reach < uMinHalfPx) {
                    world += spine * (uMinHalfPx / reach - 1.0);
                    fade = reach / uMinHalfPx;
                }

                // uOrigin frames the projection on a block of the surface rather than the whole
                // of it, so a target the size of the damage can be drawn into at its own origin.
                // Zero for anything painting the surface itself.
                vec2 device = (world - uLocalScroll) * uZoom - uOrigin;
                gl_Position = vec4(
                    device.x / uViewport.x * 2.0 - 1.0,
                    1.0 - device.y / uViewport.y * 2.0,
                    0.0, 1.0);
                vec4 base = mix(aColor, uOverride, uOverrideMix);
                vColor = vec4(base.rgb, base.a * fade);
                vPencil = vec2(aColor.r, aColor.a);
                // Camera-relative page position for the graphite field. Small
                // magnitudes stay exact, so the per-pixel wrap below lands on
                // the same cells as the paged rasterizer's doubles.
                vPage = world;
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision highp float;
            precision highp int;
            in vec4 vColor;
            in vec2 vPencil;
            in highp vec2 vPage;
            out vec4 fragColor;
            uniform float uPencil;
            // Shared with the vertex stage, whose default float precision is highp:
            // the link fails when the two sides disagree (Mali rejects it outright),
            // so this one is pinned. The app already assumes fragment highp
            // (BackgroundShader renders in it).
            uniform highp float uZoom;
            // Per-stroke grain parameters: the seed decorrelates the noise
            // between strokes, the tip mix fades the coarse bands for fine tips.
            // Only read when uPencil is set. The integer seed keeps both sides
            // bit-identical (see PencilGrain); the paged rasterizer evaluates
            // the same octave stack over the same page coordinates.
            uniform int uSeed;
            uniform float uTipMix;
            // Exact integer lattice hash both renderers share (see PencilGrain):
            // 32-bit ops that never overflow, so the field can never NaN out and
            // matches the paged rasterizer cell-for-cell up to float rounding.
            int plat(ivec2 c) {
                int h = c.x * 73 + c.y * 149;
                h = (h ^ 61) ^ (h >> 3);
                h += h << 5;
                h = (h ^ (h >> 7)) & 0xFFFF;
                h += h << 6;
                h = h ^ (h >> 5);
                return h & 0xFF;
            }
            ivec2 pwrap(ivec2 c, ivec2 m) {
                return ivec2(((c.x % m.x) + m.x) % m.x, ((c.y % m.y) + m.y) % m.y);
            }
            // Same lattice as PencilGrain.noise: explicit lattice coords in,
            // wrapped cell indices out. Bit-identical on both sides.
            float vnoise(highp vec2 l, ivec2 wrap, int salt) {
                highp vec2 f = fract(l);
                ivec2 c = ivec2(floor(l)) + ivec2(salt * 57, salt * 131);
                highp vec2 u = f * f * (3.0 - f * 2.0);
                ivec2 x0 = pwrap(c, wrap);
                ivec2 x1 = pwrap(c + ivec2(1, 0), wrap);
                ivec2 x2 = pwrap(c + ivec2(0, 1), wrap);
                ivec2 x3 = pwrap(c + ivec2(1, 1), wrap);
                float a = float(plat(x0)) * (1.0 / 255.0);
                float b = float(plat(x1)) * (1.0 / 255.0);
                float cc = float(plat(x2)) * (1.0 / 255.0);
                float d = float(plat(x3)) * (1.0 / 255.0);
                return a + (b - a) * u.x + (cc - a) * u.y + (a - b - cc + d) * u.x * u.y;
            }
            void main() {
                if (uPencil < 0.5) { fragColor = vColor; return; }
                // Page-anchored graphite field, identical to the paged rasterizer:
                // wrap the camera-relative page position into the supertile, then
                // evaluate the same octave stack the CPU evaluates. The integer
                // seed shifts every octave's lattice, decorrelating strokes.
                highp vec2 pm = mod(vPage, $PENCIL_FIELD_TILE);
                int ss = uSeed * 13;
                float tip = clamp(uTipMix, 0.0, 1.0);
                float coarse = 0.30 + 0.70 * tip;
                float medium = 0.62 + 0.38 * tip;
                float lump = (vnoise(pm / 32.0, ivec2(4, 4), 11 + ss)
                    + 0.5 * vnoise(pm / 16.0, ivec2(8, 8), 11 + 7919 + ss)
                    + 0.25 * vnoise(pm / 8.0, ivec2(16, 16), 11 + 15838 + ss)) / 1.75;
                float tooth = (vnoise(pm / 8.0, ivec2(16, 16), 23 + ss)
                    + 0.5 * vnoise(pm / 4.0, ivec2(32, 32), 23 + 7919 + ss)
                    + 0.25 * vnoise(pm / 2.0, ivec2(64, 64), 23 + 15838 + ss)) / 1.75;
                float gritF = (vnoise(pm / 4.0, ivec2(32, 32), 37 + ss)
                    + 0.5 * vnoise(pm / 2.0, ivec2(64, 64), 37 + 7919 + ss)
                    + 0.25 * vnoise(pm, ivec2(128, 128), 37 + 15838 + ss)) / 1.75;
                ivec2 scell = pwrap(ivec2(floor(pm)) + ivec2(uSeed * 3, uSeed * 7), ivec2(128, 128));
                float spark = float(plat(scell)) * (1.0 / 255.0);
                float grit = gritF * 0.82 + spark * 0.18;
                float mid = (vnoise(pm / 2.0, ivec2(64, 64), 43 + ss)
                    + 0.5 * vnoise(pm, ivec2(128, 128), 43 + 7919 + ss)) / 1.5;
                float fa = (vnoise(vec2(pm.x / 32.0, pm.y / 2.0), ivec2(4, 64), 53 + ss)
                    + 0.5 * vnoise(vec2(pm.x / 16.0, pm.y), ivec2(8, 128), 53 + 7919 + ss)) / 1.5;
                float fb = (vnoise(vec2(pm.x / 2.0, pm.y / 32.0), ivec2(64, 4), 59 + ss)
                    + 0.5 * vnoise(vec2(pm.x, pm.y / 16.0), ivec2(128, 8), 59 + 7919 + ss)) / 1.5;
                float fibre = mix(fa, fb, 0.5);
                float lodq = clamp((uZoom - 0.25) / 0.75, 0.0, 1.0);
                float lumps;
                if (lodq < 0.5) { lumps = lump; }
                else {
                    lumps = 0.6 * vnoise(pm / 64.0, ivec2(2, 2), 67 + ss)
                        + 0.4 * vnoise(pm / 32.0, ivec2(4, 4), 71 + ss);
                }
                float edge = clamp(vPencil.x, 0.0, 1.0);
                float dens = clamp(vPencil.y, 0.0, 1.0);
                float deff = dens * (1.0 - 0.45 * edge);
                float slow = 0.30 * (lump - 0.5) * coarse
                    + 0.32 * (tooth - 0.5) * medium
                    + 0.22 * (fibre - 0.5) * medium
                    + 0.22 * (lumps - 0.5) * coarse;
                float fast = (grit - 0.5) * 0.95 * (1.10 - 0.15 * tip)
                    + 0.40 * (mid - 0.5);
                float deposit = (0.645 - 0.025 * tip) + slow + fast;
                float edge0 = clamp(0.72 - 0.40 * deff + 0.03375 + 0.045 * (1.0 - tip), 0.02, 0.95);
                float soft = mix(0.20, 0.30, tip);
                float grain = smoothstep(edge0, edge0 + soft, deposit);
                grain = mix(0.05, grain, 0.95);
                float lod = smoothstep(0.45, 0.95, lodq);
                float density = mix(1.0, 0.70 + 0.62 * grit, lod);
                float alpha = clamp(grain * 0.85 * density, 0.0, 1.0);
                if (alpha <= 0.0) discard;
                fragColor = vec4(vColor.rgb, alpha * vColor.a);
            }
        """.trimIndent()
    }
}
