#include "binder/service_manager.h"

#include <cerrno>

namespace avm::binder {

namespace {
std::string utf16ToUtf8(const std::u16string& s) {
    std::string out;
    out.reserve(s.size());
    for (char16_t cp : s) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}
}  // namespace

ServiceManager::ServiceManager() = default;

uint32_t ServiceManager::addService(const std::string& name) {
    std::lock_guard<std::mutex> g(lock_);
    auto it = services_.find(name);
    if (it != services_.end()) return it->second;
    const uint32_t handle = nextHandle_++;
    services_.emplace(name, handle);
    return handle;
}

uint32_t ServiceManager::getService(const std::string& name) const {
    std::lock_guard<std::mutex> g(lock_);
    auto it = services_.find(name);
    return it == services_.end() ? 0 : it->second;
}

bool ServiceManager::hasService(const std::string& name) const {
    return getService(name) != 0;
}

std::vector<std::string> ServiceManager::listServices() const {
    std::lock_guard<std::mutex> g(lock_);
    std::vector<std::string> out;
    out.reserve(services_.size());
    for (const auto& [k, _] : services_) out.push_back(k);
    return out;
}

size_t ServiceManager::serviceCount() const {
    std::lock_guard<std::mutex> g(lock_);
    return services_.size();
}

bool ServiceManager::handleTransaction(Transaction& tx) {
    if (tx.targetHandle != kServiceManagerHandle) return false;
    Parcel& in = tx.data;
    Parcel reply;
    int32_t status = 0;
    switch (tx.code) {
        case svcmgr::GET_SERVICE_TRANSACTION:
        case svcmgr::CHECK_SERVICE_TRANSACTION: {
            const auto name16 = in.readString16();
            const std::string name = utf16ToUtf8(name16);
            const uint32_t h = getService(name);
            if (h == 0) {
                status = -ENOENT;
                reply.writeInt32(-ENOENT);
            } else {
                reply.writeInt32(0);
                reply.writeStrongBinderHandle(h);
            }
            break;
        }
        case svcmgr::ADD_SERVICE_TRANSACTION: {
            const auto name16 = in.readString16();
            const std::string name = utf16ToUtf8(name16);
            const uint32_t h = addService(name);
            reply.writeInt32(0);
            reply.writeUInt32(h);
            break;
        }
        case svcmgr::LIST_SERVICES_TRANSACTION: {
            const auto names = listServices();
            reply.writeInt32(0);
            reply.writeInt32(static_cast<int32_t>(names.size()));
            for (const auto& n : names) reply.writeString16FromUtf8(n);
            break;
        }
        default:
            status = -ENOSYS;
            reply.writeInt32(-ENOSYS);
            break;
    }
    tx.reply = std::move(reply);
    tx.status = status;
    return true;
}

}  // namespace avm::binder
