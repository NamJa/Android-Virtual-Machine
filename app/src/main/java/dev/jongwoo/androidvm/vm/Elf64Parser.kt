package dev.jongwoo.androidvm.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin ELF64 parser that mirrors `app/src/main/cpp/loader/elf_loader.cpp`. The native
 * loader is the production code path; this Kotlin parser is the executable specification of
 * what the C++ parser must produce, so JVM unit tests can pin the byte-level contract on a
 * machine without an Android device.
 *
 * Both parsers reject:
 *  - non-ELF magic
 *  - ELFCLASS32 / big-endian / unsupported version
 *  - non-PIE (`ET_EXEC`)
 *  - non-aarch64 (`EM_AARCH64 = 0xB7`) — Phase E.8 will lift the ABI gate.
 *
 * Both parsers extract `PT_INTERP` if present.
 */
object Elf64Parser {
    const val MACHINE_AARCH64: Int = 0xB7
    const val TYPE_PIE: Int = 3   // ET_DYN

    const val PT_LOAD: Int = 1
    const val PT_INTERP: Int = 3

    fun parse(bytes: ByteArray): Elf64ParseResult {
        if (bytes.size < 64) return Elf64ParseResult.failure("ehdr_truncated")
        val ident = bytes
        if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() ||
            ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
        ) return Elf64ParseResult.failure("magic_mismatch")
        if (ident[4] != 2.toByte()) return Elf64ParseResult.failure("not_elfclass64")
        if (ident[5] != 1.toByte()) return Elf64ParseResult.failure("not_little_endian")
        if (ident[6] != 1.toByte()) return Elf64ParseResult.failure("unsupported_ident_version")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.getShort(16).toInt() and 0xFFFF
        val machine = buf.getShort(18).toInt() and 0xFFFF
        val version = buf.getInt(20).toLong() and 0xFFFFFFFFL
        val entry = buf.getLong(24)
        val phoff = buf.getLong(32)
        val phentsize = buf.getShort(54).toInt() and 0xFFFF
        val phnum = buf.getShort(56).toInt() and 0xFFFF

        if (version != 1L) return Elf64ParseResult.failure("unsupported_version")
        if (type != TYPE_PIE) return Elf64ParseResult.failure("not_pie")
        if (machine != MACHINE_AARCH64) return Elf64ParseResult.failure("not_aarch64")
        if (phentsize != 56) return Elf64ParseResult.failure("unexpected_phentsize")
        val phdrTableEnd = phoff + phentsize.toLong() * phnum.toLong()
        if (phdrTableEnd < 0 || phdrTableEnd > bytes.size.toLong()) {
            return Elf64ParseResult.failure("phdr_table_truncated")
        }

        val segments = ArrayList<Elf64Segment>(phnum)
        var interpreter = ""
        for (i in 0 until phnum) {
            val base = (phoff + i.toLong() * phentsize.toLong()).toInt()
            val seg = Elf64Segment(
                type = buf.getInt(base + 0).toLong() and 0xFFFFFFFFL,
                flags = buf.getInt(base + 4).toLong() and 0xFFFFFFFFL,
                offset = buf.getLong(base + 8),
                vaddr = buf.getLong(base + 16),
                filesz = buf.getLong(base + 32),
                memsz = buf.getLong(base + 40),
                align = buf.getLong(base + 48),
            )
            segments += seg
            if (seg.type.toInt() == PT_INTERP) {
                val end = (seg.offset + seg.filesz).toInt()
                if (seg.filesz <= 0 || end > bytes.size) {
                    return Elf64ParseResult.failure("pt_interp_truncated")
                }
                val rawLen = seg.filesz.toInt()
                var len = 0
                while (len < rawLen && bytes[seg.offset.toInt() + len] != 0.toByte()) len++
                interpreter = String(bytes, seg.offset.toInt(), len, Charsets.US_ASCII)
            }
        }
        return Elf64ParseResult(
            ok = true,
            errorReason = "",
            type = type,
            machine = machine,
            entry = entry,
            phoff = phoff,
            phentsize = phentsize,
            phnum = phnum,
            segments = segments,
            interpreterPath = interpreter,
        )
    }
}

data class Elf64Segment(
    val type: Long,
    val flags: Long,
    val offset: Long,
    val vaddr: Long,
    val filesz: Long,
    val memsz: Long,
    val align: Long,
)

data class Elf64ParseResult(
    val ok: Boolean,
    val errorReason: String,
    val type: Int = 0,
    val machine: Int = 0,
    val entry: Long = 0,
    val phoff: Long = 0,
    val phentsize: Int = 0,
    val phnum: Int = 0,
    val segments: List<Elf64Segment> = emptyList(),
    val interpreterPath: String = "",
) {
    companion object {
        fun failure(reason: String): Elf64ParseResult = Elf64ParseResult(false, reason)
    }
}
