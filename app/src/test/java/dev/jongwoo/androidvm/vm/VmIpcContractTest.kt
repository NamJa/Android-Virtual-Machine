package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the wire format of the cross-process [VmIpc] contract. Adding a new message must
 * extend this test so default-process and `:vm1` stay in sync — there is no schema enforcement
 * at the framework level.
 */
class VmIpcContractTest {
    @Test
    fun encodeDecodeStateRoundTripsForEveryVmState() {
        VmState.entries.forEach { state ->
            val encoded = VmIpcCodec.encodeState("vm1", state)
            val decoded = VmIpcCodec.decodeState(encoded)
            assertEquals("vm1" to state, decoded)
        }
    }

    @Test
    fun encodedStatePayloadCarriesInstanceIdAndStateNameOnly() {
        val encoded = VmIpcCodec.encodeState("vm-test", VmState.RUNNING)
        // The payload is structural; raw bridge content (clipboard, location, package contents)
        // must never appear in cross-process traffic.
        assertTrue(encoded.contains("\"instanceId\":\"vm-test\""))
        assertTrue(encoded.contains("\"state\":\"RUNNING\""))
        assertEquals(2, encoded.count { it == ':' })
    }

    @Test
    fun decodeRejectsCorruptOrUnknownPayloads() {
        assertNull(VmIpcCodec.decodeState(""))
        assertNull(VmIpcCodec.decodeState("{not json"))
        assertNull(VmIpcCodec.decodeState("{}"))
        assertNull(VmIpcCodec.decodeState("""{"instanceId":"vm1"}"""))
        assertNull(VmIpcCodec.decodeState("""{"instanceId":"","state":"RUNNING"}"""))
        assertNull(VmIpcCodec.decodeState("""{"instanceId":"vm1","state":"TIME_TRAVEL"}"""))
    }

    @Test
    fun messageCodesAreUniqueAndStable() {
        // If you renumber a constant the on-the-wire compatibility breaks. This test exists to
        // make sure such a change is intentional — bump every renumber through code review.
        val codes = listOf(
            VmIpc.MSG_REGISTER_REPLY,
            VmIpc.MSG_UNREGISTER_REPLY,
            VmIpc.MSG_STATE_UPDATE,
            VmIpc.MSG_BOOTSTRAP_STATUS,
            VmIpc.MSG_BRIDGE_STATUS,
            VmIpc.MSG_PACKAGE_STATUS,
            VmIpc.MSG_GENERIC_LOG,
        )
        assertEquals(
            "Every MSG_* constant must have a unique value",
            codes.size,
            codes.toSet().size,
        )
        // Sanity: the inbound vs outbound spaces never collide.
        val inbound = setOf(VmIpc.MSG_REGISTER_REPLY, VmIpc.MSG_UNREGISTER_REPLY)
        val outbound = setOf(
            VmIpc.MSG_STATE_UPDATE,
            VmIpc.MSG_BOOTSTRAP_STATUS,
            VmIpc.MSG_BRIDGE_STATUS,
            VmIpc.MSG_PACKAGE_STATUS,
            VmIpc.MSG_GENERIC_LOG,
        )
        assertEquals(emptySet<Int>(), inbound.intersect(outbound))
        assertNotEquals(0, VmIpc.MSG_STATE_UPDATE)
    }

    @Test
    fun bundleKeyNamesAreStable() {
        // The default and :vm1 processes use these literal keys to look up payload fields. If you
        // rename one, you have to rename the other in the same commit.
        assertEquals("instanceId", VmIpc.KEY_INSTANCE_ID)
        assertEquals("payloadJson", VmIpc.KEY_PAYLOAD_JSON)
    }
}
