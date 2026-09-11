package com.wsvdmeer.pwncompanion.protocol

/**
 * One typed thing that arrived from a connected device, already parsed off the raw [ScreenData]
 * wire message. [MessageHandler] emits these on a single stream ([MessageHandler.events]) so a
 * caller subscribes once and branches with a `when`, instead of collecting a separate flow per
 * message type. A single wire message can yield more than one event (a STATUS packet carries the
 * device's status and, when present, its mood and mode).
 */
sealed interface DeviceEvent {
    val deviceId: String

    /** The Pwnagotchi's e-ink frame, Base64-encoded. */
    data class Image(
        override val deviceId: String,
        val imageData: String,
        val contentType: String,
    ) : DeviceEvent

    /** A GPS fix reported by the device (rare — the phone is normally the GPS source). */
    data class Gps(
        override val deviceId: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double,
        val altitude: Double,
    ) : DeviceEvent

    /** A human-readable status line from the device. */
    data class Status(
        override val deviceId: String,
        val status: String,
        val message: String,
    ) : DeviceEvent

    /** The Pwnagotchi's raw mood name ("HAPPY", "BORED", …) → drives the app's AI mood. */
    data class Mood(
        override val deviceId: String,
        val moodName: String,
    ) : DeviceEvent

    /** The device's operating mode: AUTO (scanning) vs MANUAL (user-controlled, no scan). */
    data class Mode(
        override val deviceId: String,
        val isAutoMode: Boolean,
    ) : DeviceEvent

    /** A Wi-Fi event from the plugin: handshake, discovery, deauth/anomaly, etc. */
    data class Network(
        override val deviceId: String,
        val eventType: String,       // e.g. "handshakes_captured", "network_discovered"
        val description: String,     // human-readable summary of the event
        val network: String? = null,
        val count: Int = 0,
        val signal: Int? = null,
        val channel: Int? = null,
        val bssid: String? = null,
        val station: String? = null,   // deauth target (client MAC)
        val security: String? = null,
        val totalCaptures: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
    ) : DeviceEvent
}
