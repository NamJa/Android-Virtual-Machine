#include "loader/aux_vector.h"

namespace avm::loader {

void AuxVector::push(uint64_t type, uint64_t value) {
    if (type == AT_NULL) return;
    entries_.push_back({type, value});
}

std::vector<uint64_t> AuxVector::data() const {
    std::vector<uint64_t> out;
    out.reserve(entries_.size() * 2 + 2);
    for (const auto& e : entries_) {
        out.push_back(e.type);
        out.push_back(e.value);
    }
    out.push_back(AT_NULL);
    out.push_back(0);
    finalized_ = true;
    return out;
}

}  // namespace avm::loader
