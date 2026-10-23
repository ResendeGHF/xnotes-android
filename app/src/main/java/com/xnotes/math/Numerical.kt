package com.xnotes.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point sampled from a function or an ODE solution. */
data class SamplePoint(val x: Double, val y: Double)

/** Value and diagnostic information from a quadrature. */
data class QuadratureResult(val value: Double, val error: Double, val evaluations: Int)

/** A root and whether the iteration converged. */
data class RootResult(val root: Double, val iterations: Int, val converged: Boolean, val residual: Double)

enum class OdeMethod(val label: String) {
    EULER("Euler"),
    MIDPOINT("Midpoint"),
    HEUN("Heun"),
    RK4("Runge–Kutta 4"),
    RK45("Dormand–Prince 45"),
}

/**
 * Numerical calculus: quadrature, differentiation, root finding, ODE integration and the
 * differential operators used by the field plots. Everything is plain Kotlin so the engine can be
 * unit-tested without Android.
 */
object Numerical {

    // ------------------------------------------------------------------ quadrature

    fun adaptiveSimpson(
        f: (Double) -> Double,
        a: Double,
        b: Double,
        tolerance: Double = 1e-10,
        maxDepth: Int = 24,
    ): QuadratureResult {
        if (a == b) return QuadratureResult(0.0, 0.0, 0)
        if (b < a) {
            val r = adaptiveSimpson(f, b, a, tolerance, maxDepth)
            return r.copy(value = -r.value)
        }
        var evaluations = 0
        fun g(x: Double): Double { evaluations++; return f(x) }
        val fa = g(a)
        val fb = g(b)
        val m = (a + b) / 2.0
        val fm = g(m)
        val whole = (b - a) / 6.0 * (fa + 4.0 * fm + fb)
        var error = 0.0
        fun recurse(a0: Double, b0: Double, fa0: Double, fb0: Double, fm0: Double, whole0: Double, eps: Double, depth: Int): Double {
            val m0 = (a0 + b0) / 2.0
            val lm = (a0 + m0) / 2.0
            val rm = (m0 + b0) / 2.0
            val flm = g(lm)
            val frm = g(rm)
            val left = (m0 - a0) / 6.0 * (fa0 + 4.0 * flm + fm0)
            val right = (b0 - m0) / 6.0 * (fm0 + 4.0 * frm + fb0)
            val delta = left + right - whole0
            error = abs(delta) / 15.0
            if (depth <= 0 || abs(delta) <= 15.0 * eps) return left + right + delta / 15.0
            return recurse(a0, m0, fa0, fm0, flm, left, eps / 2.0, depth - 1) +
                recurse(m0, b0, fm0, fb0, frm, right, eps / 2.0, depth - 1)
        }
        val value = recurse(a, b, fa, fb, fm, whole, tolerance, maxDepth)
        return QuadratureResult(value, error, evaluations)
    }

    /** Composite Gauss–Legendre with [panels] intervals and [order] nodes each. */
    fun gaussLegendre(
        f: (Double) -> Double,
        a: Double,
        b: Double,
        panels: Int = 16,
        order: Int = 16,
    ): QuadratureResult {
        if (a == b) return QuadratureResult(0.0, 0.0, 0)
        val nodes = gaussNodes(order)
        val lo = min(a, b)
        val hi = max(a, b)
        val h = (hi - lo) / panels
        var sum = 0.0
        var evals = 0
        for (p in 0 until panels) {
            val mid = lo + (p + 0.5) * h
            val half = h / 2.0
            for (i in nodes.indices) {
                sum += nodes[i].second * f(mid + half * nodes[i].first)
                evals++
            }
            sum *= 1.0
        }
        val value = sum * h / 2.0
        return QuadratureResult(if (b < a) -value else value, Double.NaN, evals)
    }

    private fun gaussNodes(n: Int): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(n)
        val m = (n + 1) / 2
        for (i in 1..m) {
            var z = cos(Math.PI * (i - 0.25) / (n + 0.5))
            var pp = 0.0
            for (iter in 0 until 100) {
                var p1 = 1.0
                var p2 = 0.0
                for (j in 1..n) {
                    val p3 = p2
                    p2 = p1
                    p1 = ((2.0 * j - 1.0) * z * p2 - (j - 1.0) * p3) / j
                }
                pp = n * (z * p1 - p2) / (z * z - 1.0)
                val z1 = z
                z = z1 - p1 / pp
                if (abs(z - z1) < 1e-15) break
            }
            val w = 2.0 / ((1.0 - z * z) * pp * pp)
            out += z to w
            if (i != n - i + 1) out += -z to w
        }
        return out
    }

    fun romberg(f: (Double) -> Double, a: Double, b: Double, tolerance: Double = 1e-10, maxIterations: Int = 20): QuadratureResult {
        if (a == b) return QuadratureResult(0.0, 0.0, 0)
        if (b < a) {
            val r = romberg(f, b, a, tolerance, maxIterations)
            return r.copy(value = -r.value)
        }
        var evaluations = 0
        val rows = ArrayList<DoubleArray>()
        var h = b - a
        var prev = DoubleArray(maxIterations + 1)
        var current = DoubleArray(maxIterations + 1)
        prev[0] = 0.5 * h * (f(a) + f(b)); evaluations += 2
        var best = prev[0]
        for (i in 1..maxIterations) {
            h /= 2.0
            var sum = 0.0
            var k = 1
            while (k <= (1 shl (i - 1))) {
                sum += f(a + (2 * k - 1) * h)
                evaluations++
                k++
            }
            current[0] = 0.5 * prev[0] + h * sum
            var factor = 4.0
            for (j in 1..i) {
                current[j] = current[j - 1] + (current[j - 1] - prev[j - 1]) / (factor - 1.0)
                factor *= 4.0
            }
            val err = abs(current[i] - prev[i - 1])
            best = current[i]
            if (err < tolerance) {
                val tmp = prev; prev = current; current = tmp
                rows.clear()
                return QuadratureResult(best, err, evaluations)
            }
            val tmp = prev; prev = current; current = tmp
        }
        return QuadratureResult(best, abs(prev[maxIterations] - prev[maxIterations - 1]), evaluations)
    }

    // ------------------------------------------------------------------ differentiation

    fun derivative(f: (Double) -> Double, x: Double, order: Int = 1): Double {
        val h = cbrtEps * max(1.0, abs(x))
        return when (order) {
            0 -> f(x)
            1 -> (-f(x + 2 * h) + 8 * f(x + h) - 8 * f(x - h) + f(x - 2 * h)) / (12 * h)
            2 -> (-f(x + 2 * h) + 16 * f(x + h) - 30 * f(x) + 16 * f(x - h) - f(x - 2 * h)) / (12 * h * h)
            3 -> (f(x + 2 * h) - 2 * f(x + h) + 2 * f(x - h) - f(x - 2 * h)) / (2 * h * h * h)
            4 -> (f(x + 2 * h) - 4 * f(x + h) + 6 * f(x) - 4 * f(x - h) + f(x - 2 * h)) / (h * h * h * h)
            else -> {
                // For higher orders, differentiate the lower order recursively.
                if (order < 0) Double.NaN
                else derivative({ t -> derivative(f, t, order - 1) }, x, 1)
            }
        }
    }

    /** Richardson-extrapolated central difference at [x]. */
    fun richardsonDerivative(f: (Double) -> Double, x: Double, order: Int = 1, levels: Int = 6): Double {
        val table = Array(levels) { DoubleArray(levels) }
        val h0 = cbrtEps * max(1.0, abs(x))
        for (i in 0 until levels) {
            val h = h0 / 2.0.pow(i)
            table[i][0] = centralDifference(f, x, h, order)
            for (j in 1..i) {
                val factor = 4.0.pow(j)
                table[i][j] = (factor * table[i][j - 1] - table[i - 1][j - 1]) / (factor - 1.0)
            }
        }
        return table[levels - 1][levels - 1]
    }

    private fun centralDifference(f: (Double) -> Double, x: Double, h: Double, order: Int): Double = when (order) {
        1 -> (f(x + h) - f(x - h)) / (2 * h)
        2 -> (f(x + h) - 2 * f(x) + f(x - h)) / (h * h)
        else -> {
            var sum = 0.0
            for (k in 0..order) {
                val sign = if ((order - k) % 2 == 0) 1.0 else -1.0
                sum += sign * binomialInt(order, k) * f(x + (k - order / 2.0) * h)
            }
            sum / h.pow(order)
        }
    }

    private fun binomialInt(n: Int, k: Int): Double {
        var result = 1.0
        for (i in 1..k) result = result * (n - k + i) / i
        return result
    }

    // ------------------------------------------------------------------ roots

    fun bisection(f: (Double) -> Double, a: Double, b: Double, tolerance: Double = 1e-12, maxIterations: Int = 200): RootResult {
        var lo = min(a, b)
        var hi = max(a, b)
        var flo = f(lo)
        var fhi = f(hi)
        if (flo == 0.0) return RootResult(lo, 0, true, 0.0)
        if (fhi == 0.0) return RootResult(hi, 0, true, 0.0)
        if (flo * fhi > 0) return RootResult(Double.NaN, 0, false, Double.NaN)
        var mid = (lo + hi) / 2.0
        var fmid = f(mid)
        var i = 0
        while (i < maxIterations && abs(hi - lo) > tolerance) {
            if (flo * fmid <= 0) {
                hi = mid; fhi = fmid
            } else {
                lo = mid; flo = fmid
            }
            mid = (lo + hi) / 2.0
            fmid = f(mid)
            i++
        }
        return RootResult(mid, i, abs(fmid) < 1e-8 || abs(hi - lo) <= tolerance, fmid)
    }

    fun newton(f: (Double) -> Double, guess: Double, tolerance: Double = 1e-12, maxIterations: Int = 100): RootResult {
        var x = guess
        var fx = f(x)
        var i = 0
        while (i < maxIterations) {
            val df = derivative(f, x)
            if (!df.isFinite() || abs(df) < 1e-300) break
            val next = x - fx / df
            if (!next.isFinite()) break
            val fnext = f(next)
            if (abs(next - x) < tolerance || abs(fnext) < tolerance) {
                return RootResult(next, i + 1, true, fnext)
            }
            x = next
            fx = fnext
            i++
        }
        return RootResult(x, i, abs(fx) < 1e-7, fx)
    }

    fun secant(f: (Double) -> Double, a: Double, b: Double, tolerance: Double = 1e-12, maxIterations: Int = 100): RootResult {
        var x0 = a
        var x1 = b
        var f0 = f(x0)
        var f1 = f(x1)
        var i = 0
        while (i < maxIterations) {
            val denom = f1 - f0
            if (abs(denom) < 1e-300) break
            val x2 = x1 - f1 * (x1 - x0) / denom
            val f2 = f(x2)
            if (!x2.isFinite()) break
            if (abs(x2 - x1) < tolerance || abs(f2) < tolerance) return RootResult(x2, i + 1, true, f2)
            x0 = x1; f0 = f1
            x1 = x2; f1 = f2
            i++
        }
        return RootResult(x1, i, abs(f1) < 1e-7, f1)
    }

    // ------------------------------------------------------------------ ODEs

    fun solveOde(
        f: (Double, Double) -> Double,
        x0: Double,
        y0: Double,
        x1: Double,
        steps: Int = 400,
        method: OdeMethod = OdeMethod.RK45,
    ): List<SamplePoint> {
        if (x0 == x1) return listOf(SamplePoint(x0, y0))
        val n = steps.coerceIn(2, 200_000)
        return when (method) {
            OdeMethod.EULER, OdeMethod.MIDPOINT, OdeMethod.HEUN, OdeMethod.RK4 -> {
                val h = (x1 - x0) / n
                val out = ArrayList<SamplePoint>(n + 1)
                var x = x0
                var y = y0
                out += SamplePoint(x, y)
                repeat(n) {
                    y = when (method) {
                        OdeMethod.EULER -> y + h * f(x, y)
                        OdeMethod.MIDPOINT -> {
                            val k1 = f(x, y)
                            y + h * f(x + h / 2.0, y + h * k1 / 2.0)
                        }
                        OdeMethod.HEUN -> {
                            val k1 = f(x, y)
                            val k2 = f(x + h, y + h * k1)
                            y + h * (k1 + k2) / 2.0
                        }
                        else -> rk4Step(f, x, y, h)
                    }
                    x += h
                    out += SamplePoint(x, y)
                }
                out
            }
            OdeMethod.RK45 -> rk45(f, x0, y0, x1, 1e-8, 1e-10, n.toLong() * 20)
        }
    }

    private fun rk4Step(f: (Double, Double) -> Double, x: Double, y: Double, h: Double): Double {
        val k1 = f(x, y)
        val k2 = f(x + h / 2.0, y + h * k1 / 2.0)
        val k3 = f(x + h / 2.0, y + h * k2 / 2.0)
        val k4 = f(x + h, y + h * k3)
        return y + h * (k1 + 2 * k2 + 2 * k3 + k4) / 6.0
    }

    private fun rk45(
        f: (Double, Double) -> Double,
        x0: Double,
        y0: Double,
        xEnd: Double,
        tolerance: Double,
        minStep: Double,
        maxSteps: Long,
    ): List<SamplePoint> {
        val out = ArrayList<SamplePoint>()
        var x = x0
        var y = y0
        var h = (xEnd - x0) / 100.0
        var steps = 0L
        out += SamplePoint(x, y)
        val c = doubleArrayOf(0.0, 1.0 / 5, 3.0 / 10, 4.0 / 5, 8.0 / 9, 1.0, 1.0)
        val a = arrayOf(
            doubleArrayOf(),
            doubleArrayOf(1.0 / 5),
            doubleArrayOf(3.0 / 40, 9.0 / 40),
            doubleArrayOf(44.0 / 45, -56.0 / 15, 32.0 / 9),
            doubleArrayOf(19372.0 / 6561, -25360.0 / 2187, 64448.0 / 6561, -212.0 / 729),
            doubleArrayOf(9017.0 / 3168, -355.0 / 33, 46732.0 / 5247, 49.0 / 176, -5103.0 / 18656),
            doubleArrayOf(35.0 / 384, 0.0, 500.0 / 1113, 125.0 / 192, -2187.0 / 6784, 11.0 / 84),
        )
        val b5 = doubleArrayOf(35.0 / 384, 0.0, 500.0 / 1113, 125.0 / 192, -2187.0 / 6784, 11.0 / 84, 0.0)
        val b4 = doubleArrayOf(5179.0 / 57600, 0.0, 7571.0 / 16695, 393.0 / 640, -92097.0 / 339200, 187.0 / 2100, 1.0 / 40)
        while (x < xEnd - 1e-15 && steps < maxSteps) {
            if (x + h > xEnd) h = xEnd - x
            val k = Array(7) { DoubleArray(1) }
            k[0][0] = f(x, y)
            for (i in 1..6) {
                var yi = y
                for (j in 0 until i) yi += h * a[i][j] * k[j][0]
                k[i][0] = f(x + c[i] * h, yi)
            }
            var y5 = y
            var y4 = y
            for (i in 0..6) {
                y5 += h * b5[i] * k[i][0]
                y4 += h * b4[i] * k[i][0]
            }
            val err = abs(y5 - y4)
            if (err <= tolerance || h <= minStep) {
                x += h
                y = y5
                out += SamplePoint(x, y)
                if (!y.isFinite()) break
            }
            val factor = if (err == 0.0) 5.0 else 0.9 * (tolerance / err).pow(0.2)
            h *= factor.coerceIn(0.2, 5.0)
            if (h < minStep) h = minStep
            steps++
        }
        if (out.lastOrNull()?.x?.let { abs(it - xEnd) < 1e-10 } != true) out += SamplePoint(xEnd, y)
        return out
    }

    /** A coupled first-order system, for the "system" ODE mode in the Calculus tab. */
    fun solveSystem(
        f: (Double, DoubleArray) -> DoubleArray,
        x0: Double,
        y0: DoubleArray,
        x1: Double,
        steps: Int = 400,
    ): List<Pair<Double, DoubleArray>> {
        val n = steps.coerceIn(2, 100_000)
        val h = (x1 - x0) / n
        var x = x0
        var y = y0.copyOf()
        val out = ArrayList<Pair<Double, DoubleArray>>(n + 1)
        out += x to y.copyOf()
        repeat(n) {
            val k1 = f(x, y)
            val k2 = f(x + h / 2.0, DoubleArray(y.size) { y[it] + h * k1[it] / 2.0 })
            val k3 = f(x + h / 2.0, DoubleArray(y.size) { y[it] + h * k2[it] / 2.0 })
            val k4 = f(x + h, DoubleArray(y.size) { y[it] + h * k3[it] })
            y = DoubleArray(y.size) { y[it] + h * (k1[it] + 2 * k2[it] + 2 * k3[it] + k4[it]) / 6.0 }
            x += h
            out += x to y.copyOf()
        }
        return out
    }

    // ------------------------------------------------------------------ vector calculus

    fun gradient2(f: (Double, Double) -> Double, x: Double, y: Double): Pair<Double, Double> {
        val h = cbrtEps * max(1.0, max(abs(x), abs(y)))
        val fx = (f(x + h, y) - f(x - h, y)) / (2 * h)
        val fy = (f(x, y + h) - f(x, y - h)) / (2 * h)
        return fx to fy
    }

    fun divergence2(p: (Double, Double) -> Double, q: (Double, Double) -> Double, x: Double, y: Double): Double {
        val h = cbrtEps * max(1.0, max(abs(x), abs(y)))
        return (p(x + h, y) - p(x - h, y)) / (2 * h) + (q(x, y + h) - q(x, y - h)) / (2 * h)
    }

    fun curl2(p: (Double, Double) -> Double, q: (Double, Double) -> Double, x: Double, y: Double): Double {
        val h = cbrtEps * max(1.0, max(abs(x), abs(y)))
        return (q(x + h, y) - q(x - h, y)) / (2 * h) - (p(x, y + h) - p(x, y - h)) / (2 * h)
    }

    fun laplacian2(f: (Double, Double) -> Double, x: Double, y: Double): Double {
        val h = 1e-4 * max(1.0, max(abs(x), abs(y)))
        return (f(x + h, y) + f(x - h, y) + f(x, y + h) + f(x, y - h) - 4 * f(x, y)) / (h * h)
    }

    fun gradient3(f: (Double, Double, Double) -> Double, x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val h = cbrtEps * max(1.0, max(abs(x), max(abs(y), abs(z))))
        return Triple(
            (f(x + h, y, z) - f(x - h, y, z)) / (2 * h),
            (f(x, y + h, z) - f(x, y - h, z)) / (2 * h),
            (f(x, y, z + h) - f(x, y, z - h)) / (2 * h),
        )
    }

    fun divergence3(
        p: (Double, Double, Double) -> Double,
        q: (Double, Double, Double) -> Double,
        r: (Double, Double, Double) -> Double,
        x: Double,
        y: Double,
        z: Double,
    ): Double {
        val h = cbrtEps * max(1.0, max(abs(x), max(abs(y), abs(z))))
        return (p(x + h, y, z) - p(x - h, y, z)) / (2 * h) +
            (q(x, y + h, z) - q(x, y - h, z)) / (2 * h) +
            (r(x, y, z + h) - r(x, y, z - h)) / (2 * h)
    }

    fun curl3(
        p: (Double, Double, Double) -> Double,
        q: (Double, Double, Double) -> Double,
        r: (Double, Double, Double) -> Double,
        x: Double,
        y: Double,
        z: Double,
    ): Triple<Double, Double, Double> {
        val h = cbrtEps * max(1.0, max(abs(x), max(abs(y), abs(z))))
        val rx = (r(x, y + h, z) - r(x, y - h, z)) / (2 * h)
        val ry = (r(x + h, y, z) - r(x - h, y, z)) / (2 * h)
        val qx = (q(x + h, y, z) - q(x - h, y, z)) / (2 * h)
        val qz = (q(x, y, z + h) - q(x, y, z - h)) / (2 * h)
        val px = (p(x + h, y, z) - p(x - h, y, z)) / (2 * h)
        val py = (p(x, y + h, z) - p(x, y - h, z)) / (2 * h)
        val pz = (p(x, y, z + h) - p(x, y, z - h)) / (2 * h)
        return Triple(ry - qz, pz - rx, qx - py)
    }

    // ------------------------------------------------------------------ helpers

    private const val cbrtEps = 6.055454452393343e-6
}
