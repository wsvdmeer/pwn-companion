package com.wsvdmeer.pwncompanion.receivers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wsvdmeer.pwncompanion.services.CompanionBackgroundService
import com.wsvdmeer.pwncompanion.utils.BluetoothHelper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Broadcast receiver for Bluetooth connection state changes.
 * Listens for ACL connection/disconnection events from candidate Pwnagotchi/PAN devices.
 * Starts CompanionBackgroundService on candidate device connection (with debounce)
 * and stops it after disconnection if no bnep0/bt-pan tether remains active.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    private val tag = "BluetoothConnReceiver"

    companion object {
        // Android instantiates a NEW receiver per broadcast for manifest-registered
        // receivers, so debounce timestamps must be static — an instance field
        // would always read back 0L and never actually debounce anything.
        // Separate connect/disconnect timestamps so an immediate disconnect after
        // connect is never swallowed by the connect debounce window.
        @Volatile
        private var lastConnectEventTime = 0L
        @Volatile
        private var lastDisconnectEventTime = 0L
        @Volatile
        private var disconnectStopJob: Job? = null
        private const val debounceDelayMs = 2000  // 2 second debounce to prevent rapid events
        private const val disconnectSettleDelayMs = 6000L
    }

    @SuppressLint("MissingPermission", "Deprecation")  // Permissions declared in manifest; using deprecated API for API 29 compatibility
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

        Log.d(tag, "Broadcast received: action=$action, device=${device?.name} (${device?.address})")

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!BluetoothHelper.isCandidatePanDevice(device)) {
                    Log.d(tag, "Ignoring ACL_CONNECTED for non-PAN Bluetooth device: ${device?.name} (${device?.address})")
                    return
                }
                Log.i(tag, "Bluetooth ACL connected: ${device?.name} (${device?.address})")
                handleBluetoothConnected(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (!BluetoothHelper.isCandidatePanDevice(device)) {
                    Log.d(tag, "Ignoring ACL_DISCONNECTED for non-PAN Bluetooth device: ${device?.name} (${device?.address})")
                    return
                }
                Log.i(tag, "Bluetooth ACL disconnected: ${device?.name} (${device?.address})")
                handleBluetoothDisconnected(context.applicationContext, goAsync())
            }

            else -> {
                Log.d(tag, "Unknown Bluetooth action: $action")
            }
        }
    }

    /**
     * Handle candidate Bluetooth device connection.
     * Cancels any in-flight disconnect stop timer, debounces rapid events, and starts background service.
     */
    private fun handleBluetoothConnected(context: Context) {
        val now = System.currentTimeMillis()
        // Always cancel any pending disconnect-stop job when a candidate PAN device reconnects,
        // even if within the connect debounce window.
        disconnectStopJob?.cancel()
        disconnectStopJob = null

        // Debounce: ignore events within 2 seconds of last connect event
        if (now - lastConnectEventTime < debounceDelayMs) {
            lastConnectEventTime = now
            Log.d(tag, "Ignoring duplicate Bluetooth connection event (debounce)")
            return
        }
        lastConnectEventTime = now

        Log.i(tag, "Starting CompanionBackgroundService due to Bluetooth connection")

        // Start CompanionBackgroundService with action to start networking, tagged as
        // receiver-initiated so the service auto-stops if bt-pan/bnep0 never comes up.
        val intent = Intent(context, CompanionBackgroundService::class.java).apply {
            action = CompanionBackgroundService.ACTION_START_NETWORKING
            putExtra(CompanionBackgroundService.EXTRA_FROM_BLUETOOTH_RECEIVER, true)
        }

        // Use startForegroundService (minimum SDK is 29, which is >= 26)
        // Wrapped in try-catch — on Android 12+ the system may throw
        // ForegroundServiceStartNotAllowedException if the app is fully background.
        try {
            context.startForegroundService(intent)
            Log.d(tag, "CompanionBackgroundService intent sent")
        } catch (e: Exception) {
            Log.w(tag, "Could not start CompanionBackgroundService (background restriction?): ${e.message}")
        }
    }

    /**
     * Handle candidate Bluetooth device disconnection.
     * Stops background service if no bnep0/bt-pan tether is active after a settle delay
     * and no newer ACL_CONNECTED event has arrived in the meantime.
     */
    private fun handleBluetoothDisconnected(appContext: Context, pendingResult: PendingResult?) {
        // Debounce: ignore events within 2 seconds of last disconnect event
        val now = System.currentTimeMillis()
        if (now - lastDisconnectEventTime < debounceDelayMs) {
            Log.d(tag, "Ignoring duplicate Bluetooth disconnection event (debounce)")
            pendingResult?.finish()
            return
        }
        lastDisconnectEventTime = now

        Log.i(tag, "Bluetooth device disconnected, checking if service should stop")

        disconnectStopJob?.cancel()
        @OptIn(DelicateCoroutinesApi::class)
        disconnectStopJob = GlobalScope.launch {
            try {
                delay(disconnectSettleDelayMs)
                if (!isActive || lastConnectEventTime > lastDisconnectEventTime) {
                    Log.d(tag, "Bluetooth reconnected during disconnect settle window - keeping service alive")
                    return@launch
                }
                if (!BluetoothHelper.hasBluetoothTether()) {
                    Log.i(tag, "No active bt-pan/bnep0 tether after Bluetooth disconnect - stopping CompanionBackgroundService")
                    appContext.stopService(Intent(appContext, CompanionBackgroundService::class.java))
                } else {
                    Log.d(tag, "bt-pan/bnep0 tether still active after disconnect event - keeping service alive")
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
