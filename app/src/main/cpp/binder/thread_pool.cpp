#include "binder/thread_pool.h"

namespace avm::binder {

BinderThreadPool::BinderThreadPool(TransactionRouter& router, Handler handler, int threads)
    : router_(router), handler_(std::move(handler)), threadCount_(threads) {}

BinderThreadPool::~BinderThreadPool() { stop(); }

void BinderThreadPool::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) return;
    workers_.reserve(static_cast<size_t>(threadCount_));
    for (int i = 0; i < threadCount_; ++i) {
        workers_.emplace_back([this]() { workerLoop(); });
    }
}

void BinderThreadPool::stop() {
    if (!running_.exchange(false)) return;
    router_.stop();
    for (auto& t : workers_) {
        if (t.joinable()) t.join();
    }
    workers_.clear();
}

void BinderThreadPool::workerLoop() {
    while (running_.load() && !router_.stopped()) {
        auto tx = router_.pollNext();
        if (!tx) break;
        if (handler_) handler_(tx);
    }
}

}  // namespace avm::binder
