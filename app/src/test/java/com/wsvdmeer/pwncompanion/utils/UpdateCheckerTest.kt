package com.wsvdmeer.pwncompanion.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version-compare behind the "update available" line — the only non-trivial bit of UpdateChecker. */
class UpdateCheckerTest {
    @Test fun newerPatch() = assertTrue(UpdateChecker.isNewer("1.2.5", "1.2.4"))
    @Test fun newerMinor() = assertTrue(UpdateChecker.isNewer("1.3.0", "1.2.9"))
    @Test fun newerMajor() = assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))

    @Test fun sameIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.2.4", "1.2.4"))
    @Test fun olderIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.2.3", "1.2.4"))

    /** Missing trailing segments count as 0, so "1.3" beats "1.2.9" but ties "1.2.0". */
    @Test fun shorterVersionsPadWithZero() {
        assertTrue(UpdateChecker.isNewer("1.3", "1.2.9"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2"))
    }

    /** Non-numeric junk degrades to 0 rather than throwing. */
    @Test fun nonNumericSegmentsDoNotThrow() {
        assertFalse(UpdateChecker.isNewer("v1.x", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.x"))
    }
}
