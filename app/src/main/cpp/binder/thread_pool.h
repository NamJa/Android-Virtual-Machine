#pragma once

#include "binder/transaction.h"

#include <atomic>
#include <functional>
#include <memory>
#include <thread>
#include <vector>

namespace avm::binder {

/**
 * Phase C binder thread pool — 4 worker threads pull from the [TransactionRouter] and
 * dispatch to a service handler callback. `start()` is idempotent.
 */
class BinderThreadPool {
public:
    using Handler = std::function<void(std::shared_ptr<Transaction>)>;

    BinderThreadPool(TransactionRouter& router, Handler handler, int threads = kDefaultThreads);
    ~BinderThreadPool();

    void start();
    void stop();
    bool running() const { return running_.load(); }
    int  threadCount() const { return static_cast<int>(workers_.size()); }

    static constexpr int kDefaultThreads = 4;

private:
    void workerLoop();

    TransactionRouter& router_;
    Handler handler_;
    int threadCount_ = 0;
    std::atomic<bool> running_{false};
    std::vector<std::thread> workers_;
};

}  // namespace avm::binder
