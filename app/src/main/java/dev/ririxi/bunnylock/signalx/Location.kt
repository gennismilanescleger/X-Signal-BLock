// SignalX orientation providers (superprompt §6.2, §7; Fase 5).
//
// THE one location source and THE one heading source of the whole app:
// nothing else reads GPS or the compass sensors, so there is no second
// provider to drift apart from this one. Ported from SignalX 1.0.9
// (LocationProvider.kt / HeadingProvider.kt), unchanged in behavior:
// framework GPS (2 s) + network (5 s), last-known fix first; true-north
// heading from the rotation vector + magnetic declination, with a
// reliability flag from the magnetic sensor's accuracy.
//
// Lifecycle: MainViewModel starts them when the app is in the foreground
// (MainActivity onResume / permission grant) and stops them on pause —
// the same rule SignalX 1.0.9 used, so the GPS is not left running while
// the app is in the background. Measurements read the latest fix at save
// time; without a fix they store "not available", never 0.

package dev.ririxi.bunnylock.signalx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/** The user's position as the UI shows it. Null accuracy = unknown, never 0. */
data class UserPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val updatedAtMs: Long,
) {
    /** As a measurement location, without azimuth (the heading arrives separately). */
    fun toMeasurementLocation(azimuth: Float? = null): MeasurementLocation =
        MeasurementLocation(latitude, longitude, accuracy, azimuth)
}

/** One heading reading: true-north degrees + whether the sensor can be trusted. */
data class HeadingReading(
    val degrees: Float,
    val reliable: Boolean,
)

/** Signed difference between two bearings, in (-180, 180]. Negative = left. */
fun angleDiffDeg(
    targetDeg: Double,
    currentDeg: Double,
): Double {
    var d = targetDeg - currentDeg
    while (d > 180.0) d -= 360.0
    while (d < -180.0) d += 360.0
    return d
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Framework GPS. No internet needed: the fix comes from the phone's GPS. */
class LocationProvider(context: Context) {
    var lastLocation: Location? = null
        private set

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    fun start(onLocation: (Location) -> Unit) {
        if (listener != null) return
        try {
            lastLocation =
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastLocation?.let { onLocation(it) }
        } catch (_: SecurityException) {
            return
        }
        val l =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lastLocation = location
                    onLocation(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) {
                }
            }
        listener = l
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, l)
        } catch (_: SecurityException) {
            // No permission: the caller asked for it first.
        }
    }

    fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }
}

/**
 * True-north heading (geographic north) from the rotation vector +
 * magnetic declination. Reports whether the compass is trustworthy
 * (magnetic sensor accuracy). No sensor -> [hasSensor] is false and
 * start() is a no-op: the compass screen shows its no-sensor state
 * instead of a fake heading.
 */
class HeadingProvider(context: Context) : SensorEventListener {
    var headingDeg: Float? = null
        private set
    var reliable: Boolean = true
        private set

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** False on phones without a rotation-vector sensor: no heading at all. */
    val hasSensor: Boolean get() = rotationSensor != null

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastAlt: Float = 0f
    private var callback: ((Float, Boolean) -> Unit)? = null

    fun setLocation(
        lat: Double,
        lon: Double,
        alt: Float = 0f,
    ) {
        lastLat = lat
        lastLon = lon
        lastAlt = alt
    }

    fun start(onHeading: ((Float, Boolean) -> Unit)? = null) {
        callback = onHeading
        rotationSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magneticSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
        callback = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val orient = FloatArray(3)
        SensorManager.getOrientation(rot, orient)
        var az = Math.toDegrees(orient[0].toDouble()).toFloat()
        if (az < 0) az += 360f
        val lat = lastLat
        val lon = lastLon
        if (lat != null && lon != null) {
            val decl =
                GeomagneticField(
                    lat.toFloat(), lon.toFloat(), lastAlt, System.currentTimeMillis(),
                ).declination
            az += decl
            if (az >= 360f) az -= 360f
            if (az < 0f) az += 360f
        }
        headingDeg = az
        callback?.invoke(az, reliable)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            reliable =
                accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                    accuracy != SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }
}
