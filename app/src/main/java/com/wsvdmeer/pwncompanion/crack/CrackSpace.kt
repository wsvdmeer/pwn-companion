package com.wsvdmeer.pwncompanion.crack

/**
 * The pure candidate-space math for an on-phone crack, extracted from [CrackEngine.crackOne] so it's
 * unit-testable (it has regressed before — quick caps, the mangle multiplier, the ISP-prepend index
 * mapping, and the checkpoint tag are all easy to get subtly wrong).
 *
 * The flat candidate space is: the [ispCount] targeted default-key/ESSID guesses first, then the
 * wordlist — each word expanded into [mult] mangle variants. A monotonic index walks the whole thing;
 * [candidateAt] maps an index back to its passphrase and [phaseAt] says which segment it's in.
 */
internal object CrackSpace {

    /** Mangle multiplier: each word expands into this many variants (1 when mangling is off). */
    fun mult(mangle: Boolean): Int = if (mangle) MangleRules.size else 1

    /** Words actually used: the top-[quickLimit] on a quick run, otherwise all of them. */
    fun wordCount(quick: Boolean, words: Long, quickLimit: Long): Long =
        if (quick) minOf(quickLimit, words) else words

    /** Total candidates = the ISP/ESSID guesses + every word × its mangle variants. */
    fun limit(ispCount: Long, wordCount: Long, mult: Int): Long = ispCount + wordCount * mult

    /** Which segment index [idx] falls in: the leading targeted guesses, then the wordlist. */
    fun phaseAt(idx: Long, ispCount: Long): String = if (idx < ispCount) "targeted" else "wordlist"

    /**
     * Checkpoint fingerprint: the wordlist identity plus the mangle factor and ISP count, so toggling
     * mangle or the targeted generators (which changes what each index *means*) invalidates a stale
     * resume point instead of resuming into the wrong candidate.
     */
    fun wordlistId(baseIdentity: String, mangle: Boolean, mult: Int, ispCount: Long): String =
        baseIdentity + (if (mangle) "+m$mult" else "") + (if (ispCount > 0) "+isp$ispCount" else "")

    /** Human-readable "what · how · options" banner string, e.g. "eapol · native · quick · mangle". */
    fun mode(pmkid: Boolean, native: Boolean, quick: Boolean, mangle: Boolean): String = buildString {
        append(if (pmkid) "pmkid" else "eapol")
        append(" · ").append(if (native) "native" else "cpu")
        if (quick) append(" · quick")
        if (mangle) append(" · mangle")
    }

    /**
     * Map a flat candidate index to its passphrase. The first [ispCount] indices are the ISP/ESSID
     * candidates (tried as-is, no mangling); beyond that it's the wordlist —
     * word = (idx-ispCount) / mult, rule = (idx-ispCount) % mult.
     */
    fun candidateAt(
        idx: Long,
        ispCount: Long,
        ispCandidates: List<String>,
        words: List<String>,
        mangle: Boolean,
        mult: Int,
    ): String {
        if (idx < ispCount) return ispCandidates[idx.toInt()]
        val w = idx - ispCount
        val word = words[(w / mult).toInt()]
        return if (mangle) MangleRules.apply(word, (w % mult).toInt()) else word
    }
}
