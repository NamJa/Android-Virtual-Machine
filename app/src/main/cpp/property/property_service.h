#pragma once

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace avm::property {

/**
 * Phase C property service. Stores per-instance key/value pairs in memory plus a
 * deterministic seed of boot-time properties (`init.svc.zygote=running`, …) so the guest
 * `__system_property_get` reads see what bionic expects.
 *
 * The actual mmap'd area that the guest libc reads is built lazily by [PropertyArea].
 * Phase E.3/E.4 will add Android 10/12 trie compatibility.
 */
class PropertyService {
public:
    PropertyService();

    void set(const std::string& key, const std::string& value);
    /** Returns the value or the supplied fallback when missing. */
    std::string get(const std::string& key, const std::string& fallback = {}) const;
    bool has(const std::string& key) const;
    size_t size() const;
    std::vector<std::pair<std::string, std::string>> snapshot() const;

    /**
     * Mark the boot as completed — this is a privileged operation in Android (only
     * `system_server` may call it) and is wired here so Phase C.5's regression can pin
     * the transition.
     */
    void markBootCompleted(int64_t timestampMillis);
    bool bootCompleted() const;

    /** Pre-load the doc's mandatory boot properties. */
    void seedDefaultBootProperties();

private:
    mutable std::mutex lock_;
    std::map<std::string, std::string> values_;
    bool bootCompleted_ = false;
};

}  // namespace avm::property
