package com.wsvdmeer.pwncompanion.services

import android.util.Log
import com.wsvdmeer.pwncompanion.database.WifiObservation
import com.wsvdmeer.pwncompanion.database.WifiObservationRepository
import com.wsvdmeer.pwncompanion.models.ChannelInsight
import com.wsvdmeer.pwncompanion.models.HourlyStats
import com.wsvdmeer.pwncompanion.models.LearningStats
import com.wsvdmeer.pwncompanion.models.LocationLearningSummary
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.flow.first

/**
 * WiFi Memory Service - Manages WiFi learning data.
 * Records observations, calculates success rates, learns patterns by location and time.
 * Uses DataStore-based WifiObservationRepository instead of Room DAO.
 */
class WifiMemoryService(private val repository: WifiObservationRepository) {

    private val tag = "WifiMemoryService"

    /**
     * Record a WiFi observation in the data store.
     */
    suspend fun recordObservation(observation: WifiObservation) {
        try {
            repository.insertObservation(observation)
            Log.d(tag, "Observation recorded: Ch${observation.channel}, SSID=${observation.ssid}")
        } catch (e: Exception) {
            Log.e(tag, "Error recording observation: ${e.message}", e)
        }
    }

    /**
     * Get success rate for a specific WiFi channel (all-time).
     * Returns: handshakes / attacks
     */
    suspend fun getChannelSuccessRate(channel: Int): Float {
        return try {
            val observations = repository.getObservationsByChannel(channel).first()
            if (observations.isEmpty()) return 0f

            // Capture yield: fraction of events observed on this channel that were
            // successful handshakes. (Was handshakes/attacks, which was ~always 0 —
            // capture observations record attacks_sent = 0, so the denominator was 0.)
            val totalHandshakes = observations.sumOf { it.handshakes_captured }
            (totalHandshakes.toFloat() / observations.size).coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(tag, "Error calculating success rate: ${e.message}", e)
            0f
        }
    }

    /**
     * Get best channels for current GPS location.
     * Clusters observations within radiusMeters and ranks by success rate.
     */
    suspend fun getBestChannelsForLocation(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 100f
    ): List<LocationLearningSummary> {
        return try {
            // Convert radius (meters) to lat/lon delta
            // 1 degree latitude = ~111 km = 111,000 meters
            val latDelta = radiusMeters / 111000.0
            val lonDelta = radiusMeters / (111000.0 * cos(latitude * PI / 180.0))

            // Filter all observations by bounding box in memory
            val observations = repository.getAllObservations().first()
                .filter {
                    it.latitude in (latitude - latDelta)..(latitude + latDelta) &&
                    it.longitude in (longitude - lonDelta)..(longitude + lonDelta)
                }

            if (observations.isEmpty()) return emptyList()

            // Group by SSID/location cluster
            observations
                .groupBy { it.ssid }
                .map { (ssid, obs) ->
                    // Find best channel for this SSID
                    val byChannel = obs.groupBy { it.channel }
                    val bestChannel = byChannel
                        .maxByOrNull { it.value.sumOf { o -> o.handshakes_captured } }
                        ?.key ?: 1

                    // Find best hour for this location
                    val byHour = obs.groupBy { it.hourOfDay }
                    val bestHour = byHour
                        .maxByOrNull { it.value.size }
                        ?.key ?: 12

                    // Capture yield per location cluster (handshakes / observations).
                    val totalHandshakes = obs.sumOf { it.handshakes_captured }
                    val successRate = if (obs.isNotEmpty()) {
                        totalHandshakes.toFloat() / obs.size
                    } else {
                        0f
                    }

                    LocationLearningSummary(
                        latitude = latitude,
                        longitude = longitude,
                        label = ssid.take(15),  // Use SSID as label
                        bestChannel = bestChannel,
                        bestTimeOfDay = bestHour,
                        observationCount = obs.size,
                        successRate = successRate.coerceIn(0f, 1f)
                    )
                }
                .sortedByDescending { it.successRate }
        } catch (e: Exception) {
            Log.e(tag, "Error getting location learning: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get best channel for a specific hour of day (0-23).
     * Useful for time-of-day based optimization.
     */
    suspend fun getBestChannelForTime(hour: Int): Int? {
        return try {
            val observations = repository.getObservationsByTimeOfDay(hour).first()
            if (observations.isEmpty()) return null

            observations
                .groupBy { it.channel }
                .maxByOrNull { (_, obs) -> obs.sumOf { it.handshakes_captured } }
                ?.key
        } catch (e: Exception) {
            Log.e(tag, "Error getting time-based channel: ${e.message}", e)
            null
        }
    }

    /**
     * Get time-of-day profiles (best channel per hour).
     * Returns map of hour (as string) to hourly stats.
     */
    suspend fun getTimeOfDayProfiles(): List<HourlyStats> {
        return try {
            val allObs = repository.getAllObservations().first()
            if (allObs.isEmpty()) return emptyList()

            (0..23)
                .map { hour ->
                    val hourObs = allObs.filter { it.hourOfDay == hour }
                    if (hourObs.isEmpty()) {
                        HourlyStats(
                            hour = hour,
                            bestChannel = 1,
                            observationCount = 0,
                            successRate = 0f,
                            intensity = 0f
                        )
                    } else {
                        // Find best channel for this hour
                        val bestChannel = hourObs
                            .groupBy { it.channel }
                            .maxByOrNull { it.value.sumOf { o -> o.handshakes_captured } }
                            ?.key ?: 1

                        // Calculate success rate
                        val totalAttacks = hourObs.sumOf { it.attacks_sent }
                        val totalHandshakes = hourObs.sumOf { it.handshakes_captured }
                        val successRate = if (totalAttacks > 0) {
                            totalHandshakes.toFloat() / totalAttacks
                        } else {
                            0f
                        }

                        HourlyStats(
                            hour = hour,
                            bestChannel = bestChannel,
                            observationCount = hourObs.size,
                            successRate = successRate.coerceIn(0f, 1f),
                            intensity = (successRate * hourObs.size / 100f).coerceIn(0f, 1f)
                        )
                    }
                }
        } catch (e: Exception) {
            Log.e(tag, "Error getting time profiles: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get all channels ranked by success rate (all-time).
     */
    suspend fun getChannelInsights(): List<ChannelInsight> {
        return try {
            val allObs = repository.getAllObservations().first()
            if (allObs.isEmpty()) return emptyList()

            val bestChannel = allObs
                .groupBy { it.channel }
                .maxByOrNull { (_, obs) ->
                    obs.sumOf { it.handshakes_captured }
                }
                ?.key

            allObs
                .groupBy { it.channel }
                .map { (channel, obs) ->
                    // Capture yield per channel (handshakes / observations). Non-zero
                    // whenever this channel has produced captures.
                    val totalHandshakes = obs.sumOf { it.handshakes_captured }
                    val successRate = if (obs.isNotEmpty()) {
                        totalHandshakes.toFloat() / obs.size
                    } else {
                        0f
                    }

                    ChannelInsight(
                        channel = channel,
                        successRate = successRate.coerceIn(0f, 1f),
                        observationCount = obs.size,
                        isBest = channel == bestChannel
                    )
                }
                .sortedByDescending { it.successRate }
        } catch (e: Exception) {
            Log.e(tag, "Error getting channel insights: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Channels ranked by capture yield for a given hour-of-day (± [windowHours]),
     * for time-aware steering. Empty if nothing has been observed in that window.
     */
    suspend fun getChannelsForHour(hour: Int, windowHours: Int = 1): List<ChannelInsight> {
        return try {
            val all = repository.getAllObservations().first()
            val inWindow = all.filter { o ->
                val d = ((o.hourOfDay - hour + 24) % 24)
                d <= windowHours || d >= 24 - windowHours
            }
            if (inWindow.isEmpty()) return emptyList()
            inWindow.groupBy { it.channel }
                .map { (ch, obs) ->
                    val hs = obs.sumOf { it.handshakes_captured }
                    ChannelInsight(
                        channel = ch,
                        successRate = (hs.toFloat() / obs.size).coerceIn(0f, 1f),
                        observationCount = obs.size,
                        isBest = false
                    )
                }
                .sortedWith(
                    compareByDescending<ChannelInsight> { it.successRate }
                        .thenByDescending { it.observationCount }
                )
        } catch (e: Exception) {
            Log.e(tag, "Error getting hourly channels: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get comprehensive learning statistics for UI display.
     */
    suspend fun getLearningStats(): LearningStats {
        return try {
            val count = repository.getObservationCount()
            val channels = getChannelInsights()
            val hourlyStats = getTimeOfDayProfiles()

            val bestChannel = channels.firstOrNull()?.channel
            val bestChannelSuccessRate = channels.firstOrNull()?.successRate ?: 0f

            LearningStats(
                totalObservations = count,
                bestChannel = bestChannel,
                bestChannelSuccessRate = bestChannelSuccessRate,
                channels = channels,
                locations = emptyList(),
                hourlyStats = hourlyStats,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(tag, "Error getting learning stats: ${e.message}", e)
            LearningStats(
                totalObservations = 0,
                bestChannel = null,
                bestChannelSuccessRate = 0f,
                channels = emptyList(),
                locations = emptyList(),
                hourlyStats = emptyList(),
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    /**
     * Clean up observations older than [daysToKeep] days.
     */
    suspend fun pruneOldObservations(daysToKeep: Int = 30) {
        try {
            val cutoff = System.currentTimeMillis() - daysToKeep * 24 * 60 * 60 * 1000L
            val all = repository.getAllObservations().first()
            val fresh = all.filter { it.timestamp >= cutoff }
            if (fresh.size < all.size) {
                repository.clearAll()
                fresh.forEach { repository.insertObservation(it) }
                Log.d(tag, "Pruned ${all.size - fresh.size} observations older than $daysToKeep days")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error pruning observations: ${e.message}", e)
        }
    }
}

