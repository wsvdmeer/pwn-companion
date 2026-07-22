package com.wsvdmeer.pwncompanion.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LLM Client — loads Qwen2 0.5B GGUF via llama.cpp JNI when the model file is present.
 *
 * Both engines are always available:
 *   • [GgufInference]            — real LLM, used when model is downloaded (~350 MB .gguf)
 *   • [BuiltinPersonalityEngine] — mood-keyed templates, always available as safety net
 *
 * On each generation request, [GgufInference] runs first. If it returns blank or very short
 * output (< 4 chars), [BuiltinPersonalityEngine] fills in an in-character response instead.
 * This means the UI always shows something useful, even if the LLM has a bad run.
 *
 * Model loading is always async (initialize() must be called on a background thread).
 * llama_model_load_from_file blocks for 2–8 seconds loading the GGUF into RAM.
 */
class LlamaClient(private val context: Context) {
    val modelManager = ModelManager(context)
    private var gguf: GgufInference? = null
    private val builtin = BuiltinPersonalityEngine()   // always ready — no file needed
    private val tag = "LlamaClient"
    @Volatile private var isInitialized = false

    // Serializes model (re)initialization. init, download-success and retry can all
    // call initializeModel() concurrently; without this two ~350 MB native contexts
    // could load in parallel — one leaked, peak RAM doubled → OOM kill.
    private val initMutex = Mutex()

    // App-lifetime scope for freeing the native model. Must NOT be viewModelScope:
    // that's already cancelled by the time onCleared() → cleanup() runs, so the
    // suspend close (which waits on the inference mutex) would never execute.
    private val lifecycleScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    init {
        // DO NOT call initializeModel() here — model loading blocks for several seconds.
        // Callers must invoke initialize() on a background thread (e.g. Dispatchers.IO).
    }

    /**
     * Load the GGUF model from disk. MUST be called on a background thread.
     * Typically called once from PwnagotchiViewModel.init via viewModelScope.launch(Dispatchers.IO).
     * Also called internally by reinitialize() after a model download completes.
     * Safe to call when no model is present — falls back to built-in engine silently.
     */
    suspend fun initialize() {
        initializeModel()
    }

    private suspend fun initializeModel() = initMutex.withLock {
        if (modelManager.isModelAvailable()) {
            Log.d(tag, "Qwen2 GGUF found — loading llama.cpp engine...")
            try {
                // Free any previously-loaded model first (e.g. on re-download) so we
                // don't leak a ~350 MB native context. close() is mutex-guarded.
                gguf?.close()
                gguf = GgufInference(modelManager.modelPath)
                Log.i(tag, "Qwen2 GGUF engine ready from ${modelManager.modelPath}")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load Qwen2 GGUF — built-in engine will cover all requests: ${e.message}", e)
                gguf = null
            }
        } else {
            Log.d(tag, "No model on disk — built-in personality engine will handle all requests")
        }
        isInitialized = true
    }

    /**
     * Stream tokens as they're generated (for real-time UI updates).
     *
     * Strategy:
     *   1. Try [GgufInference] if available.
     *   2. If it produces blank/very short output, fall back to [BuiltinPersonalityEngine].
     *   3. If model is not present, use [BuiltinPersonalityEngine] directly.
     */
    fun generateStreaming(prompt: String): Flow<String> = channelFlow {
        if (!isInitialized) {
            Log.e(tag, "LlamaClient not initialized yet")
            return@channelFlow
        }

        val g = gguf
        if (g == null) {
            Log.d(tag, "No GGUF model — using built-in personality engine")
            builtinStream(prompt).collect { send(it) }
            return@channelFlow
        }

        // REAL token streaming with a refusal gate. We buffer the first ~sentence WITHOUT
        // emitting, vet it for a safety refusal (the 0.5B/2.5 model still occasionally
        // refuses the persona), and only then start streaming live — so "Sorry, I can't…"
        // never reaches the box. A refusal aborts the stream and we fall back to the
        // theme's canned voice. We also stop after ~2 sentence-ends to keep replies tight
        // (replacing the old post-hoc one-sentence cap, which can't apply mid-stream).
        val buffer = StringBuilder()
        var gateOpen = false
        var refused = false
        var sentenceEnds = 0

        val full = try {
            g.streamTokens(prompt, maxTokens = 64, temperature = 0.7f) { piece ->
                when {
                    refused -> false
                    !gateOpen -> {
                        buffer.append(piece)
                        val buf = buffer.toString()
                        if (buf.length >= GATE_CHARS || buf.contains('\n') || findSentenceEnd(buf) >= 0) {
                            val norm = buf.lowercase().replace('’', '\'').replace('‘', '\'')
                            if (refusalMarkers.any { norm.contains(it) } ||
                                refusalPatterns.any { it.containsMatchIn(norm) }) {
                                refused = true
                                false   // stop generation; canned fallback below
                            } else {
                                var head = buf
                                for (p in metaPreambles) {
                                    if (head.lowercase().startsWith(p)) {
                                        head = head.substring(p.length).trimStart(' ', ',', ':', '-', '—')
                                        break
                                    }
                                }
                                head = head.trimStart('"', '\'', '`', ' ')
                                gateOpen = true
                                if (head.isNotBlank()) trySendBlocking(head)
                                if (findSentenceEnd(head) >= 0) sentenceEnds++
                                sentenceEnds < 2
                            }
                        } else true
                    }
                    else -> {
                        trySendBlocking(piece)
                        if (piece.any { it == '.' || it == '!' || it == '?' }) sentenceEnds++
                        sentenceEnds < 2   // keep it to ~2 sentences
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Streaming generation failed: ${e.message}", e)
            null
        }

        when {
            refused -> {
                Log.w(tag, "Streamed output refused — themed canned fallback")
                builtinStream(prompt).collect { send(it) }
            }
            !gateOpen -> {
                // Reply too short to hit the gate threshold — vet the whole thing at once.
                val clean = full?.let { sanitize(it) }
                if (clean != null) send(clean) else builtinStream(prompt).collect { send(it) }
            }
            // else: already streamed live; done.
        }
    }

    /** Chars to buffer before the refusal gate decides (≈ one short clause). */
    private val GATE_CHARS = 28

    /**
     * Vet a raw GGUF response. Returns the cleaned line, or null if it's blank,
     * too short, or a safety refusal (in which case the caller uses the themed
     * canned voice instead). The small instruct model will sometimes refuse the
     * Wi-Fi-attacker persona outright — those must never reach the UI.
     */
    private fun sanitize(raw: String): String? {
        var t = raw.trim().trim('"', '\'', '`').trim()
        if (t.length < 4) return null
        val low = t.lowercase()
        // Normalize apostrophes (straight/curly) so "can't" and "can’t" both match, then
        // test both exact markers and gap-tolerant patterns — the model slips filler in
        // ("can't HELP YOU with that", "sorry, BUT I can't…") that exact substrings miss.
        val norm = low.replace('’', '\'').replace('‘', '\'')
        if (refusalMarkers.any { norm.contains(it) }) return null
        if (refusalPatterns.any { it.containsMatchIn(norm) }) return null
        // The 0.5B model rambles and sometimes narrates ("As an AI, I'd say…").
        // Strip a leading meta preamble, then keep only the first sentence so the
        // reaction stays short and punchy regardless of how much it generated.
        for (p in metaPreambles) {
            if (t.lowercase().startsWith(p)) {
                t = t.substring(p.length).trimStart(' ', ',', ':', '-', '—').trim()
                break
            }
        }
        val end = findSentenceEnd(t)
        if (end >= 0) t = t.substring(0, end + 1).trim()
        return t.ifBlank { null }
    }

    /**
     * Index of the first real sentence-ending punctuation, or -1. A '.' between
     * digits or not followed by a space (decimals, IPs like 192.168.1.1,
     * abbreviations) is NOT treated as a boundary — otherwise "192.168.1.1, open."
     * would be cut to "192.". '!' and '?' always end a sentence.
     */
    private fun findSentenceEnd(t: String): Int {
        for (i in t.indices) {
            when (t[i]) {
                '!', '?' -> return i
                '.' -> {
                    val prev = t.getOrNull(i - 1)
                    val next = t.getOrNull(i + 1)
                    val betweenDigits = prev?.isDigit() == true && next?.isDigit() == true
                    val endOrSpace = next == null || next == ' '
                    if (!betweenDigits && endOrSpace) return i
                }
            }
        }
        return -1
    }

    private val metaPreambles = listOf(
        "sure,", "sure ", "okay,", "ok,", "here's", "here is", "my reaction",
        "as a", "as an", "i would say", "i'd say", "response:", "reaction:",
    )

    private val refusalMarkers = listOf(
        "can't assist", "cannot assist", "can't help with that", "cannot help with that",
        "i'm sorry", "i am sorry", "as an ai", "as a language model",
        "i can't fulfill", "i cannot fulfill", "i can't provide", "i cannot provide",
        "i'm unable", "i am unable", "i can't comply", "i cannot comply",
        "i can't do that", "i cannot do that", "against my guidelines",
        "i must decline", "not appropriate", "i won't be able",
    )

    // Gap-tolerant refusal patterns (run over an apostrophe-normalized string). These
    // catch phrasings the exact markers miss because the model inserts filler words,
    // e.g. "can't help YOU with that", "sorry, BUT I can't…", "I'm not able to…".
    private val refusalPatterns = listOf(
        Regex("""\b(can't|cannot|won't|will not|unable to|not able to)\b.{0,24}\b(help|assist|do that|provide|fulfill|fulfil|comply|complete|answer|generate)\b"""),
        Regex("""\bsorry,?\s+but\b"""),
        Regex("""\bi'?m\s+sorry\b"""),
        Regex("""\bas an?\s+(ai|language model|assistant)\b"""),
        Regex("""\b(against|violate[sd]?)\b.{0,24}\b(guideline|guidelines|policy|policies|rules)\b"""),
        Regex("""\bi\s+(must|have to|will)\s+decline\b"""),
    )

    /** Wraps [BuiltinPersonalityEngine] as a streaming flow (used as fallback). */
    private fun builtinStream(prompt: String): Flow<String> = flow {
        builtin.generateStreaming(
            prompt = prompt,
            maxTokens = 64,
            temperature = 0.7f,
            topP = 0.9f,
            onToken = { token -> emit(token) }
        )
    }

    /**
     * Quick synchronous generation (for non-UI operations).
     * Always returns a non-null value — falls back to built-in if GGUF is unavailable.
     */
    suspend fun generateQuick(prompt: String, maxTokens: Int = 50): String {
        if (!isInitialized) {
            Log.e(tag, "LlamaClient not initialized")
            return builtin.generate(prompt, maxTokens = maxTokens, temperature = 0.6f) ?: ""
        }

        return withContext(Dispatchers.Default) {
            try {
                val raw = gguf?.generate(prompt = prompt, maxTokens = maxTokens, temperature = 0.6f)?.trim()
                // Run the same refusal/sanitize gate as the streaming path so a
                // "Sorry, I can't assist…" refusal never leaks to a caller.
                val clean = raw?.let { sanitize(it) }
                if (clean != null) {
                    clean
                } else {
                    Log.w(tag, "GGUF quick generate blank/refusal — using built-in fallback")
                    builtin.generate(prompt, maxTokens = maxTokens, temperature = 0.6f) ?: ""
                }
            } catch (e: Exception) {
                Log.e(tag, "Quick generation failed: ${e.message}")
                builtin.generate(prompt, maxTokens = maxTokens, temperature = 0.6f) ?: ""
            }
        }    }

    /**
     * True once initialized. Built-in engine is always ready, so this is always true
     * after initialize() is called — regardless of whether the GGUF model is present.
     */
    fun isReady(): Boolean = isInitialized

    /** True if the downloaded GGUF model is loaded and active. */
    fun isGgufLoaded(): Boolean = gguf != null

    /**
     * Update the companion name used by the built-in engine for [NAME] substitution.
     * Called by PwnagotchiViewModel whenever the connected device name changes.
     */
    fun updateCompanionName(name: String) {
        builtin.companionName = name
    }

    /**
     * Re-initialize after a model download completes.
     * Closes any existing GGUF instance and loads the fresh file.
     * Call from a background thread — model loading is expensive (~3–8 s).
     */
    suspend fun reinitialize() {
        Log.i(tag, "Reinitializing LLM client after model download")
        try { gguf?.close() } catch (e: Exception) { Log.w(tag, "Error closing GGUF: ${e.message}") }
        gguf = null
        isInitialized = false
        initializeModel()
    }

    /**
     * Cleanup resources (called in ViewModel.onCleared()). Runs the suspend free on
     * an app-lifetime scope because viewModelScope is already cancelled here; the
     * mutex-guarded close waits for any in-flight decode so there's no UAF.
     */
    fun cleanup() {
        val toFree = gguf
        gguf = null
        isInitialized = false
        lifecycleScope.launch {
            try {
                toFree?.close()
                Log.d(tag, "Cleaned up LLM resources")
            } catch (e: Exception) {
                Log.e(tag, "Error during cleanup: ${e.message}")
            }
        }
    }
}

/**
 * LLM Inference Interface
 *
 * Implemented by:
 *   [GgufInference]            — Qwen2 0.5B GGUF via llama.cpp JNI (~350 MB .gguf file)
 *   [BuiltinPersonalityEngine] — mood-keyed templates, no file required (always-on safety net)
 */
interface LlamaInference {
    suspend fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: suspend (String) -> Unit
    )

    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String?

    suspend fun close()
}
