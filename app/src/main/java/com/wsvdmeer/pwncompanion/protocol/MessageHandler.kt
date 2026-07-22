package com.wsvdmeer.pwncompanion.protocol

import android.util.Log
import com.wsvdmeer.pwncompanion.models.ScreenData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Message Handler - Central orchestrator for message protocol.
 * Processes incoming device messages and manages message flow.
 * Exposes device events via SharedFlow for UI subscription.
 * Tracks message statistics and coordinates protocol handlers.
 */
class MessageHandler {
    private val tag = "MessageHandler"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val messageProcessor = DeviceMessageProcessor()
    
    // SharedFlow for exposing device events to UI
    private val _deviceImageUpdates = MutableSharedFlow<ImageUpdate>(replay = 1)
    val deviceImageUpdates = _deviceImageUpdates.asSharedFlow()

    private val _deviceStatusUpdates = MutableSharedFlow<StatusUpdate>(replay = 1)
    val deviceStatusUpdates = _deviceStatusUpdates.asSharedFlow()

    private val _deviceGpsUpdates = MutableSharedFlow<GpsUpdate>(replay = 1)
    val deviceGpsUpdates = _deviceGpsUpdates.asSharedFlow()

    // Network events from the Pwnagotchi plugin (handshakes, deauths, discoveries …)
    private val _networkEventUpdates = MutableSharedFlow<NetworkEventUpdate>(replay = 0)
    val networkEventUpdates = _networkEventUpdates.asSharedFlow()

    // Pwnagotchi device mood updates — triggers app mood sync
    private val _deviceMoodUpdates = MutableSharedFlow<MoodUpdate>(replay = 1)
    val deviceMoodUpdates = _deviceMoodUpdates.asSharedFlow()

    data class NetworkEventUpdate(
        val deviceId: String,
        val eventType: String,       // e.g. "handshakes_captured", "network_discovered"
        val description: String,     // human-readable, ready to pass to LLM
        val network: String? = null,
        val count: Int = 0,
        val signal: Int? = null,
        val channel: Int? = null,
        val bssid: String? = null,
        val station: String? = null,   // deauth target (client MAC)
        val security: String? = null,
        val totalCaptures: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Message statistics
    private val totalMessagesProcessed = AtomicLong(0)
    private val totalMessagesErrors = AtomicLong(0)

    data class ImageUpdate(
        val deviceId: String,
        val imageData: String,  // Base64-encoded
        val contentType: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class StatusUpdate(
        val deviceId: String,
        val status: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class GpsUpdate(
        val deviceId: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double,
        val altitude: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class MoodUpdate(
        val deviceId: String,
        val moodName: String,   // raw pwnagotchi mood: "HAPPY", "BORED", "EXCITED", etc.
        val timestamp: Long = System.currentTimeMillis()
    )

    /** AUTO = scanning actively, MANUAL = user-controlled, no WiFi scanning */
    data class ModeUpdate(
        val deviceId: String,
        val isAutoMode: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Pwnagotchi operating mode — emitted whenever the device reports AUTO or MANUAL
    private val _deviceModeUpdates = MutableSharedFlow<ModeUpdate>(replay = 1)
    val deviceModeUpdates = _deviceModeUpdates.asSharedFlow()

    init {
        setupMessageHandlers()
    }

    /**
     * Setup default message handlers.
     */
    private fun setupMessageHandlers() {
        // Note: Handlers receive only the message, deviceId is stored separately
        // Will be refactored to pass deviceId directly in the future
        
        // IMAGE handler
        messageProcessor.registerHandler(ScreenData.TYPE_IMAGE) { deviceId, message ->
            handleImageMessage(deviceId, message)
        }

        // GPS handler
        messageProcessor.registerHandler(ScreenData.TYPE_GPS) { deviceId, message ->
            handleGpsMessage(deviceId, message)
        }

        // GPS_REQUEST handler
        // NOTE: The actual GPS response is sent from NetworkService.onDataReceived() which has
        // access to the lastGpsData cache and the OutgoingMessageQueue. This handler just logs.
        messageProcessor.registerHandler(ScreenData.TYPE_GPS_REQUEST) { deviceId, message ->
            handleGpsRequestMessage(deviceId, message)
        }

        // STATUS handler
        messageProcessor.registerHandler(ScreenData.TYPE_STATUS) { deviceId, message ->
            handleStatusMessage(deviceId, message)
        }

        // NETWORK_EVENT handler — deauths, handshakes, discoveries → AI personality
        messageProcessor.registerHandler(ScreenData.TYPE_NETWORK_EVENT) { deviceId, message ->
            handleNetworkEventMessage(deviceId, message)
        }

        // AUTOTUNE_STATS handler — acknowledged so it's not logged as unhandled
        messageProcessor.registerHandler(ScreenData.TYPE_AUTOTUNE) { deviceId, message ->
            Log.d(tag, "autotune_stats received from $deviceId: best_ch=${message.autotuneBestChannel}, rssi=${message.autotuneMinRssi}, channels=${message.autotuneChannels?.size ?: 0}")
        }

        // GPS_RECEIVED handler — Pwnagotchi acknowledgment after it receives GPS coords
        messageProcessor.registerHandler(ScreenData.TYPE_GPS_RECEIVED) { deviceId, _ ->
            Log.d(tag, "GPS ack received from $deviceId")
        }

        // READY handler — Pwnagotchi signals it is ready after connecting
        messageProcessor.registerHandler(ScreenData.TYPE_READY) { deviceId, _ ->
            Log.d(tag, "Ready signal received from $deviceId")
        }

        Log.i(tag, "Message handlers initialized")
    }

    /**
     * Process incoming message from device.
     * Called by WebSocketServerService when message is received.
     */
    suspend fun handleIncomingMessage(deviceId: String, message: ScreenData) {
        try {
            Log.d(tag, "Handling incoming message: type=${message.type}, device=$deviceId")
            messageProcessor.processMessage(deviceId, message)
            totalMessagesProcessed.incrementAndGet()
        } catch (e: Exception) {
            Log.e(tag, "Error handling incoming message: ${e.message}", e)
            totalMessagesErrors.incrementAndGet()
        }
    }

    /**
     * Handle IMAGE message - emit to UI via SharedFlow.
     */
    private fun handleImageMessage(deviceId: String, message: ScreenData) {
        try {
            val imageData = message.data

            if (imageData != null) {
                val contentType = message.contentType ?: ScreenData.CONTENT_TYPE_PNG
                Log.d(tag, "Image message processed, emitting update for device=$deviceId")

                scope.launch {
                    _deviceImageUpdates.emit(
                        ImageUpdate(
                            deviceId = deviceId,
                            imageData = imageData,
                            contentType = contentType
                        )
                    )
                }
            } else {
                Log.w(tag, "Image message missing data")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing image message: ${e.message}", e)
        }
    }

    /**
     * Handle GPS_REQUEST message.
     * The actual GPS response packet is enqueued by NetworkService.onDataReceived() which
     * has access to the last cached GPS fix and the OutgoingMessageQueue.
     * This handler just logs the receipt so message stats remain accurate.
     */
    private fun handleGpsRequestMessage(deviceId: String, @Suppress("UNUSED") message: ScreenData) {
        Log.i(tag, "GPS request received from device: $deviceId (response handled by NetworkService)")
    }

    /**
     * Handle GPS message from device.
     * NOTE: The Pwnagotchi plugin does NOT send 'gps' type messages to the app — it only
     * sends 'gps_request' and 'gps_received'. This handler is kept for forward-compatibility
     * but will not normally be triggered in the current protocol.
     */
    private fun handleGpsMessage(deviceId: String, message: ScreenData) {
        try {
            val latitude = message.latitude ?: return
            val longitude = message.longitude ?: return
            val accuracy = message.accuracy ?: 0.0
            val altitude = message.altitude ?: 0.0

            Log.d(tag, "GPS message received: lat=$latitude, lon=$longitude, acc=$accuracy, alt=$altitude")

            scope.launch {
                _deviceGpsUpdates.emit(
                    GpsUpdate(
                        deviceId = deviceId,
                        latitude = latitude,
                        longitude = longitude,
                        accuracy = accuracy,
                        altitude = altitude
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing GPS message: ${e.message}", e)
        }
    }

    /**
     * Handle NETWORK_EVENT message — WiFi events from PwnagotchiEventBroadcaster.
     * Converts to NetworkEventUpdate and emits for the AI personality system.
     */
    private fun handleNetworkEventMessage(deviceId: String, message: ScreenData) {
        try {
            val eventType = message.eventType ?: "unknown"
            // Use the plugin-built description if available, otherwise construct one
            val description = message.eventDescription
                ?: when (eventType) {
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

            Log.i(tag, "Network event: $eventType — $description")

            scope.launch {
                _networkEventUpdates.emit(
                    NetworkEventUpdate(
                        deviceId     = deviceId,
                        eventType    = eventType,
                        description  = description,
                        network      = message.network,
                        count        = message.count ?: 0,
                        signal       = message.signal,
                        channel      = message.channel,
                        bssid        = message.bssid,
                        station      = message.station,
                        security     = message.security,
                        totalCaptures = message.totalCaptures ?: 0,
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing network event: ${e.message}", e)
        }
    }

    /**
     * Handle STATUS message - emit device state update.
     */
    private fun handleStatusMessage(deviceId: String, message: ScreenData) {
        try {
            val status = message.status ?: "UNKNOWN"
            val statusMessage = message.message ?: ""

            Log.d(tag, "Status message processed: device=$deviceId, status=$status")

            scope.launch {
                _deviceStatusUpdates.emit(
                    StatusUpdate(
                        deviceId = deviceId,
                        status = status,
                        message = statusMessage
                    )
                )
            }

            // Emit mood update if the plugin included one
            val rawMood = message.pwnagotchiMood
            if (!rawMood.isNullOrBlank()) {
                Log.i(tag, "Device mood update from $deviceId: $rawMood")
                scope.launch {
                    _deviceMoodUpdates.emit(MoodUpdate(deviceId = deviceId, moodName = rawMood))
                }
            }

            // Emit mode update if the plugin included one
            val rawMode = message.pwnagotchiMode
            if (!rawMode.isNullOrBlank()) {
                val isAuto = rawMode.uppercase() == "AUTO"
                Log.i(tag, "Device mode update from $deviceId: $rawMode (isAuto=$isAuto)")
                scope.launch {
                    _deviceModeUpdates.emit(ModeUpdate(deviceId = deviceId, isAutoMode = isAuto))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing status message: ${e.message}", e)
        }
    }

    /**
     * Get message processor for advanced configuration.
     */
    @Suppress("UNUSED")
    fun getMessageProcessor(): DeviceMessageProcessor = messageProcessor

    /**
     * Get total messages processed.
     */
    @Suppress("UNUSED")
    fun getTotalMessagesProcessed(): Long = totalMessagesProcessed.get()

    /**
     * Get total message processing errors.
     */
    @Suppress("UNUSED")
    fun getTotalMessageErrors(): Long = totalMessagesErrors.get()

    /**
     * Get message statistics.
     */
    @Suppress("UNUSED")
    fun getMessageStats() = messageProcessor.getAllMessageStats()

    /**
     * Reset statistics.
     */
    @Suppress("UNUSED")
    fun resetStats() {
        totalMessagesProcessed.set(0)
        totalMessagesErrors.set(0)
        messageProcessor.resetStats()
        Log.i(tag, "Message handler statistics reset")
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        scope.coroutineContext[Job]?.cancel()
        Log.i(tag, "Message handler cleaned up")
    }
}
