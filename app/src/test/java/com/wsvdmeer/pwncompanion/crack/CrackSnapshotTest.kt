package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the per-network crack-status decision now folded into one place ([CrackSnapshot.statusOf]),
 * which the captures screen used to re-derive at three separate call sites. Priority order matters:
 * running (or power-paused) wins over queued, which wins over exhausted/attempted.
 */
class CrackSnapshotTest {
    private fun snap(
        running: String? = null,
        paused: Boolean = false,
        queued: Set<String> = emptySet(),
        exhausted: Set<String> = emptySet(),
        attempted: Set<String> = emptySet(),
    ) = CrackSnapshot(running, paused, queued, exhausted, attempted)

    @Test fun unknownNetworkIsNone() {
        assertEquals(CrackStatus.NONE, snap().statusOf("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun runningNetworkIsRunning() {
        val s = snap(running = CrackQueue.norm("AA:BB:CC:DD:EE:FF"))
        assertEquals(CrackStatus.RUNNING, s.statusOf("aa-bb-cc-dd-ee-ff"))   // matches regardless of format
    }

    @Test fun runningButPausedIsPaused() {
        val s = snap(running = CrackQueue.norm("AA:BB:CC:DD:EE:FF"), paused = true)
        assertEquals(CrackStatus.PAUSED, s.statusOf("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun queuedNetworkIsQueued() {
        val s = snap(queued = setOf(CrackQueue.norm("AA:BB:CC:DD:EE:FF")))
        assertEquals(CrackStatus.QUEUED, s.statusOf("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun runningOutranksQueued() {
        val key = CrackQueue.norm("AA:BB:CC:DD:EE:FF")
        val s = snap(running = key, queued = setOf(key))
        assertEquals(CrackStatus.RUNNING, s.statusOf("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun exhaustedAndAttemptedAreLastResorts() {
        val key = CrackQueue.norm("AA:BB:CC:DD:EE:FF")
        assertEquals(CrackStatus.EXHAUSTED, snap(exhausted = setOf(key)).statusOf("AA:BB:CC:DD:EE:FF"))
        assertEquals(CrackStatus.ATTEMPTED, snap(attempted = setOf(key)).statusOf("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun exhaustedOutranksAttempted() {
        val key = CrackQueue.norm("AA:BB:CC:DD:EE:FF")
        val s = snap(exhausted = setOf(key), attempted = setOf(key))
        assertEquals(CrackStatus.EXHAUSTED, s.statusOf("AA:BB:CC:DD:EE:FF"))
    }
}
