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
 * - No extra smoothing: TYPE_ROTATION_VECTOR is already Android's fused sensor output.
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

                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            // TYPE_ROTATION_VECTOR is already Android's fused output (gyro +
                            // accelerometer + magnetometer), so it doesn't need a second
                            // low-pass filter on top. An earlier version smoothed the raw
                            // quaternion components with an EMA, which both lagged behind
                            // fast turns and could briefly invert the heading when a sample
                            // crossed the quaternion's sign ambiguity (q and -q are the same
                            // rotation). Feeding the sensor's output straight through avoids
                            // both problems.
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
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
