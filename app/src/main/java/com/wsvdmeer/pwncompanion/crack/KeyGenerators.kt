package com.wsvdmeer.pwncompanion.crack

import android.util.Log

/**
 * Registry of [KeyGenerator]s. For a capture, collects candidates from every matching generator —
 * length-filtered to WPA's 8..63 and de-duplicated, preserving order (a generator's most-likely
 * candidates first). The crack loop prepends these to the wordlist space so they're tried first.
 *
 * Active generators live in [generators] (verified against reference vectors in KeyGeneratorTest).
 * If that list is empty, [candidatesFor] returns nothing and a crack is a plain wordlist run.
 */
object KeyGenerators {
    private const val TAG = "KeyGenerators"

    /** Registered generators, in priority order (candidates tried before the wordlist — the "targeted"
     *  phase). [ThomsonKeygen] derives the exact key for SpeedTouch/Thomson SSIDs (gated — fires only
     *  on those); [EssidKeygen] adds a few name-based guesses for every network. Both are pinned to
     *  reference vectors in KeyGeneratorTest. UPC/Ziggo stays deferred (native-scale MD5). */
    private val generators: List<KeyGenerator> = listOf(ThomsonKeygen, EssidKeygen)

    /** The registered generators, for the UI to render one enable/disable chip each. */
    val registered: List<KeyGenerator> get() = generators

    /** Generated candidates for a capture (matching generators' output, 8..63 chars, de-duped).
     *  [enabled] filters generators by id so each can be toggled off in the crack options. */
    fun candidatesFor(essid: String, bssid: String, enabled: (String) -> Boolean = { true }): List<String> {
        val out = collect(generators.filter { enabled(it.id) }, essid, bssid)
        if (out.isNotEmpty()) Log.i(TAG, "${out.size} targeted candidate(s) for ${essid.ifBlank { bssid }}")
        return out
    }

    /**
     * Pure collection core (no logging) — takes the generator list explicitly so it's unit-testable.
     * Skips blank ESSIDs, gates on [KeyGenerator.matches], length-filters to 8..63, de-dupes while
     * preserving first-seen order, and isolates a throwing generator so one bad one can't break the run.
     */
    internal fun collect(gens: List<KeyGenerator>, essid: String, bssid: String): List<String> {
        if (gens.isEmpty() || essid.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (g in gens) {
            if (!g.matches(essid, bssid)) continue
            runCatching { g.candidates(essid, bssid) }
                .onSuccess { cs -> cs.forEach { if (it.length in 8..63) out.add(it) } }
                .onFailure { Log.w(TAG, "generator ${g.id} failed: ${it.message}") }
        }
        return out.toList()
    }
}
