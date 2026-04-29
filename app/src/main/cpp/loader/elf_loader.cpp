#include "loader/elf_loader.h"

#include "core/logging.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <linux/memfd.h>
#include <sys/syscall.h>
#endif

namespace avm::loader {

namespace {

constexpr uint8_t kElfMag0 = 0x7F;
constexpr uint8_t kElfMag1 = 'E';
constexpr uint8_t kElfMag2 = 'L';
constexpr uint8_t kElfMag3 = 'F';
constexpr uint8_t kElfClass64 = 2;
constexpr uint8_t kElfData2Lsb = 1;
constexpr uint8_t kElfVersionCurrent = 1;
constexpr uint32_t kPtLoad = 1;
constexpr uint32_t kPtInterp = 3;
constexpr uint64_t kPageSize = 4096;

template <class T> T readLe(const uint8_t *p) {
  T v{};
  std::memcpy(&v, p, sizeof(T));
  return v;
}

uint64_t alignUp(uint64_t value, uint64_t align) {
  if (align <= 1)
    return value;
  return (value + align - 1) & ~(align - 1);
}

uint64_t alignDown(uint64_t value, uint64_t align) {
  if (align <= 1)
    return value;
  return value & ~(align - 1);
}

int protectionFromFlags(uint32_t pflags) {
  int prot = 0;
  if (pflags & 0x4)
    prot |= PROT_READ;
  if (pflags & 0x2)
    prot |= PROT_WRITE;
  if (pflags & 0x1)
    prot |= PROT_EXEC;
  return prot;
}

void *programHeadersAddress(const Elf64ParseResult &parsed, void *base,
                            uint64_t lo) {
  for (const auto &seg : parsed.segments) {
    if (seg.type != kPtLoad)
      continue;
    const uint64_t segFileStart = seg.offset;
    const uint64_t segFileEnd = seg.offset + seg.filesz;
    if (parsed.phoff >= segFileStart && parsed.phoff < segFileEnd) {
      const uint64_t runtimeOffset =
          (seg.vaddr - lo) + (parsed.phoff - seg.offset);
      return static_cast<uint8_t *>(base) + runtimeOffset;
    }
  }
  return nullptr;
}

#if defined(__ANDROID__)
int memfdCreateCompat(const char *name, unsigned int flags) {
  // Bionic in some Android versions does not expose memfd_create as a libc
  // symbol; fall back to the raw syscall.
  return static_cast<int>(syscall(SYS_memfd_create, name, flags));
}
#endif

} // namespace

Elf64ParseResult parseElf64(const uint8_t *data, size_t size) {
  Elf64ParseResult out{};
  if (data == nullptr || size < 64) {
    out.errorReason = "ehdr_truncated";
    return out;
  }
  if (data[0] != kElfMag0 || data[1] != kElfMag1 || data[2] != kElfMag2 ||
      data[3] != kElfMag3) {
    out.errorReason = "magic_mismatch";
    return out;
  }
  if (data[4] != kElfClass64) {
    out.errorReason = "not_elfclass64";
    return out;
  }
  if (data[5] != kElfData2Lsb) {
    out.errorReason = "not_little_endian";
    return out;
  }
  if (data[6] != kElfVersionCurrent) {
    out.errorReason = "unsupported_ident_version";
    return out;
  }

  out.type = readLe<uint16_t>(data + 16);
  out.machine = readLe<uint16_t>(data + 18);
  const uint32_t version = readLe<uint32_t>(data + 20);
  out.entry = readLe<uint64_t>(data + 24);
  out.phoff = readLe<uint64_t>(data + 32);
  out.phentsize = readLe<uint16_t>(data + 54);
  out.phnum = readLe<uint16_t>(data + 56);

  if (version != 1) {
    out.errorReason = "unsupported_version";
    return out;
  }
  if (out.type != kElfTypePie) {
    out.errorReason = "not_pie";
    return out;
  }
  if (out.machine != kElfMachineAArch64) {
    out.errorReason = "not_aarch64";
    return out;
  }
  if (out.phentsize != 56) {
    out.errorReason = "unexpected_phentsize";
    return out;
  }
  const size_t phdrTableEnd =
      static_cast<size_t>(out.phoff) +
      static_cast<size_t>(out.phentsize) * static_cast<size_t>(out.phnum);
  if (phdrTableEnd > size) {
    out.errorReason = "phdr_table_truncated";
    return out;
  }

  out.segments.reserve(out.phnum);
  for (uint16_t i = 0; i < out.phnum; ++i) {
    const uint8_t *p = data + out.phoff + i * out.phentsize;
    Elf64ParseResult::Segment seg;
    seg.type = readLe<uint32_t>(p + 0);
    seg.flags = readLe<uint32_t>(p + 4);
    seg.offset = readLe<uint64_t>(p + 8);
    seg.vaddr = readLe<uint64_t>(p + 16);
    seg.filesz = readLe<uint64_t>(p + 32);
    seg.memsz = readLe<uint64_t>(p + 40);
    seg.align = readLe<uint64_t>(p + 48);
    out.segments.push_back(seg);

    if (seg.type == kPtInterp) {
      const size_t end =
          static_cast<size_t>(seg.offset) + static_cast<size_t>(seg.filesz);
      if (seg.filesz == 0 || end > size) {
        out.errorReason = "pt_interp_truncated";
        return out;
      }
      const char *str = reinterpret_cast<const char *>(data + seg.offset);
      const size_t len = ::strnlen(str, static_cast<size_t>(seg.filesz));
      out.interpreterPath.assign(str, len);
    }
  }
  out.ok = true;
  return out;
}

LoadedElf mapElf64(const uint8_t *data, size_t size) {
  LoadedElf out{};
  const auto parsed = parseElf64(data, size);
  if (!parsed.ok) {
    out.errorReason = "parse_failed:" + parsed.errorReason;
    return out;
  }

  uint64_t lo = UINT64_MAX;
  uint64_t hi = 0;
  for (const auto &seg : parsed.segments) {
    if (seg.type != kPtLoad)
      continue;
    const uint64_t segLo = alignDown(seg.vaddr, kPageSize);
    const uint64_t segHi = alignUp(seg.vaddr + seg.memsz, kPageSize);
    if (segLo < lo)
      lo = segLo;
    if (segHi > hi)
      hi = segHi;
  }
  if (lo == UINT64_MAX || hi <= lo) {
    out.errorReason = "no_pt_load";
    return out;
  }
  const size_t span = static_cast<size_t>(hi - lo);

  void *base =
      ::mmap(nullptr, span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (base == MAP_FAILED) {
    out.errorReason =
        std::string("mmap_reserve_failed:errno=") + std::to_string(errno);
    return out;
  }

  auto unmapAndFail = [&](const std::string &reason) {
    ::munmap(base, span);
    out.errorReason = reason;
    return out;
  };

  for (const auto &seg : parsed.segments) {
    if (seg.type != kPtLoad)
      continue;
    const uint64_t segLoAligned = alignDown(seg.vaddr, kPageSize);
    const uint64_t segHiAligned = alignUp(seg.vaddr + seg.memsz, kPageSize);
    const size_t segSpan = static_cast<size_t>(segHiAligned - segLoAligned);
    void *segDest = static_cast<uint8_t *>(base) + (segLoAligned - lo);
    const int prot = protectionFromFlags(seg.flags);

    void *m = ::mmap(segDest, segSpan, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (m == MAP_FAILED) {
      return unmapAndFail(std::string("mmap_segment_failed:errno=") +
                          std::to_string(errno));
    }
    if (seg.filesz > 0) {
      const size_t copyOffset = static_cast<size_t>(seg.vaddr - segLoAligned);
      const size_t copyBytes = static_cast<size_t>(seg.filesz);
      if (static_cast<size_t>(seg.offset) + copyBytes > size) {
        return unmapAndFail("segment_data_truncated");
      }
      std::memcpy(static_cast<uint8_t *>(segDest) + copyOffset,
                  data + seg.offset, copyBytes);
    }
    if ((prot & PROT_EXEC) != 0) {
      __builtin___clear_cache(static_cast<char *>(segDest),
                              static_cast<char *>(segDest) + segSpan);
      if (::mprotect(segDest, segSpan, prot) != 0) {
        const int savedErrno = errno;
        AVM_LOGW("mprotect PROT_EXEC failed: errno=%d, falling back to memfd",
                 savedErrno);
#if defined(__ANDROID__)
        int fd = memfdCreateCompat("avm-elf-seg", 0);
        if (fd < 0) {
          return unmapAndFail(std::string("memfd_create_failed:errno=") +
                              std::to_string(errno));
        }
        if (::ftruncate(fd, segSpan) != 0) {
          ::close(fd);
          return unmapAndFail("memfd_ftruncate_failed");
        }
        if (::pwrite(fd, segDest, segSpan, 0) !=
            static_cast<ssize_t>(segSpan)) {
          ::close(fd);
          return unmapAndFail("memfd_pwrite_failed");
        }
        if (::munmap(segDest, segSpan) != 0) {
          ::close(fd);
          return unmapAndFail("munmap_anon_for_memfd_failed");
        }
        void *fm =
            ::mmap(segDest, segSpan, prot, MAP_PRIVATE | MAP_FIXED, fd, 0);
        ::close(fd);
        if (fm == MAP_FAILED) {
          return unmapAndFail(std::string("memfd_mmap_failed:errno=") +
                              std::to_string(errno));
        }
#else
        return unmapAndFail("execmem_denied_no_memfd_available");
#endif
      }
    } else {
      if (::mprotect(segDest, segSpan, prot) != 0) {
        return unmapAndFail(std::string("mprotect_failed:errno=") +
                            std::to_string(errno));
      }
    }
  }

  out.mapped = true;
  out.baseAddress = base;
  out.entryAddress = static_cast<uint8_t *>(base) + (parsed.entry - lo);
  out.programHeaders = programHeadersAddress(parsed, base, lo);
  out.programHeaderCount = parsed.phnum;
  out.programHeaderSize = parsed.phentsize;
  out.mappedSize = span;
  out.interpreterPath = parsed.interpreterPath;
  return out;
}

void unmapElf64(LoadedElf &loaded) {
  if (loaded.mapped && loaded.baseAddress != nullptr && loaded.mappedSize > 0) {
    ::munmap(loaded.baseAddress, loaded.mappedSize);
  }
  loaded = LoadedElf{};
}

} // namespace avm::loader
