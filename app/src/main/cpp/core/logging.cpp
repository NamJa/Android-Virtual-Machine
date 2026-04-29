#include "core/logging.h"

#include <chrono>

namespace avm::core {

int64_t nowMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

}  // namespace avm::core
