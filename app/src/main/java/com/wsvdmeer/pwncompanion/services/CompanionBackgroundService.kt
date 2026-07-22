package com.wsvdmeer.pwncompanion.services

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.wsvdmeer.pwncompanion.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Companion Background Service - Main orchestrator service.
 * Manages lifecycle of network services and GPS tracking.
 * Started when Bluetooth tether (bnep0) is detected via BluetoothConnectionReceiver.
 * Runs as foreground service with periodic notification updates.
 */
class CompanionBackgroundService : Service() {
    private val tag = "CompanionBackgroundService"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var networkService: NetworkService
    private val notificationId = 1000

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "═══════════════════════════════════════════")
        Log.i(tag, "▶ COMPANION BACKGROUND SERVICE CREATED")
        Log.i(tag, "═══════════════════════════════════════════")

        // Initialize notification channels on first service creation
        NotificationHelper.createNotificationChannels(this)

        // Get singleton instance of NetworkService (shared with MainActivity)
        networkService = NetworkServiceSingleton.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START"
        Log.i(tag, "═══════════════════════════════════════════")
        Log.i(tag, "▶ onStartCommand: $action")
        Log.i(tag, "═══════════════════════════════════════════")

        // Always call startForeground() immediately — Android requires it within 5 s of startForegroundService().
        // We use FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE only — GPS is handled by GpsService separately.
        // The location type is NOT used here; see AndroidManifest foregroundServiceType="connectedDevice".
        val notification = NotificationHelper.createNetworkServiceNotification(this)
        try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when started from background.
            // Log and stop gracefully — the BT monitor will restart us when bnep0 is detected again.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException) {
                Log.w(tag, "FGS start not allowed (background restriction) — stopping self: ${e.message}")
            } else {
                Log.e(tag, "startForeground failed: ${e.message}", e)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        return when (action) {
            ACTION_START_NETWORKING -> {
                Log.i(tag, "  └─ Starting network services (explicit user request)")
                startNetworkServices()
                // Explicit [ start service ]: don't just wait for a bnep transition
                // (which won't fire if the interface is already up) — request a start
                // now and let the health check sustain it.
                networkService.requestStart()
                START_NOT_STICKY  // Changed from START_STICKY to avoid crash-restart loops
            }
            ACTION_STOP_NETWORKING -> {
                Log.i(tag, "  └─ Full stop requested by user")
                fullStop()
                START_NOT_STICKY   // Do NOT restart after explicit user stop
            }
            else -> {
                Log.i(tag, "  └─ Default start")
                if (!networkService.isRunning()) startNetworkServices()
                START_NOT_STICKY  // Changed from START_STICKY to avoid crash-restart loops
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "CompanionBackgroundService destroyed")

        // Run cleanup on an INDEPENDENT scope, not `scope`. Both run on Main, so a
        // coroutine launched on `scope` here cannot start until onDestroy() returns —
        // but scope.cancel() below cancels it first, so cleanup would never run and
        // the WebSocket/UDP sockets + ConnectivityManager callback would leak on
        // every teardown (BT drop / OS kill). A detached one-shot scope survives the
        // cancel and is GC'd once cleanup completes.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                networkService.cleanup()
            } catch (e: Exception) {
                Log.e(tag, "Error during cleanup: ${e.message}")
            }
        }

        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Register the Bluetooth tether monitor and let it drive start/stop automatically.
     * Do NOT call networkService.start() here — BluetoothTetherMonitor will call it
     * when it detects the bnep0/bt-pan interface, avoiding double-start races.
     */
    private fun startNetworkServices() {
        try {
            Log.i(tag, "Registering network services (monitor will auto-start on bnep0)")
            networkService.initialize()
            Log.i(tag, "Network services registered — waiting for bnep0/bt-pan interface")
        } catch (e: Exception) {
            Log.e(tag, "Error registering network services: ${e.message}")
        }
    }

    /**
     * Full user-initiated shutdown.
     * Stops the WebSocket/UDP server, unregisters the BluetoothTetherMonitor so it
     * cannot auto-restart the server while bnep0 is still connected, stops GpsService,
     * removes the foreground notification, and stops this service.
     */
    private fun fullStop() {
        scope.launch {
            try {
                // 1. Stop GpsService explicitly (will not auto-restart unless we send a new start intent)
                stopService(Intent(this@CompanionBackgroundService, GpsService::class.java))

                // 2. Stop server + unregister BluetoothTetherMonitor + drain queues
                networkService.cleanup()

                Log.i(tag, "All networking and GPS services stopped by user")
            } catch (e: Exception) {
                Log.e(tag, "Error during full stop: ${e.message}")
            } finally {
                // 3. Remove the foreground notification
                @Suppress("DEPRECATION")
                stopForeground(true)
                // 4. Stop this service (explicit stop — START_NOT_STICKY ensures no restart)
                stopSelf()
            }
        }
    }

    companion object {
        const val ACTION_START_NETWORKING = "com.wsvdmeer.pwncompanion.START_NETWORKING"
        const val ACTION_STOP_NETWORKING = "com.wsvdmeer.pwncompanion.STOP_NETWORKING"
    }
}
