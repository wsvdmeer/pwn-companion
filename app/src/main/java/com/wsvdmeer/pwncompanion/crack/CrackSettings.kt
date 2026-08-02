package com.wsvdmeer.pwncompanion.crack

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-tunable "gentle knobs" for on-phone cracking — persisted, and observed by both the UI
 * (toggle chips) and [CrackEngine] (which reads them each tick). Defaults are the safe/gentle
 * choices so cracking never surprises you with a hot, drained phone.
 */
object CrackSettings {
    private const val PREFS = "crack_settings"
    /** Pause the crack when the battery drops below this (on battery only). */
    const val LOW_PCT = 15

    @Volatile private var loaded = false

    private val _gentleCpu = MutableStateFlow(true)            // cap workers at 2 (cooler/quieter)
    private val _chargerOnly = MutableStateFlow(true)          // only crack while plugged in
    private val _lowBatteryStop = MutableStateFlow(true)       // pause under LOW_PCT on battery
    private val _quickCrack = MutableStateFlow(false)          // try only the top-N (fast, may miss)
    private val _mangle = MutableStateFlow(false)              // apply word-mangling rules (slower, wider)
    val gentleCpu: StateFlow<Boolean> = _gentleCpu.asStateFlow()
    val chargerOnly: StateFlow<Boolean> = _chargerOnly.asStateFlow()
    val lowBatteryStop: StateFlow<Boolean> = _lowBatteryStop.asStateFlow()
    val quickCrack: StateFlow<Boolean> = _quickCrack.asStateFlow()
    val mangle: StateFlow<Boolean> = _mangle.asStateFlow()

    // Targeted candidate generators (Thomson/SpeedTouch, ESSID name guesses, …) the user turned OFF,
    // by generator id. Stored as the DISABLED set so a newly-added generator defaults ON.
    private val _disabledGenerators = MutableStateFlow<Set<String>>(emptySet())
    val disabledGenerators: StateFlow<Set<String>> = _disabledGenerators.asStateFlow()

    /** Load persisted values once (safe to call repeatedly). */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _gentleCpu.value = p.getBoolean("gentleCpu", true)
        _chargerOnly.value = p.getBoolean("chargerOnly", true)
        _lowBatteryStop.value = p.getBoolean("lowBatteryStop", true)
        _quickCrack.value = p.getBoolean("quickCrack", false)
        _mangle.value = p.getBoolean("mangle", false)
        _disabledGenerators.value =
            p.getString("disabledGens", "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        loaded = true
    }

    fun setGentleCpu(context: Context, v: Boolean) = persist(context, "gentleCpu", _gentleCpu, v)
    fun setChargerOnly(context: Context, v: Boolean) = persist(context, "chargerOnly", _chargerOnly, v)
    fun setLowBatteryStop(context: Context, v: Boolean) = persist(context, "lowBatteryStop", _lowBatteryStop, v)
    fun setQuickCrack(context: Context, v: Boolean) = persist(context, "quickCrack", _quickCrack, v)
    fun setMangle(context: Context, v: Boolean) = persist(context, "mangle", _mangle, v)

    /** Is a targeted generator (by id) enabled? Empty disabled-set → all on. */
    fun isGeneratorEnabled(context: Context, id: String): Boolean {
        ensureLoaded(context); return id !in _disabledGenerators.value
    }

    /** Enable/disable one generator by id. */
    fun setGeneratorEnabled(context: Context, id: String, enabled: Boolean) {
        ensureLoaded(context)
        val next = _disabledGenerators.value.toMutableSet().apply { if (enabled) remove(id) else add(id) }
        _disabledGenerators.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("disabledGens", next.joinToString(",")).apply()
    }

    private fun persist(context: Context, key: String, flow: MutableStateFlow<Boolean>, v: Boolean) {
        flow.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
    }
}
