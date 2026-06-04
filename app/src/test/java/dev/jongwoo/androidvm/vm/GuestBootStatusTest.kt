package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestBootStatusTest {
    // Mirrors the current (simulated) native bootstrapStatus.
    private val simulated =
        "runtime_mode=simulated;virtual_init=ok;property_service=ok;servicemanager=ok;" +
            "zygote=main_loop;zygote_socket=accepting;system_server=boot_completed;" +
            "surfaceflinger=first_frame;boot_completed=1"

    // What a real EP2.2+ boot would emit: same markers, no simulated tag.
    private val real =
        "virtual_init=ok;property_service=ok;servicemanager=ok;" +
            "zygote=main_loop;zygote_socket=accepting;system_server=boot_completed;" +
            "surfaceflinger=first_frame;boot_completed=1"

    @Test
    fun simulatedBootIsNotARealGuestBoot() {
        assertTrue(GuestBootStatus.isSimulated(simulated))
        assertTrue(GuestBootStatus.hasBootMarkers(simulated))
        // Markers are present, but it is explicitly simulated -> not real.
        assertFalse(GuestBootStatus.isRealGuestBoot(simulated))
    }

    @Test
    fun realBootMarkersWithoutSimulatedTagCount() {
        assertFalse(GuestBootStatus.isSimulated(real))
        assertTrue(GuestBootStatus.isRealGuestBoot(real))
    }

    @Test
    fun incompleteBootIsNotReal() {
        val partial = "virtual_init=ok;servicemanager=ok"
        assertFalse(GuestBootStatus.hasBootMarkers(partial))
        assertFalse(GuestBootStatus.isRealGuestBoot(partial))
    }

    @Test
    fun emptyStatusIsNotReal() {
        assertFalse(GuestBootStatus.isRealGuestBoot(""))
    }
}
