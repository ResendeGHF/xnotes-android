package com.xnotes.handwrite

/**
 * Structured text for text-to-handwriting: plain paragraphs plus the things a
 * flat words-only pass would destroy — display/inline math and tables.
 *
 * Markdown (GFM tables, `$`/`$$` math) and LaTeX (equation environments,
 * `tabular`, sections, lists) parse into the same blocks; typed text, selected
 * text boxes and extracted PDF text arrive as [plainBlocks]. Pure and
 * JVM-testable: layout (which needs a [com.xnotes.core.pal.TextMeasurer])
 * lives in the layout step, not here.
 */
sealed interface HSeg {
    /** Handwritten words. */
    data class Words(val text: String) : HSeg

    /** An inline formula, set as its own editable math row. */
    data class Formula(val latex: String) : HSeg
}

sealed interface HBlock {
    /** A paragraph of inline segments, at [sizeMult] times the base size. */
    data class Para(val segments: List<HSeg> = emptyList(), val sizeMult: Double = 1.0) : HBlock

    /** A display equation: its own editable math row. */
    data class Math(val latex: String) : HBlock

    /** A table: rows of cell strings, kept as an editable table. */
    data class Table(val rows: List<List<String>>) : HBlock

    /** A blank-paragraph spacer. */
    object Gap : HBlock
}

/** One PDF page's convertible content: text blocks, or a traced scan when it has no text layer. */
sealed interface PdfPageContent {
    data class Text(val blocks: List<HBlock>) : PdfPageContent
    data class Scan(val page: HandwriteTrace.ScanPage) : PdfPageContent
}

/** Cap on consecutive spacers, so gappy sources stay compact. */
private const val MAX_GAPS = 2

/** Plain text: one paragraph per line, blanks as spacers. `$` stays literal. */
fun plainBlocks(text: String): List<HBlock> {
    val out = ArrayList<HBlock>()
    var gaps = 0
    for (line in text.split('\n')) {
        if (line.isBlank()) {
            if (gaps < MAX_GAPS) {
                out.add(HBlock.Gap)
                gaps++
            }
        } else {
            gaps = 0
            out.add(HBlock.Para(listOf(HSeg.Words(line.trim()))))
        }
    }
    return out
}

/** A short plain preview of [blocks] for the dialog's text field. */
fun blocksPreview(blocks: List<HBlock>): String {    val out = ArrayList<String>()
    for (b in blocks) {
        when (b) {
            is HBlock.Para -> out.add(b.segments.joinToString(" ") {
                when (it) {
                    is HSeg.Words -> it.text
                    is HSeg.Formula -> "\$${it.latex}\$"
                }
            })
            is HBlock.Math -> out.add("\$\$${b.latex}\$\$")
            is HBlock.Table -> {
                for (r in b.rows) out.add(r.joinToString(" | "))
                out.add("")
            }
            HBlock.Gap -> out.add("")
        }
    }
    return out.joinToString("\n").trim('\n')
}

/** True when [blocks] hold anything convertible (not just spacers). */
fun blocksHaveContent(blocks: List<HBlock>): Boolean = blocks.any {
    when (it) {
        is HBlock.Para -> it.segments.any { s ->
            when (s) {
                is HSeg.Words -> s.text.isNotBlank()
                is HSeg.Formula -> s.latex.isNotBlank()
            }
        }
        is HBlock.Math -> it.latex.isNotBlank()
        is HBlock.Table -> it.rows.any { r -> r.any { c -> c.isNotBlank() } }
        HBlock.Gap -> false
    }
}

// --- inline math splitting (shared) ---

/** Split [text] on `\(...\)` and `$...$` into words and formulas. */
fun splitInlineMath(text: String): List<HSeg> {
    val out = ArrayList<HSeg>()
    var i = 0
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(HSeg.Words(buf.toString()))
            buf.clear()
        }
    }
    while (i < text.length) {
        if (text.startsWith("\\(", i)) {
            val end = text.indexOf("\\)", i + 2)
            if (end >= 0) {
                flush()
                out.add(HSeg.Formula(text.substring(i + 2, end).trim()))
                i = end + 2
                continue
            }
        }
        // Display `$$…$$`, even mid-paragraph.
        if (text.startsWith("$$", i)) {
            val end = text.indexOf("$$", i + 2)
            if (end > i + 2) {
                flush()
                out.add(HSeg.Formula(text.substring(i + 2, end).trim()))
                i = end + 2
                continue
            }
        }
        if (text[i] == '$' && (i + 1 >= text.length || text[i + 1] != '$')) {
            var end = i + 1
            var found = -1
            while (end < text.length) {
                if (text[end] == '$' && text[end - 1] != '\\') {
                    found = end
                    break
                }
                end++
            }
            if (found > i + 1) {
                flush()
                out.add(HSeg.Formula(text.substring(i + 1, found).trim()))
                i = found + 1
                continue
            }
        }
        buf.append(text[i])
        i++
    }
    flush()
    return out.ifEmpty { listOf(HSeg.Words("")) }
}

// --- Markdown ---

/** GFM-ish Markdown: headings, lists, tables, fences and `$` math survive as blocks. */
fun parseMarkdown(src: String): List<HBlock> {
    val out = ArrayList<HBlock>()
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').lines()
    val para = ArrayList<String>()
    var item: StringBuilder? = null
    var itemBullet = ""
    var fence: String? = null
    val code = ArrayList<String>()
    var gaps = 0
    fun gap() {
        if (gaps < MAX_GAPS) {
            out.add(HBlock.Gap)
            gaps++
        }
    }
    fun flushPara() {
        if (para.isNotEmpty()) {
            val joined = para.joinToString(" ")
            if (joined.isNotBlank()) out.add(inlinePara(joined))
            para.clear()
        }
    }
    fun flushItem() {
        item?.let {
            val text = it.toString().trim()
            if (text.isNotEmpty()) out.add(inlinePara(itemBullet + text))
        }
        item = null
    }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val lead = line.trimStart()
        if (fence != null) {
            if (lead.startsWith(fence!!)) {
                out.add(HBlock.Para(listOf(HSeg.Words(code.joinToString("\n"))), sizeMult = 0.95))
                gaps = 0
                fence = null
                code.clear()
            } else {
                code.add(raw)
            }
            i++
            continue
        }
        val fenceMark = Regex("""^(`{3,}|~{3,})""").find(lead)?.groupValues?.get(1)
        if (fenceMark != null) {
            flushPara()
            flushItem()
            fence = fenceMark
            i++
            continue
        }
        if (line.isBlank()) {
            flushPara()
            flushItem()
            gap()
            i++
            continue
        }
        gaps = 0
        val heading = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(lead)
        if (heading != null) {
            flushPara()
            flushItem()
            val mult = 1.5 - 0.1 * (heading.groupValues[1].length - 1)
            out.add(inlinePara(cleanMdInline(heading.groupValues[2]), mult))
            i++
            continue
        }
        // A pipe table: header, alignment row, then body rows.
        if ('|' in line && i + 1 < lines.size && isAlignRow(lines[i + 1])) {
            flushPara()
            flushItem()
            val rows = ArrayList<List<String>>()
            rows.add(splitPipeRow(line))
            i += 2
            while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) {
                rows.add(splitPipeRow(lines[i]))
                i++
            }
            if (rows.any { r -> r.any { it.isNotEmpty() } }) out.add(HBlock.Table(rows))
            continue
        }
        val display1 = Regex("""^\$\$(.*)\$\$\s*$""").matchEntire(lead)
        if (display1 != null) {
            flushPara()
            flushItem()
            val latex = display1.groupValues[1].trim()
            if (latex.isNotEmpty()) out.add(HBlock.Math(latex))
            i++
            continue
        }
        if (lead.startsWith("$$")) {
            flushPara()
            flushItem()
            // No closing fence ahead: ordinary text, not math.
            if (lines.subList(i + 1, lines.size).none { "$$" in it }) {
                para.add(lead)
                i++
                continue
            }
            val acc = StringBuilder(lead.removePrefix("$$"))
            i++
            while (i < lines.size && !lines[i].contains("$$")) {
                acc.append('\n').append(lines[i])
                i++
            }
            if (i < lines.size) {
                acc.append('\n').append(lines[i].substringBefore("$$"))
                i++
            }
            val latex = acc.toString().trim()
            if (latex.isNotEmpty()) out.add(HBlock.Math(latex))
            continue
        }
        val bracket = Regex("""^\\\[(.*)\\]\s*$""").matchEntire(lead)
        if (bracket != null) {
            flushPara()
            flushItem()
            val latex = bracket.groupValues[1].trim()
            if (latex.isNotEmpty()) out.add(HBlock.Math(latex))
            i++
            continue
        }
        val listMark = Regex("""^([-*+]|\d+[.)])\s+(\[[ xX]]\s+)?(.*)$""").matchEntire(lead)
        if (listMark != null) {
            flushPara()
            flushItem()
            val box = listMark.groupValues[2]
            val number = Regex("""^(\d+)[.)]$""").matchEntire(listMark.groupValues[1])?.groupValues?.get(1)
            itemBullet = when {
                box.isNotEmpty() -> if (box.contains('x', true)) "☒ " else "☐ "
                number != null -> "$number. "
                else -> "• "
            }
            item = StringBuilder(listMark.groupValues[3])
            i++
            continue
        }
        if (item != null && (raw.startsWith("  ") || raw.startsWith("\t"))) {
            item!!.append(' ').append(lead)
            i++
            continue
        }
        if (Regex("""^([-*_]\s*){3,}$""").matches(lead) || Regex("""^[=-]{2,}\s*$""").matches(lead)) {
            flushPara()
            flushItem()
            i++
            continue
        }
        flushItem()
        para.add(if (lead.startsWith(">")) lead.drop(1).trimStart() else lead)
        i++
    }
    flushPara()
    flushItem()
    return out
}

/** Split [raw] on inline math first, then run [cleaner] over the word runs only. */
private fun splitClean(raw: String, cleaner: (String) -> String): List<HSeg> =
    splitInlineMath(raw).mapNotNull { seg ->
        when (seg) {
            is HSeg.Words -> cleaner(seg.text).takeIf { it.isNotEmpty() }?.let { HSeg.Words(it) }
            is HSeg.Formula -> seg.takeIf { it.latex.isNotEmpty() }
        }
    }

/** One raw line as a paragraph: inline math splits first so cleanup only touches the word runs. */
private fun inlinePara(raw: String, mult: Double = 1.0): HBlock.Para =
    HBlock.Para(splitClean(raw, ::cleanMdInline), mult)

private fun isAlignRow(line: String): Boolean =
    Regex("""^\s*\|?[\s:|-]+\|?\s*$""").matches(line) && '-' in line

private fun splitPipeRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.drop(1)
    if (t.endsWith("|")) t = t.dropLast(1)
    return t.split('|').map { cleanMdInline(it.trim()) }
}

/** Inline Markdown cleanup that never touches `$` math. */
fun cleanMdInline(s: String): String {
    var t = s
    t = t.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
    t = t.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
    t = t.replace(Regex("""`{1,3}(.*?)`{1,3}"""), "$1")
    t = t.replace(Regex("""\*\*(.*?)\*\*"""), "$1")
    t = t.replace(Regex("""__(.*?)__"""), "$1")
    t = t.replace(Regex("""~~(.*?)~~"""), "$1")
    t = t.replace(Regex("""(?<!\w)\*(?!\s)([^*\n]+)(?<!\s)\*(?!\w)"""), "$1")
    t = t.replace(Regex("""(?<!\w)_(?!\s)([^_\n]+)(?<!\s)_(?!\w)"""), "$1")
    t = t.replace(Regex("""</?[A-Za-z][^<>]*>"""), "")
    t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    return t.trim()
}

// --- LaTeX ---

private val MATH_ENVS = setOf(
    "equation", "align", "gather", "multline", "flalign", "math", "displaymath", "eqnarray",
)

/** LaTeX: sections, lists, equation environments, tabulars and inline math survive as blocks. */
fun parseLatex(src: String): List<HBlock> {
    var t = src.replace("\r\n", "\n").replace('\r', '\n')
    if ("\\begin{document}" in t) t = t.substringAfter("\\begin{document}")
    if ("\\end{document}" in t) t = t.substringBefore("\\end{document}")
    t = t.replace(Regex("""(?m)(?<!\\)%.*$"""), "")
    val out = ArrayList<HBlock>()
    val envRe = Regex("""\\begin\{([a-zA-Z]+\*?)}""")
    var pos = 0
    while (true) {
        val m = envRe.find(t, pos) ?: break
        out.addAll(parseLatexChunk(t.substring(pos, m.range.first)))
        val name = m.groupValues[1].removeSuffix("*")
        val end = findEndEnv(t, name, m.range.last + 1)
        val body = if (end != null) t.substring(m.range.last + 1, end.first) else t.substring(m.range.last + 1)
        when (name) {
            in MATH_ENVS -> {
                val latex = stripLabels(body).trim()
                if (latex.isNotEmpty()) out.add(HBlock.Math(latex))
            }
            "tabular", "tabular*" -> parseTabular(body)?.let { out.add(it) }
            "itemize", "enumerate", "description" -> {
                var n = 0
                for (piece in body.split(Regex("""\\item\b""")).drop(1)) {
                    n++
                    val prefix = if (name == "enumerate") "$n. " else "• "
                    val segs = splitClean(piece.trim(), ::cleanLatexInline).toMutableList()
                    if (segs.isNotEmpty() && segs[0] is HSeg.Words) {
                        segs[0] = HSeg.Words(prefix + (segs[0] as HSeg.Words).text)
                    } else if (segs.isNotEmpty()) {
                        segs.add(0, HSeg.Words(prefix.trim()))
                    }
                    if (segs.isNotEmpty()) out.add(HBlock.Para(segs))
                }
            }
            "verbatim", "lstlisting" -> {
                if (body.isNotBlank()) out.add(HBlock.Para(listOf(HSeg.Words(body.trim('\n'))), 0.95))
            }
            else -> out.addAll(parseLatexChunk(body))
        }
        if (end == null) {
            pos = t.length
            break
        }
        pos = end.last + 1
    }
    if (pos < t.length) out.addAll(parseLatexChunk(t.substring(pos)))
    return out
}

/** Range of the matching `\end{name}` or `\end{name*}`, or null. */
private fun findEndEnv(t: String, name: String, from: Int): IntRange? {
    val tag = Regex("""\\(begin|end)\{""" + Regex.escape(name) + """\*?}""")
    var depth = 1
    var i = from
    while (true) {
        val m = tag.find(t, i) ?: return null
        if (m.groupValues[1] == "begin") depth++ else {
            depth--
            if (depth == 0) return m.range
        }
        i = m.range.last + 1
    }
}

private fun parseLatexChunk(s: String): List<HBlock> {
    val out = ArrayList<HBlock>()
    var t = s.replace(Regex("""\\(maketitle|tableofcontents|newpage|clearpage|title|author|date)\b[^\n]*"""), " ")
    // Sections become heading paragraphs.
    t = t.replace(Regex("""\\(sub)*section\{([^{}]*)}""")) {
        "\n\n@@H${if (it.groupValues[1].isEmpty()) "1" else "2"}@@${it.groupValues[2].trim()}@@\n\n"
    }
    t = t.replace(Regex("""\\(sub)*subsection\{([^{}]*)}""")) {
        "\n\n@@H3@@${it.groupValues[2].trim()}@@\n\n"
    }
    // Display math first, so `$` splitting never sees it.
    val displays = ArrayList<String>()
    t = t.replace(Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)) {
        displays.add(it.groupValues[1].trim())
        "\n\n@@M${displays.size - 1}@@\n\n"
    }
    t = t.replace(Regex("""\\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL)) {
        displays.add(it.groupValues[1].trim())
        "\n\n@@M${displays.size - 1}@@\n\n"
    }
    var gaps = 0
    for (para in t.split(Regex("""\n\s*\n"""))) {
        // Single newlines inside a paragraph are spaces, like LaTeX reads them.
        val p = para.replace(Regex("""\s+"""), " ").trim()
        if (p.isEmpty()) {
            if (gaps < MAX_GAPS) {
                out.add(HBlock.Gap)
                gaps++
            }
            continue
        }
        gaps = 0
        val head = Regex("""@@H([123])@@(.*?)@@""", RegexOption.DOT_MATCHES_ALL).matchEntire(p)
        if (head != null) {
            val mult = when (head.groupValues[1]) { "1" -> 1.5; "2" -> 1.35; else -> 1.2 }
            val segs = splitClean(head.groupValues[2], ::cleanLatexInline)
            if (segs.isNotEmpty()) out.add(HBlock.Para(segs, mult))
            continue
        }
        val math = Regex("""@@M(\d+)@@""").matchEntire(p)
        if (math != null) {
            val latex = stripLabels(displays[math.groupValues[1].toInt()]).trim()
            if (latex.isNotEmpty()) out.add(HBlock.Math(latex))
            continue
        }
        // Inline math splits on the raw paragraph first: cleaning eats
        // backslash commands, so it may only touch the word runs.
        val segs = splitClean(p, ::cleanLatexInline)
        if (segs.isNotEmpty()) out.add(HBlock.Para(segs))
    }
    return out
}

private fun stripLabels(latex: String): String =
    latex.replace(Regex("""\\label\{[^}]*}"""), " ").replace(Regex("""\\tag\{[^}]*}"""), " ")

/** A tabular body to a Table, or null when it holds no cells. */
fun parseTabular(body: String): HBlock.Table? {
    // Drop the column spec ({cc}, {|c|c|}, …) — it is not a row.
    var t = body.replaceFirst(Regex("""^\s*(\{[^{}]*\})+"""), " ")
    t = t.replace(Regex("""\\(hline|cline\{[^}]*\}|toprule|midrule|bottomrule|cmidrule(\[[^\]]*])?\{[^}]*\}|addlinespace(\[[^\]]*])?)"""), " ")
    val rows = ArrayList<List<String>>()
    for (raw in t.split(Regex("""\\\\\\\\"""))) {
        val cells = splitLatexCells(raw).map { cleanLatexInline(it.trim()) }
        if (cells.any { it.isNotEmpty() }) rows.add(cells)
        if (rows.size >= 80) break
    }
    if (rows.isEmpty()) return null
    val cols = rows.maxOf { it.size }.coerceAtMost(10)
    return HBlock.Table(rows.map { r -> List(cols) { i -> r.getOrNull(i).orEmpty() } })
}

/** Split a tabular row on `&` outside braces. */
private fun splitLatexCells(row: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var cur = StringBuilder()
    var i = 0
    while (i < row.length) {
        val c = row[i]
        when {
            c == '\\' && i + 1 < row.length -> {
                cur.append(c).append(row[i + 1])
                i += 2
            }
            c == '{' -> {
                depth++
                cur.append(c)
                i++
            }
            c == '}' -> {
                if (depth > 0) depth--
                cur.append(c)
                i++
            }
            c == '&' && depth == 0 -> {
                out.add(cur.toString())
                cur = StringBuilder()
                i++
            }
            else -> {
                cur.append(c)
                i++
            }
        }
    }
    out.add(cur.toString())
    return out
}

/** Text-only LaTeX cleanup (math is extracted before this runs). */
fun cleanLatexInline(s: String): String {
    var t = s
    t = t.replace("\\%", "‹pct›").replace("\\&", "‹amp›").replace("\\$", "‹dol›")
        .replace("\\#", "‹hsh›").replace("\\_", "‹und›").replace("\\{", "‹lbr›").replace("\\}", "‹rbr›")
    t = t.replace(Regex("""\\\\(\[[^\]]*])?"""), " ")
    repeat(4) {
        t = t.replace(Regex("""\\[a-zA-Z]+\*?(?:\[[^\]]*])?\{([^{}]*)}"""), "$1")
    }
    t = t.replace(Regex("""\\[a-zA-Z]+\*?"""), " ")
    t = t.replace(Regex("""\\["'`^~Hcvu=.](\{([A-Za-z])}|([A-Za-z]))"""), "$2$3")
    t = t.replace(Regex("""[{}]"""), "")
    t = t.replace('\\', ' ')
    t = t.replace('~', ' ')
    t = t.replace("‹pct›", "%").replace("‹amp›", "&").replace("‹dol›", "$")
        .replace("‹hsh›", "#").replace("‹und›", "_").replace("‹lbr›", "(").replace("‹rbr›", ")")
    return t.replace(Regex("""[ \t\f\u000B]+"""), " ").trim()
}
