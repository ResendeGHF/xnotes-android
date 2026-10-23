package com.xnotes.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * A complex number with the arithmetic the engine needs. Everything the calculator evaluates is a
 * [Complex]; a real result simply has a zero imaginary part. This keeps one evaluator for real and
 * complex maths (including functions such as Γ, ζ and the Bessel family) instead of two that could
 * disagree.
 */
data class Complex(val re: Double, val im: Double) {

    val isReal: Boolean get() = im == 0.0
    val isFinite: Boolean get() = re.isFinite() && im.isFinite()
    val isNaN: Boolean get() = re.isNaN() || im.isNaN()

    fun abs(): Double = hypot(re, im)
    fun abs2(): Double = re * re + im * im
    fun arg(): Double = atan2(im, re)
    fun conj(): Complex = Complex(re, -im)

    operator fun plus(o: Complex): Complex = Complex(re + o.re, im + o.im)
    operator fun minus(o: Complex): Complex = Complex(re - o.re, im - o.im)
    operator fun times(o: Complex): Complex = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun div(o: Complex): Complex {
        val d = o.re * o.re + o.im * o.im
        if (d == 0.0) return Complex(Double.NaN, Double.NaN)
        return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }

    /** Scalar shortcuts keep the formulas in the special-function library readable. */
    operator fun plus(o: Double): Complex = Complex(re + o, im)
    operator fun minus(o: Double): Complex = Complex(re - o, im)
    operator fun times(o: Double): Complex = Complex(re * o, im * o)
    operator fun div(o: Double): Complex = Complex(re / o, im / o)

    operator fun unaryMinus(): Complex = Complex(-re, -im)

    fun exp(): Complex {
        val e = exp(re)
        return Complex(e * cos(im), e * sin(im))
    }

    fun log(): Complex = Complex(ln(abs()), arg())

    fun log(base: Complex): Complex = log() / base.log()

    fun sqrt(): Complex {
        // The negative real axis is a branch cut; take the +i side explicitly. Without this,
        // negating a real produces -0.0, atan2(-0.0, -1) gives -π, and sqrt would land on -i.
        if (im == 0.0) {
            if (re >= 0.0) return Complex(sqrt(re), 0.0)
            return Complex(0.0, sqrt(-re))
        }
        val r = sqrt(abs())
        val a = arg() / 2.0
        return Complex(r * cos(a), r * sin(a))
    }

    fun pow(o: Complex): Complex {
        if (this == ZERO) return if (o == ZERO) ONE else ZERO
        // Real bases with real integer exponents keep an exactly real result, which matters for
        // plotting: (-2)^3 must not leave -8 - 1e-15i behind.
        if (im == 0.0 && o.im == 0.0) {
            if (o.re == Math.floor(o.re) && abs(o.re) < 1024.0) {
                return Complex(re.pow(o.re.toInt()), 0.0)
            }
            if (re > 0.0) return Complex(re.pow(o.re), 0.0)
        }
        return (log() * o).exp()
    }

    fun pow(n: Double): Complex = pow(Complex(n, 0.0))

    fun sin(): Complex = Complex(sin(re) * cosh(im), cos(re) * sinh(im))
    fun cos(): Complex = Complex(cos(re) * cosh(im), -sin(re) * sinh(im))
    fun tan(): Complex = sin() / cos()
    fun cot(): Complex = cos() / sin()
    fun sec(): Complex = ONE / cos()
    fun csc(): Complex = ONE / sin()

    fun sinh(): Complex = Complex(sinh(re) * cos(im), cosh(re) * sin(im))
    fun cosh(): Complex = Complex(cosh(re) * cos(im), sinh(re) * sin(im))
    fun tanh(): Complex = sinh() / cosh()
    fun coth(): Complex = cosh() / sinh()
    fun sech(): Complex = ONE / cosh()
    fun csch(): Complex = ONE / sinh()

    fun asin(): Complex {
        // -i ln(iz + sqrt(1-z²))
        val z = this
        return Complex(0.0, -1.0) * (Complex(0.0, 1.0) * z + (ONE - z * z).sqrt()).log()
    }

    fun acos(): Complex = Complex(kotlin.math.PI / 2.0, 0.0) - asin()
    fun atan(): Complex {
        val i = Complex(0.0, 1.0)
        return (i / 2.0) * ((ONE - i * this).log() - (ONE + i * this).log()) // i/2 uses the scalar division above
    }

    fun asinh(): Complex = (this + (this * this + ONE).sqrt()).log()
    fun acosh(): Complex = (this + (this - ONE).sqrt() * (this + ONE).sqrt()).log()
    fun atanh(): Complex = ((ONE + this).log() - (ONE - this).log()) / Complex(2.0, 0.0)

    /** The real part when the imaginary part is negligible, else NaN. */
    fun realValue(): Double {
        val scale = re.absoluteValueScale()
        return if (abs(im) <= 1e-9 * scale) re else Double.NaN
    }

    private fun Double.absoluteValueScale(): Double = if (this.isFinite()) maxOf(1.0, abs(this)) else 1.0

    override fun toString(): String = Numbers.formatComplex(this)

    companion object {
        val ZERO: Complex = Complex(0.0, 0.0)
        val ONE: Complex = Complex(1.0, 0.0)
        val I: Complex = Complex(0.0, 1.0)
        val PI: Complex = Complex(kotlin.math.PI, 0.0)
        val E: Complex = Complex(kotlin.math.E, 0.0)
        val NaN: Complex = Complex(Double.NaN, Double.NaN)
        val INF: Complex = Complex(Double.POSITIVE_INFINITY, 0.0)

        fun of(re: Double, im: Double = 0.0): Complex = Complex(re, im)
        fun real(re: Double): Complex = Complex(re, 0.0)
        fun imag(im: Double): Complex = Complex(0.0, im)
    }
}

/** Number formatting shared by the calculator, the plots and the symbolic output. */
object Numbers {

    /** Format a real in the shortest form that keeps twelve significant digits. */
    fun format(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "∞"
        if (v == Double.NEGATIVE_INFINITY) return "-∞"
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e-6 && a < 1e12) {
            val r = Math.rint(v)
            if (abs(v - r) <= 1e-10 * maxOf(1.0, a)) {
                return if (abs(r) < 1e15) r.toLong().toString() else "%.12g".format(v)
            }
        }
        return "%.12g".format(v)
            .replace("e+", "e")
            .replace("E", "e")
    }

    /** Format a complex value as `a`, `bi`, or `a ± bi`. */
    fun formatComplex(z: Complex): String {
        if (z.re.isNaN() || z.im.isNaN()) return "NaN"
        if (!z.isFinite) return format(z.re)
        val re = if (abs(z.re) <= 1e-13 * maxOf(1.0, z.abs())) 0.0 else z.re
        val im = if (abs(z.im) <= 1e-13 * maxOf(1.0, z.abs())) 0.0 else z.im
        return when {
            im == 0.0 -> format(re)
            re == 0.0 -> when (im) {
                1.0 -> "i"
                -1.0 -> "-i"
                else -> "${format(im)}i"
            }
            else -> {
                val sign = if (im < 0) "−" else "+"
                val mag = format(abs(im))
                val body = if (mag == "1") "i" else "${mag}i"
                "${format(re)} $sign $body"
            }
        }
    }

    /** Compact value for a plot tooltip or an axis tick. */
    fun tick(v: Double): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e4 || a < 1e-3) return "%.2e".format(v)
        return if (abs(v - Math.rint(v)) < 1e-8) Math.rint(v).toLong().toString()
        else "%.3f".format(v).trimEnd('0').trimEnd('.')
    }
}
