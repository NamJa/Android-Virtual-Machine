package dev.jongwoo.androidvm.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnIsolationTest {
    @Test
    fun defaultSessionMatchesPhaseDPlan() {
        val session = VpnSession.defaultFor("vm1")
        assertEquals("AVM-vm1", session.sessionLabel)
        assertEquals("10.0.0.2", session.ipv4Address)
        assertEquals(24, session.ipv4Prefix)
        assertEquals(1500, session.mtu)
        assertTrue(session.routes.contains("0.0.0.0/0"))
        assertTrue(session.dnsServers.isNotEmpty())
        assertTrue(session.dnsProxyEnabled)
    }

    @Test
    fun sessionRejectsInvalidMtuAndPrefix() {
        val thrownMtu = runCatching {
            VpnSession("vm1", "x", "10.0.0.2", 24, listOf("0.0.0.0/0"), listOf("1.1.1.1"), 100, true)
        }.exceptionOrNull()
        assertTrue(thrownMtu is IllegalArgumentException)

        val thrownPrefix = runCatching {
            VpnSession("vm1", "x", "10.0.0.2", 33, listOf("0.0.0.0/0"), listOf("1.1.1.1"), 1500, true)
        }.exceptionOrNull()
        assertTrue(thrownPrefix is IllegalArgumentException)
    }

    @Test
    fun controller_consentLifecycleTransitionsAreOrdered() {
        val ctl = NetworkIsolationController()
        assertEquals(VpnConsentState.NOT_REQUESTED, ctl.consent)
        ctl.requestConsent()
        assertEquals(VpnConsentState.PENDING, ctl.consent)
        // requestConsent again is idempotent while pending.
        ctl.requestConsent()
        assertEquals(VpnConsentState.PENDING, ctl.consent)
        ctl.applyConsent(false)
        assertEquals(VpnConsentState.DENIED, ctl.consent)
        ctl.applyConsent(true)
        assertEquals(VpnConsentState.GRANTED, ctl.consent)
    }

    @Test
    fun controller_attachTunOnlyAfterConsentAndSession() {
        val ctl = NetworkIsolationController()
        // Wrong mode, no session.
        assertFalse(ctl.attachTun())
        ctl.switchMode(NetworkEgressMode.VPN_ISOLATED)
        // Mode is right, but no consent and no session yet.
        assertFalse(ctl.attachTun())
        ctl.switchMode(NetworkEgressMode.VPN_ISOLATED, VpnSession.defaultFor("vm1"))
        ctl.requestConsent()
        ctl.applyConsent(true)
        assertTrue(ctl.attachTun())
        assertTrue(ctl.tunAttached)
    }

    @Test
    fun controller_decideSyscallFollowsModeAndAttachState() {
        val ctl = NetworkIsolationController()
        assertEquals(NetworkSyscallGate.SyscallDecision.ALLOW, ctl.decideSyscall())

        ctl.switchMode(NetworkEgressMode.DISABLED)
        assertEquals(NetworkSyscallGate.SyscallDecision.ENETUNREACH, ctl.decideSyscall())

        ctl.switchMode(NetworkEgressMode.VPN_ISOLATED, VpnSession.defaultFor("vm1"))
        ctl.requestConsent()
        ctl.applyConsent(true)
        assertEquals(NetworkSyscallGate.SyscallDecision.EACCES, ctl.decideSyscall())
        ctl.attachTun()
        assertEquals(NetworkSyscallGate.SyscallDecision.ALLOW, ctl.decideSyscall())

        ctl.detachTun()
        assertEquals(NetworkSyscallGate.SyscallDecision.EACCES, ctl.decideSyscall())
    }

    @Test
    fun controllerSnapshotIncludesConsentAndSession() {
        val ctl = NetworkIsolationController()
        ctl.switchMode(NetworkEgressMode.VPN_ISOLATED, VpnSession.defaultFor("vm1"))
        ctl.requestConsent()
        ctl.applyConsent(true)
        ctl.attachTun()
        val snap = ctl.snapshot()
        assertEquals("vpn_isolated", snap.getString("mode"))
        assertEquals("granted", snap.getString("consent"))
        assertTrue(snap.getBoolean("tunAttached"))
        assertNotNull(snap.optJSONObject("session"))
    }

    @Test
    fun switchingAwayFromVpnDetachesTun() {
        val ctl = NetworkIsolationController()
        ctl.switchMode(NetworkEgressMode.VPN_ISOLATED, VpnSession.defaultFor("vm1"))
        ctl.applyConsent(true)
        ctl.attachTun()
        assertTrue(ctl.tunAttached)
        ctl.switchMode(NetworkEgressMode.HOST_NAT)
        assertFalse(ctl.tunAttached)
    }
}
