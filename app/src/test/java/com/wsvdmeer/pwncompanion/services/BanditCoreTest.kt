package com.wsvdmeer.pwncompanion.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBanditTest {

    /** The clearly-best channel (highest exploit) is always chosen. */
    @Test
    fun highestExploitChannelIsChosen() {
        val bandit = ChannelBandit()
        val exploit = mapOf(1 to 0.1, 6 to 10.0, 11 to 0.1, 3 to 0.1)
        val top = bandit.select(listOf(1, 6, 11, 3), 3) { exploit[it] ?: 0.0 }
        assertEquals("best channel should rank first", 6, top.first())
        assertEquals(3, top.size)
    }

    /** Returns at most `count`, with no duplicates, and handles fewer candidates. */
    @Test
    fun selectRespectsCountAndUniqueness() {
        val bandit = ChannelBandit()
        val top = bandit.select(listOf(1, 6), 3) { 1.0 }
        assertEquals(2, top.size)
        assertEquals(top.size, top.toSet().size)   // no dupes
        assertTrue(bandit.select(emptyList(), 3) { 1.0 }.isEmpty())
    }

    /**
     * Does NOT tunnel-vision: even with a channel that's always the top exploit, the modest
     * exploration bonus pulls in other channels within a few cycles (the whole point of UCB).
     */
    @Test
    fun exploresBeyondTheSingleBestChannel() {
        val bandit = ChannelBandit()
        val exploit = mapOf(1 to 1.0, 2 to 0.9, 3 to 0.9, 4 to 0.9, 5 to 0.9)
        var sawOther = false
        repeat(15) {
            val pick = bandit.select(listOf(1, 2, 3, 4, 5), 1) { exploit[it] ?: 0.0 }.first()
            if (pick != 1) sawOther = true
        }
        assertTrue("bandit should explore channels other than the single best", sawOther)
    }

    /**
     * Recency: a channel that stops being chosen has its pull memory decayed, so it isn't
     * permanently locked out (WiFi is non-stationary).
     */
    @Test
    fun pullsDecayForUnchosenChannels() {
        val bandit = ChannelBandit()
        // Cycle 1: only ch6 is a candidate → it gets pulled to 1.0.
        bandit.select(listOf(6), 1) { 1.0 }
        val after1 = bandit.pullsSnapshot()[6] ?: 0.0
        assertEquals(1.0, after1, 1e-9)
        // Cycle 2: ch6 not a candidate → its pull decays (×0.97), below 1.0.
        bandit.select(listOf(1), 1) { 1.0 }
        val after2 = bandit.pullsSnapshot()[6] ?: 0.0
        assertTrue("unchosen channel's pull should decay", after2 < after1)
        assertEquals(0.97, after2, 1e-9)
    }
}

class MotionHeuristicTest {

    @Test
    fun stationaryWhenChurnIsLow() {
        assertFalse(MotionHeuristic.decideByChurn(newApsLastMinute = 0, wasMoving = false))
        assertFalse(MotionHeuristic.decideByChurn(newApsLastMinute = 4, wasMoving = true))   // EXIT boundary
    }

    @Test
    fun movingOnlyOnRealBurst() {
        assertTrue(MotionHeuristic.decideByChurn(newApsLastMinute = 13, wasMoving = false))
        assertTrue(MotionHeuristic.decideByChurn(newApsLastMinute = 12, wasMoving = false))  // ENTER boundary
    }

    /** In the dead-band between EXIT and ENTER, hold the prior state (no flicker). */
    @Test
    fun holdsStateInDeadBand() {
        assertTrue(MotionHeuristic.decideByChurn(newApsLastMinute = 8, wasMoving = true))
        assertFalse(MotionHeuristic.decideByChurn(newApsLastMinute = 8, wasMoving = false))
    }

    // ── GPS-speed path ──

    @Test
    fun speedThresholdsWithHysteresis() {
        assertTrue(MotionHeuristic.decideBySpeed(1.6, wasMoving = false))    // > enter
        assertFalse(MotionHeuristic.decideBySpeed(0.3, wasMoving = true))    // < exit
        assertTrue(MotionHeuristic.decideBySpeed(1.0, wasMoving = true))     // dead-band holds
        assertFalse(MotionHeuristic.decideBySpeed(1.0, wasMoving = false))   // dead-band holds
    }

    /** Jitter inside the accuracy circle reads as stationary (0), not phantom motion. */
    @Test
    fun displacementWithinAccuracyIsStationary() {
        // 3 m move over 2 s but ±8 m accuracy → within noise → 0 (would otherwise be 1.5 m/s).
        assertEquals(0.0, MotionHeuristic.speedFromDisplacement(3.0, 2.0, 8.0)!!, 1e-9)
    }

    /** A move well beyond the error circle yields the real speed. */
    @Test
    fun displacementBeyondAccuracyIsRealSpeed() {
        // 20 m over 2 s with ±5 m accuracy → 10 m/s.
        assertEquals(10.0, MotionHeuristic.speedFromDisplacement(20.0, 2.0, 5.0)!!, 1e-9)
    }

    /** Too-short an interval yields no usable reading. */
    @Test
    fun tooShortIntervalIsNull() {
        assertNull(MotionHeuristic.speedFromDisplacement(20.0, 0.5, 5.0))
    }
}
