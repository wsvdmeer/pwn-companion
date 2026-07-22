package com.wsvdmeer.pwncompanion.models

/**
 * WiFi Telemetry - Real-time WiFi network state from Pwnagotchi.
 * Includes channel information, AP/client counts, handshake data.
 */
data class WifiTelemetry(
    val currentChannel: Int,
    val apsCount: Int,             // Total access points seen
    val clientsCount: Int,         // Total clients seen
    val handshakesTotal: Int,      // Total handshakes captured
    val channels: Map<Int, ChannelStats>,  // Per-channel breakdown
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Per-channel WiFi statistics.
 */
data class ChannelStats(
    val channel: Int,
    val apsCount: Int,
    val clientsCount: Int,
    val signalStrength: Float? = null  // Optional: average RSSI
)

