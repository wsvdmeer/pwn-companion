package com.wsvdmeer.pwncompanion.workers

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wsvdmeer.pwncompanion.services.NetworkServiceSingleton

/**
 * WorkManager health-check for the WebSocket server.
 *
 * The server itself runs inside CompanionBackgroundService (a foreground
 * service); a WorkManager job cannot host it. This worker therefore only
 * reports whether the NetworkService is alive — it intentionally does NOT
 * sleep to fake a "keep-alive" (the old delay(5000) kept nothing alive).
 */
class WebSocketWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val tag = "WebSocketWorker"

    override suspend fun doWork(): Result {
        return try {
            val service = NetworkServiceSingleton.getInstanceOrNull()
            if (service != null) {
                Log.i(tag, "WebSocket health check: NetworkService is alive")
            } else {
                Log.w(tag, "WebSocket health check: NetworkService not running")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "WebSocket worker error: ${e.message}", e)
            Result.retry()
        }
    }
}
