package com.wsvdmeer.pwncompanion.protocol

import android.util.Log
import com.wsvdmeer.pwncompanion.models.ScreenData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Parses incoming device wire messages ([ScreenData]) and emits them as typed [DeviceEvent]s on a
 * single stream. Callers collect [events] once and branch with a `when`.
 *
 * Routing is a plain `when` over the message `type` — there is no handler registry, because the set
 * of types is fixed and known at compile time. The old per-type [SharedFlow] fan-out (one flow +
 * one DTO per type) has been folded into [events]; the interpretation a caller needs to do is the
 * same `when` it always was, just in one place.
 */
class MessageHandler {
    private val tag = "MessageHandler"

    // One stream for every device event. extraBufferCapacity lets emit() from the (non-suspending)
    // parse path below never block; replay is 0 — the ViewModel folds each event into its own
    // last-value StateFlows, and it subscribes at init, before any device connects.
    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    /**
     * Parse one incoming message and emit its event(s). Called by NetworkService when a message
     * arrives. Errors are logged, never thrown — a bad message must not tear down the link.
     */
    fun handleIncomingMessage(deviceId: String, message: ScreenData) {
        try {
            when (message.type) {
                ScreenData.TYPE_IMAGE -> emitImage(deviceId, message)
                ScreenData.TYPE_GPS -> emitGps(deviceId, message)
                ScreenData.TYPE_STATUS -> emitStatus(deviceId, message)
                ScreenData.TYPE_NETWORK_EVENT -> emitNetworkEvent(deviceId, message)
                // Acknowledged but carry no UI event — logged so they aren't "unhandled".
                // GPS_REQUEST's response is enqueued by NetworkService (it holds the last fix).
                ScreenData.TYPE_GPS_REQUEST ->
                    Log.i(tag, "GPS request from $deviceId (response handled by NetworkService)")
                ScreenData.TYPE_GPS_RECEIVED -> Log.d(tag, "GPS ack from $deviceId")
                ScreenData.TYPE_READY -> Log.d(tag, "Ready signal from $deviceId")
                ScreenData.TYPE_AUTOTUNE -> Log.d(
                    tag,
                    "autotune_stats from $deviceId: best_ch=${message.autotuneBestChannel}, " +
                        "rssi=${message.autotuneMinRssi}, channels=${message.autotuneChannels?.size ?: 0}"
                )
                else -> Log.w(tag, "No route for message type: ${message.type}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling message type=${message.type}: ${e.message}", e)
        }
    }

    private fun emitImage(deviceId: String, message: ScreenData) {
        val imageData = message.data ?: run { Log.w(tag, "Image message missing data"); return }
        val contentType = message.contentType ?: ScreenData.CONTENT_TYPE_PNG
        _events.tryEmit(DeviceEvent.Image(deviceId, imageData, contentType))
    }

    // The Pwnagotchi plugin doesn't currently send 'gps' messages (only gps_request/gps_received),
    // but the route is kept for forward-compatibility.
    private fun emitGps(deviceId: String, message: ScreenData) {
        val latitude = message.latitude ?: return
        val longitude = message.longitude ?: return
        _events.tryEmit(
            DeviceEvent.Gps(
                deviceId = deviceId,
                latitude = latitude,
                longitude = longitude,
                accuracy = message.accuracy ?: 0.0,
                altitude = message.altitude ?: 0.0,
            )
        )
    }

    private fun emitStatus(deviceId: String, message: ScreenData) {
        _events.tryEmit(
            DeviceEvent.Status(
                deviceId = deviceId,
                status = message.status ?: "UNKNOWN",
                message = message.message ?: "",
            )
        )
        // A STATUS packet may also carry the device's mood and/or mode.
        message.pwnagotchiMood?.takeIf { it.isNotBlank() }?.let { mood ->
            _events.tryEmit(DeviceEvent.Mood(deviceId, mood))
        }
        message.pwnagotchiMode?.takeIf { it.isNotBlank() }?.let { mode ->
            _events.tryEmit(DeviceEvent.Mode(deviceId, isAutoMode = mode.uppercase() == "AUTO"))
        }
    }

    private fun emitNetworkEvent(deviceId: String, message: ScreenData) {
        val eventType = message.eventType ?: "unknown"
        val description = message.eventDescription ?: describeEvent(eventType, message)
        Log.i(tag, "Network event: $eventType — $description")
        _events.tryEmit(
            DeviceEvent.Network(
                deviceId = deviceId,
                eventType = eventType,
                description = description,
                network = message.network,
                count = message.count ?: 0,
                signal = message.signal,
                channel = message.channel,
                bssid = message.bssid,
                station = message.station,
                security = message.security,
                totalCaptures = message.totalCaptures ?: 0,
            )
        )
    }

    /** Fallback human-readable summary when the plugin didn't include one. */
    private fun describeEvent(eventType: String, message: ScreenData): String = when (eventType) {
        "handshakes_captured" -> "Captured ${message.count ?: 1} handshake(s) from ${message.network ?: "unknown"}"
        "network_discovered"  -> "Found network ${message.network ?: "unknown"} on CH${message.channel ?: "?"} (${message.security ?: "?"})"
        "anomaly_detected"    -> "Anomaly detected: ${message.reason ?: eventType}"
        "high_value_target"   -> "High-value target: ${message.network ?: "unknown"} (${message.reason ?: ""})"
        "connection_success"  -> "Connected to ${message.network ?: "unknown"}"
        "connection_failure"  -> "Failed to connect to ${message.network ?: "unknown"}: ${message.reason ?: ""}"
        "scan_complete"       -> "Scan complete — found ${message.count ?: 0} networks"
        "idle"                -> "Quiet epoch — nothing happening on the spectrum"
        else                  -> "WiFi event: $eventType"
    }
}
