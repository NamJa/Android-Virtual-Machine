package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductGateRunnerTest {
    @Test
    fun conservativeSourceFailsClosedAndEmitsAllLines() {
        val emitted = mutableListOf<String>()
        val result = ProductGateRunner(
            signals = ConservativeProductGateSignalSource,
            emit = { emitted += it },
        ).run()

        assertFalse(result.passed)
        assertEquals(
            listOf(
                "PRODUCT_RUNTIME_RESULT",
                "PRODUCT_BRIDGE_RESULT",
                "PRODUCT_RESILIENCE_RESULT",
                "PRODUCT_SECURITY_RESULT",
                "PRODUCT_RELEASE_RESULT",
            ),
            emitted.map { it.substringBefore(' ') },
        )
        emitted.forEach { line -> assertTrue(line, line.contains("passed=false")) }
    }

    @Test
    fun signalsAreMappedOntoTheCorrectGateFields() {
        // A source where exactly one field per line is true; verifies wiring is
        // not crossed between lines/fields.
        val source = object : ProductGateSignalSource by ConservativeProductGateSignalSource {
            override fun graphicsReal() = true
            override fun cameraPolicyDefaultOff() = true
            override fun snapshotFunctional() = true
            override fun telemetryOff() = true
            override fun debugSurfaceClosed() = true
        }

        val result = ProductGateRunner(signals = source).run()

        assertTrue(result.runtime.graphics)
        assertFalse(result.runtime.boot)
        assertTrue(result.bridge.format().contains("camera_policy=true"))
        assertFalse(result.bridge.format().contains("mic_policy=true"))
        assertTrue(result.resilience.snapshot)
        assertTrue(result.security.format().contains("telemetry=off"))
        assertTrue(result.release.format().contains("debug_surface=closed"))
        // Composite still fails because most fields remain false.
        assertFalse(result.passed)
    }

    @Test
    fun allTrueSourcePassesEveryGate() {
        val source = object : ProductGateSignalSource {
            override fun bootRealGuestOrigin() = true
            override fun installReal() = true
            override fun launchReal() = true
            override fun inputReal() = true
            override fun graphicsReal() = true
            override fun audioReal() = true
            override fun clipboardFunctional() = true
            override fun fileFunctional() = true
            override fun networkFunctional() = true
            override fun cameraPolicyDefaultOff() = true
            override fun micPolicyDefaultOff() = true
            override fun auditTrailPresent() = true
            override fun snapshotFunctional() = true
            override fun rollbackFunctional() = true
            override fun crashReportFunctional() = true
            override fun bootRepairFunctional() = true
            override fun dataExportFunctional() = true
            override fun permissionsMinimal() = true
            override fun updateUsesEd25519() = true
            override fun offlineOnly() = true
            override fun telemetryOff() = true
            override fun noBundledSecrets() = true
            override fun debugSurfaceClosed() = true
            override fun signed() = true
            override fun storeReady() = true
            override fun docsPresent() = true
            override fun supportPresent() = true
        }

        assertTrue(ProductGateRunner(signals = source).run().passed)
    }
}
