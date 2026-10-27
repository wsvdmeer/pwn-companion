package com.wsvdmeer.pwncompanion.utils

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
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

    // Standard Bluetooth PAN Service Class UUID prefixes (PANU, NAP, GN)
    private val PAN_UUID_PREFIXES = setOf(
        "00001115-",
        "00001116-",
        "00001117-",
    )

    // Bluetooth Major Device Classes that never host a Pwnagotchi PAN tether
    private val NON_PAN_MAJOR_CLASSES = setOf(
        BluetoothClass.Device.Major.AUDIO_VIDEO,
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.HEALTH,
        BluetoothClass.Device.Major.PERIPHERAL,
        BluetoothClass.Device.Major.IMAGING,
        BluetoothClass.Device.Major.TOY,
        BluetoothClass.Device.Major.PHONE,
    )

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
     * Pure, unit-testable check for whether a Bluetooth device that fired
     * ACTION_ACL_CONNECTED / ACTION_ACL_DISCONNECTED can plausibly be a Pwnagotchi
     * (or Linux PAN peer) rather than headphones, a watch, a smart scale, or an
     * unbonded background BLE peripheral.
     */
    fun isCandidatePanDevice(
        bondState: Int,
        deviceType: Int,
        majorDeviceClass: Int?,
        deviceName: String?,
        uuidStrings: List<String> = emptyList(),
    ): Boolean {
        // Bluetooth PAN tethering requires a paired (bonded) device.
        if (bondState != BluetoothDevice.BOND_BONDED) return false
        // BNEP runs over Classic Bluetooth (BR/EDR), never BLE-only.
        if (deviceType == BluetoothDevice.DEVICE_TYPE_LE ||
            deviceType == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
            return false
        }
        // Reject audio headsets/speakers, watches, scales, keyboards/mice, etc.
        if (majorDeviceClass != null && majorDeviceClass in NON_PAN_MAJOR_CLASSES) {
            return false
        }
        // Positive match 1: Device advertises a Bluetooth PAN UUID (PANU/NAP/GN)
        if (uuidStrings.any { uuid ->
                val lower = uuid.lowercase()
                PAN_UUID_PREFIXES.any { prefix -> lower.startsWith(prefix) }
            }) {
            return true
        }
        // Positive match 2: Device name looks like a Pwnagotchi or Raspberry Pi
        val lowerName = deviceName?.lowercase() ?: ""
        if (lowerName.contains("pwn") || lowerName.contains("gotchi") || lowerName.contains("raspberry")) {
            return true
        }
        // Positive match 3: Device class is explicitly COMPUTER or NETWORKING
        if (majorDeviceClass == BluetoothClass.Device.Major.COMPUTER ||
            majorDeviceClass == BluetoothClass.Device.Major.NETWORKING) {
            return true
        }
        return false
    }

    /**
     * Check whether the given [BluetoothDevice] from an ACL broadcast can plausibly
     * establish a Pwnagotchi Bluetooth PAN tether.
     */
    @SuppressLint("MissingPermission")
    fun isCandidatePanDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return try {
            val bondState = device.bondState
            val deviceType = device.type
            val majorClass = device.bluetoothClass?.majorDeviceClass
            val name = device.name
            val uuids = device.uuids?.mapNotNull { it.uuid?.toString() } ?: emptyList()
            isCandidatePanDevice(
                bondState = bondState,
                deviceType = deviceType,
                majorDeviceClass = majorClass,
                deviceName = name,
                uuidStrings = uuids,
            )
        } catch (e: SecurityException) {
            false
        }
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
