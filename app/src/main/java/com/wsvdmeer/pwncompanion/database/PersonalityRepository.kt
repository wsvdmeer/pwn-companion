package com.wsvdmeer.pwncompanion.database

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wsvdmeer.pwncompanion.ai.PersonalityStateEngine.PersonalityState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.personalityDataStore by preferencesDataStore(name = "personality_baseline")
private val BASELINE_KEY = stringPreferencesKey("learned_baseline_json")

/**
 * Persists the AI's learned personality baseline so the companion's disposition
 * survives app restarts and genuinely develops over its lifetime. Stores a single
 * serialized [PersonalityState] — the long-term "settled" trait vector.
 */
class PersonalityRepository(private val context: Context) {
    private val tag = "PersonalityRepository"
    private val json = Json { ignoreUnknownKeys = true }

    /** Load the persisted learned baseline, or null if the device has no history yet. */
    suspend fun loadBaseline(): PersonalityState? {
        return try {
            val raw = context.personalityDataStore.data.first()[BASELINE_KEY] ?: return null
            json.decodeFromString<PersonalityState>(raw)
        } catch (e: Exception) {
            Log.e(tag, "Failed to load personality baseline: ${e.message}")
            null
        }
    }

    /** Persist the current learned baseline. */
    suspend fun saveBaseline(state: PersonalityState) {
        try {
            val encoded = json.encodeToString(state)
            context.personalityDataStore.edit { it[BASELINE_KEY] = encoded }
        } catch (e: Exception) {
            Log.e(tag, "Failed to save personality baseline: ${e.message}")
        }
    }

}
