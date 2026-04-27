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
#include <sstream>
#include <string>
#include <thread>
#include <vector>

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
    std::string lastError;
    std::string rootfsPath;
    std::string dataDir;
    std::string cacheDir;
    ANativeWindow* window = nullptr;
    std::thread renderThread;
    std::atomic<bool> guestRunning = false;
    std::atomic<bool> renderRunning = false;
    std::atomic<int64_t> inputEvents = 0;
    int state = 1;
    int width = 0;
    int height = 0;
    int densityDpi = 0;
    uint32_t frame = 0;
};

constexpr jint kOk = 0;
constexpr jint kInvalidInstance = 1;
constexpr jint kConfigParseFailed = 2;
constexpr jint kProcessStartFailed = 7;
constexpr jint kSurfaceMissing = 8;
constexpr jint kInternalError = 100;
constexpr int kStateUnknown = -1;
constexpr int kStateCreated = 1;
constexpr int kStateStarting = 2;
constexpr int kStateRunning = 3;
constexpr int kStateStopped = 4;
constexpr int kStateError = 5;

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

bool hostReady() {
    std::lock_guard<std::mutex> guard(gHostLock);
    return gHostInitialized;
}

void setInstanceState(const std::shared_ptr<Instance>& instance, int state) {
    std::lock_guard<std::mutex> guard(instance->lock);
    instance->state = state;
    if (state != kStateError) {
        instance->lastError.clear();
    }
}

jint setInstanceError(
    const std::shared_ptr<Instance>& instance,
    jint code,
    const std::string& message
) {
    std::lock_guard<std::mutex> guard(instance->lock);
    instance->state = kStateError;
    instance->lastError = message;
    return code;
}

std::string extractJsonString(const std::string& json, const std::string& key) {
    const auto keyPattern = "\"" + key + "\"";
    const auto keyPosition = json.find(keyPattern);
    if (keyPosition == std::string::npos) {
        return {};
    }
    const auto colonPosition = json.find(':', keyPosition + keyPattern.size());
    if (colonPosition == std::string::npos) {
        return {};
    }
    const auto valueStart = json.find('"', colonPosition + 1);
    if (valueStart == std::string::npos) {
        return {};
    }
    std::ostringstream value;
    bool escaping = false;
    for (std::size_t index = valueStart + 1; index < json.size(); ++index) {
        const char ch = json[index];
        if (escaping) {
            switch (ch) {
                case '"':
                case '\\':
                case '/':
                    value << ch;
                    break;
                case 'n':
                    value << '\n';
                    break;
                default:
                    value << ch;
                    break;
            }
            escaping = false;
            continue;
        }
        if (ch == '\\') {
            escaping = true;
            continue;
        }
        if (ch == '"') {
            return value.str();
        }
        value << ch;
    }
    return {};
}

std::string escapeJson(const std::string& value) {
    std::ostringstream escaped;
    for (const char ch : value) {
        switch (ch) {
            case '\\':
                escaped << "\\\\";
                break;
            case '"':
                escaped << "\\\"";
                break;
            case '\n':
                escaped << "\\n";
                break;
            default:
                escaped << ch;
                break;
        }
    }
    return escaped.str();
}

std::string jsonPathResolution(
    const std::string& status,
    const std::string& guestPath,
    const std::string& hostPath,
    bool writable,
    bool virtualNode
) {
    std::ostringstream json;
    json << "{"
         << "\"status\":\"" << escapeJson(status) << "\","
         << "\"guestPath\":\"" << escapeJson(guestPath) << "\","
         << "\"hostPath\":\"" << escapeJson(hostPath) << "\","
         << "\"writable\":" << (writable ? "true" : "false") << ","
         << "\"virtualNode\":" << (virtualNode ? "true" : "false")
         << "}";
    return json.str();
}

struct NormalizedGuestPath {
    bool ok = false;
    bool traversal = false;
    std::string path;
};

NormalizedGuestPath normalizeGuestPath(const std::string& guestPath) {
    if (guestPath.empty() || guestPath.front() != '/') {
        return {};
    }

    std::vector<std::string> segments;
    std::size_t cursor = 1;
    while (cursor <= guestPath.size()) {
        const auto slash = guestPath.find('/', cursor);
        const auto end = slash == std::string::npos ? guestPath.size() : slash;
        const auto segment = guestPath.substr(cursor, end - cursor);
        if (segment == "..") {
            return {false, true, {}};
        }
        if (!segment.empty() && segment != ".") {
            segments.push_back(segment);
        }
        if (slash == std::string::npos) {
            break;
        }
        cursor = slash + 1;
    }

    std::ostringstream normalized;
    normalized << "/";
    for (std::size_t index = 0; index < segments.size(); ++index) {
        if (index > 0) {
            normalized << "/";
        }
        normalized << segments[index];
    }
    return {true, false, normalized.str()};
}

bool isPathUnderMount(const std::string& path, const std::string& mountPoint) {
    return path == mountPoint || path.rfind(mountPoint + "/", 0) == 0;
}

std::string relativeToMount(const std::string& path, const std::string& mountPoint) {
    if (path == mountPoint) {
        return {};
    }
    return path.substr(mountPoint.size() + 1);
}

std::string joinHostPath(const std::string& base, const std::string& relative) {
    if (relative.empty()) {
        return base;
    }
    return base + "/" + relative;
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
    return kOk;
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
        return kInvalidInstance;
    }
    const auto rootfsPath = extractJsonString(config, "rootfsPath");
    const auto dataDir = extractJsonString(config, "dataDir");
    const auto cacheDir = extractJsonString(config, "cacheDir");
    if (config.empty() || rootfsPath.empty() || dataDir.empty() || cacheDir.empty()) {
        AVM_LOGW("invalid config for %s", id.c_str());
        auto instance = instanceFor(id);
        return setInstanceError(instance, kConfigParseFailed, "VM config is empty or missing guest paths");
    }
    auto instance = instanceFor(id);
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->configJson = config;
        instance->rootfsPath = rootfsPath;
        instance->dataDir = dataDir;
        instance->cacheDir = cacheDir;
        instance->state = instance->guestRunning.load() ? kStateRunning : kStateCreated;
        instance->lastError.clear();
    }
    AVM_LOGI("instance initialized id=%s configBytes=%zu", id.c_str(), config.size());
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_startGuest(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    if (id.empty()) {
        return kInvalidInstance;
    }
    auto instance = findInstance(id);
    if (!instance) {
        return kInvalidInstance;
    }
    if (!hostReady()) {
        return setInstanceError(instance, kInternalError, "Host runtime is not initialized");
    }
    if (instance->guestRunning.load()) {
        return setInstanceError(instance, kProcessStartFailed, "Guest is already running");
    }
    setInstanceState(instance, kStateStarting);
    instance->guestRunning.store(true);
    bool hasWindow = false;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        hasWindow = instance->window != nullptr;
    }
    if (hasWindow) {
        startRenderer(instance, id);
    }
    setInstanceState(instance, kStateRunning);
    AVM_LOGI("guest marked running id=%s", id.c_str());
    return kOk;
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
        return kInvalidInstance;
    }
    instance->guestRunning.store(false);
    stopRenderer(instance);
    clearWindow(instance);
    setInstanceState(instance, kStateStopped);
    AVM_LOGI("guest stopped id=%s", id.c_str());
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_destroyInstance(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return kInvalidInstance;
    }
    instance->guestRunning.store(false);
    stopRenderer(instance);
    clearWindow(instance);
    {
        std::lock_guard<std::mutex> guard(gInstancesLock);
        gInstances.erase(id);
    }
    AVM_LOGI("instance destroyed id=%s", id.c_str());
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getInstanceState(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return kStateUnknown;
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    return instance->state;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getLastError(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF("Instance not found");
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    return env->NewStringUTF(instance->lastError.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_resolveGuestPath(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring guestPath,
    jboolean writeAccess
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto requestedPath = ScopedUtfChars(env, guestPath).str();
    auto instance = findInstance(id);
    if (!instance) {
        const auto result = jsonPathResolution("INVALID_INSTANCE", requestedPath, {}, false, false);
        return env->NewStringUTF(result.c_str());
    }

    std::string rootfsPath;
    std::string dataDir;
    std::string cacheDir;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        rootfsPath = instance->rootfsPath;
        dataDir = instance->dataDir;
        cacheDir = instance->cacheDir;
    }
    if (rootfsPath.empty() || dataDir.empty() || cacheDir.empty()) {
        const auto result = jsonPathResolution("CONFIG_MISSING", requestedPath, {}, false, false);
        return env->NewStringUTF(result.c_str());
    }

    const auto normalized = normalizeGuestPath(requestedPath);
    if (!normalized.ok) {
        const auto status = normalized.traversal ? "PATH_TRAVERSAL" : "INVALID_PATH";
        const auto result = jsonPathResolution(status, requestedPath, {}, false, false);
        return env->NewStringUTF(result.c_str());
    }

    const auto path = normalized.path;
    std::string hostPath;
    bool writable = false;
    bool virtualNode = false;
    if (isPathUnderMount(path, "/system")) {
        hostPath = joinHostPath(rootfsPath + "/system", relativeToMount(path, "/system"));
    } else if (isPathUnderMount(path, "/vendor")) {
        hostPath = joinHostPath(rootfsPath + "/vendor", relativeToMount(path, "/vendor"));
    } else if (isPathUnderMount(path, "/data")) {
        hostPath = joinHostPath(dataDir, relativeToMount(path, "/data"));
        writable = true;
    } else if (isPathUnderMount(path, "/cache")) {
        hostPath = joinHostPath(cacheDir, relativeToMount(path, "/cache"));
        writable = true;
    } else if (
        isPathUnderMount(path, "/dev") ||
        isPathUnderMount(path, "/proc") ||
        isPathUnderMount(path, "/sys") ||
        isPathUnderMount(path, "/property")
    ) {
        hostPath = "virtual:" + path;
        virtualNode = true;
    } else {
        const auto result = jsonPathResolution("UNKNOWN_MOUNT", path, {}, false, false);
        return env->NewStringUTF(result.c_str());
    }

    const bool requestedWrite = writeAccess == JNI_TRUE;
    if (requestedWrite && !writable) {
        const auto result = jsonPathResolution("READ_ONLY", path, hostPath, false, virtualNode);
        return env->NewStringUTF(result.c_str());
    }

    const auto result = jsonPathResolution("OK", path, hostPath, writable, virtualNode);
    return env->NewStringUTF(result.c_str());
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
    auto instance = findInstance(id);
    if (!instance) {
        return kInvalidInstance;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return setInstanceError(instance, kSurfaceMissing, "Surface is missing");
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
    return kOk;
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
        return kInvalidInstance;
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    instance->width = width;
    instance->height = height;
    instance->densityDpi = densityDpi;
    if (instance->window != nullptr) {
        ANativeWindow_setBuffersGeometry(instance->window, width, height, WINDOW_FORMAT_RGBA_8888);
    }
    return kOk;
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
        return kInvalidInstance;
    }
    stopRenderer(instance);
    clearWindow(instance);
    AVM_LOGI("surface detached id=%s", id.c_str());
    return kOk;
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
        return kInvalidInstance;
    }
    const auto count = ++instance->inputEvents;
    if (count % 120 == 0) {
        AVM_LOGI("touch id=%s action=%d pointer=%d x=%.1f y=%.1f", id.c_str(), action, pointerId, x, y);
    }
    return kOk;
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
        return kInvalidInstance;
    }
    const auto count = ++instance->inputEvents;
    if (count % 40 == 0) {
        AVM_LOGI("key id=%s action=%d key=%d meta=%d", id.c_str(), action, keyCode, metaState);
    }
    return kOk;
}
