package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxVectorBuilderTest {
    @Test
    fun emptyBuilderProducesAtNullTerminatorOnly() {
        val data = AuxVectorBuilder().build()
        assertEquals(2, data.size)
        assertEquals(AuxType.AT_NULL, data[0])
        assertEquals(0L, data[1])
    }

    @Test
    fun pushesEntriesInInsertionOrderAndAppendsTerminator() {
        val builder = AuxVectorBuilder()
            .push(AuxType.AT_PHDR, 0x1000)
            .push(AuxType.AT_PHENT, 56)
            .push(AuxType.AT_PHNUM, 7)
            .push(AuxType.AT_BASE, 0x7000_0000)
            .push(AuxType.AT_ENTRY, 0x1234)
            .push(AuxType.AT_PAGESZ, 4096)
        val data = builder.build()
        // 6 entries × 2 + AT_NULL terminator = 14
        assertEquals(14, data.size)
        assertEquals(AuxType.AT_PHDR, data[0]); assertEquals(0x1000L, data[1])
        assertEquals(AuxType.AT_PHENT, data[2]); assertEquals(56L, data[3])
        assertEquals(AuxType.AT_PHNUM, data[4]); assertEquals(7L, data[5])
        assertEquals(AuxType.AT_BASE, data[6]); assertEquals(0x7000_0000L, data[7])
        assertEquals(AuxType.AT_ENTRY, data[8]); assertEquals(0x1234L, data[9])
        assertEquals(AuxType.AT_PAGESZ, data[10]); assertEquals(4096L, data[11])
        assertEquals(AuxType.AT_NULL, data[12]); assertEquals(0L, data[13])
    }

    @Test
    fun pushingAtNullThrows() {
        val threw = runCatching { AuxVectorBuilder().push(AuxType.AT_NULL, 0) }.isFailure
        assertTrue("AT_NULL should not be pushable manually", threw)
    }

    @Test
    fun atTypeConstantsAreStable() {
        // These match `<elf.h>`. Renumbering them would silently break linker handoff.
        assertEquals(0L, AuxType.AT_NULL)
        assertEquals(3L, AuxType.AT_PHDR)
        assertEquals(4L, AuxType.AT_PHENT)
        assertEquals(5L, AuxType.AT_PHNUM)
        assertEquals(6L, AuxType.AT_PAGESZ)
        assertEquals(7L, AuxType.AT_BASE)
        assertEquals(9L, AuxType.AT_ENTRY)
        assertEquals(15L, AuxType.AT_PLATFORM)
        assertEquals(16L, AuxType.AT_HWCAP)
        assertEquals(25L, AuxType.AT_RANDOM)
        assertEquals(26L, AuxType.AT_HWCAP2)
        // Sanity: distinct.
        val codes = listOf(
            AuxType.AT_NULL, AuxType.AT_PHDR, AuxType.AT_PHENT, AuxType.AT_PHNUM,
            AuxType.AT_PAGESZ, AuxType.AT_BASE, AuxType.AT_FLAGS, AuxType.AT_ENTRY,
            AuxType.AT_UID, AuxType.AT_EUID, AuxType.AT_GID, AuxType.AT_EGID,
            AuxType.AT_PLATFORM, AuxType.AT_HWCAP, AuxType.AT_CLKTCK,
            AuxType.AT_RANDOM, AuxType.AT_HWCAP2,
        )
        assertEquals("AT_* constants must be pairwise distinct", codes.size, codes.toSet().size)
        assertNotEquals(AuxType.AT_NULL, AuxType.AT_PHDR)
    }
}
