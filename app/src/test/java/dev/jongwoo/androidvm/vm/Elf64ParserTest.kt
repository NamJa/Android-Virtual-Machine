package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the byte-level contract that `loader/elf_loader.cpp` and [Elf64Parser] share. Adding
 * a new ELF rejection branch means: add the rejection in C++ + Kotlin in the same change,
 * then add a test case here that exercises it.
 */
class Elf64ParserTest {
    @Test
    fun parsesMinimalPieFixtureSuccessfully() {
        val bytes = Elf64Fixtures.minimalPie(entryOffset = 0x1234L)
        val r = Elf64Parser.parse(bytes)
        assertTrue("parse should accept the synthesized PIE; reason=${r.errorReason}", r.ok)
        assertEquals(Elf64Parser.TYPE_PIE, r.type)
        assertEquals(Elf64Parser.MACHINE_AARCH64, r.machine)
        assertEquals(0x1234L, r.entry)
        assertEquals(64L, r.phoff)
        assertEquals(56, r.phentsize)
        assertEquals(2, r.phnum)
        assertTrue(r.segments.any { it.type.toInt() == Elf64Parser.PT_LOAD })
        assertEquals("/system/bin/linker64", r.interpreterPath)
    }

    @Test
    fun rejectsTruncatedHeader() {
        val r = Elf64Parser.parse(ByteArray(63))
        assertFalse(r.ok)
        assertEquals("ehdr_truncated", r.errorReason)
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = Elf64Fixtures.minimalPie()
        bytes[1] = 'X'.code.toByte()
        val r = Elf64Parser.parse(bytes)
        assertFalse(r.ok)
        assertEquals("magic_mismatch", r.errorReason)
    }

    @Test
    fun rejectsElfClass32() {
        val bytes = Elf64Fixtures.minimalPie()
        bytes[4] = 1  // ELFCLASS32
        assertEquals("not_elfclass64", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun rejectsBigEndian() {
        val bytes = Elf64Fixtures.minimalPie()
        bytes[5] = 2
        assertEquals("not_little_endian", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun rejectsUnsupportedIdentVersion() {
        val bytes = Elf64Fixtures.minimalPie()
        bytes[6] = 0
        assertEquals("unsupported_ident_version", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun rejectsNonPie() {
        val bytes = Elf64Fixtures.minimalPie(type = 2)  // ET_EXEC
        assertEquals("not_pie", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun rejectsNonAarch64Machine() {
        // EM_X86_64 = 62
        val bytes = Elf64Fixtures.minimalPie(machine = 62)
        assertEquals("not_aarch64", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun acceptsNoInterpreter() {
        val bytes = Elf64Fixtures.minimalPie(interpreter = null)
        val r = Elf64Parser.parse(bytes)
        assertTrue(r.ok)
        assertEquals("", r.interpreterPath)
        assertEquals(1, r.phnum)
    }

    @Test
    fun rejectsTruncatedPhdrTable() {
        val bytes = Elf64Fixtures.minimalPie()
        // Inflate phnum so the phdr table claims to extend past the file.
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(56, 1000.toShort())
        assertEquals("phdr_table_truncated", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun rejectsInterpreterTruncation() {
        val bytes = Elf64Fixtures.minimalPie()
        // Inflate the interpreter PT_INTERP filesz beyond file.
        val phoff = 64
        val phentsize = 56
        val interpOffsetWithinPhdr = phoff + phentsize  // second phdr starts here
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putLong(interpOffsetWithinPhdr + 32, (bytes.size + 1024).toLong())
        assertEquals("pt_interp_truncated", Elf64Parser.parse(bytes).errorReason)
    }

    @Test
    fun parserPrefersExactConstantMatchesForMachineAndType() {
        // Lock the constants — accidentally renumbering them would break the on-device loader.
        assertEquals(0xB7, Elf64Parser.MACHINE_AARCH64)
        assertEquals(3, Elf64Parser.TYPE_PIE)
        assertNotEquals(Elf64Parser.MACHINE_AARCH64, Elf64Parser.TYPE_PIE)
    }
}
