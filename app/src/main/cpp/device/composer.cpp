#include "device/composer.h"

#include "core/logging.h"

#include <algorithm>

namespace avm::device {

Composer::Composer(GraphicBufferAllocator& allocator) : allocator_(allocator) {}

int Composer::createDisplay(const DisplayAttributes& attrs) {
    if (attrs.width <= 0 || attrs.height <= 0) return 0;
    std::lock_guard<std::mutex> g(lock_);
    const int id = nextDisplayId_++;
    DisplayState s;
    s.attrs = attrs;
    displays_[id] = s;
    return id;
}

bool Composer::destroyDisplay(int displayId) {
    std::lock_guard<std::mutex> g(lock_);
    return displays_.erase(displayId) > 0;
}

bool Composer::getDisplayAttributes(int displayId, DisplayAttributes& out) const {
    std::lock_guard<std::mutex> g(lock_);
    auto it = displays_.find(displayId);
    if (it == displays_.end()) return false;
    out = it->second.attrs;
    return true;
}

bool Composer::setLayers(int displayId, std::vector<ComposerLayer> layers) {
    std::lock_guard<std::mutex> g(lock_);
    auto it = displays_.find(displayId);
    if (it == displays_.end()) return false;
    it->second.layers = std::move(layers);
    return true;
}

Composer::PresentResult Composer::presentDisplay(int displayId) {
    PresentResult out{};
    std::lock_guard<std::mutex> g(lock_);
    auto it = displays_.find(displayId);
    if (it == displays_.end()) {
        out.failureReason = "unknown_display";
        return out;
    }
    const auto& layers = it->second.layers;
    if (layers.empty()) {
        out.failureReason = "no_layers";
        return out;
    }
    // Pick the topmost layer (highest z).
    auto top = std::max_element(layers.begin(), layers.end(),
        [](const ComposerLayer& a, const ComposerLayer& b) { return a.z < b.z; });
    GraphicBuffer gb;
    if (!allocator_.get(top->graphicBufferHandle, gb)) {
        out.failureReason = "missing_buffer";
        return out;
    }
    out.ok = true;
    out.presentedHandle = top->graphicBufferHandle;
    out.frameSequence = ++frameSequence_;
    return out;
}

int Composer::frameCount() const {
    std::lock_guard<std::mutex> g(lock_);
    return frameSequence_;
}

}  // namespace avm::device
