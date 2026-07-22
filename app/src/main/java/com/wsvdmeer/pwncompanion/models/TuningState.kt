package com.wsvdmeer.pwncompanion.models

/**
 * Live snapshot of the phone-side personality tuner (re-implements jayofelony's removed
 * RL param-tuner). Rendered as bars in the `[ learning ]` section so the auto-tuning is
 * visible. Ranges match the plugin's clamps, so a bar shows where each knob sits in its
 * allowed span.
 */
data class TuningState(
    val minRssi: Int,      // dBm, range [-90, -55]
    val apTtl: Int,        // s,   range [30, 300]
    val staTtl: Int,       // s,   range [60, 600]
    val reconTime: Int,    // s,   range [10, 60]
    val hopRecon: Int,     // s,   range [2, 30]
)
