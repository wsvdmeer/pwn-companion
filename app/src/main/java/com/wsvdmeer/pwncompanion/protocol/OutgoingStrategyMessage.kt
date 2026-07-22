package com.wsvdmeer.pwncompanion.protocol

import com.wsvdmeer.pwncompanion.models.Strategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Outgoing Strategy Messages - Commands to send to Pwnagotchi.
 * M3-inspired clear, actionable messaging.
 */
sealed class OutgoingStrategyMessage {

    /**
     * Strategy Command - Main instruction for WiFi optimization.
     * Sent every 45 seconds with updated strategy.
     */
    @Serializable
    data class StrategyCommand(
        val type: String = "strategy_command",
        val device_id: String,
        val mode: String,                      // AGGRESSIVE | BALANCED | CONSERVATIVE | SLEEP
        val recommended_channels: List<Int>,
        val deauth_multiplier: Float,
        val should_cooldown: Boolean,
        val reason: String,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    ) : OutgoingStrategyMessage() {

        fun toJson(): String {
            return Json.encodeToString(this)
        }
    }

    /**
     * Location Learning Sync - Sends GPS-based WiFi knowledge.
     * Sent every 60 minutes with learned location patterns.
     */
    @Serializable
    data class LocationLearningSync(
        val type: String = "location_learning_sync",
        val device_id: String,
        val locations: List<LocationProfile>,
        val timestamp: Long = System.currentTimeMillis()
    ) : OutgoingStrategyMessage() {

        fun toJson(): String {
            return Json.encodeToString(this)
        }
    }

    /**
     * Time-of-Day Learning Sync - Sends temporal WiFi patterns.
     * Sent every 7 days with hourly performance metrics.
     */
    @Serializable
    data class TimeLearningSync(
        val type: String = "time_learning_sync",
        val device_id: String,
        val time_profiles: Map<String, TimeProfile>,
        val timestamp: Long = System.currentTimeMillis()
    ) : OutgoingStrategyMessage() {

        fun toJson(): String {
            return Json.encodeToString(this)
        }
    }
}

/**
 * Location Profile - WiFi learning data for a geographic location.
 */
@Serializable
data class LocationProfile(
    val latitude: Double,
    val longitude: Double,
    val radius_meters: Float = 100f,
    val label: String,                     // "Home", "Office", auto-detected name
    val best_channel: Int,
    val best_time_of_day: Int,
    val success_rate: Float,
    val observation_count: Int
)

/**
 * Time Profile - WiFi learning data for a time-of-day.
 */
@Serializable
data class TimeProfile(
    val best_channel: Int,
    val confidence: Float,
    val observation_count: Int,
    val intensity: Float                   // 0.0-1.0 for visualization
)

/**
 * Extension function to convert Strategy to StrategyCommand.
 */
fun Strategy.toStrategyCommand(deviceId: String): OutgoingStrategyMessage.StrategyCommand {
    return OutgoingStrategyMessage.StrategyCommand(
        device_id = deviceId,
        mode = this.mode.name,
        recommended_channels = this.recommendedChannels,
        deauth_multiplier = this.deauthMultiplier,
        should_cooldown = this.shouldCooldown,
        reason = this.reason,
        confidence = this.confidence
    )
}

