package com.wsvdmeer.pwncompanion.ai

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Lightweight personality state machine.
 *
 * Maintains 6 floating-point personality variables (0.0–1.0) that evolve
 * based on WiFi events, decay toward baseline over time, and get compressed
 * into short trait strings that drive the pet's disposition + tone.
 *
 * Architecture:
 *
 *   Rule engine  → applyEvent() updates variables
 *       ↓
 *   State compressor → toTraits() converts numbers to 3–5 trait strings
 *       ↓
 *   Disposition → strongest trait picks the mood/tone (e.g. "highly confident, mildly irritated")
 *       ↓
 *   Voice → that mood selects a curated corpus line (deterministic — no model)
 *
 * Variables and their meaning:
 *   confidence   — rises with captures, falls with failures
 *   curiosity    — rises with new networks, decays slowly
 *   frustration  — rises with anomalies/failures, decays over time
 *   energy       — rises with activity, drops during idle
 *   ego          — rises with capture streaks, falls rarely
 *   boredom      — rises during idle, reset by any interesting event
 */
class PersonalityStateEngine {

    private val tag = "PersonalityState"

    // ── State ─────────────────────────────────────────────────────────────────

    @Serializable
    data class PersonalityState(
        val confidence:  Float = 0.60f,
        val curiosity:   Float = 0.65f,
        val frustration: Float = 0.15f,
        val energy:      Float = 0.70f,
        val ego:         Float = 0.50f,
        val boredom:     Float = 0.10f,
    ) {
        /** Returns a compact debug string for logging. */
        override fun toString(): String =
            "conf=%.2f cur=%.2f frust=%.2f energy=%.2f ego=%.2f bore=%.2f"
                .format(confidence, curiosity, frustration, energy, ego, boredom)
    }

    private val _state = MutableStateFlow(PersonalityState())
    val state = _state.asStateFlow()

    // ── Event delta rules ─────────────────────────────────────────────────────
    //
    // Each rule is: variable → delta to add (positive = increase, negative = decrease)
    // Rules are additive and clamped to [0.0, 1.0] after application.

    private val eventDeltas: Map<String, Map<String, Float>> = mapOf(

        "HANDSHAKE_CAPTURED" to mapOf(
            "confidence"  to +0.08f,
            "ego"         to +0.06f,
            "energy"      to +0.04f,
            "frustration" to -0.07f,
            "boredom"     to -0.08f,
        ),

        "CONNECTION_SUCCESS" to mapOf(
            "confidence"  to +0.06f,
            "ego"         to +0.04f,
            "frustration" to -0.05f,
            "boredom"     to -0.06f,
        ),

        "NETWORK_DISCOVERED" to mapOf(
            "curiosity"   to +0.07f,
            "energy"      to +0.03f,
            "boredom"     to -0.05f,
        ),

        "ANOMALY_DETECTED" to mapOf(
            "frustration" to +0.09f,
            "curiosity"   to +0.04f,
            "energy"      to +0.02f,
            "boredom"     to -0.03f,
        ),

        "CONNECTION_FAILED" to mapOf(
            "frustration" to +0.12f,
            "confidence"  to -0.05f,
            "ego"         to -0.04f,
        ),

        "IDLE" to mapOf(
            "boredom"     to +0.10f,
            "energy"      to -0.07f,
            "frustration" to +0.02f,
        ),
    )

    // ── Learned baseline — the long-term "settled" personality ────────────────
    //
    // State decays back toward this baseline (not a fixed constant), and the
    // baseline itself slowly drifts toward the lived state via learn(). So a
    // Pwnagotchi that is usually capturing develops a permanently higher
    // confidence/ego baseline; one that mostly idles trends bored and low-energy.
    // This is what makes the personality *learn* rather than reset. It is
    // persisted across restarts (see snapshotBaseline/restoreBaseline).
    private val factoryBaseline = PersonalityState(
        confidence  = 0.60f,
        curiosity   = 0.60f,
        frustration = 0.15f,
        energy      = 0.70f,
        ego         = 0.50f,
        boredom     = 0.10f,
    )

    @Volatile
    private var learnedBaseline: PersonalityState = factoryBaseline

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Apply event-driven deltas to personality variables.
     * Call this whenever a WiFi event occurs.
     */
    fun applyEvent(eventType: String) {
        val deltas = eventDeltas[eventType] ?: return
        val s = _state.value
        _state.value = PersonalityState(
            confidence  = (s.confidence  + (deltas["confidence"]  ?: 0f)).clamp(),
            curiosity   = (s.curiosity   + (deltas["curiosity"]   ?: 0f)).clamp(),
            frustration = (s.frustration + (deltas["frustration"] ?: 0f)).clamp(),
            energy      = (s.energy      + (deltas["energy"]      ?: 0f)).clamp(),
            ego         = (s.ego         + (deltas["ego"]         ?: 0f)).clamp(),
            boredom     = (s.boredom     + (deltas["boredom"]     ?: 0f)).clamp(),
        )
        Log.d(tag, "After $eventType: ${_state.value}")
    }

    /**
     * Apply time-based idle penalty.
     * Call this periodically when there is no activity (every few minutes).
     */
    fun applyIdle() = applyEvent("IDLE")

    /**
     * Apply trait nudges derived from the *device's own* reported mood, so the
     * companion's emergent personality reacts to how the Pwnagotchi feels — not
     * just to discrete WiFi events. Each call also feeds the learned baseline.
     */
    fun applyDeviceMood(rawMood: String) {
        val deltas = deviceMoodDeltas[rawMood.uppercase().trim()] ?: return
        val s = _state.value
        _state.value = PersonalityState(
            confidence  = (s.confidence  + (deltas["confidence"]  ?: 0f)).clamp(),
            curiosity   = (s.curiosity   + (deltas["curiosity"]   ?: 0f)).clamp(),
            frustration = (s.frustration + (deltas["frustration"] ?: 0f)).clamp(),
            energy      = (s.energy      + (deltas["energy"]      ?: 0f)).clamp(),
            ego         = (s.ego         + (deltas["ego"]         ?: 0f)).clamp(),
            boredom     = (s.boredom     + (deltas["boredom"]     ?: 0f)).clamp(),
        )
        learn()
        Log.d(tag, "After device mood '$rawMood': ${_state.value}")
    }

    /**
     * Nudge all variables back toward the learned baseline (natural decay) and
     * then let the baseline drift a little toward the lived state (slow learning).
     * Call this after every event so extremes don't persist forever, but lasting
     * tendencies still accumulate.
     * [factor] controls decay speed (0.03 = 3% step toward baseline).
     */
    fun decay(factor: Float = 0.03f) {
        val s = _state.value
        _state.value = PersonalityState(
            confidence  = lerp(s.confidence,  learnedBaseline.confidence,  factor),
            curiosity   = lerp(s.curiosity,   learnedBaseline.curiosity,   factor),
            frustration = lerp(s.frustration, learnedBaseline.frustration, factor),
            energy      = lerp(s.energy,      learnedBaseline.energy,      factor),
            ego         = lerp(s.ego,         learnedBaseline.ego,         factor),
            boredom     = lerp(s.boredom,     learnedBaseline.boredom,     factor),
        )
        learn()
    }

    /**
     * Slowly move the learned baseline toward the current lived state. This is the
     * long-term memory of "what this Pwnagotchi is usually like". [rate] is tiny so
     * it takes many events to meaningfully shift — a real disposition, not a mood.
     */
    fun learn(rate: Float = 0.004f) {
        val s = _state.value
        learnedBaseline = PersonalityState(
            confidence  = lerp(learnedBaseline.confidence,  s.confidence,  rate),
            curiosity   = lerp(learnedBaseline.curiosity,   s.curiosity,   rate),
            frustration = lerp(learnedBaseline.frustration, s.frustration, rate),
            energy      = lerp(learnedBaseline.energy,      s.energy,      rate),
            ego         = lerp(learnedBaseline.ego,         s.ego,         rate),
            boredom     = lerp(learnedBaseline.boredom,     s.boredom,     rate),
        )
    }

    /** The learned long-term baseline, for persistence. */
    fun snapshotBaseline(): PersonalityState = learnedBaseline

    /** Restore a previously-persisted learned baseline and seed live state from it. */
    fun restoreBaseline(state: PersonalityState) {
        learnedBaseline = state
        _state.value = state
        Log.i(tag, "Restored learned personality baseline: $state")
    }

    /**
     * Fold the device's own per-epoch telemetry into the personality. This is the
     * richest emergent-mood signal: [reward] is pwnagotchi's RL self-score, the
     * *_for_epochs counters are how long it's been in each emotional state, and
     * temperature/cpuLoad act as a "running hot / overworked" stress input.
     *
     * Deltas are deliberately tiny — this fires every epoch (~60s), so lasting
     * tendencies accumulate while a single epoch barely moves the needle. Feeds
     * the learned baseline via learn().
     */
    fun applyTelemetry(
        reward: Float? = null,
        temperature: Float? = null,
        cpuLoad: Float? = null,
        activeForEpochs: Int? = null,
        inactiveForEpochs: Int? = null,
        boredForEpochs: Int? = null,
        sadForEpochs: Int? = null,
        blindForEpochs: Int? = null,
    ) {
        val s = _state.value
        var dConf = 0f; var dCur = 0f; var dFrust = 0f
        var dEnergy = 0f; var dEgo = 0f; var dBore = 0f

        // reward: positive = doing well → confident/proud; negative = struggling.
        reward?.let { r ->
            val x = r.coerceIn(-2f, 2f) * 0.02f
            dConf += x; dEgo += x * 0.6f; dFrust -= x
        }
        // Stress: a hot or pegged Pi reads as irritable and drained.
        temperature?.let { if (it >= 70f) { dFrust += 0.02f; dEnergy -= 0.02f } }
        cpuLoad?.let { if (it >= 0.85f) { dFrust += 0.015f; dEnergy -= 0.015f } }

        // Behavioural counters → saturating nudges (5 epochs ≈ full weight).
        fun sat(n: Int?, k: Int = 5) = ((n ?: 0).coerceAtLeast(0).toFloat() / k).coerceAtMost(1f)
        val act = sat(activeForEpochs)
        val inact = sat(inactiveForEpochs)
        val bore = sat(boredForEpochs)
        val sad = sat(sadForEpochs)
        val blind = sat(blindForEpochs)

        dEnergy += act * 0.02f - inact * 0.02f - sad * 0.015f
        dConf += act * 0.015f - sad * 0.02f
        dBore += (inact + bore) * 0.015f - act * 0.02f
        dFrust += blind * 0.02f
        dCur += blind * 0.01f   // can't see anything → restless to explore

        _state.value = PersonalityState(
            confidence  = (s.confidence  + dConf).clamp(),
            curiosity   = (s.curiosity   + dCur).clamp(),
            frustration = (s.frustration + dFrust).clamp(),
            energy      = (s.energy      + dEnergy).clamp(),
            ego         = (s.ego         + dEgo).clamp(),
            boredom     = (s.boredom     + dBore).clamp(),
        )
        learn()
        Log.d(tag, "After telemetry (reward=$reward): ${_state.value}")
    }

    private val deviceMoodDeltas: Map<String, Map<String, Float>> = mapOf(
        "HAPPY"       to mapOf("confidence" to +0.05f, "energy" to +0.04f, "boredom" to -0.04f),
        // The device caught something / is doing well — proud and content.
        "GRATEFUL"    to mapOf("confidence" to +0.05f, "ego" to +0.04f, "frustration" to -0.04f, "boredom" to -0.04f),
        // The device is annoyed (e.g. deauth spam, no results) — irritable and wired.
        "ANGRY"       to mapOf("frustration" to +0.07f, "ego" to +0.04f, "energy" to +0.04f),
        "EXCITED"     to mapOf("energy" to +0.06f, "curiosity" to +0.05f, "boredom" to -0.05f),
        "MOTIVATED"   to mapOf("confidence" to +0.04f, "energy" to +0.04f),
        "COOL"        to mapOf("confidence" to +0.05f, "ego" to +0.04f, "frustration" to -0.03f),
        "AGGRESSIVE"  to mapOf("ego" to +0.06f, "frustration" to +0.05f, "energy" to +0.04f),
        "SMART"       to mapOf("confidence" to +0.05f, "curiosity" to +0.04f),
        "INTENSE"     to mapOf("energy" to +0.05f, "frustration" to +0.03f, "curiosity" to +0.03f),
        "LONELY"      to mapOf("energy" to -0.05f, "boredom" to +0.05f),
        "SAD"         to mapOf("confidence" to -0.05f, "energy" to -0.05f, "boredom" to +0.04f),
        "DEMOTIVATED" to mapOf("confidence" to -0.06f, "energy" to -0.05f),
        "BORED"       to mapOf("boredom" to +0.08f, "energy" to -0.05f),
    )

    /**
     * Convert the current state into 3–5 short trait strings that drive the disposition/tone.
     *
     * Priority order ensures the most extreme/interesting traits surface first.
     * Returns ["neutral"] if nothing is particularly pronounced.
     */
    fun toTraits(): List<String> {
        val s = _state.value
        val traits = mutableListOf<Pair<Float, String>>()

        // Frustration — highest priority (shapes tone most dramatically)
        when {
            s.frustration > 0.75f -> traits += 0.90f to "aggressively sarcastic"
            s.frustration > 0.50f -> traits += 0.70f to "mildly irritated"
        }

        // Ego / confidence combo
        when {
            s.ego > 0.80f && s.confidence > 0.75f -> traits += 0.85f to "insufferably cocky"
            s.ego > 0.70f                          -> traits += 0.65f to "cocky"
            s.confidence > 0.80f                   -> traits += 0.75f to "highly confident"
            s.confidence > 0.60f                   -> traits += 0.55f to "confident"
            s.confidence < 0.30f                   -> traits += 0.60f to "uncertain"
        }

        // Boredom
        when {
            s.boredom > 0.70f -> traits += 0.80f to "dismissive"
            s.boredom > 0.45f -> traits += 0.50f to "mildly bored"
        }

        // Curiosity
        when {
            s.curiosity > 0.85f -> traits += 0.70f to "intensely curious"
            s.curiosity > 0.65f -> traits += 0.45f to "curious"
        }

        // Energy
        when {
            s.energy > 0.90f -> traits += 0.60f to "energized"
            s.energy < 0.25f -> traits += 0.65f to "exhausted"
        }

        // Return top 4 by intensity, then just the label
        return traits
            .sortedByDescending { it.first }
            .take(4)
            .map { it.second }
            .ifEmpty { listOf("neutral") }
    }

    /**
     * Serialise to a compact one-line string for logging / debug display.
     */
    fun toDebugString(): String = _state.value.toString()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun Float.clamp() = coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

