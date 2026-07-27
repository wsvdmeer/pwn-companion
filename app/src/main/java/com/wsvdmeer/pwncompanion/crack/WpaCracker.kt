package com.wsvdmeer.pwncompanion.crack

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * On-phone WPA2 cracker. Pure Kotlin, deterministic, no model. Verifies passphrase candidates
 * from a wordlist against a hashcat-`22000` hash the plugin distilled from the capture.
 *
 * WPA2: `PMK = PBKDF2-HMAC-SHA1(passphrase, ESSID, 4096, 32)`. Two capture flavours:
 *  - **PMKID** (`WPA*01`): `PMKID = HMAC-SHA1(PMK, "PMK Name" || MAC_AP || MAC_STA)[:16]`.
 *  - **EAPOL** (`WPA*02`): derive the PTK from the 4-way-handshake nonces, then the key-confirmation
 *    key `KCK = PTK[0:16]`, and check `MIC = HMAC-SHA1(KCK, EAPOL)[:16]` (key-version 2 / CCMP —
 *    the common WPA2 case; TKIP `keyver 1` MD5 and CMAC `keyver 3` are not cracked here).
 *
 * A candidate matches when the computed value equals the captured one. Both flavours share the
 * expensive PBKDF2; the native cracker accelerates each, with this class as the verified fallback.
 */
object WpaCracker {

    /** Parsed PMKID hash (the `WPA*01` variant of the 22000 format). */
    data class PmkidHash(
        val pmkid: ByteArray,   // 16 bytes
        val macAp: ByteArray,   // 6 bytes (AP BSSID)
        val macSta: ByteArray,  // 6 bytes (client)
        val essid: ByteArray,   // raw ESSID bytes — the PBKDF2 salt
    )

    /** Parsed EAPOL hash (the `WPA*02` variant). The EAPOL frame carries the client SNonce and has
     *  its MIC field pre-zeroed by the 22000 exporter, so it's ready to HMAC as-is. */
    data class EapolHash(
        val mic: ByteArray,     // 16 bytes — the captured MIC to match
        val macAp: ByteArray,   // 6 bytes
        val macSta: ByteArray,  // 6 bytes
        val essid: ByteArray,   // PBKDF2 salt
        val anonce: ByteArray,  // 32 bytes — AP nonce (given explicitly in the 22000 line)
        val eapol: ByteArray,   // the 802.1X EAPOL-Key frame, MIC field zeroed
        val keyVer: Int,        // key-descriptor version (2 = HMAC-SHA1; only supported one)
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

    /** True if this line is a PMKID hash we can crack. */
    fun isCrackablePmkid(line: String?): Boolean = line != null && parsePmkid(line) != null

    /** Parse a `WPA*02*MIC*MACAP*MACSTA*ESSIDHEX*ANONCE*EAPOL*MSGPAIR` line. Returns null unless it's
     *  a well-formed, key-version-2 (HMAC-SHA1 / CCMP) handshake we can actually crack. */
    fun parseEapol(line: String): EapolHash? {
        val p = line.trim().split("*")
        if (p.size < 8 || p[0] != "WPA" || p[1] != "02") return null
        return try {
            val eapol = hexToBytes(p[7])
            // key-info is the 2 bytes after the 802.1X header (4) + descriptor type (1); low 3 bits
            // are the key-descriptor version. Nonce lives at eapol[17..48], so we need >= 49 bytes.
            if (eapol.size < 49) return null
            val keyInfo = ((eapol[5].toInt() and 0xff) shl 8) or (eapol[6].toInt() and 0xff)
            val keyVer = keyInfo and 0x07
            val h = EapolHash(
                mic = hexToBytes(p[2]), macAp = hexToBytes(p[3]), macSta = hexToBytes(p[4]),
                essid = hexToBytes(p[5]), anonce = hexToBytes(p[6]), eapol = eapol, keyVer = keyVer,
            )
            if (h.mic.size == 16 && h.macAp.size == 6 && h.macSta.size == 6 &&
                h.essid.isNotEmpty() && h.anonce.size == 32 && keyVer == 2) h else null
        } catch (e: Exception) {
            null
        }
    }

    /** True if this line is an EAPOL handshake we can crack (well-formed key-version-2). */
    fun isCrackableEapol(line: String?): Boolean = line != null && parseEapol(line) != null

    /** True if this line is any handshake the on-phone cracker can take (PMKID or EAPOL). */
    fun isOnPhoneCrackable(line: String?): Boolean = isCrackablePmkid(line) || isCrackableEapol(line)

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
     * Key-confirmation key `KCK = PTK[0:16]`, derived by the IEEE 802.11 PRF from the PMK and the
     * four-way-handshake material. The PRF sorts the two MACs and the two nonces (min || max) so both
     * endpoints agree regardless of who is AP/STA, then runs
     * `HMAC-SHA1(PMK, "Pairwise key expansion" || 0x00 || B || i)` per 20-byte block. The KCK is the
     * first 16 bytes, so block `i = 0` alone suffices — one HMAC, no need to expand the full PTK.
     */
    fun kck(pmk: ByteArray, macAp: ByteArray, macSta: ByteArray, anonce: ByteArray, snonce: ByteArray): ByteArray {
        val (m1, m2) = if (lteq(macAp, macSta)) macAp to macSta else macSta to macAp
        val (n1, n2) = if (lteq(anonce, snonce)) anonce to snonce else snonce to anonce
        val b = m1 + m2 + n1 + n2                              // 6 + 6 + 32 + 32 = 76 bytes
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        mac.update("Pairwise key expansion".toByteArray(Charsets.US_ASCII))
        mac.update(0)                                          // 0x00 separator
        mac.update(b)
        mac.update(0)                                          // block index i = 0
        return mac.doFinal().copyOf(16)
    }

    /** Does [passphrase] match the EAPOL handshake? SNonce comes from the frame's key-nonce field
     *  (bytes 17..48); the MIC is `HMAC-SHA1(KCK, EAPOL)[:16]` for key-version 2. */
    fun verifyEapol(h: EapolHash, passphrase: String): Boolean {
        val snonce = h.eapol.copyOfRange(17, 49)
        val kck = kck(pmk(passphrase, h.essid), h.macAp, h.macSta, h.anonce, snonce)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(kck, "HmacSHA1"))
        return mac.doFinal(h.eapol).copyOf(16).contentEquals(h.mic)
    }

    /** Unsigned lexicographic `a <= b` for equal-length byte arrays (nonce/MAC ordering). */
    private fun lteq(a: ByteArray, b: ByteArray): Boolean {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xff; val y = b[i].toInt() and 0xff
            if (x != y) return x < y
        }
        return true
    }

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
