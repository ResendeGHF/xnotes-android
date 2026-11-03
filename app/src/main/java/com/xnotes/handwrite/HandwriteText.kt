package com.xnotes.handwrite

/**
 * Plain-text preparation for text-to-handwriting.
 *
 * Every text source (typed text, a selected text box, an imported .md/.tex/.txt
 * file, PDF text) funnels through here before rasterization, so all of them get
 * the same cleanup. Pure and JVM-testable: no Android dependency.
 */
object HandwriteText {

    /**
     * Longest text a single convert run accepts; beyond this the tail is
     * dropped. Sized for whole documents (tens of pages flow on their own).
     */
    const val MAX_CHARS = 100_000

    /**
     * Strip [content] according to [fileName]'s extension and normalize the
     * result. Unknown extensions pass through as plain text.
     */
    fun plainFromFile(fileName: String, content: String): String {
        val ext = fileName.substringAfterLast('.', "").substringAfterLast('/', "").lowercase()
        val plain = when (ext) {
            "md", "markdown", "mdown", "mkd" -> sanitizeMarkdown(content)
            "tex", "latex", "sty", "cls", "dtx" -> sanitizeLatex(content)
            else -> content
        }
        return normalize(plain)
    }

    /**
     * Collapse line endings, trim line ends, cap runs of blank lines at two and
     * truncate to [MAX_CHARS]. Empty input stays empty (the caller reports it).
     */
    fun normalize(text: String): String {
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(t.length.coerceAtLeast(0))
        var blanks = 0
        for (raw in t.split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                blanks++
                if (blanks <= 2) out.append('\n')
            } else {
                blanks = 0
                out.append(line).append('\n')
            }
        }
        var r = out.toString().trim('\n', ' ', '\t')
        if (r.length > MAX_CHARS) r = r.take(MAX_CHARS)
        return r
    }

    /**
     * Best-effort Markdown strip: headings, emphasis, code fences, links,
     * images, quotes, list markers, tables, rules, HTML tags and frontmatter go;
     * the words and their line breaks stay.
     */
    fun sanitizeMarkdown(src: String): String {
        var t = src.replace("\r\n", "\n").replace('\r', '\n')
        // YAML/TOML frontmatter between leading +++ or --- lines.
        t = t.replace(Regex("""\A(---|\+\+\+)\n.*?\n\1\n""", RegexOption.DOT_MATCHES_ALL), "")
        // Fenced code blocks: drop the fences, keep the code.
        t = t.replace(Regex("""(?m)^[`~]{3,}.*$"""), "")
        // ATX headings: "## Title" -> "Title".
        t = t.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        // Setext underlines.
        t = t.replace(Regex("""(?m)^[=-]{2,}\s*$"""), "")
        // Images keep their alt text; links keep their label.
        t = t.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
        t = t.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        // Reference-style link definitions.
        t = t.replace(Regex("""(?m)^\[[^\]]+]:\s*\S+.*$"""), "")
        // Inline code, bold, italic, strikethrough (strongest first).
        t = t.replace(Regex("""`{1,3}(.*?)`{1,3}"""), "$1")
        t = t.replace(Regex("""\*\*(.*?)\*\*"""), "$1")
        t = t.replace(Regex("""__(.*?)__"""), "$1")
        t = t.replace(Regex("""~~(.*?)~~"""), "$1")
        t = t.replace(Regex("""(?<!\w)\*(?!\s)([^*\n]+)(?<!\s)\*(?!\w)"""), "$1")
        t = t.replace(Regex("""(?<!\w)_(?!\s)([^_\n]+)(?<!\s)_(?!\w)"""), "$1")
        // Block quotes.
        t = t.replace(Regex("""(?m)^\s{0,3}>\s?"""), "")
        // Unordered, ordered and task list markers.
        t = t.replace(Regex("""(?m)^\s*([-*+]|\d+[.)])\s+"""), "")
        t = t.replace(Regex("""(?m)^\s*-\s*\[[ xX]]\s+"""), "")
        // Tables: pipes become spaces, alignment rows go.
        t = t.replace(Regex("""(?m)^\s*\|?[\s:|-]+\|?\s*$"""), "")
        t = t.replace('|', ' ')
        // Horizontal rules.
        t = t.replace(Regex("""(?m)^\s*([-*_]\s*){3,}$"""), "")
        // Raw HTML tags.
        t = t.replace(Regex("""</?[A-Za-z][^<>]*>"""), "")
        // HTML entities for the common cases.
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        return t
    }

    /**
     * Best-effort LaTeX strip: comments, preambles, environments and commands
     * go; command arguments (the human words) and math innards stay as text.
     */
    fun sanitizeLatex(src: String): String {
        var t = src.replace("\r\n", "\n").replace('\r', '\n')
        // Comments (% to end of line, keeping escaped \%).
        t = t.replace(Regex("""(?m)(?<!\\)%.*$"""), "")
        // Escaped specials become their literal character first.
        t = t.replace("\\%","‹pct›").replace("\\&","‹amp›").replace("\\$","‹dol›")
            .replace("\\#","‹hsh›").replace("\\_","‹und›").replace("\\{","‹lbr›").replace("\\}","‹rbr›")
        // Line breaks and paragraph skips.
        t = t.replace(Regex("""\\\\(\[[^\]]*])?"""), "\n")
        // Environments: keep the body, drop the wrappers.
        t = t.replace(Regex("""\\begin\{[^}]*}(?:\[[^\]]*])?(?:\{[^}]*})*"""), "\n")
        t = t.replace(Regex("""\\end\{[^}]*}"""), "\n")
        // Display math \[ \] and \( \): keep the inside.
        t = t.replace(Regex("""\\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL), "$1")
        t = t.replace(Regex("""\\\((.*?)\\\)"""), "$1")
        // $..$ and $$..$$: keep the inside.
        t = t.replace(Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL), "$1")
        t = t.replace(Regex("""\$(.*?)\$"""), "$1")
        // Commands with one braced argument, applied inside-out a few rounds
        // (\section{Title} -> Title). Up to two trailing option/argument groups.
        repeat(4) {
            t = t.replace(Regex("""\\[a-zA-Z]+\*?(?:\[[^\]]*])?\{([^{}]*)}"""), "$1")
        }
        // Left-over commands without arguments.
        t = t.replace(Regex("""\\[a-zA-Z]+\*?"""), " ")
        // Accent shorthands \"a \'e \`u \^o \~n \c c \v r.
        t = t.replace(Regex("""\\["'`^~Hcvu=.](\{([A-Za-z])}|([A-Za-z]))"""), "$2$3")
        // Stray braces and remaining backslashes.
        t = t.replace(Regex("""[{}]"""), "")
        t = t.replace('\\', ' ')
        // Restore the escaped specials.
        t = t.replace("‹pct›","%").replace("‹amp›","&").replace("‹dol›","$")
            .replace("‹hsh›","#").replace("‹und›","_").replace("‹lbr›","(").replace("‹rbr›",")")
        // Collapse horizontal whitespace; newlines are meaningful.
        t = t.replace(Regex("""[ \t\f\u000B]+"""), " ")
        t = t.replace(Regex("""(?m)^ +"""), "")
        return t
    }
}
