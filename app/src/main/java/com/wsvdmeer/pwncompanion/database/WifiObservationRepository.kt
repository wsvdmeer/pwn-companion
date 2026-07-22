package com.wsvdmeer.pwncompanion.database

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "wifi_observations")
private val OBSERVATIONS_KEY = stringPreferencesKey("observations_json")

/**
 * WiFi Observation Repository - DataStore-backed persistent storage.
 * Observations survive app restarts, limited to last 2000 entries to cap storage.
 */
class WifiObservationRepository(private val context: Context) {
    private val tag = "WifiObservationRepository"

    private val json = Json { ignoreUnknownKeys = true }

    // In-memory cache populated from DataStore on first access. The cache is a
    // plain ArrayList, so every read/write must hold [cacheMutex] — insert,
    // clear and prune run from different coroutine scopes concurrently and
    // would otherwise throw ConcurrentModificationException / lose writes.
    private val cacheMutex = Mutex()
    private var cache: MutableList<WifiObservation>? = null

    // Caller must hold [cacheMutex]. Mutex is non-reentrant, so getCache()/persist()
    // never lock themselves — the public methods own the lock.
    private suspend fun getCache(): MutableList<WifiObservation> {
        if (cache != null) return cache!!
        return try {
            val raw = context.dataStore.data.first()[OBSERVATIONS_KEY]
            val loaded: MutableList<WifiObservation> = if (raw != null) {
                json.decodeFromString<List<WifiObservation>>(raw).toMutableList()
            } else {
                mutableListOf()
            }
            Log.d(tag, "Loaded ${loaded.size} observations from DataStore")
            cache = loaded
            loaded
        } catch (e: Exception) {
            Log.e(tag, "Failed to load observations, starting fresh: ${e.message}")
            mutableListOf<WifiObservation>().also { cache = it }
        }
    }

    private suspend fun persist() {
        try {
            val current = cache ?: return
            val encoded = json.encodeToString(current)
            context.dataStore.edit { prefs -> prefs[OBSERVATIONS_KEY] = encoded }
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist observations: ${e.message}")
        }
    }

    fun getAllObservations(): Flow<List<WifiObservation>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[OBSERVATIONS_KEY] ?: return@map emptyList()
            try { json.decodeFromString<List<WifiObservation>>(raw) }
            catch (e: Exception) { emptyList() }
        }

    suspend fun insertObservation(observation: WifiObservation) {
        try {
            cacheMutex.withLock {
                val c = getCache()
                c.add(observation)
                // Cap at 2000 entries — drop oldest
                if (c.size > 2000) {
                    val excess = c.size - 2000
                    repeat(excess) { c.removeAt(0) }
                }
                persist()
                Log.d(tag, "Saved observation: ${observation.ssid} CH${observation.channel} (total=${c.size})")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error inserting observation: ${e.message}", e)
        }
    }

    fun getObservationsByBssid(bssid: String): Flow<List<WifiObservation>> =
        getAllObservations().map { it.filter { o -> o.bssid == bssid } }

    fun getObservationsByChannel(channel: Int): Flow<List<WifiObservation>> =
        getAllObservations().map { it.filter { o -> o.channel == channel } }

    fun getObservationsByTimeOfDay(hour: Int): Flow<List<WifiObservation>> =
        getAllObservations().map { it.filter { o -> o.hourOfDay == hour } }

    suspend fun clearAll() {
        try {
            cacheMutex.withLock {
                getCache().clear()
                persist()
                Log.d(tag, "All observations cleared")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error clearing observations: ${e.message}", e)
        }
    }

    /**
     * Total observation count. Suspending so it can warm the cache from
     * DataStore — the old non-suspend version returned 0 until some other
     * call happened to load the cache, making the UI show "0 observations".
     */
    suspend fun getObservationCount(): Int = cacheMutex.withLock { getCache().size }

    suspend fun getChannelStatistics(): Map<Int, Float> = cacheMutex.withLock {
        getCache()
            .groupBy { it.channel }
            .mapValues { (_, obs) ->
                // Capture yield: handshakes / observations on this channel. (Averaging
                // per-observation getSuccessRate() was meaningless — each observation is
                // either 1/0 or 0/1, so the mean was ~always 0.)
                if (obs.isNotEmpty())
                    (obs.sumOf { it.handshakes_captured }.toFloat() / obs.size).coerceIn(0f, 1f)
                else 0f
            }
    }
}
