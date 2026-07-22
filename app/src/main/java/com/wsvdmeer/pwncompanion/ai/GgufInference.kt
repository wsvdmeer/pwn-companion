package com.wsvdmeer.pwncompanion.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GGUF inference engine backed by llama.cpp via JNI.
 *
 * Supports any architecture that llama.cpp supports — Qwen2, LLaMA, Mistral, etc.
 * Runs entirely on CPU (no GPU on most Android devices).
 *
 * The native library is built from llama.cpp source via CMake FetchContent.
 * Model is loaded once and kept alive for the lifetime of this object.
 *
 * Generation is synchronous at the JNI boundary; this class word-streams
 * the result to the Kotlin/UI layer for real-time feel (~33 words/s).
 */
/** Per-token callback for real streaming. Return false to stop generation early. */
fun interface TokenSink { fun onToken(piece: String): Boolean }

internal class GgufInference(modelPath: String) : LlamaInference {

    private val tag = "GgufInference"
    // Volatile: the pre-lock `if (nativeHandle == 0L)` fast-path reads this from a
    // different thread than close() writes it. 64-bit reads aren't guaranteed atomic
    // without it, and a stale read could miss a close().
    @Volatile private var nativeHandle: Long = 0L

    // Serializes ALL native calls. The model is a single llama context (one
    // nativeHandle); two concurrent nativeGenerate() calls share its KV-cache and
    // compute buffers, which corrupts ggml memory → SIGSEGV in ggml_mul_mat. In
    // AUTO mode events flood in and overlapping generations are common, so this
    // lock is what keeps inference crash-safe.
    private val inferenceMutex = Mutex()

    companion object {
        init {
            System.loadLibrary("llama-jni")
        }
    }

    init {
        Log.i(tag, "Loading GGUF from: $modelPath")
        // Use the device's cores (capped) instead of a fixed 4 — modern phones
        // have 6-8, and prompt-eval (time-to-first-token) scales with threads.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        Log.i(tag, "Using $threads inference threads")
        nativeHandle = nativeInit(
            modelPath = modelPath,
            nCtx      = 1536,      // context window — room for richer facts/proactive prompts
            nThreads  = threads
        )
        if (nativeHandle == 0L) {
            throw RuntimeException("llama.cpp failed to load: $modelPath")
        }
        Log.i(tag, "GGUF engine ready")
    }

    override suspend fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: suspend (String) -> Unit
    ) {
        // Run synchronous generation on IO thread, then word-stream back.
        // The mutex serialises the native call so two coroutines can never enter
        // llama_decode on the shared context at once (see inferenceMutex).
        val result = withContext(Dispatchers.IO) {
            if (nativeHandle == 0L) null
            else inferenceMutex.withLock {
                if (nativeHandle == 0L) null
                else nativeGenerate(nativeHandle, prompt, maxTokens, temperature)
            }?.trim()?.takeIf { it.isNotBlank() }
        } ?: return

        Log.d(tag, "Raw output: $result")

        // Stream word-by-word so the UI fills in naturally (~15 words/s)
        val words = result.split(" ")
        words.forEachIndexed { i, word ->
            if (word.isNotEmpty()) {
                onToken(if (i == 0) word else " $word")
                delay(65L)
            }
        }
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String? = withContext(Dispatchers.IO) {
        if (nativeHandle == 0L) null
        else inferenceMutex.withLock {
            if (nativeHandle == 0L) null
            else nativeGenerate(nativeHandle, prompt, maxTokens, temperature)
        }?.trim()
    }

    /**
     * REAL token streaming: [sink] is invoked with each piece as it's decoded (return
     * false to stop early). Returns the full text. Serialized by [inferenceMutex] like
     * the other native calls. The caller (LlamaClient) applies the refusal gate via the
     * sink so refusals never reach the UI.
     */
    suspend fun streamTokens(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        sink: TokenSink,
    ): String? = withContext(Dispatchers.IO) {
        if (nativeHandle == 0L) null
        else inferenceMutex.withLock {
            if (nativeHandle == 0L) null
            else nativeGenerateStreaming(nativeHandle, prompt, maxTokens, temperature, sink)
        }?.trim()
    }

    override suspend fun close() {
        // Free under the same lock as generation so nativeFree can never run while
        // a nativeGenerate is decoding on this handle (that would be a use-after-free
        // → SIGSEGV). The lock makes close wait for any in-flight decode to finish.
        inferenceMutex.withLock {
            if (nativeHandle != 0L) {
                nativeFree(nativeHandle)
                nativeHandle = 0L
                Log.d(tag, "GGUF model freed")
            }
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String?
    private external fun nativeGenerateStreaming(handle: Long, prompt: String, maxTokens: Int, temperature: Float, sink: TokenSink): String?
    private external fun nativeFree(handle: Long)
}

