package com.xnotes.core.stroke

/*
 * Kalman filter core ported from androidx.input.motionprediction (AOSP,
 * platform/frameworks/support, Apache License 2.0):
 *   input/input-motionprediction/.../kalman/KalmanFilter.java
 *   input/input-motionprediction/.../kalman/matrix/Matrix.java
 *
 * Two transcription notes (both verified by unit tests here):
 * - predict() writes the covariance update back into P (F·P·Fᵀ + Q); the
 *   upstream allocation-trimming refactor computes it into a scratch buffer
 *   that is otherwise dropped, which would freeze P after the first step.
 * - update() applies the freshly computed Kalman gain (not a stale zero
 *   buffer) to both the state and the covariance.
 */

/** Minimal row-major matrix backing the 4-state/1-measurement Kalman filter below. */
internal class KalmanMatrix(val rows: Int, val cols: Int) {
    val m = DoubleArray(rows * cols)

    constructor(stride: Int, values: DoubleArray) : this(
        (values.size + stride - 1) / stride,
        stride,
    ) {
        require(values.size == rows * cols) { "len ${values.size} != $rows x $cols" }
        values.copyInto(m)
    }

    operator fun get(i: Int, j: Int): Double = m[i * cols + j]
    operator fun set(i: Int, j: Int, v: Double) {
        m[i * cols + j] = v
    }

    fun fill(v: Double) {
        m.fill(v)
    }

    fun setIdentity() {
        fill(0.0)
        for (i in 0 until minOf(rows, cols)) this[i, i] = 1.0
    }

    fun scale(a: Double) {
        for (i in m.indices) m[i] *= a
    }

    /** this += that (in place). */
    fun addInPlace(that: KalmanMatrix): KalmanMatrix {
        require(rows == that.rows && cols == that.cols)
        for (i in m.indices) m[i] += that.m[i]
        return this
    }

    /** this -= that (in place). */
    fun subtractInPlace(that: KalmanMatrix): KalmanMatrix {
        require(rows == that.rows && cols == that.cols)
        for (i in m.indices) m[i] -= that.m[i]
        return this
    }

    /** result = this * that. */
    fun dot(that: KalmanMatrix, result: KalmanMatrix): KalmanMatrix {
        require(cols == that.rows && result.rows == rows && result.cols == that.cols)
        for (i in 0 until rows) {
            for (j in 0 until that.cols) {
                var s = 0.0
                for (k in 0 until cols) s += this[i, k] * that[k, j]
                result[i, j] = s
            }
        }
        return result
    }

    /** result = this * that^T. */
    fun dotTranspose(that: KalmanMatrix, result: KalmanMatrix): KalmanMatrix {
        require(cols == that.cols && result.rows == rows && result.cols == that.rows)
        for (i in 0 until rows) {
            for (j in 0 until that.rows) {
                var s = 0.0
                for (k in 0 until cols) s += this[i, k] * that[j, k]
                result[i, j] = s
            }
        }
        return result
    }

    /** Inverts this square matrix in place, using scratch (rows x 2*cols). Gauss-Jordan. */
    fun inverseInPlace(scratch: KalmanMatrix): KalmanMatrix {
        require(rows == cols)
        require(scratch.rows == rows && scratch.cols == 2 * cols)
        val n = rows
        for (i in 0 until n) {
            for (j in 0 until n) {
                scratch[i, j] = this[i, j]
                scratch[i, n + j] = if (i == j) 1.0 else 0.0
            }
        }
        for (i in 0 until n) {
            var best = i
            var bestVal = kotlin.math.abs(scratch[best, i])
            for (r in i + 1 until n) {
                val v = kotlin.math.abs(scratch[r, i])
                if (v > bestVal) {
                    bestVal = v
                    best = r
                }
            }
            if (best != i) {
                for (j in 0 until 2 * n) {
                    val t = scratch[i, j]
                    scratch[i, j] = scratch[best, j]
                    scratch[best, j] = t
                }
            }
            val d = scratch[i, i]
            require(d != 0.0) { "singular matrix" }
            for (j in 0 until 2 * n) scratch[i, j] = scratch[i, j] / d
            for (r in 0 until n) {
                if (r == i) continue
                val f = scratch[r, i]
                if (f != 0.0) {
                    for (j in 0 until 2 * n) scratch[r, j] = scratch[r, j] - f * scratch[i, j]
                }
            }
        }
        for (i in 0 until n) {
            for (j in 0 until n) this[i, j] = scratch[i, n + j]
        }
        return this
    }

    companion object {
        fun identity(width: Int): KalmanMatrix {
            val out = KalmanMatrix(width, width)
            out.setIdentity()
            return out
        }
    }
}

/**
 * Kalman filter specialized to a 4-element state with scalar measurements
 * (position, velocity, acceleration, jank; measures position only).
 */
internal class KalmanFilter(xDim: Int, zDim: Int) {
    var x = KalmanMatrix(xDim, 1)
    var p = KalmanMatrix.identity(xDim)
    var q = KalmanMatrix.identity(xDim)
    var r = KalmanMatrix.identity(zDim)
    var f = KalmanMatrix(xDim, xDim)
    var h = KalmanMatrix(zDim, xDim)

    // Dedicated scratch: predict() never reads and writes the same matrix.
    private val nx = KalmanMatrix(xDim, 1)
    private val t1 = KalmanMatrix(xDim, xDim)
    private val t2 = KalmanMatrix(xDim, xDim)
    private val kGain = KalmanMatrix(xDim, 1)
    private val pRow0 = KalmanMatrix(1, xDim)

    /** Reset the state estimate (keeps F, Q, H, R tuning). */
    fun reset() {
        x.fill(0.0)
        p.setIdentity()
    }

    /** Predict the next state: x = F·x, P = F·P·Fᵀ + Q. */
    fun predict() {
        f.dot(x, nx)
        for (i in 0 until x.m.size) x.m[i] = nx.m[i]
        f.dot(p, t1)
        t1.dotTranspose(f, t2)
        for (i in 0 until p.m.size) p.m[i] = t2.m[i]
        p.addInPlace(q)
    }

    /** Fold scalar measurement [z] into the state estimate. */
    fun update(z: Double) {
        // Innovation: y = z - H·x (H selects the position component).
        var hx = 0.0
        for (k in 0 until h.cols) hx += h[0, k] * x[k, 0]
        val y = z - hx
        // Innovation covariance S = H·P·Hᵀ + R (1x1 with our H).
        var s = r[0, 0]
        for (a in 0 until h.cols) {
            for (b in 0 until h.cols) s += h[0, a] * p[a, b] * h[0, b]
        }
        if (!(s > 0.0) || !s.isFinite()) return
        // Kalman gain K = P·Hᵀ / S, then x += K·y and P -= K·(H·P row).
        for (i in 0 until x.rows) kGain[i, 0] = p[i, 0] / s
        // H selects row 0, so snapshot it before mutating P below.
        for (j in 0 until p.cols) pRow0[0, j] = p[0, j]
        for (i in 0 until x.rows) x[i, 0] = x[i, 0] + kGain[i, 0] * y
        for (i in 0 until p.rows) {
            val ki = kGain[i, 0]
            if (ki == 0.0) continue
            for (j in 0 until p.cols) p[i, j] = p[i, j] - ki * pRow0[0, j]
        }
    }
}
