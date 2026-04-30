#pragma once

#include "binder/parcel.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <queue>
#include <unordered_map>

namespace avm::binder {

/** Single binder transaction. Symmetric for request and reply. */
struct Transaction {
    uint64_t id = 0;
    uint32_t targetHandle = 0;       // 0 == servicemanager
    uint32_t code = 0;               // BC_TRANSACTION code (e.g. CHECK_SERVICE_TRANSACTION)
    bool oneway = false;
    Parcel data;                     // request payload
    Parcel reply;                    // reply payload (filled by service)
    int32_t status = 0;              // 0 success, -ENOENT not found, etc.
    bool replyReady = false;
};

/**
 * Per-instance transaction routing. Multiple guest threads enqueue transactions; service
 * handlers dequeue and produce replies. The Phase C MVP runs entirely in-process —
 * cross-process binder traffic is Phase D.
 */
class TransactionRouter {
public:
    TransactionRouter();

    /** Push a transaction onto the queue and (if not oneway) wait for the reply. */
    int  submitAndWait(std::shared_ptr<Transaction> tx, int64_t timeoutNanos = -1);
    /** Pop the next pending transaction (blocks until one is available or stop=true). */
    std::shared_ptr<Transaction> pollNext();
    /** Mark a transaction as replied. The submitter resumes from `submitAndWait`. */
    void publishReply(uint64_t id, Parcel reply, int32_t status);
    /** Stop the router so workers exit. */
    void stop();
    bool stopped() const { return stop_.load(); }

    /** Test/diagnostic helpers. */
    size_t pendingCount() const;
    size_t completedCount() const;

private:
    mutable std::mutex lock_;
    std::condition_variable cv_;
    std::queue<std::shared_ptr<Transaction>> pending_;
    std::unordered_map<uint64_t, std::shared_ptr<Transaction>> awaiting_;
    std::atomic<uint64_t> nextId_{1};
    std::atomic<bool> stop_{false};
    std::atomic<size_t> completed_{0};
};

}  // namespace avm::binder
