package com.wsvdmeer.pwncompanion.crack

import android.util.Log

/**
 * JNI bridge to the native PBKDF2/PMKID cracker (`libwpacrack.so`). Doing a candidate in C — one
 * JNI call instead of ~8200 JCE `Mac.doFinal()` dispatches — is the on-phone speed path. Falls
 * back to the pure-Kotlin [WpaCracker] when the lib can't load (so cracking always works).
 */
object NativeWpaCracker {
    private const val TAG = "NativeWpaCracker"

    /** True if the native lib loaded — callers fall back to [WpaCracker] otherwise. */
    val available: Boolean = try {
        System.loadLibrary("wpacrack")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "native cracker unavailable (${e.message}); using Kotlin fallback")
        false
    }

    /** Index of the first candidate in [batch] whose PMKID matches, or -1 if none. */
    external fun crackBatch(
        essid: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
        pmkid: ByteArray,
        iterations: Int,
        batch: Array<String>,
    ): Int

    /** Index of the first candidate in [batch] whose EAPOL MIC matches, or -1 if none. */
    external fun crackBatchEapol(
        essid: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
        mic: ByteArray,
        anonce: ByteArray,
        eapol: ByteArray,
        iterations: Int,
        batch: Array<String>,
    ): Int

    // Reference vectors (match WpaCrackerTest). PMKID key "12345678"; EAPOL key "hashcat!"
    // (the official hashcat mode-22000 example handshake).
    private const val REF_LINE =
        "WPA*01*72ba558ee61938a6061902e2fa1fb8b3*fc690c158264*f4747f87f9f4*70776e2d746573742d6e6574***"
    private const val REF_EAPOL =
        "WPA*02*024022795224bffca545276c3762686f*6466b38ec3fc*225edc49b7aa" +
        "*54502d4c494e4b5f484153484341545f54455354" +
        "*10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e" +
        "*0103007502010a0000000000000000000148ce2ccba9c1fda130ff2fbbfb4fd3b063d1a93920b0f7df54a5cbf787b16171" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "001630140100000fac040100000fac040100000fac028000*a2"

    /** Does native find the known key for the reference PMKID? Guards against a broken .so. */
    fun selfCheck(): Boolean {
        if (!available) return false
        val h = WpaCracker.parsePmkid(REF_LINE) ?: return false
        return runCatching {
            crackBatch(h.essid, h.macAp, h.macSta, h.pmkid, 4096,
                arrayOf("password", "letmein12", "12345678", "qwertyui")) == 2
        }.getOrDefault(false)
    }

    /** Does native find the known key for the reference EAPOL handshake? Guards the EAPOL path. */
    fun selfCheckEapol(): Boolean {
        if (!available) return false
        val h = WpaCracker.parseEapol(REF_EAPOL) ?: return false
        return runCatching {
            crackBatchEapol(h.essid, h.macAp, h.macSta, h.mic, h.anonce, h.eapol, 4096,
                arrayOf("password", "hashcat", "hashcat!", "qwertyui")) == 2
        }.getOrDefault(false)
    }

    /**
     * Cached self-checks — the crack loop only uses a native path when its check is true, so a
     * broken/wrong `.so` (or one built for the wrong ABI variant) can never return wrong results; it
     * falls back to the pure-Kotlin cracker instead. PMKID and EAPOL are gated independently.
     */
    val verified: Boolean by lazy { selfCheck() }
    val eapolVerified: Boolean by lazy { selfCheckEapol() }
}
