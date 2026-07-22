package com.wsvdmeer.pwncompanion.services

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The channel-selection bandit, extracted as a pure, dependency-free unit so the steering
 * "protocol" can be reasoned about and unit-tested in isolation (no Android, no network).
 *
 * UCB1: each candidate channel scores as `normalisedExploit + C · √(ln(Σpulls) / (pulls+1))`.
 * The exploration term is large for under-sampled channels and shrinks as they're sampled,
 * so the bandit keeps trying channels it knows little about instead of tunnel-visioning on a
 * self-reinforcing top-N. After each pick the pulls DECAY (recency) then the chosen channels
 * are credited — a channel that goes cold has its exploitation forgotten and gets re-explored.
 *
 * State (pulls + totalPulls) lives here; [SyncScheduler] owns one instance across cycles.
 */
class ChannelBandit(
    private val exploreC: Double = 0.6,
    private val pullDecay: Double = 0.97,   // ~15-20 min exploration half-life at one call/45s
) {
    private val pulls = HashMap<Int, Double>()
    private var totalPulls = 0.0

    /**
     * Score every [candidates] channel, return the top [count] by UCB, then update memory
     * (decay all pulls, credit the chosen). [exploit] maps a channel → its raw exploit value
     * (handshakes + live clients + yield/here/now bonuses); it's normalised internally.
     */
    fun select(candidates: Collection<Int>, count: Int, exploit: (Int) -> Double): List<Int> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val maxExploit = candidates.maxOf { exploit(it) }.coerceAtLeast(1e-6)
        val logTot = ln(totalPulls + 2.0)
        fun ucb(ch: Int): Double =
            exploit(ch) / maxExploit + exploreC * sqrt(logTot / ((pulls[ch] ?: 0.0) + 1.0))

        val top = candidates.sortedByDescending { ucb(it) }.take(count)
        if (top.isEmpty()) return top

        // Decay everything (recency), then credit the chosen — order matters and matches the
        // original in-line implementation exactly.
        pulls.replaceAll { _, v -> v * pullDecay }
        totalPulls *= pullDecay
        top.forEach { pulls[it] = (pulls[it] ?: 0.0) + 1.0; totalPulls += 1.0 }
        return top
    }

    /** Read-only view of accumulated pulls — for tests / debugging. */
    fun pullsSnapshot(): Map<Int, Double> = pulls.toMap()
}

/**
 * Motion decision from AP "churn" (new BSSIDs/min), used only when GPS speed is unavailable
 * (indoors / no fix). Hysteresis: a stationary pwnagotchi steadily discovers new BSSIDs, so a
 * low threshold false-trips "moving" and drops steering into fast-hop 1/6/11 mode. Require a
 * real burst to ENTER moving, a low count to EXIT, and hold the prior state in between — so it
 * doesn't flicker (this is the fix for "hops too fast" indoors).
 */
object MotionHeuristic {
    const val ENTER = 12   // ≥ this many new APs/min → genuinely on the move
    const val EXIT = 4     // ≤ this → stationary

    fun decideByChurn(newApsLastMinute: Int, wasMoving: Boolean): Boolean = when {
        newApsLastMinute >= ENTER -> true
        newApsLastMinute <= EXIT  -> false
        else                      -> wasMoving   // in the dead-band → hold (no flicker)
    }

    // ── GPS-speed motion (primary signal when a fresh fix is available) ──
    const val SPEED_ENTER = 1.4   // m/s (~5 km/h) → walking or faster
    const val SPEED_EXIT = 0.6    // m/s → basically stationary

    /** Speed (m/s) → moving?, with a hysteresis dead-band so a jittery reading can't flicker. */
    fun decideBySpeed(speedMps: Double, wasMoving: Boolean): Boolean = when {
        speedMps > SPEED_ENTER -> true
        speedMps < SPEED_EXIT  -> false
        else                   -> wasMoving
    }

    /**
     * Speed (m/s) inferred from a displacement between two fixes — but only trusted when the
     * displacement clears the fixes' error circle, so GPS jitter *inside* the accuracy radius
     * reads as stationary (0), not phantom motion. Returns null when there's no usable interval.
     * Used only as the fallback when the OS didn't report a hardware speed.
     */
    fun speedFromDisplacement(meters: Double, dtSec: Double, accuracyM: Double): Double? {
        if (dtSec < 1.0) return null
        // Combined 1σ error of two independent fixes ≈ √2·accuracy; require the move to clear it.
        if (meters < 1.5 * accuracyM) return 0.0
        return meters / dtSec
    }
}
