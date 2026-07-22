package com.wsvdmeer.pwncompanion.services

import android.Manifest
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.PermissionChecker
import com.wsvdmeer.pwncompanion.models.ScreenData
import com.wsvdmeer.pwncompanion.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * GPS Service - Foreground Service managing location updates.
 * Provides continuous location tracking while app is in focus.
 * Broadcasts location data to WebSocket clients via NetworkService singleton.
 * Runs as foreground service to prevent system termination on low memory.
 */
class GpsService : Service() {
    private val tag = "GpsService"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var networkService: NetworkService? = null
    private var lastLocation: Location? = null  // Store last known location
    // Desired tracking state: we only poll GPS while a pwnagotchi is connected (battery). The
    // service stays alive regardless, so we never have to re-start a location foreground service
    // from the background (Android 12+ blocks that) — we just pause/resume the updates.
    @Volatile private var shouldTrack = false
    // Share the ONE network foreground notification (id 1000) instead of showing a second
    // GPS notification. Both foreground services hold the same id, so the shade shows a
    // single "live pet" notice; NetworkService keeps its content fresh.
    private val notificationId = NotificationHelper.NOTIFICATION_ID_NETWORK

    companion object {
        const val ACTION_STOP = "com.wsvdmeer.pwncompanion.GPS_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "GpsService created")

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        setupLocationListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "GpsService started (intent action: ${intent?.action})")

        // Handle Stop action from notification button
        if (intent?.action == ACTION_STOP) {
            Log.i(tag, "Stop action received — stopping GpsService")
            // DETACH (not remove): the shared notification (id 1000) belongs to the
            // network service, which may still be running — don't tear its notice down.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }

        // Ensure notification channels exist (in case service starts before CompanionBackgroundService)
        NotificationHelper.createNotificationChannels(this)
        // Reuse the shared network notification so both foreground services show as ONE.
        val notification = NotificationHelper.createNetworkServiceNotification(this)

        // MUST call startForeground() within the Android 5-second window — if we never call it
        // (even when catching an exception), Android fires ForegroundServiceDidNotStartInTimeException.
        //
        // Strategy:
        //   1. Try the typed variant (API 29+, Android 14 requires it for LOCATION type).
        //   2. If that throws (e.g. SecurityException on Android 14 when permission is momentarily
        //      unavailable), fall back to the un-typed startForeground() which always succeeds as
        //      long as the notification channel exists.
        //   3. Only call stopSelf() AFTER startForeground() has been called in some form — never
        //      before — so Android's timer is satisfied.
        var foregroundStarted = false
        try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION  // 0x8, matches manifest foregroundServiceType="location"
            )
            foregroundStarted = true
            Log.d(tag, "Foreground service started with LOCATION type")
        } catch (e: Exception) {
            Log.w(tag, "startForeground(LOCATION type) failed (${e.message}), trying without type…")
            try {
                @Suppress("DEPRECATION")
                startForeground(notificationId, notification)
                foregroundStarted = true
                Log.d(tag, "Foreground service started (no type fallback)")
            } catch (e2: Exception) {
                Log.e(tag, "startForeground() fallback also failed: ${e2.message}", e2)
            }
        }

        if (!foregroundStarted) {
            // startForeground() was never called — stop cleanly.  Android will still fire
            // ForegroundServiceDidNotStartInTimeException, but at least the service doesn't hang.
            Log.e(tag, "Could not become foreground service in any mode — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        // CRITICAL FIX: Use NetworkService singleton instead of creating new instance
        // This prevents lifecycle/threading crashes when sending location data
        scope.launch {
            try {
                if (networkService == null) {
                    networkService = NetworkServiceSingleton.getInstance(this@GpsService)
                    Log.i(tag, "✓ Got NetworkService singleton instance for GPS broadcasting")
                }
                // Poll GPS only while a device is connected — resume updates when the link
                // comes up, pause them when the last device drops (saves battery; before, GPS
                // polled every second from launch forever). This collect suspends for the
                // service's lifetime.
                networkService?.connectedDeviceCount?.collect { count ->
                    if (count > 0) {
                        if (!shouldTrack) { shouldTrack = true; resumeLocationUpdates() }
                    } else {
                        if (shouldTrack) { shouldTrack = false; pauseLocationUpdates() }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in GPS connection-gating: ${e.message}")
            }
        }

        // NOT sticky: GPS is bound to the companion service's lifetime (started in the
        // foreground alongside it), so it shouldn't independently resurrect with a null intent.
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission")  // Checked in hasLocationPermissions()
    override fun onDestroy() {
        // Detach (don't remove) the shared notification first — if this service is torn
        // down while NetworkService still runs, its id-1000 notice must survive.
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) } catch (_: Exception) {}
        super.onDestroy()
        Log.i(tag, "GpsService destroyed, stopping location updates")

        // Stop listening for location updates. removeUpdates needs no permission, so DON'T
        // gate it on hasLocationPermissions() — if permission was revoked mid-run, the guard
        // would skip unregistration and leak the listener. locationListener is null only if
        // setupLocationListener() bailed, hence ?.let.
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) {
            Log.e(tag, "Error removing location updates: ${e.message}")
        }

        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Setup location listener with accuracy preference.
     * Uses GPS when available, falls back to NETWORK provider.
     */
    private fun setupLocationListener() {
        if (!hasLocationPermissions()) {
            Log.w(tag, "Location permissions not granted, cannot setup listener")
            return
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocationUpdate(location)
            }

            override fun onProviderEnabled(provider: String) {
                Log.i(tag, "Location provider enabled: $provider")
                if (shouldTrack) resumeLocationUpdates()
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(tag, "Location provider disabled: $provider")
            }

            @Deprecated("Deprecated in API 31")
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
                Log.d(tag, "Provider status changed: $provider = $status")
            }
        }
        // NB: don't request updates here — the connectedDeviceCount collector in onStartCommand
        // resumes them only while a device is linked (see shouldTrack).
    }

    /**
     * Resume location updates from best available provider.
     */
    @android.annotation.SuppressLint("MissingPermission")  // Checked in hasLocationPermissions()
    private fun resumeLocationUpdates() {
        if (!hasLocationPermissions()) return

        // Re-register cleanly so repeated resumes (provider re-enable, reconnect) don't stack.
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}

        try {
            val providers = mutableListOf<String>()

            // Prioritize GPS
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                providers.add(LocationManager.GPS_PROVIDER)
            }

            // Add NETWORK as fallback
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                providers.add(LocationManager.NETWORK_PROVIDER)
            }

            if (providers.isEmpty()) {
                Log.w(tag, "No location providers available")
                return
            }

            for (provider in providers) {
                try {
                    // ~4 s is plenty for geotagging captures + motion; 1 s was needless drain.
                    locationManager?.requestLocationUpdates(
                        provider,
                        4000,    // Min time interval: 4 seconds
                        0f,      // Min distance: 0 meters
                        locationListener!!
                    )
                    Log.i(tag, "Location updates requested from provider: $provider")
                } catch (e: Exception) {
                    Log.e(tag, "Error requesting updates from $provider: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error resuming location updates: ${e.message}")
        }
    }

    /** Stop polling GPS (no device connected) while keeping the service alive. */
    private fun pauseLocationUpdates() {
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
            Log.i(tag, "Location updates paused (no connected device)")
        } catch (e: Exception) {
            Log.e(tag, "Error pausing location updates: ${e.message}")
        }
    }

    private var lastNotificationUpdateMs = 0L

    /**
     * Handle location update - broadcast via NetworkService singleton.
     * CRITICAL: Only broadcasts if there are connected devices to avoid wasting battery.
     */
    private fun handleLocationUpdate(location: Location) {
        try {
            Log.d(tag, "Location update: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m")
            
            // Store last known location
            lastLocation = location

            // No separate GPS notification anymore — the single shared notification (owned by
            // NetworkService, id 1000) carries status; GPS coords don't need their own notice.

            // Broadcast location to all connected WebSocket clients via the outgoing message queue
            scope.launch {
                try {
                    if (networkService != null) {
                        val outgoingQueue = networkService?.getOutgoingMessageQueue()
                        val deviceStates = networkService?.getDeviceStates()
                        
                        // Only send if there are connected devices (avoid wasting resources)
                        if (deviceStates != null && deviceStates.isNotEmpty()) {
                            for ((deviceId, _) in deviceStates) {
                                try {
                                    outgoingQueue?.queueLocationResponse(
                                        deviceId = deviceId,
                                        latitude = location.latitude,
                                        longitude = location.longitude,
                                        accuracy = location.accuracy,
                                        altitude = location.altitude
                                    )
                                    Log.d(tag, "✓ Location queued for device: $deviceId (lat=${location.latitude}, lon=${location.longitude})")
                                } catch (e: Exception) {
                                    Log.e(tag, "Error queueing location for device $deviceId: ${e.message}")
                                }
                            }

                            // Cache this GPS fix in NetworkService so it can be returned immediately
                            // to gps_request messages and to newly connecting devices.
                            val gpsSnapshot = ScreenData(
                                type = ScreenData.TYPE_GPS,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy.toDouble(),
                                altitude = location.altitude,
                                // Hardware speed (m/s) when the chipset reports it — the
                                // reliable motion signal (Doppler); null → app differences positions.
                                speed = if (location.hasSpeed()) location.speed else null,
                                timestamp = System.currentTimeMillis()
                            )
                            networkService?.updateLastGpsData(gpsSnapshot)
                        } else {
                            Log.d(tag, "No connected devices, skipping location broadcast")
                        }
                    } else {
                        Log.w(tag, "NetworkService not initialized, cannot broadcast location")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error broadcasting location: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling location update: ${e.message}", e)
        }
    }

    /**
     * Get the last known location.
     * Used when Pwnagotchi requests GPS data.
     */
    fun getLastLocation(): Location? {
        Log.d(tag, "getLastLocation() called, lastLocation=${lastLocation?.let { "lat=${it.latitude}, lon=${it.longitude}" } ?: "null"}")
        return lastLocation
    }

    /**
     * Check if required location permissions are granted.
     */
    private fun hasLocationPermissions(): Boolean {
        val fineLocation = PermissionChecker.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED

        val coarseLocation = PermissionChecker.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }
}
