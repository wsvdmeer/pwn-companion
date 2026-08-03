package com.wsvdmeer.pwncompanion.services

import com.wsvdmeer.pwncompanion.models.CaptureEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers the capture merge/normalize logic that's regressed before: mixed ms/s timestamps, and
 * reconnects/re-scans wiping a cracked password or the 22000 hash.
 */
class CaptureMergeTest {
    private fun cap(bssid: String, ts: Long? = null, pw: String? = null, hash: String? = null) =
        CaptureEntry(ssid = "net", bssid = bssid, timestamp = ts, password = pw, hash22000 = hash)

    @Test fun normalizesMillisToSeconds() {
        assertEquals(1_700_000_000L, CaptureMerge.normalizeTs(1_700_000_000_000L))  // ms → s
        assertEquals(1_700_000_000L, CaptureMerge.normalizeTs(1_700_000_000L))       // already s
        assertNull(CaptureMerge.normalizeTs(null))
    }

    @Test fun emptyIncomingLeavesExistingUntouched() {
        val e = listOf(cap("aa"))
        assertSame(e, CaptureMerge.merge(e, null))
        assertSame(e, CaptureMerge.merge(e, emptyList()))
    }

    @Test fun incomingWinsButCarriesForwardPasswordAndHash() {
        val existing = listOf(cap("aa", ts = 100, pw = "secret", hash = "WPA*01*x"))
        val incoming = listOf(cap("aa", ts = 200))   // a re-scan that dropped pw + hash
        val merged = CaptureMerge.merge(existing, incoming).single()
        assertEquals(200L, merged.timestamp)          // fresher timestamp kept
        assertEquals("secret", merged.password)       // password carried forward
        assertEquals("WPA*01*x", merged.hash22000)    // hash carried forward
    }

    @Test fun freshHashWinsWhenPresent() {
        val existing = listOf(cap("aa", hash = "OLD"))
        val incoming = listOf(cap("aa", hash = "NEW"))
        assertEquals("NEW", CaptureMerge.merge(existing, incoming).single().hash22000)
    }

    @Test fun carriesPasswordWhileFresherHashStillWins() {
        // Mixed case: a re-scan brings a NEW hash but no password — keep the new hash, carry the pw.
        val existing = listOf(cap("aa", pw = "secret", hash = "OLD"))
        val incoming = listOf(cap("aa", hash = "NEW"))
        val merged = CaptureMerge.merge(existing, incoming).single()
        assertEquals("secret", merged.password)   // cracked pw preserved
        assertEquals("NEW", merged.hash22000)      // fresher hash still wins
    }

    @Test fun normalizeBoundaryOnlyDividesTrueMillis() {
        assertEquals(100_000_000_000L, CaptureMerge.normalizeTs(100_000_000_000L))     // == threshold: kept
        assertEquals(100_000_000L, CaptureMerge.normalizeTs(100_000_000_001L))          // just over: ms → s
    }

    @Test fun dedupesByBssidAndSortsNewestFirst() {
        val existing = listOf(cap("aa", ts = 50))
        val incoming = listOf(cap("bb", ts = 300), cap("aa", ts = 100))
        val merged = CaptureMerge.merge(existing, incoming)
        assertEquals(2, merged.size)                              // "aa" deduped, not duplicated
        assertEquals(listOf("bb", "aa"), merged.map { it.bssid }) // newest (300) first
    }

    @Test fun normalizesTimestampsDuringMerge() {
        val merged = CaptureMerge.merge(emptyList(), listOf(cap("aa", ts = 1_700_000_000_000L))).single()
        assertEquals(1_700_000_000L, merged.timestamp)   // ms input normalized to s
    }
}
