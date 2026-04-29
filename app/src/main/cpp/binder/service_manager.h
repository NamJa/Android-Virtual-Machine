#pragma once

#include "binder/parcel.h"
#include "binder/transaction.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace avm::binder {

/** Service Manager transaction codes — `frameworks/native/include/binder/IServiceManager.h`. */
namespace svcmgr {
inline constexpr uint32_t GET_SERVICE_TRANSACTION    = 0x00000001;
inline constexpr uint32_t CHECK_SERVICE_TRANSACTION  = 0x00000002;
inline constexpr uint32_t ADD_SERVICE_TRANSACTION    = 0x00000003;
inline constexpr uint32_t LIST_SERVICES_TRANSACTION  = 0x00000004;
}  // namespace svcmgr

/**
 * Phase C in-process service manager. Each service is identified by a stable string name
 * and gets a sequential strong handle. Phase D will hook this up to real cross-process
 * traffic.
 */
class ServiceManager {
public:
    ServiceManager();

    /** Register a service. Returns the assigned handle (>= 1). */
    uint32_t addService(const std::string& name);

    /** Look up a service. Returns 0 when missing. */
    uint32_t getService(const std::string& name) const;
    bool     hasService(const std::string& name) const;
    std::vector<std::string> listServices() const;

    /**
     * Handle a transaction targeting handle 0 (the service manager itself). Writes the
     * reply into [tx->reply] and sets [tx->status]. Returns true when the transaction was
     * recognised.
     */
    bool handleTransaction(Transaction& tx);

    size_t serviceCount() const;

private:
    mutable std::mutex lock_;
    std::unordered_map<std::string, uint32_t> services_;
    uint32_t nextHandle_ = 1;
};

}  // namespace avm::binder
