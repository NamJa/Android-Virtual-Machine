package dev.jongwoo.androidvm.vm

/**
 * Pure-Kotlin twin of `app/src/main/cpp/loader/linker_bridge.h`. The native linker bridge
 * is the production code; this Kotlin-side builder pins the contract that JVM unit tests
 * can verify off-device. When B.3 runs end-to-end on a real device the C++ code is what
 * actually transfers control to the guest linker.
 */
data class LinkerProfile(
    val interpreterPath: String,
    val abiPlatform: String,
    val abiHwcap: Long,
    val abiHwcap2: Long,
) {
    companion object {
        /** Phase B MVP: Android 7.1.2 / aarch64. */
        val DEFAULT_AARCH64: LinkerProfile = LinkerProfile(
            interpreterPath = "/system/bin/linker64",
            abiPlatform = "aarch64",
            abiHwcap = 0,
            abiHwcap2 = 0,
        )

        fun forGuestAndroidVersion(@Suppress("UNUSED_PARAMETER") version: String): LinkerProfile =
            DEFAULT_AARCH64
    }
}

/** Address layout of a mapped ELF image. Passed to the linker handoff builder. */
data class ElfMapping(
    val base: Long,
    val entry: Long,
    val programHeaders: Long,
    val programHeaderCount: Int,
    val programHeaderSize: Int,
)

/**
 * Result of preparing the guest stack + auxv for the linker. Phase B.5 takes one of these
 * and jumps to [linkerEntry] on a guest thread.
 */
data class LinkerHandoff(
    val prepared: Boolean,
    val failureReason: String,
    val binaryEntry: Long,
    val linkerEntry: Long,
    val binaryBase: Long,
    val linkerBase: Long,
    val programHeaders: Long,
    val programHeaderCount: Int,
    val programHeaderSize: Int,
    val stackTop: Long,
    val stackBase: Long,
    val stackSize: Long,
    val profile: LinkerProfile,
    val auxv: LongArray,
) {
    companion object {
        fun failure(reason: String): LinkerHandoff = LinkerHandoff(
            prepared = false, failureReason = reason,
            binaryEntry = 0, linkerEntry = 0, binaryBase = 0, linkerBase = 0,
            programHeaders = 0, programHeaderCount = 0, programHeaderSize = 0,
            stackTop = 0, stackBase = 0, stackSize = 0,
            profile = LinkerProfile.DEFAULT_AARCH64, auxv = LongArray(0),
        )
    }
}

/**
 * Build a [LinkerHandoff] from a parsed binary + linker mapping. Mirrors
 * `loader/linker_bridge.cpp:prepareLinkerHandoff`.
 *
 * Validation is intentionally narrow: the handoff is structural data only. Real address
 * sanity (e.g., the linker base does not collide with the host loader's address space) is
 * the on-device runtime's responsibility — the JVM never observes those addresses.
 */
object LinkerBridge {
    fun prepareHandoff(
        binary: ElfMapping,
        linker: ElfMapping,
        profile: LinkerProfile,
        stackTop: Long,
        stackBase: Long,
        stackSize: Long,
    ): LinkerHandoff {
        if (binary.base == 0L || binary.entry == 0L) {
            return LinkerHandoff.failure("binary_mapping_invalid")
        }
        if (stackTop == 0L || stackBase == 0L || stackSize <= 0) {
            return LinkerHandoff.failure("stack_invalid")
        }
        if (binary.base == linker.base) {
            // Phase B requires the binary and linker images to live at distinct base
            // addresses so the linker's own dl_iterate_phdr never sees the binary.
            return LinkerHandoff.failure("namespace_overlap")
        }
        val auxv = AuxVectorBuilder()
            .push(AuxType.AT_PHDR, binary.programHeaders)
            .push(AuxType.AT_PHENT, binary.programHeaderSize.toLong())
            .push(AuxType.AT_PHNUM, binary.programHeaderCount.toLong())
            .push(AuxType.AT_PAGESZ, 4096)
            .push(AuxType.AT_BASE, linker.base)
            .push(AuxType.AT_FLAGS, 0)
            .push(AuxType.AT_ENTRY, binary.entry)
            .push(AuxType.AT_HWCAP, profile.abiHwcap)
            .push(AuxType.AT_HWCAP2, profile.abiHwcap2)
            .build()
        return LinkerHandoff(
            prepared = true,
            failureReason = "",
            binaryEntry = binary.entry,
            linkerEntry = linker.entry,
            binaryBase = binary.base,
            linkerBase = linker.base,
            programHeaders = binary.programHeaders,
            programHeaderCount = binary.programHeaderCount,
            programHeaderSize = binary.programHeaderSize,
            stackTop = stackTop,
            stackBase = stackBase,
            stackSize = stackSize,
            profile = profile,
            auxv = auxv,
        )
    }
}
