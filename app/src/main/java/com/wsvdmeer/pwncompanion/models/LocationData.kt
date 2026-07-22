package com.wsvdmeer.pwncompanion.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GPS location data from device location manager.
 * Cached for responding to GPS requests from Pwnagotchi.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class LocationData(
    @SerialName("latitude")
    val latitude: Double,

    @SerialName("longitude")
    val longitude: Double,

    @SerialName("altitude")
    val altitude: Double? = null,

    @SerialName("accuracy")
    val accuracy: Float? = null,

    @SerialName("bearing")
    val bearing: Float? = null,

    @SerialName("speed")
    val speed: Float? = null,

    @SerialName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerialName("provider")
    val provider: String? = null
) {
    /**
     * Check if location data is fresh (within last 60 seconds).
     */
    @Suppress("UNUSED")
    fun isFresh(maxAgeMs: Long = 60_000): Boolean {
        return (System.currentTimeMillis() - timestamp) < maxAgeMs
    }

    /**
     * Check if location accuracy is acceptable.
     */
    @Suppress("UNUSED")
    fun hasAcceptableAccuracy(maxAccuracy: Float = 50f): Boolean {
        return accuracy != null && accuracy <= maxAccuracy
    }
}
