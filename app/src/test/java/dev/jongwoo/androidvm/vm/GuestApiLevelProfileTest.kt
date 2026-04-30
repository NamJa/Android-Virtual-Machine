package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestApiLevelProfileTest {
    @Test
    fun android712IsThePhaseDDefault() {
        val p = GuestApiLevelProfile.ANDROID_7_1_2
        assertEquals(25, p.apiLevel)
        assertNull(p.binderfsPath)
        assertFalse(p.memfdCreateStable)
        assertFalse(p.scopedStorageEnforced)
        assertEquals(GuestApiLevelProfile.SeccompStrictness.LENIENT, p.seccompFilter)
        assertFalse(p.rroScanRequired)
    }

    @Test
    fun android10ProfileEnablesBinderfsAndScopedStorage() {
        val p = GuestApiLevelProfile.ANDROID_10
        assertEquals(29, p.apiLevel)
        assertEquals("/dev/binderfs/binder", p.binderfsPath)
        assertTrue(p.memfdCreateStable)
        assertTrue(p.scopedStorageEnforced)
        assertTrue(p.requiresBinderfs())
        assertEquals(GuestApiLevelProfile.ArtAbi.ART_29, p.artAbi)
        assertEquals(GuestApiLevelProfile.SeccompStrictness.MODERATE, p.seccompFilter)
        assertTrue(p.rroScanRequired)
    }

    @Test
    fun android12ProfileTightensSeccompFilter() {
        val p = GuestApiLevelProfile.ANDROID_12
        assertEquals(31, p.apiLevel)
        assertEquals("/dev/binderfs/binder", p.binderfsPath)
        assertEquals(GuestApiLevelProfile.SeccompStrictness.STRICT, p.seccompFilter)
        assertEquals(GuestApiLevelProfile.ArtAbi.ART_31, p.artAbi)
        assertTrue(p.rroScanRequired)
    }

    @Test
    fun forApiLevelDispatchesByThreshold() {
        assertEquals(GuestApiLevelProfile.ANDROID_7_1_2, GuestApiLevelProfile.forApiLevel(25))
        assertEquals(GuestApiLevelProfile.ANDROID_7_1_2, GuestApiLevelProfile.forApiLevel(28))
        assertEquals(GuestApiLevelProfile.ANDROID_10, GuestApiLevelProfile.forApiLevel(29))
        assertEquals(GuestApiLevelProfile.ANDROID_10, GuestApiLevelProfile.forApiLevel(30))
        assertEquals(GuestApiLevelProfile.ANDROID_12, GuestApiLevelProfile.forApiLevel(31))
        assertEquals(GuestApiLevelProfile.ANDROID_12, GuestApiLevelProfile.forApiLevel(34))
    }

    @Test
    fun forVersionLabelLookup() {
        assertEquals(GuestApiLevelProfile.ANDROID_10, GuestApiLevelProfile.forVersionLabel("10"))
        assertEquals(GuestApiLevelProfile.ANDROID_12, GuestApiLevelProfile.forVersionLabel("12"))
        assertNull(GuestApiLevelProfile.forVersionLabel("13"))
    }

    @Test
    fun readinessLineCarriesPerProfileFields() {
        val ten = GuestProfileReadiness(
            profile = GuestApiLevelProfile.ANDROID_10,
            zygoteBootable = true,
            systemServerReachable = true,
            launcherReachable = true,
        )
        assertTrue(ten.passed)
        val line = ten.line("STAGE_PHASE_E_ANDROID10")
        assertTrue(line.contains("STAGE_PHASE_E_ANDROID10"))
        assertTrue(line.contains("passed=true"))
        assertTrue(line.contains("zygote=ok"))
        val twelveFail = GuestProfileReadiness(
            profile = GuestApiLevelProfile.ANDROID_12,
            zygoteBootable = true,
            systemServerReachable = false,
            launcherReachable = false,
        )
        assertFalse(twelveFail.passed)
        val failLine = twelveFail.line("STAGE_PHASE_E_ANDROID12")
        assertTrue(failLine.contains("system_server=fail"))
        assertTrue(failLine.contains("launcher=fail"))
    }
}
