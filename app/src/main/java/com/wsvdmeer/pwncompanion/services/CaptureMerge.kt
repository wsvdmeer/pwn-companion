package com.wsvdmeer.pwncompanion.services

import com.wsvdmeer.pwncompanion.models.CaptureEntry

/**
 * Pure capture-history merge + timestamp normalization, extracted from [NetworkService] so the
 * logic that keeps regressing is unit-testable without Android: mixed ms/s timestamps (mangled
 * sort order + age), and reconnects/re-scans wiping a cracked password or the 22000 hash.
 */
object CaptureMerge {

    /** Coerce a capture timestamp to Unix **seconds**: values that look like milliseconds
     *  (> ~year 5138 in seconds) are divided by 1000. Fixes mixed ms/s units from the plugin. */
    fun normalizeTs(ts: Long?): Long? = when {
        ts == null -> null
        ts > 100_000_000_000L -> ts / 1000
        else -> ts
    }

    /**
     * Merge [incoming] onto [existing], keyed by [CaptureEntry.key]. Incoming wins on collision,
     * but carries forward a cracked `password` AND the `hash22000` the fresh record may omit (a
     * re-scan can drop either), so reconnects don't wipe them. All timestamps are normalized to
     * seconds; result is newest-first. Null/empty incoming leaves [existing] untouched.
     */
    fun merge(existing: List<CaptureEntry>, incoming: List<CaptureEntry>?): List<CaptureEntry> {
        if (incoming.isNullOrEmpty()) return existing
        val byKey = LinkedHashMap<String, CaptureEntry>()
        existing.forEach { byKey[it.key] = it.copy(timestamp = normalizeTs(it.timestamp)) }
        incoming.forEach { inc0 ->
            val inc = inc0.copy(timestamp = normalizeTs(inc0.timestamp))
            val prev = byKey[inc.key]
            byKey[inc.key] = if (prev != null && (inc.password == null && prev.password != null ||
                                                  inc.hash22000 == null && prev.hash22000 != null))
                inc.copy(password = inc.password ?: prev.password,
                         hash22000 = inc.hash22000 ?: prev.hash22000)
            else inc
        }
        return byKey.values.sortedByDescending { it.timestamp ?: 0L }
    }
}
