package com.wsvdmeer.pwncompanion.database

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * WiFi Observation - Data model for WiFi network observations.
 * Stores all data needed for learning channel effectiveness.
 *
 * Serializable for DataStore JSON persistence.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class WifiObservation(
    // Network identification
    val bssid: String = "",              // MAC address (AA:BB:CC:DD:EE:FF)
    val ssid: String = "",               // Network name
    val channel: Int = 0,                // WiFi channel (1-14)
    val security: String = "",           // WPA2, Open, etc

    // Attack & capture data
    val attacks_sent: Int = 0,           // Number of deauth attempts
    val handshakes_captured: Int = 0,    // Successful handshakes

    // Location data
    val latitude: Double = 0.0,          // GPS latitude
    val longitude: Double = 0.0,         // GPS longitude

    // Temporal data
    val timestamp: Long = 0L,            // When observation occurred
    val hourOfDay: Int = 0               // Hour (0-23) for time-of-day analysis
) {
    /**
     * Calculate success rate for this observation.
     * Returns handshakes / attacks, or 0 if no attacks sent.
     */
    fun getSuccessRate(): Float {
        return if (attacks_sent > 0) {
            handshakes_captured.toFloat() / attacks_sent
        } else {
            0f
        }
    }
}
