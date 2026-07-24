package com.wsvdmeer.pwncompanion.ai

import com.wsvdmeer.pwncompanion.models.AutotuneChannelStat
import com.wsvdmeer.pwncompanion.models.DeviceTelemetry
import com.wsvdmeer.pwncompanion.models.LearningStats

/**
 * A deauth-first "where to hunt now" readout, computed entirely on the phone from real signals.
 *
 * It is NOT a second steering engine — the [ChannelBandit] decides which channels the device
 * actually recons (and the advisor names that same channel, so the two never disagree). The
 * advisor's own job is to (a) surface deauth-mission problems the operator can act on (dead
 * antenna, no clients to deauth, running hot, a spot gone dry), and (b) hand the pet's voice
 * pre-chewed facts to phrase in-character.
 */
data class HuntAdvice(
    /** Deterministic one-liner shown in the [ advisor ] section (always correct). */
    val headline: String,
    /** The channel recon is parked on (the bandit's pick), if any. */
    val channel: Int?,
    /** Deauth-mission problems, most-urgent first (rendered in the error color). */
    val warnings: List<String>,
    /**
     * Stable identity of the current top warning (category + coarse bucket). Changes
     * only when the situation meaningfully changes, so callers can proactively voice a
     * new alert without spamming on every telemetry tick. Null when all clear.
     */
    val alertKey: String?,
    /** Pre-chewed facts for the pet's voice to phrase in-character. */
    val voiceFacts: String,
)

object HuntAdvisor {

    /**
     * Build advice from the device's own per-channel capture stats (autotune — the most
     * reliable "where handshakes land" signal), live telemetry (clients = deauth targets,
     * blind/thermal = capture blockers) and the app's learning DB (fallback).
     *
     * Returns null only when there is genuinely nothing to say (no data at all).
     */
    fun recommend(
        autotune: Map<Int, AutotuneChannelStat>,
        telemetry: DeviceTelemetry?,
        learning: LearningStats?,
        isAutoMode: Boolean,
        minutesSinceLastCatch: Long?,
        steeredChannel: Int? = null,   // the bandit's current top pick — one source of truth
    ): HuntAdvice? {
        val warnings = mutableListOf<String>()
        var alertKey: String? = null

        // ── deauth-mission blockers (ordered by urgency) ────────────────────────
        val blind = telemetry?.blindForEpochs ?: 0
        val temp = telemetry?.temperature
        val aps = telemetry?.numAps
        val sta = telemetry?.numSta

        if (blind >= 3) {
            warnings += "blind ${blind}e — can't see the air (check antenna / monitor mode)"
            alertKey = "blind:${bucket(blind, 3, 10, 30)}"
        }
        if (temp != null && temp >= 75) {
            warnings += "running hot ${temp.toInt()}°C — may throttle"
            if (alertKey == null) alertKey = "hot:${bucket(temp.toInt(), 75, 80, 85)}"
        }
        if (isAutoMode && aps != null && aps > 0 && sta != null && sta == 0) {
            warnings += "${aps} APs but 0 clients — nothing to deauth here, move"
            if (alertKey == null) alertKey = "noclients"
        }
        if (isAutoMode && sta != null && sta > 0 && (minutesSinceLastCatch ?: 0) >= 30) {
            warnings += "dry ${minutesSinceLastCatch}m with clients around — try another spot/channel"
            if (alertKey == null) alertKey = "dry:${bucket(minutesSinceLastCatch!!.toInt(), 30, 60, 120)}"
        }

        // ── channel pick: name the bandit's actual steer (one source of truth), then fall
        // back to device autotune / learning DB when it hasn't picked yet ────────────────
        val ranked = autotune.entries
            .filter { it.key in 1..165 && it.value.handshakes > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<Int, AutotuneChannelStat>> { it.value.handshakes }
                    .thenByDescending { it.value.sta }
            )
        val topAuto = ranked.firstOrNull()

        val headline: String
        val channel: Int?
        when {
            !isAutoMode -> {
                headline = "manual mode — not hunting. flip to auto to catch."
                channel = null
            }
            steeredChannel != null -> {
                // Mirror the bandit so [ advisor ] and [ steering ] never disagree; annotate
                // with this channel's own stats when we have them.
                val c = steeredChannel
                val v = autotune[c]
                channel = c
                headline = buildString {
                    append("park on ch$c")
                    if (v != null && v.sta > 0) append(" · ${v.sta} client${if (v.sta == 1) "" else "s"}")
                    if (v != null && v.handshakes > 0) append(" · ${v.handshakes} caught here")
                }
            }
            topAuto != null -> {
                val c = topAuto.key
                val v = topAuto.value
                channel = c
                headline = buildString {
                    append("park on ch$c")
                    if (v.sta > 0) append(" · ${v.sta} client${if (v.sta == 1) "" else "s"}")
                    append(" · ${v.handshakes} caught here")
                }
            }
            learning?.bestChannel != null -> {
                val c = learning.bestChannel!!
                channel = c
                headline = "try ch$c · ${(learning.bestChannelSuccessRate * 100).toInt()}% yield so far"
            }
            (aps ?: 0) > 0 -> {
                channel = null
                headline = "scoping ${aps} AP${if (aps == 1) "" else "s"}… no proven channel yet"
            }
            else -> {
                channel = null
                headline = "quiet air — nothing worth deauthing in range yet"
            }
        }

        // ── facts for the pet's voice (compact, pre-ranked; it only phrases these) ──────
        val facts = buildString {
            appendLine("- recommendation: $headline")
            if (channel != null) appendLine("- best channel: ch$channel")
            if (topAuto != null) appendLine("- that channel: ${topAuto.value.handshakes} handshakes, ${topAuto.value.sta} clients, ${topAuto.value.deauths} deauths sent")
            if (sta != null) appendLine("- clients in range now: $sta (these are the deauth targets)")
            if (aps != null) appendLine("- APs in range now: $aps")
            if (warnings.isNotEmpty()) appendLine("- problem: ${warnings.first()}")
        }.trim()

        return HuntAdvice(headline, channel, warnings, alertKey, voiceFacts = facts)
    }

    /** Coarse bucket so alertKey only changes on meaningful escalation, not every tick. */
    private fun bucket(v: Int, vararg thresholds: Int): Int =
        thresholds.count { v >= it }
}
