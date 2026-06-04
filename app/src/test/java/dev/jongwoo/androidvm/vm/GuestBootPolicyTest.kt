package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestBootPolicyTest {
    @Test
    fun realOnlyWhenBootReadyAndFlagEnabled() {
        assertEquals(GuestBootMode.REAL, GuestBootPolicy.select(bootReady = true, realBootEnabled = true))
    }

    @Test
    fun flagOffAlwaysSimulated() {
        assertEquals(GuestBootMode.SIMULATED, GuestBootPolicy.select(bootReady = true, realBootEnabled = false))
        assertEquals(GuestBootMode.SIMULATED, GuestBootPolicy.select(bootReady = false, realBootEnabled = false))
    }

    @Test
    fun notBootReadyAlwaysSimulated() {
        assertEquals(GuestBootMode.SIMULATED, GuestBootPolicy.select(bootReady = false, realBootEnabled = true))
    }

    @Test
    fun defaultFlagIsOffSoDefaultSelectionIsSimulated() {
        // The real-boot path is gated until ROM + native integration land.
        assertEquals(GuestBootMode.SIMULATED, GuestBootPolicy.select(bootReady = true))
    }
}
