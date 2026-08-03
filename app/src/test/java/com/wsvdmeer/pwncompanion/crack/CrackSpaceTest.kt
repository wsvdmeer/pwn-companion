package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the candidate-space math extracted from CrackEngine.crackOne — the parts that have regressed
 * before: the quick cap, the mangle multiplier, the ISP-prepend index→passphrase mapping, the phase
 * boundary, and the checkpoint fingerprint.
 */
class CrackSpaceTest {

    private val words = listOf("alpha", "bravo", "charlie", "delta")
    private val isp = listOf("KEY1KEY1", "KEY2KEY2", "KEY3KEY3")

    // ── mult / wordCount / limit ─────────────────────────────────────────────
    @Test fun multIsOneWithoutMangleAndRuleCountWithIt() {
        assertEquals(1, CrackSpace.mult(false))
        assertEquals(MangleRules.size, CrackSpace.mult(true))
    }

    @Test fun quickCapsWordCountFullUsesAll() {
        assertEquals(25L, CrackSpace.wordCount(quick = true, words = 100, quickLimit = 25))
        assertEquals(10L, CrackSpace.wordCount(quick = true, words = 10, quickLimit = 25)) // fewer than cap
        assertEquals(100L, CrackSpace.wordCount(quick = false, words = 100, quickLimit = 25))
    }

    @Test fun limitIsIspPlusWordsTimesMult() {
        assertEquals(0L, CrackSpace.limit(0, 0, 1))
        assertEquals(100L, CrackSpace.limit(0, 100, 1))
        assertEquals(203L, CrackSpace.limit(3, 100, 2))     // 3 isp + 100*2
        assertEquals(3L, CrackSpace.limit(3, 0, 1))         // only isp (empty wordlist)
    }

    // ── phaseAt ──────────────────────────────────────────────────────────────
    @Test fun phaseSwitchesAtIspBoundary() {
        assertEquals("targeted", CrackSpace.phaseAt(0, ispCount = 3))
        assertEquals("targeted", CrackSpace.phaseAt(2, ispCount = 3))
        assertEquals("wordlist", CrackSpace.phaseAt(3, ispCount = 3))   // first past the isp block
        assertEquals("wordlist", CrackSpace.phaseAt(99, ispCount = 3))
        assertEquals("wordlist", CrackSpace.phaseAt(0, ispCount = 0))   // no isp → always wordlist
    }

    // ── wordlistId (checkpoint fingerprint) ──────────────────────────────────
    @Test fun wordlistIdTagsMangleAndIsp() {
        assertEquals("default:655000", CrackSpace.wordlistId("default:655000", mangle = false, mult = 1, ispCount = 0))
        assertEquals("default:655000+m22", CrackSpace.wordlistId("default:655000", mangle = true, mult = 22, ispCount = 0))
        assertEquals("default:655000+isp3", CrackSpace.wordlistId("default:655000", mangle = false, mult = 1, ispCount = 3))
        assertEquals("default:655000+m22+isp3", CrackSpace.wordlistId("default:655000", mangle = true, mult = 22, ispCount = 3))
        // A starter→full swap changes the base identity → different id → old checkpoint invalidated.
        assertEquals("starter:21000", CrackSpace.wordlistId("starter:21000", mangle = false, mult = 1, ispCount = 0))
    }

    // ── mode banner string ───────────────────────────────────────────────────
    @Test fun modeReflectsFlags() {
        assertEquals("eapol · native", CrackSpace.mode(pmkid = false, native = true, quick = false, mangle = false))
        assertEquals("pmkid · cpu", CrackSpace.mode(pmkid = true, native = false, quick = false, mangle = false))
        assertEquals("eapol · native · quick · mangle", CrackSpace.mode(pmkid = false, native = true, quick = true, mangle = true))
    }

    // ── candidateAt: the index → passphrase mapping ──────────────────────────
    @Test fun candidateAtReturnsIspGuessesFirstInOrder() {
        val ispCount = isp.size.toLong()
        for (i in isp.indices) {
            assertEquals(isp[i], CrackSpace.candidateAt(i.toLong(), ispCount, isp, words, mangle = false, mult = 1))
        }
    }

    @Test fun candidateAtWalksWordlistAfterIspNoMangle() {
        val ispCount = isp.size.toLong()   // 3
        // first index past the isp block is words[0], then words[1], …
        assertEquals("alpha", CrackSpace.candidateAt(ispCount, ispCount, isp, words, mangle = false, mult = 1))
        assertEquals("bravo", CrackSpace.candidateAt(ispCount + 1, ispCount, isp, words, mangle = false, mult = 1))
        assertEquals("delta", CrackSpace.candidateAt(ispCount + 3, ispCount, isp, words, mangle = false, mult = 1))
    }

    @Test fun candidateAtMapsWordAndRuleWithMangle() {
        val ispCount = 0L
        val mult = MangleRules.size
        // idx = word*mult + rule → candidateAt == MangleRules.apply(words[word], rule)
        assertEquals(MangleRules.apply(words[0], 0), CrackSpace.candidateAt(0, ispCount, isp, words, mangle = true, mult))
        assertEquals(MangleRules.apply(words[0], 1), CrackSpace.candidateAt(1, ispCount, isp, words, mangle = true, mult))
        // crossing into the next word at index == mult
        assertEquals(MangleRules.apply(words[1], 0), CrackSpace.candidateAt(mult.toLong(), ispCount, isp, words, mangle = true, mult))
        assertEquals(MangleRules.apply(words[1], 2), CrackSpace.candidateAt(mult.toLong() + 2, ispCount, isp, words, mangle = true, mult))
    }

    @Test fun candidateAtCombinesIspThenMangledWordlist() {
        val ispCount = isp.size.toLong()   // 3
        val mult = MangleRules.size
        // last isp, then the first mangled word variant right after
        assertEquals(isp.last(), CrackSpace.candidateAt(ispCount - 1, ispCount, isp, words, mangle = true, mult))
        assertEquals(MangleRules.apply(words[0], 0), CrackSpace.candidateAt(ispCount, ispCount, isp, words, mangle = true, mult))
        assertEquals(MangleRules.apply(words[0], 1), CrackSpace.candidateAt(ispCount + 1, ispCount, isp, words, mangle = true, mult))
    }
}
