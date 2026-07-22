package com.wsvdmeer.pwncompanion.services

import android.util.Log
import com.wsvdmeer.pwncompanion.models.GpsData
import com.wsvdmeer.pwncompanion.models.PersonalityState
import com.wsvdmeer.pwncompanion.models.Strategy
import com.wsvdmeer.pwncompanion.models.StrategyMode
import com.wsvdmeer.pwncompanion.models.WifiTelemetry
import kotlin.math.PI
import kotlin.math.cos

/**
 * Strategy Decision Engine - Converts learned WiFi patterns into actionable strategies.
 * Uses heuristic rules based on personality, environment, and learned patterns.
 * M3-compliant with clear decision reasoning.
 */
class StrategyDecisionEngine(private val memoryService: WifiMemoryService) {

    private val tag = "StrategyDecisionEngine"

    /**
     * Generate WiFi optimization strategy based on current state.
     * Applies heuristic rules in priority order: Safety → Personality → Channel Selection.
     */
    suspend fun decideStrategy(
        personality: PersonalityState,
        telemetry: WifiTelemetry,
        gpsData: GpsData?,
        battery: Int,
        temperature: Int
    ): Strategy {
        try {
            Log.d(tag, "Deciding strategy: battery=$battery, temp=$temperature, mood=${personality.mood}")

            // ========== SAFETY RULES (Highest Priority) ==========

            // Rule 1: Battery critical
            if (battery < 20) {
                return conservative(
                    reason = "🪫 Battery critical (<20%)",
                    confidence = 1.0f
                )
            }

            // Rule 2: Overheating
            if (temperature > 65) {
                return cooldown(
                    reason = "🌡️ Temperature critical (>65°C)",
                    confidence = 1.0f
                )
            }

            // ========== PERSONALITY RULES ==========

            // Personality multipliers affect aggressiveness
            val moodMultiplier = (personality.mood + 1.0f) / 2.0f  // Scale: 0.0 (sad) to 1.0 (happy)
            val hungerDrive = personality.hunger > 0.7f
            val isTired = personality.tiredness > 0.8f

            // Rule 3: Pwnagotchi is exhausted
            if (isTired) {
                return sleep(
                    reason = "😴 Very tired (${(personality.tiredness * 100).toInt()}%)",
                    confidence = 0.95f
                )
            }

            // Rule 4: Moderate temperature warning
            if (temperature > 55) {
                return conservative(
                    reason = "⚠️ Temperature warning (${temperature}°C)",
                    confidence = 0.9f
                )
            }

            // ========== CHANNEL SELECTION RULES ==========

            // Get learned best channels from memory service
            val bestChannels = if (gpsData != null) {
                memoryService.getBestChannelsForLocation(
                    gpsData.latitude,
                    gpsData.longitude,
                    radiusMeters = 100f
                )
                    .take(3)
                    .map { it.bestChannel }
            } else {
                // Fallback to all-time best channels
                memoryService.getChannelInsights()
                    .take(3)
                    .map { it.channel }
            }

            // ========== CLIENT DENSITY HEURISTICS ==========

            val clientDensity = if (telemetry.apsCount > 0) {
                telemetry.clientsCount.toFloat() / telemetry.apsCount
            } else {
                0f
            }

            Log.d(tag, "Client density: $clientDensity (clients=${telemetry.clientsCount}, APs=${telemetry.apsCount})")

            // Rule 5: High client density (opportunity)
            if (clientDensity > 2.5f) {
                val deauthMult = 1.8f * moodMultiplier  // Very aggressive if happy
                return Strategy(
                    mode = StrategyMode.AGGRESSIVE,
                    recommendedChannels = bestChannels.ifEmpty { listOf(1, 6, 11) },
                    deauthMultiplier = deauthMult.coerceIn(0f, 2.0f),
                    shouldCooldown = false,
                    sleepMode = false,
                    reason = "🔥 High density (${"%.1f".format(clientDensity)}) + Mood: ${"%.0f".format(moodMultiplier * 100)}%",
                    confidence = 0.9f
                )
            }

            // Rule 6: Medium client density (balanced)
            if (clientDensity > 1.2f) {
                val deauthMult = 1.2f * moodMultiplier
                return Strategy(
                    mode = StrategyMode.BALANCED,
                    recommendedChannels = bestChannels.ifEmpty { listOf(1, 6, 11) },
                    deauthMultiplier = deauthMult.coerceIn(0f, 2.0f),
                    shouldCooldown = false,
                    sleepMode = false,
                    reason = "⚖️ Medium density (${"%.1f".format(clientDensity)}) + Balanced approach",
                    confidence = 0.85f
                )
            }

            // Rule 7: Low client density (conservative)
            val deauthMult = 0.6f * moodMultiplier
            return Strategy(
                mode = StrategyMode.CONSERVATIVE,
                recommendedChannels = bestChannels.ifEmpty { listOf(1, 6, 11) },
                deauthMultiplier = deauthMult.coerceIn(0f, 2.0f),
                shouldCooldown = false,
                sleepMode = false,
                reason = "🔇 Low density (${"%.1f".format(clientDensity)}) + Conservative scan",
                confidence = 0.75f
            )

        } catch (e: Exception) {
            Log.e(tag, "Error deciding strategy: ${e.message}", e)
            return conservative(
                reason = "❌ Error in strategy: ${e.message?.take(30)}",
                confidence = 0.5f
            )
        }
    }

    /**
     * Create conservative strategy with explanation.
     */
    private fun conservative(reason: String, confidence: Float): Strategy {
        return Strategy(
            mode = StrategyMode.CONSERVATIVE,
            recommendedChannels = listOf(1, 6, 11),
            deauthMultiplier = 0.5f,
            shouldCooldown = false,
            sleepMode = false,
            reason = reason,
            confidence = confidence
        )
    }

    /**
     * Create cooldown strategy (temperature management).
     */
    private fun cooldown(reason: String, confidence: Float): Strategy {
        return Strategy(
            mode = StrategyMode.CONSERVATIVE,
            recommendedChannels = emptyList(),
            deauthMultiplier = 0.2f,
            shouldCooldown = true,
            sleepMode = false,
            reason = reason,
            confidence = confidence
        )
    }

    /**
     * Create sleep strategy (power management).
     */
    private fun sleep(reason: String, confidence: Float): Strategy {
        return Strategy(
            mode = StrategyMode.SLEEP,
            recommendedChannels = emptyList(),
            deauthMultiplier = 0.0f,
            shouldCooldown = false,
            sleepMode = true,
            reason = reason,
            confidence = confidence
        )
    }
}

