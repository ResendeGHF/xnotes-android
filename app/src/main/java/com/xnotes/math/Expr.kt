package com.xnotes.math

import kotlin.math.abs
import kotlin.math.floor

/** Binary operators the parser understands. */
enum class BinOp(val symbol: String, val precedence: Int) {
    OR("||", 1),
    AND("&&", 2),
    EQ("=", 3),
    NE("!=", 3),
    LT("<", 3),
    LE("<=", 3),
    GT(">", 3),
    GE(">=", 3),
    ADD("+", 4),
    SUB("-", 4),
    MUL("*", 5),
    DIV("/", 5),
    MOD("%", 5),
    POW("^", 6),
}

/** A parsed expression tree. The same tree feeds the numeric evaluator and the symbolic engine. */
sealed interface Expr {
    data class Num(val value: Double) : Expr
    data class Var(val name: String) : Expr
    data class Neg(val value: Expr) : Expr
    data class Bin(val op: BinOp, val left: Expr, val right: Expr) : Expr
    data class Call(val name: String, val args: List<Expr>) : Expr
    data class Factorial(val value: Expr) : Expr
    data class Sum(val variable: String, val from: Expr, val to: Expr, val body: Expr) : Expr
    data class Product(val variable: String, val from: Expr, val to: Expr, val body: Expr) : Expr
}

class MathSyntaxException(message: String) : Exception(message)

/**
 * Recursive-descent parser for calculator/plot syntax. It accepts ordinary infix notation with
 * implicit multiplication (`2x`, `3(x+1)`), postfix factorial, right-associative powers and the
 * binding forms `sum(n, a, b, body)` / `prod(n, a, b, body)`.
 */
object ExpressionParser {

    fun parse(input: String): Expr {
        val text = normalize(input)
        if (text.isBlank()) throw MathSyntaxException("Enter an expression")
        val parser = Parser(Tokenizer(text).tokens())
        val expr = parser.parseAll()
        return expr
    }

    fun parseOrNull(input: String): Expr? = runCatching { parse(input) }.getOrNull()

    /** Replaces display glyphs with ASCII the tokenizer knows, so pasted maths just works. */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val next = raw.getOrNull(i + 1)
            when {
                c == 'π' || c == 'Π' && next == null -> sb.append("pi")
                c == '∞' -> sb.append("inf")
                c == '√' -> sb.append("sqrt")
                c == '×' || c == '·' || c == '⋅' -> sb.append('*')
                c == '÷' -> sb.append('/')
                c == '−' || c == '–' || c == '—' -> sb.append('-')
                c == '≤' -> sb.append("<=")
                c == '≥' -> sb.append(">=")
                c == '≠' -> sb.append("!=")
                c == '∑' -> sb.append("sum")
                c == '∏' -> sb.append("prod")
                c == '^' && next == '^' -> { sb.append('^'); i++ }
                c == '²' -> sb.append("^2")
                c == '³' -> sb.append("^3")
                c == '⁰' -> sb.append("^0")
                c == '¹' -> sb.append("^1")
                c == '⁴' -> sb.append("^4")
                c == '⁵' -> sb.append("^5")
                c == '⁶' -> sb.append("^6")
                c == '⁷' -> sb.append("^7")
                c == '⁸' -> sb.append("^8")
                c == '⁹' -> sb.append("^9")
                c == '⁻' -> sb.append("^-")
                c == '{' -> sb.append('(')
                c == '}' -> sb.append(')')
                c == '[' -> sb.append('(')
                c == ']' -> sb.append(')')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private enum class T { NUM, IDENT, OP, LPAREN, RPAREN, COMMA, END }

    private data class Token(val type: T, val text: String, val at: Int)

    private class Tokenizer(private val s: String) {
        private var i = 0
        private val out = ArrayList<Token>()

        fun tokens(): List<Token> {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && s.getOrNull(i + 1)?.isDigit() == true) -> number()
                    c.isLetter() || c == '_' || c == '\\' -> ident()
                    c == '(' -> { out += Token(T.LPAREN, "(", i); i++ }
                    c == ')' -> { out += Token(T.RPAREN, ")", i); i++ }
                    c == ',' || c == ';' -> { out += Token(T.COMMA, ",", i); i++ }
                    c == '<' && s.getOrNull(i + 1) == '=' -> { out += Token(T.OP, "<=", i); i += 2 }
                    c == '>' && s.getOrNull(i + 1) == '=' -> { out += Token(T.OP, ">=", i); i += 2 }
                    c == '=' && s.getOrNull(i + 1) == '=' -> { out += Token(T.OP, "=", i); i += 2 }
                    c == '!' && s.getOrNull(i + 1) == '=' -> { out += Token(T.OP, "!=", i); i += 2 }
                    c == '&' && s.getOrNull(i + 1) == '&' -> { out += Token(T.OP, "&&", i); i += 2 }
                    c == '|' && s.getOrNull(i + 1) == '|' -> { out += Token(T.OP, "||", i); i += 2 }
                    c == '!' -> { out += Token(T.OP, "!", i); i++ }
                    c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == '%' ||
                        c == '<' || c == '>' || c == '=' -> { out += Token(T.OP, c.toString(), i); i++ }
                    else -> throw MathSyntaxException("Unexpected character '$c' at ${i + 1}")
                }
            }
            out += Token(T.END, "", s.length)
            return out
        }

        private fun number() {
            val start = i
            var seenDot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) { i++; continue }
                if (c == '.' && !seenDot) { seenDot = true; i++; continue }
                break
            }
            // Scientific notation only when the e/E is followed by a number (so 2e is 2*e).
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j].isDigit()) {
                    i = j + 1
                    while (i < s.length && s[i].isDigit()) i++
                }
            }
            out += Token(T.NUM, s.substring(start, i).replace("_", ""), start)
        }

        private fun ident() {
            val start = i
            if (s[i] == '\\') i++
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            if (i == start) throw MathSyntaxException("Unexpected character at ${start + 1}")
            out += Token(T.IDENT, s.substring(start, i), start)
        }
    }

    private class Parser(private val toks: List<Token>) {
        private var i = 0

        fun parseAll(): Expr {
            val e = or()
            if (peek().type != T.END) throw MathSyntaxException("Unexpected '${peek().text}'")
            return e
        }

        private fun peek(k: Int = 0): Token = toks[minOf(i + k, toks.lastIndex)]

        private fun take(): Token = toks[i++]

        private fun match(type: T, text: String? = null): Boolean {
            val t = peek()
            if (t.type == type && (text == null || t.text == text)) { i++; return true }
            return false
        }

        private fun expect(type: T, text: String? = null): Token {
            val t = peek()
            if (t.type != type || (text != null && t.text != text)) {
                throw MathSyntaxException("Expected ${text ?: type.name.lowercase()} at ${t.at + 1}")
            }
            i++
            return t
        }

        private fun isOp(vararg symbols: String): Boolean =
            peek().type == T.OP && peek().text in symbols

        private fun or(): Expr {
            var e = and()
            while (isOp("||") || (peek().type == T.IDENT && peek().text.equals("or", true))) {
                i++
                e = Expr.Bin(BinOp.OR, e, and())
            }
            return e
        }

        private fun and(): Expr {
            var e = comparison()
            while (isOp("&&") || (peek().type == T.IDENT && peek().text.equals("and", true))) {
                i++
                e = Expr.Bin(BinOp.AND, e, comparison())
            }
            return e
        }

        private fun comparison(): Expr {
            var e = add()
            while (isOp("<", "<=", ">", ">=", "=", "!=")) {
                val op = when (take().text) {
                    "<" -> BinOp.LT
                    "<=" -> BinOp.LE
                    ">" -> BinOp.GT
                    ">=" -> BinOp.GE
                    "!=" -> BinOp.NE
                    else -> BinOp.EQ
                }
                e = Expr.Bin(op, e, add())
            }
            return e
        }

        private fun add(): Expr {
            var e = mul()
            while (isOp("+", "-")) {
                val op = if (take().text == "+") BinOp.ADD else BinOp.SUB
                e = Expr.Bin(op, e, mul())
            }
            return e
        }

        private fun mul(): Expr {
            var e = unary()
            while (true) {
                when {
                    isOp("*", "/", "%") -> {
                        val op = when (take().text) {
                            "/" -> BinOp.DIV
                            "%" -> BinOp.MOD
                            else -> BinOp.MUL
                        }
                        e = Expr.Bin(op, e, unary())
                    }
                    startsFactor(peek()) -> e = Expr.Bin(BinOp.MUL, e, unary())
                    else -> return e
                }
            }
        }

        private fun unary(): Expr {
            if (isOp("+")) { i++; return unary() }
            if (isOp("-")) { i++; return Expr.Neg(unary()) }
            return power()
        }

        private fun power(): Expr {
            val base = postfix()
            if (isOp("^")) {
                i++
                return Expr.Bin(BinOp.POW, base, unary())
            }
            return base
        }

        private fun postfix(): Expr {
            var e = primary()
            while (isOp("!")) { i++; e = Expr.Factorial(e) }
            return e
        }

        private fun primary(): Expr {
            val t = peek()
            when (t.type) {
                T.NUM -> { i++; return Expr.Num(t.text.toDoubleOrNull() ?: throw MathSyntaxException("Bad number '${t.text}'")) }
                T.LPAREN -> {
                    i++
                    val e = or()
                    expect(T.RPAREN, ")")
                    return e
                }
                T.IDENT -> {
                    i++
                    val raw = t.text
                    val name = raw.removePrefix("\\")
                    if (match(T.LPAREN)) {
                        val args = ArrayList<Expr>()
                        if (!match(T.RPAREN)) {
                            do { args += or() } while (match(T.COMMA))
                            expect(T.RPAREN, ")")
                        }
                        var base = bind(name, args)
                        base = finishChainedSumProd(name, base)
                        return base
                    }
                    // A few functions read naturally without brackets: sin x, sqrt 2, ln x.
                    if (name.lowercase() in MathCatalog.unaryFunctionNames && startsFactor(peek()) && !isOp("^")) {
                        return bind(name, listOf(unary()))
                    }
                    return Expr.Var(name)
                }
                else -> throw MathSyntaxException("Expected a number, name or '(' at ${t.at + 1}")
            }
        }

        /** Converts sum/prod calls whose first argument names the binding variable into binding nodes. */
        private fun bind(name: String, args: List<Expr>): Expr {
            val key = name.lowercase()
            if ((key == "sum" || key == "prod" || key == "product") && args.size == 4) {
                val v = args[0]
                if (v is Expr.Var) {
                    return if (key == "sum") Expr.Sum(v.name, args[1], args[2], args[3])
                    else Expr.Product(v.name, args[1], args[2], args[3])
                }
            }
            // A 3-arg sum/prod is a partial binder awaiting trailing (binder)*(body) groups,
            // e.g. sum(n,0,10)(x^n/n!) or sum(n,0,10)(m,0,10)(x^n/m!).
            if ((key == "sum" || key == "prod" || key == "product") && args.size == 3) {
                val v = args[0]
                if (v is Expr.Var) return Expr.Call(key, args)
            }
            return Expr.Call(key, args)
        }

        /**
         * Finishes chained summatory/productory forms:
         * `sum(n,0,10)(body)`, `prod(n,0,10)(body)`,
         * `sum(n,0,10)(m,0,10)(body)` (bare binder groups inherit the head operation),
         * and mixed nesting via an explicit body such as `sum(n,0,10)(prod(m,0,10)(body))`.
         * The legacy 4-arg form passes through untouched.
         */
        private fun finishChainedSumProd(head: String, base: Expr): Expr {
            val headKey = head.lowercase()
            if (headKey != "sum" && headKey != "prod" && headKey != "product") return base
            if (base !is Expr.Call) return base
            if (base.args.size != 3) return base
            val headVar = base.args[0] as? Expr.Var ?: return base
            val headOp = if (headKey == "sum") "sum" else "prod"
            val binders = ArrayList<Triple<String, Expr, Expr>>()
            binders += Triple(headVar.name, base.args[1], base.args[2])
            while (peek().type == T.LPAREN) {
                val saved = i
                i++ // consume '('
                // Try a bare binder group: Var ',' expr ',' expr ')'.
                val binder = tryParseBareBinder()
                if (binder != null) {
                    binders += binder
                    continue
                }
                // Otherwise a final body group: '(' expr ')'.
                i = saved
                i++ // consume '(' again
                val body: Expr = try {
                    val e = or()
                    expect(T.RPAREN, ")")
                    e
                } catch (_: Exception) {
                    i = saved
                    break
                }
                var wrapped: Expr = body
                for (k in binders.indices.reversed()) {
                    val (v, from, to) = binders[k]
                    wrapped = if ((if (k == 0) headOp else headOp) == "sum") Expr.Sum(v, from, to, wrapped)
                    else Expr.Product(v, from, to, wrapped)
                }
                // Allow further application like sum(...)(...)(body)(extra)? No: body completes it.
                // But the body itself may already contain nested chains, which were parsed recursively.
                return wrapped
            }
            // No trailing body: keep legacy behaviour (report as unknown function at eval time
            // rather than silently dropping). Restore a Call so evaluation fails clearly.
            return base
        }

        private fun tryParseBareBinder(): Triple<String, Expr, Expr>? {
            val saved = i
            try {
                val first = or()
                if (first !is Expr.Var) { i = saved; return null }
                if (!match(T.COMMA)) { i = saved; return null }
                val from = or()
                if (!match(T.COMMA)) { i = saved; return null }
                val to = or()
                if (!match(T.RPAREN)) { i = saved; return null }
                return Triple(first.name, from, to)
            } catch (_: Exception) {
                i = saved
                return null
            }
        }

        private fun startsFactor(t: Token): Boolean =
            t.type == T.NUM || t.type == T.IDENT || t.type == T.LPAREN
    }
}

/** A small precedence-aware printer. The result can be parsed back unchanged. */
fun Expr.toText(): String = renderText(Numbers::format)

/** [toText] with close decimals rendered as fractions (`1/3` instead of `0.333…`). */
fun Expr.toFracText(): String = renderText(Numbers::formatFraction)

private fun Expr.renderText(fmt: (Double) -> String): String = when (this) {
    is Expr.Num -> fmt(value)
    is Expr.Var -> name
    is Expr.Neg -> "-${value.wrapP(5, fmt)}"
    is Expr.Bin -> {
        val p = op.precedence
        val left = left.wrapP(if (op == BinOp.POW) p + 1 else p, fmt)
        val right = right.wrapP(if (op == BinOp.POW) p else p + 1, fmt)
        "$left ${op.symbol} $right"
    }
    is Expr.Call -> "$name(${args.joinToString(", ") { it.renderText(fmt) }})"
    is Expr.Factorial -> "${value.wrapP(7, fmt)}!"
    is Expr.Sum -> "sum($variable, ${from.renderText(fmt)}, ${to.renderText(fmt)}, ${body.renderText(fmt)})"
    is Expr.Product -> "prod($variable, ${from.renderText(fmt)}, ${to.renderText(fmt)}, ${body.renderText(fmt)})"
}

private fun Expr.wrapP(parentPrecedence: Int, fmt: (Double) -> String): String {
    val text = renderText(fmt)
    val mine = when (this) {
        is Expr.Bin -> op.precedence
        is Expr.Neg -> 5
        is Expr.Factorial -> 7
        else -> 100
    }
    return if (mine < parentPrecedence) "($text)" else text
}

private fun Expr.wrap(parentPrecedence: Int): String = wrapP(parentPrecedence, Numbers::format)

/** LaTeX for a parsed expression, used by the symbolic result card. */
fun Expr.toLatex(): String = renderLatex(Numbers::format)

/** [toLatex] with close decimals rendered as `\frac{p}{q}`. */
fun Expr.toFracLatex(): String = renderLatex(Numbers::formatFractionLatex)

private fun Expr.renderLatex(fmt: (Double) -> String): String = when (this) {
    is Expr.Num -> fmt(value)
    is Expr.Var -> when (name.lowercase()) {
        "pi", "π" -> "\\pi"
        "inf", "infinity", "oo" -> "\\infty"
        else -> name
    }
    is Expr.Neg -> "-${value.renderLatex(fmt)}"
    is Expr.Bin -> when (op) {
        BinOp.DIV -> "\\frac{${left.renderLatex(fmt)}}{${right.renderLatex(fmt)}}"
        BinOp.POW -> "{${left.renderLatex(fmt)}}^{${right.renderLatex(fmt)}}"
        BinOp.MUL -> "${left.renderLatex(fmt)} \\cdot ${right.renderLatex(fmt)}"
        BinOp.ADD -> "${left.renderLatex(fmt)} + ${right.renderLatex(fmt)}"
        BinOp.SUB -> "${left.renderLatex(fmt)} - ${right.renderLatex(fmt)}"
        else -> "${left.renderLatex(fmt)} ${op.symbol} ${right.renderLatex(fmt)}"
    }
    is Expr.Call -> when (name.lowercase()) {
        "sqrt" -> "\\sqrt{${args.firstOrNull()?.renderLatex(fmt).orEmpty()}}"
        "abs" -> "\\left|${args.firstOrNull()?.renderLatex(fmt).orEmpty()}\\right|"
        "exp" -> "e^{${args.firstOrNull()?.renderLatex(fmt).orEmpty()}}"
        "sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "ln", "log" ->
            "\\${name}\\left(${args.joinToString(", ") { it.renderLatex(fmt) }}\\right)"
        else -> "\\operatorname{${name}}\\left(${args.joinToString(", ") { it.renderLatex(fmt) }}\\right)"
    }
    is Expr.Factorial -> "${value.renderLatex(fmt)}!"
    is Expr.Sum -> "\\sum_{$variable=${from.renderLatex(fmt)}}^{${to.renderLatex(fmt)}} ${body.renderLatex(fmt)}"
    is Expr.Product -> "\\prod_{$variable=${from.renderLatex(fmt)}}^{${to.renderLatex(fmt)}} ${body.renderLatex(fmt)}"
}

/** Variables that occur free in an expression (binding variables inside their own sums excluded). */
fun Expr.variables(bound: Set<String> = emptySet()): Set<String> = when (this) {
    is Expr.Num -> emptySet()
    is Expr.Var -> if (name in bound || name.lowercase() in MathCatalog.constantNames) emptySet() else setOf(name)
    is Expr.Neg -> value.variables(bound)
    is Expr.Bin -> left.variables(bound) + right.variables(bound)
    is Expr.Call -> args.flatMap { it.variables(bound) }.toSet()
    is Expr.Factorial -> value.variables(bound)
    is Expr.Sum -> from.variables(bound) + to.variables(bound) + body.variables(bound + variable)
    is Expr.Product -> from.variables(bound) + to.variables(bound) + body.variables(bound + variable)
}

/** Structural equality with numeric tolerance is intentionally not defined: trees are compared exactly. */
internal fun isInteger(v: Double): Boolean = v.isFinite() && abs(v - floor(v)) < 1e-10
