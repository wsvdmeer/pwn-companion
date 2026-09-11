package com.wsvdmeer.pwncompanion.ai

import com.wsvdmeer.pwncompanion.protocol.DeviceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure event→personality decision extracted from MainContentArea's LaunchedEffect blocks
 * ([DeviceMoodBridge.wifiEventFor]): snake_case → UPPER_SNAKE normalization, and the idle-in-manual
 * skip. This is the branching logic that used to be inline in the UI, now testable without Compose.
 */
class DeviceMoodBridgeTest {
    private fun net(type: String) = DeviceEvent.Network(
        deviceId = "dev",
        eventType = type,
        description = "d",
        network = "HomeNet",
        count = 2,
        signal = -50,
        channel = 6,
        security = "WPA2",
    )

    @Test fun nullEventIsSkipped() {
        assertNull(DeviceMoodBridge.wifiEventFor(null, isAutoMode = true))
    }

    @Test fun eventTypeIsUppercased() {
        val e = DeviceMoodBridge.wifiEventFor(net("handshakes_captured"), isAutoMode = true)
        assertEquals("HANDSHAKES_CAPTURED", e?.type)
    }

    @Test fun fieldsAreCarriedThrough() {
        val e = DeviceMoodBridge.wifiEventFor(net("network_discovered"), isAutoMode = true)!!
        assertEquals("HomeNet", e.network)
        assertEquals(2, e.count)
        assertEquals(-50, e.rssi)
        assertEquals(6, e.channel)
        assertEquals("WPA2", e.security)
    }

    @Test fun idleIsSkippedInManualMode() {
        assertNull(DeviceMoodBridge.wifiEventFor(net("idle"), isAutoMode = false))
    }

    @Test fun idleIsKeptInAutoMode() {
        val e = DeviceMoodBridge.wifiEventFor(net("idle"), isAutoMode = true)
        assertEquals("IDLE", e?.type)
    }
}
