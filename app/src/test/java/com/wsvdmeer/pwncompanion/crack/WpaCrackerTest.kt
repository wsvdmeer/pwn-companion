package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference vector generated with a trusted independent implementation (Python
 * hashlib.pbkdf2_hmac + hmac): passphrase "12345678", ESSID "pwn-test-net",
 * AP fc:69:0c:15:82:64, STA f4:74:7f:87:f9:f4.
 */
class WpaCrackerTest {
    private val line =
        "WPA*01*72ba558ee61938a6061902e2fa1fb8b3*fc690c158264*f4747f87f9f4*70776e2d746573742d6e6574***"
    private val expectedPmk =
        "b3b65e32a8d9c63c26776660eff2de738de88c63e79c3568fe8c05567f325516"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun pmkMatchesReferencePbkdf2() {
        val h = WpaCracker.parsePmkid(line)!!
        assertEquals(expectedPmk, hex(WpaCracker.pmk("12345678", h.essid)))
    }

    @Test
    fun crackFindsTheKeyInAWordlist() {
        val words = sequenceOf("password", "letmein12", "12345678", "qwertyui")
        assertEquals("12345678", WpaCracker.crack(line, words))
    }

    @Test
    fun crackReturnsNullWhenKeyIsAbsent() {
        val words = sequenceOf("password", "letmein12", "notthekey", "qwertyui")
        assertNull(WpaCracker.crack(line, words))
    }

    @Test
    fun skipsOutOfRangeLengths() {
        assertFalse(WpaCracker.isValidLength("short"))          // < 8
        assertFalse(WpaCracker.isValidLength("x".repeat(64)))   // > 63
        assertTrue(WpaCracker.isValidLength("12345678"))
        // A 7-char correct-prefix candidate must NOT be tried (would never be a WPA key).
        assertNull(WpaCracker.crack(line, sequenceOf("1234567")))
    }

    @Test
    fun parseRejectsNonPmkidLines() {
        assertNull(WpaCracker.parsePmkid("WPA*02*mic*ap*sta*essid*anonce*eapol*02"))  // EAPOL, not PMKID
        assertNull(WpaCracker.parsePmkid("garbage"))
        assertFalse(WpaCracker.isCrackablePmkid(null))
        assertTrue(WpaCracker.isCrackablePmkid(line))
    }

    @Test
    fun progressAndCancelWork() {
        var last = 0L
        // Cancel immediately → no result even though the key is present.
        assertNull(WpaCracker.crack(line, sequenceOf("12345678"), onProgress = { last = it }, cancel = { true }))
    }

    // ── EAPOL (WPA*02) ──
    // The official hashcat mode-22000 example handshake (ESSID "TP-LINK_HASHCAT_TEST"); its
    // passphrase is "hashcat!" (the wiki's "hashcat" line is misleading — the WPA examples take the
    // bang). If our PTK/KCK/MIC derivation is right, "hashcat!" verifies and nothing else does —
    // this is the reference vector that gates shipping EAPOL cracking.
    private val eapolLine =
        "WPA*02*024022795224bffca545276c3762686f*6466b38ec3fc*225edc49b7aa" +
        "*54502d4c494e4b5f484153484341545f54455354" +
        "*10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e" +
        "*0103007502010a0000000000000000000148ce2ccba9c1fda130ff2fbbfb4fd3b063d1a93920b0f7df54a5cbf787b16171" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "001630140100000fac040100000fac040100000fac028000*a2"

    @Test
    fun eapolParsesAsKeyVersion2() {
        val h = WpaCracker.parseEapol(eapolLine)!!
        assertEquals(2, h.keyVer)
        assertEquals(16, h.mic.size)
        assertEquals(32, h.anonce.size)
        assertEquals("TP-LINK_HASHCAT_TEST", String(h.essid, Charsets.US_ASCII))
    }

    @Test
    fun eapolVerifiesTheKnownPassphrase() {
        val h = WpaCracker.parseEapol(eapolLine)!!
        assertTrue(WpaCracker.verifyEapol(h, "hashcat!"))
        assertFalse(WpaCracker.verifyEapol(h, "password"))
        assertFalse(WpaCracker.verifyEapol(h, "hashcat"))   // near-miss — must NOT false-positive
    }

    @Test
    fun parseRejectsWrongFlavours() {
        assertNull(WpaCracker.parseEapol(line))                 // PMKID line, not EAPOL
        assertNull(WpaCracker.parsePmkid(eapolLine))            // EAPOL line, not PMKID
        assertFalse(WpaCracker.isCrackableEapol(null))
        assertTrue(WpaCracker.isCrackableEapol(eapolLine))
        assertFalse(WpaCracker.isCrackablePmkid(eapolLine))
    }
}
