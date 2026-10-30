package com.xnotes.core.text

/**
 * The vision-model side of whole-note LaTeX export: what to ask for and how to
 * clean up the answer. Pure Kotlin (no Android imports) so the prompt contract
 * and the sanitizer are pinned by JVM unit tests. The HTTP call itself lives in
 * the platform layer and only ever sends page renders to the provider the user
 * configured in Settings.
 *
 * The model is asked for plain LaTeX only: body text, `$...$` inline maths and
 * `\[...\]` display maths. No preamble, no packages, no explanations.
 */
object VlmLatex {

    /** Largest page dimension, in pixels, sent to the model. Big enough to read handwriting, small enough to upload. */
    const val MAX_IMAGE_DIM_PX = 1600

    /**
     * Fit a [pageW] × [pageH] page into [MAX_IMAGE_DIM_PX] on its long edge,
     * keeping the aspect ratio so formulas are never stretched for the model.
     * Vector pages upscale cleanly, so small pages are enlarged to stay readable.
     */
    fun rasterSize(pageW: Float, pageH: Float, maxDim: Int = MAX_IMAGE_DIM_PX): Pair<Int, Int> {
        val w = pageW.coerceAtLeast(1f)
        val h = pageH.coerceAtLeast(1f)
        val scale = maxDim / maxOf(w, h)
        return maxOf(1, (w * scale).toInt()) to maxOf(1, (h * scale).toInt())
    }

    /**
     * The instruction sent with every page image. It names exactly the output
     * contract: plain LaTeX body, inline versus display maths, drawings ignored.
     */
    fun promptFor(pageIndex: Int, pageCount: Int): String =
        """Transcribe this image of page ${pageIndex + 1} of $pageCount of a handwritten note into LaTeX.
Rules:
- Output ONLY the page's LaTeX body. No preamble, no \documentclass, no \usepackage, no markdown fences, no explanations, no commentary.
- Plain handwriting becomes plain text paragraphs. Escape LaTeX specials (& % $ # _ { }).
- A displayed formula goes on its own lines as \[ ... \].
- Maths inside a sentence goes inline as $ ... $.
- Ignore drawings, graphs, doodles and crossings-out: do not describe them, emit nothing for them.
- Use only basic LaTeX: ^ _ \frac \sqrt \sum \int \times \cdot \leq \geq \neq \pi \infty Greek letters. Never use \text, aligned, or any environment.
- If a line is unreadable, transcribe your best reading. Never invent content that is not there.""".trimIndent()

    /**
     * Turn raw model output into a page body: strip fences and any echoed
     * preamble, drop package imports, and rewrite `\text` (needs amsmath, which
     * the output must not require) as `\mathrm`.
     */
    fun sanitize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', s).trim()
            if (s.endsWith("```")) s = s.dropLast(3).trim()
        }
        val docStart = s.indexOf("\\begin{document}")
        if (docStart >= 0) {
            s = s.substring(docStart + "\\begin{document}".length)
            val docEnd = s.indexOf("\\end{document}")
            if (docEnd >= 0) s = s.substring(0, docEnd)
            s = s.trim()
        }
        val kept = s.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("\\usepackage") || t.startsWith("\\documentclass")
        }
        s = kept.joinToString("\n").trim()
        // \text needs amsmath; \mathrm is base LaTeX and renders the same word.
        s = Regex("""\\text\{([^{}]*)\}""").replace(s, """\\mathrm{$1}""")
        return s
    }

    /**
     * Assemble per-page bodies (already sanitized, "" for unreadable pages) into
     * the final `.tex`: no packages, just text and equations, pages separated.
     */
    fun assembleDocument(bodies: List<String>): String {
        val out = StringBuilder()
        out.append("\\documentclass{article}\n\\begin{document}\n")
        var any = false
        for ((i, body) in bodies.withIndex()) {
            val b = body.trim()
            if (b.isEmpty()) continue
            if (any) out.append("\n\\newpage\n")
            out.append("\n% Page ${i + 1}\n\n")
            out.append(b)
            out.append('\n')
            any = true
        }
        out.append("\n\\end{document}\n")
        return if (any) out.toString() else ""
    }
}
