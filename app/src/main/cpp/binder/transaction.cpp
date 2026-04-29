#include "binder/transaction.h"

#include <cerrno>
#include <chrono>

namespace avm::binder {

TransactionRouter::TransactionRouter() = default;

int TransactionRouter::submitAndWait(std::shared_ptr<Transaction> tx, int64_t timeoutNanos) {
    if (!tx) return -EINVAL;
    {
        std::lock_guard<std::mutex> g(lock_);
        if (stop_.load()) return -ESHUTDOWN;
        if (tx->id == 0) tx->id = nextId_.fetch_add(1);
        pending_.push(tx);
        if (!tx->oneway) {
            awaiting_.emplace(tx->id, tx);
        }
        cv_.notify_all();
    }
    if (tx->oneway) return 0;

    std::unique_lock<std::mutex> guard(lock_);
    if (timeoutNanos < 0) {
        cv_.wait(guard, [&]() { return tx->replyReady || stop_.load(); });
    } else {
        cv_.wait_for(guard, std::chrono::nanoseconds(timeoutNanos),
                     [&]() { return tx->replyReady || stop_.load(); });
    }
    if (!tx->replyReady) return -ETIMEDOUT;
    return tx->status;
}

std::shared_ptr<Transaction> TransactionRouter::pollNext() {
    std::unique_lock<std::mutex> guard(lock_);
    cv_.wait(guard, [&]() { return !pending_.empty() || stop_.load(); });
    if (pending_.empty()) return nullptr;
    auto tx = pending_.front();
    pending_.pop();
    return tx;
}

void TransactionRouter::publishReply(uint64_t id, Parcel reply, int32_t status) {
    std::lock_guard<std::mutex> g(lock_);
    auto it = awaiting_.find(id);
    if (it == awaiting_.end()) return;
    auto tx = it->second;
    tx->reply = std::move(reply);
    tx->status = status;
    tx->replyReady = true;
    awaiting_.erase(it);
    completed_.fetch_add(1);
    cv_.notify_all();
}

void TransactionRouter::stop() {
    {
        std::lock_guard<std::mutex> g(lock_);
        stop_.store(true);
    }
    cv_.notify_all();
}

size_t TransactionRouter::pendingCount() const {
    std::lock_guard<std::mutex> g(lock_);
    return pending_.size();
}

size_t TransactionRouter::completedCount() const {
    return completed_.load();
}

}  // namespace avm::binder
