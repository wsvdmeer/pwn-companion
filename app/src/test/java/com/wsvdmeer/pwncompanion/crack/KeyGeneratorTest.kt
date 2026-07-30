package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── ThomsonKeygen — reference vector ──
    // Canonical public vector (Kevin Devine / GNUCitizen 2008): serial CP0615…109 → SHA-1
    // 742da831d2… → SSID "SpeedTouchF8A3D0", WPA key "742DA831D2". If the serial→SHA-1→key
    // derivation is right, cracking SSID SpeedTouchF8A3D0 must surface 742DA831D2. This gates
    // registering the generator — a wrong port fails here instead of injecting garbage candidates.
    @Test fun thomsonReproducesReferenceKey() {
        val cands = ThomsonKeygen.candidates("SpeedTouchF8A3D0", "")
        assertTrue("expected 742DA831D2 among $cands", cands.contains("742DA831D2"))
    }

    @Test fun thomsonMatchesOnlyItsOwnSsids() {
        assertTrue(ThomsonKeygen.matches("SpeedTouchF8A3D0", ""))
        assertTrue(ThomsonKeygen.matches("ThomsonABCDEF", ""))
        assertFalse(ThomsonKeygen.matches("UPC1234567", ""))          // other family
        assertFalse(ThomsonKeygen.matches("SpeedTouchZZZZZZ", ""))    // suffix not hex
        assertFalse(ThomsonKeygen.matches("SpeedTouch", ""))          // no suffix
    }

    @Test fun thomsonEmitsWpaValidCandidates() {
        // Every candidate is a 10-char uppercase-hex key (a valid WPA passphrase length).
        ThomsonKeygen.candidates("SpeedTouchF8A3D0", "").forEach {
            assertEquals(10, it.length)
        }
    }

    // ── EssidKeygen ──
    @Test fun essidMatchesAnyNonBlankSsid() {
        assertTrue(EssidKeygen.matches("MyHomeWifi", ""))
        assertFalse(EssidKeygen.matches("", "aabbccddeeff"))
    }

    @Test fun essidDerivesNameVariants() {
        val c = EssidKeygen.candidates("HomeNet", "")
        assertTrue("plain name", c.contains("HomeNet"))
        assertTrue("name+123", c.contains("HomeNet123"))
        assertTrue("name+year", c.contains("HomeNet2024"))
        assertTrue("no-space form", EssidKeygen.candidates("My Net", "").contains("MyNet123"))
    }

    @Test fun essidThroughRegistryIsLengthFiltered() {
        // Short SSID "abc": "abc"(3) dropped, but "abc12345"(8) etc. kept — all results 8..63.
        KeyGenerators.collect(listOf(EssidKeygen), "abc", "").forEach {
            assertTrue("len ${it.length}: $it", it.length in 8..63)
        }
    }
}
