package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the data contract that `loader/linker_bridge.cpp::prepareLinkerHandoff` must produce.
 * The auxv layout is the part both the C++ and Kotlin builders share — one drift between
 * them and bionic linker would read junk from the guest stack on entry.
 */
class LinkerHandoffTest {
    private val sampleBinary = ElfMapping(
        base = 0x7000_0000L,
        entry = 0x7000_1234L,
        programHeaders = 0x7000_0040L,
        programHeaderCount = 7,
        programHeaderSize = 56,
    )
    private val sampleLinker = ElfMapping(
        base = 0x7800_0000L,
        entry = 0x7800_1000L,
        programHeaders = 0x7800_0040L,
        programHeaderCount = 5,
        programHeaderSize = 56,
    )
    private val stackTop = 0x6000_0000L
    private val stackBase = 0x5FFF_0000L
    private val stackSize = 0x10000L

    @Test
    fun preparesHandoffOnHappyPath() {
        val h = LinkerBridge.prepareHandoff(
            sampleBinary, sampleLinker, LinkerProfile.DEFAULT_AARCH64,
            stackTop, stackBase, stackSize,
        )
        assertTrue("handoff should succeed; reason=${h.failureReason}", h.prepared)
        assertEquals(sampleBinary.entry, h.binaryEntry)
        assertEquals(sampleLinker.entry, h.linkerEntry)
        assertEquals(stackTop, h.stackTop)
        assertEquals("/system/bin/linker64", h.profile.interpreterPath)
    }

    @Test
    fun rejectsBinaryMappingWithZeroBaseOrEntry() {
        val zero = sampleBinary.copy(base = 0)
        assertEquals(
            "binary_mapping_invalid",
            LinkerBridge.prepareHandoff(zero, sampleLinker, LinkerProfile.DEFAULT_AARCH64,
                stackTop, stackBase, stackSize).failureReason,
        )
    }

    @Test
    fun rejectsStackParametersThatAreZero() {
        listOf(
            { LinkerBridge.prepareHandoff(sampleBinary, sampleLinker, LinkerProfile.DEFAULT_AARCH64,
                0, stackBase, stackSize) },
            { LinkerBridge.prepareHandoff(sampleBinary, sampleLinker, LinkerProfile.DEFAULT_AARCH64,
                stackTop, 0, stackSize) },
            { LinkerBridge.prepareHandoff(sampleBinary, sampleLinker, LinkerProfile.DEFAULT_AARCH64,
                stackTop, stackBase, 0) },
        ).forEach { build ->
            assertEquals("stack_invalid", build().failureReason)
        }
    }

    @Test
    fun rejectsNamespaceOverlap() {
        val collidingLinker = sampleLinker.copy(base = sampleBinary.base)
        val h = LinkerBridge.prepareHandoff(
            sampleBinary, collidingLinker, LinkerProfile.DEFAULT_AARCH64,
            stackTop, stackBase, stackSize,
        )
        assertFalse(h.prepared)
        assertEquals("namespace_overlap", h.failureReason)
    }

    @Test
    fun auxvLayoutMatchesContract() {
        val h = LinkerBridge.prepareHandoff(
            sampleBinary, sampleLinker, LinkerProfile.DEFAULT_AARCH64,
            stackTop, stackBase, stackSize,
        )
        // Required entries + AT_NULL terminator. Order: PHDR, PHENT, PHNUM, PAGESZ, BASE,
        // FLAGS, ENTRY, HWCAP, HWCAP2, AT_NULL = 9 entries × 2 + 2 = 20.
        assertEquals(20, h.auxv.size)
        assertEquals(AuxType.AT_PHDR, h.auxv[0]); assertEquals(sampleBinary.programHeaders, h.auxv[1])
        assertEquals(AuxType.AT_PHENT, h.auxv[2]); assertEquals(56L, h.auxv[3])
        assertEquals(AuxType.AT_PHNUM, h.auxv[4]); assertEquals(7L, h.auxv[5])
        assertEquals(AuxType.AT_PAGESZ, h.auxv[6]); assertEquals(4096L, h.auxv[7])
        assertEquals(AuxType.AT_BASE, h.auxv[8]); assertEquals(sampleLinker.base, h.auxv[9])
        assertEquals(AuxType.AT_FLAGS, h.auxv[10]); assertEquals(0L, h.auxv[11])
        assertEquals(AuxType.AT_ENTRY, h.auxv[12]); assertEquals(sampleBinary.entry, h.auxv[13])
        assertEquals(AuxType.AT_HWCAP, h.auxv[14])
        assertEquals(AuxType.AT_HWCAP2, h.auxv[16])
        assertEquals(AuxType.AT_NULL, h.auxv[18]); assertEquals(0L, h.auxv[19])
    }

    @Test
    fun defaultProfileTargetsAndroid712Aarch64() {
        val p = LinkerProfile.DEFAULT_AARCH64
        assertEquals("/system/bin/linker64", p.interpreterPath)
        assertEquals("aarch64", p.abiPlatform)
        assertEquals(0L, p.abiHwcap)
        assertEquals(0L, p.abiHwcap2)
    }

    @Test
    fun profileLookupAlwaysReturnsDefaultUntilPhaseE() {
        // Phase B is fixed to Android 7.1.2; E.3/E.4 will branch this.
        listOf("7.1.2", "10", "12", "unknown").forEach { v ->
            assertEquals(LinkerProfile.DEFAULT_AARCH64, LinkerProfile.forGuestAndroidVersion(v))
        }
    }

    @Test
    fun handoffFailureCarriesEmptyAuxvAndZeroAddresses() {
        val h = LinkerHandoff.failure("test_reason")
        assertFalse(h.prepared)
        assertEquals("test_reason", h.failureReason)
        assertEquals(0, h.auxv.size)
        assertEquals(0L, h.binaryEntry)
        assertNotEquals(0, LinkerProfile.DEFAULT_AARCH64.interpreterPath.length)
    }
}
