package com.xnotes.core.model

/** An RGBA colour with each channel an integer in `[0, 255]` (spec README §4). */
data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int = 255) {

    /** Same colour with its alpha multiplied by [factor] (e.g. highlighter ×0.35). */
    fun scaleAlpha(factor: Double): Rgba = copy(a = (a * factor).toInt().coerceIn(0, 255))

    fun withAlpha(newAlpha: Int): Rgba = copy(a = newAlpha.coerceIn(0, 255))

    /** Inverted copy for the color-inversion display mode (alpha untouched, see below). */
    fun inverted(): Rgba = hslLightnessFlipped()

    /**
     * This app's color-inversion algorithm (same as the notes app's `withInversion`): the HSL
     * lightness flipped end for end (`1 - L`), hue and saturation kept, alpha untouched. Reds
     * stay red instead of turning cyan; white/black/greys behave like a plain invert.
     * Replicates Dart's `HSLColor` conversion branch for branch (including round-half-up),
     * so object colours match that app's inversion exactly.
     */
    private fun hslLightnessFlipped(): Rgba {
        val r = r / 255.0
        val g = g / 255.0
        val b = b / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        var h = 0.0
        var s = 0.0
        if (max != min) {
            val d = max - min
            s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
            h = when {
                max == r -> (g - b) / d + (if (g < b) 6.0 else 0.0)
                max == g -> (b - r) / d + 2.0
                else -> (r - g) / d + 4.0
            } / 6.0
        }
        return hslToRgba(h, s, 1.0 - l, a)
    }

    /** Perceived brightness in [0, 1], by the Rec. 709 weights. */
    fun luminance(): Double = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

    /** Packed ARGB (the platform-neutral 0xAARRGGBB packing the Renderer consumes). */
    fun toArgb(): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun toList(): List<Int> = listOf(r, g, b, a)

    companion object {
        /** The text caret: a fixed light blue that stays visible on both light and dark page
         *  backgrounds, where the theme accent (often white) does not. */
        val CARET = Rgba(64, 156, 255)

        fun fromArgb(argb: Int): Rgba =
            Rgba((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)

        /** Forgiving parse of a `[r,g,b(,a)]` list; `null`/too-short yields `null`. */
        fun fromList(list: List<Int>?): Rgba? {
            if (list == null || list.size < 3) return null
            return Rgba(
                list[0].coerceIn(0, 255),
                list[1].coerceIn(0, 255),
                list[2].coerceIn(0, 255),
                (list.getOrNull(3) ?: 255).coerceIn(0, 255),
            )
        }

        /** Parse a `#rrggbb` (or `#aarrggbb`) hex string; `null` on malformed input. */
        fun fromHex(hex: String?): Rgba? {
            val s = hex?.trim()?.removePrefix("#") ?: return null
            return try {
                when (s.length) {
                    6 -> Rgba(
                        s.substring(0, 2).toInt(16),
                        s.substring(2, 4).toInt(16),
                        s.substring(4, 6).toInt(16),
                    )
                    8 -> Rgba(
                        s.substring(2, 4).toInt(16),
                        s.substring(4, 6).toInt(16),
                        s.substring(6, 8).toInt(16),
                        s.substring(0, 2).toInt(16),
                    )
                    else -> null
                }
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun toHex(c: Rgba): String = "#%02x%02x%02x".format(c.r, c.g, c.b)
    }
}

/** Dart `double.round()` (half away from zero) for channel values known non-negative. */
private fun roundChannel(v: Double): Int = kotlin.math.floor(v + 0.5).toInt().coerceIn(0, 255)

/** Dart `HSLColor.toColor`, shared by [Rgba.inverted]. */
private fun hslToRgba(h: Double, s: Double, l: Double, a: Int): Rgba {
    val chroma = (1.0 - kotlin.math.abs(2.0 * l - 1.0)) * s
    val secondary = chroma * (1.0 - kotlin.math.abs(((h * 6.0) % 2.0) - 1.0))
    val m = l - chroma / 2.0
    val (rr, gg, bb) = when {
        h < 1.0 / 6.0 -> Triple(chroma, secondary, 0.0)
        h < 2.0 / 6.0 -> Triple(secondary, chroma, 0.0)
        h < 3.0 / 6.0 -> Triple(0.0, chroma, secondary)
        h < 4.0 / 6.0 -> Triple(0.0, secondary, chroma)
        h < 5.0 / 6.0 -> Triple(secondary, 0.0, chroma)
        else -> Triple(chroma, 0.0, secondary)
    }
    return Rgba(roundChannel((rr + m) * 255.0), roundChannel((gg + m) * 255.0), roundChannel((bb + m) * 255.0), a)
}
