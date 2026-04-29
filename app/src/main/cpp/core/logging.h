#pragma once

#include <android/log.h>
#include <cstdint>

#define AVM_LOG_TAG "AVM.Native"
#define AVM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  AVM_LOG_TAG, __VA_ARGS__)
#define AVM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  AVM_LOG_TAG, __VA_ARGS__)
#define AVM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AVM_LOG_TAG, __VA_ARGS__)

namespace avm::core {

/**
 * Wall-clock milliseconds since the Unix epoch. Used by the log-line writer in
 * `jni/vm_native_bridge.cpp` and by the Phase B loader / syscall modules so per-event
 * timestamps are consistent across modules.
 */
int64_t nowMillis();

}  // namespace avm::core
