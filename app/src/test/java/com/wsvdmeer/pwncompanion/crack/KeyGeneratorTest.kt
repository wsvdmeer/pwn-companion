package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the [KeyGenerators] collection framework (step 1 — no real generators yet): matching,
 * WPA-length filtering, de-dup with order preserved, and isolation of a throwing generator.
 */
class KeyGeneratorTest {

    private fun gen(genId: String, match: Boolean, cands: List<String>, throws: Boolean = false) =
        object : KeyGenerator {
            override val id = genId
            override fun matches(essid: String, bssid: String) = match
            override fun candidates(essid: String, bssid: String): List<String> {
                if (throws) error("boom")
                return cands
            }
        }

    @Test fun emptyRegistryYieldsNothing() {
        assertTrue(KeyGenerators.collect(emptyList(), "UPC123456", "aabbccddeeff").isEmpty())
    }

    @Test fun blankEssidYieldsNothing() {
        val g = gen("x", true, listOf("password1"))
        assertTrue(KeyGenerators.collect(listOf(g), "", "aabbccddeeff").isEmpty())
    }

    @Test fun nonMatchingGeneratorSkipped() {
        val g = gen("x", false, listOf("password1"))
        assertTrue(KeyGenerators.collect(listOf(g), "UPC123456", "aabbccddeeff").isEmpty())
    }

    @Test fun filtersLengthAndDedupesPreservingOrder() {
        val g = gen("x", true, listOf(
            "short",         // < 8 → dropped
            "AABBCCDD",      // 8   → kept
            "AABBCCDD",      // dup → dropped
            "x".repeat(64),  // > 63 → dropped
            "welkom2024",    // kept
        ))
        assertEquals(listOf("AABBCCDD", "welkom2024"), KeyGenerators.collect(listOf(g), "UPC1", "b"))
    }

    @Test fun throwingGeneratorDoesNotBreakOthers() {
        val bad = gen("bad", true, emptyList(), throws = true)
        val good = gen("good", true, listOf("goodpass1"))
        assertEquals(listOf("goodpass1"), KeyGenerators.collect(listOf(bad, good), "UPC1", "b"))
    }

    @Test fun collectsFromMultipleMatchingInPriorityOrder() {
        val a = gen("a", true, listOf("aaaaaaaa"))
        val b = gen("b", true, listOf("bbbbbbbb"))
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), KeyGenerators.collect(listOf(a, b), "UPC1", "x"))
    }
}
