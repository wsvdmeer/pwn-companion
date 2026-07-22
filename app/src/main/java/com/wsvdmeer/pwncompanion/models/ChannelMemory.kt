package com.wsvdmeer.pwncompanion.models

/**
 * Channel Memory - Aggregated channel performance data for learning.
 * Used internally by WifiMemoryService for calculations.
 */
data class ChannelMemory(
    val channel: Int,
    val success_rate: Float,        // handshakes / attacks
    val observation_count: Int,
    val last_seen: Long,
    val best_hour: Int?             // Hour (0-23) when most successful
)

