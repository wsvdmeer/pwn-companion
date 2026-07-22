package com.wsvdmeer.pwncompanion.models

/**
 * Personality State - Tracks Pwnagotchi's emotional state.
 * Affects scanning behavior, strategy decisions, and UI display.
 */
data class PersonalityState(
    val deviceId: String,
    val mood: Float,              // -1.0 (sad) to +1.0 (happy)
    val hunger: Float,             // 0.0 to 1.0 (drives scanning need)
    val tiredness: Float,          // 0.0 to 1.0 (triggers sleep mode)
    val boredom: Float,            // 0.0 to 1.0 (forces exploration)
    val timestamp: Long
) {
    /**
     * Get mood emoji representation.
     */
    fun getMoodEmoji(): String = when {
        mood > 0.7f -> "😊"   // Very happy
        mood > 0.3f -> "🙂"   // Happy
        mood > -0.3f -> "😐"  // Neutral
        mood > -0.7f -> "😕"  // Sad
        else -> "😢"           // Very sad
    }

    /**
     * Get overall status as percentage (0-100).
     * Higher is better (happy, not tired, not hungry).
     */
    fun getHealthPercentage(): Int {
        val moodScore = (mood + 1.0f) / 2.0f * 0.3f       // 30% of score
        val hungerScore = (1.0f - hunger) * 0.35f         // 35% of score
        val tiredScore = (1.0f - tiredness) * 0.35f       // 35% of score
        return ((moodScore + hungerScore + tiredScore) * 100).toInt()
    }
}

