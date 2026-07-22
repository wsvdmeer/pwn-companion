package com.wsvdmeer.pwncompanion.services

import android.content.Context
import android.util.Log

/**
 * NetworkService Singleton - Ensures only one instance exists across the entire app.
 * This is critical because:
 * 1. MainActivity needs access to MessageHandler for UI binding
 * 2. CompanionBackgroundService also needs NetworkService for starting/stopping
 * 3. They MUST be the same instance to share message flows
 */
object NetworkServiceSingleton {
    private val tag = "NetworkServiceSingleton"
    @Volatile
    private var instance: NetworkService? = null

    fun getInstance(context: Context): NetworkService {
        // Always retain the application context. A Service/Activity context passed
        // by whichever caller wins the race would otherwise be held for the whole
        // process lifetime and leak that component.
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: NetworkService(appContext).also {
                Log.d(tag, "NetworkService instance created")
                instance = it
            }
        }
    }

    fun getInstanceOrNull(): NetworkService? = instance

    fun cleanup() {
        instance?.cleanup()
        instance = null
        Log.d(tag, "NetworkService instance cleaned up")
    }
}

