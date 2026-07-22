package com.wsvdmeer.pwncompanion.workers

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.PermissionChecker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wsvdmeer.pwncompanion.models.LocationData
import com.wsvdmeer.pwncompanion.models.ScreenData
import com.wsvdmeer.pwncompanion.services.NetworkServiceSingleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WorkManager task for periodic GPS location tracking.
 * Periodically requests location updates and broadcasts to connected WebSocket clients.
 * Runs on user-defined intervals (default: every 30 seconds).
 * Battery-aware: Will respect device battery saver mode via WorkManager constraints.
 */
class GpsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val tag = "GpsWorker"

    override suspend fun doWork(): Result {
        return try {
            Log.i(tag, "GPS worker started")

            // Check permissions
            if (!hasLocationPermissions()) {
                Log.w(tag, "Location permissions not granted, skipping GPS update")
                return Result.success() // Don't retry, just skip
            }

            // Get last known location
            val location = getLastKnownLocation()

            if (location != null) {
                Log.i(tag, "Location obtained: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")

                // Convert to LocationData
                val locationData = LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    bearing = location.bearing,
                    speed = location.speed,
                    timestamp = location.time,
                    provider = location.provider
                )

                // Coarse periodic fallback broadcast. The real-time GPS path is
                // GpsService; WorkManager periodic work can't run more often than
                // ~15 min, so this only tops up the cached fix for connected devices.
                broadcastLocation(location)

                Result.success()
            } else {
                Log.w(tag, "No location available from any provider")
                Result.success() // Don't retry on location unavailable
            }
        } catch (e: Exception) {
            Log.e(tag, "GPS worker error: ${e.message}", e)
            // Retry with exponential backoff
            Result.retry()
        }
    }

    /**
     * Push a location fix to connected devices via the shared NetworkService,
     * mirroring GpsService.handleLocationUpdate. No-op if networking isn't up or
     * no devices are connected (avoids waking the radio for nothing).
     */
    private fun broadcastLocation(location: Location) {
        val networkService = NetworkServiceSingleton.getInstanceOrNull()
        if (networkService == null) {
            Log.d(tag, "NetworkService not running, skipping periodic GPS broadcast")
            return
        }
        val deviceStates = networkService.getDeviceStates()
        if (deviceStates.isNullOrEmpty()) {
            Log.d(tag, "No connected devices, skipping periodic GPS broadcast")
            return
        }
        val outgoingQueue = networkService.getOutgoingMessageQueue()
        for ((deviceId, _) in deviceStates) {
            try {
                outgoingQueue?.queueLocationResponse(
                    deviceId = deviceId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    altitude = location.altitude
                )
            } catch (e: Exception) {
                Log.e(tag, "Error queueing periodic location for $deviceId: ${e.message}")
            }
        }
        networkService.updateLastGpsData(
            ScreenData(
                type = ScreenData.TYPE_GPS,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy.toDouble(),
                altitude = location.altitude,
                timestamp = System.currentTimeMillis()
            )
        )
        Log.i(tag, "Periodic GPS broadcast queued for ${deviceStates.size} device(s)")
    }

    /**
     * Get last known location from all available providers.
     * Prioritizes:
     * 1. GPS (most accurate)
     * 2. NETWORK (cell/WiFi)
     * 3. FUSED (if available via Play Services)
     */
    private suspend fun getLastKnownLocation(): Location? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var bestLocation: Location? = null

                // Try GPS first
                if (hasLocationPermissions() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    try {
                        val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (gpsLocation != null && isBetterLocation(gpsLocation, bestLocation)) {
                            bestLocation = gpsLocation
                        }
                    } catch (e: SecurityException) {
                        Log.w(tag, "GPS permission denied")
                    }
                }

                // Try NETWORK as fallback
                if (hasLocationPermissions() && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    try {
                        val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (networkLocation != null && isBetterLocation(networkLocation, bestLocation)) {
                            bestLocation = networkLocation
                        }
                    } catch (e: SecurityException) {
                        Log.w(tag, "Network location permission denied")
                    }
                }

                continuation.resume(bestLocation)
            } catch (e: Exception) {
                Log.e(tag, "Error getting location: ${e.message}")
                continuation.resume(null)
            }
        }
    }

    /**
     * Determine if new location is better than current best.
     * Considers accuracy and age of location.
     */
    private fun isBetterLocation(location: Location, currentBest: Location?): Boolean {
        // If we don't have a current best, this is better
        if (currentBest == null) return true

        // Check if location is newer
        val timeDelta = location.time - currentBest.time
        val isNewer = timeDelta > 0

        // Check if location is more accurate
        val accuracyDelta = location.accuracy - currentBest.accuracy
        val isMoreAccurate = accuracyDelta < 0

        // If location is newer AND at least as accurate, it's better
        if (isNewer && accuracyDelta <= 0) return true

        // If location is significantly more accurate, it's better
        // (even if slightly older, but not more than 1 minute)
        if (isMoreAccurate && timeDelta > -60_000) return true

        return false
    }

    /**
     * Check if required location permissions are granted.
     */
    private fun hasLocationPermissions(): Boolean {
        val context = applicationContext
        val fineLocation = PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED

        val coarseLocation = PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }
}
