#include "property/property_service.h"

#include <algorithm>

namespace avm::property {

PropertyService::PropertyService() {
    seedDefaultBootProperties();
}

void PropertyService::set(const std::string& key, const std::string& value) {
    std::lock_guard<std::mutex> g(lock_);
    values_[key] = value;
}

std::string PropertyService::get(const std::string& key, const std::string& fallback) const {
    std::lock_guard<std::mutex> g(lock_);
    auto it = values_.find(key);
    return it == values_.end() ? fallback : it->second;
}

bool PropertyService::has(const std::string& key) const {
    std::lock_guard<std::mutex> g(lock_);
    return values_.count(key) > 0;
}

size_t PropertyService::size() const {
    std::lock_guard<std::mutex> g(lock_);
    return values_.size();
}

std::vector<std::pair<std::string, std::string>> PropertyService::snapshot() const {
    std::lock_guard<std::mutex> g(lock_);
    std::vector<std::pair<std::string, std::string>> out(values_.begin(), values_.end());
    return out;
}

void PropertyService::markBootCompleted(int64_t timestampMillis) {
    std::lock_guard<std::mutex> g(lock_);
    bootCompleted_ = true;
    values_["sys.boot_completed"] = "1";
    values_["dev.bootcomplete"]   = "1";
    values_["ro.runtime.firstboot"] = std::to_string(timestampMillis);
}

bool PropertyService::bootCompleted() const {
    std::lock_guard<std::mutex> g(lock_);
    return bootCompleted_;
}

void PropertyService::seedDefaultBootProperties() {
    std::lock_guard<std::mutex> g(lock_);
    // The set in `phase-c-android-boot.md` § C.3.c. Values are conservative defaults that
    // the guest bionic is happy to see during boot.
    values_["ro.build.version.release"]  = "7.1.2";
    values_["ro.build.version.sdk"]      = "25";
    values_["ro.product.cpu.abi"]        = "arm64-v8a";
    values_["ro.product.cpu.abilist"]    = "arm64-v8a";
    values_["ro.product.cpu.abilist64"]  = "arm64-v8a";
    values_["ro.zygote"]                 = "zygote64";
    values_["ro.kernel.qemu"]            = "0";
    values_["init.svc.zygote"]           = "running";
    values_["init.svc.servicemanager"]   = "running";
    values_["init.svc.surfaceflinger"]   = "running";
    values_["dalvik.vm.heapsize"]        = "256m";
    values_["dalvik.vm.dex2oat-flags"]   = "--compiler-filter=quicken";
    values_["persist.sys.locale"]        = "en-US";
    values_["sys.boot_completed"]        = "0";
}

}  // namespace avm::property
