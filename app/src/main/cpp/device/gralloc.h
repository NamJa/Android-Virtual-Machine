#pragma once

#include "device/ashmem.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace avm::device {

/** Pixel formats Phase C supports. RGBA_8888 is the SurfaceFlinger primary. */
enum class GraphicPixelFormat {
    UNKNOWN = 0,
    RGBA_8888 = 1,   // 4 bytes per pixel
    RGBX_8888 = 2,
    BGRA_8888 = 5,
    RGB_565   = 4,
};

/** Sufficient metadata for Phase C composer to memcpy out the buffer. */
struct GraphicBuffer {
    int handle = 0;
    int ashmemFd = -1;
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;          // pixels per row
    GraphicPixelFormat format = GraphicPixelFormat::UNKNOWN;
    int64_t sizeBytes = 0;
};

/**
 * Phase C user-space gralloc stub. Each `allocate(...)` returns a `GraphicBuffer` whose
 * memory is backed by an ashmem region (so the same fd can be passed across binder
 * transactions in Phase D).
 */
class GraphicBufferAllocator {
public:
    explicit GraphicBufferAllocator(AshmemDevice& ashmem);

    /** Returns the new buffer's `handle` (>= 1) or 0 on failure. */
    int allocate(int32_t width, int32_t height, GraphicPixelFormat format);
    bool free(int handle);

    bool get(int handle, GraphicBuffer& out) const;
    size_t allocatedCount() const;

    static int bytesPerPixel(GraphicPixelFormat fmt);

private:
    mutable std::mutex lock_;
    AshmemDevice& ashmem_;
    int nextHandle_ = 1;
    std::unordered_map<int, GraphicBuffer> buffers_;
};

}  // namespace avm::device
