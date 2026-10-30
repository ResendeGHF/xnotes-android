package com.xnotes.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Symbolic algebra on the shared expression tree. The rules are deliberately conservative: when a
 * closed form is not known the original call is returned (or an unevaluated `Integral`/`D` node is
 * produced) instead of inventing a wrong answer. The symbolic card is a scratchpad, not a CAS
 * replacement for a full computer-algebra system.
 */
object Symbolic {

    fun simplify(e: Expr): Expr {
        val s = when (e) {
            is Expr.Num -> e
            is Expr.Var -> e
            is Expr.Neg -> {
                val v = simplify(e.value)
                when {
                    v is Expr.Num -> Expr.Num(-v.value)
                    v is Expr.Neg -> v.value
                    else -> Expr.Neg(v)
                }
            }
            is Expr.Bin -> simplifyBinary(e.op, simplify(e.left), simplify(e.right))
            is Expr.Call -> {
                val args = e.args.map { simplify(it) }
                val call = Expr.Call(e.name, args)
                if (args.all { it is Expr.Num }) constantFold(call) ?: call else call
            }
            is Expr.Factorial -> {
                val v = simplify(e.value)
                if (v is Expr.Num && isInteger(v.value) && v.value in 0.0..170.0) Expr.Num(Special.factorial(v.value))
                else Expr.Factorial(v)
            }
            is Expr.Sum -> Expr.Sum(e.variable, simplify(e.from), simplify(e.to), simplify(e.body))
            is Expr.Product -> Expr.Product(e.variable, simplify(e.from), simplify(e.to), simplify(e.body))
        }
        return if (s.variables().isEmpty()) constantFold(s) ?: s else s
    }

    private fun constantFold(e: Expr): Expr? {
        if (e is Expr.Sum || e is Expr.Product) return null
        if (e is Expr.Call && e.name.lowercase() in setOf("integral", "integrate", "derivative", "diff", "limit")) return null
        val value = runCatching { Evaluator.evaluate(e) }.getOrNull() ?: return null
        if (!value.isFinite || abs(value.im) > 1e-10) return null
        return Expr.Num(value.re)
    }

    private fun simplifyBinary(op: BinOp, l: Expr, r: Expr): Expr {
        if (op == BinOp.ADD || op == BinOp.SUB) {
            val sign = if (op == BinOp.SUB) -1.0 else 1.0
            if (op == BinOp.SUB && l == r) return Expr.Num(0.0)
            if (l is Expr.Num && l.value == 0.0) return if (op == BinOp.ADD) r else Expr.Neg(r)
            if (r is Expr.Num && r.value == 0.0) return l
            if (l is Expr.Num && r is Expr.Num) return Expr.Num(l.value + sign * r.value)
            // Collect a numeric term at the end: 3 + x + 4 -> x + 7.
            if (r is Expr.Num && l is Expr.Bin && l.op == BinOp.ADD && l.right is Expr.Num) {
                return Expr.Bin(BinOp.ADD, l.left, Expr.Num(l.right.value + sign * r.value))
            }
            if (op == BinOp.SUB && r is Expr.Neg) return simplifyBinary(BinOp.ADD, l, r.value)
            return Expr.Bin(op, l, r)
        }
        if (op == BinOp.MUL) {
            if (l is Expr.Num && l.value == 0.0) return Expr.Num(0.0)
            if (r is Expr.Num && r.value == 0.0) return Expr.Num(0.0)
            if (l is Expr.Num && l.value == 1.0) return r
            if (r is Expr.Num && r.value == 1.0) return l
            if (l is Expr.Num && l.value == -1.0) return simplify(Expr.Neg(r))
            if (r is Expr.Num && r.value == -1.0) return simplify(Expr.Neg(l))
            if (l is Expr.Num && r is Expr.Num) return Expr.Num(l.value * r.value)
            if (l == r) return Expr.Bin(BinOp.POW, l, Expr.Num(2.0))
            if (r is Expr.Num && l is Expr.Bin && l.op == BinOp.MUL && l.right is Expr.Num) {
                return Expr.Bin(BinOp.MUL, l.left, Expr.Num(l.right.value * r.value))
            }
            return Expr.Bin(op, l, r)
        }
        if (op == BinOp.DIV) {
            if (l is Expr.Num && l.value == 0.0 && r !is Expr.Num) return Expr.Num(0.0)
            if (r is Expr.Num && r.value == 1.0) return l
            if (l == r) return Expr.Num(1.0)
            if (l is Expr.Num && r is Expr.Num) return Expr.Num(l.value / r.value)
            return Expr.Bin(op, l, r)
        }
        if (op == BinOp.POW) {
            if (r is Expr.Num) {
                if (r.value == 0.0) return Expr.Num(1.0)
                if (r.value == 1.0) return l
                if (l is Expr.Num) return Expr.Num(l.value.pow(r.value))
                if (l is Expr.Bin && l.op == BinOp.POW && l.right is Expr.Num) {
                    return Expr.Bin(BinOp.POW, l.left, Expr.Num(l.right.value * r.value))
                }
            }
            if (l is Expr.Num && l.value == 1.0) return Expr.Num(1.0)
            return Expr.Bin(op, l, r)
        }
        return Expr.Bin(op, l, r)
    }

    // ------------------------------------------------------------------ differentiation

    fun derivative(e: Expr, variable: String): Expr = simplify(deriv(e, variable))

    private fun deriv(e: Expr, v: String): Expr = when (e) {
        is Expr.Num -> Expr.Num(0.0)
        is Expr.Var -> Expr.Num(if (e.name == v) 1.0 else 0.0)
        is Expr.Neg -> Expr.Neg(deriv(e.value, v))
        is Expr.Bin -> when (e.op) {
            BinOp.ADD, BinOp.SUB -> Expr.Bin(e.op, deriv(e.left, v), deriv(e.right, v))
            BinOp.MUL -> Expr.Bin(
                BinOp.ADD,
                Expr.Bin(BinOp.MUL, deriv(e.left, v), e.right),
                Expr.Bin(BinOp.MUL, e.left, deriv(e.right, v)),
            )
            BinOp.DIV -> Expr.Bin(
                BinOp.DIV,
                Expr.Bin(
                    BinOp.SUB,
                    Expr.Bin(BinOp.MUL, deriv(e.left, v), e.right),
                    Expr.Bin(BinOp.MUL, e.left, deriv(e.right, v)),
                ),
                Expr.Bin(BinOp.POW, e.right, Expr.Num(2.0)),
            )
            BinOp.POW -> {
                val basePart = Expr.Bin(
                    BinOp.MUL,
                    e.right,
                    Expr.Bin(BinOp.POW, e.left, Expr.Bin(BinOp.SUB, e.right, Expr.Num(1.0))),
                )
                val power = Expr.Bin(BinOp.POW, e.left, e.right)
                val exponentPart = Expr.Bin(BinOp.MUL, power, Expr.Call("ln", listOf(e.left)))
                Expr.Bin(
                    BinOp.ADD,
                    Expr.Bin(BinOp.MUL, basePart, deriv(e.left, v)),
                    Expr.Bin(BinOp.MUL, exponentPart, deriv(e.right, v)),
                )
            }
            else -> Expr.Num(0.0)
        }
        is Expr.Call -> callDerivative(e, v)
        is Expr.Factorial -> {
            val g = Expr.Call("gamma", listOf(Expr.Bin(BinOp.ADD, e.value, Expr.Num(1.0))))
            Expr.Bin(BinOp.MUL, Expr.Bin(BinOp.MUL, g, Expr.Call("digamma", listOf(Expr.Bin(BinOp.ADD, e.value, Expr.Num(1.0))))), deriv(e.value, v))
        }
        is Expr.Sum -> if (v == e.variable) Expr.Num(0.0) else Expr.Sum(e.variable, e.from, e.to, deriv(e.body, v))
        is Expr.Product -> if (v == e.variable) Expr.Num(0.0) else Expr.Call("D", listOf(e, Expr.Var(v)))
    }

    private fun callDerivative(call: Expr.Call, v: String): Expr {
        val name = call.name.lowercase()
        val x = call.args.firstOrNull() ?: return Expr.Num(0.0)
        if (v !in call.args.flatMap { it.variables() }.toSet()) return Expr.Num(0.0)
        fun chain(outer: Expr): Expr = Expr.Bin(BinOp.MUL, outer, deriv(x, v))
        fun one(f: (Expr) -> Expr) = Expr.Bin(BinOp.MUL, f(x), deriv(x, v))
        return when (name) {
            "sin" -> one { Expr.Call("cos", listOf(it)) }
            "cos" -> one { Expr.Neg(Expr.Call("sin", listOf(it))) }
            "tan" -> one { Expr.Bin(BinOp.POW, Expr.Call("sec", listOf(it)), Expr.Num(2.0)) }
            "cot" -> one { Expr.Neg(Expr.Bin(BinOp.POW, Expr.Call("csc", listOf(it)), Expr.Num(2.0))) }
            "sec" -> one { Expr.Bin(BinOp.MUL, Expr.Call("sec", listOf(it)), Expr.Call("tan", listOf(it))) }
            "csc" -> one { Expr.Neg(Expr.Bin(BinOp.MUL, Expr.Call("csc", listOf(it)), Expr.Call("cot", listOf(it)))) }
            "asin" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Call("sqrt", listOf(Expr.Bin(BinOp.SUB, Expr.Num(1.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0)))))) }
            "acos" -> one { Expr.Neg(Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Call("sqrt", listOf(Expr.Bin(BinOp.SUB, Expr.Num(1.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0))))))) }
            "atan" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Bin(BinOp.ADD, Expr.Num(1.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0)))) }
            "acot" -> one { Expr.Neg(Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Bin(BinOp.ADD, Expr.Num(1.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0))))) }
            "sinh" -> one { Expr.Call("cosh", listOf(it)) }
            "cosh" -> one { Expr.Call("sinh", listOf(it)) }
            "tanh" -> one { Expr.Bin(BinOp.SUB, Expr.Num(1.0), Expr.Bin(BinOp.POW, Expr.Call("tanh", listOf(it)), Expr.Num(2.0))) }
            "coth" -> one { Expr.Neg(Expr.Bin(BinOp.POW, Expr.Call("csch", listOf(it)), Expr.Num(2.0))) }
            "sech" -> one { Expr.Neg(Expr.Bin(BinOp.MUL, Expr.Call("sech", listOf(it)), Expr.Call("tanh", listOf(it)))) }
            "csch" -> one { Expr.Neg(Expr.Bin(BinOp.MUL, Expr.Call("csch", listOf(it)), Expr.Call("coth", listOf(it)))) }
            "asinh" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Call("sqrt", listOf(Expr.Bin(BinOp.ADD, Expr.Bin(BinOp.POW, it, Expr.Num(2.0)), Expr.Num(1.0))))) }
            "acosh" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Call("sqrt", listOf(Expr.Bin(BinOp.SUB, Expr.Bin(BinOp.POW, it, Expr.Num(2.0)), Expr.Num(1.0))))) }
            "atanh" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Bin(BinOp.SUB, Expr.Num(1.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0)))) }
            "ln" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), it) }
            "log", "log10" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Bin(BinOp.MUL, it, Expr.Num(2.302585092994046))) }
            "exp" -> one { Expr.Call("exp", listOf(it)) }
            "sqrt" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Bin(BinOp.MUL, Expr.Num(2.0), Expr.Call("sqrt", listOf(it)))) }
            "cbrt" -> one { Expr.Bin(BinOp.DIV, Expr.Num(1.0), Expr.Bin(BinOp.MUL, Expr.Num(3.0), Expr.Bin(BinOp.POW, Expr.Call("cbrt", listOf(it)), Expr.Num(2.0)))) }
            "abs" -> one { Expr.Call("sign", listOf(it)) }
            "sign", "sgn", "floor", "ceil", "ceiling", "round", "trunc", "frac" -> Expr.Num(0.0)
            "gamma" -> one { Expr.Bin(BinOp.MUL, Expr.Call("gamma", listOf(it)), Expr.Call("digamma", listOf(it))) }
            "lgamma", "lngamma" -> one { Expr.Call("digamma", listOf(it)) }
            "digamma" -> one { Expr.Call("polygamma", listOf(Expr.Num(1.0), it)) }
            "erf" -> one { Expr.Bin(BinOp.MUL, Expr.Num(2.0 / sqrt(PI)), Expr.Call("exp", listOf(Expr.Neg(Expr.Bin(BinOp.POW, it, Expr.Num(2.0)))))) }
            "erfc" -> one { Expr.Neg(Expr.Bin(BinOp.MUL, Expr.Num(2.0 / sqrt(PI)), Expr.Call("exp", listOf(Expr.Neg(Expr.Bin(BinOp.POW, it, Expr.Num(2.0))))))) }
            "erfi" -> one { Expr.Bin(BinOp.MUL, Expr.Num(2.0 / sqrt(PI)), Expr.Call("exp", listOf(Expr.Bin(BinOp.POW, it, Expr.Num(2.0))))) }
            "dawson" -> one { Expr.Bin(BinOp.SUB, Expr.Num(1.0), Expr.Bin(BinOp.MUL, Expr.Num(2.0), Expr.Bin(BinOp.MUL, it, Expr.Call("dawson", listOf(it))))) }
            "fresnels" -> one { Expr.Call("sin", listOf(Expr.Bin(BinOp.MUL, Expr.Num(PI / 2.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0))))) }
            "fresnelc" -> one { Expr.Call("cos", listOf(Expr.Bin(BinOp.MUL, Expr.Num(PI / 2.0), Expr.Bin(BinOp.POW, it, Expr.Num(2.0))))) }
            "besselj", "jn" -> {
                val order = call.args.getOrNull(0) ?: x
                val arg = call.args.getOrNull(1) ?: x
                if (v !in arg.variables()) Expr.Num(0.0) else Expr.Bin(
                    BinOp.MUL,
                    Expr.Bin(
                        BinOp.DIV,
                        Expr.Bin(
                            BinOp.SUB,
                            Expr.Call("besselj", listOf(Expr.Bin(BinOp.SUB, order, Expr.Num(1.0)), arg)),
                            Expr.Call("besselj", listOf(Expr.Bin(BinOp.ADD, order, Expr.Num(1.0)), arg)),
                        ),
                        Expr.Num(2.0),
                    ),
                    deriv(arg, v),
                )
            }
            else -> Expr.Call("D", listOf(call, Expr.Var(v)))
        }
    }

    // ------------------------------------------------------------------ integration

    fun integrate(e: Expr, variable: String): Expr = simplify(integrateRaw(e, variable))

    private fun integrateRaw(e: Expr, v: String): Expr {
        if (v !in e.variables()) return Expr.Bin(BinOp.MUL, e, Expr.Var(v))
        return when (e) {
            is Expr.Num -> Expr.Bin(BinOp.MUL, e, Expr.Var(v))
            is Expr.Var -> if (e.name == v) Expr.Bin(BinOp.DIV, Expr.Bin(BinOp.POW, Expr.Var(v), Expr.Num(2.0)), Expr.Num(2.0)) else Expr.Bin(BinOp.MUL, e, Expr.Var(v))
            is Expr.Neg -> Expr.Neg(integrateRaw(e.value, v))
            is Expr.Bin -> when (e.op) {
                BinOp.ADD, BinOp.SUB -> Expr.Bin(e.op, integrateRaw(e.left, v), integrateRaw(e.right, v))
                BinOp.MUL -> {
                    if (v !in e.left.variables()) Expr.Bin(BinOp.MUL, e.left, integrateRaw(e.right, v))
                    else if (v !in e.right.variables()) Expr.Bin(BinOp.MUL, e.right, integrateRaw(e.left, v))
                    else integrateByParts(e, v) ?: unevaluated(e, v)
                }
                BinOp.DIV -> integrateQuotient(e, v) ?: unevaluated(e, v)
                BinOp.POW -> integratePower(e, v) ?: unevaluated(e, v)
                else -> unevaluated(e, v)
            }
            is Expr.Call -> integrateCall(e, v) ?: unevaluated(e, v)
            is Expr.Factorial -> unevaluated(e, v)
            is Expr.Sum -> Expr.Sum(e.variable, e.from, e.to, integrateRaw(e.body, v))
            is Expr.Product -> unevaluated(e, v)
        }
    }

    private fun integratePower(e: Expr.Bin, v: String): Expr? {
        val base = e.left
        val exponent = e.right
        val baseD = linearCoefficient(base, v) ?: return null
        if (exponent !is Expr.Num) {
            // ∫ e^(a x) dx is handled through Call("exp"); a general power with variable exponent is not elementary.
            return null
        }
        val n = exponent.value
        if (abs(n + 1.0) < 1e-12) {
            return Expr.Bin(BinOp.DIV, Expr.Call("ln", listOf(Expr.Call("abs", listOf(base)))), Expr.Num(baseD))
        }
        val newPower = Expr.Num(n + 1.0)
        return Expr.Bin(
            BinOp.DIV,
            Expr.Bin(BinOp.POW, base, newPower),
            Expr.Num(baseD * (n + 1.0)),
        )
    }

    private fun integrateQuotient(e: Expr.Bin, v: String): Expr? {
        val num = e.left
        val den = e.right
        // 1/linear
        val a = linearCoefficient(den, v)
        if (a != null && v !in num.variables()) {
            if (num is Expr.Num && abs(num.value - 1.0) < 1e-12) {
                return Expr.Bin(BinOp.DIV, Expr.Call("ln", listOf(Expr.Call("abs", listOf(den)))), Expr.Num(a))
            }
        }
        // x/(x²+a²)
        if (den is Expr.Bin && den.op == BinOp.ADD && den.left is Expr.Bin && den.left.op == BinOp.POW &&
            (den.left.left == Expr.Var(v)) && den.left.right is Expr.Num && den.left.right.value == 2.0 &&
            v !in den.right.variables() && num == Expr.Var(v)
        ) {
            return Expr.Bin(BinOp.MUL, Expr.Num(0.5), Expr.Call("ln", listOf(den)))
        }
        // 1/(1+x²) and close relatives
        if (num is Expr.Num && num.value == 1.0 && isOnePlusSquare(den, v)) {
            return Expr.Call("atan", listOf(Expr.Var(v)))
        }
        if (num is Expr.Num && num.value == 1.0 && isOneMinusSquare(den, v)) {
            return Expr.Call("atanh", listOf(Expr.Var(v)))
        }
        return null
    }

    private fun isOnePlusSquare(e: Expr, v: String): Boolean =
        e is Expr.Bin && e.op == BinOp.ADD && e.left == Expr.Num(1.0) &&
            e.right is Expr.Bin && e.right.op == BinOp.POW && e.right.left == Expr.Var(v) &&
            e.right.right is Expr.Num && e.right.right.value == 2.0

    private fun isOneMinusSquare(e: Expr, v: String): Boolean =
        e is Expr.Bin && e.op == BinOp.SUB && e.left == Expr.Num(1.0) &&
            e.right is Expr.Bin && e.right.op == BinOp.POW && e.right.left == Expr.Var(v) &&
            e.right.right is Expr.Num && e.right.right.value == 2.0

    private fun integrateCall(e: Expr.Call, v: String): Expr? {
        val name = e.name.lowercase()
        val arg = e.args.firstOrNull() ?: return null
        if (name == "exp") {
            val a = linearCoefficient(arg, v) ?: return null
            return Expr.Bin(BinOp.DIV, e, Expr.Num(a))
        }
        val a = linearCoefficient(arg, v) ?: return null
        return when (name) {
            "sin" -> Expr.Bin(BinOp.DIV, Expr.Neg(Expr.Call("cos", listOf(arg))), Expr.Num(a))
            "cos" -> Expr.Bin(BinOp.DIV, Expr.Call("sin", listOf(arg)), Expr.Num(a))
            "tan" -> Expr.Bin(BinOp.DIV, Expr.Neg(Expr.Call("ln", listOf(Expr.Call("abs", listOf(Expr.Call("cos", listOf(arg))))))), Expr.Num(a))
            "cot" -> Expr.Bin(BinOp.DIV, Expr.Call("ln", listOf(Expr.Call("abs", listOf(Expr.Call("sin", listOf(arg)))))), Expr.Num(a))
            "sinh" -> Expr.Bin(BinOp.DIV, Expr.Call("cosh", listOf(arg)), Expr.Num(a))
            "cosh" -> Expr.Bin(BinOp.DIV, Expr.Call("sinh", listOf(arg)), Expr.Num(a))
            "sqrt" -> Expr.Bin(BinOp.MUL, Expr.Num(2.0 / 3.0), Expr.Bin(BinOp.DIV, Expr.Bin(BinOp.POW, arg, Expr.Num(1.5)), Expr.Num(a)))
            "ln" -> Expr.Bin(BinOp.DIV, Expr.Bin(BinOp.SUB, Expr.Bin(BinOp.MUL, arg, Expr.Call("ln", listOf(arg))), arg), Expr.Num(a))
            "abs" -> Expr.Bin(BinOp.DIV, Expr.Bin(BinOp.MUL, arg, Expr.Call("abs", listOf(arg))), Expr.Num(2.0 * a))
            "asin" -> Expr.Bin(
                BinOp.DIV,
                Expr.Bin(
                    BinOp.ADD,
                    Expr.Bin(BinOp.MUL, arg, Expr.Call("asin", listOf(arg))),
                    Expr.Call("sqrt", listOf(Expr.Bin(BinOp.SUB, Expr.Num(1.0), Expr.Bin(BinOp.POW, arg, Expr.Num(2.0))))),
                ),
                Expr.Num(a),
            )
            "atan" -> Expr.Bin(
                BinOp.DIV,
                Expr.Bin(
                    BinOp.SUB,
                    Expr.Bin(BinOp.MUL, arg, Expr.Call("atan", listOf(arg))),
                    Expr.Bin(BinOp.MUL, Expr.Num(0.5), Expr.Call("ln", listOf(Expr.Bin(BinOp.ADD, Expr.Num(1.0), Expr.Bin(BinOp.POW, arg, Expr.Num(2.0)))))),
                ),
                Expr.Num(a),
            )
            else -> null
        }
    }

    private fun integrateByParts(e: Expr.Bin, v: String): Expr? {
        // Only the common xᵏ·e^(ax) and xᵏ·sin(ax) patterns; any other product stays unevaluated.
        val (poly, other) = when {
            e.left.variables().contains(v) && !e.right.variables().contains(v) -> e.left to e.right
            e.right.variables().contains(v) && !e.left.variables().contains(v) -> e.right to e.left
            else -> return null
        }
        if (poly is Expr.Var && poly.name == v) {
            val a = when {
                other is Expr.Call && other.name.lowercase() == "exp" -> linearCoefficient(other.args[0], v)
                other is Expr.Call && other.name.lowercase() in setOf("sin", "cos") -> linearCoefficient(other.args[0], v)
                else -> null
            } ?: return null
            val otherIntegral = integrateCall(other as Expr.Call, v) ?: return null
            // ∫ x f(x) dx = x F(x) − ∫ F(x) dx. For exp and trig F is another exponential/trig.
            val second = integrateRaw(otherIntegral, v)
            return Expr.Bin(BinOp.SUB, Expr.Bin(BinOp.MUL, poly, otherIntegral), second)
        }
        return null
    }

    /** The coefficient a in a linear expression a·v + b, or null when [e] is not linear in v. */
    private fun linearCoefficient(e: Expr, v: String): Double? {
        if (v !in e.variables()) return 0.0
        return when (e) {
            is Expr.Var -> if (e.name == v) 1.0 else null
            is Expr.Num -> if (e.value == 0.0) 0.0 else null
            is Expr.Neg -> linearCoefficient(e.value, v)?.let { -it }
            is Expr.Bin -> when (e.op) {
                BinOp.ADD, BinOp.SUB -> {
                    val a = linearCoefficient(e.left, v) ?: return null
                    val b = linearCoefficient(e.right, v) ?: return null
                    a + if (e.op == BinOp.SUB) -b else b
                }
                BinOp.MUL -> {
                    val a = linearCoefficient(e.left, v)
                    val b = linearCoefficient(e.right, v)
                    when {
                        a == null || b == null -> null
                        a == 0.0 -> b
                        b == 0.0 -> a
                        else -> null
                    }
                }
                BinOp.DIV -> {
                    val a = linearCoefficient(e.left, v)
                    if (a == null || v in e.right.variables()) null else a / constantValue(e.right)
                }
                else -> null
            }
            is Expr.Call -> if (v !in e.variables()) 0.0 else null
            else -> null
        }
    }

    private fun constantValue(e: Expr): Double {
        val v = runCatching { Evaluator.evaluate(e) }.getOrNull() ?: return 1.0
        return if (v.im == 0.0) v.re else 1.0
    }

    private fun unevaluated(e: Expr, v: String): Expr = Expr.Call("Integral", listOf(e, Expr.Var(v)))

    // ------------------------------------------------------------------ expansion, series, solving

    fun expand(e: Expr): Expr = simplify(expandRaw(e))

    private fun expandRaw(e: Expr): Expr = when (e) {
        is Expr.Num, is Expr.Var -> e
        is Expr.Neg -> Expr.Neg(expandRaw(e.value))
        is Expr.Bin -> when (e.op) {
            BinOp.ADD, BinOp.SUB -> Expr.Bin(e.op, expandRaw(e.left), expandRaw(e.right))
            BinOp.MUL -> distribute(expandRaw(e.left), expandRaw(e.right))
            BinOp.POW -> {
                val base = expandRaw(e.left)
                val n = (e.right as? Expr.Num)?.value
                if (n != null && isInteger(n) && n >= 0 && n <= 12) {
                    var acc: Expr = Expr.Num(1.0)
                    repeat(n.toInt()) { acc = distribute(acc, base) }
                    acc
                } else Expr.Bin(BinOp.POW, base, e.right)
            }
            else -> Expr.Bin(e.op, expandRaw(e.left), expandRaw(e.right))
        }
        is Expr.Call -> Expr.Call(e.name, e.args.map { expandRaw(it) })
        is Expr.Factorial -> Expr.Factorial(expandRaw(e.value))
        is Expr.Sum -> Expr.Sum(e.variable, expandRaw(e.from), expandRaw(e.to), expandRaw(e.body))
        is Expr.Product -> Expr.Product(e.variable, expandRaw(e.from), expandRaw(e.to), expandRaw(e.body))
    }

    private fun distribute(a: Expr, b: Expr): Expr {
        if (a is Expr.Bin && (a.op == BinOp.ADD || a.op == BinOp.SUB)) {
            return Expr.Bin(a.op, distribute(a.left, b), distribute(a.right, b))
        }
        if (b is Expr.Bin && (b.op == BinOp.ADD || b.op == BinOp.SUB)) {
            return Expr.Bin(b.op, distribute(a, b.left), distribute(a, b.right))
        }
        return Expr.Bin(BinOp.MUL, a, b)
    }

    fun taylor(e: Expr, variable: String, at: Double, order: Int): Expr {
        if (order < 0) return Expr.Num(0.0)
        var term = e
        var result: Expr = Expr.Num(0.0)
        var factorial = 1.0
        for (k in 0..order) {
            val coefficient = runCatching {
                Evaluator.evaluate(term, mapOf(variable to at)).realValue()
            }.getOrNull() ?: Double.NaN
            if (coefficient.isFinite() && abs(coefficient) > 1e-14) {
                var piece: Expr = Expr.Num(coefficient / factorial)
                val dx = Expr.Bin(BinOp.SUB, Expr.Var(variable), Expr.Num(at))
                if (k == 1) piece = Expr.Bin(BinOp.MUL, piece, dx)
                else if (k > 1) piece = Expr.Bin(BinOp.MUL, piece, Expr.Bin(BinOp.POW, dx, Expr.Num(k.toDouble())))
                result = Expr.Bin(BinOp.ADD, result, piece)
            }
            term = derivative(term, variable)
            factorial *= (k + 1)
        }
        return simplify(result)
    }

    /** Coefficients of a polynomial in [v], low power first, or null if the expression is not polynomial. */
    fun polynomialCoefficients(e: Expr, v: String): List<Double>? {
        val expanded = expand(e)
        fun coeff(x: Expr): MutableList<Double>? {
            return when (x) {
            is Expr.Num -> mutableListOf(x.value)
            is Expr.Var -> if (x.name == v) mutableListOf(0.0, 1.0) else if (v !in x.variables()) mutableListOf(constantValue(x)) else null
            is Expr.Neg -> coeff(x.value)?.map { -it }?.toMutableList()
            is Expr.Bin -> when (x.op) {
                BinOp.ADD, BinOp.SUB -> {
                    val a = coeff(x.left) ?: return null
                    val b = coeff(x.right) ?: return null
                    val n = max(a.size, b.size)
                    MutableList(n) { i ->
                        (a.getOrNull(i) ?: 0.0) + (if (x.op == BinOp.SUB) -1 else 1) * (b.getOrNull(i) ?: 0.0)
                    }
                }
                BinOp.MUL -> {
                    val a = coeff(x.left) ?: return null
                    val b = coeff(x.right) ?: return null
                    val out = MutableList(a.size + b.size - 1) { 0.0 }
                    for (i in a.indices) for (j in b.indices) out[i + j] += a[i] * b[j]
                    out
                }
                BinOp.DIV -> {
                    val a = coeff(x.left) ?: return null
                    if (v in x.right.variables()) return null
                    val d = constantValue(x.right)
                    MutableList(a.size) { a[it] / d }
                }
                BinOp.POW -> {
                    val base = coeff(x.left) ?: return null
                    val n = (x.right as? Expr.Num)?.value ?: return null
                    if (!isInteger(n) || n < 0 || n > 12) return null
                    var acc = mutableListOf(1.0)
                    repeat(n.toInt()) {
                        val out = MutableList(acc.size + base.size - 1) { 0.0 }
                        for (i in acc.indices) for (j in base.indices) out[i + j] += acc[i] * base[j]
                        acc = out
                    }
                    acc
                }
                else -> if (v in x.variables()) null else mutableListOf(constantValue(x))
            }
            is Expr.Call -> if (v in x.variables()) null else mutableListOf(constantValue(x))
            is Expr.Factorial -> if (v in x.variables()) null else mutableListOf(constantValue(x))
            is Expr.Sum, is Expr.Product -> if (v in x.variables()) null else mutableListOf(constantValue(x))
            }
        }
        val list = coeff(expanded) ?: return null
        while (list.size > 1 && abs(list.last()) < 1e-14) list.removeAt(list.lastIndex)
        return list
    }

    /** Roots of a polynomial, real and complex, for the symbolic Solve card. */
    fun polynomialRoots(coefficients: List<Double>): List<Complex> {
        val c = coefficients.dropLastWhile { abs(it) < 1e-14 }.toMutableList()
        while (c.size > 1 && abs(c.last()) < 1e-14) c.removeAt(c.lastIndex)
        val degree = c.size - 1
        if (degree < 1) return emptyList()
        if (degree == 1) return listOf(Complex.real(-c[0] / c[1]))
        if (degree == 2) {
            val a = c[2]; val b = c[1]; val d = c[0]
            val disc = Complex.real(b * b - 4 * a * d).sqrt()
            return listOf(Complex.real(-b) + disc, Complex.real(-b) - disc).map { (it / (2.0 * a)).normalize() }
        }
        // Durand–Kerner on the monic polynomial.
        val lead = c.last()
        val monic = c.map { it / lead }
        val n = degree
        val radius = 1.0 + (monic.dropLast(1).maxOfOrNull { abs(it) } ?: 1.0)
        var roots: List<Complex> = List(n) { k ->
            Complex(radius * cos(2 * PI * k / n), radius * sin(2 * PI * k / n))
        }
        repeat(1000) {
            var changed = false
            val next = ArrayList<Complex>(n)
            for (i in 0 until n) {
                var value = Complex.ONE
                for (j in 0 until n) if (j != i) value *= roots[i] - roots[j]
                val pv = monic.foldRight(Complex.ZERO) { coeff, acc -> acc * roots[i] + Complex.real(coeff) }
                val delta = pv / value
                val r = roots[i] - delta
                if (delta.abs() > 1e-13) changed = true
                next += r
            }
            roots = next
            if (!changed) return roots.map { it.normalize() }
        }
        return roots.map { it.normalize() }
    }

    fun solve(e: Expr, variable: String): List<Complex>? {
        val coeffs = polynomialCoefficients(e, variable) ?: return null
        return polynomialRoots(coeffs)
    }

    fun limit(e: Expr, variable: String, at: Double): Double {
        if (variable !in e.variables()) return runCatching { Evaluator.evaluate(e).re }.getOrDefault(Double.NaN)
        var last = Double.NaN
        for (k in 3..10) {
            val h = 10.0.pow(-k)
            val l = runCatching { Evaluator.evaluate(e, variable, at - h).realValue() }.getOrDefault(Double.NaN)
            val r = runCatching { Evaluator.evaluate(e, variable, at + h).realValue() }.getOrDefault(Double.NaN)
            if (l.isFinite() && r.isFinite() && abs(l - r) < 1e-4 * max(1.0, abs(l))) last = (l + r) / 2.0
        }
        return last
    }

    private fun Complex.normalize(): Complex {
        val re = if (abs(re) < 1e-12) 0.0 else re
        val im = if (abs(im) < 1e-12) 0.0 else im
        return Complex(re, im)
    }

    /** Human-readable result for a solved variable. */
    fun formatRoots(roots: List<Complex>, fractions: Boolean = false): String =
        roots.joinToString(", ") { if (fractions) Numbers.formatComplexFraction(it) else Numbers.formatComplex(it) }
}
