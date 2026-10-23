package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.math.Evaluator
import com.xnotes.math.Expr
import com.xnotes.math.ExpressionParser
import com.xnotes.math.Numerical
import com.xnotes.math.Numbers
import com.xnotes.math.OdeMethod
import com.xnotes.math.toLatex
import com.xnotes.math.toText
import com.xnotes.math.SamplePoint
import com.xnotes.math.Symbolic
import com.xnotes.math.Units
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---------------------------------------------------------------------------------- calculus

internal enum class CalculusTool(val labelRes: Int) {
    DERIVATIVE(R.string.math_tool_derivative),
    INTEGRAL(R.string.math_tool_integral),
    ODE(R.string.math_tool_ode),
    ROOT(R.string.math_tool_root),
    LIMIT(R.string.math_tool_limit),
    SERIES(R.string.math_tool_series),
}

internal class CalculusState {
    var tool by mutableStateOf(CalculusTool.DERIVATIVE)
    var expression by mutableStateOf("x^3 - 3x + 1")
    var variable by mutableStateOf("x")
    var at by mutableStateOf("1")
    var order by mutableStateOf("1")
    var a by mutableStateOf("0")
    var b by mutableStateOf("1")
    var method by mutableStateOf("adaptive")
    var odeExpression by mutableStateOf("-0.8y + sin(x)")
    var x0 by mutableStateOf("0")
    var y0 by mutableStateOf("1")
    var x1 by mutableStateOf("8")
    var steps by mutableStateOf("400")
    var odeMethod by mutableStateOf("RK45")
    var rootA by mutableStateOf("-2")
    var rootB by mutableStateOf("2")
    var guess by mutableStateOf("1")
    var rootMethod by mutableStateOf("bisection")
    var seriesExpression by mutableStateOf("1/n^2")
    var seriesFrom by mutableStateOf("1")
    var seriesTo by mutableStateOf("60")

    var title by mutableStateOf("")
    var result by mutableStateOf("")
    var detail by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var points by mutableStateOf<List<SamplePoint>>(emptyList())
}

@Composable
internal fun CalculusPane(state: CalculusState) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        MathToolSelector(
            CalculusTool.entries.map { it.name to stringResource(it.labelRes) },
            selected = state.tool.name,
            modifier = Modifier.fillMaxWidth(),
        ) { state.tool = CalculusTool.valueOf(it) }
        Spacer(Modifier.height(7.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (state.tool) {
                CalculusTool.DERIVATIVE -> DerivativeCard(state)
                CalculusTool.INTEGRAL -> IntegralCard(state)
                CalculusTool.ODE -> OdeCard(state)
                CalculusTool.ROOT -> RootCard(state)
                CalculusTool.LIMIT -> LimitCard(state)
                CalculusTool.SERIES -> SeriesCard(state)
            }
            if (state.points.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                MathCard(Modifier.fillMaxWidth()) {
                    MathSectionTitle(stringResource(R.string.math_tool_graph))
                    Spacer(Modifier.height(5.dp))
                    MiniPlot(state.points, Modifier.fillMaxWidth().height(170.dp))
                }
            }
        }
    }
}

@Composable
private fun DerivativeCard(state: CalculusState) {
    val palette = LocalPalette.current
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_tool_derivative))
        Spacer(Modifier.height(5.dp))
        MathTextField(state.expression, { state.expression = it }, "f(x)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.variable, { state.variable = it }, stringResource(R.string.math_variable), Modifier.weight(1f))
            MathTextField(state.at, { state.at = it }, stringResource(R.string.math_at), Modifier.weight(1f))
            MathTextField(state.order, { state.order = it.filter(Char::isDigit) }, stringResource(R.string.math_order), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) {
            runCatching {
                val expr = ExpressionParser.parse(state.expression)
                val v = state.variable.trim().ifEmpty { "x" }
                val x = state.at.toDouble()
                val order = state.order.toIntOrNull()?.coerceIn(0, 12) ?: 1
                val d = Numerical.derivative({ t -> Evaluator.real(expr, v, t) }, x, order)
                state.title = "d^$order/d$v^$order"
                state.result = Numbers.format(d)
                state.detail = "at $v = ${Numbers.format(x)}  ·  central difference order $order"
                state.error = null
                state.points = emptyList()
            }.onFailure { state.error = it.message ?: "Could not differentiate" }
        }
        state.error?.let {
            Spacer(Modifier.height(5.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MathResultCard(state.title, state.result, detail = state.detail)
        }
    }
    Spacer(Modifier.height(6.dp))
    MathHint(stringResource(R.string.math_hint_derivative))
}

@Composable
private fun IntegralCard(state: CalculusState) {
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_tool_integral))
        Spacer(Modifier.height(5.dp))
        MathTextField(state.expression, { state.expression = it }, "f(x)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.variable, { state.variable = it }, stringResource(R.string.math_variable), Modifier.weight(1f))
            MathTextField(state.a, { state.a = it }, stringResource(R.string.math_from), Modifier.weight(1f))
            MathTextField(state.b, { state.b = it }, stringResource(R.string.math_to), Modifier.weight(1f))
        }
        Spacer(Modifier.height(5.dp))
        MathToolSelector(
            listOf(
                "adaptive" to stringResource(R.string.math_integral_adaptive),
                "gauss" to stringResource(R.string.math_integral_gauss),
                "romberg" to stringResource(R.string.math_integral_romberg),
            ),
            selected = state.method,
        ) { state.method = it }
        Spacer(Modifier.height(6.dp))
        MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) {
            runCatching {
                val expr = ExpressionParser.parse(state.expression)
                val v = state.variable.trim().ifEmpty { "x" }
                val a = state.a.toDouble()
                val b = state.b.toDouble()
                val f: (Double) -> Double = { t -> Evaluator.real(expr, v, t) }
                val q = when (state.method) {
                    "gauss" -> Numerical.gaussLegendre(f, a, b)
                    "romberg" -> Numerical.romberg(f, a, b)
                    else -> Numerical.adaptiveSimpson(f, a, b)
                }
                state.title = "∫ f d$v"
                state.result = Numbers.format(q.value)
                state.detail = "error ≈ ${Numbers.format(q.error)}  ·  ${q.evaluations} evaluations"
                state.error = null
                state.points = emptyList()
            }.onFailure { state.error = it.message ?: "Could not integrate" }
        }
        state.error?.let {
            Spacer(Modifier.height(5.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MathResultCard(state.title, state.result, detail = state.detail)
        }
    }
    Spacer(Modifier.height(6.dp))
    MathHint(stringResource(R.string.math_hint_integral))
}

@Composable
private fun OdeCard(state: CalculusState) {
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_tool_ode))
        Spacer(Modifier.height(5.dp))
        MathTextField(state.odeExpression, { state.odeExpression = it }, "dy/dx = f(x, y)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.x0, { state.x0 = it }, "x₀", Modifier.weight(1f))
            MathTextField(state.y0, { state.y0 = it }, "y₀", Modifier.weight(1f))
            MathTextField(state.x1, { state.x1 = it }, "x₁", Modifier.weight(1f))
            MathTextField(state.steps, { state.steps = it.filter(Char::isDigit) }, "steps", Modifier.weight(1f))
        }
        Spacer(Modifier.height(5.dp))
        MathToolSelector(
            OdeMethod.entries.map { it.name to it.name },
            selected = state.odeMethod,
        ) { state.odeMethod = it }
        Spacer(Modifier.height(6.dp))
        MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) {
            runCatching {
                val expr = ExpressionParser.parse(state.odeExpression)
                val x0 = state.x0.toDouble()
                val y0 = state.y0.toDouble()
                val x1 = state.x1.toDouble()
                val steps = state.steps.toIntOrNull()?.coerceIn(10, 20_000) ?: 400
                val method = OdeMethod.entries.firstOrNull { it.name == state.odeMethod } ?: OdeMethod.RK45
                val points = Numerical.solveOde(
                    { x, y -> Evaluator.real(expr, mapOf("x" to x, "y" to y)) },
                    x0, y0, x1, steps, method,
                )
                val last = points.last()
                state.title = "y(${Numbers.format(last.x)})"
                state.result = Numbers.format(last.y)
                state.detail = "${points.size} points  ·  ${method.name}"
                state.points = points
                state.error = null
            }.onFailure {
                state.error = it.message ?: "Could not solve the ODE"
                state.points = emptyList()
            }
        }
        state.error?.let {
            Spacer(Modifier.height(5.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MathResultCard(state.title, state.result, detail = state.detail)
        }
    }
    Spacer(Modifier.height(6.dp))
    MathHint(stringResource(R.string.math_hint_ode))
}

@Composable
private fun RootCard(state: CalculusState) {
    val rootLabel = stringResource(R.string.math_root_result)
    val noConvergence = stringResource(R.string.math_no_convergence)
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_tool_root))
        Spacer(Modifier.height(5.dp))
        MathTextField(state.expression, { state.expression = it }, "f(x)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        MathToolSelector(
            listOf(
                "bisection" to stringResource(R.string.math_root_bisection),
                "newton" to stringResource(R.string.math_root_newton),
                "secant" to stringResource(R.string.math_root_secant),
            ),
            selected = state.rootMethod,
        ) { state.rootMethod = it }
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.rootA, { state.rootA = it }, stringResource(R.string.math_left), Modifier.weight(1f))
            MathTextField(state.rootB, { state.rootB = it }, stringResource(R.string.math_right), Modifier.weight(1f))
            MathTextField(state.guess, { state.guess = it }, stringResource(R.string.math_guess), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) {
            runCatching {
                val expr = ExpressionParser.parse(state.expression)
                val f: (Double) -> Double = { x -> Evaluator.real(expr, "x", x) }
                val r = when (state.rootMethod) {
                    "newton" -> Numerical.newton(f, state.guess.toDouble())
                    "secant" -> Numerical.secant(f, state.rootA.toDouble(), state.rootB.toDouble())
                    else -> Numerical.bisection(f, state.rootA.toDouble(), state.rootB.toDouble())
                }
                state.title = rootLabel
                state.result = if (r.converged) Numbers.format(r.root) else noConvergence
                state.detail = "${r.iterations} iterations  ·  residual ${Numbers.format(r.residual)}"
                state.error = if (r.converged) null else noConvergence
                state.points = emptyList()
            }.onFailure { state.error = it.message ?: "Could not find a root" }
        }
        state.error?.let {
            Spacer(Modifier.height(5.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MathResultCard(state.title, state.result, detail = state.detail)
        }
    }
    Spacer(Modifier.height(6.dp))
    MathHint(stringResource(R.string.math_hint_root))
}

@Composable
private fun LimitCard(state: CalculusState) {
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_tool_limit))
        Spacer(Modifier.height(5.dp))
        MathTextField(state.expression, { state.expression = it }, "f(x)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.variable, { state.variable = it }, stringResource(R.string.math_variable), Modifier.weight(1f))
            MathTextField(state.at, { state.at = it }, "$" + stringResource(R.string.math_at), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) {
            runCatching {
                val expr = ExpressionParser.parse(state.expression)
                val v = state.variable.trim().ifEmpty { "x" }
                val at = state.at.toDouble()
                val value = Symbolic.limit(expr, v, at)
                state.title = "lim $v→${Numbers.format(at)}"
                state.result = Numbers.format(value)
                state.detail = if (value.isFinite()) "two-sided numerical limit" else "limit does not settle"
                state.error = null
                state.points = emptyList()
            }.onFailure { state.error = it.message ?: "Could not take the limit" }
        }
        state.error?.let {
            Spacer(Modifier.height(5.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MathResultCard(state.title, state.result, detail = state.detail)
        }
    }
    Spacer(Modifier.height(6.dp))
    MathHint(stringResource(R.string.math_hint_limit))
}

@Composable
private fun SeriesCard(state: CalculusState) {
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_tool_series))
        Spacer(Modifier.height(5.dp))
        MathTextField(state.seriesExpression, { state.seriesExpression = it }, "a(n)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.seriesFrom, { state.seriesFrom = it }, stringResource(R.string.math_from), Modifier.weight(1f))
            MathTextField(state.seriesTo, { state.seriesTo = it }, stringResource(R.string.math_to), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        MathPrimaryButton(stringResource(R.string.math_compute), Modifier.fillMaxWidth()) {
            runCatching {
                val expr = ExpressionParser.parse(state.seriesExpression)
                val from = state.seriesFrom.toIntOrNull() ?: 1
                val to = state.seriesTo.toIntOrNull()?.coerceIn(from, 1_000_000) ?: 60
                var sum = 0.0
                var last = 0.0
                val partial = ArrayList<SamplePoint>()
                for (n in from..to) {
                    last = Evaluator.real(expr, "n", n.toDouble())
                    if (!last.isFinite()) continue
                    sum += last
                    if (n == from || (n - from) % max(1, (to - from) / 200) == 0 || n == to) {
                        partial += SamplePoint(n.toDouble(), sum)
                    }
                }
                state.title = "Σ a(n), n=$from…$to"
                state.result = Numbers.format(sum)
                state.detail = "last term ${Numbers.format(last)}  ·  ${to - from + 1} terms"
                state.points = partial
                state.error = null
            }.onFailure {
                state.error = it.message ?: "Could not sum the series"
                state.points = emptyList()
            }
        }
        state.error?.let {
            Spacer(Modifier.height(5.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MathResultCard(state.title, state.result, detail = state.detail)
        }
    }
    Spacer(Modifier.height(6.dp))
    MathHint(stringResource(R.string.math_hint_series))
}

/** A minimal autoscaled line plot for ODE trajectories and partial sums. */
@Composable
internal fun MiniPlot(points: List<SamplePoint>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.paper.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            val finite = points.filter { it.x.isFinite() && it.y.isFinite() }
            if (finite.size < 2) return@Canvas
            val x0 = finite.minOf { it.x }
            val x1 = finite.maxOf { it.x }
            val y0 = finite.minOf { it.y }
            val y1 = finite.maxOf { it.y }
            val dx = (x1 - x0).takeIf { abs(it) > 1e-15 } ?: 1.0
            val dy = (y1 - y0).takeIf { abs(it) > 1e-15 } ?: 1.0
            fun sx(x: Double): Float = ((x - x0) / dx * size.width).toFloat()
            fun sy(y: Double): Float = (size.height - (y - y0) / dy * size.height).toFloat()
            drawLine(palette.border.toComposeColor(), Offset(0f, sy(0.0).coerceIn(0f, size.height)), Offset(size.width, sy(0.0).coerceIn(0f, size.height)), 1f)
            var prev: Offset? = null
            for (p in finite) {
                val point = Offset(sx(p.x), sy(p.y))
                prev?.let { drawLine(palette.accent.toComposeColor(), it, point, strokeWidth = 2f) }
                prev = point
            }
        }
    }
}

// ---------------------------------------------------------------------------------- symbolic

internal class SymbolicState {
    var expression by mutableStateOf("x^3 - 3x + 1")
    var variable by mutableStateOf("x")
    var at by mutableStateOf("0")
    var order by mutableStateOf("6")
    var title by mutableStateOf("")
    var result by mutableStateOf("")
    var detail by mutableStateOf<String?>(null)
    var latex by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

@Composable
internal fun SymbolicPane(state: SymbolicState) {
    val derivativeLabel = stringResource(R.string.math_symbolic_derivative)
    val secondLabel = stringResource(R.string.math_symbolic_second)
    val integralLabel = stringResource(R.string.math_symbolic_integral)
    val simplifyLabel = stringResource(R.string.math_symbolic_simplify)
    val expandLabel = stringResource(R.string.math_symbolic_expand)
    val taylorLabel = stringResource(R.string.math_symbolic_taylor)
    val solveLabel = stringResource(R.string.math_symbolic_solve)
    val limitLabel = stringResource(R.string.math_symbolic_limit)
    Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
        MathCard(Modifier.fillMaxWidth()) {
            MathSectionTitle(stringResource(R.string.math_symbolic_expression))
            Spacer(Modifier.height(5.dp))
            MathTextField(state.expression, { state.expression = it }, "f(x)", Modifier.fillMaxWidth(), minLines = 2)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MathTextField(state.variable, { state.variable = it }, stringResource(R.string.math_variable), Modifier.weight(1f))
                MathTextField(state.at, { state.at = it }, stringResource(R.string.math_at), Modifier.weight(1f))
                MathTextField(state.order, { state.order = it.filter(Char::isDigit) }, stringResource(R.string.math_order), Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SymbolicButton(derivativeLabel, Modifier.weight(1f)) {
                    symbolicAction(state, derivativeLabel) {
                        Symbolic.derivative(it, state.variable.trim().ifEmpty { "x" })
                    }
                }
                SymbolicButton(secondLabel, Modifier.weight(1f)) {
                    symbolicAction(state, secondLabel) {
                        val v = state.variable.trim().ifEmpty { "x" }
                        Symbolic.derivative(Symbolic.derivative(it, v), v)
                    }
                }
                SymbolicButton(integralLabel, Modifier.weight(1f)) {
                    symbolicAction(state, integralLabel) {
                        Symbolic.integrate(it, state.variable.trim().ifEmpty { "x" })
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SymbolicButton(simplifyLabel, Modifier.weight(1f)) {
                    symbolicAction(state, simplifyLabel) { Symbolic.simplify(it) }
                }
                SymbolicButton(expandLabel, Modifier.weight(1f)) {
                    symbolicAction(state, expandLabel) { Symbolic.expand(it) }
                }
                SymbolicButton(taylorLabel, Modifier.weight(1f)) {
                    symbolicAction(state, taylorLabel) {
                        Symbolic.taylor(
                            it,
                            state.variable.trim().ifEmpty { "x" },
                            state.at.toDoubleOrNull() ?: 0.0,
                            state.order.toIntOrNull()?.coerceIn(1, 24) ?: 6,
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SymbolicButton(solveLabel, Modifier.weight(1f)) {
                    runCatching {
                        val v = state.variable.trim().ifEmpty { "x" }
                        val roots = Symbolic.solve(ExpressionParser.parse(state.expression), v)
                        if (roots == null) throw IllegalArgumentException("Not a polynomial in $v")
                        state.title = solveLabel
                        state.result = Symbolic.formatRoots(roots)
                        state.detail = "${roots.size} root(s) in the complex plane"
                        state.latex = null
                        state.error = null
                    }.onFailure { state.error = it.message ?: "Could not solve" }
                }
                SymbolicButton(limitLabel, Modifier.weight(1f)) {
                    runCatching {
                        val v = state.variable.trim().ifEmpty { "x" }
                        val value = Symbolic.limit(ExpressionParser.parse(state.expression), v, state.at.toDoubleOrNull() ?: 0.0)
                        state.title = limitLabel
                        state.result = Numbers.format(value)
                        state.detail = "lim $v → ${state.at}"
                        state.latex = null
                        state.error = null
                    }.onFailure { state.error = it.message ?: "Could not take the limit" }
                }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(7.dp))
            MathResultCard(stringResource(R.string.math_error), it, isError = true)
        }
        if (state.result.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            MathCard(Modifier.fillMaxWidth()) {
                MathSectionTitle(state.title)
                Spacer(Modifier.height(5.dp))
                MathResultCard(
                    stringResource(R.string.math_symbolic_result),
                    state.result,
                    detail = state.detail,
                )
                state.latex?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        it,
                        color = paletteTextDim(),
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
            }
        }
        Spacer(Modifier.height(6.dp))
        MathHint(stringResource(R.string.math_hint_symbolic))
    }
}

@Composable
private fun SymbolicButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MathSecondaryButton(text, modifier.height(34.dp), onClick = onClick)
}

@Composable
private fun paletteTextDim(): Color = LocalPalette.current.textDim.toComposeColor()

private fun symbolicAction(
    state: SymbolicState,
    title: String,
    operation: (Expr) -> Expr,
) {
    runCatching {
        val expr = ExpressionParser.parse(state.expression)
        val result = operation(expr)
        val text = result.toText()
        if (text.contains("Integral(") || text.contains("D(")) {
            throw IllegalArgumentException("No elementary closed form found")
        }
        state.title = title
        state.result = text
        state.detail = "simplified symbolic form"
        state.latex = result.toLatex()
        state.error = null
    }.onFailure {
        state.error = it.message ?: "Could not evaluate symbolically"
        state.result = ""
    }
}

// ------------------------------------------------------------------------------------ units

internal class UnitState {
    var category by mutableStateOf(Units.categories.first().name)
    var from by mutableStateOf(Units.categories.first().units.first().symbol)
    var to by mutableStateOf(Units.categories.first().units.getOrNull(1)?.symbol ?: Units.categories.first().units.first().symbol)
    var value by mutableStateOf("1")

    fun selectCategory(name: String) {
        val c = Units.category(name) ?: return
        category = name
        from = c.units.firstOrNull()?.symbol.orEmpty()
        to = c.units.getOrNull(1)?.symbol ?: from
    }
}

@Composable
internal fun UnitPane(state: UnitState) {
    val palette = LocalPalette.current
    val category = Units.category(state.category)
    val from = category?.unit(state.from)
    val to = category?.unit(state.to)
    val input = runCatching {
        Evaluator.evaluate(ExpressionParser.parse(state.value)).realValue()
    }.getOrNull()
    val converted = if (input != null && from != null && to != null) Units.convert(input, from, to) else null
    val equivalents = if (input != null) Units.equivalents(input, state.category, state.from) else emptyList()

    Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
        MathCard(Modifier.fillMaxWidth()) {
            MathSectionTitle(stringResource(R.string.math_units_category))
            Spacer(Modifier.height(5.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for (c in Units.categories) {
                    MathChip(c.name, selected = c.name == state.category) { state.selectCategory(c.name) }
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MathTextField(state.value, { state.value = it }, stringResource(R.string.math_units_value), Modifier.weight(1f))
                MathDropdown(
                    label = stringResource(R.string.math_units_from),
                    selected = state.from,
                    options = category?.units?.map { it.symbol to it.name }.orEmpty(),
                    modifier = Modifier.weight(1f),
                ) { state.from = it }
                MathIconButton(Icons.Outlined.SwapHoriz, stringResource(R.string.math_units_swap)) {
                    val old = state.from
                    state.from = state.to
                    state.to = old
                }
                MathDropdown(
                    label = stringResource(R.string.math_units_to),
                    selected = state.to,
                    options = category?.units?.map { it.symbol to it.name }.orEmpty(),
                    modifier = Modifier.weight(1f),
                ) { state.to = it }
            }
        }
        if (converted != null) {
            Spacer(Modifier.height(7.dp))
            MathResultCard(
                stringResource(R.string.math_units_result),
                "${Numbers.format(converted)}  ${to?.symbol.orEmpty()}",
                detail = "${Numbers.format(input ?: 0.0)} ${from?.symbol.orEmpty()}  ·  ${to?.name.orEmpty()}",
            )
        } else {
            Spacer(Modifier.height(7.dp))
            MathResultCard(stringResource(R.string.math_error), stringResource(R.string.math_units_bad_value), isError = true)
        }
        Spacer(Modifier.height(7.dp))
        MathCard(Modifier.fillMaxWidth()) {
            MathSectionTitle(stringResource(R.string.math_units_equivalents))
            Spacer(Modifier.height(5.dp))
            if (equivalents.isEmpty()) {
                MathHint(stringResource(R.string.math_units_bad_value))
            } else {
                for ((unit, v) in equivalents) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            unit.name,
                            color = palette.textDim.toComposeColor(),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${Numbers.format(v)} ${unit.symbol}",
                            color = palette.text.toComposeColor(),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        MathHint(stringResource(R.string.math_hint_units))
    }
}

@Composable
private fun MathDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text(label, color = palette.textDim.toComposeColor(), fontSize = 9.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected,
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", color = palette.textDim.toComposeColor(), fontSize = 10.sp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((symbol, name) in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "$symbol  ·  $name",
                            color = palette.text.toComposeColor(),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    },
                    onClick = { onSelect(symbol); open = false },
                )
            }
        }
    }
}
