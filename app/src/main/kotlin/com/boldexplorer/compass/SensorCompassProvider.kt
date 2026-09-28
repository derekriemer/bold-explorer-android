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

        // The location a declination was last computed for, and that declination — GeomagneticField
        // construction is a real World Magnetic Model computation, not a cheap lookup, and
        // onSensorChanged runs at up to ~50 Hz (SENSOR_DELAY_GAME) while setLocation() only updates
        // at GPS fix rate (~1 Hz) — recomputing on every sensor callback was rebuilding the identical
        // answer ~49 times out of every 50 (review finding, PR #145). Triple has structural equality,
        // so this still invalidates on genuine GPS jitter between fixes (the cache isn't distance-
        // thresholded) -- it just stops paying the cost again for the ~50 sensor callbacks that land
        // between one GPS fix and the next. The timestamp GeomagneticField also takes is not part of
        // the cache key: secular magnetic drift is a fraction of a degree per *year*, immaterial
        // against compass sensor noise within one navigation session.
        private var declinationCache: Pair<Triple<Double, Double, Double>, Double>? = null

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
                                loc?.let { ((magneticDeg + declinationFor(it)) % 360 + 360) % 360 }

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

        /**
         * Magnetic declination at [loc], degrees — cached against the last location it was computed
         * for (see [declinationCache]'s own doc). Only ever called from the sensor listener's
         * `onSensorChanged` callback above, so no explicit synchronization: it shares that callback's
         * already-single-threaded-in-practice assumption (the same one its own `rotationMatrix`/
         * `orientation` scratch arrays rely on), not a new one.
         */
        private fun declinationFor(loc: Triple<Double, Double, Double>): Double {
            declinationCache?.let { (cachedLoc, cachedDeclination) ->
                if (cachedLoc == loc) return cachedDeclination
            }
            val (lat, lon, alt) = loc
            val declination =
                GeomagneticField(
                    lat.toFloat(),
                    lon.toFloat(),
                    alt.toFloat(),
                    System.currentTimeMillis(),
                ).declination.toDouble()
            declinationCache = loc to declination
            return declination
        }
    }
