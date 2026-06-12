package com.boldexplorer.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import javax.inject.Inject

/**
 * Keeps the process alive and GPS running when the screen is off.
 *
 * Start via ACTION_START; it switches the active location provider to background mode
 * (50 m accuracy gate) and holds a subscription to keep the SharedFlow upstream active.
 * The ViewModel also subscribes to the same SharedFlow, so location updates continue
 * seamlessly when the user returns to the app.
 *
 * Stop via ACTION_STOP (or call stopSelf/stopService from the UI).
 */
@AndroidEntryPoint
class LocationForegroundService : Service() {
    @Inject
    lateinit var locationProvider: LocationProviderRouter

    @Inject
    lateinit var backgroundSession: GpsBackgroundSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                startTracking()
            }

            // null = restarted by Android after process kill
            ACTION_STOP -> {
                if (!backgroundSession.state.value.needsForegroundService) {
                    stopTracking()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        locationProvider.setBackgroundMode(true)

        // Subscribe to the shared flow so the GPS upstream stays active.
        // Actual location data is consumed by the ViewModel via the same SharedFlow.
        if (locationJob == null) {
            locationJob =
                locationProvider.locationFlow
                    .launchIn(scope)
        }
        backgroundSession.onServiceStarted()
    }

    private fun stopTracking() {
        locationProvider.setBackgroundMode(false)
        locationJob?.cancel()
        locationJob = null
        backgroundSession.onServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active GPS tracking for trail navigation"
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Bold Explorer")
            .setContentText("GPS tracking active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_START = "com.boldexplorer.location.START"
        const val ACTION_STOP = "com.boldexplorer.location.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bold_explorer_location"
    }
}
