package com.nayproductions.flightview.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** One aircraft as returned by the Flight View API (`/v1/aircraft`). */
data class Aircraft(
    val id: String,
    val callsign: String?,
    val registration: String?,
    val typeCode: String?,
    val typeName: String?,
    val operator: String?,
    val latDeg: Double,
    val lonDeg: Double,
    val altGeomM: Double?,
    val altBaroM: Double?,
    val groundSpeedKt: Double?,
    val trackDeg: Double?,
    val verticalRateFpm: Double?,
    /** Unix seconds at which the position was measured. */
    val positionTime: Double,
    val onGround: Boolean,
    val category: String?,
    val squawk: String?,
) {
    /** Prefer geometric (GNSS, ellipsoidal) height; barometric altitude can differ by hundreds of metres. */
    val bestHeightM: Double? get() = altGeomM ?: altBaroM
    val displayName: String get() = callsign?.trim()?.ifEmpty { null } ?: registration ?: id.uppercase()
}

data class SkyMarker(
    val aircraft: Aircraft,
    val position: GeoPoint,
    val look: LookAngles,
    val screen: ScreenPoint,
    /** Valid when the aircraft is not on screen: direction to turn (0 = right, pi/2 = up). */
    val edgeAngleRad: Double,
    val positionAgeS: Double,
) {
    val isStale: Boolean get() = positionAgeS > SkyScene.STALE_AFTER_S
}

data class SceneConfig(
    val maxRangeM: Double = 80_000.0,
    /** Slightly negative so aircraft on the visual horizon are not dropped by GPS height error. */
    val minElevationDeg: Double = -1.0,
    val includeOnGround: Boolean = false,
)

object SkyScene {
    const val STALE_AFTER_S = 30.0
    const val DROP_AFTER_S = 120.0

    /**
     * Build the visible scene for one frame.
     *
     * @param nowS current time on the server clock (device time + measured offset), unix seconds.
     * @param rDw device attitude, device -> ENU, already corrected to true north.
     */
    fun build(
        aircraft: List<Aircraft>,
        observer: GeoPoint,
        nowS: Double,
        rDw: Mat3,
        k: Intrinsics,
        viewW: Double,
        viewH: Double,
        config: SceneConfig = SceneConfig(),
    ): List<SkyMarker> {
        val out = ArrayList<SkyMarker>(aircraft.size)
        for (a in aircraft) {
            if (a.onGround && !config.includeOnGround) continue
            val h = a.bestHeightM ?: continue
            val age = nowS - a.positionTime
            if (age > DROP_AFTER_S) continue
            val pos = Geodesy.deadReckon(
                GeoPoint(a.latDeg, a.lonDeg, h), a.groundSpeedKt, a.trackDeg, a.verticalRateFpm, age,
            )
            val look = Geodesy.lookAngles(observer, pos)
            if (look.slantRangeM > config.maxRangeM || look.elevationDeg < config.minElevationDeg) continue
            val dir = Geodesy.directionEnu(look.azimuthDeg, look.elevationDeg)
            val screen = Projection.project(rDw, dir, k, viewW, viewH)
            out += SkyMarker(a, pos, look, screen, Projection.edgeAngleRad(rDw, dir), age)
        }
        out.sortBy { it.look.slantRangeM }
        return out
    }

    /** Nearest on-screen marker to a tap, within [radius] view units. */
    fun hitTest(markers: List<SkyMarker>, x: Double, y: Double, radius: Double = 44.0): SkyMarker? =
        markers.asSequence()
            .filter { it.screen.onScreen }
            .map { it to hypot(it.screen.x - x, it.screen.y - y) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first
}

/**
 * Exponential smoothing of marker positions in screen space. Sensor jitter is
 * far more visible than a few frames of lag, but a large jump (the user swung
 * the phone) snaps immediately so labels never trail across the screen.
 */
class ScreenSmoother(private val timeConstantS: Double = 0.12, private val snapDistance: Double = 160.0) {
    private data class State(val x: Double, val y: Double, val t: Double)
    private val states = HashMap<String, State>()

    fun smooth(id: String, x: Double, y: Double, tS: Double): Pair<Double, Double> {
        val prev = states[id]
        val next = if (prev == null || tS - prev.t > 0.5 || hypot(x - prev.x, y - prev.y) > snapDistance) {
            State(x, y, tS)
        } else {
            val alpha = 1.0 - exp(-(tS - prev.t).coerceAtLeast(0.0) / timeConstantS)
            State(prev.x + (x - prev.x) * alpha, prev.y + (y - prev.y) * alpha, tS)
        }
        states[id] = next
        return next.x to next.y
    }

    fun retain(ids: Set<String>) {
        states.keys.retainAll(ids)
    }
}

data class LabelSize(val width: Double, val height: Double)

data class PlacedLabel(val id: String, val left: Double, val top: Double, val width: Double, val height: Double)

/**
 * Greedy label declutter. Markers are placed nearest first; each label tries a
 * small ladder of vertical offsets above its marker and is dropped (dot only)
 * if it cannot find space. Deterministic, O(n^2) on at most [maxLabels].
 */
object LabelLayout {
    private const val LABEL_SPACING = 4.0

    fun place(
        anchors: List<Triple<String, Double, Double>>,
        sizes: Map<String, LabelSize>,
        viewW: Double,
        viewH: Double,
        markerGap: Double = 14.0,
        maxLabels: Int = 14,
    ): List<PlacedLabel> {
        val placed = ArrayList<PlacedLabel>()
        for ((id, ax, ay) in anchors) {
            if (placed.size >= maxLabels) break
            val size = sizes[id] ?: continue
            val row = size.height + LABEL_SPACING
            val above = ay - markerGap - size.height
            val below = ay + markerGap
            // Preferred slots: directly above, stacked above, directly below, then further out.
            val tops = doubleArrayOf(above, above - row, below, above - 2 * row, below + row)
            val left = (ax - size.width / 2.0).coerceIn(4.0, maxOf(4.0, viewW - size.width - 4.0))
            for (top in tops) {
                if (top < 0.0 || top + size.height > viewH) continue
                val candidate = PlacedLabel(id, left, top, size.width, size.height)
                if (placed.none { overlaps(it, candidate) }) {
                    placed += candidate
                    break
                }
            }
        }
        return placed
    }

    private fun overlaps(a: PlacedLabel, b: PlacedLabel): Boolean =
        abs((a.left + a.width / 2) - (b.left + b.width / 2)) * 2 < a.width + b.width &&
            abs((a.top + a.height / 2) - (b.top + b.height / 2)) * 2 < a.height + b.height
}
