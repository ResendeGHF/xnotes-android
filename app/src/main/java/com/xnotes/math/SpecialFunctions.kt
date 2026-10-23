package com.xnotes.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
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

/**
 * The special-function library. Real-valued functions are written in double precision for speed;
 * the functions that matter in the complex plane (Γ, ψ, ζ and the Bessel J series) also have
 * complex overloads. Every function returns NaN where a real input leaves the real domain, rather
 * than silently switching to a complex answer the plot cannot draw.
 */
object Special {

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7,
    )
    private const val LANCZOS_G = 7.0

    fun gamma(z: Complex): Complex {
        if (z.im == 0.0 && z.re <= 0.0 && isInteger(z.re)) return Complex.INF
        if (z.re < 0.5) {
            val oneMinus = Complex(1.0, 0.0) - z
            val s = (Complex(PI, 0.0) * z).sin()
            return Complex(PI, 0.0) / (s * gamma(oneMinus))
        }
        val zz = z - Complex(1.0, 0.0)
        var x = Complex(LANCZOS[0], 0.0)
        for (k in 1 until LANCZOS.size) x += Complex(LANCZOS[k], 0.0) / (zz + Complex(k.toDouble(), 0.0))
        val t = zz + Complex(LANCZOS_G + 0.5, 0.0)
        return (Complex(2.0 * PI, 0.0).sqrt()) * t.pow(zz + Complex(0.5, 0.0)) *
            (-t).exp().let { Complex(it.re, it.im) } * x
    }

    fun gamma(x: Double): Double = gamma(Complex(x, 0.0)).realValue()

    fun lnGamma(z: Complex): Complex {
        if (z.im == 0.0 && z.re <= 0.0 && isInteger(z.re)) return Complex.INF
        if (z.re < 0.5) {
            return Complex(ln(PI), 0.0) - (Complex(PI, 0.0) * z).sin().log() - lnGamma(Complex(1.0, 0.0) - z)
        }
        val zz = z - Complex(1.0, 0.0)
        var x = Complex(LANCZOS[0], 0.0)
        for (k in 1 until LANCZOS.size) x += Complex(LANCZOS[k], 0.0) / (zz + Complex(k.toDouble(), 0.0))
        val t = zz + Complex(LANCZOS_G + 0.5, 0.0)
        return Complex(0.5 * ln(2.0 * PI), 0.0) + (zz + Complex(0.5, 0.0)) * t.log() - t + x.log()
    }

    fun lnGamma(x: Double): Double = lnGamma(Complex(x, 0.0)).realValue()

    fun digamma(z: Complex): Complex {
        if (z.im == 0.0 && z.re <= 0.0 && isInteger(z.re)) return Complex.INF
        if (z.re < 0.5) {
            return digamma(Complex(1.0, 0.0) - z) - Complex(PI, 0.0) / (Complex(PI, 0.0) * z).tan()
        }
        var x = z
        var result = Complex.ZERO
        while (x.re < 8.0) {
            result -= Complex.ONE / x
            x += Complex.ONE
        }
        val inv = Complex.ONE / x
        val inv2 = inv * inv
        result += x.log() - inv * 0.5
        result -= inv2 * (Complex.ONE / 12.0 - inv2 * (Complex.ONE / 120.0 - inv2 * (Complex.ONE / 252.0 - inv2 * (Complex.ONE / 240.0 - inv2 / 132.0))))
        return result
    }

    fun digamma(x: Double): Double = digamma(Complex(x, 0.0)).realValue()

    fun polygamma(order: Int, x: Double): Double {
        if (order < 0 || !x.isFinite()) return Double.NaN
        if (order == 0) return digamma(x)
        var xx = x
        var shift = 0.0
        // ψ⁽ᵐ⁾(x) = ψ⁽ᵐ⁾(x+1) + (-1)^m m!/x^(m+1)
        while (xx < 12.0) {
            shift += if (order % 2 == 0) -1.0 else 1.0
            shift += 0.0
            xx += 1.0
        }
        var sum = 0.0
        var k = 0
        while (k < 200) {
            val t = 1.0 / (xx + k).pow(order + 1)
            sum += t
            if (t < 1e-16 * abs(sum)) break
            k++
        }
        val tail = if (order % 2 == 0) -1.0 else 1.0
        var value = tail * factorialReal(order) * sum
        // Add back the recurrence steps, from the shifted point down to x.
        var p = x
        while (p < xx - 0.5) {
            value += (if (order % 2 == 0) 1.0 else -1.0) * factorialReal(order) / p.pow(order + 1)
            p += 1.0
        }
        return value
    }

    fun beta(a: Double, b: Double): Double = exp(lnBeta(a, b))
    fun lnBeta(a: Double, b: Double): Double = lnGamma(a) + lnGamma(b) - lnGamma(a + b)

    /** Error function: power series near zero, a continued-fraction erfc for the tail. */
    fun erf(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        if (x == 0.0) return 0.0
        val ax = abs(x)
        if (ax < 2.5) {
            var term = x
            var sum = x
            val x2 = x * x
            var n = 1
            while (n < 100) {
                term *= -x2 / n
                val add = term / (2 * n + 1)
                sum += add
                if (abs(add) < 1e-18 * abs(sum)) break
                n++
            }
            return 2.0 / sqrt(PI) * sum
        }
        val tail = erfcAsymptotic(ax)
        return if (x > 0) 1.0 - tail else tail - 1.0
    }

    fun erfc(x: Double): Double = 1.0 - erf(x)
    fun erfcAsymptotic(x: Double): Double {
        if (x <= 0) return 1.0
        val t = 1.0 / (1.0 + 0.5 * x)
        return t * exp(
            -x * x - 1.26551223 +
                t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                    t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
                        t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))),
        )
    }

    /** Scaled complementary error function exp(x²) erfc(x), stable in the large-positive tail. */
    fun erfcx(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        if (x < 0.0) return exp(x * x) * erfc(x)
        if (x < 8.0) return exp(x * x) * erfc(x)
        // Asymptotic series 1/(x√π)·Σ (−1)ⁿ (2n−1)!!/(2x²)ⁿ for the scaled tail.
        val inv = 1.0 / (2.0 * x * x)
        var term = 1.0
        var sum = 1.0
        var n = 1
        while (n < 40) {
            term *= -(2.0 * n - 1.0) * inv
            sum += term
            if (abs(term) < 1e-17) break
            n++
        }
        return sum / (x * sqrt(PI))
    }

    fun erf(z: Complex): Complex {
        if (z.im == 0.0) return Complex(erf(z.re), 0.0)
        if (z.abs() > 25.0) return Complex(sign(z.re), 0.0)
        var term = z
        var sum = z
        var n = 1
        val z2 = z * z
        while (n < 200) {
            term = term * (-z2) / Complex(n.toDouble(), 0.0)
            val add = term / Complex((2 * n + 1).toDouble(), 0.0)
            sum += add
            if (add.abs() < 1e-17 * sum.abs()) break
            n++
        }
        return sum * Complex(2.0 / sqrt(PI), 0.0)
    }

    fun erfc(z: Complex): Complex = Complex.ONE - erf(z)

    fun erfi(x: Double): Double {
        if (!x.isFinite()) return x
        var term = x
        var sum = x
        val x2 = x * x
        var n = 1
        while (n < 200) {
            term *= x2 / n
            val add = term / (2 * n + 1)
            sum += add
            if (abs(add) < 1e-18 * abs(sum)) break
            n++
        }
        return 2.0 / sqrt(PI) * sum
    }

    fun dawson(x: Double): Double {
        if (!x.isFinite()) return 0.0
        if (abs(x) < 3.0) {
            var term = x
            var sum = x
            val x2 = x * x
            var n = 0
            while (n < 120) {
                n++
                term *= -2.0 * x2 / (2 * n + 1)
                sum += term
                if (abs(term) < 1e-18 * abs(sum)) break
            }
            return exp(-x2) * sum
        }
        // Asymptotic 1/(2x) + 1/(4x³) + ...
        val x2 = x * x
        return (1.0 / (2.0 * x)) * (1.0 + 1.0 / (2.0 * x2) + 3.0 / (4.0 * x2 * x2))
    }

    fun fresnelS(x: Double): Double {
        if (!x.isFinite()) return Double.NaN
        var term = x
        var sum = 0.0
        val x2 = x * x
        var n = 0
        while (n < 200) {
            val add = term / (2 * n + 1)
            sum += add
            if (abs(add) < 1e-18 * max(1.0, abs(sum))) break
            term *= -x2 * PI / 2.0 / ((2 * n + 1) * (2 * n + 2))
            n++
        }
        return sum
    }

    fun fresnelC(x: Double): Double {
        if (!x.isFinite()) return Double.NaN
        var term = x
        var sum = 0.0
        val x2 = x * x
        var n = 0
        while (n < 200) {
            val add = term / (2 * n)
            if (n > 0) sum += add
            if (n > 0 && abs(add) < 1e-18 * max(1.0, abs(sum))) break
            term *= -x2 * PI / 2.0 / ((2 * n + 1) * (2 * n + 2))
            n++
        }
        return sum
    }

    fun zeta(z: Complex): Complex {
        if (z.im == 0.0 && z.re == 1.0) return Complex.INF
        if (z.re < 0.5) {
            val oneMinus = Complex(1.0, 0.0) - z
            return Complex(2.0, 0.0).pow(z) * Complex(PI, 0.0).pow(z - Complex.ONE) *
                (Complex(PI / 2.0, 0.0) * z).sin() * gamma(oneMinus) * zeta(oneMinus)
        }
        val n = 40
        var sum = Complex.ZERO
        for (k in 1 until n) sum += Complex(k.toDouble(), 0.0).pow(-z)
        val nz = Complex(n.toDouble(), 0.0)
        sum += nz.pow(Complex.ONE - z) / (z - Complex.ONE)
        sum += nz.pow(-z) * 0.5
        var rising = Complex.ONE
        for (k in 1..12) {
            rising = if (k == 1) z else rising * (z + Complex((2 * k - 3).toDouble(), 0.0)) *
                (z + Complex((2 * k - 2).toDouble(), 0.0))
            val b = bernoulli(2 * k)
            sum += rising * (nz.pow(-z - Complex((2 * k - 1).toDouble(), 0.0))) *
                Complex(b / factorialReal(2 * k), 0.0)
        }
        return sum
    }

    fun zeta(x: Double): Double = zeta(Complex(x, 0.0)).realValue()

    fun hurwitzZeta(s: Complex, a: Complex): Complex {
        val n = 40
        var sum = Complex.ZERO
        for (k in 0 until n) sum += (a + Complex(k.toDouble(), 0.0)).pow(-s)
        val nz = a + Complex(n.toDouble(), 0.0)
        sum += nz.pow(Complex.ONE - s) / (s - Complex.ONE)
        sum += nz.pow(-s) * 0.5
        var rising = Complex.ONE
        for (k in 1..12) {
            rising = if (k == 1) s else rising * (s + Complex((2 * k - 3).toDouble(), 0.0)) *
                (s + Complex((2 * k - 2).toDouble(), 0.0))
            sum += rising * nz.pow(-s - Complex((2 * k - 1).toDouble(), 0.0)) *
                Complex(bernoulli(2 * k) / factorialReal(2 * k), 0.0)
        }
        return sum
    }

    fun hurwitzZeta(s: Double, a: Double): Double = hurwitzZeta(Complex(s, 0.0), Complex(a, 0.0)).realValue()

    fun eta(z: Complex): Complex = (Complex.ONE - Complex(2.0, 0.0).pow(Complex.ONE - z)) * zeta(z)
    fun eta(x: Double): Double = eta(Complex(x, 0.0)).realValue()

    /** Dirichlet beta β(s)=Σ(−1)ⁿ/(2n+1)ˢ, via Hurwitz zeta at one quarter and three quarters. */
    fun dirichletBeta(s: Double): Double {
        if (!s.isFinite() || s <= 0.0) return Double.NaN
        if (s == 1.0) return PI / 4.0
        return 0.25.pow(s) * (hurwitzZeta(s, 0.25) - hurwitzZeta(s, 0.75))
    }

    /** Riemann xi ξ(s)=½s(s−1)π^(−s/2)Γ(s/2)ζ(s), entire and symmetric about s=½. */
    fun riemannXi(s: Complex): Complex {
        if (s.re == 0.0 && s.im == 0.0) return Complex(0.5, 0.0)
        if (s.re == 1.0 && s.im == 0.0) return Complex(0.5, 0.0)
        val half = s * Complex(0.5, 0.0)
        return half * (s - Complex.ONE) * Complex(PI, 0.0).pow(-half) * gamma(half) * zeta(s)
    }

    fun riemannXi(x: Double): Double = riemannXi(Complex(x, 0.0)).realValue()

    fun besselJ(nu: Complex, z: Complex): Complex {
        if (z.abs() < 1e-14) return if (nu.abs() < 1e-14) Complex.ONE else Complex.ZERO
        val half = z / Complex(2.0, 0.0)
        var term = Complex.ONE
        var sum = Complex.ONE
        var k = 1
        while (k < 240) {
            term = term * (-(z * z) / Complex(4.0, 0.0)) / (Complex(k.toDouble(), 0.0) * (Complex(k.toDouble(), 0.0) + nu))
            sum += term
            if (term.abs() < 1e-17 * max(1.0, sum.abs())) break
            k++
        }
        return half.pow(nu) / gamma(nu + Complex.ONE) * sum
    }

    fun besselJ(nu: Double, x: Double): Double {
        if (x < 0 && isInteger(nu)) {
            val sign = if (Math.round(nu) % 2 == 0L) 1.0 else -1.0
            return sign * besselJ(abs(nu), -x)
        }
        if (x < 0) return Double.NaN
        return besselJ(Complex(nu, 0.0), Complex(x, 0.0)).realValue()
    }

    fun besselJ0(x: Double): Double {
        val ax = abs(x)
        if (ax < 8.0) {
            val y = x * x
            val a = 57568490574.0 + y * (-13362590354.0 + y * (651619640.7 + y * (-11214424.18 + y * (77392.33017 + y * (-184.9052456)))))
            val b = 57568490411.0 + y * (1029532985.0 + y * (9494680.718 + y * (59272.64853 + y * (267.8532712 + y))))
            return a / b
        }
        val z = 8.0 / ax
        val y = z * z
        val xx = ax - 0.785398164
        val a = 1.0 + y * (-0.1098628627e-2 + y * (0.2734510407e-4 + y * (-0.2073370639e-5 + y * 0.2093887211e-6)))
        val b = -0.1562499995e-1 + y * (0.1430488765e-3 + y * (-0.6911147651e-5 + y * (0.7621095161e-6 + y * (-0.934935152e-7))))
        return sqrt(0.636619772 / ax) * (cos(xx) * a - z * sin(xx) * b)
    }

    fun besselJ1(x: Double): Double {
        val ax = abs(x)
        if (ax < 8.0) {
            val y = x * x
            val a = x * (72362614232.0 + y * (-7895059235.0 + y * (242396853.1 + y * (-2972611.439 + y * (15704.48260 + y * (-30.16036606))))))
            val b = 144725228442.0 + y * (2300535178.0 + y * (18583304.74 + y * (99447.43394 + y * (376.9991397 + y))))
            return a / b
        }
        val z = 8.0 / ax
        val y = z * z
        val xx = ax - 2.356194491
        val a = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4 + y * (0.2457520174e-5 + y * (-0.240337019e-6))))
        val b = 0.04687499995 + y * (-0.2002690873e-3 + y * (0.8449199096e-5 + y * (-0.88228987e-6 + y * (0.105787412e-6))))
        val ans = sqrt(0.636619772 / ax) * (cos(xx) * a - z * sin(xx) * b)
        return if (x < 0) -ans else ans
    }

    fun besselJInt(n: Int, x: Double): Double {
        if (n == 0) return besselJ0(x)
        if (n == 1) return besselJ1(x)
        var jm = besselJ0(x)
        var j = besselJ1(x)
        for (k in 1 until abs(n)) jm = (2.0 * k / x) * j - jm.also { j = it }
        return if (n < 0 && n % 2 != 0) -jm else jm
    }

    fun besselY0(x: Double): Double {
        if (x <= 0) return Double.NaN
        if (x < 8.0) {
            val y = x * x
            val a = -2957821389.0 + y * (7062834065.0 + y * (-512359803.6 + y * (10879881.29 + y * (-86327.92757 + y * 228.4622733))))
            val b = 40076544269.0 + y * (745249964.8 + y * (7189466.438 + y * (47447.26470 + y * (226.1030244 + y))))
            return a / b + 0.636619772 * besselJ0(x) * ln(x)
        }
        val z = 8.0 / x
        val y = z * z
        val xx = x - 0.785398164
        val a = 1.0 + y * (-0.1098628627e-2 + y * (0.2734510407e-4 + y * (-0.2073370639e-5 + y * 0.2093887211e-6)))
        val b = -0.1562499995e-1 + y * (0.1430488765e-3 + y * (-0.6911147651e-5 + y * (0.7621095161e-6 + y * (-0.934935152e-7))))
        return sqrt(0.636619772 / x) * (sin(xx) * a + z * cos(xx) * b)
    }

    fun besselY1(x: Double): Double {
        if (x <= 0) return Double.NaN
        if (x < 8.0) {
            val y = x * x
            val a = x * (-0.4900604943e13 + y * (0.1275274390e13 + y * (-0.5153438139e11 + y * (0.7349264551e9 + y * (-0.4237922726e7 + y * 0.8511937935e4)))))
            val b = 0.2499580570e14 + y * (0.4244419664e12 + y * (0.3733650367e10 + y * (0.2245904002e8 + y * (0.1020426050e6 + y * (0.3549632885e3 + y)))))
            return a / b + 0.636619772 * (besselJ1(x) * ln(x) - 1.0 / x)
        }
        val z = 8.0 / x
        val y = z * z
        val xx = x - 2.356194491
        val a = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4 + y * (0.2457520174e-5 + y * (-0.240337019e-6))))
        val b = 0.04687499995 + y * (-0.2002690873e-3 + y * (0.8449199096e-5 + y * (-0.88228987e-6 + y * 0.105787412e-6)))
        return sqrt(0.636619772 / x) * (sin(xx) * a + z * cos(xx) * b)
    }

    fun besselYInt(n: Int, x: Double): Double {
        if (x <= 0) return Double.NaN
        if (n == 0) return besselY0(x)
        if (n == 1) return besselY1(x)
        var ym = besselY0(x)
        var y = besselY1(x)
        for (k in 1 until abs(n)) {
            val next = (2.0 * k / x) * y - ym
            ym = y
            y = next
        }
        return y
    }

    fun besselI0(x: Double): Double {
        var term = 1.0
        var sum = 1.0
        val q = x * x / 4.0
        var k = 1
        while (k < 200) {
            term *= q / (k * k)
            sum += term
            if (term < 1e-18 * sum) break
            k++
        }
        return sum
    }

    fun besselI1(x: Double): Double {
        var term = x / 2.0
        var sum = term
        val q = x * x / 4.0
        var k = 1
        while (k < 200) {
            term *= q / (k * (k + 1))
            sum += term
            if (abs(term) < 1e-18 * abs(sum)) break
            k++
        }
        return sum
    }

    fun besselI(n: Int, x: Double): Double {
        if (n == 0) return besselI0(x)
        if (n == 1) return besselI1(x)
        var im = besselI0(x)
        var i = besselI1(x)
        for (k in 1 until abs(n)) {
            val next = im - (2.0 * k / x) * i
            im = i
            i = next
        }
        return i
    }

    fun besselK0(x: Double): Double {
        if (x <= 0) return Double.NaN
        if (x <= 2.0) {
            val y = x * x / 4.0
            return -ln(x / 2.0) * besselI0(x) - 0.57721566 +
                y * (0.42278420 + y * (0.23069756 + y * (0.03488590 + y * (0.00262698 + y * (0.00010750 + y * 0.00000740)))))
        }
        val z = 2.0 / x
        return exp(-x) / sqrt(x) * (1.25331414 + z * (-0.7832358 + z * (0.2189568 + z * (-0.1062446 + z * (0.587872e-1 + z * (-0.251540e-1 + z * 0.53208e-2))))))
    }

    fun besselK1(x: Double): Double {
        if (x <= 0) return Double.NaN
        if (x <= 2.0) {
            val y = x * x / 4.0
            return ln(x / 2.0) * besselI1(x) + 1.0 / x *
                (1.0 + y * (0.15443144 + y * (-0.67278579 + y * (-0.18156897 + y * (-0.01919402 + y * (-0.00110404 + y * -0.00004686))))))
        }
        val z = 2.0 / x
        return exp(-x) / sqrt(x) * (1.25331414 + z * (0.23498619 + z * (-0.3655620e-1 + z * (0.1504268e-1 + z * (-0.780353e-2 + z * (0.325614e-2 + z * -0.68245e-3))))))
    }

    fun besselK(n: Int, x: Double): Double {
        if (n == 0) return besselK0(x)
        if (n == 1) return besselK1(x)
        var km = besselK0(x)
        var k = besselK1(x)
        for (m in 1 until abs(n)) {
            val next = km + (2.0 * m / x) * k
            km = k
            k = next
        }
        return k
    }

    /** Spherical Bessel function jₙ(x)=√(π/2x) J_{n+½}(x), by upward recurrence. */
    fun sphericalBesselJ(n: Int, x: Double): Double {
        if (n < 0 || !x.isFinite()) return Double.NaN
        if (x == 0.0) return if (n == 0) 1.0 else 0.0
        var j0 = sin(x) / x
        if (n == 0) return j0
        var j1 = sin(x) / (x * x) - cos(x) / x
        if (n == 1) return j1
        for (k in 1 until n) {
            val j2 = (2.0 * k + 1.0) / x * j1 - j0
            j0 = j1
            j1 = j2
        }
        return j1
    }

    /** Spherical Bessel function yₙ(x) (Neumann function). */
    fun sphericalBesselY(n: Int, x: Double): Double {
        if (n < 0 || x <= 0 || !x.isFinite()) return Double.NaN
        var y0 = -cos(x) / x
        if (n == 0) return y0
        var y1 = -cos(x) / (x * x) - sin(x) / x
        if (n == 1) return y1
        for (k in 1 until n) {
            val y2 = (2.0 * k + 1.0) / x * y1 - y0
            y0 = y1
            y1 = y2
        }
        return y1
    }

    fun hankel1(n: Int, x: Double): Complex = Complex(besselJInt(n, x), besselYInt(n, x))
    fun hankel2(n: Int, x: Double): Complex = Complex(besselJInt(n, x), -besselYInt(n, x))

    fun airyAi(x: Double): Double {
        if (!x.isFinite()) return Double.NaN
        if (x > 8.0) {
            val z = 2.0 / 3.0 * x.pow(1.5)
            return exp(-z) / (2.0 * sqrt(PI) * x.pow(0.25)) *
                (1.0 - 5.0 / (72.0 * z) + 385.0 / (10368.0 * z * z))
        }
        if (x < -8.0) {
            val z = 2.0 / 3.0 * (-x).pow(1.5)
            return (sin(z + PI / 4) - 5.0 / (72.0 * z) * cos(z + PI / 4)) /
                (sqrt(PI) * (-x).pow(0.25))
        }
        val c1 = 1.0 / (Math.pow(3.0, 2.0 / 3.0) * gamma(2.0 / 3.0))
        val c2 = 1.0 / (Math.pow(3.0, 1.0 / 3.0) * gamma(1.0 / 3.0))
        return c1 * hyper0F1(2.0 / 3.0, x * x * x / 9.0) - c2 * x * hyper0F1(4.0 / 3.0, x * x * x / 9.0)
    }

    fun airyBi(x: Double): Double {
        if (!x.isFinite()) return Double.NaN
        if (x > 8.0) {
            val z = 2.0 / 3.0 * x.pow(1.5)
            return exp(z) / (sqrt(PI) * x.pow(0.25)) *
                (1.0 + 5.0 / (72.0 * z) + 385.0 / (10368.0 * z * z))
        }
        if (x < -8.0) {
            val z = 2.0 / 3.0 * (-x).pow(1.5)
            return (cos(z + PI / 4) + 5.0 / (72.0 * z) * sin(z + PI / 4)) /
                (sqrt(PI) * (-x).pow(0.25))
        }
        val c1 = 1.0 / (Math.pow(3.0, 1.0 / 6.0) * gamma(2.0 / 3.0))
        val c2 = Math.pow(3.0, 1.0 / 6.0) / gamma(1.0 / 3.0)
        return c1 * hyper0F1(2.0 / 3.0, x * x * x / 9.0) + c2 * x * hyper0F1(4.0 / 3.0, x * x * x / 9.0)
    }

    /** Ai′(x) and Bi′(x), obtained with a five-point central difference of the Airy functions. */
    fun airyAiPrime(x: Double): Double {
        if (!x.isFinite()) return Double.NaN
        val h = 1e-3 * max(1.0, abs(x))
        return (-airyAi(x + 2.0 * h) + 8.0 * airyAi(x + h) - 8.0 * airyAi(x - h) + airyAi(x - 2.0 * h)) / (12.0 * h)
    }

    fun airyBiPrime(x: Double): Double {
        if (!x.isFinite()) return Double.NaN
        val h = 1e-3 * max(1.0, abs(x))
        return (-airyBi(x + 2.0 * h) + 8.0 * airyBi(x + h) - 8.0 * airyBi(x - h) + airyBi(x - 2.0 * h)) / (12.0 * h)
    }

    private fun hyper0F1(a: Double, z: Double): Double {
        var term = 1.0
        var sum = 1.0
        var k = 1
        while (k < 200) {
            term *= z / (k * (a + k - 1))
            sum += term
            if (abs(term) < 1e-18 * abs(sum)) break
            k++
        }
        return sum
    }

    fun lambertW(x: Double, branch: Int = 0): Double {
        if (!x.isFinite()) return Double.NaN
        val em1 = -1.0 / Math.E
        if (x < em1) return Double.NaN
        if (branch == 0 && x == 0.0) return 0.0
        if (branch == -1 && x >= 0.0) return Double.NaN
        if (x == em1) return -1.0
        var w = if (branch == -1) ln(-x) - ln(-ln(-x)) else ln(1.0 + x)
        if (x < -0.3) w = -1.0 + sqrt(2.0 * (Math.E * x + 1.0))
        var i = 0
        while (i < 100) {
            val ew = exp(w)
            val f = w * ew - x
            val fp = ew * (w + 1.0) - (w + 2.0) * f / (2.0 * w + 2.0)
            val next = w - f / fp
            if (abs(next - w) < 1e-14 * max(1.0, abs(next))) return next
            w = next
            i++
        }
        return w
    }

    fun polylog(s: Double, x: Double): Double {
        if (x > 1.0) return Double.NaN
        if (x == 1.0) return zeta(s)
        if (x == 0.0) return 0.0
        if (abs(x) < 0.95) {
            var sum = 0.0
            var term = x
            var k = 1
            while (k < 10000) {
                val add = term / k.toDouble().pow(s)
                sum += add
                if (abs(add) < 1e-17 * max(1.0, abs(sum))) break
                term *= x
                k++
            }
            return sum
        }
        // Euler-Maclaurin in the logarithm for the near-unit regime.
        val lx = ln(x)
        var sum = 0.0
        for (k in 0..80) {
            sum += zeta(s - k) * lx.pow(k) / factorialReal(k)
        }
        return sum * gamma(1.0 - s) * (-lx).pow(s - 1) + sum * 0.0
    }

    fun ellipticK(m: Double): Double {
        if (m >= 1.0) return if (m == 1.0) Double.POSITIVE_INFINITY else Double.NaN
        var a = 1.0
        var b = sqrt(1.0 - m)
        repeat(50) {
            val next = (a + b) / 2.0
            b = sqrt(a * b)
            a = next
        }
        return PI / (2.0 * a)
    }

    fun ellipticE(m: Double): Double {
        if (m > 1.0) return Double.NaN
        var a = 1.0
        var b = sqrt(1.0 - m)
        var sum = 0.0
        var pow2 = 1.0
        repeat(50) {
            val c = (a - b) / 2.0
            sum += pow2 * c * c
            pow2 *= 2.0
            val next = (a + b) / 2.0
            b = sqrt(a * b)
            a = next
        }
        return (PI / (2.0 * a)) * (1.0 - sum)
    }

    fun ellipticPi(n: Double, m: Double): Double {
        if (m > 1.0 || n >= 1.0) return Double.NaN
        if (m == 1.0) return Double.POSITIVE_INFINITY
        // Π(n,m) = ∫₀^(π/2) dθ / ((1−n sin²θ)√(1−m sin²θ)). The integrand is smooth for
        // n<1 and m<1, so adaptive Simpson keeps the endpoint behaviour under control.
        val result = Numerical.adaptiveSimpson(
            { t ->
                val s = sin(t)
                val s2 = s * s
                1.0 / ((1.0 - n * s2) * sqrt(1.0 - m * s2))
            },
            0.0,
            PI / 2.0,
            1e-12,
            28,
        )
        return result.value
    }

    fun legendreP(n: Int, x: Double): Double {
        if (n == 0) return 1.0
        if (n == 1) return x
        var p0 = 1.0
        var p1 = x
        for (k in 1 until n) {
            val p2 = ((2.0 * k + 1.0) * x * p1 - k * p0) / (k + 1.0)
            p0 = p1
            p1 = p2
        }
        return p1
    }

    fun legendreQ(n: Int, x: Double): Double {
        if (abs(x) >= 1.0) return Double.NaN
        val q0 = 0.5 * ln((1.0 + x) / (1.0 - x))
        if (n == 0) return q0
        var qm = q0
        var q = x * q0 - 1.0
        if (n == 1) return q
        for (k in 1 until n) {
            val next = ((2.0 * k + 1.0) * x * q - k * qm) / (k + 1.0)
            qm = q
            q = next
        }
        return q
    }

    fun hermite(n: Int, x: Double): Double {
        if (n == 0) return 1.0
        if (n == 1) return 2.0 * x
        var h0 = 1.0
        var h1 = 2.0 * x
        for (k in 1 until n) {
            val h2 = 2.0 * x * h1 - 2.0 * k * h0
            h0 = h1
            h1 = h2
        }
        return h1
    }

    fun laguerre(n: Int, x: Double): Double {
        if (n == 0) return 1.0
        if (n == 1) return 1.0 - x
        var l0 = 1.0
        var l1 = 1.0 - x
        for (k in 1 until n) {
            val l2 = ((2.0 * k + 1.0 - x) * l1 - k * l0) / (k + 1.0)
            l0 = l1
            l1 = l2
        }
        return l1
    }

    fun chebyshevT(n: Int, x: Double): Double {
        if (n == 0) return 1.0
        if (n == 1) return x
        var t0 = 1.0
        var t1 = x
        for (k in 1 until n) {
            val t2 = 2.0 * x * t1 - t0
            t0 = t1
            t1 = t2
        }
        return t1
    }

    fun chebyshevU(n: Int, x: Double): Double {
        if (n == 0) return 1.0
        if (n == 1) return 2.0 * x
        var u0 = 1.0
        var u1 = 2.0 * x
        for (k in 1 until n) {
            val u2 = 2.0 * x * u1 - u0
            u0 = u1
            u1 = u2
        }
        return u1
    }

    /** Generalised Laguerre polynomial Lₙ^(α)(x). */
    fun laguerre(n: Int, alpha: Double, x: Double): Double {
        if (n < 0) return Double.NaN
        if (n == 0) return 1.0
        var l0 = 1.0
        var l1 = 1.0 + alpha - x
        for (k in 1 until n) {
            val l2 = ((2.0 * k + 1.0 + alpha - x) * l1 - (k + alpha) * l0) / (k + 1.0)
            l0 = l1
            l1 = l2
        }
        return l1
    }

    /** Associated Legendre function Pₗᵐ(x), including negative m through the Condon–Shortley phase. */
    fun associatedLegendreP(l: Int, m: Int, x: Double): Double {
        if (l < 0 || abs(m) > l) return 0.0
        val mm = abs(m)
        var pmm = 1.0
        if (mm > 0) {
            val somx2 = sqrt(max(0.0, 1.0 - x * x))
            var fact = 1.0
            for (i in 1..mm) {
                pmm *= -fact * somx2
                fact += 2.0
            }
        }
        var result = if (l == mm) pmm else {
            var pmmp1 = x * (2.0 * mm + 1.0) * pmm
            if (l == mm + 1) {
                pmmp1
            } else {
                var pll = 0.0
                for (ll in mm + 2..l) {
                    pll = ((2.0 * ll - 1.0) * x * pmmp1 - (ll + mm - 1.0) * pmm) / (ll - mm)
                    pmm = pmmp1
                    pmmp1 = pll
                }
                pll
            }
        }
        if (m < 0) {
            // P_l^(−m) = (−1)^m (l−m)!/(l+m)! P_l^m.
            var ratio = 1.0
            for (k in l - mm + 1..l + mm) ratio /= k.toDouble()
            if (mm % 2 == 1) ratio = -ratio
            result *= ratio
        }
        return result
    }

    /** Gegenbauer (ultraspherical) polynomial Cₙ^(α)(x). */
    fun gegenbauer(n: Int, alpha: Double, x: Double): Double {
        if (n < 0) return Double.NaN
        if (n == 0) return 1.0
        if (n == 1) return 2.0 * alpha * x
        var c0 = 1.0
        var c1 = 2.0 * alpha * x
        for (k in 1 until n) {
            val c2 = (2.0 * (k + alpha) * x * c1 - (k + 2.0 * alpha - 1.0) * c0) / (k + 1.0)
            c0 = c1
            c1 = c2
        }
        return c1
    }

    /** Jacobi polynomial Pₙ^(α,β)(x). */
    fun jacobi(n: Int, alpha: Double, beta: Double, x: Double): Double {
        if (n < 0) return Double.NaN
        if (n == 0) return 1.0
        var p0 = 1.0
        var p1 = 0.5 * (alpha - beta + (alpha + beta + 2.0) * x)
        if (n == 1) return p1
        for (k in 1 until n) {
            val kk = k.toDouble()
            val a1 = (2.0 * kk + alpha + beta + 1.0) *
                ((2.0 * kk + alpha + beta) * (2.0 * kk + alpha + beta + 2.0) * x + alpha * alpha - beta * beta)
            val a2 = 2.0 * (kk + alpha) * (kk + beta) * (2.0 * kk + alpha + beta + 2.0)
            val a3 = 2.0 * (kk + 1.0) * (kk + alpha + beta + 1.0) * (2.0 * kk + alpha + beta)
            val p2 = (a1 * p1 - a2 * p0) / a3
            p0 = p1
            p1 = p2
        }
        return p1
    }

    fun hypergeometric2F1(a: Double, b: Double, c: Double, z: Double): Double {
        if (z >= 1.0) return Double.NaN
        if (abs(z) > 0.9) return Double.NaN
        var term = 1.0
        var sum = 1.0
        var k = 0
        while (k < 500) {
            term *= ((a + k) * (b + k) / ((c + k) * (k + 1.0))) * z
            sum += term
            if (abs(term) < 1e-17 * max(1.0, abs(sum))) break
            k++
        }
        return sum
    }

    fun incompleteGamma(a: Double, x: Double): Double {
        if (x < 0 || a <= 0) return Double.NaN
        if (x == 0.0) return 0.0
        if (x < a + 1.0) {
            var sum = 1.0 / a
            var term = sum
            var n = 1
            while (n < 500) {
                term *= x / (a + n)
                sum += term
                if (abs(term) < 1e-17 * abs(sum)) break
                n++
            }
            return sum * exp(-x + a * ln(x) - lnGamma(a))
        }
        return gamma(a) * upperGammaFraction(a, x)
    }

    /** Upper incomplete gamma Γ(a,x) = Γ(a) − γ(a,x). */
    fun upperIncompleteGamma(a: Double, x: Double): Double {
        if (a <= 0 || x < 0) return Double.NaN
        return gamma(a) - incompleteGamma(a, x)
    }

    private fun upperGammaFraction(a: Double, x: Double): Double {
        val tiny = 1e-300
        var b = x + 1.0 - a
        var c = 1.0 / tiny
        var d = 1.0 / b
        var h = d
        for (i in 1..500) {
            val an = -i * (i - a)
            b += 2.0
            d = an * d + b
            if (abs(d) < tiny) d = tiny
            c = b + an / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < 1e-15) break
        }
        return exp(-x + a * ln(x) - lnGamma(a)) * h
    }

    fun incompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x < 0 || x > 1 || a <= 0 || b <= 0) return Double.NaN
        if (x == 0.0) return 0.0
        if (x == 1.0) return 1.0
        val bt = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x))
        return if (x < (a + 1.0) / (a + b + 2.0)) bt * betaFraction(a, b, x) / a
        else 1.0 - bt * betaFraction(b, a, 1.0 - x) / b
    }

    private fun betaFraction(a: Double, b: Double, x: Double): Double {
        val tiny = 1e-300
        var qab = a + b
        var qap = a + 1.0
        var qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..500) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < 1e-15) break
        }
        return h
    }

    fun expIntegralEi(x: Double): Double {
        if (x == 0.0) return Double.NEGATIVE_INFINITY
        if (abs(x) < 40.0) {
            var sum = 0.0
            var term = 1.0
            var n = 1
            while (n < 200) {
                term *= x / n
                sum += term / n
                if (abs(term / n) < 1e-18 * max(1.0, abs(sum))) break
                n++
            }
            return 0.5772156649015329 + ln(abs(x)) + sum
        }
        return exp(x) / x * (1.0 + 1.0 / x + 2.0 / (x * x) + 6.0 / (x * x * x))
    }

    fun expIntegralE1(x: Double): Double {
        if (x <= 0) return Double.NaN
        if (x < 1.0) {
            var sum = -0.5772156649015329 - ln(x)
            var term = x
            var n = 1
            while (n < 200) {
                val add = -term / (n * n) * if (n % 2 == 0) 1.0 else -1.0
                sum -= add
                term *= x
                if (abs(term / (n * n)) < 1e-18 * max(1.0, abs(sum))) break
                n++
            }
            return sum
        }
        return exp(-x) / x * (1.0 - 1.0 / x + 2.0 / (x * x) - 6.0 / (x * x * x))
    }

    fun logarithmicIntegral(x: Double): Double {
        if (x <= 0) return Double.NaN
        return expIntegralEi(ln(x))
    }

    fun sinIntegral(x: Double): Double {
        if (abs(x) < 30.0) {
            var sum = 0.0
            var term = x
            var k = 0
            while (k < 300) {
                val add = term / (2 * k + 1)
                sum += add
                if (abs(add) < 1e-18 * max(1.0, abs(sum))) break
                term *= -x * x / ((2 * k + 2) * (2 * k + 3))
                k++
            }
            return sum
        }
        return PI / 2.0 - cos(x) / x * (1.0 - 2.0 / (x * x))
    }

    fun cosIntegral(x: Double): Double {
        if (x <= 0) return Double.NaN
        if (x < 30.0) {
            var sum = 0.0
            var term = 1.0
            var k = 1
            while (k < 300) {
                term *= -x * x / ((2 * k - 1) * (2 * k))
                sum += term / (2 * k)
                if (abs(term / (2 * k)) < 1e-18 * max(1.0, abs(sum))) break
                k++
            }
            return 0.5772156649015329 + ln(x) + sum
        }
        return sin(x) / x * (1.0 - 2.0 / (x * x))
    }

    fun sinhIntegral(x: Double): Double {
        var sum = 0.0
        var term = x
        var k = 0
        while (k < 300) {
            val add = term / (2 * k + 1)
            sum += add
            if (abs(add) < 1e-18 * max(1.0, abs(sum))) break
            term *= x * x / ((2 * k + 2) * (2 * k + 3))
            k++
        }
        return sum
    }

    fun coshIntegral(x: Double): Double {
        if (x <= 0) return Double.NaN
        var sum = 0.0
        var term = 1.0
        var k = 1
        while (k < 300) {
            term *= x * x / ((2 * k - 1) * (2 * k))
            sum += term / (2 * k)
            if (abs(term / (2 * k)) < 1e-18 * max(1.0, abs(sum))) break
            k++
        }
        return 0.5772156649015329 + ln(x) + sum
    }

    fun agm(a: Double, b: Double): Double {
        var x = a
        var y = b
        repeat(100) {
            val nx = (x + y) / 2.0
            val ny = sqrt(x * y)
            if (abs(nx - ny) < 1e-16 * max(1.0, abs(nx))) return nx
            x = nx
            y = ny
        }
        return x
    }

    fun bernoulli(n: Int): Double = when (n) {
        0 -> 1.0
        1 -> -0.5
        2 -> 1.0 / 6.0
        4 -> -1.0 / 30.0
        6 -> 1.0 / 42.0
        8 -> -1.0 / 30.0
        10 -> 5.0 / 66.0
        12 -> -691.0 / 2730.0
        14 -> 7.0 / 6.0
        16 -> -3617.0 / 510.0
        18 -> 43867.0 / 798.0
        20 -> -174611.0 / 330.0
        22 -> 854513.0 / 138.0
        24 -> -236364091.0 / 2730.0
        26 -> 8553103.0 / 6.0
        28 -> -23749461029.0 / 870.0
        30 -> 8615841276005.0 / 14322.0
        32 -> -7709321041217.0 / 510.0
        else -> 0.0
    }

    fun factorialReal(n: Int): Double {
        if (n < 0) return Double.NaN
        if (n <= 170) {
            var p = 1.0
            for (k in 2..n) p *= k
            return p
        }
        return exp(lnGamma(n + 1.0))
    }

    fun factorial(x: Double): Double {
        if (x < 0 && isInteger(x)) return Double.NaN
        return if (x == floor(x) && x <= 170.0) factorialReal(x.toInt()) else gamma(x + 1.0)
    }

    fun doubleFactorial(n: Double): Double {
        if (n < 0 && isInteger(n)) return Double.NaN
        if (isInteger(n)) {
            var k = n.toInt()
            var p = 1.0
            while (k > 0) { p *= k; k -= 2 }
            return p
        }
        return 2.0.pow((n + 1.0) / 2.0) / sqrt(PI) * gamma(n / 2.0 + 1.0)
    }

    fun binomial(n: Double, k: Double): Double {
        if (!isInteger(n) || !isInteger(k)) return Double.NaN
        val kk = k.toInt()
        val nn = n.toInt()
        if (kk < 0 || kk > nn) return 0.0
        val m = min(kk, nn - kk)
        var result = 1.0
        for (i in 1..m) result = result * (nn - m + i) / i
        return result
    }

    fun risingFactorial(x: Double, n: Double): Double = gamma(x + n) / gamma(x)
    fun fallingFactorial(x: Double, n: Double): Double = gamma(x + 1.0) / gamma(x - n + 1.0)
    fun harmonic(n: Double): Double {
        if (isInteger(n) && n >= 0) {
            var sum = 0.0
            for (k in 1..n.toInt()) sum += 1.0 / k
            return sum
        }
        return digamma(n + 1.0) + 0.5772156649015329
    }

    fun gcd(a: Double, b: Double): Double {
        var x = abs(a).toLong()
        var y = abs(b).toLong()
        while (y != 0L) { val t = x % y; x = y; y = t }
        return x.toDouble()
    }

    fun lcm(a: Double, b: Double): Double {
        if (a == 0.0 || b == 0.0) return 0.0
        return abs(a * b) / gcd(a, b)
    }

    fun isPrime(n: Double): Double {
        if (!isInteger(n) || n < 2) return 0.0
        val v = n.toLong()
        if (v < 4) return 1.0
        if (v % 2 == 0L || v % 3 == 0L) return 0.0
        var i = 5L
        while (i * i <= v) {
            if (v % i == 0L || v % (i + 2) == 0L) return 0.0
            i += 6
        }
        return 1.0
    }

    fun nthPrime(n: Double): Double {
        if (!isInteger(n) || n < 1) return Double.NaN
        val target = n.toInt()
        if (target == 1) return 2.0
        if (target == 2) return 3.0
        var count = 2
        var candidate = 5L
        while (true) {
            if (isPrime(candidate.toDouble()) == 1.0) {
                count++
                if (count == target) return candidate.toDouble()
            }
            candidate += 2
        }
    }

    fun totient(n: Double): Double {
        if (!isInteger(n) || n < 1) return Double.NaN
        var v = n.toLong()
        var result = v
        var p = 2L
        while (p * p <= v) {
            if (v % p == 0L) {
                while (v % p == 0L) v /= p
                result -= result / p
            }
            p++
        }
        if (v > 1) result -= result / v
        return result.toDouble()
    }

    fun mobius(n: Double): Double {
        if (!isInteger(n) || n < 1) return Double.NaN
        var v = n.toLong()
        if (v == 1L) return 1.0
        var count = 0
        var p = 2L
        while (p * p <= v) {
            if (v % p == 0L) {
                v /= p
                if (v % p == 0L) return 0.0
                count++
            }
            p++
        }
        if (v > 1) count++
        return if (count % 2 == 0) 1.0 else -1.0
    }

    fun divisorSigma(n: Double, k: Double = 1.0): Double {
        if (!isInteger(n) || n < 1) return Double.NaN
        val v = n.toLong()
        var sum = 0.0
        var d = 1L
        while (d * d <= v) {
            if (v % d == 0L) {
                sum += d.toDouble().pow(k)
                val other = v / d
                if (other != d) sum += other.toDouble().pow(k)
            }
            d++
        }
        return sum
    }

    fun fibonacci(n: Double): Double {
        if (!isInteger(n)) return Double.NaN
        var a = 0.0
        var b = 1.0
        repeat(abs(n.toInt())) {
            val t = a + b
            a = b
            b = t
        }
        return if (n >= 0) a else if (abs(n.toInt()) % 2 == 0) -a else a
    }

    fun partitionNumber(n: Double): Double {
        if (!isInteger(n) || n < 0) return Double.NaN
        val limit = n.toInt()
        val dp = DoubleArray(limit + 1)
        dp[0] = 1.0
        for (k in 1..limit) {
            for (j in k..limit) dp[j] += dp[j - k]
        }
        return dp[limit]
    }

    fun catalanNumber(n: Double): Double {
        if (!isInteger(n) || n < 0) return Double.NaN
        if (n > 160) return Double.POSITIVE_INFINITY
        return binomial(2.0 * n, n) / (n + 1.0)
    }

    fun bellNumber(n: Double): Double {
        if (!isInteger(n) || n < 0) return Double.NaN
        val m = n.toInt()
        if (m > 170) return Double.POSITIVE_INFINITY
        var prev = DoubleArray(m + 1)
        prev[0] = 1.0
        for (i in 1..m) {
            val cur = DoubleArray(m + 1)
            cur[0] = prev[i - 1]
            for (j in 1..i) cur[j] = cur[j - 1] + prev[j - 1]
            prev = cur
        }
        return prev[0]
    }

    /** Unsigned Stirling number of the first kind c(n,k). */
    fun stirlingFirst(n: Int, k: Int): Double {
        if (n < 0 || k < 0 || k > n) return 0.0
        val dp = DoubleArray(k + 1)
        dp[0] = 1.0
        for (i in 1..n) {
            for (j in min(i, k) downTo 1) dp[j] = dp[j - 1] + (i - 1) * dp[j]
            dp[0] = 0.0
        }
        return dp[k]
    }

    /** Stirling number of the second kind S(n,k). */
    fun stirlingSecond(n: Int, k: Int): Double {
        if (n < 0 || k < 0 || k > n) return 0.0
        if (n == 0 && k == 0) return 1.0
        val dp = DoubleArray(k + 1)
        dp[0] = 1.0
        for (i in 1..n) {
            for (j in min(i, k) downTo 1) dp[j] = j * dp[j] + dp[j - 1]
            dp[0] = 0.0
        }
        return dp[k]
    }

    /** Number of derangements !n. */
    fun derangement(n: Double): Double {
        if (!isInteger(n) || n < 0) return Double.NaN
        val m = n.toInt()
        if (m == 0) return 1.0
        var d0 = 1.0
        var d1 = 0.0
        for (i in 2..m) {
            val d = (i - 1) * (d0 + d1)
            d0 = d1
            d1 = d
        }
        return d1
    }

    fun lucasNumber(n: Double): Double {
        if (!isInteger(n)) return Double.NaN
        val k = abs(n.toInt())
        val value = fibonacci(k - 1.0) + fibonacci(k + 1.0)
        return if (n >= 0 || k % 2 == 0) value else -value
    }

    /** Prime-counting function π(n), by sieve; capped to keep an accidental plot responsive. */
    fun primePi(n: Double): Double {
        if (!isInteger(n) || n < 0) return Double.NaN
        val limit = n.toInt()
        if (limit < 2) return 0.0
        if (limit > 2_000_000) return Double.NaN
        val composite = BooleanArray(limit + 1)
        var count = 0
        var i = 2
        while (i <= limit) {
            if (!composite[i]) {
                count++
                if (i.toLong() * i <= limit) {
                    var j = i * i
                    while (j <= limit) {
                        composite[j] = true
                        j += i
                    }
                }
            }
            i++
        }
        return count.toDouble()
    }

    fun kronecker(a: Double, b: Double): Double = if (a == b) 1.0 else 0.0

    /** Regularized lower incomplete gamma, P(a,x). */
    fun regularizedGammaP(a: Double, x: Double): Double {
        if (x <= 0) return 0.0
        return incompleteGamma(a, x) / gamma(a)
    }

    /** The digamma-like logarithmic derivative of Γ, used by symbolic factorial differentiation. */
    fun gammaPrimeOverGamma(x: Double): Double = digamma(x)
}
