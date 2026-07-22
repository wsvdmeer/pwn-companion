package com.wsvdmeer.pwncompanion.models

/**
 * Channel performance insights - success rate, observations, best times.
 */
data class ChannelInsight(
    val channel: Int,
    val successRate: Float,        // 0.0 to 1.0
    val observationCount: Int,
    val isBest: Boolean = false
)

/**
 * Location-based learning summary.
 */
data class LocationLearningSummary(
    val latitude: Double,
    val longitude: Double,
    val label: String,            // "Home", "Office", etc (auto or manual)
    val bestChannel: Int,
    val bestTimeOfDay: Int,       // Hour (0-23)
    val observationCount: Int,
    val successRate: Float
)

/**
 * Hourly statistics for time-of-day patterns.
 */
data class HourlyStats(
    val hour: Int,               // 0-23
    val bestChannel: Int,
    val observationCount: Int,
    val successRate: Float,
    val intensity: Float          // 0.0-1.0 for visualization
)

/**
 * Comprehensive learning statistics for dashboard display.
 */
data class LearningStats(
    val totalObservations: Int,
    val bestChannel: Int?,
    val bestChannelSuccessRate: Float,
    val channels: List<ChannelInsight>,
    val locations: List<LocationLearningSummary>,
    val hourlyStats: List<HourlyStats>,
    val lastUpdated: Long
) {
    /** Human label for the most active hour ("evenings (~20:00)"), or null if no data. */
    fun busiestHourLabel(): String? {
        val top = hourlyStats.maxByOrNull { it.intensity } ?: return null
        if (top.intensity <= 0f) return null
        val h = top.hour
        val part = when {
            h < 6  -> "late night"
            h < 12 -> "mornings"
            h < 18 -> "afternoons"
            else   -> "evenings"
        }
        return "$part (~%02d:00)".format(h)
    }
}

