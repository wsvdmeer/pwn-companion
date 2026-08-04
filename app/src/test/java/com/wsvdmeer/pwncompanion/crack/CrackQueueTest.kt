package com.wsvdmeer.pwncompanion.crack

import com.wsvdmeer.pwncompanion.models.CaptureEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the FIFO crack-queue logic extracted from CrackEngine: BSSID normalisation, dedup on enqueue
 * (the same AP tapped twice, or in a different case/separator, must not queue twice), removal, and the
 * head/tail the processor drains in order.
 */
class CrackQueueTest {
    private fun cap(bssid: String) = CaptureEntry(ssid = "net", bssid = bssid)

    @Test fun normLowercasesAndStripsSeparators() {
        assertEquals("aabbccddeeff", CrackQueue.norm("AA:BB:CC:DD:EE:FF"))
        assertEquals("aabbccddeeff", CrackQueue.norm("aa-bb-cc-dd-ee-ff"))
        assertEquals(CrackQueue.norm("AA:BB:CC"), CrackQueue.norm("aa-bb-cc"))
    }

    @Test fun addAppendsANewBssid() {
        val q = CrackQueue.add(emptyList(), cap("aa:bb"))
        assertEquals(listOf("aa:bb"), q.map { it.bssid })
    }

    @Test fun addDedupesRegardlessOfCaseOrSeparators() {
        val q0 = listOf(cap("AA:BB:CC"))
        val q1 = CrackQueue.add(q0, cap("aa-bb-cc"))   // same AP, different formatting
        assertSame("dedup must return the SAME list instance so callers detect 'not added'", q0, q1)
        assertEquals(1, q1.size)
    }

    @Test fun addPreservesOrderAcrossDistinctEntries() {
        var q = emptyList<CaptureEntry>()
        q = CrackQueue.add(q, cap("aa"))
        q = CrackQueue.add(q, cap("bb"))
        q = CrackQueue.add(q, cap("cc"))
        assertEquals(listOf("aa", "bb", "cc"), q.map { it.bssid })
    }

    @Test fun removeDropsByNormalisedBssid() {
        val q = listOf(cap("AA:BB"), cap("cc:dd"))
        val after = CrackQueue.remove(q, "aa-bb")       // different formatting still matches
        assertEquals(listOf("cc:dd"), after.map { it.bssid })
    }

    @Test fun removeOfAbsentKeyLeavesQueueIntact() {
        val q = listOf(cap("aa"), cap("bb"))
        assertEquals(q.map { it.bssid }, CrackQueue.remove(q, "zz").map { it.bssid })
    }

    @Test fun headAndTailDrainFifo() {
        val q = listOf(cap("first"), cap("second"), cap("third"))
        assertEquals("first", CrackQueue.head(q)?.bssid)
        assertEquals(listOf("second", "third"), CrackQueue.tail(q).map { it.bssid })
    }

    @Test fun headOfEmptyIsNullTailOfEmptyIsEmpty() {
        assertNull(CrackQueue.head(emptyList()))
        assertTrue(CrackQueue.tail(emptyList()).isEmpty())
    }
}
