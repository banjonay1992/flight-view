package com.nayproductions.flightview.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nayproductions.flightview.FlightViewApp
import com.nayproductions.flightview.camera.CameraOptics
import com.nayproductions.flightview.core.Aircraft
import com.nayproductions.flightview.core.AircraftMerge
import com.nayproductions.flightview.core.GeoPoint
import com.nayproductions.flightview.core.Intrinsics
import com.nayproductions.flightview.core.LabelLayout
import com.nayproductions.flightview.core.LabelSize
import com.nayproductions.flightview.core.Mat3
import com.nayproductions.flightview.core.PlacedLabel
import com.nayproductions.flightview.core.Projection
import com.nayproductions.flightview.core.SceneConfig
import com.nayproductions.flightview.core.ScreenSmoother
import com.nayproductions.flightview.core.SkyMarker
import com.nayproductions.flightview.core.SkyScene
import com.nayproductions.flightview.core.Vec3
import com.nayproductions.flightview.data.AircraftApi
import com.nayproductions.flightview.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

enum class SkyStatus { OK, LOCATING, BUSY, ERROR }

data class SkyFrame(
    val markers: List<SkyMarker>,
    val labels: List<PlacedLabel>,
    val smoothed: Map<String, Pair<Float, Float>>,
)

class SkyViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as FlightViewApp).container
    private val prefs = app.getSharedPreferences("flightview", Context.MODE_PRIVATE)

    private val _aircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    val aircraft: StateFlow<List<Aircraft>> = _aircraft.asStateFlow()
    private val _attribution = MutableStateFlow<List<String>>(emptyList())
    val attribution: StateFlow<List<String>> = _attribution.asStateFlow()
    private val _status = MutableStateFlow(SkyStatus.LOCATING)
    val status: StateFlow<SkyStatus> = _status.asStateFlow()
    private val _observer = MutableStateFlow<GeoPoint?>(null)
    val observer: StateFlow<GeoPoint?> = _observer.asStateFlow()
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()
    private val _nudge = MutableStateFlow(prefs.getFloat(KEY_NUDGE, 0f).toDouble())
    val headingNudgeDeg: StateFlow<Double> = _nudge.asStateFlow()

    val calibration = c.orientation.calibration
    val sensorsAvailable get() = c.orientation.isAvailable

    @Volatile var optics: CameraOptics = CameraOptics.DEFAULT
    private val smoother = ScreenSmoother()
    private var jobs: List<Job> = emptyList()

    @SuppressLint("MissingPermission") // only started after permissions are granted
    fun start() {
        if (jobs.isNotEmpty()) return
        c.orientation.start()
        jobs = listOf(
            viewModelScope.launch {
                c.location.updates().collect { p ->
                    _observer.value = p
                    c.orientation.updateDeclination(p)
                }
            },
            viewModelScope.launch {
                while (isActive) {
                    poll()
                    delay(POLL_MS)
                }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        c.orientation.stop()
    }

    override fun onCleared() = stop()

    private suspend fun poll() {
        val obs = _observer.value ?: run { _status.value = SkyStatus.LOCATING; return }
        val (tileLat, tileLon) = AircraftApi.tileCentre(obs.latDeg, obs.lonDeg)
        // The Worker and the phone's own feeds run in parallel; whichever answers is used.
        val (worker, direct) = coroutineScope {
            val w = async { runCatching { c.api.fetch(obs.latDeg, obs.lonDeg, RADIUS_KM) } }
            val d = async { runCatching { c.directFeeds.poll(tileLat, tileLon, RADIUS_KM + 10) } }
            w.await() to d.await()
        }
        (worker.exceptionOrNull() ?: direct.exceptionOrNull())?.let { if (it is CancellationException) throw it }
        val workerRes = worker.getOrNull()
        val (directLists, directAttribution) = direct.getOrNull() ?: (emptyList<List<Aircraft>>() to emptyList())
        if (workerRes == null && directLists.isEmpty()) {
            // Keep the last good data; dead reckoning carries it for a while.
            _status.value = if (worker.exceptionOrNull() is ApiException.Busy) SkyStatus.BUSY else SkyStatus.ERROR
            return
        }
        val lists = listOfNotNull(workerRes?.aircraft?.map { it.toCore() }) + directLists
        _aircraft.value = AircraftMerge.merge(lists)
        _attribution.value = ((workerRes?.attribution ?: emptyList()) + directAttribution).distinct()
        _status.value = SkyStatus.OK
    }

    private val nowS get() = System.currentTimeMillis() / 1000.0 + c.api.clockOffsetS

    private fun attitude(): Mat3? = c.orientation.attitude?.let { Projection.rotateAboutUp(it, _nudge.value) }

    fun frame(w: Float, h: Float): SkyFrame? {
        val obs = _observer.value ?: return null
        val r = attitude() ?: return null
        val o = optics
        val k = Intrinsics.aspectFill(o.longSideFovDeg, o.imageAspect, w.toDouble(), h.toDouble())
        val markers = SkyScene.build(_aircraft.value, obs, nowS, r, k, w.toDouble(), h.toDouble())
        val t = System.nanoTime() / 1e9
        val smoothed = HashMap<String, Pair<Float, Float>>()
        for (m in markers) if (m.screen.onScreen) {
            val (x, y) = smoother.smooth(m.aircraft.id, m.screen.x, m.screen.y, t)
            smoothed[m.aircraft.id] = x.toFloat() to y.toFloat()
        }
        smoother.retain(smoothed.keys)
        val anchors = markers.mapNotNull { m ->
            smoothed[m.aircraft.id]?.let { (x, y) -> Triple(m.aircraft.id, x.toDouble(), y.toDouble()) }
        }
        val sizes = anchors.associate { it.first to LabelSize(labelW.toDouble(), labelH.toDouble()) }
        return SkyFrame(markers, LabelLayout.place(anchors, sizes, w.toDouble(), h.toDouble()), smoothed)
    }

    /** Label size in pixels; set by the UI from dp so layout matches what is drawn. */
    var labelW = 360f
    var labelH = 110f

    /** 44 dp, matching the iOS 44 pt touch target; set by the UI. */
    var tapRadiusPx = 120f

    fun select(x: Float, y: Float, frame: SkyFrame) {
        val candidates = frame.markers.mapNotNull { m ->
            frame.smoothed[m.aircraft.id]?.let { (sx, sy) -> m.copy(screen = m.screen.copy(x = sx.toDouble(), y = sy.toDouble())) }
        }
        _selectedId.value = SkyScene.hitTest(candidates, x.toDouble(), y.toDouble(), radius = tapRadiusPx.toDouble())?.aircraft?.id
    }

    fun select(id: String?) {
        _selectedId.value = id
    }

    fun setNudge(deg: Double) {
        val v = deg.coerceIn(-45.0, 45.0)
        _nudge.value = v
        prefs.edit().putFloat(KEY_NUDGE, v.toFloat()).apply()
    }

    /** Everything in range regardless of camera direction (list view and detail sheet). */
    fun nearby(): List<SkyMarker> {
        val obs = _observer.value ?: return emptyList()
        return SkyScene.build(_aircraft.value, obs, nowS, Mat3.IDENTITY, Intrinsics(1.0, 1.0, 0.0, 0.0), 1.0, 1.0)
    }

    fun snapshot(id: String): SkyMarker? {
        val obs = _observer.value ?: return null
        val a = _aircraft.value.firstOrNull { it.id == id } ?: return null
        return SkyScene.build(
            listOf(a), obs, nowS, Mat3.IDENTITY, Intrinsics(1.0, 1.0, 0.0, 0.0), 1.0, 1.0,
            SceneConfig(maxRangeM = Double.POSITIVE_INFINITY, minElevationDeg = -90.0),
        ).firstOrNull()
    }

    val cameraHeadingDeg: Double?
        get() {
            val axis = attitude()?.times(Vec3(0.0, 0.0, -1.0)) ?: return null
            if (hypot(axis.x, axis.y) < 0.2) return null
            return Math.toDegrees(atan2(axis.x, axis.y)).mod(360.0)
        }

    fun save(marker: SkyMarker, onDone: (Boolean) -> Unit) {
        val obs = _observer.value ?: return onDone(false)
        viewModelScope.launch {
            onDone(runCatching { c.logbook.save(marker, obs) }.isSuccess)
        }
    }

    companion object {
        private const val KEY_NUDGE = "headingNudgeDeg"
        private const val POLL_MS = 2_000L
        private const val RADIUS_KM = 80
    }
}
