package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.math.EvalException
import com.xnotes.math.EvalScope
import com.xnotes.math.Evaluator
import com.xnotes.math.Expr
import com.xnotes.math.ExpressionParser
import com.xnotes.math.LatexToExpr
import com.xnotes.math.Numbers
import com.xnotes.math.Numerical
import com.xnotes.math.OdeMethod
import com.xnotes.math.SamplePoint
import com.xnotes.math.Symbolic
import com.xnotes.math.toFracLatex
import com.xnotes.math.toFracText
import com.xnotes.math.toLatex
import com.xnotes.math.toText
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.max

/** One operation of the advanced calculator: numeric evaluation/solving or symbolic rewriting. */
internal enum class SolverOp(val label: String) {
    EVALUATE("="),
    SIMPLIFY("Simplify"),
    DERIVATIVE("d/dx"),
    INTEGRAL("∫ dx"),
    DEFINITE("∫ₐᵇ"),
    DERIV_AT("f′(at)"),
    LIMIT("lim"),
    TAYLOR("Taylor"),
    SOLVE("Solve"),
    ODE("ODE"),
}

/** How an operation that exists both ways runs: legacy behaviour, exact form, or quadrature. */
internal enum class SolverMethod(val label: String) {
    AUTO("Auto"),
    SYMBOLIC("Symbolic"),
    NUMERIC("Numeric"),
}

/** Everything the Solver tab keeps across tab switches. Input is LaTeX (the maths keyboard writes it). */
internal class SolverState {
    var input by mutableStateOf(TextFieldValue(""))
    var op by mutableStateOf(SolverOp.EVALUATE)
    var variable by mutableStateOf("x")
    var at by mutableStateOf("0")
    var from by mutableStateOf("0")
    var to by mutableStateOf("1")
    var order by mutableStateOf("1")
    var y0 by mutableStateOf("1")
    var odeMethod by mutableStateOf(OdeMethod.RK45.name)
    var method by mutableStateOf(SolverMethod.AUTO)
    var title by mutableStateOf("")
    var result by mutableStateOf("")
    var detail by mutableStateOf<String?>(null)
    var latex by mutableStateOf<String?>(null)
    var insertLatex by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var odePoints by mutableStateOf<List<SamplePoint>>(emptyList())
    /** Which field the maths keyboard currently types into: "input" or a small-field key. */
    var focusKey by mutableStateOf("input")

    fun clearEntry() {
        input = TextFieldValue("")
        result = ""
        detail = null
        latex = null
        insertLatex = ""
        error = null
        odePoints = emptyList()
    }

    /** Small-field text by [focusKey]; null for the main expression field. */
    private fun smallText(key: String): String? = when (key) {
        "variable" -> variable
        "at" -> at
        "from" -> from
        "to" -> to
        "order" -> order
        "y0" -> y0
        else -> null
    }

    private fun setSmallText(key: String, text: String) {
        when (key) {
            "variable" -> variable = text
            "at" -> at = text
            "from" -> from = text
            "to" -> to = text
            "order" -> order = text
            "y0" -> y0 = text
        }
    }

    /** Insert a maths-key template at the caret of the focused field, replacing any selection. */
    fun typeMath(template: String) {
        val small = smallText(focusKey)
        if (small != null) {
            // Small fields have no caret tracking: append, letting their own parsers read LaTeX.
            setSmallText(focusKey, small + template)
            error = null
            return
        }
        val v = input
        val start = minOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
        val end = maxOf(v.selection.start, v.selection.end).coerceIn(start, v.text.length)
        val (insert, cursor) = latexInsertion(v.text.substring(0, start), v.text.substring(start, end), template)
        val next = v.text.substring(0, start) + insert + v.text.substring(end)
        input = TextFieldValue(next, TextRange((start + cursor).coerceIn(0, next.length)))
        error = null
    }

    fun moveCaret(by: Int) {
        if (smallText(focusKey) != null) return // small fields keep no caret
        val v = input
        val at = (if (by < 0) minOf(v.selection.start, v.selection.end) else maxOf(v.selection.start, v.selection.end)) + by
        input = v.copy(selection = TextRange(at.coerceIn(0, v.text.length)))
    }

    fun eraseBefore() {
        val key = focusKey
        val small = smallText(key)
        if (small != null) {
            if (small.isNotEmpty()) setSmallText(key, small.dropLast(1))
            error = null
            return
        }
        val v = input
        val start = minOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)
        val end = maxOf(v.selection.start, v.selection.end).coerceIn(start, v.text.length)
        input = if (start != end) {
            TextFieldValue(v.text.removeRange(start, end), TextRange(start))
        } else if (start > 0) {
            TextFieldValue(v.text.removeRange(start - 1, start), TextRange(start - 1))
        } else v
        error = null
    }
}

/** Parse the solver input (LaTeX or plain text) into an engine expression. */
internal fun SolverState.parseExpr(): Expr {
    val raw = input.text.trim()
    if (raw.isEmpty()) throw EvalException("Enter an expression")
    var engine = LatexToExpr.convertOrPass(raw)
    if (engine.isBlank()) throw EvalException("Could not read an expression")
    engine = stripTrailingDifferential(engine, varName())
    if (engine.isBlank()) throw EvalException("Could not read an expression")
    return ExpressionParser.parse(engine)
}

/**
 * A trailing `d<variable>` (the integral key writes one, canvas formulas carry one) is residue,
 * not a factor: `x^2 dx` must integrate x², not x²·d·x. Only the variable's own differential
 * is stripped — as a balanced `(dx)` unit or bare after whitespace/a comma — and only when the
 * remainder still parses, so `x^dx`, `2*dx`, `(x + dx)` and calls like `sin(dx)` keep working.
 */
internal fun stripTrailingDifferential(engine: String, variable: String): String {
    if (variable.isEmpty()) return engine
    val esc = Regex.escape(variable)
    val pattern = Regex("(?<![A-Za-z])(?:\\(d$esc\\)|(?:^|[\\s,])d$esc)\\s*$")
    var out = engine
    while (true) {
        val m = pattern.find(out) ?: break
        val cut = out.substring(0, m.range.first).trimEnd()
        if (cut.isEmpty() || ExpressionParser.parseOrNull(cut) == null) break
        out = cut
    }
    return out.ifEmpty { engine }
}

private fun SolverState.varName(): String = variable.trim().ifEmpty { "x" }

/** A plain number; complex constants and `pi` are accepted, NaN is rejected. */
private fun SolverState.finiteNumber(text: String, what: String): Double {
    val v = try {
        Evaluator.evaluate(ExpressionParser.parse(LatexToExpr.convertOrPass(text.ifBlank { "0" }))).realValue()
    } catch (e: Exception) {
        throw EvalException("Bad $what: $text")
    }
    if (!v.isFinite()) throw EvalException("Bad $what: $text")
    return v
}

/** A bound or limit point; ±infinity is allowed, NaN is not. */
private fun SolverState.boundNumber(text: String, what: String, default: Double): Double {
    if (text.isBlank()) return default
    val v = try {
        Evaluator.evaluate(ExpressionParser.parse(LatexToExpr.convertOrPass(text))).realValue()
    } catch (e: Exception) {
        throw EvalException("Bad $what: $text")
    }
    if (v.isNaN()) throw EvalException("Bad $what: $text")
    return v
}

private fun SolverState.ok(title: String, result: String, insertRhs: String, detail: String? = null, latex: String? = null) {
    this.title = title
    this.result = result
    this.detail = detail
    this.latex = latex
    this.insertLatex = input.text.trim() + " = " + insertRhs
    this.error = null
}

/** Runs the selected operation; numeric, symbolic and ODE paths all funnel through here. */
internal fun runSolver(state: SolverState) {
    state.error = null
    state.odePoints = emptyList()
    try {
        val expr = state.parseExpr()
        val v = state.varName()
        val f: (Double) -> Double = { t -> Evaluator.real(expr, v, t) }
        val symbolicOnly = state.method == SolverMethod.SYMBOLIC
        val numericOnly = state.method == SolverMethod.NUMERIC
        when (state.op) {
            SolverOp.EVALUATE -> {
                val value = Evaluator.evaluate(expr, EvalScope())
                val shown = Numbers.formatComplex(value)
                state.ok("Result", shown, shown, LatexToExpr.convertOrPass(state.input.text.trim()))
            }
            SolverOp.SIMPLIFY -> {
                val r = Symbolic.simplify(expr)
                state.ok("Simplify", r.toFracText(), r.toFracLatex(), "simplified form", r.toFracLatex())
            }
            SolverOp.DERIVATIVE -> {
                val o = state.order.toIntOrNull()?.coerceIn(1, 4) ?: 1
                var r = expr
                repeat(o) { r = Symbolic.derivative(r, v) }
                state.ok(if (o == 1) "d/dx" else "d^$o/dx^$o", r.toFracText(), r.toFracLatex(), "$o-th derivative with respect to $v", r.toFracLatex())
            }
            SolverOp.INTEGRAL -> {
                val r = Symbolic.integrate(expr, v)
                if (r.toText().contains("Integral(") || r.toText().contains("D(")) {
                    throw IllegalArgumentException("No elementary antiderivative — try ∫ₐᵇ numeric")
                }
                state.ok("∫ dx", r.toFracText(), r.toFracLatex(), "indefinite integral in $v", r.toFracLatex())
            }
            SolverOp.DEFINITE -> {
                val a = state.boundNumber(state.from, "lower bound", 0.0)
                val b = state.boundNumber(state.to, "upper bound", 1.0)
                if (symbolicOnly) {
                    if (!a.isFinite() || !b.isFinite()) throw EvalException("Infinite bounds need numeric quadrature")
                    val antideriv = Symbolic.integrate(expr, v)
                    if (antideriv.toText().contains("Integral(") || antideriv.toText().contains("D(")) {
                        throw EvalException("No elementary antiderivative — use Numeric")
                    }
                    val fa = Evaluator.real(antideriv, mapOf(v to a))
                    val fb = Evaluator.real(antideriv, mapOf(v to b))
                    if (!fa.isFinite() || !fb.isFinite()) throw EvalException("Antiderivative is singular there — use Numeric")
                    val value = fb - fa
                    state.ok(
                        "∫ d$v",
                        Numbers.formatFraction(value),
                        Numbers.formatFraction(value),
                        "exact: F(${Numbers.formatFraction(b)}) − F(${Numbers.formatFraction(a)})",
                    )
                } else {
                    val q = if (a.isFinite() && b.isFinite()) Numerical.adaptiveSimpson(f, a, b)
                    else Numerical.improperQuad(f, a, b)
                    if (!q.value.isFinite()) throw EvalException("Integral does not converge")
                    state.ok(
                        "∫ d$v",
                        Numbers.format(q.value),
                        Numbers.format(q.value),
                        "error ≈ ${Numbers.format(q.error)}  ·  ${q.evaluations} evaluations",
                    )
                }
            }
            SolverOp.DERIV_AT -> {
                val at = state.finiteNumber(state.at, "point")
                val o = state.order.toIntOrNull()?.coerceIn(0, 6) ?: 1
                val value = Numerical.derivative(f, at, o)
                if (!value.isFinite()) throw EvalException("Derivative does not exist there")
                state.ok("f′($at)", Numbers.format(value), Numbers.format(value), "central difference, order $o at $v = $at")
            }
            SolverOp.LIMIT -> {
                val target = state.boundNumber(state.at, "point", 0.0)
                val sym: Double? = runCatching { Symbolic.limit(expr, v, target) }.getOrNull()?.takeIf { it.isFinite() }
                if (symbolicOnly && sym == null) throw EvalException("No symbolic limit — use Numeric")
                val value = sym ?: run {
                    val atExpr = ExpressionParser.parse(LatexToExpr.convertOrPass(state.at.ifBlank { "0" }))
                    val call = Expr.Call("limit", listOf(expr, Expr.Var(v), atExpr))
                    Evaluator.evaluate(call, EvalScope()).re
                }
                if (!value.isFinite()) throw EvalException("Limit does not settle")
                state.ok("lim $v→${state.at.ifBlank { "0" }}", Numbers.format(value), Numbers.format(value), if (sym != null) "symbolic limit" else "two-sided numerical limit")
            }
            SolverOp.TAYLOR -> {
                val at = state.finiteNumber(state.at, "point")
                val o = state.order.toIntOrNull()?.coerceIn(1, 24) ?: 6
                val r = Symbolic.taylor(expr, v, at, o)
                state.ok("Taylor", r.toFracText(), r.toFracLatex(), "order $o about $v = ${Numbers.formatFraction(at)}", r.toFracLatex())
            }
            SolverOp.SOLVE -> {
                val roots = if (!numericOnly) runCatching { Symbolic.solve(expr, v) }.getOrNull() else null
                if (!roots.isNullOrEmpty()) {
                    val shown = Symbolic.formatRoots(roots, fractions = true)
                    state.ok("Solve $v", shown, shown, "${roots.size} root(s) in the complex plane")
                } else {
                    if (symbolicOnly) throw EvalException("Not a polynomial in $v — use Numeric")
                    // Not a polynomial: fall back to numeric root finding around `at`, then [from, to].
                    val guess = runCatching { state.finiteNumber(state.at, "guess") }.getOrElse { 0.0 }
                    var r = Numerical.newton(f, guess)
                    var how = "Newton from $guess"
                    if (!r.converged) {
                        val lo = runCatching { state.finiteNumber(state.from, "left") }.getOrNull()
                        val hi = runCatching { state.finiteNumber(state.to, "right") }.getOrNull()
                        if (lo != null && hi != null && lo != hi) {
                            r = Numerical.bisection(f, lo, hi)
                            how = "bisection on [$lo, $hi]"
                        }
                    }
                    if (!r.converged) throw EvalException("No convergence (residual ${Numbers.format(r.residual)})")
                    val shown = Numbers.format(r.root)
                    state.ok("Solve $v", shown, shown, "$how  ·  ${r.iterations} iterations")
                }
            }
            SolverOp.ODE -> {
                val x0 = state.finiteNumber(state.from, "x₀")
                val y0 = state.finiteNumber(state.y0, "y₀")
                val x1 = state.finiteNumber(state.to, "x₁")
                if (x0 == x1) throw EvalException("x₀ and x₁ must differ")
                val method = OdeMethod.entries.firstOrNull { it.name == state.odeMethod } ?: OdeMethod.RK45
                val fxy: (Double, Double) -> Double = { x, y -> Evaluator.real(expr, mapOf("x" to x, "y" to y)) }
                val points = Numerical.solveOde(fxy, x0, y0, x1, steps = 400, method = method)
                if (points.isEmpty() || !points.last().y.isFinite()) throw EvalException("ODE integration failed")
                val last = points.last()
                state.ok(
                    "y(${Numbers.format(last.x)})",
                    Numbers.format(last.y),
                    Numbers.format(last.y),
                    "${points.size} points  ·  ${method.label}",
                )
                state.odePoints = points
            }
        }
    } catch (e: Exception) {
        state.error = e.message ?: e.javaClass.simpleName
        state.result = ""
        state.detail = null
        state.latex = null
        state.insertLatex = ""
        state.odePoints = emptyList()
    }
}

@Composable
internal fun SolverPane(state: SolverState, onInsertResult: (String) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            MathCard(Modifier.fillMaxWidth()) {
                MathSectionTitle(stringResource(R.string.math_expression))
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = state.input,
                    onValueChange = { state.input = it; state.error = null },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) state.focusKey = "input" },
                    textStyle = TextStyle(
                        color = palette.text.toComposeColor(),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { runSolver(state) }),
                    colors = mathFieldColors(),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(7.dp))
                MathToolSelector(
                    SolverOp.entries.map { it.name to it.label },
                    selected = state.op.name,
                    modifier = Modifier.fillMaxWidth(),
                ) { state.op = SolverOp.valueOf(it) }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MathTextField(state.variable, { state.variable = it }, stringResource(R.string.math_variable), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "variable" })
                    when (state.op) {
                        SolverOp.EVALUATE, SolverOp.SIMPLIFY, SolverOp.INTEGRAL -> Unit
                        SolverOp.DERIVATIVE -> {
                            MathTextField(state.order, { state.order = it.filter(Char::isDigit) }, stringResource(R.string.math_order), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "order" })
                        }
                        SolverOp.TAYLOR -> {
                            MathTextField(state.at, { state.at = it }, stringResource(R.string.math_at), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "at" })
                            MathTextField(state.order, { state.order = it.filter(Char::isDigit) }, stringResource(R.string.math_order), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "order" })
                        }
                        SolverOp.DEFINITE -> {
                            MathTextField(state.from, { state.from = it }, stringResource(R.string.math_from), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "from" })
                            MathTextField(state.to, { state.to = it }, stringResource(R.string.math_to), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "to" })
                        }
                        SolverOp.DERIV_AT -> {
                            MathTextField(state.at, { state.at = it }, stringResource(R.string.math_at), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "at" })
                            MathTextField(state.order, { state.order = it.filter(Char::isDigit) }, stringResource(R.string.math_order), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "order" })
                        }
                        SolverOp.LIMIT -> {
                            MathTextField(state.at, { state.at = it }, stringResource(R.string.math_at), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "at" })
                        }
                        SolverOp.SOLVE -> {
                            MathTextField(state.at, { state.at = it }, stringResource(R.string.math_guess), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "at" })
                        }
                        SolverOp.ODE -> {
                            MathTextField(state.from, { state.from = it }, "x₀", Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "from" })
                            MathTextField(state.y0, { state.y0 = it }, "y₀", Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "y0" })
                            MathTextField(state.to, { state.to = it }, "x₁", Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "to" })
                        }
                    }
                }
                if (state.op == SolverOp.SOLVE) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MathTextField(state.from, { state.from = it }, stringResource(R.string.math_bracket_from), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "from" })
                        MathTextField(state.to, { state.to = it }, stringResource(R.string.math_bracket_to), Modifier.weight(1f).onFocusChanged { if (it.isFocused) state.focusKey = "to" })
                    }
                }
                if (state.op == SolverOp.DEFINITE || state.op == SolverOp.SOLVE || state.op == SolverOp.LIMIT) {
                    Spacer(Modifier.height(5.dp))
                    MathToolSelector(
                        SolverMethod.entries.map { it.name to it.label },
                        selected = state.method.name,
                        modifier = Modifier.fillMaxWidth(),
                    ) { state.method = SolverMethod.valueOf(it) }
                }
                if (state.op == SolverOp.ODE) {
                    Spacer(Modifier.height(5.dp))
                    MathToolSelector(
                        OdeMethod.entries.map { it.name to it.name },
                        selected = state.odeMethod,
                        modifier = Modifier.fillMaxWidth(),
                    ) { state.odeMethod = it }
                }
                Spacer(Modifier.height(7.dp))
                MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) { runSolver(state) }
            }
            state.error?.let {
                Spacer(Modifier.height(7.dp))
                MathResultCard(stringResource(R.string.math_error), it, isError = true)
            }
            if (state.result.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                MathCard(Modifier.fillMaxWidth()) {
                    MathSectionTitle(state.title.ifEmpty { stringResource(R.string.math_symbolic_result) })
                    Spacer(Modifier.height(5.dp))
                    MathResultCard(
                        stringResource(R.string.math_symbolic_result),
                        state.result,
                        detail = state.detail,
                    )
                    state.latex?.let { latex ->
                        Spacer(Modifier.height(5.dp))
                        Text(
                            latex,
                            color = LocalPalette.current.textDim.toComposeColor(),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(LocalPalette.current.surface.toComposeColor())
                                .padding(7.dp),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    MathSecondaryButton(
                        stringResource(R.string.math_insert_result),
                        Modifier.fillMaxWidth(),
                        enabled = state.insertLatex.isNotBlank(),
                    ) { onInsertResult(state.insertLatex) }
                }
            }
            if (state.odePoints.size > 1) {
                Spacer(Modifier.height(7.dp))
                MathCard(Modifier.fillMaxWidth()) {
                    MathSectionTitle(stringResource(R.string.math_tool_graph))
                    Spacer(Modifier.height(5.dp))
                    MiniPlot(state.odePoints, Modifier.fillMaxWidth().height(170.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.math_solver_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        MathKeys(
            MathKeyActions(
                onKey = { state.typeMath(it) },
                onClear = { state.clearEntry() },
                onMove = { state.moveCaret(it) },
                onDelete = { state.eraseBefore() },
                onEnter = { runSolver(state) },
            ),
            Modifier.fillMaxWidth(),
        )
    }
}
