package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the terminal crack decision extracted from `CrackEngine.crackOne` ([CrackOutcome.decide]).
 * The priority order and — crucially — the "a quick-pass miss must NOT be exhausted" rule used to
 * live only as a comment at the bottom of a 159-line suspend fun; now it has a test.
 */
class CrackOutcomeTest {
    @Test fun foundPasswordWinsOverEverything() {
        val o = CrackOutcome.decide(found = "hunter2", paused = true, skipped = true, quick = true)
        assertEquals(CrackOutcome.Cracked("hunter2"), o)
        assertTrue(o.consumed)
    }

    @Test fun pauseOutranksSkipAndSearchVerdict() {
        // A network paused *and* skipped must still resume (not be consumed) when power returns.
        val o = CrackOutcome.decide(found = null, paused = true, skipped = true, quick = false)
        assertEquals(CrackOutcome.Paused, o)
        assertFalse(o.consumed)
    }

    @Test fun onlyPauseHoldsTheQueueBack() {
        assertTrue(CrackOutcome.decide(null, paused = false, skipped = true, quick = false).consumed)
        assertTrue(CrackOutcome.decide(null, paused = false, skipped = false, quick = true).consumed)
        assertTrue(CrackOutcome.decide(null, paused = false, skipped = false, quick = false).consumed)
        assertFalse(CrackOutcome.decide(null, paused = true, skipped = false, quick = false).consumed)
    }

    @Test fun skipOutranksSearchVerdict() {
        val o = CrackOutcome.decide(found = null, paused = false, skipped = true, quick = true)
        assertEquals(CrackOutcome.Skipped, o)
    }

    @Test fun quickMissIsNotExhausted() {
        // The load-bearing rule: a quick-pass miss leaves the network crackable for a full run.
        val o = CrackOutcome.decide(found = null, paused = false, skipped = false, quick = true)
        assertEquals(CrackOutcome.QuickMiss, o)
    }

    @Test fun fullMissIsExhausted() {
        val o = CrackOutcome.decide(found = null, paused = false, skipped = false, quick = false)
        assertEquals(CrackOutcome.Exhausted, o)
    }
}
