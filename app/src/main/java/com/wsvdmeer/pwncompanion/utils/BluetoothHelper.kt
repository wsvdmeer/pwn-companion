package com.wsvdmeer.pwncompanion.utils

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission

/**
 * Bluetooth API compatibility helper.
 * Handles API level differences between Android 10-14+ for Bluetooth operations.
 */
object BluetoothHelper {

    /**
     * Get Bluetooth adapter with API-level compatibility.
     * - API 31+: Use BluetoothManager
     * - API <31: Use BluetoothAdapter.getDefaultAdapter()
     */
    fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Use BluetoothManager
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter
        } else {
            // Android 10-11: Use deprecated getDefaultAdapter (with suppress)
            @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter
        }
    }

    /**
     * Check if interface name is Bluetooth-related.
     * Recognizes common tether interface names: bnep0, bt-pan, etc.
     */
    fun isBluetoothRelated(interfaceName: String?): Boolean {
        if (interfaceName == null) return false
        return interfaceName.startsWith("bnep") || 
               interfaceName.startsWith("bt-pan") ||
               interfaceName.startsWith("pan") ||
               interfaceName.contains("bluetooth", ignoreCase = true)
    }

    /**
     * Get list of connected Bluetooth devices.
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    fun getBluetoothConnectedDevices(context: Context): List<String> {
        val adapter = getBluetoothAdapter(context) ?: return emptyList()
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: Requires BLUETOOTH_CONNECT permission
                adapter.bondedDevices?.mapNotNull { 
                    "${it.name} (${it.address})"
                } ?: emptyList()
            } else {
                // API <31: Direct access
                adapter.bondedDevices?.mapNotNull {
                    "${it.name} (${it.address})"
                } ?: emptyList()
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * True if a Bluetooth-tether interface (bnep0 / bt-pan) is currently up — i.e. a
     * Pwnagotchi PAN link exists. Used to avoid starting the foreground service (and its
     * persistent notification) when there's nothing to serve; the manifest
     * BluetoothConnectionReceiver starts it the moment the device tethers.
     */
    fun hasBluetoothTether(): Boolean {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.any { ni ->
                ni.isUp && isBluetoothRelated(ni.name)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if Bluetooth is enabled.
     */
    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(context: Context): Boolean {
        return getBluetoothAdapter(context)?.isEnabled ?: false
    }

    /**
     * Get Bluetooth adapter state.
     */
    @SuppressLint("MissingPermission")
    fun getBluetoothState(context: Context): Int {
        return getBluetoothAdapter(context)?.state ?: BluetoothAdapter.STATE_OFF
    }
}
