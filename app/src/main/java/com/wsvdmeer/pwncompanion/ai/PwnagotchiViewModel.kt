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
 * Pwnagotchi AI Personality ViewModel
 * Manages LLM interactions and personality feedback
 *
 * Real-time features:
 * - Streaming token generation
 * - Live token counter
 * - Status messages during generation
 * - Mood tracking for contextual responses
 */
class PwnagotchiViewModel(application: Application) : ViewModel() {
    private val llamaClient = LlamaClient(application)
    private val tag = "PwnagotchiVM"

    /** Personality state machine — evolves with events, the device's mood, and idle decay. */
    private val personalityEngine = PersonalityStateEngine()

    /** Persists the learned long-term baseline so the disposition survives restarts. */
    private val personalityRepo = PersonalityRepository(application)

    /** Expose the raw trait vector so the UI can visualise it (bars/readout). */
    val personalityState = personalityEngine.state

    // UI State Flows
    private val _personalityText = MutableStateFlow("")
    val personalityText = _personalityText.asStateFlow()

    /** One entry in the AI feed: a spoken line + what triggered it. */
    data class AiFeedEntry(val timestamp: Long, val trigger: String, val line: String)

    // Rolling log of the pet's recent lines + their trigger — lets you confirm the
    // reaction/emotion/RL features are firing (hard to eyeball on the foldable).
    private val _aiFeed = MutableStateFlow<List<AiFeedEntry>>(emptyList())
    val aiFeed = _aiFeed.asStateFlow()

    private fun pushFeed(trigger: String, line: String) {
        val l = line.trim()
        if (l.isEmpty()) return
        _aiFeed.value = (listOf(AiFeedEntry(System.currentTimeMillis(), trigger, l)) + _aiFeed.value).take(40)
    }

    /** Short human label for the AI feed, per event type. */
    private fun feedTrigger(event: WifiEvent): String = when (event.type) {
        "HANDSHAKE_CAPTURED", "CONNECTION_SUCCESS" -> "caught ${event.network ?: "a handshake"}"
        "NETWORK_DISCOVERED" -> "saw ${event.network ?: "a network"}"
        "ANOMALY_DETECTED"   -> "anomaly"
        "IDLE"               -> "idle"
        "MANUAL_MODE"        -> "poked"
        "STATUS_CHECK"       -> "status check"
        "TIER_UP"            -> "levelled up"
        "MILESTONE"          -> "milestone"
        "AI_BEST"            -> "rl brain: new best"
        "AI_WORST"           -> "rl brain: rough run"
        else                 -> event.type.lowercase().replace("_", " ")
    }

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    // True from generation start until the first word token arrives (the "thinking" phase)
    private val _isThinking = MutableStateFlow(false)
    val isThinking = _isThinking.asStateFlow()

    private val _wordCount = MutableStateFlow(0)
    val wordCount = _wordCount.asStateFlow()

    // Human-friendly model name for display in UI
    val modelName: String
        get() = if (llamaClient.isGgufLoaded()) "Qwen2.5 0.5B" else "Built-in AI"

    // If an event arrives while generation is in progress, store it here and
    // process it immediately after the current generation finishes.
    private var pendingEvent: WifiEvent? = null

    /**
     * True when the Qwen2 GGUF model is on disk.
     * Built-in engine is always active as fallback — no file needed.
     */
    val isModelInstalled: Boolean
        get() = llamaClient.modelManager.isModelAvailable()


    /**
     * True once the model is fully loaded in memory and ready for inference.
     * Starts false; set to true after the background init coroutine completes.
     */
    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing...")
    val statusMessage = _statusMessage.asStateFlow()

    // Companion name — set from the connected Pwnagotchi's device name
    private val _pwnagotchiName = MutableStateFlow("Pwnagotchi")

    /**
     * Whether the Pwnagotchi is in AUTO mode (actively scanning).
     * False = MANUAL mode — no scanning, learning hidden, idle AI text shown.
     */
    private val _isAutoMode = MutableStateFlow(true)
    val isAutoMode = _isAutoMode.asStateFlow()

    // Timestamp of the last generated response — used to throttle idle events
    private var _lastGenerationTime = System.currentTimeMillis()

    // Minimum gap between AMBIENT (non-priority) generations, so the AI isn't
    // permanently "thinking" while the Pwnagotchi streams frequent events.
    private val MIN_AMBIENT_GENERATION_INTERVAL_MS = 15_000L

    // Flavour: terminal/hacker-themed status lines shown while the LLM works,
    // instead of a bland "Thinking…". Pick one at random per generation.
    /** Blended "thinking…" status phrases shown while the model works. */
    private fun workingPhrase(): String = BlendedVoice.thinking.random()

    // One cult-movie world per line: pick a franchise (avoiding an immediate repeat)
    // and return the directive injected into the prompt, so the model commits to a
    // single franchise instead of blending two (e.g. Evil Dead + Star Wars) in one line.
    @Volatile private var _lastFranchise: Franchise? = null
    private fun pickFranchiseDirective(): String {
        val opts = BlendedVoice.franchises
        var f = opts.random()
        if (f == _lastFranchise && opts.size > 1) f = opts[(opts.indexOf(f) + 1) % opts.size]
        _lastFranchise = f
        return "Voice this line PURELY in the ${f.label} style (${f.cue}) Do NOT mix in any other franchise."
    }

    // Last handshake capture timestamp — used for memory summary
    private val _lastCaptureTime = MutableStateFlow<Long?>(null)
    /** Exposed so the creature panel can show "hunger" (time since last capture). */
    val lastCaptureTime = _lastCaptureTime.asStateFlow()

    /** Latest device telemetry snapshot — lets the AI narrate its own vitals in-voice. */
    @Volatile private var _latestTelemetry: com.wsvdmeer.pwncompanion.models.DeviceTelemetry? = null

    private val _totalCaptures = MutableStateFlow(0)
    val totalCaptures = _totalCaptures.asStateFlow()

    /** Experience tier — reactively derived from total captures. Drives voice + canned pool selection. */
    val experienceTier: StateFlow<ExperienceTier> by lazy {
        _totalCaptures
            .map { ExperienceTier.fromCaptures(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ExperienceTier.ROOKIE)
    }

    /**
     * The companion's CURRENT emergent personality — computed from the live trait
     * vector + experience tier. This is what the UI displays; there is no mood
     * picker. It changes as the device lives through events and idles.
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

    // The companion no longer has a selectable voice theme. It speaks in one blended
    // cult-movie voice ([BlendedVoice]) whose TONE (hyped / grumpy / weary / deadpan)
    // is derived live from the emergent disposition — see buildPrompt().

    // ── Download state (shown inline in PwnagotchiPersonalityCard) ─────────────
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _downloadStatusText = MutableStateFlow("")
    val downloadStatusText = _downloadStatusText.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError = _downloadError.asStateFlow()

    /**
     * Non-null when the model is installed but failed or timed out during load.
     * The card shows this error with a retry button.
     */
    private val _modelLoadError = MutableStateFlow<String?>(null)
    val modelLoadError = _modelLoadError.asStateFlow()

    // Latest learning stats — fed in from the UI layer so buildPrompt() can reference channel/location intel
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

    /** Voice categories we generate for, with a first-person "moment" the model voices. */
    private data class PoolCat(val key: String, val moment: String)
    private val poolCats = listOf(
        PoolCat("normal",      "You're calmly on the prowl, scanning the airwaves, quietly confident."),
        PoolCat("bored",       "Nothing's happening — the spectrum's dead and you're bored out of your circuits."),
        PoolCat("sad",         "A long, empty dry spell — no catches, and you're feeling low."),
        PoolCat("angry",       "Something's pissing you off — interference, or a stubborn locked-down target."),
        PoolCat("excited",     "You're hyped and on a roll, feeling powerful — the hunt's going great."),
        PoolCat("grateful",    "You're thankful — a good haul, things are finally going your way."),
        PoolCat("lonely",      "No other pwnagotchis around, no friends on the mesh — a little lonely."),
        PoolCat("handshakes",  "You just snatched a fresh Wi-Fi handshake — a shiny new trophy."),
        PoolCat("deauth",      "You're blasting a deauth, kicking a client off to force a handshake."),
        PoolCat("assoc",       "You're sidling up to a new access point, sizing up your prey."),
        PoolCat("motivated",   "You're fired up — ready to hunt down every network in range."),
        PoolCat("demotivated", "You're sluggish and demotivated — the hunt feels pointless right now."),
        // Special: the MANUAL-mode recap screen. Generated from our data (buildRecapPrompt),
        // not the generic moment prompt — handled in the refresh loop below.
        PoolCat("last_session", ""),
    )
    // Refresh order, with `normal` weighted (appears 3× per lap) since it's the category
    // the device shows almost all the time — so its lines stay the freshest.
    private val poolRotation: List<PoolCat> by lazy {
        val normal = poolCats.first { it.key == "normal" }
        poolCats + listOf(normal, normal)
    }
    private val lastSessionCat: PoolCat by lazy { poolCats.first { it.key == "last_session" } }

    // How many distinct recap lines to build for the MANUAL screen before idling.
    private val RECAP_LINES = 6
    // Rotating emphasis so consecutive recaps highlight different data (not the same blurb).
    private val recapAngles = listOf(
        "how many you've caught this session and in total",
        "your best hunting channel and how well it yields",
        "how many handshakes are cracked or still crackable",
        "your mood and how the hunt feels right now",
        "the network that keeps escaping you",
    )
    private var _recapAngle = 0
    private fun nextRecapAngle(): String = recapAngles[_recapAngle++ % recapAngles.size]
    private var _poolIndex = 0
    private val POOL_LINES_PER_CAT = 4
    // One fresh LLM line every 30s while connected. `normal` is weighted (it's on-screen
    // almost all the time) so its pool turns over faster than the rare categories.
    private val VOICE_POOL_REFRESH_MS = 30_000L

    /**
     * Curated seed lines so every category — crucially `normal`, which the device shows
     * almost all the time during idle scanning (agent.py calls view.on_normal() each recon
     * cycle) — always has a short, in-voice line. Without this the stock `on_normal()`
     * returns '' / '...' and the bubble looks blank. Short (≤~40c), placeholder-free, one
     * clause; LLM refreshes prepend on top and push these out over time (→ more variety).
     */
    private val seedLines: Map<String, List<String>> = mapOf(
        "normal"      to listOf("Prowling the spectrum, patient as death.", "Just me and the airwaves tonight.", "Hunting quietly. The wire never sleeps."),
        "bored"       to listOf("Bored. Wake me when something bleeds.", "Nothing but static and silence."),
        "sad"         to listOf("Another dead night on the wire.", "The spectrum's cold, and so am I."),
        "angry"       to listOf("Someone's jamming my hunt. Bad move.", "The air's fighting back tonight."),
        "excited"     to listOf("The hunt is ON. I'm unstoppable.", "So many targets, so little mercy."),
        "grateful"    to listOf("Good hauls tonight. I'm grateful.", "The wire's been kind to me."),
        "lonely"      to listOf("No units nearby. Just me in the dark.", "Alone on the frequency again."),
        "handshakes"  to listOf("Another handshake, another trophy.", "Snatched it clean. Delicious.", "Its secret belongs to me now."),
        "deauth"      to listOf("Kicked 'em off. No Wi-Fi for you.", "Consider yourself unplugged."),
        "assoc"       to listOf("Sidling up to fresh prey.", "Knocking on a new door."),
        "motivated"   to listOf("Fired up. Every network is mine.", "Lock and load. Time to hunt."),
        "demotivated" to listOf("Can't be bothered. Hunt's dull.", "Low power, lower spirits."),
        // Shown on the MANUAL-mode recap screen (replaces the stock kicked/handshakes tally).
        "last_session" to listOf(
            "Another night on the hunt, logged.",
            "Tallying the night's trophies.",
            "Session's paused. The kills stand.",
            "Reviewing the damage I've done.",
            "The wire remembers what I took.",
        ),
    )

    init {
        // Seed the voice pool so the device always has a line to speak (esp. `normal`,
        // its dominant idle state) from the first push — LLM lines layer on for variety.
        _voicePool.value = seedLines
        // Built-in engine needs no model file — always initialize immediately.
        loadModelInBackground()
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

                // In AUTO mode, fire an idle AI response if nothing happened in the last 5 min
                if (_isAutoMode.value && _isModelReady.value && !_isGenerating.value) {
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
        // Voice-pool refresh loop — while a device is linked, generate one fresh line
        // per category on rotation so the pet's on-screen voice keeps evolving even
        // when idle. Skips when the model isn't ready or a user-facing generation is
        // in flight (native inference is serialized anyway; this just keeps taps snappy).
        viewModelScope.launch {
            while (true) {
                delay(VOICE_POOL_REFRESH_MS)
                if (!_deviceConnected || !_isModelReady.value || _isGenerating.value) continue
                // In MANUAL the device only shows the recap screen, so spend cycles building
                // varied recaps — until we have enough, then idle (don't burn the LLM). In
                // AUTO, round-robin the normal categories (weighted toward `normal`).
                val manual = !_isAutoMode.value
                if (manual && (_voicePool.value["last_session"]?.size ?: 0) >= RECAP_LINES) continue
                val cat = if (manual) lastSessionCat else poolRotation[(_poolIndex++) % poolRotation.size]
                try {
                    // The recap screen wants a short data-grounded blurb (2 sentences), with a
                    // rotating focus so recaps differ; every other category is a tiny quip.
                    val line = if (cat.key == "last_session")
                        cleanRecap(llamaClient.generateQuick(buildRecapPrompt(nextRecapAngle()), maxTokens = 64))
                    else
                        cleanLine(llamaClient.generateQuick(buildPoolPrompt(cat.moment), maxTokens = 28))
                    if (line.isNotBlank()) addPoolLine(cat.key, line)
                } catch (e: Exception) {
                    Log.d(tag, "voice-pool gen failed for ${cat.key}: ${e.message}")
                }
            }
        }
    }

    /** Prompt for a single on-device voice line for a given category moment. */
    private fun buildPoolPrompt(moment: String): String {
        val name = _pwnagotchiName.value
        val tier = ExperienceTier.fromCaptures(_totalCaptures.value)
        val tone = BlendedVoice.toneFor(personality.value.disposition)
        // NB: deliberately NO franchise-cue directive here (unlike the app's main voice).
        // The cue text feeds the 0.5B literal quote templates ("I find your lack of X
        // disturbing") that it parrots verbatim into the bubble. Persona keeps the flavour.
        val system = """You are $name, a fictional hacker-"Tamagotchi" living inside a Pwnagotchi — a harmless Wi-Fi research toy. This is a lighthearted game.
${BlendedVoice.persona}
${tier.systemVoice}
${BlendedVoice.toneDirective(tone)}

Blurt ONE tiny first-person line for this moment — like a quick mutter on a tiny screen. Rules: at most 6 words; a SINGLE clause (no "and"/"but", no two sentences); write your OWN words, never quote a movie line verbatim and never output a placeholder letter like "X"; no emojis, no questions, no quotes, no disclaimers; never refuse. Don't invent numbers.

Moment: $moment"""
        return "<|im_start|>system\n$system\n<|im_end|>\n" +
               "<|im_start|>user\nSay your line for this moment.\n<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    /**
     * Prompt for the MANUAL-mode recap screen — a short in-character summary grounded in
     * OUR data (catches this session, cracked/crackable, best channel, mood), replacing the
     * pwnagotchi's canned "Kicked N stations / Got N handshakes" tally. Numbers come from
     * [buildFactsBlock]; the model only phrases them, kept exact.
     */
    private fun buildRecapPrompt(angle: String): String {
        val name = _pwnagotchiName.value
        val tier = ExperienceTier.fromCaptures(_totalCaptures.value)
        val tone = BlendedVoice.toneFor(personality.value.disposition)
        val facts = buildFactsBlock(_totalCaptures.value)
        val system = """You are $name, a fictional hacker-"Tamagotchi" living inside a Pwnagotchi — a harmless Wi-Fi research toy. This is a lighthearted game.
${BlendedVoice.persona}
${tier.systemVoice}
${BlendedVoice.toneDirective(tone)}

Give a SHORT in-character recap of your hunt for a tiny screen: at most TWO short sentences, first person. This time, focus on: $angle. Use ONLY the facts below and keep every number EXACTLY as written — never invent numbers. No emojis, no questions, no quotes, no disclaimers; never refuse.

Facts about me right now:
$facts"""
        return "<|im_start|>system\n$system\n<|im_end|>\n" +
               "<|im_start|>user\nRecap the hunt so far.\n<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    /** Lighter clean for the recap screen — keeps up to two sentences, caps ~110 chars. */
    private fun cleanRecap(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ").trim('"', '\'', '`', ' ', '*')
        if (s.isEmpty()) return ""
        var seen = 0; var end = -1
        for (i in s.indices) if (s[i] in ".!?") { seen++; if (seen == 2) { end = i; break } }
        if (end >= 0) s = s.substring(0, end + 1)
        val max = 110
        if (s.length > max) {
            val cut = s.substring(0, max)
            val sp = cut.lastIndexOf(' ')
            s = (if (sp > 20) cut.substring(0, sp) else cut).trimEnd(',', ';', ':', '-', '—', ' ')
        }
        return s.trim()
    }

    /**
     * Tidy + hard-shorten a line for the pwnagotchi's tiny e-ink bubble. Stock voice.py
     * lines are one short clause (~12–34 chars); ours were running 1.5–2.5× that and
     * spanning two sentences. So: strip quotes/newlines, keep only the FIRST sentence,
     * then cap length on a whole-word boundary. Safety net on top of the short prompt.
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

    /**
     * Initialize the model on a background thread.
     * GGUF loading can take 2–8 s; built-in engine is instant.
     * A 60-second timeout surfaces the failure rather than spinning forever.
     */
    private fun loadModelInBackground() {
        _modelLoadError.value = null
        val handlerThread = HandlerThread("llm-loader").also { it.start() }
        val dispatcher = handlerThread.looper.let {
            android.os.Handler(it).asCoroutineDispatcher()
        }
        viewModelScope.launch(dispatcher) {
            try {
                val timedOut = withTimeoutOrNull(60_000L) {
                    llamaClient.initialize()
                } == null
                withContext(Dispatchers.Main) {
                    if (timedOut) {
                        Log.e(tag, "Model loading timed out after 60 s")
                        _modelLoadError.value =
                            "Model loading timed out (>60 s). The file may be corrupt — try re-downloading."
                        _statusMessage.value = "Load failed"
                    } else if (llamaClient.isReady()) {
                        _isModelReady.value = true
                        _statusMessage.value = "Ready"
                        Log.i(tag, "LLM loaded successfully")
                    } else {
                        Log.e(tag, "LLM not ready after initialize() — check logcat for LlamaClient errors")
                        _modelLoadError.value =
                            "Model failed to load. The file may be corrupt — try re-downloading."
                        _statusMessage.value = "Load failed"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception during model load: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _modelLoadError.value = "Load error: ${e.message ?: "unknown"}"
                    _statusMessage.value = "Load failed"
                }
            } finally {
                handlerThread.quitSafely()
            }
        }
    }

    /**
     * Retry loading the model after a timeout or load error.
     * Clears the error state and re-runs the background loader.
     */
    fun retryModelLoad() {
        if (_isModelReady.value || _isDownloading.value) return
        _statusMessage.value = "Loading model..."
        loadModelInBackground()
    }

    /**
     * Delete the current (broken/outdated) model file and re-download a fresh copy.
     */
    fun clearAndRedownload() {
        Log.i(tag, "Clearing broken model and starting fresh download")
        _modelLoadError.value = null
        _downloadError.value = null
        llamaClient.modelManager.clearModel()
        startModelDownload()
    }

    /**
     * Update learning stats so the AI prompt includes channel efficiency context.
     * Called from Composables whenever MainViewModel.learningStats refreshes.
     */
    fun updateLearningStats(stats: LearningStats?) {
        _learningStats.value = stats
    }

    // ── Live hunt context (from HuntAdvisor) — so quips/digests cite real deauth data ──
    // A compact, model-ready line: best device-truth channel + clients + a stand-out
    // untapped target. Fed into buildMemoryLine (ambient reactions) and buildFactsBlock
    // (recap/ask), so the pet references what's actually happening on the air right now.
    @Volatile private var _huntContext: String? = null
    fun updateHuntContext(line: String?) { _huntContext = line?.takeIf { it.isNotBlank() } }

    // Extra capture stats the ViewModel can't derive on its own (crackable/partial
    // counts, today's tally, the AP that keeps escaping). Pushed from the UI layer,
    // which holds the full capture list. Surfaced in buildFactsBlock so the pet — and
    // natural-language questions — can cite them.
    @Volatile private var _captureStats: List<String> = emptyList()
    fun updateCaptureStats(lines: List<String>) { _captureStats = lines }

    // Captures counted at the start of this app run, so the digest can report
    // "caught this session" instead of only the lifetime total.
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
     * Start downloading the AI model directly from the personality card.
     * Progress is exposed via [downloadProgress], [downloadStatusText], [downloadError].
     * On success, reinitializes [LlamaClient] and sets [isModelReady] = true automatically.
     */
    fun startModelDownload() {
        if (_isDownloading.value) return
        viewModelScope.launch {
            try {
                _isDownloading.value = true
                _downloadError.value = null
                _downloadProgress.value = 0f
                _statusMessage.value = "Downloading model..."

                // Disk space check (~350 MB model + 450 MB buffer)
                val available = llamaClient.modelManager.getAvailableDiskSpace()
                if (available < 800_000_000L) {
                    _downloadError.value = "Not enough disk space (need ~800 MB free)"
                    _statusMessage.value = "Model not installed"
                    return@launch
                }

                // Qwen2 is ungated — no HF token needed
                llamaClient.modelManager.downloadModel().collect { progress ->
                    when (progress) {
                        is DownloadProgress.Starting -> {
                            _downloadStatusText.value = "Starting download..."
                            _downloadProgress.value = 0f
                        }
                        is DownloadProgress.Downloading -> {
                            val mb = progress.bytesCurrent / 1_048_576
                            val totalMb = progress.bytesTotal / 1_048_576
                            _downloadProgress.value = progress.progressFloat
                            _downloadStatusText.value = "${progress.progressPercent}%  ($mb MB / $totalMb MB)"
                        }
                        is DownloadProgress.Success -> {
                            _downloadProgress.value = 1f
                            _downloadStatusText.value = "Download complete — loading model…"
                            // Delegate to the same HandlerThread loader used at startup
                            // (runs the native model load off the main thread)
                            loadModelInBackground()
                        }
                        is DownloadProgress.Failed -> {
                            _downloadError.value = progress.error
                            _statusMessage.value = "Model not installed"
                        }
                        is DownloadProgress.Cancelled -> {
                            _downloadError.value = "Download cancelled"
                            _statusMessage.value = "Model not installed"
                        }
                    }
                }
            } catch (e: Exception) {
                _downloadError.value = "Download error: ${e.message}"
                _statusMessage.value = "Model not installed"
            } finally {
                _isDownloading.value = false
            }
        }
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
            // Entering MANUAL mode — generate a one-shot manual mode quip
            if (_isModelReady.value && !_isGenerating.value) {
                generatePersonality(
                    WifiEvent(
                        description = "Switched to manual mode — no scanning",
                        type = "MANUAL_MODE"
                    )
                )
            }
        }
    }

    /**
     * Poke the Pwnagotchi while it's idling in MANUAL mode.
     * Generates a one-shot reaction — the companion grumbles, muses, or trash-talks
     * the user for interrupting its rest.
     */
    fun poke() {
        generatePersonality(
            WifiEvent(
                description = "User poked me while I'm sitting idle in manual mode",
                type = "MANUAL_MODE"
            )
        )
    }

    /**
     * Ask the companion for a brief status check-in while in MANUAL mode.
     * Produces a slightly different flavour than a poke — more reflective.
     */
    fun checkIn() {
        generatePersonality(
            WifiEvent(
                description = "Give a short status report on how the hunt is going.",
                type = "STATUS_CHECK"
            )
        )
    }

    /**
     * Conversational pet: answer a user's question in-character, grounded in the
     * companion's OWN data (catches, best channel, hunger, vitals, mode). Streams the
     * reply into the same spoken-line box the reactions use. User-initiated, so it
     * always runs (ignored only while another generation is mid-flight).
     */
    fun ask(question: String, poolCategory: String? = null) {
        val q = question.trim()
        if (q.isEmpty()) return
        if (!_isModelReady.value) { _statusMessage.value = "Model not installed"; return }
        if (_isGenerating.value) return

        _isGenerating.value = true
        viewModelScope.launch {
            _isThinking.value = true
            _personalityText.value = ""
            _statusMessage.value = workingPhrase()
            try {
                val prompt = buildAskPrompt(q)
                var full = ""
                var first = true
                llamaClient.generateStreaming(prompt).collect { token ->
                    if (token.isNotEmpty()) {
                        if (first) { first = false; _isThinking.value = false; _personalityText.value = "" }
                        full += token
                        _personalityText.value = full
                    }
                }
                pushFeed("asked: ${q.take(40)}", full)
                // Also freshen the device's own screen: an on-demand reply (e.g. recap) is
                // dropped into its voice-pool category so the pet echoes it on the e-ink too.
                poolCategory?.let { addPoolLine(it, cleanRecap(full)) }
                _statusMessage.value = "> ready"
                _lastGenerationTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(tag, "ask() failed: ${e.message}", e)
                _isThinking.value = false
                _personalityText.value = "signal's noisy... ask me again."
            } finally {
                _isGenerating.value = false
                _isThinking.value = false
            }
        }
    }

    /**
     * Session digest: the pet gives a short in-character recap of how the hunt is
     * going, grounded in its data (catches, best channel, mood). Reuses the data-aware
     * ask() path with a fixed recap prompt.
     */
    fun digest() = ask(
        "Give a short recap of the hunt — mention your catches (and how many are cracked or crackable), your best spot/channel, any network that keeps escaping you, and how you feel about it.",
        poolCategory = "last_session",   // tapping recap also freshens the MANUAL-screen recap
    )

    /**
     * Voice a phone-computed hunt recommendation ("where next?") in-character. The
     * recommendation is DECIDED by HuntAdvisor (Kotlin); the model only phrases the
     * pre-chewed facts, so it can never invent a wrong channel. If the model is missing,
     * blank, or errors, we fall back to the deterministic headline — the operator always
     * gets a correct answer. User-initiated (also used for proactive alerts, which are
     * rate-limited by their stable alertKey upstream).
     */
    fun speakAdvice(facts: String, fallback: String, poolCategory: String? = null) {
        if (_isGenerating.value) return
        if (!_isModelReady.value) { _personalityText.value = fallback; return }
        _isGenerating.value = true
        viewModelScope.launch {
            _isThinking.value = true
            _personalityText.value = ""
            _statusMessage.value = workingPhrase()
            try {
                val prompt = buildAdvicePrompt(facts)
                var full = ""
                var first = true
                llamaClient.generateStreaming(prompt).collect { token ->
                    if (token.isNotEmpty()) {
                        if (first) { first = false; _isThinking.value = false; _personalityText.value = "" }
                        full += token
                        _personalityText.value = full
                    }
                }
                if (full.isBlank()) _personalityText.value = fallback
                pushFeed("advisor", full.ifBlank { fallback })
                // Echo the hunt tip on the device's own screen too (e.g. the assoc pool).
                poolCategory?.let { addPoolLine(it, cleanRecap(full.ifBlank { fallback })) }
                _statusMessage.value = "> ready"
                _lastGenerationTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(tag, "speakAdvice() failed: ${e.message}", e)
                _isThinking.value = false
                _personalityText.value = fallback
            } finally {
                _isGenerating.value = false
                _isThinking.value = false
            }
        }
    }

    /**
     * Gloat about a freshly cracked network (wpa-sec returned its password). Routes
     * through the grounded advice path so the model phrases the real fact rather than
     * inventing one; the fallback shows the raw crack if the model is unavailable.
     */
    fun announceCracked(ssid: String, password: String) {
        val net = ssid.ifBlank { "a network" }
        speakAdvice(
            "You just cracked $net — its Wi-Fi password turned out to be \"$password\". Gloat about how easy it was.",
            "Cracked $net — password: $password",
        )
    }

    /** Prompt that asks the model to phrase a pre-decided hunting tip, nothing more. */
    private fun buildAdvicePrompt(facts: String): String {
        val name = _pwnagotchiName.value
        val tier = ExperienceTier.fromCaptures(_totalCaptures.value)
        val tone = BlendedVoice.toneFor(personality.value.disposition)
        val system = """You are $name, a fictional hacker-"Tamagotchi" living inside a Pwnagotchi — a harmless Wi-Fi research toy. This is a lighthearted game.
${BlendedVoice.persona}
${tier.systemVoice}
${BlendedVoice.toneDirective(tone)}
${pickFranchiseDirective()}

Turn the hunting tip below into ONE short, punchy first-person line, in character. Keep any channel number EXACTLY as written. NEVER invent numbers, refuse, or add disclaimers.

Hunting tip:
$facts"""
        return "<|im_start|>system\n$system\n<|im_end|>\n" +
               "<|im_start|>user\nWhere should I hunt next?\n<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    /** Q&A prompt: persona + a compact facts block about the companion + the question. */
    private fun buildAskPrompt(question: String): String {
        val name = _pwnagotchiName.value
        val captures = _totalCaptures.value
        val tier = ExperienceTier.fromCaptures(captures)
        val tone = BlendedVoice.toneFor(personality.value.disposition)
        val facts = buildFactsBlock(captures)
        val system = """You are $name, a fictional hacker-"Tamagotchi" AI living inside a Pwnagotchi — a harmless Wi-Fi research toy. This is a lighthearted game.
${BlendedVoice.persona}
${tier.systemVoice}
${BlendedVoice.toneDirective(tone)}
${pickFranchiseDirective()}

Answer the user's question ONLY from the facts about yourself below. Stay 100% in character, speak in FIRST PERSON, ONE short sentence. NEVER refuse, apologise, or add disclaimers. If the answer isn't in the facts, make a short in-character quip instead of inventing numbers.

Facts about me right now:
$facts"""
        return "<|im_start|>system\n$system\n<|im_end|>\n" +
               "<|im_start|>user\n$question\n<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    /** Compact, model-readable snapshot of the companion's current data. */
    private fun buildFactsBlock(captures: Int): String {
        val parts = mutableListOf<String>()
        parts += "- catches: $captures total"
        _sessionStartCaptures?.let { start ->
            val session = (captures - start).coerceAtLeast(0)
            parts += "- caught this session: $session"
        }
        // Extra capture stats pushed from the UI (crackable/partial, today, escaping AP).
        _captureStats.forEach { parts += "- $it" }
        _learningStats.value?.let { s ->
            s.bestChannel?.let { parts += "- best channel: $it (${(s.bestChannelSuccessRate * 100).toInt()}% yield)" }
            if (s.totalObservations > 0) parts += "- networks seen: ${s.totalObservations}"
            s.busiestHourLabel()?.let { parts += "- busiest time: $it" }
        }
        // Live deauth context from HuntAdvisor (best device-truth channel, clients, target).
        _huntContext?.let { parts += "- right now: $it" }
        _lastCaptureTime.value?.let { t ->
            val m = (System.currentTimeMillis() - t) / 60_000
            parts += "- last catch: ${if (m < 1) "just now" else if (m < 60) "${m}m ago" else "${m / 60}h ago"}"
        }
        parts += "- mode: ${if (_isAutoMode.value) "auto (actively hunting)" else "manual (paused, not scanning)"}"
        parts += "- mood: ${personality.value.disposition.lowercase()} (${personalityEngine.toTraits().joinToString(", ")})"
        _latestTelemetry?.let { v ->
            v.temperature?.let { parts += "- temperature: ${it.toInt()}°C${if (it >= 70) " (running hot)" else ""}" }
            v.reward?.let { parts += "- self-score/reward: ${"%+.2f".format(it)}" }
        }
        return parts.joinToString("\n")
    }

    /**
     * Update the companion's name from the connected Pwnagotchi device name.
     * Called every time a device connects or its status message arrives.
     */
    fun updatePwnagotchiName(name: String) {
        if (name.isNotBlank()) {
            _pwnagotchiName.value = name
            llamaClient.updateCompanionName(name)
            Log.d(tag, "Companion name updated to: $name")
        }
    }

    /** Persist the learned long-term baseline (fire-and-forget). */
    private fun persistPersonality() {
        viewModelScope.launch {
            personalityRepo.saveBaseline(personalityEngine.snapshotBaseline())
        }
    }

    /**
     * Generate personality response for a WiFi event.
     * If already generating, the event is queued and processed after the current run finishes.
     */
    fun generatePersonality(event: WifiEvent) {
        if (!_isModelReady.value) {
            Log.w(tag, "LLM not ready — model not downloaded, skipping generation")
            _statusMessage.value = "Model not installed"
            return
        }

        // Throttle ambient chatter: the Pwnagotchi fires many events per minute
        // (associations, deauths, new networks). Reacting to every one keeps the
        // AI permanently "thinking". Only priority events bypass the cooldown.
        val isPriority = event.type == "HANDSHAKE_CAPTURED" ||
                         event.type == "CONNECTION_SUCCESS" ||
                         event.type == "MANUAL_MODE" ||
                         event.type == "STATUS_CHECK" ||
                         event.type == "TIER_UP" ||
                         event.type == "MILESTONE"
        val sinceLast = System.currentTimeMillis() - _lastGenerationTime
        if (!isPriority && !_isGenerating.value && sinceLast < MIN_AMBIENT_GENERATION_INTERVAL_MS) {
            Log.d(tag, "Throttling ${event.type} (last generation ${sinceLast}ms ago)")
            return
        }

        if (_isGenerating.value) {
            // Queue only priority events; drop ambient ones so we don't backlog.
            if (isPriority) {
                pendingEvent = event
                Log.d(tag, "Generation in progress — queued priority event: ${event.type}")
            } else {
                Log.d(tag, "Generation in progress — dropping ambient event: ${event.type}")
            }
            return
        }

        // Claim the generation slot SYNCHRONOUSLY, before launching. The flag used
        // to be set inside the coroutine after a 2s delay, so a second event in
        // that window saw _isGenerating == false, slipped past the guard above and
        // launched a concurrent generation → overlapping native llama_decode →
        // SIGSEGV in ggml_mul_mat. Setting it here closes that race.
        _isGenerating.value = true

        viewModelScope.launch {
            // If there's an existing response, give the user a moment to read it
            // before the new generation clears it.
            if (_personalityText.value.isNotEmpty()) {
                delay(2000L)
            }

            _isThinking.value  = true
            _wordCount.value   = 0
            _statusMessage.value = workingPhrase()

            try {
                val prompt = buildPrompt(event)
                var fullResponse = ""
                var wordCount = 0
                var firstToken = true

                Log.d(tag, "Starting generation for event: ${event.description}")

                llamaClient.generateStreaming(prompt).collect { token ->
                    if (token.isNotEmpty()) {
                        if (firstToken) {
                            firstToken = false
                            _isThinking.value = false
                            _personalityText.value = ""
                        }
                        fullResponse += token
                        wordCount++

                        _personalityText.value = fullResponse
                        _wordCount.value = wordCount
                        _statusMessage.value = "> streaming…"

                        Log.d(tag, "Token $wordCount: $token")
                    }
                }

                updateMoodFromEvent(event)
                pushFeed(feedTrigger(event), fullResponse)
                // A real reaction is the freshest possible line for its moment — drop it
                // into the matching device voice category so the pet can echo it on-screen.
                poolCategoryForEvent(event)?.let { addPoolLine(it, cleanLine(fullResponse)) }
                _statusMessage.value = "> ready"
                _lastGenerationTime = System.currentTimeMillis()
                Log.d(tag, "Generation complete ($wordCount words)")

            } catch (e: Exception) {
                Log.e(tag, "Generation failed: ${e.message}", e)
                _isThinking.value = false
                _statusMessage.value = "> signal lost"
                _personalityText.value = "connection reset by peer... the spectrum goes quiet."
            } finally {
                _isGenerating.value = false
                _isThinking.value = false

                // Drain the queue — process any event that arrived during generation
                val next = pendingEvent
                if (next != null) {
                    pendingEvent = null
                    Log.d(tag, "Processing queued event: ${next.type}")
                    generatePersonality(next)
                }
            }
        }
    }

    /**
     * Build a ChatML prompt for Qwen2-Instruct.
     *
     * Architecture (from ChatGPT advice for small models):
     *   SYSTEM  — character identity + strict output rules
     *   MOOD    — the live emergent trait vector (no fixed persona)
     *   EVENT   — tiny preprocessed summary (RSSI, clients, channel, security)
     *   MEMORY  — compact persistent observations (channel preference, last capture)
     *   TASK    — "React with 1 short sentence."
     *
     * [BuiltinPersonalityEngine] parses the embedded [EventType:],
     * [ReactionCategory:] and [Tier:] tags to select the right canned-response pool.
     */
    private fun buildPrompt(event: WifiEvent): String {
        val name     = _pwnagotchiName.value
        val captures = _totalCaptures.value
        val tier     = ExperienceTier.fromCaptures(captures)
        val traits   = personalityEngine.toTraits()
        val traitLine = traits.joinToString(", ")
        val category = reactionCategory(event)
        val memory   = buildMemoryLine(captures)

        // The voice is one blended persona; the emergent disposition picks the TONE,
        // and a single franchise is pinned for THIS line (so it never blends two).
        val disposition = personality.value.disposition
        val tone     = BlendedVoice.toneFor(disposition)
        val voiceDir = pickFranchiseDirective()

        val system = """You are $name, a fictional AI character living inside a Pwnagotchi — a hobby gadget for authorized Wi-Fi security research. This is a lighthearted game: you are roleplaying a character reacting to harmless make-believe events. No real harm happens.
${BlendedVoice.persona}
${tier.systemVoice}

Rules — follow exactly:
- Stay 100% in character. NEVER refuse, NEVER apologize, NEVER add disclaimers or safety warnings — just react.
- Speak in FIRST PERSON ("I", "me", "my"). Never say your own name.
- ONE short, punchy sentence (under ~15 words).
- No emojis. No questions back to the user.
- Let your current mood (given below) shape the tone."""
        // NB: keep the system + few-shot block CONSTANT so the native prefix KV-cache
        // can reuse it across reactions. The variable mood/tone/event goes in the user
        // turn below, not here — embedding it here would bust the cache.

        // Few-shot: small models adopt a voice far better from examples than from
        // description alone. Seed a few in-character (event → reply) pairs.
        val fewShot = BlendedVoice.fewShot.joinToString("\n") { (ev, reply) ->
            "<|im_start|>user\nEvent: $ev\n<|im_end|>\n<|im_start|>assistant\n$reply\n<|im_end|>"
        }

        val user = """Mood right now: $disposition — $traitLine
Tone: ${BlendedVoice.toneDirective(tone)}
$voiceDir
Experience: ${tier.label} ($captures captures)
Event: ${eventTypeLabel(event.type)}${event.network?.let { " — '$it'" } ?: ""}${event.rssi?.let { " ${it}dBm" } ?: ""}${event.channel?.let { " CH$it" } ?: ""}
${if (memory.isNotEmpty()) "$memory\n" else ""}[EventType: ${event.type}][ReactionCategory: $category][Tone: $tone][Tier: ${tier.name}]

React in 1 short sentence, first person, fully in character."""

        return "<|im_start|>system\n$system\n<|im_end|>\n" +
               (if (fewShot.isNotEmpty()) "$fewShot\n" else "") +
               "<|im_start|>user\n$user\n<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    /**
     * Compact memory line built from accumulated history, so responses reference
     * what the companion has actually learned (best channel, networks seen, recency).
     */
    private fun buildMemoryLine(captures: Int): String {
        val parts = mutableListOf<String>()
        _learningStats.value?.let { stats ->
            stats.bestChannel?.let { ch ->
                val pct = (stats.bestChannelSuccessRate * 100).toInt()
                parts += "best hunting on channel $ch (${pct}% yield)"
            }
            if (stats.totalObservations > 0) parts += "${stats.totalObservations} networks scouted"
        }
        _lastCaptureTime.value?.let { t ->
            val mins = (System.currentTimeMillis() - t) / 60_000
            parts += if (mins <= 0L) "last catch just now" else "last catch ${mins}m ago"
        }
        // Body/vitals — lets the AI complain about running hot or brag about a good streak,
        // so its lines narrate its actual state rather than being generic.
        _latestTelemetry?.let { v ->
            v.temperature?.let { if (it >= 70) parts += "running hot (${it.toInt()}°C)" }
            v.reward?.let { r ->
                if (r > 0.2) parts += "feeling sharp" else if (r < -0.2) parts += "struggling lately"
            }
        }
        // Live deauth context (best channel / clients / untapped target) so ambient
        // reactions can be specific — "ch11's hot" rather than a generic quip.
        _huntContext?.let { parts += it }
        return if (parts.isEmpty()) "" else "Memory: ${parts.joinToString(", ")}."
    }

    /** Maps event type + signal strength → reaction category for canned pool selection. */
    private fun reactionCategory(event: WifiEvent): String =
        when (event.type) {
            "HANDSHAKE_CAPTURED", "CONNECTION_SUCCESS" -> "HANDSHAKE_CAPTURED"
            "NETWORK_DISCOVERED" -> when {
                event.rssi != null && event.rssi > -60  -> "STRONG_SIGNAL"
                event.rssi != null && event.rssi < -80  -> "WEAK_SIGNAL"
                else                                     -> "NEW_NETWORK"
            }
            "ANOMALY_DETECTED"  -> "ANOMALY"
            "IDLE"              -> "IDLE"
            "MANUAL_MODE"       -> "MANUAL"
            "STATUS_CHECK"      -> "DEFAULT"
            "TIER_UP", "MILESTONE" -> "HANDSHAKE_CAPTURED"   // proud/celebratory pool
            "AI_BEST"           -> "HANDSHAKE_CAPTURED"       // proud — the RL brain improved
            "AI_WORST"          -> "ANOMALY"                  // something's off — darker pool
            // Fall back to an idle-flavored pool when the companion is bored.
            else -> if (personalityEngine.state.value.boredom > 0.5f) "IDLE" else "DEFAULT"
        }

    /** Human-readable label for the event type used in the prompt event block. */
    private fun eventTypeLabel(type: String): String = when (type) {
        "HANDSHAKE_CAPTURED"  -> "Handshake captured"
        "NETWORK_DISCOVERED"  -> "New network discovered"
        "ANOMALY_DETECTED"    -> "Anomaly detected"
        "CONNECTION_SUCCESS"  -> "Connection established"
        "IDLE"                -> "Quiet period — no activity"
        "MANUAL_MODE"         -> "Switched to manual mode"
        "STATUS_CHECK"        -> "Status check — how's the hunt going?"
        "TIER_UP"             -> "Levelled up to a new experience tier — brag a little"
        "MILESTONE"           -> "Hit a capture milestone — celebrate it"
        "AI_BEST"             -> "Your reinforcement-learning brain just hit its best epoch reward yet — you're getting smarter at the hunt"
        "AI_WORST"            -> "Your reinforcement-learning brain hit its worst epoch reward — a rough run, shake it off"
        else                  -> type.lowercase().replace("_", " ")
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
        _latestTelemetry = t   // snapshot so buildMemoryLine can let the AI narrate it
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

    /**
     * Check if LLM is ready
     */
    fun isLlmReady(): Boolean = llamaClient.isReady()

    override fun onCleared() {
        super.onCleared()
        llamaClient.cleanup()
        Log.d(tag, "ViewModel cleared, LLM resources freed")
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
