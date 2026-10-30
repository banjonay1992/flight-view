package com.nayproductions.flightview.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Pinhole intrinsics in view units (dp on Android, points on iOS). */
data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    companion object {
        /**
         * Intrinsics for a portrait preview that aspect-fills its view.
         *
         * @param longSideFovDeg field of view along the sensor's long side.
         * @param imageAspect long side / short side of the camera stream (e.g. 4/3).
         */
        fun aspectFill(longSideFovDeg: Double, imageAspect: Double, viewW: Double, viewH: Double): Intrinsics {
            val fImg = (imageAspect / 2.0) / tan(Math.toRadians(longSideFovDeg) / 2.0)
            val scale = max(viewW / 1.0, viewH / imageAspect)
            val f = fImg * scale
            return Intrinsics(f, f, viewW / 2.0, viewH / 2.0)
        }
    }
}

data class ScreenPoint(
    val x: Double,
    val y: Double,
    val depth: Double,
    val inFront: Boolean,
    val onScreen: Boolean,
)

object Projection {
    /**
     * Project a unit ENU direction onto the screen.
     * Device frame: +X right, +Y up, +Z towards the user; the rear camera looks along -Z.
     */
    fun project(rDw: Mat3, worldDir: Vec3, k: Intrinsics, viewW: Double, viewH: Double): ScreenPoint {
        val d = rDw.transposeTimes(worldDir)
        val zc = -d.z
        if (zc <= 1e-6) return ScreenPoint(Double.NaN, Double.NaN, zc, inFront = false, onScreen = false)
        val x = k.cx + k.fx * d.x / zc
        val y = k.cy - k.fy * d.y / zc
        val on = x in 0.0..viewW && y in 0.0..viewH
        return ScreenPoint(x, y, zc, inFront = true, onScreen = on)
    }

    /** Screen direction to turn towards a target (0 = right, +pi/2 = up). */
    fun edgeAngleRad(rDw: Mat3, worldDir: Vec3): Double {
        val d = rDw.transposeTimes(worldDir)
        return atan2(d.y, d.x)
    }

    /**
     * Apply a heading correction (degrees clockwise seen from above) to an attitude.
     * Used for magnetic declination on Android and for the user's manual nudge.
     */
    fun rotateAboutUp(rDw: Mat3, degreesClockwise: Double): Mat3 {
        val d = Math.toRadians(degreesClockwise)
        val c = cos(d)
        val s = sin(d)
        val rz = Mat3(doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0))
        return rz * rDw
    }

    /** Convert a CoreMotion xTrueNorthZVertical (N, W, U) attitude to ENU. Shared for parity tests. */
    fun referenceNwuToEnu(rDeviceToRef: Mat3): Mat3 {
        val c = Mat3(doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        return c * rDeviceToRef
    }
}
