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
}
