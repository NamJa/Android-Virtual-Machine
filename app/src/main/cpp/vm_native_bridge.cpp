#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#define AVM_LOG_TAG "AVM.Native"
#define AVM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, AVM_LOG_TAG, __VA_ARGS__)
#define AVM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, AVM_LOG_TAG, __VA_ARGS__)

namespace {

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    std::string str() const {
        return chars_ == nullptr ? std::string() : std::string(chars_);
    }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

struct Instance {
    std::mutex lock;
    std::string configJson;
    ANativeWindow* window = nullptr;
    std::thread renderThread;
    std::atomic<bool> guestRunning = false;
    std::atomic<bool> renderRunning = false;
    std::atomic<int64_t> inputEvents = 0;
    int width = 0;
    int height = 0;
    int densityDpi = 0;
    uint32_t frame = 0;
};

std::mutex gHostLock;
std::mutex gInstancesLock;
std::map<std::string, std::shared_ptr<Instance>> gInstances;
bool gHostInitialized = false;

std::shared_ptr<Instance> instanceFor(const std::string& instanceId) {
    std::lock_guard<std::mutex> guard(gInstancesLock);
    auto& slot = gInstances[instanceId];
    if (!slot) {
        slot = std::make_shared<Instance>();
    }
    return slot;
}

std::shared_ptr<Instance> findInstance(const std::string& instanceId) {
    std::lock_guard<std::mutex> guard(gInstancesLock);
    auto found = gInstances.find(instanceId);
    return found == gInstances.end() ? nullptr : found->second;
}

uint32_t colorFor(uint32_t frame, int x, int y, int width, int height) {
    const uint8_t r = static_cast<uint8_t>((x * 255) / (width <= 0 ? 1 : width));
    const uint8_t g = static_cast<uint8_t>((y * 255) / (height <= 0 ? 1 : height));
    const uint8_t b = static_cast<uint8_t>((frame * 3) & 0xFF);
    return (0xFFu << 24u) | (static_cast<uint32_t>(b) << 16u) |
           (static_cast<uint32_t>(g) << 8u) | static_cast<uint32_t>(r);
}

void drawFrame(Instance& instance, ANativeWindow_Buffer& buffer) {
    const auto width = buffer.width;
    const auto height = buffer.height;
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    const uint32_t frame = instance.frame++;
    for (int y = 0; y < height; ++y) {
        uint32_t* row = pixels + (y * buffer.stride);
        for (int x = 0; x < width; ++x) {
            row[x] = colorFor(frame, x, y, width, height);
        }
    }
}

void renderLoop(std::shared_ptr<Instance> instance, std::string instanceId) {
    AVM_LOGI("render loop started for %s", instanceId.c_str());
    while (instance->renderRunning.load()) {
        ANativeWindow* window = nullptr;
        {
            std::lock_guard<std::mutex> guard(instance->lock);
            window = instance->window;
            if (window != nullptr) {
                ANativeWindow_acquire(window);
            }
        }

        if (window == nullptr) {
            std::this_thread::sleep_for(std::chrono::milliseconds(32));
            continue;
        }

        ANativeWindow_Buffer buffer;
        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
            drawFrame(*instance, buffer);
            ANativeWindow_unlockAndPost(window);
        } else {
            AVM_LOGW("failed to lock surface for %s", instanceId.c_str());
        }
        ANativeWindow_release(window);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    AVM_LOGI("render loop stopped for %s", instanceId.c_str());
}

void startRenderer(const std::shared_ptr<Instance>& instance, const std::string& instanceId) {
    if (instance->renderRunning.exchange(true)) {
        return;
    }
    if (instance->renderThread.joinable()) {
        instance->renderThread.join();
    }
    instance->renderThread = std::thread(renderLoop, instance, instanceId);
}

void stopRenderer(const std::shared_ptr<Instance>& instance) {
    instance->renderRunning.store(false);
    if (instance->renderThread.joinable()) {
        instance->renderThread.join();
    }
}

void clearWindow(const std::shared_ptr<Instance>& instance) {
    ANativeWindow* oldWindow = nullptr;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        oldWindow = instance->window;
        instance->window = nullptr;
        instance->width = 0;
        instance->height = 0;
    }
    if (oldWindow != nullptr) {
        ANativeWindow_release(oldWindow);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_initHost(
    JNIEnv* env,
    jclass,
    jstring filesDir,
    jstring nativeLibraryDir,
    jint sdkInt
) {
    const auto files = ScopedUtfChars(env, filesDir).str();
    const auto libs = ScopedUtfChars(env, nativeLibraryDir).str();
    std::lock_guard<std::mutex> guard(gHostLock);
    gHostInitialized = true;
    AVM_LOGI("host init files=%s libs=%s sdk=%d", files.c_str(), libs.c_str(), sdkInt);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_initInstance(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring configJson
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto config = ScopedUtfChars(env, configJson).str();
    if (id.empty()) {
        return -1;
    }
    auto instance = instanceFor(id);
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->configJson = config;
    }
    AVM_LOGI("instance initialized id=%s configBytes=%zu", id.c_str(), config.size());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_startGuest(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    if (id.empty()) {
        return -1;
    }
    auto instance = instanceFor(id);
    instance->guestRunning.store(true);
    bool hasWindow = false;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        hasWindow = instance->window != nullptr;
    }
    if (hasWindow) {
        startRenderer(instance, id);
    }
    AVM_LOGI("guest marked running id=%s", id.c_str());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_stopGuest(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -1;
    }
    instance->guestRunning.store(false);
    stopRenderer(instance);
    clearWindow(instance);
    AVM_LOGI("guest stopped id=%s", id.c_str());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_attachSurface(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jobject surface,
    jint width,
    jint height,
    jint densityDpi
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = instanceFor(id);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return -2;
    }
    ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow* oldWindow = nullptr;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        oldWindow = instance->window;
        instance->window = window;
        instance->width = width;
        instance->height = height;
        instance->densityDpi = densityDpi;
    }
    if (oldWindow != nullptr) {
        ANativeWindow_release(oldWindow);
    }
    if (instance->guestRunning.load()) {
        startRenderer(instance, id);
    }
    AVM_LOGI("surface attached id=%s size=%dx%d dpi=%d", id.c_str(), width, height, densityDpi);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_resizeSurface(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint width,
    jint height,
    jint densityDpi
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -1;
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    instance->width = width;
    instance->height = height;
    instance->densityDpi = densityDpi;
    if (instance->window != nullptr) {
        ANativeWindow_setBuffersGeometry(instance->window, width, height, WINDOW_FORMAT_RGBA_8888);
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_detachSurface(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -1;
    }
    stopRenderer(instance);
    clearWindow(instance);
    AVM_LOGI("surface detached id=%s", id.c_str());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_sendTouch(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint action,
    jint pointerId,
    jfloat x,
    jfloat y
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -1;
    }
    const auto count = ++instance->inputEvents;
    if (count % 120 == 0) {
        AVM_LOGI("touch id=%s action=%d pointer=%d x=%.1f y=%.1f", id.c_str(), action, pointerId, x, y);
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_sendKey(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint action,
    jint keyCode,
    jint metaState
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -1;
    }
    const auto count = ++instance->inputEvents;
    if (count % 40 == 0) {
        AVM_LOGI("key id=%s action=%d key=%d meta=%d", id.c_str(), action, keyCode, metaState);
    }
    return 0;
}
