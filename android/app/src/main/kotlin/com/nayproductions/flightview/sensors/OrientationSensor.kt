package com.nayproductions.flightview.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.nayproductions.flightview.core.GeoPoint
import com.nayproductions.flightview.core.Mat3
import com.nayproductions.flightview.core.Projection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Device attitude relative to TRUE north.
 *
 * The fused rotation vector is referenced to magnetic north, so we rotate it by the local
 * magnetic declination (from the World Magnetic Model via [GeomagneticField]). In Cornwall
 * that is only a degree or two, but it is over 10 degrees in much of the world.
 *
 * The activity is locked to portrait, the natural orientation of phones, so the sensor
 * frame already matches the screen and no axis remapping is needed.
 *
 * The latest attitude is held in an [AtomicReference] and read each frame by the renderer;
 * only slow-changing state (calibration quality) is exposed as a Flow.
 */
class OrientationSensor(private val context: Context) : SensorEventListener {
    enum class Calibration { UNKNOWN, POOR, OK, GOOD }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = rotationSensor != null

    private val latest = AtomicReference<Mat3?>(null)
    /** ENU, true-north device attitude (device -> world), or null before the first sample. */
    val attitude: Mat3? get() = latest.get()

    private val _calibration = MutableStateFlow(Calibration.UNKNOWN)
    val calibration: StateFlow<Calibration> = _calibration

    @Volatile private var declinationDeg = 0f
    private val raw = FloatArray(9)

    fun start() {
        rotationSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    /** Call when the location changes; declination varies slowly so occasional updates suffice. */
    fun updateDeclination(at: GeoPoint) {
        declinationDeg = GeomagneticField(
            at.latDeg.toFloat(), at.lonDeg.toFloat(), at.heightM.toFloat(), System.currentTimeMillis(),
        ).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        // getRotationMatrixFromVector: device -> world (X east, Y magnetic north, Z up), row-major.
        val magneticEnu = Mat3.fromRowMajorFloats(raw)
        latest.set(Projection.rotateAboutUp(magneticEnu, declinationDeg.toDouble()))

        // values[4] is the estimated heading accuracy in radians (-1 when unknown).
        if (event.values.size > 4) {
            val acc = event.values[4]
            _calibration.value = when {
                acc < 0f -> Calibration.UNKNOWN
                acc > 0.35f -> Calibration.POOR // > ~20 degrees
                acc > 0.15f -> Calibration.OK
                else -> Calibration.GOOD
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) _calibration.value = Calibration.POOR
    }
}
