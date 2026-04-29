#pragma once

#include "device/gralloc.h"

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace avm::device {

/** A single layer pinned at present time. Phase C only routes the topmost layer. */
struct ComposerLayer {
    int graphicBufferHandle = 0;
    int32_t z = 0;
    int32_t srcLeft = 0, srcTop = 0, srcRight = 0, srcBottom = 0;
    int32_t dstLeft = 0, dstTop = 0, dstRight = 0, dstBottom = 0;
};

struct DisplayAttributes {
    int32_t width = 0;
    int32_t height = 0;
    int32_t densityDpi = 320;
};

/**
 * Phase C `IComposer` stub. SurfaceFlinger calls `presentDisplay(layers)` and we copy the
 * topmost layer's GraphicBuffer onto the host Surface (the actual `ANativeWindow` lock /
 * unlockAndPost lives in `jni/vm_native_bridge.cpp`).
 */
class Composer {
public:
    explicit Composer(GraphicBufferAllocator& allocator);

    int  createDisplay(const DisplayAttributes& attrs);
    bool destroyDisplay(int displayId);
    bool getDisplayAttributes(int displayId, DisplayAttributes& out) const;

    /** Replace the layers on a display. */
    bool setLayers(int displayId, std::vector<ComposerLayer> layers);

    struct PresentResult {
        bool ok = false;
        std::string failureReason;
        int presentedHandle = 0;
        int64_t presentTimestampMillis = 0;
        int frameSequence = 0;
    };

    /** Pick the topmost layer's buffer; the actual host Surface copy is done by the JNI. */
    PresentResult presentDisplay(int displayId);

    int  frameCount() const;

private:
    mutable std::mutex lock_;
    GraphicBufferAllocator& allocator_;
    int nextDisplayId_ = 1;
    int frameSequence_ = 0;

    struct DisplayState {
        DisplayAttributes attrs;
        std::vector<ComposerLayer> layers;
    };
    std::unordered_map<int, DisplayState> displays_;
};

}  // namespace avm::device
