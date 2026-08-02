package com.wsvdmeer.pwncompanion.utils

import android.content.Context
import com.wsvdmeer.pwncompanion.ai.Franchise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice preferences — which film-world "franchises" the pet is allowed to speak in. A multi-select
 * pool: check all, or check just the ones you want. The pet rotates among the enabled set on a mood
 * flip; enable exactly one and it's effectively pinned to that world.
 *
 * Persisted as a CSV of [Franchise.name]. Absent pref (first run) = ALL enabled. Read by
 * [com.wsvdmeer.pwncompanion.ai.PwnagotchiViewModel]; observed by the Settings checkboxes.
 */
object VoiceSettings {
    private const val PREFS = "voice_settings"
    private const val KEY = "enabled_franchises"

    @Volatile private var loaded = false

    /** Explicit set of enabled [Franchise.name]s. Defaults to all on first run. */
    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val csv = prefs(context).getString(KEY, null)   // null = never set → default all
        _enabled.value =
            if (csv == null) Franchise.entries.map { it.name }.toSet()
            else csv.split(",").filter { it.isNotBlank() }.toSet()
        loaded = true
    }

    /** Franchises currently in rotation. If the stored set is empty, falls back to ALL so the pet is
     *  never left mute — but the checkboxes still reflect the (empty) stored set. */
    fun activePool(context: Context): List<Franchise> {
        ensureLoaded(context)
        val en = _enabled.value
        val pool = Franchise.entries.filter { it.name in en }
        return pool.ifEmpty { Franchise.entries }
    }

    fun isEnabled(context: Context, f: Franchise): Boolean {
        ensureLoaded(context)
        return f.name in _enabled.value
    }

    /** Toggle one franchise in/out of the pool. */
    fun setEnabled(context: Context, f: Franchise, on: Boolean) {
        ensureLoaded(context)
        val cur = _enabled.value.toMutableSet()
        if (on) cur.add(f.name) else cur.remove(f.name)
        persist(context, cur)
    }

    /** Check-all / uncheck-all. */
    fun setAll(context: Context, on: Boolean) {
        persist(context, if (on) Franchise.entries.map { it.name }.toSet() else emptySet())
    }

    private fun persist(context: Context, set: Set<String>) {
        _enabled.value = set
        prefs(context).edit().putString(KEY, set.joinToString(",")).apply()
    }
}
