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

    // Reference vector (matches WpaCrackerTest): passphrase "12345678".
    private const val REF_LINE =
        "WPA*01*72ba558ee61938a6061902e2fa1fb8b3*fc690c158264*f4747f87f9f4*70776e2d746573742d6e6574***"

    /** Does native find the known key for the reference PMKID? Guards against a broken .so. */
    fun selfCheck(): Boolean {
        if (!available) return false
        val h = WpaCracker.parsePmkid(REF_LINE) ?: return false
        return runCatching {
            crackBatch(h.essid, h.macAp, h.macSta, h.pmkid, 4096,
                arrayOf("password", "letmein12", "12345678", "qwertyui")) == 2
        }.getOrDefault(false)
    }

    /**
     * Cached [selfCheck] — the crack loop only uses native when this is true, so a broken/wrong
     * `.so` (or one built for the wrong ABI variant) can never return wrong results; it falls back
     * to the pure-Kotlin cracker instead.
     */
    val verified: Boolean by lazy { selfCheck() }
}
