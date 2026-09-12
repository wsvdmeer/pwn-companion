package com.wsvdmeer.pwncompanion.crack

/**
 * The pure "may we crack right now, and with how many workers?" decision, split out of
 * [CrackEngine] so it's testable without a real battery. The Android battery reads (plugged flag,
 * capacity) stay in [CrackEngine]; this only decides. The load-bearing "plugged ≠ charging"
 * subtlety lives in how [CrackEngine] reads `plugged` — here we just trust the flag.
 */
object PowerPolicy {

    /**
     * Why cracking is blocked by the power policy right now, or null if it may run. [batteryPct] is a
     * lazy supplier so the (mildly costly) battery read only happens when the low-battery rule
     * actually needs it — matching the original short-circuit order.
     */
    fun blockReason(
        plugged: Boolean,
        chargerOnly: Boolean,
        lowBatteryStop: Boolean,
        lowPct: Int,
        batteryPct: () -> Int,
    ): String? {
        if (plugged) return null                       // on a charger → nothing to hold for
        if (chargerOnly) return "waiting for charger"
        if (lowBatteryStop) {
            val pct = batteryPct()
            if (pct in 0..lowPct) return "battery $pct% - paused"
        }
        return null
    }

    /**
     * Worker count, honouring the gentle knobs: cap at 2 in easy-CPU mode, else all-but-one while
     * plugged in and half on battery — clamped to 1..8.
     */
    fun workers(plugged: Boolean, gentleCpu: Boolean, cpus: Int): Int {
        if (gentleCpu) return 2.coerceIn(1, cpus)
        return if (plugged) (cpus - 1).coerceIn(1, 8) else (cpus / 2).coerceIn(1, 8)
    }
}
