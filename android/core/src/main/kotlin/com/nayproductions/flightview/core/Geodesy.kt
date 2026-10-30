package com.nayproductions.flightview.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 geodesy. Mirrors shared/reference/flightmath.py exactly; any change
 * here must be made there first and the fixtures regenerated.
 */
object Geodesy {
    const val WGS84_A = 6378137.0
    const val WGS84_F = 1.0 / 298.257223563
    const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
    const val MEAN_EARTH_RADIUS_M = 6371008.8
    const val KNOT_TO_MPS = 0.514444
    const val FOOT_TO_M = 0.3048
    const val MAX_EXTRAPOLATION_S = 60.0

    fun geodeticToEcef(p: GeoPoint): Vec3 {
        val lat = Math.toRadians(p.latDeg)
        val lon = Math.toRadians(p.lonDeg)
        val s = sin(lat)
        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * s * s)
        return Vec3(
            (n + p.heightM) * cos(lat) * cos(lon),
            (n + p.heightM) * cos(lat) * sin(lon),
            (n * (1.0 - WGS84_E2) + p.heightM) * s,
        )
    }

    fun ecefDeltaToEnu(d: Vec3, lat0Deg: Double, lon0Deg: Double): Vec3 {
        val lat = Math.toRadians(lat0Deg)
        val lon = Math.toRadians(lon0Deg)
        val sl = sin(lat)
        val cl = cos(lat)
        val so = sin(lon)
        val co = cos(lon)
        return Vec3(
            -so * d.x + co * d.y,
            -sl * co * d.x - sl * so * d.y + cl * d.z,
            cl * co * d.x + cl * so * d.y + sl * d.z,
        )
    }

    fun enuOf(observer: GeoPoint, target: GeoPoint): Vec3 =
        ecefDeltaToEnu(geodeticToEcef(target) - geodeticToEcef(observer), observer.latDeg, observer.lonDeg)

    fun lookAngles(observer: GeoPoint, target: GeoPoint): LookAngles {
        val enu = enuOf(observer, target)
        val az = Math.toDegrees(atan2(enu.x, enu.y)).mod(360.0)
        val el = Math.toDegrees(atan2(enu.z, hypot(enu.x, enu.y)))
        return LookAngles(az, el, enu.length)
    }

    fun directionEnu(azimuthDeg: Double, elevationDeg: Double): Vec3 {
        val az = Math.toRadians(azimuthDeg)
        val el = Math.toRadians(elevationDeg)
        return Vec3(cos(el) * sin(az), cos(el) * cos(az), sin(el))
    }

    /** Advance a position along a great circle for [dtS] seconds (clamped to 0..60 s). */
    fun deadReckon(
        start: GeoPoint,
        groundSpeedKt: Double?,
        trackDeg: Double?,
        verticalRateFpm: Double?,
        dtS: Double,
    ): GeoPoint {
        val dt = max(0.0, min(dtS, MAX_EXTRAPOLATION_S))
        var lat = start.latDeg
        var lon = start.lonDeg
        if (groundSpeedKt != null && trackDeg != null && dt != 0.0) {
            val delta = groundSpeedKt * KNOT_TO_MPS * dt / MEAN_EARTH_RADIUS_M
            val theta = Math.toRadians(trackDeg)
            val phi1 = Math.toRadians(start.latDeg)
            val lam1 = Math.toRadians(start.lonDeg)
            val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
            val lam2 = lam1 + atan2(
                sin(theta) * sin(delta) * cos(phi1),
                cos(delta) - sin(phi1) * sin(phi2),
            )
            lat = Math.toDegrees(phi2)
            lon = (Math.toDegrees(lam2) + 540.0).mod(360.0) - 180.0
        }
        val alt = start.heightM + ((verticalRateFpm ?: 0.0) * FOOT_TO_M / 60.0) * dt
        return GeoPoint(lat, lon, max(alt, -500.0))
    }
}

/** Latitude/longitude in degrees, height in metres above the WGS84 ellipsoid. */
data class GeoPoint(val latDeg: Double, val lonDeg: Double, val heightM: Double)

data class LookAngles(val azimuthDeg: Double, val elevationDeg: Double, val slantRangeM: Double)
