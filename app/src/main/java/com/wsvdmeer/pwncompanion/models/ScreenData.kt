package com.wsvdmeer.pwncompanion.models

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebSocket message data from Pwnagotchi device.
 *
 * Message Types:
 * - "image"         : Screen capture (Base64-encoded)
 * - "gps_request"   : Device requests GPS from phone
 * - "gps"           : GPS data from device
 * - "status"        : Device status / name update
 * - "network_event" : WiFi events (handshakes, deauths, discoveries …)
 * - "autotune_stats": Per-channel efficiency from auto-tune plugin
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class ScreenData(
    @SerialName("type")
    val type: String,

    @SerialName("data")
    val data: String? = null,

    @SerialName("contentType")
    val contentType: String? = null,

    @SerialName("timestamp")
    val timestamp: Long? = null,

    @SerialName("status")
    val status: String? = null,

    @SerialName("message")
    val message: String? = null,

    /** Pwnagotchi name — camelCase variant */
    @SerialName("deviceName")
    val deviceName: String? = null,

    /** Pwnagotchi name — snake_case sent by _send_status_message in plugin */
    @SerialName("device_name")
    val deviceNameSnake: String? = null,

    @SerialName("latitude")
    val latitude: Double? = null,

    @SerialName("longitude")
    val longitude: Double? = null,

    @SerialName("accuracy")
    val accuracy: Double? = null,

    @SerialName("altitude")
    val altitude: Double? = null,

    // Hardware speed (m/s) from the phone's own Location, when reported — used for motion
    // detection (steering). Null when the chipset didn't report a speed.
    @SerialName("speed")
    val speed: Float? = null,

    @SerialName("tether_interface")
    val tetherInterface: String? = null,

    /** Raw pwnagotchi mood name sent by the plugin (e.g. "HAPPY", "BORED", "EXCITED") */
    @SerialName("pwnagotchi_mood")
    val pwnagotchiMood: String? = null,

    /** Pwnagotchi operating mode — "AUTO" (scanning) or "MANUAL" (idle, no scanning) */
    @SerialName("pwnagotchi_mode")
    val pwnagotchiMode: String? = null,

    // ── network_event fields ─────────────────────────────────────────────────
    @SerialName("event_type")
    val eventType: String? = null,

    @SerialName("description")
    val eventDescription: String? = null,

    @SerialName("network")
    val network: String? = null,

    @SerialName("count")
    val count: Int? = null,

    @SerialName("signal")
    val signal: Int? = null,

    @SerialName("channel")
    val channel: Int? = null,

    @SerialName("bssid")
    val bssid: String? = null,

    // Deauth target — the client station MAC (sent top-level by the plugin for deauth events).
    @SerialName("station")
    val station: String? = null,

    @SerialName("security")
    val security: String? = null,

    @SerialName("reason")
    val reason: String? = null,

    @SerialName("total_captures")
    val totalCaptures: Int? = null,

    // ── auto-tune stats ──────────────────────────────────────────────────────
    @SerialName("autotune_channels")
    val autotuneChannels: Map<String, AutotuneChannelStat>? = null,

    @SerialName("autotune_best_channel")
    val autotuneBestChannel: Int? = null,

    @SerialName("autotune_min_rssi")
    val autotuneMinRssi: Int? = null,

    // The channels the device's monitor interface actually supports (reg-domain aware).
    // Lets the steering bandit discover 5 GHz on dual-band adapters instead of a
    // hardcoded 2.4 GHz list. Null on older plugins → app falls back to the 2.4 floor.
    @SerialName("supported_channels")
    val supportedChannels: List<Int>? = null,

    // Whether the device's wpa-sec cracking plugin is enabled + downloading results
    // (reported in status messages), so the app can flag if cracking is actually on.
    @SerialName("wpa_sec_enabled")  val wpaSecEnabled: Boolean? = null,
    @SerialName("wpa_sec_download") val wpaSecDownload: Boolean? = null,
    @SerialName("wpa_sec_online")   val wpaSecOnline: Boolean? = null,

    // Whether the device's e-ink is inverted (black-on-white / 'light'), from its config —
    // the app mirrors the web /ui screenshot which honours this, so it normalises a light
    // face onto the dark console. null = unknown yet.
    @SerialName("ui_invert")        val uiInvert: Boolean? = null,

    // ── capture history (geolocated handshakes) ──────────────────────────────
    @SerialName("captures")
    val captures: List<CaptureEntry>? = null,

    // Raw handshake-file count on the device (pre app-side BSSID dedup) — so the app
    // can show "N networks · M handshakes" and match the pwnagotchi's own count.
    @SerialName("total_files")
    val totalFiles: Int? = null,

    // True on a full-history snapshot (connect seed / post-clean resend) vs a single-entry
    // append. The app treats a full snapshot as authoritative and reconciles partials to it,
    // so a partial the device deleted (e.g. via clean_partials) stops re-syncing back.
    @SerialName("full")
    val full: Boolean? = null,

    // ── cracked results (from wpa-sec, matched to captures by BSSID) ──────────
    @SerialName("results")
    val crackedResults: List<CrackedResult>? = null,

    // ── device telemetry (per-epoch vitals / reward / mood) ──────────────────
    @SerialName("temperature")        val temperature: Double? = null,
    @SerialName("cpu_load")           val cpuLoad: Double? = null,
    @SerialName("mem_usage")          val memUsage: Double? = null,
    @SerialName("reward")             val reward: Double? = null,
    @SerialName("num_aps")            val numAps: Int? = null,
    @SerialName("num_sta")            val numSta: Int? = null,
    @SerialName("num_peers")          val numPeers: Int? = null,
    @SerialName("active_for_epochs")   val activeForEpochs: Int? = null,
    @SerialName("inactive_for_epochs") val inactiveForEpochs: Int? = null,
    @SerialName("bored_for_epochs")    val boredForEpochs: Int? = null,
    @SerialName("sad_for_epochs")      val sadForEpochs: Int? = null,
    @SerialName("blind_for_epochs")    val blindForEpochs: Int? = null,
    @SerialName("epoch")              val epoch: Int? = null,
    @SerialName("total_handshakes")   val totalHandshakes: Int? = null,
) {
    /** Build a DeviceTelemetry from this message's telemetry fields. */
    fun toTelemetry(): DeviceTelemetry = DeviceTelemetry(
        temperature = temperature, cpuLoad = cpuLoad, memUsage = memUsage, reward = reward,
        numAps = numAps, numSta = numSta, numPeers = numPeers,
        activeForEpochs = activeForEpochs, inactiveForEpochs = inactiveForEpochs,
        boredForEpochs = boredForEpochs, sadForEpochs = sadForEpochs, blindForEpochs = blindForEpochs,
        epoch = epoch, totalHandshakes = totalHandshakes,
    )
    /** Resolved Pwnagotchi name: snake_case → camelCase → null */
    val resolvedDeviceName: String?
        get() = deviceNameSnake?.takeIf { it.isNotBlank() }
            ?: deviceName?.takeIf { it.isNotBlank() }

    companion object {
        const val TYPE_IMAGE = "image"
        const val TYPE_GPS_REQUEST = "gps_request"
        const val TYPE_GPS = "gps"
        const val TYPE_GPS_RECEIVED = "gps_received"   // ack from Pwnagotchi after receiving GPS
        const val TYPE_READY = "ready"                 // Pwnagotchi signals it is ready after connecting
        const val TYPE_STATUS = "status"
        const val TYPE_NETWORK_EVENT = "network_event"
        const val TYPE_AUTOTUNE = "autotune_stats"
        const val TYPE_CAPTURE_HISTORY = "capture_history"
        const val TYPE_DEVICE_TELEMETRY = "device_telemetry"
        const val TYPE_CRACKED = "cracked"

        @Suppress("UNUSED") const val CONTENT_TYPE_PNG = "image/png"
        @Suppress("UNUSED") const val CONTENT_TYPE_JPEG = "image/jpeg"
    }
}

/**
 * A single captured handshake, optionally geolocated.
 * Built by the plugin from <ssid>_<bssid>.pcap + matching .gps.json sidecar.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class CaptureEntry(
    @SerialName("ssid")      val ssid: String = "unknown",
    @SerialName("bssid")     val bssid: String = "",
    @SerialName("latitude")  val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("accuracy")  val accuracy: Double? = null,
    @SerialName("timestamp") val timestamp: Long? = null,
    // Handshake quality from hcxpcapngtool (plugin): "eapol" | "pmkid" (both crackable)
    // | "partial" (uncrackable grab) | null (unknown / tool unavailable).
    @SerialName("quality")   val quality: String? = null,
    // Cracked password, once wpa-sec returns one for this BSSID (applied app-side, not
    // sent by the capture message itself). Null = not cracked (yet).
    @SerialName("password")  val password: String? = null,
    // hashcat-22000 line (WPA*01 PMKID / WPA*02 EAPOL) from the plugin's hcxpcapngtool, for
    // on-phone cracking. Null when the grab isn't crackable or the tool was unavailable.
    @SerialName("hash22000") val hash22000: String? = null,
    // The AP's channel, from the plugin (on_handshake / gps sidecar). Null = unknown
    // (older plugin, or a pre-channel capture) — the band tag is just hidden then.
    @SerialName("channel")   val channel: Int? = null,
) {
    /** Stable identity for de-duping across reconnects / live appends. */
    val key: String get() = if (bssid.isNotBlank()) bssid else "$ssid@$timestamp"
    /** Radio band derived from the channel: "2.4" | "5" | null when unknown. */
    val band: String? get() = channel?.let { if (it in 1..14) "2.4" else if (it >= 36) "5" else null }
    val isGeolocated: Boolean get() = latitude != null && longitude != null
    /** True when the capture yields a crackable hash (PMKID or full EAPOL). */
    val isCrackable: Boolean get() = quality == "eapol" || quality == "pmkid"
    /** True when we know it's an uncrackable partial grab. */
    val isPartial: Boolean get() = quality == "partial"
    /** True once wpa-sec has returned the password for this capture. */
    val isCracked: Boolean get() = !password.isNullOrBlank()
}

/** A cracked Wi-Fi password from wpa-sec, matched to a capture by BSSID. */
@OptIn(InternalSerializationApi::class)
@Serializable
data class CrackedResult(
    @SerialName("bssid")    val bssid: String = "",
    @SerialName("ssid")     val ssid: String = "",
    @SerialName("password") val password: String = "",
)

/** Per-channel statistics from auto-tune plugin */
@OptIn(InternalSerializationApi::class)
@Serializable
data class AutotuneChannelStat(
    @SerialName("handshakes")   val handshakes: Int = 0,
    @SerialName("deauths")      val deauths: Int = 0,
    @SerialName("associations") val associations: Int = 0,
    /** Peak APs observed while hopping on this channel (target density). */
    @SerialName("aps")          val aps: Int = 0,
    /** Peak client stations observed on this channel. */
    @SerialName("sta")          val sta: Int = 0,
)

/**
 * Per-epoch device telemetry from the pwnagotchi (vitals, RL reward, mood counters).
 * Drives the app's [ vitals ] section and feeds the emergent AI personality.
 */
data class DeviceTelemetry(
    val temperature: Double? = null,
    val cpuLoad: Double? = null,
    val memUsage: Double? = null,
    val reward: Double? = null,
    val numAps: Int? = null,
    val numSta: Int? = null,
    val numPeers: Int? = null,
    val activeForEpochs: Int? = null,
    val inactiveForEpochs: Int? = null,
    val boredForEpochs: Int? = null,
    val sadForEpochs: Int? = null,
    val blindForEpochs: Int? = null,
    val epoch: Int? = null,
    val totalHandshakes: Int? = null,
) {
    /** True when the message carried at least one telemetry value. */
    val hasData: Boolean
        get() = listOfNotNull(temperature, cpuLoad, memUsage, reward).isNotEmpty() ||
            listOfNotNull(numAps, numSta, numPeers, epoch).isNotEmpty()
}

/** Response message sent to Pwnagotchi via WebSocket (GPS, acks). */
@OptIn(InternalSerializationApi::class)
@Serializable
@Suppress("UNUSED")
data class WebSocketResponse(
    @SerialName("type")      val type: String,
    @SerialName("data")      val data: String? = null,
    @SerialName("latitude")  val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("accuracy")  val accuracy: Float? = null,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
