package com.wsvdmeer.pwncompanion.crack

/**
 * Codec for a network's persisted crack outcome (the `crack_results` prefs), the status that survives
 * process death: `"c:<password>"` for a hit, `"x"` for a fully-searched miss, `"a"` for started-but-
 * -unfinished. Pure so the encode/decode and precedence stay a tested unit; [CrackEngine] owns the
 * prefs I/O and the in-memory flows, and the supersede rules (cracked/exhausted outrank attempted).
 */
internal object CrackResults {
    const val EXHAUSTED = "x"
    const val ATTEMPTED = "a"
    private const val CRACKED_PREFIX = "c:"

    fun cracked(password: String): String = "$CRACKED_PREFIX$password"

    sealed interface Outcome {
        data class Cracked(val password: String) : Outcome
        data object Exhausted : Outcome
        data object Attempted : Outcome
    }

    /** Decode a stored value, or null if it's absent or an unrecognised token. */
    fun parse(raw: String?): Outcome? = when {
        raw == null -> null
        raw.startsWith(CRACKED_PREFIX) -> Outcome.Cracked(raw.substring(CRACKED_PREFIX.length))
        raw == EXHAUSTED -> Outcome.Exhausted
        raw == ATTEMPTED -> Outcome.Attempted
        else -> null
    }
}
