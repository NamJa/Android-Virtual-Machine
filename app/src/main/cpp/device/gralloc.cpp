#include "device/gralloc.h"

namespace avm::device {

GraphicBufferAllocator::GraphicBufferAllocator(AshmemDevice& ashmem) : ashmem_(ashmem) {}

int GraphicBufferAllocator::bytesPerPixel(GraphicPixelFormat fmt) {
    switch (fmt) {
        case GraphicPixelFormat::RGBA_8888:
        case GraphicPixelFormat::RGBX_8888:
        case GraphicPixelFormat::BGRA_8888:
            return 4;
        case GraphicPixelFormat::RGB_565:
            return 2;
        default:
            return 0;
    }
}

int GraphicBufferAllocator::allocate(int32_t width, int32_t height, GraphicPixelFormat format) {
    if (width <= 0 || height <= 0) return 0;
    const int bpp = bytesPerPixel(format);
    if (bpp == 0) return 0;

    const int fd = ashmem_.allocate();
    if (fd < 0) return 0;
    const int64_t size = static_cast<int64_t>(width) * height * bpp;
    int rc = ashmem_.ioctl(fd, ASHMEM_SET_SIZE, &const_cast<int64_t&>(size));
    if (rc != 0) {
        ashmem_.release(fd);
        return 0;
    }

    std::lock_guard<std::mutex> g(lock_);
    GraphicBuffer b;
    b.handle = nextHandle_++;
    b.ashmemFd = fd;
    b.width = width;
    b.height = height;
    b.stride = width;
    b.format = format;
    b.sizeBytes = size;
    buffers_[b.handle] = b;
    return b.handle;
}

bool GraphicBufferAllocator::free(int handle) {
    int fd = -1;
    {
        std::lock_guard<std::mutex> g(lock_);
        auto it = buffers_.find(handle);
        if (it == buffers_.end()) return false;
        fd = it->second.ashmemFd;
        buffers_.erase(it);
    }
    if (fd >= 0) ashmem_.release(fd);
    return true;
}

bool GraphicBufferAllocator::get(int handle, GraphicBuffer& out) const {
    std::lock_guard<std::mutex> g(lock_);
    auto it = buffers_.find(handle);
    if (it == buffers_.end()) return false;
    out = it->second;
    return true;
}

size_t GraphicBufferAllocator::allocatedCount() const {
    std::lock_guard<std::mutex> g(lock_);
    return buffers_.size();
}

}  // namespace avm::device
