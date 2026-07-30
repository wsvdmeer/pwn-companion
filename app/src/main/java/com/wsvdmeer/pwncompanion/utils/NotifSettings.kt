package com.wsvdmeer.pwncompanion.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which alert notifications are enabled — persisted, observed by the settings UI, and read by
 * [NotificationHelper] before it posts. The persistent foreground-service notices (network / crack
 * progress) are NOT covered here; they're required by Android while a service runs. Defaults on.
 */
object NotifSettings {
    private const val PREFS = "notif_settings"

    @Volatile private var loaded = false

    private val _onCatch = MutableStateFlow(true)     // 📡 handshake caught
    private val _onCracked = MutableStateFlow(true)   // 🔓 password cracked
    val onCatch: StateFlow<Boolean> = _onCatch.asStateFlow()
    val onCracked: StateFlow<Boolean> = _onCracked.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _onCatch.value = p.getBoolean("onCatch", true)
        _onCracked.value = p.getBoolean("onCracked", true)
        loaded = true
    }

    /** Read the flags directly (loads once) — used by [NotificationHelper]'s post gate. */
    fun catchEnabled(context: Context): Boolean { ensureLoaded(context); return _onCatch.value }
    fun crackedEnabled(context: Context): Boolean { ensureLoaded(context); return _onCracked.value }

    fun setOnCatch(context: Context, v: Boolean) = persist(context, "onCatch", _onCatch, v)
    fun setOnCracked(context: Context, v: Boolean) = persist(context, "onCracked", _onCracked, v)

    private fun persist(context: Context, key: String, flow: MutableStateFlow<Boolean>, v: Boolean) {
        flow.value = v
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, v).apply()
    }
}
