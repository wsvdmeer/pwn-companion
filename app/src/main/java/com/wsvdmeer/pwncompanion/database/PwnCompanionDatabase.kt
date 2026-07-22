package com.wsvdmeer.pwncompanion.database

import android.content.Context

/**
 * PwnCompanion Database - DataStore-based persistent storage.
 * Stores WiFi observations for learning system.
 *
 * Replaces Room with DataStore (no annotation processing).
 */
object PwnCompanionDatabase {

    private var observationRepository: WifiObservationRepository? = null

    /**
     * Get or create repository instance (thread-safe singleton).
     */
    fun getRepository(context: Context): WifiObservationRepository {
        return observationRepository ?: synchronized(this) {
            observationRepository ?: WifiObservationRepository(context.applicationContext)
                .also { observationRepository = it }
        }
    }

    /**
     * Reset repository instance (for testing).
     */
    fun resetInstance() {
        observationRepository = null
    }

    /**
     * Get WiFi Observation DAO (for backward compatibility).
     * Returns a placeholder - use getRepository() instead.
     */
    @Deprecated("Use getRepository() instead for DataStore access")
    fun wifiObservationDao(): WifiObservationDao? = null
}

