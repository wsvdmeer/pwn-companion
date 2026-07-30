package com.wsvdmeer.pwncompanion.ai

import com.wsvdmeer.pwncompanion.models.AutotuneChannelStat
import com.wsvdmeer.pwncompanion.models.DeviceTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the deauth-mission advisor: that it never disagrees with the bandit's steered channel
 * (a past regression), and that the mission-blocker warnings fire on the right signals.
 */
class HuntAdvisorTest {

    @Test fun manualModeSaysNotHunting() {
        val a = HuntAdvisor.recommend(emptyMap(), null, null, isAutoMode = false, minutesSinceLastCatch = null)!!
        assertTrue(a.headline.contains("manual", ignoreCase = true))
        assertNull(a.channel)
    }

    @Test fun headlineMirrorsTheBanditSteeredChannel() {
        val a = HuntAdvisor.recommend(
            autotune = mapOf(11 to AutotuneChannelStat(handshakes = 4, sta = 2)),
            telemetry = null, learning = null, isAutoMode = true,
            minutesSinceLastCatch = null, steeredChannel = 11,
        )!!
        assertEquals(11, a.channel)             // same source of truth as the bandit
        assertTrue(a.headline.contains("ch11")) // …and named in the headline
    }

    @Test fun warnsWhenBlind() {
        val a = HuntAdvisor.recommend(emptyMap(), DeviceTelemetry(blindForEpochs = 5), null, true, null)!!
        assertTrue(a.warnings.any { it.contains("blind") })
        assertEquals("blind", a.alertKey?.substringBefore(":"))
    }

    @Test fun warnsWhenApsButNoClients() {
        val a = HuntAdvisor.recommend(emptyMap(), DeviceTelemetry(numAps = 6, numSta = 0), null, true, null)!!
        assertTrue(a.warnings.any { it.contains("0 clients") })
    }

    @Test fun warnsWhenDryWithClientsAround() {
        val a = HuntAdvisor.recommend(emptyMap(), DeviceTelemetry(numSta = 3), null, true, minutesSinceLastCatch = 45)!!
        assertTrue(a.warnings.any { it.contains("dry") })
    }
}
