package com.wsvdmeer.pwncompanion.crack

/**
 * Codec for the per-network resume checkpoint stored in SharedPreferences as `"<index>@<wordlistId>"`.
 *
 * Pure (no Context) so the part that actually regresses — the gate that a checkpoint tagged with a
 * *different* wordlist/mangle factor must be ignored rather than resumed into the wrong candidate — is
 * unit-tested. [CrackEngine] owns the SharedPreferences read/write and delegates the string work here.
 */
internal object CrackCheckpoint {
    /** Serialised form written to prefs: the resume [index] fingerprinted with [wordlistId]. */
    fun encode(index: Long, wordlistId: String): String = "$index@$wordlistId"

    /**
     * The resume index in [raw], or 0 when there's nothing safe to resume: absent, malformed, a
     * non-numeric index, or tagged with a wordlistId other than [expectedWordlistId] (a stale
     * checkpoint from a different wordlist / mangle setting). Splits on the first `@` only, so a
     * wordlistId is compared whole even if it ever contained one.
     */
    fun decode(raw: String?, expectedWordlistId: String): Long {
        raw ?: return 0L
        val parts = raw.split("@", limit = 2)
        if (parts.size != 2 || parts[1] != expectedWordlistId) return 0L
        return parts[0].toLongOrNull() ?: 0L
    }
}
