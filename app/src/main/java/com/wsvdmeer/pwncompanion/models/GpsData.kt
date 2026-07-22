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
    // Hardware speed (m/s) from the OS Location when the chipset reports it (Doppler-based,
    // far less noisy than differencing positions). null = not reported → fall back to
    // position-differencing for motion detection.
    val speed: Float? = null,
    val timestamp: Long = 0L
) {
    /**
     * Check if GPS data is valid (has non-zero coordinates)
     */
    fun isValid(): Boolean {
        return latitude != 0.0 && longitude != 0.0
    }
}

