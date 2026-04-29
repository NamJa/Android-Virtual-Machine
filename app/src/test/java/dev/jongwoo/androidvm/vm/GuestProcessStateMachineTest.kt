package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProcessStateMachineTest {
    @Test
    fun walksTheHappyPath() {
        val gp = GuestProcess()
        assertEquals(GuestProcessState.CREATED, gp.state)
        assertTrue(gp.transitionTo(GuestProcessState.LOADING))
        assertTrue(gp.transitionTo(GuestProcessState.RUNNING))
        assertTrue(gp.exitGroup(0))
        assertEquals(GuestProcessState.ZOMBIE, gp.state)
        assertTrue(gp.hasExited)
        assertEquals(0, gp.exitCode)
        assertTrue(gp.transitionTo(GuestProcessState.REAPED))
        assertEquals(GuestProcessState.REAPED, gp.state)
    }

    @Test
    fun loadingMayTransitionDirectlyToZombieOnLoadFailure() {
        val gp = GuestProcess()
        gp.transitionTo(GuestProcessState.LOADING)
        gp.setLastError("execmem_denied")
        assertTrue(gp.transitionTo(GuestProcessState.ZOMBIE))
        assertEquals(GuestProcessState.ZOMBIE, gp.state)
        assertEquals("execmem_denied", gp.lastError())
        // ZOMBIE without exitGroup() does not record an exit code.
        assertFalse(gp.hasExited)
    }

    @Test
    fun rejectsCreatedToRunningTransition() {
        val gp = GuestProcess()
        // Skipping LOADING is illegal — must go through ELF / linker handoff.
        assertFalse(gp.transitionTo(GuestProcessState.RUNNING))
        assertEquals(GuestProcessState.CREATED, gp.state)
    }

    @Test
    fun rejectsRunningToCreatedTransition() {
        val gp = GuestProcess()
        gp.transitionTo(GuestProcessState.LOADING)
        gp.transitionTo(GuestProcessState.RUNNING)
        assertFalse(gp.transitionTo(GuestProcessState.CREATED))
        assertEquals(GuestProcessState.RUNNING, gp.state)
    }

    @Test
    fun rejectsExitGroupFromNonRunning() {
        val gp = GuestProcess()
        assertFalse("CREATED → ZOMBIE via exitGroup is illegal", gp.exitGroup(0))
        gp.transitionTo(GuestProcessState.LOADING)
        assertFalse("LOADING → ZOMBIE must use transitionTo, not exitGroup", gp.exitGroup(0))
    }

    @Test
    fun reapedIsTerminal() {
        val gp = GuestProcess()
        gp.transitionTo(GuestProcessState.LOADING)
        gp.transitionTo(GuestProcessState.RUNNING)
        gp.exitGroup(0)
        gp.transitionTo(GuestProcessState.REAPED)
        assertFalse(gp.transitionTo(GuestProcessState.LOADING))
        assertFalse(gp.transitionTo(GuestProcessState.RUNNING))
        assertFalse(gp.transitionTo(GuestProcessState.CREATED))
    }

    @Test
    fun transitionRulesMatchDocSpec() {
        // Lock the documented state machine — any change to this matrix is a contract change
        // and must be reflected in `phase-b-guest-runtime-poc.md` § B.5.b too.
        val s = GuestProcessState.values()
        val expectedLegalEdges = setOf(
            GuestProcessState.CREATED to GuestProcessState.LOADING,
            GuestProcessState.LOADING to GuestProcessState.RUNNING,
            GuestProcessState.LOADING to GuestProcessState.ZOMBIE,
            GuestProcessState.RUNNING to GuestProcessState.ZOMBIE,
            GuestProcessState.ZOMBIE  to GuestProcessState.REAPED,
        )
        val actualLegalEdges = mutableSetOf<Pair<GuestProcessState, GuestProcessState>>()
        for (from in s) for (to in s) {
            if (GuestProcessTransitions.isLegal(from, to)) actualLegalEdges += from to to
        }
        assertEquals(expectedLegalEdges, actualLegalEdges)
    }

    @Test
    fun wireValuesMatchNativeEnum() {
        assertEquals(0, GuestProcessState.CREATED.wireValue)
        assertEquals(1, GuestProcessState.LOADING.wireValue)
        assertEquals(2, GuestProcessState.RUNNING.wireValue)
        assertEquals(3, GuestProcessState.ZOMBIE.wireValue)
        assertEquals(4, GuestProcessState.REAPED.wireValue)
    }

    @Test
    fun statePersistsAcrossThreads() {
        val gp = GuestProcess()
        gp.transitionTo(GuestProcessState.LOADING)
        val workers = Array(8) { Thread { gp.transitionTo(GuestProcessState.RUNNING) } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        // Only one thread should have advanced the state; the rest get rejected without
        // corrupting it.
        assertEquals(GuestProcessState.RUNNING, gp.state)
    }
}
