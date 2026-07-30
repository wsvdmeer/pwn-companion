package com.wsvdmeer.pwncompanion.crack

/**
 * A per-ISP/router **default-key generator**: given a capture's ESSID + BSSID, produce the small set
 * of WPA passphrases that router family derives from those identifiers.
 *
 * Many ISP routers (Ziggo/UPC, Thomson/Technicolor, …) ship WPA keys that are *algorithmically
 * derived* from the ESSID/BSSID, not random — so a handful of generated candidates crack them in
 * seconds, before the wordlist is ever touched. The crack loop tries [candidates] first.
 *
 * **Framework only for now — no generators ship yet.** Each implementation MUST:
 *  - only use inputs the app actually has over the air (ESSID + BSSID); anything needing a serial
 *    number is out of scope, and
 *  - be verified against a known `(ESSID, BSSID) → key` reference before shipping (see the tests) —
 *    a wrong generator just injects garbage candidates.
 *
 * Register implementations in [KeyGenerators].
 */
interface KeyGenerator {
    /** Short id for logging/debug, e.g. "upc" / "thomson". */
    val id: String

    /** True if this generator applies to the capture — matched by ESSID pattern and/or BSSID OUI. */
    fun matches(essid: String, bssid: String): Boolean

    /** Candidate passphrases derived from the ESSID/BSSID (most-likely first). May be empty. */
    fun candidates(essid: String, bssid: String): List<String>
}
