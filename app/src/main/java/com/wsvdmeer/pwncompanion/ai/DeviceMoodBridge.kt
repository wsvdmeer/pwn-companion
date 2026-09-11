package com.wsvdmeer.pwncompanion.ai

import com.wsvdmeer.pwncompanion.models.CaptureEntry
import com.wsvdmeer.pwncompanion.models.DeviceTelemetry
import com.wsvdmeer.pwncompanion.models.LearningStats
import com.wsvdmeer.pwncompanion.protocol.DeviceEvent

/**
 * Translates device state into calls on the AI voice/personality engine ([PwnagotchiViewModel]).
 *
 * The *rules* for which device signals drive the pet's mood — event-type normalization, the
 * idle-in-manual skip, the capture-count guard, the seconds→millis timestamp math — used to live
 * scattered across a dozen `LaunchedEffect` blocks inside `MainContentArea`. They live here now, in
 * one place: the Composable observes state and forwards each change; this decides what the AI hears.
 *
 * The genuinely branching decision — "does this network event become a personality trigger, and in
 * what shape?" — is [wifiEventFor], a pure function testable without Compose or a ViewModel.
 */
class DeviceMoodBridge(private val ai: PwnagotchiViewModel) {

    fun onAutoMode(isAutoMode: Boolean) = ai.setAutoMode(isAutoMode)

    /** A Wi-Fi event from the device → personality trigger + running capture total. */
    fun onNetworkEvent(event: DeviceEvent.Network?, isAutoMode: Boolean) {
        val wifiEvent = wifiEventFor(event, isAutoMode) ?: return
        ai.generatePersonality(wifiEvent)
        // Keep the AI's running total in sync so its lines can cite it.
        if (event!!.totalCaptures > 0) ai.recordCapture(event.totalCaptures)
    }

    fun onDeviceMood(mood: String?) {
        ai.applyDeviceMood(mood ?: return)
    }

    fun onLearningStats(stats: LearningStats?) = ai.updateLearningStats(stats)

    fun onTelemetry(telemetry: DeviceTelemetry?) {
        telemetry?.let { ai.applyTelemetry(it) }
    }

    /** The pet's real catch count + last-catch time, from the device's capture history. */
    fun onCaptures(captures: List<CaptureEntry>) {
        val newestSec = captures.mapNotNull { it.timestamp }.maxOrNull()
        ai.syncCaptureHistory(
            total = captures.size,
            lastCaptureMs = newestSec?.let { it * 1000L },   // capture timestamps are unix seconds
        )
    }

    fun onPwnagotchiName(name: String) = ai.updatePwnagotchiName(name)

    fun onDeviceConnected(connected: Boolean) = ai.setDeviceConnected(connected)

    companion object {
        /**
         * Decide whether a device network event should drive the AI personality, and shape it into
         * the [WifiEvent] the voice engine expects — or null to skip it.
         *
         * The plugin sends `event_type` in snake_case ("idle", "handshakes_captured") while
         * [WifiEvent.type] is UPPER_SNAKE, so we normalize here. Idle chatter is skipped in MANUAL
         * mode (the device isn't hunting, so there's nothing to narrate).
         */
        fun wifiEventFor(event: DeviceEvent.Network?, isAutoMode: Boolean): WifiEvent? {
            val e = event ?: return null
            val type = e.eventType.uppercase()
            if (type == "IDLE" && !isAutoMode) return null
            return WifiEvent(
                description = e.description,
                type = type,
                network = e.network,
                count = e.count,
                rssi = e.signal,
                channel = e.channel,
                security = e.security,
                timestamp = e.timestamp,
            )
        }
    }
}
