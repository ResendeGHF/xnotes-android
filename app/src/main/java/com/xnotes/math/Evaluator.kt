package com.xnotes.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

enum class AngleMode(val label: String) { RADIANS("RAD"), DEGREES("DEG") }

class EvalException(message: String) : Exception(message)

/** Mutable bindings for one evaluation. A sum temporarily binds its index and restores it after. */
class EvalScope(val angle: AngleMode = AngleMode.RADIANS) {
    val vars: HashMap<String, Complex> = HashMap()
    var maxIterations: Int = 2_000_000
    var ans: Complex = Complex.ZERO

    fun set(name: String, value: Double): EvalScope { vars[name] = Complex.real(value); return this }
    fun set(name: String, value: Complex): EvalScope { vars[name] = value; return this }
    fun copy(): EvalScope = EvalScope(angle).also { it.vars.putAll(vars); it.maxIterations = maxIterations; it.ans = ans }
}

/**
 * Numeric evaluation of the shared [Expr] tree. Real and complex results follow the same code path;
 * a plot takes the real part only when the imaginary part is negligible, so a complex branch shows
 * as a gap rather than as a misleading curve.
 */
object Evaluator {

    fun evaluate(expr: Expr, scope: EvalScope = EvalScope()): Complex = when (expr) {
        is Expr.Num -> Complex.real(expr.value)
        is Expr.Var -> resolve(expr.name, scope)
        is Expr.Neg -> -evaluate(expr.value, scope)
        is Expr.Bin -> binary(expr, scope)
        is Expr.Call -> call(expr.name, expr.args, scope)
        is Expr.Factorial -> Special.gamma(evaluate(expr.value, scope) + Complex.ONE)
        is Expr.Sum -> sum(expr, scope)
        is Expr.Product -> product(expr, scope)
    }

    /** Convenience for plots and the calculator: no user variables besides the ones given. */
    fun evaluate(expr: Expr, variables: Map<String, Double>, angle: AngleMode = AngleMode.RADIANS): Complex {
        val scope = EvalScope(angle)
        for ((k, v) in variables) scope.vars[k] = Complex.real(v)
        return evaluate(expr, scope)
    }

    fun evaluate(expr: Expr, varName: String, value: Double, angle: AngleMode = AngleMode.RADIANS): Complex {
        val scope = EvalScope(angle)
        scope.vars[varName] = Complex.real(value)
        return evaluate(expr, scope)
    }

    /** A real sample, or NaN if the expression strayed off the real axis at this point. */
    fun real(expr: Expr, variables: Map<String, Double>, angle: AngleMode = AngleMode.RADIANS): Double =
        evaluate(expr, variables, angle).realValue()

    fun real(expr: Expr, varName: String, value: Double, angle: AngleMode = AngleMode.RADIANS): Double =
        evaluate(expr, varName, value, angle).realValue()

    private fun resolve(name: String, scope: EvalScope): Complex {
        scope.vars[name]?.let { return it }
        val lower = name.lowercase()
        scope.vars[lower]?.let { return it }
        Constants.values[lower]?.let { return it }
        if (lower == "ans") return scope.ans
        throw EvalException("Unknown symbol '$name'")
    }

    private fun binary(expr: Expr.Bin, scope: EvalScope): Complex {
        if (expr.op == BinOp.AND || expr.op == BinOp.OR) {
            val l = evaluate(expr.left, scope).realValue() != 0.0
            if (expr.op == BinOp.AND) {
                return if (!l) Complex.ZERO else if (evaluate(expr.right, scope).realValue() != 0.0) Complex.ONE else Complex.ZERO
            }
            return if (l) Complex.ONE else if (evaluate(expr.right, scope).realValue() != 0.0) Complex.ONE else Complex.ZERO
        }
        val a = evaluate(expr.left, scope)
        val b = evaluate(expr.right, scope)
        return when (expr.op) {
            BinOp.ADD -> a + b
            BinOp.SUB -> a - b
            BinOp.MUL -> a * b
            BinOp.DIV -> a / b
            BinOp.MOD -> {
                val av = a.realValue()
                val bv = b.realValue()
                if (av.isNaN() || bv.isNaN() || bv == 0.0) Complex.NaN else Complex.real(av - bv * floor(av / bv))
            }
            BinOp.POW -> {
                if (a.im == 0.0 && b.im == 0.0 && b.re == Math.floor(b.re) && abs(b.re) < 4096.0) {
                    val n = b.re.toInt()
                    if (n >= 0) {
                        var r = Complex.ONE
                        var base = a
                        var k = n
                        while (k > 0) {
                            if (k and 1 == 1) r *= base
                            base *= base
                            k = k shr 1
                        }
                        r
                    } else Complex.ONE / a.pow(Complex.real(-b.re))
                } else a.pow(b)
            }
            BinOp.EQ -> bool(a.realValue() == b.realValue())
            BinOp.NE -> bool(a.realValue() != b.realValue())
            BinOp.LT -> bool(a.realValue() < b.realValue())
            BinOp.LE -> bool(a.realValue() <= b.realValue())
            BinOp.GT -> bool(a.realValue() > b.realValue())
            BinOp.GE -> bool(a.realValue() >= b.realValue())
            BinOp.AND, BinOp.OR -> Complex.ZERO
        }
    }

    private fun bool(b: Boolean): Complex = if (b) Complex.ONE else Complex.ZERO

    private fun sum(expr: Expr.Sum, scope: EvalScope): Complex {
        val start = evaluate(expr.from, scope).realValue().roundToIntOrThrow("sum lower bound")
        val end = evaluate(expr.to, scope).realValue().roundToIntOrThrow("sum upper bound")
        if (end < start) return Complex.ZERO
        if (end - start > scope.maxIterations) throw EvalException("Sum has too many terms")
        val old = scope.vars[expr.variable]
        var acc = Complex.ZERO
        try {
            for (n in start..end) {
                scope.vars[expr.variable] = Complex.real(n.toDouble())
                acc += evaluate(expr.body, scope)
            }
        } finally {
            if (old == null) scope.vars.remove(expr.variable) else scope.vars[expr.variable] = old
        }
        return acc
    }

    private fun product(expr: Expr.Product, scope: EvalScope): Complex {
        val start = evaluate(expr.from, scope).realValue().roundToIntOrThrow("product lower bound")
        val end = evaluate(expr.to, scope).realValue().roundToIntOrThrow("product upper bound")
        if (end < start) return Complex.ONE
        if (end - start > scope.maxIterations) throw EvalException("Product has too many terms")
        val old = scope.vars[expr.variable]
        var acc = Complex.ONE
        try {
            for (n in start..end) {
                scope.vars[expr.variable] = Complex.real(n.toDouble())
                acc *= evaluate(expr.body, scope)
            }
        } finally {
            if (old == null) scope.vars.remove(expr.variable) else scope.vars[expr.variable] = old
        }
        return acc
    }

    private fun call(name: String, args: List<Expr>, scope: EvalScope): Complex {
        return when (name.lowercase()) {
            "if" -> {
                requireArgs(name, args, 3)
                val c = evaluate(args[0], scope).realValue()
                if (c != 0.0 && !c.isNaN()) evaluate(args[1], scope) else evaluate(args[2], scope)
            }
            "integral", "integrate" -> {
                requireArgs(name, args, 4)
                val variable = (args[1] as? Expr.Var)?.name ?: throw EvalException("integral needs a variable")
                val a = evaluate(args[2], scope).realValue()
                val b = evaluate(args[3], scope).realValue()
                val old = scope.vars[variable]
                val fn: (Double) -> Double = { t ->
                    scope.vars[variable] = Complex.real(t)
                    evaluate(args[0], scope).realValue()
                }
                // Endpoints at ±infinity switch to a compactifying change of variables.
                val result = if (a.isFinite() && b.isFinite()) Numerical.adaptiveSimpson(fn, a, b, 1e-10, 20)
                else Numerical.improperQuad(fn, a, b, 1e-10)
                if (old == null) scope.vars.remove(variable) else scope.vars[variable] = old
                Complex.real(result.value)
            }
            "derivative", "diff" -> {
                if (args.size != 3 && args.size != 4) throw EvalException("derivative expects 3 arguments")
                val variable = (args[1] as? Expr.Var)?.name ?: throw EvalException("derivative needs a variable")
                val at = evaluate(args[2], scope).realValue()
                val order = if (args.size == 4) evaluate(args[3], scope).realValue().roundToIntOrThrow("derivative order") else 1
                val old = scope.vars[variable]
                val result = Numerical.derivative({ t ->
                    scope.vars[variable] = Complex.real(t)
                    evaluate(args[0], scope).realValue()
                }, at, order)
                if (old == null) scope.vars.remove(variable) else scope.vars[variable] = old
                Complex.real(result)
            }
            "limit" -> {
                requireArgs(name, args, 3)
                val variable = (args[1] as? Expr.Var)?.name ?: throw EvalException("limit needs a variable")
                val at = evaluate(args[2], scope).realValue()
                val old = scope.vars[variable]
                val result = limitValue(at) { t ->
                    scope.vars[variable] = Complex.real(t)
                    evaluate(args[0], scope).realValue()
                }
                if (old == null) scope.vars.remove(variable) else scope.vars[variable] = old
                Complex.real(result)
            }
            else -> normalCall(name, args, scope)
        }
    }

    private fun normalCall(name: String, args: List<Expr>, scope: EvalScope): Complex {
        val v = args.map { evaluate(it, scope) }
        val x = v.getOrNull(0) ?: Complex.NaN
        val y = v.getOrNull(1) ?: Complex.ZERO
        val z = v.getOrNull(2) ?: Complex.ZERO
        val rad = if (scope.angle == AngleMode.DEGREES) PI / 180.0 else 1.0
        val invRad = if (scope.angle == AngleMode.DEGREES) 180.0 / PI else 1.0
        return when (name.lowercase()) {
            "abs", "absval" -> Complex.real(x.abs())
            "sign", "sgn" -> Complex.real(x.realValue().sign)
            "floor" -> Complex.real(floor(x.realValue()))
            "ceil", "ceiling" -> Complex.real(ceil(x.realValue()))
            "round" -> Complex.real(round(x.realValue()))
            "trunc" -> Complex.real(if (x.realValue() >= 0) floor(x.realValue()) else ceil(x.realValue()))
            "frac" -> { val r = x.realValue(); Complex.real(r - floor(r)) }
            "min" -> Complex.real(v.minOfOrNull { it.realValue() } ?: Double.NaN)
            "max" -> Complex.real(v.maxOfOrNull { it.realValue() } ?: Double.NaN)
            "mod", "rem" -> { val a = x.realValue(); val b = y.realValue(); Complex.real(if (b == 0.0) Double.NaN else a - b * floor(a / b)) }
            "hypot" -> Complex.real(hypot(x.realValue(), y.realValue()))
            "clamp" -> Complex.real(x.realValue().coerceIn(y.realValue(), z.realValue()))
            "sqrt" -> x.sqrt()
            "cbrt" -> Complex.real(if (x.im == 0.0) sign(x.re) * abs(x.re).pow(1.0 / 3.0) else x.pow(Complex.real(1.0 / 3.0)).re)
            "root" -> {
                if (y.im == 0.0 && y.re == 2.0) x.sqrt()
                else if (x.im == 0.0 && y.im == 0.0 && y.re == floor(y.re) && y.re.toInt() % 2 != 0) {
                    Complex.real(sign(x.re) * abs(x.re).pow(1.0 / y.re))
                } else x.pow(Complex.ONE / y)
            }
            "exp" -> x.exp()
            "ln" -> x.log()
            "log", "log10" -> if (v.size >= 2) x.log(y) else Complex.real(log10(x.realValue()))
            "log2" -> Complex.real(log2(x.realValue()))
            "logn", "logb" -> if (v.size >= 2) x.log(y) else x.log()
            "sinc" -> {
                if (x.abs() < 1e-12) Complex.ONE else x.sin() / x
            }
            "sin" -> (x * Complex.real(rad)).sin()
            "cos" -> (x * Complex.real(rad)).cos()
            "tan" -> (x * Complex.real(rad)).tan()
            "cot" -> (x * Complex.real(rad)).cot()
            "sec" -> (x * Complex.real(rad)).sec()
            "csc" -> (x * Complex.real(rad)).csc()
            "asin", "arcsin" -> (x.asin() / Complex.real(rad))
            "acos", "arccos" -> (x.acos() / Complex.real(rad))
            "atan", "arctan" -> (x.atan() / Complex.real(rad))
            "atan2", "arctan2" -> Complex.real(atan2(x.realValue(), y.realValue()) * invRad)
            "acot", "arccot" -> (Complex(PI / 2.0, 0.0) - x.atan()) / Complex.real(rad)
            "asec" -> (Complex.ONE / x).acos() / Complex.real(rad)
            "acsc" -> (Complex.ONE / x).asin() / Complex.real(rad)
            "sinh" -> x.sinh()
            "cosh" -> x.cosh()
            "tanh" -> x.tanh()
            "coth" -> x.coth()
            "sech" -> x.sech()
            "csch" -> x.csch()
            "asinh", "arcsinh" -> x.asinh()
            "acosh", "arccosh" -> x.acosh()
            "atanh", "arctanh" -> x.atanh()

            "gamma" -> Special.gamma(x)
            "lgamma", "lngamma" -> Special.lnGamma(x)
            "digamma" -> Special.digamma(x)
            "polygamma" -> Complex.real(Special.polygamma(y.realValue().toInt(), x.realValue()))
            "beta" -> Complex.real(Special.beta(x.realValue(), y.realValue()))
            "lnbeta" -> Complex.real(Special.lnBeta(x.realValue(), y.realValue()))
            "factorial" -> Special.gamma(x + Complex.ONE)
            "doublefactorial" -> Complex.real(Special.doubleFactorial(x.realValue()))
            "binomial", "choose", "ncr" -> Complex.real(Special.binomial(x.realValue(), y.realValue()))
            "rising", "pochhammer" -> Complex.real(Special.risingFactorial(x.realValue(), y.realValue()))
            "falling" -> Complex.real(Special.fallingFactorial(x.realValue(), y.realValue()))
            "harmonic" -> Complex.real(Special.harmonic(x.realValue()))
            "erf" -> Special.erf(x)
            "erfc" -> Special.erfc(x)
            "erfi" -> Complex.real(Special.erfi(x.realValue()))
            "erfcx" -> Complex.real(Special.erfcx(x.realValue()))
            "erfinv" -> Complex.real(erfinv(x.realValue()))
            "erfcinv" -> Complex.real(erfcinv(x.realValue()))
            "dawson" -> Complex.real(Special.dawson(x.realValue()))
            "normalcdf" -> Complex.real(0.5 * Special.erfc(-x.realValue() / sqrt(2.0)))
            "normalpdf" -> Complex.real(exp(-0.5 * x.realValue() * x.realValue()) / sqrt(2.0 * PI))
            "logistic" -> {
                val t = x.realValue()
                Complex.real(if (t >= 0) 1.0 / (1.0 + exp(-t)) else exp(t) / (1.0 + exp(t)))
            }
            "softplus" -> {
                val t = x.realValue()
                Complex.real(if (t > 0) t + ln(1.0 + exp(-t)) else ln(1.0 + exp(t)))
            }
            "fresnels" -> Complex.real(Special.fresnelS(x.realValue()))
            "fresnelc" -> Complex.real(Special.fresnelC(x.realValue()))
            "zeta" -> Special.zeta(x)
            "hurwitzzeta", "zetah" -> Special.hurwitzZeta(x, y)
            "eta" -> Special.eta(x)
            "dirichletbeta" -> Complex.real(Special.dirichletBeta(x.realValue()))
            "riemannxi", "xi_riemann" -> Special.riemannXi(x)
            "polylog" -> Complex.real(Special.polylog(y.realValue(), x.realValue()))
            "dilog" -> Complex.real(Special.polylog(2.0, x.realValue()))
            "li" -> Complex.real(Special.logarithmicIntegral(x.realValue()))
            "ei" -> Complex.real(Special.expIntegralEi(x.realValue()))
            "e1" -> Complex.real(Special.expIntegralE1(x.realValue()))
            "si" -> Complex.real(Special.sinIntegral(x.realValue()))
            "ci" -> Complex.real(Special.cosIntegral(x.realValue()))
            "shi" -> Complex.real(Special.sinhIntegral(x.realValue()))
            "chi" -> Complex.real(Special.coshIntegral(x.realValue()))
            "lambertw" -> Complex.real(Special.lambertW(x.realValue(), if (v.size >= 2) y.realValue().toInt() else 0))
            "agm" -> Complex.real(Special.agm(x.realValue(), y.realValue()))
            "bernoulli" -> Complex.real(Special.bernoulli(x.realValue().toInt()))
            "incompletegamma", "gammainc" -> Complex.real(Special.incompleteGamma(x.realValue(), y.realValue()))
            "uppergamma", "gammaincupper" -> Complex.real(Special.upperIncompleteGamma(x.realValue(), y.realValue()))
            "gammap" -> Complex.real(Special.regularizedGammaP(x.realValue(), y.realValue()))
            "gammaq" -> Complex.real(1.0 - Special.regularizedGammaP(x.realValue(), y.realValue()))
            "lnfactorial" -> Special.lnGamma(x + Complex.ONE)
            "incompletebeta", "betainc" -> Complex.real(Special.incompleteBeta(x.realValue(), y.realValue(), z.realValue()))
            "hypergeom", "2f1" -> Complex.real(Special.hypergeometric2F1(x.realValue(), y.realValue(), z.realValue(), v.getOrElse(3) { Complex.ZERO }.realValue()))

            "besselj", "jn" -> Complex.real(Special.besselJ(x.realValue(), y.realValue()))
            "bessely", "yn" -> Complex.real(if (x.realValue() == floor(x.realValue())) Special.besselYInt(x.realValue().toInt(), y.realValue()) else Double.NaN)
            "besseli", "in" -> Complex.real(if (x.realValue() == floor(x.realValue())) Special.besselI(x.realValue().toInt(), y.realValue()) else Double.NaN)
            "besselk", "kn" -> Complex.real(if (x.realValue() == floor(x.realValue())) Special.besselK(x.realValue().toInt(), y.realValue()) else Double.NaN)
            "sphericalj" -> Complex.real(Special.sphericalBesselJ(x.realValue().toInt(), y.realValue()))
            "sphericaly" -> Complex.real(Special.sphericalBesselY(x.realValue().toInt(), y.realValue()))
            "hankel1" -> Special.hankel1(x.realValue().toInt(), y.realValue())
            "hankel2" -> Special.hankel2(x.realValue().toInt(), y.realValue())
            "airy", "airyai" -> Complex.real(Special.airyAi(x.realValue()))
            "airybi" -> Complex.real(Special.airyBi(x.realValue()))
            "airyaiprime" -> Complex.real(Special.airyAiPrime(x.realValue()))
            "airybiprime" -> Complex.real(Special.airyBiPrime(x.realValue()))
            "elliptick" -> Complex.real(Special.ellipticK(x.realValue()))
            "elliptice" -> Complex.real(Special.ellipticE(x.realValue()))
            "ellipticpi" -> Complex.real(Special.ellipticPi(x.realValue(), y.realValue()))
            "legendrep" -> Complex.real(Special.legendreP(x.realValue().toInt(), y.realValue()))
            "legendreq" -> Complex.real(Special.legendreQ(x.realValue().toInt(), y.realValue()))
            "hermite" -> Complex.real(Special.hermite(x.realValue().toInt(), y.realValue()))
            "laguerre" -> Complex.real(Special.laguerre(x.realValue().toInt(), y.realValue()))
            "chebyshevt" -> Complex.real(Special.chebyshevT(x.realValue().toInt(), y.realValue()))
            "chebyshevu" -> Complex.real(Special.chebyshevU(x.realValue().toInt(), y.realValue()))
            "laguerrel" -> Complex.real(Special.laguerre(x.realValue().toInt(), y.realValue(), z.realValue()))
            "associatedlegendre" -> Complex.real(Special.associatedLegendreP(x.realValue().toInt(), y.realValue().toInt(), z.realValue()))
            "gegenbauer" -> Complex.real(Special.gegenbauer(x.realValue().toInt(), y.realValue(), z.realValue()))
            "jacobi" -> Complex.real(
                Special.jacobi(
                    x.realValue().toInt(),
                    y.realValue(),
                    z.realValue(),
                    v.getOrElse(3) { Complex.ZERO }.realValue(),
                ),
            )

            "gcd" -> Complex.real(Special.gcd(x.realValue(), y.realValue()))
            "lcm" -> Complex.real(Special.lcm(x.realValue(), y.realValue()))
            "isprime" -> Complex.real(Special.isPrime(x.realValue()))
            "prime" -> Complex.real(Special.nthPrime(x.realValue()))
            "totient" -> Complex.real(Special.totient(x.realValue()))
            "mobius" -> Complex.real(Special.mobius(x.realValue()))
            "sigma" -> Complex.real(Special.divisorSigma(x.realValue(), if (v.size >= 2) y.realValue() else 1.0))
            "fib", "fibonacci" -> Complex.real(Special.fibonacci(x.realValue()))
            "partitions" -> Complex.real(Special.partitionNumber(x.realValue()))

            "heaviside", "step" -> Complex.real(if (x.realValue() < 0) 0.0 else 1.0)
            "square" -> Complex.real(sign(sin(x.realValue())))
            "triangle" -> {
                val t = ((x.realValue() + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
                Complex.real(1.0 - 2.0 * abs(t) / PI)
            }
            "sawtooth" -> {
                val t = ((x.realValue() + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
                Complex.real(t / PI)
            }
            "gauss", "gaussian" -> {
                val mu = y.realValue()
                val sigma = z.realValue()
                Complex.real(exp(-0.5 * ((x.realValue() - mu) / sigma).pow(2)))
            }
            "lorentzian" -> {
                val x0 = y.realValue()
                val gamma = z.realValue()
                Complex.real(1.0 / (1.0 + ((x.realValue() - x0) / gamma).pow(2)))
            }
            "bump" -> {
                val a = y.realValue()
                val b = z.realValue()
                val t = x.realValue()
                if (t <= a || t >= b || a == b) Complex.ZERO
                else {
                    val u = (t - a) / (b - a)
                    Complex.real(exp(-1.0 / (u * (1.0 - u))))
                }
            }
            else -> throw EvalException("Unknown function '$name'")
        }
    }

    private fun erfinv(y: Double): Double {
        if (abs(y) >= 1.0) return if (abs(y) == 1.0) sign(y) * Double.POSITIVE_INFINITY else Double.NaN
        var x = 0.0
        var lo = -6.0
        var hi = 6.0
        repeat(100) {
            x = (lo + hi) / 2.0
            if (Special.erf(x) < y) lo = x else hi = x
        }
        return (lo + hi) / 2.0
    }

    private fun erfcinv(y: Double): Double = erfinv(1.0 - y)

    private fun limitValue(at: Double, f: (Double) -> Double): Double {
        if (at.isInfinite()) {
            val sign = if (at > 0) 1.0 else -1.0
            val values = (1..8).mapNotNull { f(sign * 10.0.pow(it)).takeIf(Double::isFinite) }
            return values.lastOrNull() ?: Double.NaN
        }
        val values = ArrayList<Double>()
        for (k in 3..10) {
            val h = 10.0.pow(-k)
            val l = f(at - h)
            val r = f(at + h)
            if (l.isFinite() && r.isFinite() && abs(l - r) < 1e-4 * max(1.0, abs(l))) values += (l + r) / 2.0
        }
        return values.lastOrNull() ?: f(at)
    }

    private fun requireArgs(name: String, args: List<Expr>, count: Int) {
        if (args.size != count) throw EvalException("$name expects $count arguments")
    }

    private fun Double.roundToIntOrThrow(what: String): Int {
        if (!isFinite() || abs(this) > 1e7) throw EvalException("Bad $what")
        return round(this).toInt()
    }

    /** Physical and mathematical constants, resolved after user variables. */
    object Constants {
        val values: Map<String, Complex> = mapOf(
            "pi" to Complex(PI, 0.0),
            "π" to Complex(PI, 0.0),
            "tau" to Complex(2 * PI, 0.0),
            "e" to Complex(kotlin.math.E, 0.0),
            "i" to Complex(0.0, 1.0),
            "j" to Complex(0.0, 1.0),
            "phi" to Complex(1.618033988749895, 0.0),
            "goldenratio" to Complex(1.618033988749895, 0.0),
            "gamma_e" to Complex(0.5772156649015329, 0.0),
            "euler" to Complex(0.5772156649015329, 0.0),
            "inf" to Complex.INF,
            "infinity" to Complex.INF,
            "infty" to Complex.INF,
            "oo" to Complex.INF,
            "nan" to Complex.NaN,
            "c" to Complex(299792458.0, 0.0),
            "hbar" to Complex(1.054571817e-34, 0.0),
            "h" to Complex(6.62607015e-34, 0.0),
            "g" to Complex(6.67430e-11, 0.0),
            "kb" to Complex(1.380649e-23, 0.0),
            "na" to Complex(6.02214076e23, 0.0),
            "r" to Complex(8.314462618, 0.0),
            "qe" to Complex(1.602176634e-19, 0.0),
            "me" to Complex(9.1093837015e-31, 0.0),
            "mp" to Complex(1.67262192369e-27, 0.0),
            "mn" to Complex(1.67492749804e-27, 0.0),
            "mu0" to Complex(1.25663706212e-6, 0.0),
            "eps0" to Complex(8.8541878128e-12, 0.0),
            "alpha_fs" to Complex(0.0072973525693, 0.0),
            "bohr" to Complex(5.29177210903e-11, 0.0),
            "ry" to Complex(13.605693122994, 0.0),
            "ev" to Complex(1.602176634e-19, 0.0),
            "sigma_sb" to Complex(5.670374419e-8, 0.0),
        )
    }
}
