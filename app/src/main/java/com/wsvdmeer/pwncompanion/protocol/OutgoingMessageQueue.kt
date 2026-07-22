package com.wsvdmeer.pwncompanion.protocol

import android.util.Log
import com.wsvdmeer.pwncompanion.models.ScreenData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Outgoing Message Queue - Manages messages to send to devices.
 * Queues commands and responses for batch transmission.
 * Tracks queue status and transmission attempts.
 * Used in Phase 8 for sending GPS location, commands, etc.
 */
class OutgoingMessageQueue {
    private val tag = "OutgoingMessageQueue"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val messageQueue = ConcurrentLinkedQueue<QueuedMessage>()

    // Queue state for UI observation
    private val _queueSize = MutableStateFlow(0)
    val queueSize = _queueSize.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    @Suppress("UNUSED")
    val isPaused = _isPaused.asStateFlow()

    data class QueuedMessage(
        val deviceId: String,
        val message: ScreenData,
        val timestamp: Long = System.currentTimeMillis(),
        var attemptCount: Int = 0,
        val maxRetries: Int = 3
    ) {
        fun shouldRetry(): Boolean = attemptCount < maxRetries
        @Suppress("UNUSED")
        fun canSend(): Boolean = attemptCount == 0
    }

    /**
     * Queue a message for transmission to device.
     * Messages are held until explicitly sent or dequeued.
     */
    fun enqueue(deviceId: String, message: ScreenData): Boolean {
        return try {
            val queuedMsg = QueuedMessage(deviceId, message)
            messageQueue.offer(queuedMsg)
            _queueSize.value = messageQueue.size

            Log.d(tag, "Message queued: device=$deviceId, type=${message.type}, queueSize=${messageQueue.size}")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error enqueuing message: ${e.message}", e)
            false
        }
    }

    /**
     * Get next message to send without removing from queue.
     * Useful for checking what's next to send.
     */
    @Suppress("UNUSED")
    fun peek(): QueuedMessage? = messageQueue.peek()

    /**
     * Dequeue next message for transmission.
     * Returns null if queue is empty.
     */
    @Suppress("UNUSED")
    fun dequeue(): QueuedMessage? {
        val msg = messageQueue.poll()
        _queueSize.value = messageQueue.size
        return msg
    }

    /**
     * Re-queue a message that failed to send (for retry).
     */
    @Suppress("UNUSED")
    fun requeue(queuedMessage: QueuedMessage): Boolean {
        return try {
            if (queuedMessage.shouldRetry()) {
                queuedMessage.attemptCount++
                messageQueue.offer(queuedMessage)
                _queueSize.value = messageQueue.size

                Log.d(tag, "Message re-queued for retry: attempt=${queuedMessage.attemptCount}/${queuedMessage.maxRetries}")
                true
            } else {
                Log.w(tag, "Message max retries exceeded, discarding")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error re-queueing message: ${e.message}", e)
            false
        }
    }

    /**
     * Queue a GPS response message.
     * Creates ScreenData with location update for device.
     * Uses TYPE_GPS to send the actual location data back to Pwnagotchi.
     * Includes all location fields: latitude, longitude, accuracy, and altitude.
     */
    fun queueLocationResponse(deviceId: String, latitude: Double, longitude: Double, accuracy: Float?, altitude: Double = 0.0) {
        try {
            val message = ScreenData(
                type = ScreenData.TYPE_GPS,  // Send as "gps" type
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy?.toDouble() ?: 0.0,
                altitude = altitude,  // Include altitude
                timestamp = System.currentTimeMillis(),
                deviceName = deviceId
            )
            enqueue(deviceId, message)
            Log.d(tag, "Location response queued: device=$deviceId, lat=$latitude, lon=$longitude, alt=$altitude")
        } catch (e: Exception) {
            Log.e(tag, "Error queueing location response: ${e.message}", e)
        }
    }

    /**
     * Queue a command message.
     * Creates a command ScreenData for device execution.
     */
    fun queueCommand(deviceId: String, commandType: String, commandData: String?): Boolean {
        return try {
            val message = ScreenData(
                type = "command",
                data = commandData,
                message = commandType,
                timestamp = System.currentTimeMillis(),
                deviceName = deviceId
            )
            enqueue(deviceId, message)
            Log.d(tag, "Command queued: device=$deviceId, type=$commandType")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error queueing command: ${e.message}", e)
            false
        }
    }

    /**
     * Clear all queued messages.
     */
    @Suppress("UNUSED")
    fun clear() {
        messageQueue.clear()
        _queueSize.value = 0
        Log.d(tag, "Message queue cleared")
    }

    /**
     * Pause queue processing.
     * Messages remain queued but won't be sent.
     */
    @Suppress("UNUSED")
    fun pause() {
        _isPaused.value = true
        Log.i(tag, "Message queue paused")
    }

    /**
     * Resume queue processing.
     */
    @Suppress("UNUSED")
    fun resume() {
        _isPaused.value = false
        Log.i(tag, "Message queue resumed")
    }

    /**
     * Get current queue size.
     */
    @Suppress("UNUSED")
    fun getQueueSize(): Int = messageQueue.size

    /**
     * Check if queue is empty.
     */
    @Suppress("UNUSED")
    fun isEmpty(): Boolean = messageQueue.isEmpty()

    /**
     * Get all queued messages (for debugging).
     */
    @Suppress("UNUSED")
    fun getAllMessages(): List<QueuedMessage> = messageQueue.toList()

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        messageQueue.clear()
        scope.coroutineContext[Job]?.cancel()
        Log.i(tag, "Outgoing message queue cleaned up")
    }
}
