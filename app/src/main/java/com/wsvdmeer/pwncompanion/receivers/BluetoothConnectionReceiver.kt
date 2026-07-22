package com.wsvdmeer.pwncompanion.receivers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wsvdmeer.pwncompanion.services.CompanionBackgroundService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Broadcast receiver for Bluetooth connection state changes.
 * Listens for ACL connection/disconnection events.
 * Starts CompanionBackgroundService on device connection (with debounce).
 * Implementation in Phase 5.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    private val tag = "BluetoothConnReceiver"

    companion object {
        // Android instantiates a NEW receiver per broadcast for manifest-registered
        // receivers, so the debounce timestamp must be static — an instance field
        // would always read back 0L and never actually debounce anything.
        @Volatile
        private var lastEventTime = 0L
        private const val debounceDelayMs = 2000  // 2 second debounce to prevent rapid events
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
                Log.i(tag, "Bluetooth ACL connected: ${device?.name} (${device?.address})")
                handleBluetoothConnected(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(tag, "Bluetooth ACL disconnected: ${device?.name} (${device?.address})")
                handleBluetoothDisconnected()
            }

            else -> {
                Log.d(tag, "Unknown Bluetooth action: $action")
            }
        }
    }

    /**
     * Handle Bluetooth device connection.
     * Debounces rapid events and starts background service.
     */
    private fun handleBluetoothConnected(context: Context) {
        // Debounce: ignore events within 2 seconds of last event
        val now = System.currentTimeMillis()
        if (now - lastEventTime < debounceDelayMs) {
            Log.d(tag, "Ignoring duplicate Bluetooth connection event (debounce)")
            return
        }
        lastEventTime = now

        Log.i(tag, "Starting CompanionBackgroundService due to Bluetooth connection")

        // Start CompanionBackgroundService with action to start networking
        val intent = Intent(context, CompanionBackgroundService::class.java).apply {
            action = CompanionBackgroundService.ACTION_START_NETWORKING
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
     * Handle Bluetooth device disconnection.
     * Stops background service if no other devices are connected.
     */
    private fun handleBluetoothDisconnected() {
        // Debounce: ignore events within 2 seconds of last event
        val now = System.currentTimeMillis()
        if (now - lastEventTime < debounceDelayMs) {
            Log.d(tag, "Ignoring duplicate Bluetooth disconnection event (debounce)")
            return
        }
        lastEventTime = now

        Log.i(tag, "Bluetooth device disconnected, checking if service should stop")

        // Use GlobalScope for fire-and-forget async check
        // (only needed for debounce verification, not for production critical logic)
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            delay(3000) // Wait 3 seconds to see if another device connects

            // For now, just log - actual disconnection handling will be via NetworkService
            // which monitors bnep0 interface via BluetoothTetherMonitor
            Log.d(tag, "Bluetooth disconnect check complete - NetworkService will auto-stop on bnep0 loss")
        }
    }
}
