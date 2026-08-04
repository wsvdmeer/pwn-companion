package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the persisted crack-outcome codec extracted from CrackEngine: the `c:`/`x`/`a` tokens that a
 * finished (or abandoned) crack keeps across process death, and that unknown/absent values decode to
 * null rather than a wrong status.
 */
class CrackResultsTest {

    @Test fun crackedEncodesWithPrefix() {
        assertEquals("c:hunter2", CrackResults.cracked("hunter2"))
    }

    @Test fun parsesCrackedBackToThePassword() {
        assertEquals(
            CrackResults.Outcome.Cracked("hunter2"),
            CrackResults.parse("c:hunter2"),
        )
    }

    @Test fun passwordsWithColonsSurviveRoundTrip() {
        val pw = "a:b:c"                       // only the first "c:" is the prefix
        assertEquals(CrackResults.Outcome.Cracked(pw), CrackResults.parse(CrackResults.cracked(pw)))
    }

    @Test fun emptyPasswordStillDecodesAsCracked() {
        assertEquals(CrackResults.Outcome.Cracked(""), CrackResults.parse("c:"))
    }

    @Test fun exhaustedAndAttemptedTokens() {
        assertEquals(CrackResults.Outcome.Exhausted, CrackResults.parse(CrackResults.EXHAUSTED))
        assertEquals(CrackResults.Outcome.Attempted, CrackResults.parse(CrackResults.ATTEMPTED))
    }

    @Test fun absentOrUnknownIsNull() {
        assertNull(CrackResults.parse(null))
        assertNull(CrackResults.parse(""))
        assertNull(CrackResults.parse("z"))
        assertNull(CrackResults.parse("cracked"))   // not the "c:" prefix
    }
}
