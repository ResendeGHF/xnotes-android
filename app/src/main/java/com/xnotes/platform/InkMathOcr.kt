package com.xnotes.platform

import com.xnotes.core.model.Stroke
import com.xnotes.core.text.InkMath

/**
 * Handwritten maths to LaTeX from parts already in the project: [InkMath] reads the
 * layout off the ink's own geometry, [HandwritingOcr] (the same on-device models as
 * Convert to text, shared per [tag]) reads each run, and [com.xnotes.core.text.PlainMath]
 * turns that text into LaTeX. Nothing is downloaded for this feature itself, so there
 * is no add-on to 404: it works offline wherever handwriting recognition does.
 *
 * Blocks on the ML Kit calls; call off the main thread.
 */
object InkMathOcr {

    /** Recognize [strokes] as LaTeX, or "" when nothing could be read. */
    fun recognize(strokes: List<Stroke>, tag: String): String {
        if (strokes.none { it.sampleCount > 0 }) return ""
        return InkMath.render(InkMath.structure(strokes)) { part ->
            if (part.isEmpty()) "" else HandwritingOcr.recognize(part, tag)
        }
    }
}
