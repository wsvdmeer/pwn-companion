package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure power decision extracted from CrackEngine ([PowerPolicy]). The "plugged ≠ charging"
 * subtlety and the worker-count knobs used to be untestable (they reached into BatteryManager); now
 * the decision is a pure function of its inputs.
 */
class PowerPolicyTest {
    private val lowPct = 20

    @Test fun pluggedInNeverBlocks() {
        val r = PowerPolicy.blockReason(plugged = true, chargerOnly = true, lowBatteryStop = true, lowPct = lowPct) { 5 }
        assertNull(r)
    }

    @Test fun chargerOnlyBlocksWhenUnplugged() {
        val r = PowerPolicy.blockReason(plugged = false, chargerOnly = true, lowBatteryStop = false, lowPct = lowPct) { 90 }
        assertEquals("waiting for charger", r)
    }

    @Test fun lowBatteryBlocksBelowThreshold() {
        val r = PowerPolicy.blockReason(plugged = false, chargerOnly = false, lowBatteryStop = true, lowPct = lowPct) { 15 }
        assertEquals("battery 15% - paused", r)
    }

    @Test fun healthyBatteryDoesNotBlock() {
        val r = PowerPolicy.blockReason(plugged = false, chargerOnly = false, lowBatteryStop = true, lowPct = lowPct) { 80 }
        assertNull(r)
    }

    @Test fun batteryIsReadLazily_onlyWhenTheLowRuleNeedsIt() {
        var read = false
        val supplier = { read = true; 50 }
        // Plugged in → short-circuits before touching the battery.
        PowerPolicy.blockReason(plugged = true, chargerOnly = false, lowBatteryStop = true, lowPct = lowPct, batteryPct = supplier)
        assertFalse("battery must not be read when plugged in", read)
        // Charger-only path also short-circuits before the battery read.
        PowerPolicy.blockReason(plugged = false, chargerOnly = true, lowBatteryStop = true, lowPct = lowPct, batteryPct = supplier)
        assertFalse("battery must not be read on the charger-only path", read)
        // Only the low-battery rule reads it.
        PowerPolicy.blockReason(plugged = false, chargerOnly = false, lowBatteryStop = true, lowPct = lowPct, batteryPct = supplier)
        assertTrue(read)
    }

    @Test fun gentleModeCapsAtTwo() {
        assertEquals(2, PowerPolicy.workers(plugged = true, gentleCpu = true, cpus = 8))
        assertEquals(1, PowerPolicy.workers(plugged = true, gentleCpu = true, cpus = 1))   // clamped to cpu count
    }

    @Test fun pluggedUsesAllButOne_batteryUsesHalf_bothClamped() {
        assertEquals(7, PowerPolicy.workers(plugged = true, gentleCpu = false, cpus = 8))    // cpus-1
        assertEquals(4, PowerPolicy.workers(plugged = false, gentleCpu = false, cpus = 8))   // cpus/2
        assertEquals(8, PowerPolicy.workers(plugged = true, gentleCpu = false, cpus = 16))   // clamped to 8
        assertEquals(1, PowerPolicy.workers(plugged = false, gentleCpu = false, cpus = 1))   // clamped to 1
    }
}
