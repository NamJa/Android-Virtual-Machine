package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtRuntimeBootstrapTest {
    @Test
    fun fullRuntimeChainIncludesPhaseCAndDexExecutionLibs() {
        ArtRuntimeChain.PRIMARY_LIBS.forEach {
            assertTrue("$it must remain in the full runtime chain", it in ArtRuntimeBootstrap.FULL_RUNTIME_LIBS)
        }
        ArtRuntimeBootstrap.DEX_EXECUTION_LIBS.forEach {
            assertTrue("$it must be present", it in ArtRuntimeBootstrap.FULL_RUNTIME_LIBS)
        }
        assertEquals(
            ArtRuntimeChain.PRIMARY_LIBS.size + ArtRuntimeBootstrap.DEX_EXECUTION_LIBS.size,
            ArtRuntimeBootstrap.EXPECTED_LIB_COUNT,
        )
    }

    @Test
    fun phaseDPinnedApiLevelMatchesAndroid712() {
        assertEquals(25, ArtRuntimeBootstrap.PINNED_API_LEVEL)
    }

    @Test
    fun dex2OatPolicyDefaultIsDisabled() {
        assertEquals(
            ArtRuntimeBootstrap.Dex2OatPolicy.DISABLED,
            ArtRuntimeBootstrap.Dex2OatPolicy.PHASE_D_DEFAULT,
        )
    }

    @Test
    fun dex2OatArgsIncludeFilterAndCachePath() {
        val args = ArtRuntimeBootstrap.toDex2OatArgs(
            ArtRuntimeBootstrap.Dex2OatPolicy.QUICKEN,
            "/avm/instances/vm1/data/dalvik-cache/arm64",
        )
        assertTrue(args.contains("--compiler-filter=quicken"))
        assertTrue(args.contains("--dalvik-cache=/avm/instances/vm1/data/dalvik-cache/arm64"))
        assertTrue(args.first() == "dex2oat")
    }

    @Test
    fun tlsSwapTrampolineRequiresAllFourGuarantees() {
        assertTrue(TlsSwapTrampoline.PHASE_D_REQUIRED.safe)
        val partial = TlsSwapTrampoline.PHASE_D_REQUIRED.copy(restoresHostTlsOnException = false)
        assertFalse(partial.safe)
    }

    @Test
    fun activityLaunchPassesOnlyWhenOnCreateAndFrame() {
        val passing = ActivityLaunchTransaction(
            packageName = "com.example",
            activity = "com.example.MainActivity",
            onCreateInvoked = true,
            frameCount = 3,
            crashCount = 0,
            watchdogMillis = 5000,
        )
        assertTrue(passing.passed)
        assertFalse(passing.copy(onCreateInvoked = false).passed)
        assertFalse(passing.copy(frameCount = 0).passed)
        assertFalse(passing.copy(crashCount = 1).passed)
    }

    @Test
    fun gateAggregatesLibsTlsAndActivityChecks() {
        val gate = ArtRuntimeGate()
        val good = gate.observe(
            libsLoaded = ArtRuntimeBootstrap.EXPECTED_LIB_COUNT,
            transaction = ActivityLaunchTransaction(
                packageName = "p", activity = "a",
                onCreateInvoked = true, frameCount = 1, crashCount = 0, watchdogMillis = 5000,
            ),
        )
        assertTrue(good.passed)
        val tooFewLibs = gate.observe(
            libsLoaded = ArtRuntimeBootstrap.EXPECTED_LIB_COUNT - 1,
            transaction = good.transaction,
        )
        assertFalse(tooFewLibs.passed)
        assertFalse(tooFewLibs.librariesPresent)
        val unsafePolicy = ArtRuntimeGate(policy = ArtRuntimeBootstrap.Dex2OatPolicy.SPEED)
            .observe(ArtRuntimeBootstrap.EXPECTED_LIB_COUNT, good.transaction)
        assertFalse(unsafePolicy.dex2oatPolicySafe)
        assertFalse(unsafePolicy.passed)
    }

    @Test
    fun fromCliFlagRoundTrip() {
        ArtRuntimeBootstrap.Dex2OatPolicy.entries.forEach { policy ->
            assertNotNull(ArtRuntimeBootstrap.Dex2OatPolicy.fromCliFlag(policy.cliFlag))
        }
        assertEquals(null, ArtRuntimeBootstrap.Dex2OatPolicy.fromCliFlag("--garbage"))
    }
}
