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
        setPencil(0f)
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

    fun clearOverride() {
        program.set("uOverrideMix", 0f)
    }

    /**
     * Pencil cover mode for the next draws: 0 off (ordinary ink), 1 graphite
     * stipple. The grain texture unit must be bound before drawing; see
     * [setPencilGrain].
     */
    fun setPencil(mode: Float) {
        program.set("uPencil", mode)
    }

    /** Density map matching [com.xnotes.core.infinite.StrokeTessellator.pencilDensityFor]. */
    fun setPencilDensity(base: Float, span: Float, gamma: Float) {
        program.set("uDensBase", base)
        program.set("uDensSpan", span)
        program.set("uDensGamma", gamma)
    }

    /** Grain shade map matching [com.xnotes.core.infinite.StrokeTessellator.pencilShadeFor]. */
    fun setPencilShade(base: Float, span: Float, gamma: Float) {
        program.set("uShadeBase", base)
        program.set("uShadeSpan", span)
        program.set("uShadeGamma", gamma)
    }

    /**
     * Per-stroke pencil config: graphite density scale, pressure sensitivity
     * gain, colour-shade response. The grain size folds into the tile span
     * ([setPencilGrain]) and the phase into [setPencilPhase].
     */
    fun setPencilConfig(densityScale: Float, pressureGain: Float, shadeResponse: Float) {
        program.set("uPencilCfg", densityScale, pressureGain, shadeResponse, 0f)
    }

    /** Grain tile phase in tile units: decorrelates strokes so they layer. */
    fun setPencilPhase(phaseX: Float, phaseY: Float) {
        program.set("uGrainPhase", phaseX, phaseY)
    }

    /** Grain sampler unit and the content px one tile spans. */
    fun setPencilGrain(unit: Int, tilePx: Float) {
        program.set("uGrain", unit)
        program.set("uGrainTile", tilePx)
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
            // Pencil cover: the page-space position for the grain lookup.
            // Small magnitudes stay exact, so the tile phase matches the
            // paged renderer's bitmap shader cell-for-cell up to filtering.
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
                vPage = world;
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision highp float;
            precision highp int;
            in vec4 vColor;
            in highp vec2 vPage;
            out vec4 fragColor;
            // Pencil cover: 0 off, 1 graphite stipple. The vertex alpha
            // carries raw stylus pressure (already min-width faded, like
            // every other vertex alpha); pressure maps to graphite DENSITY
            // (how many pixels carry ink) and to grain SHADE (light grey to
            // full ink), and the field texture stipples both into opaque
            // grains composited normally. The maps match
            // StrokeTessellator.pencilDensityFor/pencilShadeFor; uGrainTile
            // matches PencilTexture.TILE_CONTENT_PX times the stroke's grain
            // size, uGrainPhase its decorrelating offset.
            uniform float uPencil;
            uniform sampler2D uGrain;
            uniform float uDensBase;
            uniform float uDensSpan;
            uniform float uDensGamma;
            uniform float uShadeBase;
            uniform float uShadeSpan;
            uniform float uShadeGamma;
            uniform vec4 uPencilCfg;
            uniform vec2 uGrainPhase;
            uniform float uGrainTile;
            void main() {
                if (uPencil < 0.5) { fragColor = vColor; return; }
                float pe = uPencilCfg.y <= 0.0 ? 1.0 : clamp(clamp(vColor.a, 0.0, 1.0) * uPencilCfg.y, 0.0, 1.0);
                float dens = clamp((uDensBase + uDensSpan * pow(pe, uDensGamma)) * uPencilCfg.x, 0.0, 0.97);
                float shade = clamp(uShadeBase + (uShadeSpan * pow(pe, uShadeGamma)) * uPencilCfg.z, 0.0, 1.0);
                float f = step(texture(uGrain, vPage / uGrainTile + uGrainPhase).a, dens);
                vec3 graphite = mix(vec3(1.0), vColor.rgb, shade);
                fragColor = vec4(graphite, f);
            }
        """.trimIndent()
    }
}
