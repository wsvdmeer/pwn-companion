package com.wsvdmeer.pwncompanion.protocol

import android.util.Log
import com.wsvdmeer.pwncompanion.models.ScreenData
import java.util.concurrent.ConcurrentHashMap

/**
 * Device Message Processor - Handles different ScreenData message types.
 * Routes messages to appropriate handlers based on message type.
 * Manages device-specific message processing pipelines.
 */
class DeviceMessageProcessor {
    private val tag = "DeviceMessageProcessor"
    private val messageHandlers = ConcurrentHashMap<String, suspend (String, ScreenData) -> Unit>()
    private val messageStats = ConcurrentHashMap<String, MessageStats>()

    data class MessageStats(
        var processedCount: Long = 0,
        var errorCount: Long = 0,
        var lastProcessedTime: Long = 0
    )

    /**
     * Register a handler for a specific message type.
     * Handlers are called when messages of that type are processed.
     */
    fun registerHandler(messageType: String, handler: suspend (String, ScreenData) -> Unit) {
        Log.d(tag, "Registering handler for message type: $messageType")
        messageHandlers[messageType] = handler
    }

    /**
     * Unregister a handler for a specific message type.
     */
    @Suppress("UNUSED")
    fun unregisterHandler(messageType: String) {
        Log.d(tag, "Unregistering handler for message type: $messageType")
        messageHandlers.remove(messageType)
    }

    /**
     * Process incoming message.
     * Routes to appropriate handler based on message type.
     * Handles errors gracefully without crashing the service.
     */
    suspend fun processMessage(deviceId: String, message: ScreenData) {
        val messageType = message.type
        val stats = messageStats.getOrPut(messageType) { MessageStats() }

        try {
            Log.d(tag, "Processing message: type=$messageType, deviceId=$deviceId")

            // Get handler for message type
            val handler = messageHandlers[messageType]

            if (handler != null) {
                // Call handler with deviceId
                handler.invoke(deviceId, message)
                Log.d(tag, "Message processed successfully: type=$messageType")

                // Update stats
                stats.processedCount++
                stats.lastProcessedTime = System.currentTimeMillis()
            } else {
                Log.w(tag, "No handler registered for message type: $messageType")
                // Still count as processed (just unhandled)
                stats.processedCount++
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing message type=$messageType: ${e.message}", e)
            stats.errorCount++
        }
    }

    /**
     * Get statistics for a specific message type.
     */
    @Suppress("UNUSED")
    fun getMessageStats(messageType: String): MessageStats? {
        return messageStats[messageType]?.copy()
    }

    /**
     * Get all message statistics.
     */
    fun getAllMessageStats(): Map<String, MessageStats> {
        return messageStats.mapValues { it.value.copy() }
    }

    /**
     * Reset statistics.
     */
    fun resetStats() {
        messageStats.clear()
        Log.d(tag, "Message statistics reset")
    }
}
