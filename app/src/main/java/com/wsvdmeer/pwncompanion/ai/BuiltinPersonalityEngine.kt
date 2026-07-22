package com.wsvdmeer.pwncompanion.ai

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Built-in personality engine — no model download required.
 *
 * Reliable canned-response voice (the safety net + fallback). Reaction lines come
 * from the single [BlendedVoice], keyed by reaction category (derived from event
 * type + signal strength) AND by the current [VoiceTone] (derived from the emergent
 * mood). Each response is one short first-person line in the blended cult-movie voice.
 *
 * Substitution tokens:
 *   [NETWORK]  → SSID extracted from the prompt, or "that network"
 *   [CAPTURES] → running handshake count, or "a few"
 */
internal class BuiltinPersonalityEngine : LlamaInference {

    private val tag = "BuiltinAI"

    /** Updated by PwnagotchiViewModel via LlamaClient.updateCompanionName() */
    var companionName: String = "Pwnagotchi"

    // Remember the last line picked per bucket so a random pick never repeats the
    // same line twice in a row (a plain cursor cycled lines in a predictable order,
    // which read as mechanical). Keyed by "category:tone".
    private val lastIndexByBucket = HashMap<String, Int>()

    override suspend fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: suspend (String) -> Unit
    ) {
        val response = buildResponse(prompt)
        Log.d(tag, "Built-in response: $response")

        // Stream word by word — 65 ms per word feels like natural typing
        val words = response.split(" ")
        words.forEachIndexed { i, word ->
            val token = if (i == 0) word else " $word"
            onToken(token)
            delay(65L)
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int, temperature: Float): String =
        buildResponse(prompt)

    override suspend fun close() { /* nothing to close */ }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun buildResponse(prompt: String): String {
        val category = extractReactionCategory(prompt)
        val tone     = extractTone(prompt)
        val net      = extractNetwork(prompt)
        val caps     = extractCaptures(prompt)

        // Pick the category's lines for the current tone, degrading gracefully:
        // this tone → DEADPAN → any non-empty tone → the DEFAULT category.
        val byTone = BlendedVoice.pools[category] ?: BlendedVoice.pools["DEFAULT"] ?: emptyMap()
        val pool = byTone[tone]?.takeIf { it.isNotEmpty() }
            ?: byTone[VoiceTone.DEADPAN]?.takeIf { it.isNotEmpty() }
            ?: byTone.values.firstOrNull { it.isNotEmpty() }
            ?: BlendedVoice.pools["DEFAULT"]?.get(VoiceTone.DEADPAN)
            ?: listOf("...")
        val idx = pickIndex("$category:$tone", pool.size)
        val raw = pool[idx]

        return raw
            .replace("[NETWORK]",  if (net.isNotBlank()) "'$net'" else "that network")
            .replace("[CAPTURES]", if (caps > 0) caps.toString() else "a few")
    }

    /** Random index into a pool that avoids repeating the previous line for this bucket. */
    private fun pickIndex(bucket: String, size: Int): Int {
        if (size <= 1) return 0
        val last = lastIndexByBucket[bucket]
        var idx = kotlin.random.Random.nextInt(size)
        if (idx == last) idx = (idx + 1) % size   // nudge off an immediate repeat
        lastIndexByBucket[bucket] = idx
        return idx
    }

    /** Extracts the [Tone: X] tag embedded by PwnagotchiViewModel.buildPrompt(). */
    private fun extractTone(prompt: String): VoiceTone {
        val m = Regex("""\[Tone:\s*([A-Z]+)\]""").find(prompt)
        return m?.groupValues?.get(1)?.let { runCatching { VoiceTone.valueOf(it) }.getOrNull() }
            ?: VoiceTone.DEADPAN
    }

    /** Extracts the [ReactionCategory: X] tag embedded by PwnagotchiViewModel.buildPrompt(). */
    private fun extractReactionCategory(prompt: String): String {
        val m = Regex("""\[ReactionCategory:\s*([A-Z_]+)\]""").find(prompt)
        if (m != null) return m.groupValues[1]
        // Fallback: check EventType tag
        val et = Regex("""\[EventType:\s*([A-Z_]+)\]""").find(prompt)
        return when (et?.groupValues?.get(1)) {
            "HANDSHAKE_CAPTURED", "CONNECTION_SUCCESS" -> "HANDSHAKE_CAPTURED"
            "NETWORK_DISCOVERED" -> "NEW_NETWORK"
            "ANOMALY_DETECTED"   -> "ANOMALY"
            // Idle-flavored pool when the emergent mood reads bored/restless.
            else -> if (prompt.contains("bored", ignoreCase = true) ||
                        prompt.contains("restless", ignoreCase = true)) "IDLE" else "DEFAULT"
        }
    }

    /** Extracts SSID / network name from the user turn of the prompt. */
    private fun extractNetwork(prompt: String): String {
        val patterns = listOf(
            Regex("""Network:\s+'([^']+)'"""),
            Regex("""'([^']+)'"""),
            Regex("""network\s+["']?([A-ZaZ0-9_\-. ]+)["']?""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val match = p.find(prompt)
            if (match != null) return match.groupValues[1].trim()
        }
        return ""
    }

    /** Extracts capture count from the prompt context. */
    private fun extractCaptures(prompt: String): Int {
        val m = Regex("""Handshakes so far:\s*(\d+)""", RegexOption.IGNORE_CASE).find(prompt)
            ?: Regex("""(\d+)\s+handshake""",           RegexOption.IGNORE_CASE).find(prompt)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
