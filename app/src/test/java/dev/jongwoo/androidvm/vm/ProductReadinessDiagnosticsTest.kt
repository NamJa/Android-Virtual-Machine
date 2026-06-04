package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductReadinessDiagnosticsTest {
    @Test
    fun emitsAllProductGateLinesWhenEverythingPasses() {
        val emitted = mutableListOf<String>()
        val result = ProductReadinessDiagnostics(
            runtimeProbe = { ProductRuntimeResultLine(true, true, true, true, true, true) },
            bridgeProbe = { ProductBridgeResultLine(true, true, true, true, true, true) },
            resilienceProbe = { ProductResilienceResultLine(true, true, true, true, true) },
            securityProbe = { ProductSecurityResultLine(true, true, true, true, true) },
            releaseProbe = { ProductReleaseResultLine(true, true, true, true, true) },
            emit = { emitted += it },
        ).run()

        assertTrue(result.passed)
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
        emitted.forEach { line -> assertTrue(line, line.contains("passed=true")) }
    }

    @Test
    fun anyFailedSubGateFailsCompositeProductReadiness() {
        val result = ProductReadinessDiagnostics(
            runtimeProbe = { ProductRuntimeResultLine(true, true, true, true, true, true) },
            bridgeProbe = { ProductBridgeResultLine(true, true, false, true, true, true) },
            resilienceProbe = { ProductResilienceResultLine(true, true, true, true, true) },
            securityProbe = { ProductSecurityResultLine(true, true, true, true, true) },
            releaseProbe = { ProductReleaseResultLine(true, true, true, true, true) },
        ).run()

        assertFalse(result.passed)
        assertFalse(result.bridge.passed)
        assertTrue(result.bridge.format().contains("network=false"))
    }

    @Test
    fun defaultProbesAreConservativeAndFailClosed() {
        val emitted = mutableListOf<String>()
        val result = ProductReadinessDiagnostics(emit = { emitted += it }).run()

        assertFalse(result.passed)
        assertEquals(5, emitted.size)
        emitted.forEach { line -> assertTrue(line, line.contains("passed=false")) }
    }
}
