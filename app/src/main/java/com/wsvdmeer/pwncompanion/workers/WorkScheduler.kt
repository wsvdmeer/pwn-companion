package com.wsvdmeer.pwncompanion.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkScheduler utility for managing background work lifecycle.
 * Handles constraint configuration and work scheduling/cancellation.
 * Respects battery, network, and device conditions via WorkManager.
 */
object WorkScheduler {
    private const val TAG = "WorkScheduler"

    // Work names
    private const val WEBSOCKET_WORK = "websocket_server"
    private const val GPS_WORK = "gps_tracking"

    /**
     * Schedule WebSocket server as foreground work.
     * Runs continuously as long as constraints are satisfied.
     * Requires: Network connectivity, not on battery saver mode.
     */
    fun scheduleWebSocketServer(context: Context) {
        try {
            Log.i(TAG, "Scheduling WebSocket server work")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)  // Must have network
                .setRequiresBatteryNotLow(true)                 // Don't run on low battery
                .setRequiresDeviceIdle(false)                   // Can run while device is in use
                .build()

            // One-time work for WebSocket (long-running foreground)
            val webSocketWorkRequest = OneTimeWorkRequestBuilder<WebSocketWorker>()
                .setConstraints(constraints)
                .build()

            // Enqueue with KEEP policy - don't replace if already running
            WorkManager.getInstance(context).enqueueUniqueWork(
                WEBSOCKET_WORK,
                ExistingWorkPolicy.KEEP,
                webSocketWorkRequest
            )

            Log.i(TAG, "WebSocket server work scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule WebSocket work: ${e.message}", e)
        }
    }

    /**
     * Schedule the periodic GPS worker as a COARSE fallback only.
     *
     * WorkManager clamps periodic work to a 15-minute (900 s) minimum, so the
     * requested [intervalSeconds] is floored to 900 — sub-minute GPS is not
     * possible here and is handled in real time by GpsService instead. Passing a
     * smaller value is accepted but has no effect; the effective interval is logged.
     */
    fun scheduleGpsTracking(context: Context, intervalSeconds: Long = 900) {
        try {
            Log.i(TAG, "Scheduling GPS tracking work (interval: ${intervalSeconds}s)")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)  // Send to WebSocket
                .setRequiresBatteryNotLow(true)                 // Preserve battery
                .setRequiresDeviceIdle(false)                   // Can run anytime
                .build()

            // Periodic work - use at least 900 seconds (15 minutes) for production compatibility
            val finalInterval = maxOf(intervalSeconds, 900)

            val gpsWorkRequest = PeriodicWorkRequestBuilder<GpsWorker>(
                finalInterval,
                TimeUnit.SECONDS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                GPS_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                gpsWorkRequest
            )

            Log.i(TAG, "GPS tracking work scheduled (interval: ${finalInterval}s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule GPS work: ${e.message}", e)
        }
    }

    /**
     * Cancel all scheduled work.
     * Called when network services stop or app is destroyed.
     */
    fun cancelAllWork(context: Context) {
        try {
            Log.i(TAG, "Cancelling all work")
            val workManager = WorkManager.getInstance(context)

            workManager.cancelUniqueWork(WEBSOCKET_WORK)
            workManager.cancelUniqueWork(GPS_WORK)

            Log.i(TAG, "All work cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling work: ${e.message}", e)
        }
    }

    /**
     * Cancel specific work by name.
     */
    fun cancelWork(context: Context, workName: String) {
        try {
            Log.i(TAG, "Cancelling work: $workName")
            WorkManager.getInstance(context).cancelUniqueWork(workName)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling work $workName: ${e.message}", e)
        }
    }
}
