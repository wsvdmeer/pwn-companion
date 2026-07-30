package com.wsvdmeer.pwncompanion.crack

import android.util.Log

/**
 * Registry of [KeyGenerator]s. For a capture, collects candidates from every matching generator —
 * length-filtered to WPA's 8..63 and de-duplicated, preserving order (a generator's most-likely
 * candidates first). The crack loop prepends these to the wordlist space so they're tried first.
 *
 * Empty until generators are registered in [generators]; with none, [candidatesFor] returns an
 * empty list and the crack behaves exactly as a plain wordlist run.
 */
object KeyGenerators {
    private const val TAG = "KeyGenerators"

    /** Registered generators, in priority order. Each is gated by a reference-vector test. */
    private val generators: List<KeyGenerator> = listOf(ThomsonKeygen)

    /** Generated candidates for a capture (matching generators' output, 8..63 chars, de-duped). */
    fun candidatesFor(essid: String, bssid: String): List<String> {
        val out = collect(generators, essid, bssid)
        if (out.isNotEmpty()) Log.i(TAG, "${out.size} ISP candidate(s) for ${essid.ifBlank { bssid }}")
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
