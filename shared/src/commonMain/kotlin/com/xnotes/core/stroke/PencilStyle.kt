package com.xnotes.core.stroke

import com.xnotes.core.tools.ToolConfig

/**
 * Everything a pencil run needs beyond geometry: a stable per-stroke grain
 * phase plus the four UI knobs. The phase decorrelates strokes so a new
 * stroke layers darker over settled ink instead of re-inking the same dots;
 * live runs and the committed stroke share it, so pen-up changes nothing.
 */
data class PencilStyle(
    /** Grain tile phase in tile units, from the stroke's first sample. */
    val phaseX: Double,
    /** Grain tile phase in tile units, from the stroke's first sample. */
    val phaseY: Double,
    /** Pressure sensitivity multiplier. */
    val pressure: Double,
    /** Graphite density scale. */
    val density: Double,
    /** Grain size scale multiplying the tile span. */
    val grainSize: Double,
    /** Colour-shade response multiplier. */
    val shadeResponse: Double,
) {
    companion object {
        /** Style for the stroke starting at ([firstX], [firstY]) with [config]. */
        fun of(firstX: Double, firstY: Double, config: ToolConfig): PencilStyle {
            // Two irrational hashes of the quantized first sample: stable for
            // the stroke's whole life (live ribbon, committed geometry,
            // save/load), decorrelated between strokes.
            val qx = (firstX * 100.0).toInt()
            val qy = (firstY * 100.0).toInt()
            var h = qx.toLong() * 0x8DA6B343L + qy.toLong() * 0xD8163841L + 0x9E3779B9L
            h = h xor (h ushr 13)
            h *= 0x5BD1E995L
            h = h xor (h ushr 15)
            val a = (h ushr 11) % 1000L
            val b = (h ushr 29) % 1000L
            return PencilStyle(
                phaseX = a / 1000.0,
                phaseY = b / 1000.0,
                pressure = config.pencilPressure
                    .coerceIn(ToolConfig.PENCIL_PRESSURE_MIN, ToolConfig.PENCIL_PRESSURE_MAX),
                density = config.pencilDensity
                    .coerceIn(ToolConfig.PENCIL_DENSITY_MIN, ToolConfig.PENCIL_DENSITY_MAX),
                grainSize = config.pencilGrainSize
                    .coerceIn(ToolConfig.PENCIL_GRAIN_SIZE_MIN, ToolConfig.PENCIL_GRAIN_SIZE_MAX),
                shadeResponse = config.pencilShadeResponse
                    .coerceIn(ToolConfig.PENCIL_SHADE_RESPONSE_MIN, ToolConfig.PENCIL_SHADE_RESPONSE_MAX),
            )
        }
    }
}
