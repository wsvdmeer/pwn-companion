package com.wsvdmeer.pwncompanion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Structural guard for the curated voice [BlendedVoice.corpus]. When a franchise is added it's easy
 * to forget a category or paste a wall-of-text line, so this asserts, for EVERY [Franchise]:
 *  - the corpus has a block for it,
 *  - all 8 categories are present and non-empty,
 *  - every line is non-blank, ≥2 words, and within a sane length ceiling.
 *
 * It deliberately does NOT replicate PwnagotchiViewModel.cleanLine exactly — cleanLine is a lenient
 * runtime net (first-clause truncation + fallback), and some intentionally-long lines are shortened
 * at runtime. This test catches the structural/gross mistakes, not stylistic ones.
 */
class PersonalityThemesTest {

    private val categories = listOf(
        "handshake", "assoc", "deauth", "idle", "excited", "weary", "normal", "recap",
    )

    /** Sanity ceiling on raw line length (slot tokens like [SESSION]/[CRACKED] count raw). Existing
     *  lines top out ~58 chars; 64 leaves headroom while still catching a runaway paragraph. */
    private val MAX_RAW_LEN = 64

    @Test fun everyFranchiseHasACompleteCorpusBlock() {
        for (f in Franchise.entries) {
            val block = BlendedVoice.corpus[f]
                ?: run { fail("no corpus block for franchise $f"); return }
            for (cat in categories) {
                val lines = block[cat]
                assertTrue("franchise $f missing category '$cat'", lines != null)
                assertFalse("franchise $f category '$cat' is empty", lines!!.isEmpty())
            }
        }
    }

    @Test fun everyLineIsNonBlankTwoWordsAndBounded() {
        for (f in Franchise.entries) {
            val block = BlendedVoice.corpus[f] ?: continue
            for ((cat, lines) in block) {
                for (line in lines) {
                    assertTrue("$f/$cat: blank line", line.isNotBlank())
                    val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    assertTrue("$f/$cat: <2 words: \"$line\"", words.size >= 2)
                    assertTrue(
                        "$f/$cat: line too long (${line.length} > $MAX_RAW_LEN): \"$line\"",
                        line.length <= MAX_RAW_LEN,
                    )
                }
            }
        }
    }

    @Test fun corpusOnlyKeysKnownCategories() {
        for (f in Franchise.entries) {
            val block = BlendedVoice.corpus[f] ?: continue
            for (cat in block.keys) {
                assertTrue("$f: unknown category '$cat'", cat in categories)
            }
        }
    }
}
