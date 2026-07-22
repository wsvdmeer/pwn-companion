package com.wsvdmeer.pwncompanion.models

import com.wsvdmeer.pwncompanion.models.AutotuneChannelStat

/**
 * Device connection state tracker.
 * Maintains state for each connected Pwnagotchi device.
 */
data class DeviceState(
    val deviceId: String,
    val deviceName: String,
    val pwnagotchiName: String? = null,
    val ipAddress: String,
    val port: Int = 8081,
    val interfaceName: String,
    val macAddress: String? = null,
    val isConnected: Boolean = false,
    val lastImageData: String? = null,
    val lastImageTimestamp: Long? = null,
    val lastStatusMessage: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val personality: PersonalityState? = null,
    val wifiTelemetry: WifiTelemetry? = null,
    val autotuneChannels: Map<String, AutotuneChannelStat>? = null,
    val autotuneBestChannel: Int? = null,
    val autotuneMinRssi: Int? = null,
    /** Geolocated handshakes captured by the device (newest first). */
    val captures: List<CaptureEntry> = emptyList(),
    /** Latest per-epoch telemetry (vitals, reward, mood counters). */
    val telemetry: DeviceTelemetry? = null,
    /** Whether the device's wpa-sec cracking plugin is on (null = unknown yet). */
    val wpaSecEnabled: Boolean? = null,
    /** Whether the wpa-sec service was reachable at last check (null = unknown). */
    val wpaSecOnline: Boolean? = null,
    /** Raw handshake-file count on the device (pre BSSID-dedup); null = unknown. */
    val captureFileCount: Int? = null,
) {
    enum class ConnectionState {
        DISCOVERING,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED,
        ERROR
    }
}
