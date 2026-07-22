package com.wsvdmeer.pwncompanion.models

/**
 * GPS coordinate data from device.
 * Tracks location information received from mobile app's GPS sensor.
 */
data class GpsData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Double = 0.0,  // in meters
    val altitude: Double = 0.0,  // in meters
    val timestamp: Long = 0L
) {
    /**
     * Check if GPS data is valid (has non-zero coordinates)
     */
    fun isValid(): Boolean {
        return latitude != 0.0 && longitude != 0.0
    }
}

