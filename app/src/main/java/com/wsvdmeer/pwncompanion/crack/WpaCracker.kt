package com.wsvdmeer.pwncompanion.crack

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * On-phone WPA2 cracker — Phase 2: PMKID (`WPA*01`). Pure Kotlin, deterministic, no native
 * code and no model. Verifies passphrase candidates from a wordlist against a hashcat-`22000`
 * hash the plugin distilled from the capture.
 *
 * WPA2: `PMK = PBKDF2-HMAC-SHA1(passphrase, ESSID, 4096, 32)`; for a PMKID capture,
 * `PMKID = HMAC-SHA1(PMK, "PMK Name" || MAC_AP || MAC_STA)[:16]`. A candidate matches when the
 * computed PMKID equals the captured one.
 *
 * EAPOL (`WPA*02`, needs PTK + MIC) is Phase 3.
 */
object WpaCracker {

    /** Parsed PMKID hash (the `WPA*01` variant of the 22000 format). */
    data class PmkidHash(
        val pmkid: ByteArray,   // 16 bytes
        val macAp: ByteArray,   // 6 bytes (AP BSSID)
        val macSta: ByteArray,  // 6 bytes (client)
        val essid: ByteArray,   // raw ESSID bytes — the PBKDF2 salt
    )

    /** Parse a `WPA*01*PMKID*MACAP*MACSTA*ESSIDHEX*…` line, or null if not a valid PMKID line. */
    fun parsePmkid(line: String): PmkidHash? {
        val p = line.trim().split("*")
        if (p.size < 6 || p[0] != "WPA" || p[1] != "01") return null
        return try {
            val h = PmkidHash(hexToBytes(p[2]), hexToBytes(p[3]), hexToBytes(p[4]), hexToBytes(p[5]))
            if (h.pmkid.size == 16 && h.macAp.size == 6 && h.macSta.size == 6 && h.essid.isNotEmpty()) h else null
        } catch (e: Exception) {
            null
        }
    }

    /** True if this line is a PMKID hash we can crack in Phase 2. */
    fun isCrackablePmkid(line: String?): Boolean = line != null && parsePmkid(line) != null

    /** WPA PMK = PBKDF2-HMAC-SHA1(passphrase, essid, 4096, 32). */
    fun pmk(passphrase: String, essid: ByteArray): ByteArray =
        pbkdf2HmacSha1(passphrase.toByteArray(Charsets.UTF_8), essid, 4096, 32)

    /** PMKID = HMAC-SHA1(PMK, "PMK Name" || MAC_AP || MAC_STA), first 16 bytes. */
    fun pmkid(pmk: ByteArray, macAp: ByteArray, macSta: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        mac.update("PMK Name".toByteArray(Charsets.US_ASCII))
        mac.update(macAp)
        mac.update(macSta)
        return mac.doFinal().copyOf(16)
    }

    /** WPA passphrases are 8–63 chars; anything else can't be the key, so skip it. */
    fun isValidLength(passphrase: String): Boolean = passphrase.length in 8..63

    /** Does [passphrase] match the PMKID hash? (Callers should pre-filter by [isValidLength].) */
    fun verify(h: PmkidHash, passphrase: String): Boolean =
        pmkid(pmk(passphrase, h.essid), h.macAp, h.macSta).contentEquals(h.pmkid)

    /**
     * Run [candidates] against the hash; return the first match, or null if exhausted/aborted.
     * Out-of-range lengths are skipped for free. [onProgress] fires every ~256 tried with the
     * running count (for a progress bar); [cancel] aborts between candidates.
     */
    fun crack(
        line: String,
        candidates: Sequence<String>,
        onProgress: (tried: Long) -> Unit = {},
        cancel: () -> Boolean = { false },
    ): String? {
        val h = parsePmkid(line) ?: return null
        var tried = 0L
        for (c in candidates) {
            if (cancel()) return null
            if (isValidLength(c)) {
                if (verify(h, c)) { onProgress(tried); return c }
            }
            tried++
            if (tried and 0xFF == 0L) onProgress(tried)
        }
        onProgress(tried)
        return null
    }

    // ── PBKDF2-HMAC-SHA1, explicit so WPA byte handling is unambiguous ──
    private fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val hLen = mac.macLength                       // 20
        val blocks = (dkLen + hLen - 1) / hLen
        val dk = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()                      // U1 = HMAC(pass, salt || INT32BE(i))
            val f = u.copyOf()
            for (j in 1 until iterations) {
                u = mac.doFinal(u)                     // Uj = HMAC(pass, U(j-1))
                for (k in f.indices) f[k] = (f[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(f, 0, dk, (i - 1) * hLen, hLen)
        }
        return dk.copyOf(dkLen)
    }

    private fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
