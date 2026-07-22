package com.wsvdmeer.pwncompanion.ai

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Voice engine client — **deterministic, on-device, no model download.**
 *
 * The on-device LLM (llama.cpp / Qwen GGUF) was removed: with the curated-first voice the
 * ~491 MB model earned almost nothing. The pet's personality now lives entirely in the
 * curated corpus + franchise-flavored data-slot templates (see [BlendedVoice] and
 * PwnagotchiViewModel), served by [BuiltinPersonalityEngine].
 *
 * This thin wrapper keeps the old call-site API (generateStreaming / generateQuick /
 * isReady / updateCompanionName / cleanup) so the ViewModel's generation plumbing was left
 * intact — it just always routes to the built-in engine now.
 */
class LlamaClient(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val builtin = BuiltinPersonalityEngine()

    /** Stream a curated line word-by-word (keeps the app card's "typing" feel). */
    fun generateStreaming(prompt: String): Flow<String> = flow {
        builtin.generateStreaming(prompt, maxTokens = 64, temperature = 0f, topP = 1f) { emit(it) }
    }

    /** One-shot curated line. */
    suspend fun generateQuick(prompt: String, maxTokens: Int = 50): String =
        builtin.generate(prompt, maxTokens = maxTokens, temperature = 0f) ?: ""

    /** Always ready — there is no model to load. */
    fun isReady(): Boolean = true

    fun updateCompanionName(name: String) { builtin.companionName = name }

    fun cleanup() { /* nothing to free */ }
}

/** Voice engine interface (implemented by [BuiltinPersonalityEngine]). */
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
