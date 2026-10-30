package com.xnotes.math

/**
 * LaTeX as produced by handwriting recognition (or typed in notes) into the engine's
 * plain-text syntax, so a selected formula can be dropped straight into symbolic
 * calculus or the 2D/3D plotters.
 *
 * Pure Kotlin (no Android imports) so the rules are pinned by JVM unit tests.
 * Anything unrecognized is kept verbatim where possible: the entry field shows the
 * result for the user to fix, rather than silently changing the maths.
 */
object LatexToExpr {

    /** Markdown/plain input: unwrap `$…$`, take the first `$…$` segment, else convert LaTeX as-is. */
    fun convertOrPass(input: String): String {
        var t = input.trim()
        if (t.isEmpty()) return ""
        t = unwrapWrappers(t).trim()
        if ('$' in t) {
            t = firstDollarSegment(t)?.trim() ?: t.replace("$", "")
            t = t.trim()
        }
        if (t.isEmpty()) return ""
        // Markdown emphasis/code around plain maths (`**x^2**`, `` `x^2` ``) unwraps the same way,
        // but only when there is no LaTeX markup that could legitimately contain those chars.
        var p = t
        if ('\\' !in p && '{' !in p && '}' !in p) {
            p = p.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            p = p.replace(Regex("__(.+?)__"), "$1")
            p = p.replace("`", "").trim()
            if (p.isEmpty()) return ""
        }
        if (p.none { it == '\\' || it == '{' || it == '}' || it == '&' || it == '~' || it == '|' }) return p
        return squash(convert(p))
    }

    private fun unwrapWrappers(s: String): String {
        var t = s
        while (true) {
            val inner = when {
                t.startsWith("$$") && t.endsWith("$$") && t.length > 4 ->
                    t.substring(2, t.length - 2)
                t.startsWith('$') && t.endsWith('$') && t.length > 2 ->
                    t.substring(1, t.length - 1)
                t.startsWith("\\(") && t.endsWith("\\)") && t.length > 4 ->
                    t.substring(2, t.length - 2)
                t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4 ->
                    t.substring(2, t.length - 2)
                else -> return t
            }
            t = inner.trim()
        }
    }

    private fun firstDollarSegment(s: String): String? {
        var open = s.indexOf("$$")
        if (open >= 0) {
            val close = s.indexOf("$$", open + 2)
            if (close > open) return s.substring(open + 2, close)
        }
        open = s.indexOf('$')
        if (open < 0) return null
        val close = s.indexOf('$', open + 1)
        if (close <= open) return null
        return s.substring(open + 1, close)
    }

    private fun squash(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /** Full LaTeX conversion; see [convertOrPass] for markdown-tolerant entry. */
    fun convert(latex: String): String {
        if (latex.isBlank()) return ""
        val cleaned = stripComments(latex)
        val pre = preprocessDelimiters(cleaned)
        return squash(Parser(pre).parseTop())
    }

    /** `%…` comments (not `\%`) go before anything else reads the text. */
    private fun stripComments(s: String): String =
        s.split('\n').joinToString("\n") { line ->
            val out = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (line[i] == '%' && (i == 0 || line[i - 1] != '\\')) break
                out.append(line[i])
                i++
            }
            out.toString()
        }

    /**
     * Normalize delimiter spellings up front so the parser only sees single chars:
     * SOH behaves exactly like `|` (norm bars pair into `abs(…)`); size commands vanish.
     */
    private fun preprocessDelimiters(s: String): String {
        var t = s
        // Sizing hints carry no meaning: the delimiters that follow stay and pair normally.
        t = Regex("\\\\(left|right)\\s*\\.").replace(t, "")
        t = Regex("\\\\(left|right)(?![A-Za-z])").replace(t, "")
        t = t.replace("\\|", "")
        fun word(cmd: String, to: String) {
            t = Regex("\\\\$cmd(?![A-Za-z])").replace(t, to)
        }
        word("Vert", "")
        word("lVert", "")
        word("rVert", "")
        word("vert", "|")
        word("lvert", "|")
        word("rvert", "|")
        word("mid", "|")
        word("langle", "(")
        word("rangle", ")")
        word("lceil", "ceil(")
        word("rceil", ")")
        word("lfloor", "floor(")
        word("rfloor", ")")
        word("big", "")
        word("Big", "")
        word("bigg", "")
        word("Bigg", "")
        return t
    }

    private val GREEK = mapOf(
        "alpha" to "alpha", "beta" to "beta", "gamma" to "gamma", "delta" to "delta",
        "epsilon" to "epsilon", "varepsilon" to "varepsilon", "zeta" to "zeta",
        "eta" to "eta", "theta" to "theta", "vartheta" to "vartheta", "iota" to "iota",
        "kappa" to "kappa", "lambda" to "lambda", "mu" to "mu", "nu" to "nu",
        "xi" to "xi", "omicron" to "omicron", "pi" to "pi", "rho" to "rho",
        "sigma" to "sigma", "tau" to "tau", "upsilon" to "upsilon", "phi" to "phi",
        "varphi" to "varphi", "chi" to "chi", "psi" to "psi", "omega" to "omega",
        "Gamma" to "Gamma", "Delta" to "Delta", "Theta" to "Theta", "Lambda" to "Lambda",
        "Xi" to "Xi", "Pi" to "Pi", "Sigma" to "Sigma", "Upsilon" to "Upsilon",
        "Phi" to "Phi", "Psi" to "Psi", "Omega" to "Omega",
    )

    /** Function names that take one parenthesized argument (pow/subscripts first). */
    private val FUNCS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "coth", "sech", "csch", "asinh", "acosh", "atanh",
        "exp", "ln", "det", "max", "min", "arg",
    )

    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val IDENT_END = Regex("[A-Za-z_][A-Za-z0-9_]*$")

    private class Parser(val s: String) {
        var i = 0
        /** Groups entered through the main loop; body readers stop at their close. */
        private var depth = 0

        fun parseTop(): String = parseSequence(false)

        private fun done(): Boolean = i >= s.length

        private fun skipSpaces() {
            while (!done() && s[i].isWhitespace()) i++
        }

        /** Consumes one balanced `{…}` group (caller must be on `{`), converted. */
        private fun parseBraced(): String {
            i++ // '{'
            depth++
            val inner = parseSequence(stopBrace = true)
            if (!done() && s[i] == '}') {
                i++
            }
            if (depth > 0) depth--
            return inner
        }

        /** Raw substring of the `{…}` group starting at [i] (without converting it). */
        private fun readGroupRaw(): String? {
            if (done() || s[i] != '{') return null
            var depth = 0
            var j = i
            while (j < s.length) {
                if (s[j] == '{') depth++
                else if (s[j] == '}') {
                    depth--
                    if (depth == 0) {
                        val inner = s.substring(i + 1, j)
                        i = j + 1
                        return inner
                    }
                }
                j++
            }
            return null
        }

        /** Converted `(…)` group, or null when there is no `{`. */
        private fun parseGroup(): String? {
            val raw = readGroupRaw() ?: return null
            return "(" + Parser(raw).parseTop() + ")"
        }

        /** One braced script (`^{…}`/`_{…}`) converted, or "" when absent/empty. */
        private fun parseScriptGroup(): String? {
            skipSpaces()
            if (done() || s[i] != '{') return null
            val raw = readGroupRaw() ?: return null
            val inner = Parser(raw).parseTop().trim()
            return if (inner.isEmpty()) "" else "($inner)"
        }

        /** A balanced `(…)` atom, converted inside. */
        private fun parseParenAtom(): String? {
            if (done() || s[i] != '(') return null
            var depth = 0
            var j = i
            while (j < s.length) {
                if (s[j] == '(') depth++
                else if (s[j] == ')') {
                    depth--
                    if (depth == 0) {
                        val inner = s.substring(i + 1, j)
                        i = j + 1
                        return "(" + Parser(inner).parseTop() + ")"
                    }
                }
                j++
            }
            return null
        }

        /** `^…` exponent; "" when there is none (or it is empty, e.g. a dropped prime). */
        private fun parsePow(): String {
            skipSpaces()
            if (done() || s[i] != '^') return ""
            i++
            return parseScriptAtom()
        }

        /** One exponent/subscript atom after `^`/`_` (braces, sign, number, letter, command). */
        private fun parseScriptAtom(): String {
            skipSpaces()
            if (done()) return ""
            return when (val c = s[i]) {
                '{' -> parseGroup() ?: ""
                '(' -> parseParenAtom() ?: ""
                '+', '-' -> {
                    i++
                    val a = parseScriptAtom()
                    if (a.isEmpty()) "" else "($c$a)"
                }
                '\\' -> {
                    val r = parseCommand()
                    if (r.isEmpty()) "" else wrapAtom(r)
                }
                else -> if (c.isDigit()) {
                    val start = i
                    while (!done() && (s[i].isDigit() || s[i] == '.')) i++
                    s.substring(start, i)
                } else if (c.isLetter()) {
                    i++
                    c.toString()
                } else {
                    // Anything else (e.g. a stray `(` handled above): take it raw.
                    i++
                    c.toString()
                }
            }
        }

        private fun wrapAtom(r: String): String {
            val t = r.trim()
            if (t.isEmpty()) return ""
            if (t.startsWith('(') && t.endsWith(')') && t.length > 1) return t
            if (IDENT.matches(t)) return t
            if (t.matches(Regex("^-?[0-9.]+$"))) return t
            return "($t)"
        }

        private fun parseSequence(stopBrace: Boolean): String {
            val out = StringBuilder()
            while (!done()) {
                val c = s[i]
                when {
                    c == '}' -> if (stopBrace) break else i++
                    c == '{' -> out.append('(').append(parseBraced()).append(')')
                    c == '^' -> {
                        i++
                        val sc = parseScriptAtom()
                        if (sc.isNotEmpty()) out.append('^').append(wrapAtom(sc))
                    }
                    c == '_' -> {
                        i++
                        out.append(parseSubscript())
                    }
                    c == '&' || c == '~' -> {
                        out.append(' ')
                        i++
                    }
                    c == '|' || c == '' -> out.append(parseAbs(c))
                    c == '\\' -> out.append(parseCommand())
                    c == '%' -> i++ // stray; comments are stripped up front
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** `_…`: `x_0`/`x_{10}` stay one name; anything operator-like is dropped (never misbound). */
        private fun parseSubscript(): String {
            skipSpaces()
            if (done()) return ""
            val c = s[i]
            if (c == '{') {
                val raw = readGroupRaw() ?: return ""
                val conv = Parser(raw).parseTop().trim().replace(Regex("\\s+"), "")
                if (conv.isEmpty()) return ""
                if (IDENT.matches(conv)) return "_$conv"
                if (conv.any { it in "=<>+-*/^!," }) return ""
                return "_$conv"
            }
            if (c == '\\') {
                val r = parseCommand().trim().replace(Regex("\\s+"), "")
                if (r.isEmpty() || !IDENT.matches(r)) return ""
                return "_$r"
            }
            if (c.isLetterOrDigit()) {
                i++
                return "_$c"
            }
            return ""
        }

        /**
         * `|…|` reads as absolute value. An unmatched bar wraps the rest of the
         * sequence instead of leaking a character the engine rejects.
         */
        private fun parseAbs(bar: Char): String {
            i++ // opening bar
            val inner = StringBuilder()
            var depth = 0
            val start = i
            var j = i
            var found = -1
            while (j < s.length) {
                val d = s[j]
                if (d == '{') depth++
                else if (d == '}') {
                    if (depth == 0) break
                    depth--
                } else if ((d == '|' || d == '') && depth == 0 && !(d == '|' && j > 0 && s[j - 1] == '\\')) {
                    found = j
                    break
                }
                j++
            }
            return if (found < 0) {
                val rest = s.substring(start)
                i = s.length
                "abs((" + Parser(rest).parseTop() + "))"
            } else {
                val rest = s.substring(start, found)
                i = found + 1
                inner.append(Parser(rest).parseTop())
                "abs(($inner))"
            }
        }

        private fun commandName(): String {
            // Precondition: s[i] == '\\' already consumed by caller? No — consume here.
            i++ // backslash
            if (done()) return ""
            if (!s[i].isLetter()) {
                val c = s[i]
                i++
                return c.toString()
            }
            val start = i
            while (!done() && s[i].isLetter()) i++
            return s.substring(start, i)
        }

        /** Convert one command at [i] (backslash included); advances past what it consumes. */
        fun parseCommand(): String {
            if (done() || s[i] != '\\') return ""
            val save = i
            val name = commandName()
            if (name.isEmpty()) return ""
            // Single-character commands.
            if (name.length == 1 && !name[0].isLetter()) {
                return when (name) {
                    ",", ":", ";", "!", " " -> " "
                    "\\" -> " "
                    "{" -> "("
                    "}" -> ")"
                    "%" -> "/100"
                    "$", "#", "&" -> ""
                    "_" -> "_"
                    "^" -> "^"
                    "(" -> "("
                    ")" -> ")"
                    "[" -> "("
                    "]" -> ")"
                    else -> ""
                }
            }
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val a = parseSingleToken() ?: ""
                    val b = parseSingleToken() ?: ""
                    "(($a)/($b))"
                }
                "sqrt" -> {
                    var n: String? = null
                    val si = i
                    skipSpaces()
                    if (!done() && s[i] == '[') {
                        var depth = 0
                        var j = i
                        while (j < s.length) {
                            if (s[j] == '[') depth++
                            else if (s[j] == ']') {
                                depth--
                                if (depth == 0) break
                            }
                            j++
                        }
                        if (j < s.length) {
                            n = Parser(s.substring(i + 1, j)).parseTop()
                            i = j + 1
                        } else {
                            i = si
                        }
                    }
                    val g = parseGroupOrAtom() ?: ""
                    if (n == null) "sqrt(($g))" else "(($g)^(1/($n)))"
                }
                "cdot", "times", "ast" -> "*"
                "div" -> "/"
                "pm" -> "+-"
                "mp" -> "-+"
                "over" -> "/"
                "le", "leq" -> "<="
                "ge", "geq" -> ">="
                "ne", "neq" -> "!="
                "approx", "simeq", "cong", "equiv" -> "="
                "to", "rightarrow", "Rightarrow" -> "->"
                "infty", "oo" -> "inf"
                "left", "right" -> "" // stripped up front; leftovers are dropped
                "sum", "prod" -> parseBigOp(if (name == "sum") "sum" else "prod")
                "int", "oint", "iint", "iiint" -> parseIntegral()
                "lim" -> parseLimit()
                "log" -> parseLog()
                "binom" -> {
                    val a = parseGroupOrAtom() ?: ""
                    val b = parseGroupOrAtom() ?: ""
                    "binomial(($a), ($b))"
                }
                "overset", "underset" -> {
                    parseGroupOrAtom()
                    "(" + (parseGroupOrAtom() ?: "") + ")"
                }
                "boxed", "fbox", "cancel", "bcancel", "xcancel", "smash" -> {
                    "(" + (parseGroupOrAtom() ?: "") + ")"
                }
                "tag", "label", "phantom" -> {
                    parseGroupOrAtom()
                    ""
                }
                "begin", "end" -> {
                    parseGroupOrAtom()
                    ""
                }
                "displaystyle", "limits", "nolimits" -> ""
                "hat", "widehat", "vec", "tilde", "widetilde", "dot", "ddot",
                "bar", "overline", "underline", "emph" -> {
                    "(" + parseDecorated() + ")"
                }
                "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mbox",
                "operatorname", "textnormal", "textrm", "boldsymbol", "bm", "mathnormal" -> {
                    parseGroupOrAtom() ?: ""
                }
                else -> {
                    if (name in GREEK) return GREEK.getValue(name)
                    if (name in FUNCS) return parseFunc(name)
                    // Unknown control word: keep it as an identifier to fix up by hand.
                    name
                }
            }
        }

        /** `{…}`, `(…)` (balanced, converted) or one bare atom. */
        private fun parseGroupOrAtom(): String? {
            skipSpaces()
            if (done()) return null
            return when (s[i]) {
                '{' -> parseBraced()
                '(' -> parseParenAtomInner()
                else -> parseBareAtom()
            }
        }

        private fun parseParenAtomInner(): String? {
            var depth = 0
            var j = i
            while (j < s.length) {
                if (s[j] == '(') depth++
                else if (s[j] == ')') {
                    depth--
                    if (depth == 0) {
                        val inner = s.substring(i + 1, j)
                        i = j + 1
                        return Parser(inner).parseTop()
                    }
                }
                j++
            }
            return null
        }

        /**
         * One bare token for `\\frac12`-style arguments: braced or parenthesized
         * groups convert fully, but unbraced input is a single digit or letter.
         */
        private fun parseSingleToken(): String? {
            skipSpaces()
            if (done()) return null
            return when {
                s[i] == '{' -> parseBraced()
                s[i] == '(' -> parseParenAtomInner()
                s[i] == '\\' -> {
                    val r = parseCommand()
                    if (r.isEmpty()) null else r
                }
                s[i].isDigit() -> {
                    val c = s[i]
                    i++
                    c.toString()
                }
                s[i].isLetter() -> {
                    val c = s[i]
                    i++
                    c.toString()
                }
                else -> null
            }
        }

        private fun parseBareAtom(): String? {
            skipSpaces()
            if (done()) return null
            val c = s[i]
            if (c == '\\') return parseCommand().ifEmpty { null }
            if (c.isDigit()) {
                val start = i
                while (!done() && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(start, i)
            }
            if (c.isLetter()) {
                i++
                return c.toString()
            }
            return null
        }

        private fun parseDecorated(): String {
            skipSpaces()
            if (done()) return ""
            return when {
                s[i] == '{' -> parseBraced()
                s[i] == '\\' -> parseCommand()
                s[i].isLetterOrDigit() -> {
                    val c = s[i]
                    i++
                    c.toString()
                }
                else -> ""
            }
        }

        /** `\sum_{n=a}^{b} body` / `\prod…` → `sum(n, a, b, (body))`. */
        private fun parseBigOp(op: String): String {
            var lower: String? = null
            var upper: String? = null
            while (true) {
                skipIgnorable()
                if (done()) break
                when {
                    s[i] == '_' && lower == null -> {
                        i++
                        lower = parseScriptGroupRaw()
                    }
                    s[i] == '^' && upper == null -> {
                        i++
                        upper = parseScriptGroupRaw()
                    }
                    else -> break
                }
            }
            if (lower == null || upper == null) return op
            val varFrom = splitBound(lower) ?: return op
            val body: String = if (peekNonSpace() == '{') {
                i = skipSpacesTo(i)
                "(" + (parseGroupOrAtom() ?: "") + ")"
            } else {
                "(" + parseSequence(stopBrace = depth > 0).trim() + ")"
            }
            return "$op(${varFrom.first}, ${varFrom.second}, ${Parser(upper).parseTop()}, $body)"
        }

        private fun skipIgnorable() {
            while (true) {
                skipSpaces()
                if (s.startsWith("\\limits", i) || s.startsWith("\\nolimits", i)) {
                    i += if (s.startsWith("\\nolimits", i)) 10 else 7
                    continue
                }
                if (s.startsWith("\\displaystyle", i)) {
                    i += 13
                    continue
                }
                break
            }
        }

        private fun peekNonSpace(): Char? {
            var j = i
            while (j < s.length && s[j].isWhitespace()) j++
            return s.getOrNull(j)
        }

        private fun skipSpacesTo(at: Int): Int {
            var j = at
            while (j < s.length && s[j].isWhitespace()) j++
            i = j
            return j
        }

        /** Raw `{…}` or bare token for a script bound (no conversion yet). */
        private fun parseScriptGroupRaw(): String? {
            skipSpaces()
            if (done()) return null
            if (s[i] == '{') return readGroupRaw()
            if (s[i] == '\\') {
                val keep = i
                val r = parseCommand()
                return if (r.isEmpty()) {
                    i = keep
                    null
                } else r
            }
            // Bare token: one letter, digit run, or sign+atom.
            return when (val c = s[i]) {
                '+', '-' -> {
                    i++
                    val rest = parseScriptGroupRaw() ?: return null
                    c + rest
                }
                else -> if (c.isLetterOrDigit()) {
                    i++
                    c.toString()
                } else null
            }
        }

        /** `n=0` → var/from; anything else → null (caller falls back). */
        private fun splitBound(raw: String): Pair<String, String>? {
            val t = raw.trim()
            val eq = t.indexOf('=')
            if (eq < 0) {
                val v = t.replace(Regex("\\s+"), "")
                if (!IDENT.matches(v)) return null
                return v to "0"
            }
            val left = t.substring(0, eq)
            val right = t.substring(eq + 1)
            val v = IDENT_END.find(left)?.value ?: return null
            if (right.isBlank()) return null
            return v to Parser(right).parseTop()
        }

        /** `\int_{a}^{b} body dx` → `integral((body), x, a, b)`. */
        private fun parseIntegral(): String {
            var lower: String? = null
            var upper: String? = null
            while (true) {
                skipIgnorable()
                if (done()) break
                when {
                    s[i] == '_' && lower == null -> {
                        i++
                        lower = parseScriptGroupRaw()?.let { Parser(it).parseTop() }
                    }
                    s[i] == '^' && upper == null -> {
                        i++
                        upper = parseScriptGroupRaw()?.let { Parser(it).parseTop() }
                    }
                    else -> break
                }
            }
            val converted = parseSequence(stopBrace = depth > 0).trim()
            // A trailing differential (usually `\,dx`) names the variable; without one
            // it stays a factor of the body rather than being mistaken for it.
            val diff = Regex("(?:^|\\s)d([A-Za-z])\\s*$").find(converted)
            val variable = diff?.groupValues?.get(1) ?: "x"
            val body = diff?.let { converted.substring(0, it.range.first).trim().ifEmpty { "x" } }
                ?: converted.ifEmpty { "x" }
            return "integral(($body), $variable, ${lower ?: "0"}, ${upper ?: "1"})"
        }

        /** `\lim_{x \to a} body` → `limit((body), x, a)`. */
        private fun parseLimit(): String {
            var sub: String? = null
            while (true) {
                skipIgnorable()
                if (done()) break
                if (s[i] == '_' && sub == null) {
                    i++
                    sub = parseScriptGroupRaw()
                    continue
                }
                break
            }
            val body = "(" + parseSequence(stopBrace = depth > 0).trim() + ")"
            if (sub == null) return "limit($body, x, 0)"
            val arrow = Regex("(\\\\to|\\\\rightarrow|\\\\Rightarrow|->|→|=>)").find(sub)
            if (arrow == null) {
                val v = sub.trim().replace(Regex("\\s+"), "")
                return if (IDENT.matches(v)) "limit($body, $v, 0)" else "limit($body, x, 0)"
            }
            val left = sub.substring(0, arrow.range.first)
            val right = sub.substring(arrow.range.last + 1)
            val v = IDENT_END.find(left)?.value ?: "x"
            return "limit($body, $v, (${Parser(right).parseTop()}))"
        }

        /** `\log_{b}(x)` → `log((x), (b))`, else like any function. */
        private fun parseLog(): String {
            val save = i
            skipSpaces()
            var base: String? = null
            if (!done() && s[i] == '_') {
                i++
                base = if (!done() && s[i] == '{') {
                    val raw = readGroupRaw()
                    raw?.let { Parser(it).parseTop() }
                } else {
                    parseBareAtom()
                }
            }
            if (base == null) {
                i = save
                return parseFunc("log")
            }
            val arg = parseFuncArg() ?: ""
            return "log(($arg), ($base))"
        }

        /** `name` + optional `^pow` + one argument atom. */
        private fun parseFunc(name: String): String {
            val pow = parsePow()
            val arg = parseFuncArg()
            if (arg == null) {
                return if (pow.isEmpty()) name else "$name^$pow"
            }
            return if (pow.isEmpty()) "$name($arg)" else "($name($arg)^$pow)"
        }

        private fun parseFuncArg(): String? {
            skipSpaces()
            if (done()) return null
            return when {
                s[i] == '{' -> parseBraced()
                s[i] == '(' -> parseParenAtomInner()
                s[i] == '\\' -> {
                    val r = parseCommand()
                    if (r.isEmpty()) null else r
                }
                s[i].isLetter() -> {
                    i++
                    s[i - 1].toString()
                }
                s[i].isDigit() -> {
                    val start = i
                    while (!done() && (s[i].isDigit() || s[i] == '.')) i++
                    s.substring(start, i)
                }
                else -> null
            }
        }
    }
}
