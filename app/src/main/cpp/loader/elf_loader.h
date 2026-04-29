#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace avm::loader {

/** Result of parsing the ELF64 ehdr + phdr table (no mapping yet). */
struct Elf64ParseResult {
  bool ok = false;
  std::string errorReason; // populated when ok == false

  // Parsed Ehdr fields the loader cares about.
  uint16_t type = 0;    // expected ET_DYN (3) for PIE
  uint16_t machine = 0; // expected EM_AARCH64 (0xB7)
  uint64_t entry = 0;
  uint64_t phoff = 0;
  uint16_t phentsize = 0;
  uint16_t phnum = 0;

  struct Segment {
    uint32_t type = 0;
    uint32_t flags = 0; // PF_R/W/X bits
    uint64_t offset = 0;
    uint64_t vaddr = 0;
    uint64_t filesz = 0;
    uint64_t memsz = 0;
    uint64_t align = 0;
  };
  std::vector<Segment> segments;
  std::string interpreterPath; // PT_INTERP NUL-terminated string, "" if none
};

/** Result of fully loading an ELF (parse + mmap). */
struct LoadedElf {
  bool mapped = false;
  std::string errorReason;

  void *baseAddress = nullptr;    // PT_LOAD base
  void *entryAddress = nullptr;   // ehdr.e_entry + base
  void *programHeaders = nullptr; // phoff offset within base
  int programHeaderCount = 0;
  int programHeaderSize = 0;
  size_t mappedSize = 0;
  std::string interpreterPath;
};

/**
 * Parse an ELF64 image from a byte buffer. Validates magic, class, endianness,
 * type and machine. Returns a structured result with phdrs decoded — does NOT
 * map any segments.
 *
 * Used both by the on-device loader and by the on-device verification JNI hook.
 */
Elf64ParseResult parseElf64(const uint8_t *data, size_t size);

/**
 * Reserve PROT_NONE address space and map every PT_LOAD with the right
 * protection. Returns the loaded image (entry/base/phdrs filled in).
 *
 * On hosts where `mmap(PROT_EXEC, MAP_ANONYMOUS)` is denied (Android
 * `untrusted_app` SELinux `execmem`), this falls back to a memfd-backed file
 * mapping. If both fail the result has `mapped == false` and `errorReason` set.
 */
LoadedElf mapElf64(const uint8_t *data, size_t size);

/** Release a mapping returned by [mapElf64]. */
void unmapElf64(LoadedElf &loaded);

/**
 * The forbidden-permission counterpart for Phase B. Loaders refuse to map
 * non-PIE ELFs and non-aarch64 ELFs and report a structured reason instead of
 * trying their luck.
 */
inline constexpr uint16_t kElfMachineAArch64 = 0xB7;
inline constexpr uint16_t kElfTypePie = 3;

} // namespace avm::loader
