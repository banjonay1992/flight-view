package com.nayproductions.flightview.core

import kotlin.math.sqrt

/** Minimal 3-vector. Kept dependency-free so the core module is plain JVM. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val l = length
        return if (l == 0.0) this else Vec3(x / l, y / l, z / l)
    }
}

/**
 * Row-major 3x3 matrix. Used for device attitude `R_dw`, which maps a vector
 * in device coordinates to ENU world coordinates: `w = R_dw * d`.
 */
class Mat3(private val m: DoubleArray) {
    init {
        require(m.size == 9) { "Mat3 needs 9 values" }
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += this[i, k] * o[k, j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun transposed() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    /** Transpose-multiply without allocating the transpose: `R^T * v`. */
    fun transposeTimes(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    fun toArray(): DoubleArray = m.copyOf()

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun rows(r0: DoubleArray, r1: DoubleArray, r2: DoubleArray) =
            Mat3(doubleArrayOf(r0[0], r0[1], r0[2], r1[0], r1[1], r1[2], r2[0], r2[1], r2[2]))

        /** Android `SensorManager.getRotationMatrixFromVector` fills a row-major 9-array. */
        fun fromRowMajorFloats(values: FloatArray): Mat3 {
            require(values.size >= 9)
            return Mat3(DoubleArray(9) { values[it].toDouble() })
        }
    }
}
