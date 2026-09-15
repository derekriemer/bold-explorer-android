package com.boldexplorer.compass

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.boldexplorer.shared.model.HeadingReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides true-north and magnetic heading via TYPE_ROTATION_VECTOR.
 *
 * Replaces the Capacitor Heading plugin entirely.
 * - Low-pass filter on raw quaternion components avoids heading jitter.
 * - GeomagneticField.declination converts magnetic to true north once a GPS fix is available.
 * - setLocation() should be called whenever the LocationViewModel receives a new fix.
 */
@Singleton
class SensorCompassProvider
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val sensorManager = context.getSystemService(SensorManager::class.java)
        private val rotationSensor: Sensor? =
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Updated by the ViewModel when a new GPS fix arrives; used for declination computation.
        private val _location = MutableStateFlow<Triple<Double, Double, Double>?>(null)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val headingFlow: SharedFlow<HeadingReading> =
            callbackFlow {
                val sensor =
                    rotationSensor ?: run {
                        // Device has no rotation vector sensor; channel stays empty.
                        awaitClose { }
                        return@callbackFlow
                    }

                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)

                // Low-pass filter state — fresh per callbackFlow invocation.
                // Alpha ≈ 0.35 at SENSOR_DELAY_GAME (~20ms/sample): time constant ~60ms,
                // fast enough to track a quick body turn while still damping jitter.
                val alpha = 0.35f
                val filtered = FloatArray(4) // x, y, z, w quaternion components
                var hasFilter = false

                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            val values = event.values
                            val len = minOf(values.size, filtered.size)

                            if (!hasFilter) {
                                for (i in 0 until len) filtered[i] = values[i]
                                hasFilter = true
                            } else {
                                // A unit quaternion q and -q represent the same rotation. The
                                // sensor can flip sign between samples; blending components
                                // straight across that flip would average two opposite
                                // rotations and briefly send the heading (and beacon pan) to
                                // the wrong side. Detect the flip via the dot product and
                                // negate the incoming sample to match the filter's hemisphere
                                // before blending.
                                var dot = 0f
                                for (i in 0 until len) dot += values[i] * filtered[i]
                                val sign = if (dot < 0f) -1f else 1f
                                for (i in 0 until len) {
                                    filtered[i] = alpha * (sign * values[i]) + (1f - alpha) * filtered[i]
                                }
                            }

                            SensorManager.getRotationMatrixFromVector(rotationMatrix, filtered)
                            SensorManager.getOrientation(rotationMatrix, orientation)

                            // orientation[0] = azimuth in radians; normalise to [0, 360)
                            val magneticDeg = ((Math.toDegrees(orientation[0].toDouble()) % 360) + 360) % 360

                            val loc = _location.value
                            val trueDeg: Double? =
                                if (loc != null) {
                                    val (lat, lon, alt) = loc
                                    val declination =
                                        GeomagneticField(
                                            lat.toFloat(),
                                            lon.toFloat(),
                                            alt.toFloat(),
                                            System.currentTimeMillis(),
                                        ).declination.toDouble()
                                    ((magneticDeg + declination) % 360 + 360) % 360
                                } else {
                                    null
                                }

                            trySend(
                                HeadingReading(
                                    magnetic = magneticDeg,
                                    trueNorth = trueDeg,
                                    timestamp = System.currentTimeMillis(),
                                ),
                            )
                        }

                        override fun onAccuracyChanged(
                            sensor: Sensor?,
                            accuracy: Int,
                        ) = Unit
                    }

                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                awaitClose { sensorManager.unregisterListener(listener) }
            }.shareIn(scope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

        /**
         * Provide a GPS fix so the compass can compute true-north declination.
         * Call this from LocationViewModel whenever the active location provider's locationFlow emits.
         */
        fun setLocation(
            lat: Double,
            lon: Double,
            altM: Double = 0.0,
        ) {
            _location.value = Triple(lat, lon, altM)
        }
    }
