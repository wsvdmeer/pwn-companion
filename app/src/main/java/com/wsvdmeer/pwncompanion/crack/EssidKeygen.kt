package com.wsvdmeer.pwncompanion.crack

/**
 * ESSID-based candidate generator — universal and near-zero cost. A lot of people set the WPA
 * password from the network name, so before the wordlist we try the ESSID itself plus common human
 * variants: append digits / years / `!`, capitalize, strip spaces, lowercase.
 *
 * Unlike the ISP generators this matches **every** network (it's just a smart guess, not a derived
 * key), producing a few dozen candidates. The [KeyGenerators] registry length-filters them to WPA's
 * 8..63 and de-dups, and the crack loop tries them before the wordlist.
 */
object EssidKeygen : KeyGenerator {
    override val id = "essid"

    private val suffixes = listOf(
        "", "1", "12", "123", "1234", "12345", "123456", "0", "00", "01", "007",
        "!", "1!", "123!", "2021", "2022", "2023", "2024", "2025", "2026",
    )

    override fun matches(essid: String, bssid: String) = essid.isNotBlank()

    override fun candidates(essid: String, bssid: String): List<String> {
        val e = essid.trim()
        if (e.isEmpty()) return emptyList()
        // A few base forms of the name, each with common suffixes appended.
        val bases = linkedSetOf(e, e.replace(" ", ""), e.lowercase(), cap(e))
        val out = LinkedHashSet<String>()
        for (b in bases) for (s in suffixes) out.add(b + s)
        return out.toList()
    }

    private fun cap(s: String) = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
}
