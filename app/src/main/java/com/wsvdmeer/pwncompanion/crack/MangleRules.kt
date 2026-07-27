package com.wsvdmeer.pwncompanion.crack

/**
 * Cheap hashcat-style word mangling: turn each wordlist entry into a handful of common human
 * variants (append digits/years/bangs, capitalize, UPPER, light leet). Multiplies the candidate
 * space ~[rules].size× and catches "Welkom2024!"-style keys the raw list misses.
 *
 * Opt-in (it also multiplies crack time by the same factor). Rules are index-addressable so the
 * cracker can map a flat cursor to (word, rule) and stay resumable.
 */
object MangleRules {

    private fun leet(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                'a', 'A' -> '4'; 'e', 'E' -> '3'; 'o', 'O' -> '0'
                'i', 'I' -> '1'; 's', 'S' -> '5'; 't', 'T' -> '7'
                else -> c
            }
        )
    }

    private fun cap(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    /** Ordered, index-addressable transforms. Keep [0] = identity so plain words are still tried. */
    val rules: List<(String) -> String> = listOf(
        { it },                       // 0: identity (the plain word)
        { it + "1" }, { it + "12" }, { it + "123" }, { it + "1234" }, { it + "12345" },
        { it + "!" }, { it + "1!" }, { it + "123!" },
        { it + "0" }, { it + "00" }, { it + "01" },
        { it + "2022" }, { it + "2023" }, { it + "2024" }, { it + "2025" },
        { cap(it) }, { cap(it) + "1" }, { cap(it) + "123" }, { cap(it) + "!" },
        { it.uppercase() },
        { leet(it) },
    )

    val size: Int get() = rules.size

    /** The [ruleIndex]-th variant of [word] (ruleIndex in 0 until [size]). */
    fun apply(word: String, ruleIndex: Int): String = rules[ruleIndex](word)
}
