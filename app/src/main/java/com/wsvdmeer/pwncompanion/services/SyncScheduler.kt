package com.wsvdmeer.pwncompanion.services

import android.util.Log
import com.wsvdmeer.pwncompanion.protocol.OutgoingMessageQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sync Scheduler - Orchestrates periodic synchronization of strategies and learning data.
 * Maintains multiple independent sync loops for different data types.
 * M3-compliant with state tracking for UI feedback.
 */
class SyncScheduler(
    private val outgoingQueue: OutgoingMessageQueue,
    private val memoryService: WifiMemoryService,
    private val strategyEngine: StrategyDecisionEngine,
    // Live set of currently-connected device session IDs. Outgoing messages route
    // by session UUID, so we must target the real connected ids — not a fixed name.
    private val connectedDeviceIds: () -> Collection<String> = { emptyList() },
    // Reports the channels we just steered the device toward, for UI feedback.
    private val onSteer: (List<Int>) -> Unit = {},
    // Current (lat, lon) if a GPS fix is available — enables location-aware steering.
    private val currentLocation: () -> Pair<Double, Double>? = { null },
    // The device's OWN per-channel capture stats (from autotune_stats). This is the
    // most reliable "where handshakes actually land" signal — it's attributed by the
    // pwnagotchi itself and doesn't depend on the app having logged events while
    // connected. Keyed by channel number. Empty when no autotune data has arrived.
    private val autotuneStats: () -> Map<Int, com.wsvdmeer.pwncompanion.models.AutotuneChannelStat> = { emptyMap() },
    // The channels the device's monitor interface actually supports (reg-domain aware,
    // reported by the plugin). This is the authoritative candidate universe for steering,
    // so a dual-band adapter's 5 GHz channels are discoverable instead of a hardcoded 2.4
    // list. Empty when unknown (older plugin / before first report) → 2.4 GHz floor fallback.
    private val supportedChannels: () -> Set<Int> = { emptySet() },
    // Whether the device is actively hunting (AUTO). In MANUAL the device isn't
    // scanning, so steering its recon is pointless — we skip it entirely (no command,
    // no log spam) until the user goes back to AUTO.
    private val isAutoMode: () -> Boolean = { true },
    // Channel of the AP seen often but never captured — steering gives it a bonus so
    // recon parks there to finally grab it. Null when there's no standout target.
    private val untappedChannel: () -> Int? = { null },
    // True when the phone is moving. Pinning a learned channel set only helps while
    // stationary; on the move the environment churns, so we hop the wide band instead.
    private val isMoving: () -> Boolean = { false },
    // Reward inputs for the personality tuner (re-implementing jayofelony's removed RL):
    // lifetime capture count (for a capture-rate signal) and the device's own epoch reward.
    private val totalCaptures: () -> Int = { 0 },
    private val deviceReward: () -> Float? = { null },
    // Reports the current auto-tuned personality params (as numbers) for the UI to visualise.
    private val onTune: (com.wsvdmeer.pwncompanion.models.TuningState) -> Unit = {},
) {

    private val tag = "SyncScheduler"

    // Don't steer the device until we've actually learned something meaningful.
    private val minObservationsToSteer = 12

    // Idle back-off: don't re-send an unchanged channel set every 45 s. Resend only
    // when the set changes or this keepalive elapses (saves redundant commands/battery).
    private val STEER_KEEPALIVE_MS = 5 * 60_000L
    private var lastSentCsv: String? = null

    // Dwell steering: recon seconds to request when moving vs stationary. Sit longer
    // when still (collect handshakes on good channels); hop faster when moving.
    private val DWELL_MOVING = 15
    private val DWELL_STILL = 45
    private var lastReconTime: Int? = null

    // ── Explore/exploit bandit (UCB1) for channel selection ───────────────────
    // Exploit the productive channels, but keep sampling under-explored ones so we don't
    // tunnel-vision. The algorithm lives in [ChannelBandit] (pure + unit-tested); this holds
    // one instance so its pull memory persists across cycles.
    private val bandit = ChannelBandit()

    // Round-robin cursor for the reserved exploration slot — walks the full supported
    // spectrum so 5 GHz keeps getting revisited even when 2.4 dominates the exploit score.
    private var exploreIdx = 0

    // ── Personality tuner (re-implements jayofelony's removed RL param-tuner) ──
    // Context policy: ap_ttl/sta_ttl/hop set from motion. Feedback hill-climb: min_rssi
    // nudged every RSSI_WINDOW toward whatever raises the capture rate. Per-key idle
    // back-off so unchanged values aren't re-sent.
    private val lastParam = HashMap<String, Int>()
    private var rssiBaseline = -75          // last accepted min_rssi
    private var rssiPending = -75           // value currently under test this window
    private var rssiBaselineScore: Double? = null
    private var rssiDir = -5                // start by trying LOWER (reach weaker APs)
    private var rssiCapturesAtStart = -1
    private var rssiWindowStartMs = 0L
    private val RSSI_WINDOW_MS = 10 * 60_000L

    // Per-channel floor before a channel's success rate is trusted for steering.
    private val MIN_CHANNEL_OBS = 3

    // Sync state tracking (for UI indicators)
    var lastStrategySync: Long = 0
        private set
    var isSyncing: Boolean = false
        private set
    var lastError: String? = null
        private set

    /**
     * Start all periodic sync loops.
     * Must be called with viewModelScope or similar long-lived scope.
     */
    fun startPeriodicSync(
        deviceId: String,
        coroutineScope: CoroutineScope
    ) {
        Log.i(tag, "Starting periodic sync for device: $deviceId")

        // Strategy sync: every 45 seconds (channel steering + param tuning). The old
        // location-learning (60 min) and time-of-day (7 day) loops were never implemented —
        // they only stamped a timestamp — so they've been removed rather than waking the
        // device for nothing. Location/time intel is already folded into the steering scorer.
        coroutineScope.launch {
            while (isActive) {
                try {
                    delay(45_000)  // 45 seconds
                    sendStrategyCommand(deviceId)
                } catch (e: Exception) {
                    Log.e(tag, "Error in strategy sync loop: ${e.message}", e)
                }
            }
        }

        Log.i(tag, "Strategy sync loop started")
    }

    /**
     * Send strategy command to device (45s interval).
     * High frequency for real-time optimization.
     */
    private suspend fun sendStrategyCommand(deviceId: String) {
        try {
            // In MANUAL the device isn't scanning — don't steer (and don't log) until AUTO.
            if (!isAutoMode()) return

            isSyncing = true
            lastError = null

            val targets = connectedDeviceIds()
            if (targets.isEmpty()) return  // nothing connected; nothing to steer

            val moving = isMoving()

            // ── Dwell steering: how long to linger per recon cycle ────────────────
            // Sit longer when stationary (wait out handshakes on the good channels),
            // hop faster when moving. Sent only on change (a rare motion transition).
            setDwell(targets, if (moving) DWELL_MOVING else DWELL_STILL)

            // ── Personality tuner: re-tune the params jayofelony's removed RL used to ──
            tunePersonality(targets, moving)

            // ── Motion-aware channels: pin only while stationary ──────────────────
            // On the move the environment churns, so a learned pin is already stale —
            // hop the wide 2.4GHz band (1/6/11, where APs cluster) and keep discovering.
            // This complements the device's own auto-timing instead of caging it.
            if (moving) {
                steer(targets, listOf(1, 6, 11), "moving·wide")
                return
            }

            // ── Device signals (best-effort, NOT preconditions) ───────────────────
            // autotune = the plugin's per-channel handshake/client tally. On modern
            // pwnagotchi (>= 2.9.5.5, "strategy" core) epoch_data carries no channel, so
            // this stays empty — treat it as a bonus when present, never a gate.
            val auto = autotuneStats()
            val hasAuto = auto.values.any { it.handshakes > 0 || it.sta > 0 }

            val stats = memoryService.getLearningStats()
            val haveLearning = stats.totalObservations >= minObservationsToSteer

            // Candidate universe: the channels the DEVICE actually supports (authoritative,
            // reg-domain aware) so dual-band adapters' 5 GHz channels are discoverable — not
            // a hardcoded 2.4 GHz list. Fall back to the 2.4 floor until the device reports.
            val supported = supportedChannels().filter { it in 1..165 }.toSet()
            val discoveryBase = supported.ifEmpty { (1..11).toSet() }

            // Cold start: with no learned yield AND no autotune (the norm on new pwnagotchi,
            // where autotune never populates), don't sit idle — fall through to a pure-
            // exploration steer across the supported channels so recon covers both bands from
            // cycle one and the learning DB starts filling. Only bail if there's genuinely
            // nothing to explore.
            if (!haveLearning && !hasAuto && discoveryBase.isEmpty()) {
                Log.d(tag, "no learning, no autotune, no channel list — not steering yet")
                return
            }

            // Signals that give a channel a "productive here / now" bonus. Time-of-day:
            // channels that yield at this hour; location: channels that yield near the
            // current GPS fix; all-time yield: proven success rate (floored so a single
            // lucky sample can't dominate). Only trust the learning DB once warmed up.
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val hourly = if (haveLearning) memoryService.getChannelsForHour(hour)
                .filter { it.observationCount >= MIN_CHANNEL_OBS && it.successRate > 0f }
                .map { it.channel }.toSet() else emptySet()
            val nearby = if (haveLearning) currentLocation()?.let { (lat, lon) ->
                memoryService.getBestChannelsForLocation(lat, lon).map { it.bestChannel }
            }?.toSet() ?: emptySet() else emptySet()
            val yieldByCh = if (haveLearning) stats.channels
                .filter { it.channel in 1..165 && it.observationCount >= MIN_CHANNEL_OBS && it.successRate > 0f }
                .associate { it.channel to it.successRate } else emptyMap()
            val untap = untappedChannel()?.takeIf { it in 1..165 }

            // ── Exploit value: weighted blend across all signals ──────────────────
            // A channel strong across several signals beats one strong in a single one.
            // Handshakes + live clients dominate; yield / here / now add bonuses; the
            // untapped target gets a chase bonus.
            val maxHs = (auto.values.maxOfOrNull { it.handshakes } ?: 0).coerceAtLeast(1)
            val maxSta = (auto.values.maxOfOrNull { it.sta } ?: 0).coerceAtLeast(1)
            fun exploit(ch: Int): Double {
                var s = 0.0
                auto[ch]?.let { a ->
                    s += 1.0 * (a.handshakes.toDouble() / maxHs)   // proven captures here
                    s += 1.0 * (a.sta.toDouble() / maxSta)         // live clients = deauth targets NOW
                }
                yieldByCh[ch]?.let { s += 0.8 * it }               // all-time productivity (0..1)
                if (ch in nearby) s += 0.4                          // productive near here
                if (ch in hourly) s += 0.3                          // productive this hour
                if (ch == untap)  s += 0.6                          // chase the one that keeps escaping
                return s
            }

            // Candidates: the supported-channel discovery base plus everything we know about
            // (learned yield / here / now / untapped), intersected with what the device can do.
            val candidates = buildSet {
                addAll(discoveryBase); addAll(auto.keys); addAll(yieldByCh.keys); addAll(hourly); addAll(nearby); untap?.let { add(it) }
            }.filter { it in 1..165 && (supported.isEmpty() || it in supported) }

            // Two EXPLOIT slots via UCB1 (goes where the clients/handshakes are — usually 2.4,
            // since that's where real per-channel yield lives now that ground truth is restored).
            val exploitTop = bandit.select(candidates, 2) { exploit(it) }

            // One RESERVED EXPLORE slot, rotating through the band the exploit slots are NOT
            // covering. Clients (hence exploit) live on 2.4, so this keeps the 3rd slot on 5 GHz
            // — hunting 5 GHz EVERY cycle instead of only after walking all of 2.4 first. Breaks
            // the chicken-and-egg (can't earn 5 GHz yield without ever hunting 5 GHz); a yielding
            // 5 GHz channel still also wins exploit slots. Falls back to the whole spectrum if the
            // device is single-band.
            val sortedCand = candidates.sorted()
            val band24 = sortedCand.filter { it <= 14 }
            val band5 = sortedCand.filter { it > 14 }
            val exploit5 = exploitTop.count { it > 14 }
            val exploit24 = exploitTop.size - exploit5
            val pool = when {
                exploit24 >= exploit5 && band5.isNotEmpty() -> band5   // exploit leans 2.4 → explore 5 GHz
                band24.isNotEmpty() -> band24                          // exploit leans 5 GHz → explore 2.4
                else -> sortedCand
            }
            var exploreCh: Int? = null
            if (pool.isNotEmpty()) {
                for (i in pool.indices) {
                    val c = pool[(exploreIdx + i) % pool.size]
                    if (c !in exploitTop) {
                        exploreCh = c
                        exploreIdx = (exploreIdx + i + 1) % pool.size
                        break
                    }
                }
            }
            val topChannels = (exploitTop + listOfNotNull(exploreCh)).distinct()
            if (topChannels.isEmpty()) return

            // Per-cycle trace so behaviour is observable live in AUTO (adb logcat -s SyncScheduler:D):
            // motion state, candidate count, and the chosen channels with their exploit values.
            Log.d(tag, "bandit: moving=$moving cand=${candidates.size} → " +
                topChannels.joinToString(" ") { "ch$it(${"%.2f".format(exploit(it))})" })

            val why = buildString {
                if (hasAuto) append("dev·")
                if (untap != null && untap in topChannels) append("chase·")
                if (nearby.isNotEmpty()) append("here·")
                if (hourly.isNotEmpty()) append("@${hour}h·")
                append("bandit")
            }
            steer(targets, topChannels, why)

        } catch (e: Exception) {
            Log.e(tag, "Error sending strategy: ${e.message}", e)
            lastError = "Strategy sync failed: ${e.message?.take(50)}"
        } finally {
            isSyncing = false
        }
    }

    /**
     * Push a channel-priority nudge, with idle back-off: skip re-sending an unchanged
     * set unless the keepalive interval has elapsed, so a parked device isn't spammed
     * the same command every 45 s.
     */
    /** Request a recon dwell time (seconds) — only when it changes, so we don't spam. */
    private fun setDwell(targets: Collection<String>, seconds: Int) {
        if (seconds == lastReconTime) return
        targets.forEach { id -> outgoingQueue.queueCommand(id, "set_recon_time", seconds.toString()) }
        lastReconTime = seconds
        Log.i(tag, "Dwell → recon_time ${seconds}s sent to ${targets.size} device(s)")
    }

    /** Send one personality param (`key:value`) — only when it changes (idle back-off). */
    private fun setParam(targets: Collection<String>, key: String, value: Int): Boolean {
        if (lastParam[key] == value) return false
        targets.forEach { id -> outgoingQueue.queueCommand(id, "set_param", "$key:$value") }
        lastParam[key] = value
        return true
    }

    /**
     * Re-implements the param-tuning the removed RL did. Context policy sets the TTLs +
     * hop timing from motion; a slow feedback hill-climb tunes min_rssi toward whatever
     * raises the capture rate. Emits `set_param` commands (idle-backed-off) + a UI readout.
     */
    private fun tunePersonality(targets: Collection<String>, moving: Boolean) {
        // Context policy: moving → forget fast + hop fast; stationary → remember + dwell.
        val apTtl = if (moving) 45 else 180
        val staTtl = if (moving) 120 else 300
        val hop = if (moving) 4 else 10
        setParam(targets, "ap_ttl", apTtl)
        setParam(targets, "sta_ttl", staTtl)
        setParam(targets, "hop_recon_time", hop)

        hillClimbRssi(targets)

        val recon = if (moving) DWELL_MOVING else DWELL_STILL
        onTune(
            com.wsvdmeer.pwncompanion.models.TuningState(
                minRssi = rssiPending, apTtl = apTtl, staTtl = staTtl,
                reconTime = recon, hopRecon = hop,
            )
        )
    }

    /**
     * Feedback hill-climb for min_rssi: each ~10-min window, score the value under test
     * (captures gained + a little device reward); keep it if it's ≥ the accepted
     * baseline (and keep stepping that way), reverse on a drop. Holds when there's no
     * signal (a quiet spell) so it doesn't wander. Clamped to [-90, -55] dBm.
     */
    private fun hillClimbRssi(targets: Collection<String>) {
        val now = System.currentTimeMillis()
        setParam(targets, "min_rssi", rssiPending)   // ensure the device uses the value we're testing
        if (rssiCapturesAtStart < 0) {               // first window — establish a baseline
            rssiCapturesAtStart = totalCaptures(); rssiWindowStartMs = now; return
        }
        if (now - rssiWindowStartMs < RSSI_WINDOW_MS) return

        val gained = (totalCaptures() - rssiCapturesAtStart).coerceAtLeast(0)
        val reward = deviceReward() ?: 0f
        // No signal this window → hold the value, don't wander on noise.
        if (gained == 0 && kotlin.math.abs(reward) < 0.05f) {
            rssiCapturesAtStart = totalCaptures(); rssiWindowStartMs = now; return
        }
        val score = gained.toDouble() + reward * 0.5
        val base = rssiBaselineScore
        when {
            base == null -> { rssiBaseline = rssiPending; rssiBaselineScore = score }
            score + 0.01 >= base -> { rssiBaseline = rssiPending; rssiBaselineScore = score }  // accept, keep dir
            else -> rssiDir = -rssiDir                                                          // worse → reverse
        }
        rssiPending = (rssiBaseline + rssiDir).coerceIn(-90, -55)
        rssiCapturesAtStart = totalCaptures(); rssiWindowStartMs = now
        Log.i(tag, "Tuner: min_rssi baseline=$rssiBaseline testing=$rssiPending (score=$score)")
    }

    private fun steer(targets: Collection<String>, channels: List<Int>, why: String) {
        if (channels.isEmpty()) return
        val csv = channels.joinToString(",")
        val now = System.currentTimeMillis()
        if (csv == lastSentCsv && now - lastStrategySync < STEER_KEEPALIVE_MS) return
        targets.forEach { id -> outgoingQueue.queueCommand(id, "set_channel_priority", csv) }
        lastSentCsv = csv
        lastStrategySync = now
        onSteer(channels)
        Log.i(tag, "Channel priority → [$csv] ($why) sent to ${targets.size} device(s)")
    }

    /**
     * Get time since last strategy sync (for UI).
     */
    fun getTimeSinceLastStrategySync(): Long {
        return if (lastStrategySync == 0L) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() - lastStrategySync
        }
    }

    /**
     * Get sync status string for UI display (M3 compliant).
     */
    fun getSyncStatusString(): String {
        return when {
            isSyncing -> "🔄 Syncing..."
            lastError != null -> "⚠️ ${lastError}"
            lastStrategySync == 0L -> "⏳ Pending first sync"
            getTimeSinceLastStrategySync() < 10_000 -> "✅ Just synced"
            else -> "✓ Last sync: ${formatTimeDifference(getTimeSinceLastStrategySync())}"
        }
    }

    /**
     * Format time difference for display (M3 human-readable).
     */
    private fun formatTimeDifference(millis: Long): String {
        return when {
            millis < 60_000 -> "${millis / 1000}s ago"
            millis < 3_600_000 -> "${millis / 60_000}m ago"
            else -> "${millis / 3_600_000}h ago"
        }
    }
}

