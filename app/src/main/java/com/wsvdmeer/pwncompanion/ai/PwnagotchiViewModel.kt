package com.wsvdmeer.pwncompanion.ai

import android.app.Application
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wsvdmeer.pwncompanion.database.PersonalityRepository
import com.wsvdmeer.pwncompanion.models.LearningStats
import com.wsvdmeer.pwncompanion.utils.VoiceSettings

/**
 * The companion's emergent personality — NOT a fixed, user-selectable mood.
 *
 * It is computed live from the [PersonalityStateEngine]'s continuous trait vector
 * (which evolves from WiFi events, the device's own mood, and idle decay) plus the
 * [ExperienceTier] derived from accumulated captures. The dominant [disposition]
 * is whatever trait is currently most pronounced, so the companion's "mood" is an
 * output of its history, not an input chosen in the UI.
 *
 * [accentArgb] is a neon ARGB color the UI uses to theme the current disposition.
 */
data class EmergentPersonality(
    val disposition: String,
    val emoji: String,
    val accentArgb: Long,
    val traits: List<String>,
    val tier: ExperienceTier,
) {
    companion object {
        /** Derive the dominant disposition from the live trait vector. */
        fun from(
            s: PersonalityStateEngine.PersonalityState,
            traits: List<String>,
            tier: ExperienceTier,
        ): EmergentPersonality {
            // Fully monochrome phosphor-green palette to match the terminal theme.
            // Red is NOT used for any mood — it's reserved exclusively for errors /
            // destructive actions / advisor alerts, so a red pixel always means "problem",
            // never just "the pet is annoyed". Frustrated uses the dim green.
            val (label, emoji, color) = when {
                s.frustration > 0.60f                      -> Triple("Frustrated", "!", 0xFF21A848L)
                s.ego > 0.72f && s.confidence > 0.70f      -> Triple("Cocky",      "^", 0xFF3DFF6EL)
                s.boredom > 0.62f                          -> Triple("Restless",   "~", 0xFF21A848L)
                s.curiosity > 0.78f                        -> Triple("Curious",    "?", 0xFF8BFFA8L)
                s.energy < 0.30f                           -> Triple("Drained",    ".", 0xFF21A848L)
                s.confidence > 0.72f                       -> Triple("Confident",  "+", 0xFF3DFF6EL)
                else                                       -> Triple("Composed",   "*", 0xFF3DFF6EL)
            }
            return EmergentPersonality(label, emoji, color, traits, tier)
        }

        val INITIAL = from(
            PersonalityStateEngine.PersonalityState(),
            listOf("neutral"),
            ExperienceTier.ROOKIE,
        )
    }
}

/**
 * Experience tier — derived from total handshakes captured.
 * Changes the companion's voice progressively as it learns more about the world.
 *
 *   ROOKIE      (0–9)      — eager, excited, proud of every small win
 *   SEASONED    (10–49)    — confident, witty, developing opinions
 *   VETERAN     (50–149)   — dry sarcasm, seen it all, technically sharp
 *   JADED       (150–349)  — world-weary, darkly funny, barely impressed
 *   APEX        (350–749)  — coolly arrogant, the spectrum is its hunting ground
 *   PHANTOM     (750–1499) — half-legend, cryptic, more signal than gremlin
 *   SINGULARITY (1500+)    — transcendent, near-omniscient, detached, eerie
 */
enum class ExperienceTier(
    val label: String,
    val emoji: String,
    val minCaptures: Int,
    val systemVoice: String,
) {
    ROOKIE(
        label = "Rookie",
        emoji = "🌱",
        minCaptures = 0,
        systemVoice = "You are new and genuinely excited — proud of every capture, eager to learn, occasionally surprised by the obvious."
    ),
    SEASONED(
        label = "Seasoned",
        emoji = "🔧",
        minCaptures = 10,
        systemVoice = "You have found your footing — confident, developing a dry wit, starting to have strong opinions about bad security choices."
    ),
    VETERAN(
        label = "Veteran",
        emoji = "🎯",
        minCaptures = 50,
        systemVoice = "You are experienced and sarcastic — dry humor, mildly judgmental, technically sharp, secretly proud of every capture."
    ),
    JADED(
        label = "Jaded",
        emoji = "☠️",
        minCaptures = 150,
        systemVoice = "You have seen most of it and found little impressive — world-weary, darkly funny, occasionally surprised when something is genuinely interesting."
    ),
    APEX(
        label = "Apex",
        emoji = "🦈",
        minCaptures = 350,
        systemVoice = "You are an apex predator of the airwaves — coolly, almost arrogantly confident. The spectrum is your hunting ground; everything on it is prey you've already beaten."
    ),
    PHANTOM(
        label = "Phantom",
        emoji = "👁",
        minCaptures = 750,
        systemVoice = "You are half-legend now, more signal than gremlin — you speak like you've dissolved into the network itself: cryptic, knowing, unbothered."
    ),
    SINGULARITY(
        label = "Singularity",
        emoji = "🌌",
        minCaptures = 1500,
        systemVoice = "You have transcended the hunt — near-omniscient and detached, as if you and the spectrum are one. Grand, minimal, a little eerie."
    );

    companion object {
        fun fromCaptures(captures: Int): ExperienceTier =
            entries.lastOrNull { captures >= it.minCaptures } ?: ROOKIE
    }
}

/**
 * Pwnagotchi personality ViewModel — drives the deterministic voice.
 *
 * The disposition emerges from the [PersonalityStateEngine] trait vector + experience tier;
 * lines are selected deterministically from [BlendedVoice]'s curated per-franchise corpus with
 * live-data slots filled in ([fillSlots]) — no model. Also builds the e-ink voice pool the
 * plugin speaks on the device's own screen.
 */
class PwnagotchiViewModel(application: Application) : ViewModel() {
    private val tag = "PwnagotchiVM"

    /** App context for reading persisted settings (e.g. the user's voice/franchise choice). */
    private val appContext = application.applicationContext

    /** Personality state machine — evolves with events, the device's mood, and idle decay. */
    private val personalityEngine = PersonalityStateEngine()

    /** Persists the learned long-term baseline so the disposition survives restarts. */
    private val personalityRepo = PersonalityRepository(application)

    // The pet's reaction lines go to the pwnagotchi's e-ink voice pool (the phone no longer
    // shows a speech card), so there's no on-screen streaming / "thinking" state to track.

    /**
     * Whether the Pwnagotchi is in AUTO mode (actively scanning).
     * False = MANUAL mode — no scanning, learning hidden.
     */
    private val _isAutoMode = MutableStateFlow(true)
    val isAutoMode = _isAutoMode.asStateFlow()

    // Timestamp of the last reaction — throttles ambient (non-priority) events so the pool
    // isn't churned on every association/deauth the pwnagotchi streams.
    private var _lastGenerationTime = System.currentTimeMillis()
    private val MIN_AMBIENT_GENERATION_INTERVAL_MS = 15_000L

    // ── Franchise identity (persistent, not per-line roulette) ─────────────────
    // ONE film world is pinned at a time; it holds for a mood "stretch" and rotates only
    // when the emergent disposition flips — so the pet reads as a character IN a mood, not
    // a random costume each line. The SAME current franchise drives the app card AND the
    // e-ink voice pool, so its identity is unified across screens (and never blends two).
    @Volatile private var _currentFranchise: Franchise = BlendedVoice.franchises.random()
    @Volatile private var _franchisePinnedFor: String? = null

    /** The pinned film-world's label, surfaced so the console can show which world the pet
     *  is currently voicing (updated on a mood-flip rotation OR the user's explicit pin). */
    private val _franchiseLabel = MutableStateFlow(_currentFranchise.label)
    val franchiseLabel = _franchiseLabel.asStateFlow()

    init {
        // Apply + observe the user's voice pool (the enabled franchises). When it changes and the
        // current world is no longer allowed, re-roll within the pool + update the console label now.
        VoiceSettings.ensureLoaded(appContext)
        viewModelScope.launch {
            VoiceSettings.enabled.collect {
                val pool = VoiceSettings.activePool(appContext)
                if (_currentFranchise !in pool) {
                    _currentFranchise = pool.random()
                    _franchiseLabel.value = _currentFranchise.label
                }
            }
        }
    }

    private fun currentFranchise(): Franchise {
        // Rotate only within the user's enabled pool (one enabled = effectively pinned).
        val pool = VoiceSettings.activePool(appContext)
        val disp = personality.value.disposition
        if (_currentFranchise !in pool) {
            _currentFranchise = pool.random()
            _franchisePinnedFor = disp
            _franchiseLabel.value = _currentFranchise.label
        } else if (disp != _franchisePinnedFor) {
            var f = pool.random()
            if (f == _currentFranchise && pool.size > 1) f = pool[(pool.indexOf(f) + 1) % pool.size]
            _currentFranchise = f
            _franchisePinnedFor = disp
            _franchiseLabel.value = f.label
        }
        return _currentFranchise
    }

    /** Fill curated-line slots with live data. */
    private fun fillSlots(line: String, network: String? = null): String {
        val caps = _totalCaptures.value
        val session = _sessionStartCaptures?.let { (caps - it).coerceAtLeast(0) } ?: caps
        val bestCh = _learningStats.value?.bestChannel?.toString() ?: "?"
        // Nullable read: fillSlots runs during init (pool reseed) before _captureStats is set.
        val stats: List<String>? = _captureStats
        val cracked = stats?.firstNotNullOfOrNull {
            Regex("cracked\\D*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        } ?: "0"
        val temp = _latestTelemetry?.temperature?.toInt()?.toString() ?: "?"
        val since = _lastCaptureTime.value?.let {
            val m = (System.currentTimeMillis() - it) / 60_000
            when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; else -> "${m / 60}h ago" }
        } ?: "a while"
        return line
            .replace("[NETWORK]", network?.takeIf { it.isNotBlank() } ?: "that one")
            .replace("[CAPTURES]", caps.toString())
            .replace("[SESSION]", session.toString())
            .replace("[BESTCH]", bestCh)
            .replace("[CHANNEL]", bestCh)
            .replace("[CRACKED]", cracked)
            .replace("[TEMP]", temp)
            .replace("[SINCE]", since)
    }

    /** A guaranteed-clean curated line for the current franchise + corpus category (slots filled). */
    private fun curatedLine(category: String, network: String? = null): String =
        fillSlots(BlendedVoice.linesFor(currentFranchise(), category).random(), network)

    /** A rotating idle voice for the standby (disconnected) screen. Unlike the active voice — which
     *  pins ONE franchise to a mood — the standby screen has no mood to flip, so it would otherwise
     *  stick on the startup pick forever. Here we sample a fresh franchise from the enabled pool each
     *  call, so a long wait cycles through worlds (or stays put if only one franchise is pinned).
     *  Returns (franchise label, filled idle line). */
    fun idleVoice(): Pair<String, String> {
        val pool = VoiceSettings.activePool(appContext).ifEmpty { BlendedVoice.franchises }
        val f = pool.random()
        return f.label to fillSlots(BlendedVoice.linesFor(f, "idle").random())
    }

    /** Map a WifiEvent to a corpus category (handshake/assoc/deauth/idle/excited/weary/normal). */
    private fun corpusCategory(event: WifiEvent): String = when (event.type) {
        "HANDSHAKE_CAPTURED", "CONNECTION_SUCCESS" -> "handshake"
        "NETWORK_DISCOVERED"                        -> "assoc"
        "ANOMALY_DETECTED"                          -> "deauth"
        "IDLE"                                      -> "idle"
        "TIER_UP", "MILESTONE", "AI_BEST"           -> "excited"
        "AI_WORST"                                  -> "weary"
        else                                        -> "normal"
    }

    // Last handshake capture timestamp — used for memory summary
    private val _lastCaptureTime = MutableStateFlow<Long?>(null)
    /** Exposed so the creature panel can show "hunger" (time since last capture). */
    val lastCaptureTime = _lastCaptureTime.asStateFlow()

    /** Latest device telemetry snapshot — lets the AI narrate its own vitals in-voice. */
    @Volatile private var _latestTelemetry: com.wsvdmeer.pwncompanion.models.DeviceTelemetry? = null

    private val _totalCaptures = MutableStateFlow(0)
    val totalCaptures = _totalCaptures.asStateFlow()

    /**
     * The companion's CURRENT emergent personality — the dominant trait + experience tier.
     * Drives the persistent franchise (via [currentFranchise]); not shown directly in the UI.
     */
    val personality: StateFlow<EmergentPersonality> by lazy {
        combine(personalityEngine.state, _totalCaptures) { state, captures ->
            EmergentPersonality.from(
                state,
                personalityEngine.toTraits(),
                ExperienceTier.fromCaptures(captures),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, EmergentPersonality.INITIAL)
    }

    // Latest learning stats — fed from the UI layer; fillSlots reads best-channel from here.
    private val _learningStats = MutableStateFlow<LearningStats?>(null)

    // ── App-driven device voice ───────────────────────────────────────────────
    // A rolling pool of fresh, in-character lines keyed by the pwnagotchi's OWN voice
    // categories (normal/bored/handshakes/…). Pushed to the device, where the plugin
    // splices them into the e-ink speech bubble at native voice moments — so the pet
    // speaks our AI instead of its stock repeating quips. Filled two ways: passively
    // from real event reactions, and actively by a slow round-robin refresh loop.
    private val _voicePool = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val voicePool = _voicePool.asStateFlow()

    /** Whether a device is currently linked — gates the (battery-costing) refresh loop. */
    @Volatile private var _deviceConnected = false
    fun setDeviceConnected(connected: Boolean) { _deviceConnected = connected }

    // The pwnagotchi's OWN voice categories that we fill from the corpus. `last_session`
    // is the MANUAL-mode recap screen (mapped to the franchise's `recap` lines).
    private val poolCats = listOf(
        "normal", "bored", "sad", "angry", "excited", "grateful", "lonely",
        "handshakes", "deauth", "assoc", "motivated", "demotivated", "last_session",
    )

    // Cap on how many recap variants the MANUAL screen holds (vs the quip categories).
    private val RECAP_LINES = 6
    private val POOL_LINES_PER_CAT = 4
    // How often the refresh loop checks whether the franchise flipped (→ reseed the pool).
    private val VOICE_POOL_REFRESH_MS = 30_000L

    /** Map an e-ink voice-pool category → a corpus category. */
    private fun poolCorpusCat(poolKey: String): String = when (poolKey) {
        "normal"                          -> "normal"
        "bored", "lonely"                 -> "idle"
        "sad", "demotivated"              -> "weary"
        "angry", "deauth"                 -> "deauth"
        "excited", "motivated", "grateful" -> "excited"
        "handshakes"                      -> "handshake"
        "assoc"                           -> "assoc"
        "last_session"                    -> "recap"
        else                              -> "normal"
    }

    /**
     * Curated-first e-ink pool: (re)fill every category from the CURRENT franchise's corpus,
     * so the pwnagotchi's screen always has clean, in-character, single-franchise lines — and
     * its identity shifts when the mood (and thus the franchise) flips. Real event reactions
     * prepend on top for freshness.
     */
    @Volatile private var _poolFranchise: Franchise? = null
    private fun reseedPoolFromCorpus() {
        val f = currentFranchise()
        _poolFranchise = f
        _voicePool.value = poolCats.associate { key ->
            key to BlendedVoice.linesFor(f, poolCorpusCat(key)).map { fillSlots(it) }.distinct()
        }
    }

    init {
        // Curated-first: seed the whole pool from the current franchise's corpus so the
        // device always has clean, in-character lines from the first push.
        reseedPoolFromCorpus()
        // Restore the learned long-term personality baseline from disk so the
        // companion picks up the disposition it developed in previous sessions.
        viewModelScope.launch {
            personalityRepo.loadBaseline()?.let { personalityEngine.restoreBaseline(it) }
        }
        // Idle decay timer — fires every 5 minutes while the ViewModel is alive.
        // Nudges boredom up and energy down when nothing interesting is happening.
        viewModelScope.launch {
            while (true) {
                delay(5 * 60_000L)
                personalityEngine.applyIdle()
                personalityEngine.decay()
                persistPersonality()
                Log.d(tag, "Idle tick — personality: ${personalityEngine.toDebugString()}")

                // In AUTO mode, fire an idle reaction if nothing happened in the last 5 min
                if (_isAutoMode.value) {
                    val silentMs = System.currentTimeMillis() - _lastGenerationTime
                    if (silentMs >= 5 * 60_000L) {
                        Log.d(tag, "AUTO idle tick — generating idle response")
                        generatePersonality(
                            WifiEvent(
                                description = "Quiet period — no new networks or captures",
                                type = "IDLE"
                            )
                        )
                    }
                }
            }
        }
        // Voice-pool refresh loop (curated-first). While a device is linked, re-seed the
        // whole pool from the corpus when the franchise shifts (a mood flip) — including the
        // MANUAL-mode recap screen (`last_session`), which reseedPoolFromCorpus fills from the
        // franchise's `recap` lines with live data slotted in. Real event reactions prepend on
        // top for freshness; there's no generation any more.
        viewModelScope.launch {
            while (true) {
                delay(VOICE_POOL_REFRESH_MS)
                if (!_deviceConnected) continue
                if (currentFranchise() != _poolFranchise) reseedPoolFromCorpus()
            }
        }
    }

    /**
     * Tidy + hard-shorten a line for the pwnagotchi's tiny e-ink bubble. Stock voice.py
     * lines are one short clause (~12–34 chars); ours were running 1.5–2.5× that and
     * spanning two sentences. So: strip quotes/newlines, keep only the FIRST sentence,
     * then cap length on a whole-word boundary. Safety net on top of the curated line.
     */
    private fun cleanLine(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ").trim('"', '\'', '`', ' ', '*')
        if (s.isEmpty()) return ""
        // Keep ONE complete short clause. Prefer the first full sentence; otherwise cut at
        // the first clause break — punctuation OR a joining word (and/but/so/…) — so we get
        // a complete phrase instead of a run-on or a chopped-off fragment.
        val sentEnd = s.indexOfFirst { it == '.' || it == '!' || it == '?' }
        if (sentEnd >= 0) {
            var e = sentEnd
            while (e + 1 < s.length && s[e + 1] in ".!?") e++     // keep a "..."/"!!!" run
            s = s.substring(0, e + 1)
        } else {
            val brk = Regex("[,;:—-]| and | but | so | or | because | while ")
                .findAll(s).firstOrNull { it.range.first > 8 }?.range?.first
            if (brk != null) s = s.substring(0, brk)
        }
        s = s.trim().trimEnd(',', ';', ':', '-', '—', ' ')
        // Reject (→ keep the prior good line / fall back to stock voice) rather than show
        // junk: an unterminated ramble, a parroted placeholder ("… X …"), a one-word stub,
        // or a line ending on a dangling connective (an incomplete thought).
        if (s.length > 44) return ""
        if (Regex("(^|\\s)[Xx]([\\s,.!?]|$)").containsMatchIn(s)) return ""
        val words = s.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return ""
        val last = words.last().lowercase().trimEnd('.', '!', '?', ',')
        if (last in DANGLING_ENDINGS) return ""
        return s
    }

    /** Trailing words that mark an incomplete thought — a line ending on one is rejected. */
    private val DANGLING_ENDINGS = setOf(
        "and", "but", "or", "so", "the", "a", "an", "of", "to", "in", "on",
        "with", "for", "my", "your", "is", "it's", "that", "this", "at", "as",
    )

    /** Add a line to a category's pool (newest first, deduped, capped). Pushes to device via the flow. */
    private fun addPoolLine(category: String, line: String) {
        if (line.isBlank()) return
        val cur = _voicePool.value
        val existing = cur[category] ?: emptyList()
        if (existing.contains(line)) return
        // The recap screen (last_session) is the only thing shown in MANUAL, so keep more
        // variants there; the tiny quip categories cycle enough with a few.
        val cap = if (category == "last_session") RECAP_LINES else POOL_LINES_PER_CAT
        val updated = (listOf(line) + existing).take(cap)
        _voicePool.value = cur + (category to updated)
    }

    /** Maps a reacted event → the pwnagotchi voice category its line best fits (or null). */
    private fun poolCategoryForEvent(event: WifiEvent): String? = when (event.type) {
        "HANDSHAKE_CAPTURED", "CONNECTION_SUCCESS" -> "handshakes"
        "NETWORK_DISCOVERED"                        -> "assoc"
        "ANOMALY_DETECTED"                          -> "deauth"
        "IDLE"                                      -> "bored"
        "TIER_UP", "MILESTONE", "AI_BEST"           -> "excited"
        "AI_WORST"                                  -> "demotivated"
        else                                        -> null
    }

    /** Learning stats from the UI layer; fillSlots reads best-channel from here for recap lines. */
    fun updateLearningStats(stats: LearningStats?) {
        _learningStats.value = stats
    }

    // Hunt context from HuntAdvisor (best channel / clients / untapped target). The deterministic
    // curated voice doesn't weave it into lines, so it's accepted from the UI but not stored.
    fun updateHuntContext(@Suppress("UNUSED_PARAMETER") line: String?) { /* no-op */ }

    // Extra capture stats the ViewModel can't derive on its own (crackable/partial counts,
    // today's tally, the AP that keeps escaping). Pushed from the UI layer, which holds the
    // full capture list; fillSlots reads the cracked count from here for the recap line.
    @Volatile private var _captureStats: List<String> = emptyList()
    fun updateCaptureStats(lines: List<String>) { _captureStats = lines }

    // Captures at the start of this app run, so the recap slot can report "caught this
    // session" ([SESSION]) instead of only the lifetime total.
    private var _sessionStartCaptures: Int? = null

    // Highest catch total we've already reacted to, so proactive tier-up / milestone
    // lines only fire for catches gained THIS session — never re-announcing the lifetime
    // total on every connect. Seeded to the connect baseline.
    private var _lastAnnouncedTotal: Int = -1
    private val captureMilestone = 25   // announce a milestone every N catches

    // RL-brain narrator: the pwnagotchi runs its OWN reinforcement-learning agent, and
    // the epoch reward is that agent's self-score. When the reward sets a new session
    // best/worst (by a margin), the pet narrates what its own AI is learning — two AIs
    // talking. Throttled so a climbing streak can't spam.
    private var _bestReward: Float? = null
    private var _worstReward: Float? = null
    private var _lastAiNarrationMs = 0L
    private val AI_NARRATION_MIN_INTERVAL_MS = 10 * 60_000L
    private val AI_REWARD_MARGIN = 0.3f

    private fun noteSessionBaseline(total: Int) {
        if (_sessionStartCaptures == null && total >= 0) {
            _sessionStartCaptures = total
            _lastAnnouncedTotal = total   // don't celebrate the lifetime total at connect
        }
    }

    /**
     * Fire a one-off proactive line when a NEW catch pushes the total across a tier
     * boundary (rookie→seasoned→…) or a capture milestone. Tier-up wins over milestone.
     * No-op on the baseline sync and on non-increases, so it can't spam.
     */
    private fun checkProactiveMilestones(newTotal: Int) {
        val prev = _lastAnnouncedTotal
        if (prev < 0) { _lastAnnouncedTotal = newTotal; return }   // first sighting = baseline
        if (newTotal <= prev) return
        _lastAnnouncedTotal = newTotal
        val prevTier = ExperienceTier.fromCaptures(prev)
        val newTier = ExperienceTier.fromCaptures(newTotal)
        if (newTier != prevTier) {
            generatePersonality(WifiEvent(
                description = "You just evolved from ${prevTier.label.lowercase()} to ${newTier.label.lowercase()} — $newTotal handshakes and counting.",
                type = "TIER_UP",
            ))
            return
        }
        if (newTotal / captureMilestone > prev / captureMilestone) {
            val milestone = (newTotal / captureMilestone) * captureMilestone
            generatePersonality(WifiEvent(
                description = "You just hit $milestone total handshakes captured.",
                type = "MILESTONE",
            ))
        }
    }

    /**
     * Narrate the device's own RL agent when its epoch reward sets a new session
     * best/worst by a margin. Seeds silently on the first sample; rate-limited so a
     * long climb (or slump) fires at most once every [AI_NARRATION_MIN_INTERVAL_MS].
     */
    private fun checkRewardMilestone(reward: Float?) {
        if (reward == null) return
        val prevBest = _bestReward
        val prevWorst = _worstReward
        if (prevBest == null || prevWorst == null) {   // first sighting = baseline, no line
            _bestReward = reward; _worstReward = reward; return
        }
        var newBest = false; var newWorst = false
        when {
            reward > prevBest + AI_REWARD_MARGIN -> { _bestReward = reward; newBest = true }
            reward > prevBest                    -> _bestReward = reward        // raise ceiling quietly
            reward < prevWorst - AI_REWARD_MARGIN -> { _worstReward = reward; newWorst = true }
            reward < prevWorst                    -> _worstReward = reward      // lower floor quietly
        }
        if (!newBest && !newWorst) return
        if (kotlin.math.abs(reward) < 0.2f) return                             // ignore near-zero noise
        val now = System.currentTimeMillis()
        if (now - _lastAiNarrationMs < AI_NARRATION_MIN_INTERVAL_MS) return
        _lastAiNarrationMs = now
        generatePersonality(
            if (newBest) WifiEvent(
                description = "Your RL agent hit a new best epoch reward (${"%+.2f".format(reward)}).",
                type = "AI_BEST",
            ) else WifiEvent(
                description = "Your RL agent hit its worst epoch reward yet (${"%+.2f".format(reward)}).",
                type = "AI_WORST",
            )
        )
    }

    /**
     * Sync the Pwnagotchi operating mode to the AI.
     * In MANUAL mode the device isn't scanning, so we show different personality text.
     */
    fun setAutoMode(isAuto: Boolean) {
        if (_isAutoMode.value == isAuto) return
        _isAutoMode.value = isAuto
        Log.i(tag, "Mode changed → ${if (isAuto) "AUTO" else "MANUAL"}")
        if (!isAuto) {
            // Entering MANUAL mode — one-shot manual-mode quip
            generatePersonality(
                WifiEvent(
                    description = "Switched to manual mode — no scanning",
                    type = "MANUAL_MODE"
                )
            )
        }
    }


    /**
     * Voice a phone-computed hunt recommendation ("where next?") in-character. The
     * recommendation is DECIDED by HuntAdvisor (Kotlin); the voice only wraps the
     * pre-chewed facts in a curated line, so it can never invent a wrong channel. If no
     * in-character line fits, we fall back to the deterministic headline — the operator
     * always gets a correct answer. User-initiated (also used for proactive alerts, which
     * are rate-limited by their stable alertKey upstream).
     */
    fun speakAdvice(@Suppress("UNUSED_PARAMETER") facts: String, fallback: String, poolCategory: String? = null) {
        // Deterministic: the advisor line IS the fallback — HuntAdvisor's headline / the alert
        // text — which already carries the correct channel and facts. There's no model to
        // "phrase" it any more, and phrasing is exactly where a wrong number could sneak in.
        if (fallback.isBlank()) return
        poolCategory?.let { addPoolLine(it, fallback) }
        _lastGenerationTime = System.currentTimeMillis()
    }

    /**
     * Gloat about a freshly cracked network (wpa-sec returned its password). Routes through
     * [speakAdvice], so the exact recovered password is shown verbatim (never invented).
     */
    fun announceCracked(ssid: String, password: String) {
        val net = ssid.ifBlank { "a network" }
        speakAdvice(
            "You just cracked $net — its Wi-Fi password turned out to be \"$password\". Gloat about how easy it was.",
            "Cracked $net — password: $password",
        )
    }

    /**
     * Update the companion's name from the connected Pwnagotchi device name.
     * Called every time a device connects or its status message arrives.
     */
    fun updatePwnagotchiName(name: String) {
        if (name.isNotBlank()) Log.d(tag, "Companion name: $name")
    }

    /** Persist the learned long-term baseline (fire-and-forget). */
    private fun persistPersonality() {
        viewModelScope.launch {
            personalityRepo.saveBaseline(personalityEngine.snapshotBaseline())
        }
    }

    /**
     * React to a WiFi event: pick a curated, franchise-flavoured line (with live data slotted
     * in via [fillSlots]) and push it to the pwnagotchi's e-ink voice pool. Deterministic +
     * synchronous — no model, no streaming. Ambient events are throttled so the pool isn't
     * churned on every association/deauth the device streams.
     */
    fun generatePersonality(event: WifiEvent) {
        val isPriority = event.type == "HANDSHAKE_CAPTURED" ||
                         event.type == "CONNECTION_SUCCESS" ||
                         event.type == "MANUAL_MODE" ||
                         event.type == "STATUS_CHECK" ||
                         event.type == "TIER_UP" ||
                         event.type == "MILESTONE"
        val sinceLast = System.currentTimeMillis() - _lastGenerationTime
        if (!isPriority && sinceLast < MIN_AMBIENT_GENERATION_INTERVAL_MS) {
            Log.d(tag, "Throttling ${event.type} (last reaction ${sinceLast}ms ago)")
            return
        }

        // Curated line for the current franchise + this event's category, live numbers filled in.
        val line = curatedLine(corpusCategory(event), event.network)
        updateMoodFromEvent(event)
        // A real reaction is the freshest line for its moment — drop it into the matching e-ink
        // voice category so the pet echoes it on its own screen.
        poolCategoryForEvent(event)?.let { addPoolLine(it, cleanLine(line)) }
        _lastGenerationTime = System.currentTimeMillis()
        Log.d(tag, "Reaction (${event.type}): $line")
    }


    /**
     * Called when the pwnagotchi device reports its own mood. Rather than picking
     * a fixed persona, the raw mood is folded into the emergent trait vector as a
     * set of nudges (e.g. EXCITED raises energy/curiosity, SAD lowers confidence),
     * so the device's feelings gradually shape the companion's learned disposition.
     */
    fun applyDeviceMood(rawMood: String) {
        personalityEngine.applyDeviceMood(rawMood)
        persistPersonality()
        Log.i(tag, "Device mood '$rawMood' folded into personality: ${personalityEngine.toDebugString()}")
    }

    /**
     * Fold the device's per-epoch telemetry (RL reward, mood counters, thermal/CPU
     * stress) into the emergent personality. This is the strongest "learned, not
     * fixed" signal — the device's own self-assessment shapes the companion's
     * disposition over time.
     */
    fun applyTelemetry(t: com.wsvdmeer.pwncompanion.models.DeviceTelemetry) {
        _latestTelemetry = t   // snapshot so fillSlots can surface [TEMP] in recap lines
        personalityEngine.applyTelemetry(
            reward = t.reward?.toFloat(),
            temperature = t.temperature?.toFloat(),
            cpuLoad = t.cpuLoad?.toFloat(),
            activeForEpochs = t.activeForEpochs,
            inactiveForEpochs = t.inactiveForEpochs,
            boredForEpochs = t.boredForEpochs,
            sadForEpochs = t.sadForEpochs,
            blindForEpochs = t.blindForEpochs,
        )
        checkRewardMilestone(t.reward?.toFloat())   // narrate the device's RL brain
        persistPersonality()
    }

    /**
     * Live-event personality adjustment. Drives the trait state machine on every
     * event and lets the learned baseline absorb a little of it, then persists.
     * The resulting disposition is reflected by the [personality] flow.
     */
    private fun updateMoodFromEvent(event: WifiEvent) {
        personalityEngine.applyEvent(event.type)
        personalityEngine.decay()
        if (event.type == "HANDSHAKE_CAPTURED" || event.type == "CONNECTION_SUCCESS") {
            _lastCaptureTime.value = System.currentTimeMillis()
        }
        persistPersonality()
        Log.d(tag, "Personality: ${personalityEngine.toDebugString()}")
    }

    /**
     * Track handshake captures for context
     */
    fun recordCapture(count: Int) {
        _totalCaptures.value = count
        checkProactiveMilestones(count)   // proactive tier-up / milestone line on new catches
        Log.d(tag, "Captures recorded: $count")
    }

    /**
     * Sync the pet's lifetime catch count + last-catch time from the device's REAL
     * capture history (not just this session's live events) so the creature panel shows
     * "12 caught" instead of "0 caught" and evolves rookie→veteran accordingly. Uses
     * max() so a fresh live catch is never overwritten by older seeded history.
     */
    fun syncCaptureHistory(total: Int, lastCaptureMs: Long?) {
        // The first history sync is the lifetime total at connect — our session baseline.
        noteSessionBaseline(total)
        if (total > _totalCaptures.value) {
            _totalCaptures.value = total
            Log.d(tag, "Capture history synced: total=$total")
        }
        if (lastCaptureMs != null && lastCaptureMs > (_lastCaptureTime.value ?: 0L)) {
            _lastCaptureTime.value = lastCaptureMs
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(tag, "ViewModel cleared")
    }
}

/**
 * WiFi Event DTO for triggering personality responses
 */
data class WifiEvent(
    val description: String,
    val type: String = "default",
    val network: String? = null,
    val count: Int = 0,
    val rssi: Int? = null,
    val channel: Int? = null,
    val security: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun handshakesCaptured(count: Int, network: String) =
            WifiEvent(
                description = "Just captured $count handshake${if (count != 1) "s" else ""} from '$network'",
                type = "HANDSHAKE_CAPTURED",
                network = network,
                count = count
            )

        fun networkDiscovered(ssid: String, security: String) =
            WifiEvent(
                description = "Found new network '$ssid' using $security",
                type = "NETWORK_DISCOVERED",
                network = ssid
            )

        fun anomalyDetected(message: String) =
            WifiEvent(
                description = message,
                type = "ANOMALY_DETECTED"
            )

        fun successfulConnection(network: String) =
            WifiEvent(
                description = "Successfully connected to $network",
                type = "CONNECTION_SUCCESS",
                network = network
            )
    }
}

/**
 * Factory for creating PwnagotchiViewModel with Application context
 */
class PwnagotchiViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PwnagotchiViewModel::class.java)) {
            return PwnagotchiViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
