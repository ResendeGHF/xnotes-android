package com.xnotes.core.text

/**
 * The Markdown twin of [VlmLatex]: what to ask the vision model for and how to
 * clean up its answer when the user wants Markdown instead of LaTeX. Pure Kotlin
 * with JVM unit tests; the HTTP call itself is shared with the LaTeX path.
 */
object VlmMarkdown {

    /**
     * The instruction sent with every page image. Mirrors the LaTeX prompt but asks
     * for Markdown: headings, lists and tables where the layout shows them, maths
     * kept as `$...$` / `\[...\]` for MathJax-style renderers, drawings ignored.
     */
    fun promptFor(pageIndex: Int, pageCount: Int): String =
        """Transcribe this image of page ${pageIndex + 1} of $pageCount of a handwritten note into Markdown.
Rules: output ONLY the page content. No explanations, no code fences, no commentary.
- Plain handwriting becomes plain paragraphs. Use # headings, - or 1. lists and Markdown tables only where the layout clearly shows them.
- Displayed maths goes on its own lines as \[ ... \]. Maths inside a sentence goes inline as $ ... $.
- Escape nothing: this is Markdown, not LaTeX.
- Ignore drawings, graphs, doodles and crossings-out: do not describe them, emit nothing for them.
- If a line is unreadable, transcribe your best reading. Never invent content that is not there.""".trimIndent()

    /**
     * Clean up one page of model output: drop code fences and surrounding blank
     * lines. Unlike LaTeX there is no preamble to strip, so everything else stays
     * exactly as the model wrote it.
     */
    fun sanitize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', s).trim()
            if (s.endsWith("```")) s = s.dropLast(3).trim()
        }
        return s
    }

    /**
     * Join per-page Markdown bodies into one document, each under an invisible
     * `<!-- Page N -->` marker so the page structure survives without showing up
     * in rendered output. Empty pages are skipped; "" when nothing was read.
     */
    fun assembleDocument(bodies: List<String>): String {
        val out = StringBuilder()
        var any = false
        for ((i, body) in bodies.withIndex()) {
            val b = body.trim()
            if (b.isEmpty()) continue
            if (any) out.append("\n\n")
            out.append("<!-- Page ${i + 1} -->\n\n")
            out.append(b)
            out.append('\n')
            any = true
        }
        return if (any) out.toString().trimEnd() + "\n" else ""
    }
}
