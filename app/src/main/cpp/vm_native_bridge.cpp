#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <sys/stat.h>

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

struct OpenFile {
    std::string guestPath;
    std::string hostPath;
    std::string content;
    bool writable = false;
    bool virtualNode = false;
    bool deviceNode = false;
    std::size_t cursor = 0;
};

struct GuestInputEvent {
    std::string type;
    int action = 0;
    int pointerId = 0;
    int keyCode = 0;
    int metaState = 0;
    float hostX = 0.0f;
    float hostY = 0.0f;
    float guestX = 0.0f;
    float guestY = 0.0f;
};

struct Instance {
    std::mutex lock;
    std::string configJson;
    std::string lastError;
    std::string rootfsPath;
    std::string dataDir;
    std::string cacheDir;
    std::string logsDir;
    std::string logPath;
    std::map<int, OpenFile> fdTable;
    std::map<std::string, std::string> properties;
    std::map<std::string, int> binderServices;
    std::string bootstrapStatus;
    std::string framebufferSource = "empty";
    std::vector<uint32_t> framebuffer;
    std::vector<GuestInputEvent> inputQueue;
    int nextFd = 1000;
    int nextBinderHandle = 1;
    int framebufferWidth = 720;
    int framebufferHeight = 1280;
    int framebufferRotation = 0;
    int64_t framebufferFrames = 0;
    int64_t surfaceCopies = 0;
    int64_t inputQueueResets = 0;
    int audioSampleRate = 48000;
    int audioFramesGenerated = 0;
    int audioChannels = 2;
    bool framebufferDirty = false;
    bool audioMuted = false;
    ANativeWindow* window = nullptr;
    std::thread renderThread;
    std::thread guestThread;
    std::atomic<bool> guestRunning = false;
    std::atomic<bool> guestProcessRunning = false;
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

int extractJsonInt(const std::string& json, const std::string& key, int fallback) {
    const auto keyPattern = "\"" + key + "\"";
    const auto keyPosition = json.find(keyPattern);
    if (keyPosition == std::string::npos) {
        return fallback;
    }
    const auto colonPosition = json.find(':', keyPosition + keyPattern.size());
    if (colonPosition == std::string::npos) {
        return fallback;
    }
    auto cursor = colonPosition + 1;
    while (cursor < json.size() && std::isspace(static_cast<unsigned char>(json[cursor]))) {
        ++cursor;
    }
    std::size_t end = cursor;
    while (end < json.size() && (std::isdigit(static_cast<unsigned char>(json[end])) || json[end] == '-')) {
        ++end;
    }
    if (end == cursor) {
        return fallback;
    }
    return std::stoi(json.substr(cursor, end - cursor));
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

std::string parentPath(const std::string& path) {
    const auto slash = path.find_last_of('/');
    if (slash == std::string::npos || slash == 0) {
        return slash == 0 ? "/" : std::string();
    }
    return path.substr(0, slash);
}

bool mkdirRecursive(const std::string& path) {
    if (path.empty()) {
        return false;
    }
    std::string current;
    std::size_t cursor = 0;
    if (path.front() == '/') {
        current = "/";
        cursor = 1;
    }
    while (cursor <= path.size()) {
        const auto slash = path.find('/', cursor);
        const auto end = slash == std::string::npos ? path.size() : slash;
        const auto segment = path.substr(cursor, end - cursor);
        if (!segment.empty()) {
            if (!current.empty() && current.back() != '/') {
                current += "/";
            }
            current += segment;
            if (mkdir(current.c_str(), 0700) != 0 && errno != EEXIST) {
                return false;
            }
        }
        if (slash == std::string::npos) {
            break;
        }
        cursor = slash + 1;
    }
    return true;
}

std::string readWholeFile(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        return {};
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

bool writeWholeFile(const std::string& path, const std::string& content) {
    const auto parent = parentPath(path);
    if (!parent.empty() && !mkdirRecursive(parent)) {
        return false;
    }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) {
        return false;
    }
    output << content;
    return output.good();
}

bool fileExists(const std::string& path) {
    struct stat info {};
    return stat(path.c_str(), &info) == 0;
}

void appendInstanceLog(const std::shared_ptr<Instance>& instance, const std::string& message) {
    std::string logPath;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        logPath = instance->logPath;
    }
    if (logPath.empty()) {
        return;
    }
    const auto parent = parentPath(logPath);
    if (!parent.empty()) {
        mkdirRecursive(parent);
    }
    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
    std::ofstream output(logPath, std::ios::app);
    if (output) {
        output << now << " " << message << "\n";
    }
}

struct PathResolution {
    std::string status;
    std::string guestPath;
    std::string hostPath;
    bool writable = false;
    bool virtualNode = false;
    bool deviceNode = false;
    bool ok() const {
        return status == "OK";
    }
};

bool isVirtualDevicePath(const std::string& path) {
    return path == "/dev/binder" ||
        path == "/dev/hwbinder" ||
        path == "/dev/vndbinder" ||
        path == "/dev/ashmem" ||
        path == "/dev/input/event0" ||
        path == "/dev/graphics/fb0" ||
        path == "/dev/fb0" ||
        path == "/dev/snd/pcmC0D0p";
}

PathResolution resolveGuestPathForInstance(
    const std::shared_ptr<Instance>& instance,
    const std::string& requestedPath,
    bool writeAccess
) {
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
        return {"CONFIG_MISSING", requestedPath, {}, false, false, false};
    }

    const auto normalized = normalizeGuestPath(requestedPath);
    if (!normalized.ok) {
        const auto status = normalized.traversal ? "PATH_TRAVERSAL" : "INVALID_PATH";
        return {status, requestedPath, {}, false, false, false};
    }

    const auto path = normalized.path;
    PathResolution resolution = {"OK", path, {}, false, false, false};
    if (isPathUnderMount(path, "/system")) {
        resolution.hostPath = joinHostPath(rootfsPath + "/system", relativeToMount(path, "/system"));
    } else if (isPathUnderMount(path, "/vendor")) {
        resolution.hostPath = joinHostPath(rootfsPath + "/vendor", relativeToMount(path, "/vendor"));
    } else if (isPathUnderMount(path, "/data")) {
        resolution.hostPath = joinHostPath(dataDir, relativeToMount(path, "/data"));
        resolution.writable = true;
    } else if (isPathUnderMount(path, "/cache")) {
        resolution.hostPath = joinHostPath(cacheDir, relativeToMount(path, "/cache"));
        resolution.writable = true;
    } else if (
        isPathUnderMount(path, "/dev") ||
        isPathUnderMount(path, "/proc") ||
        isPathUnderMount(path, "/sys") ||
        isPathUnderMount(path, "/property")
    ) {
        resolution.hostPath = "virtual:" + path;
        resolution.virtualNode = true;
        resolution.deviceNode = isVirtualDevicePath(path);
        resolution.writable = resolution.deviceNode;
    } else {
        return {"UNKNOWN_MOUNT", path, {}, false, false, false};
    }

    if (writeAccess && !resolution.writable) {
        resolution.status = "READ_ONLY";
    }
    return resolution;
}

std::string jsonPathResolution(const PathResolution& resolution) {
    return jsonPathResolution(
        resolution.status,
        resolution.guestPath,
        resolution.hostPath,
        resolution.writable,
        resolution.virtualNode
    );
}

template <typename T>
T clampValue(T value, T minValue, T maxValue) {
    return std::min(maxValue, std::max(minValue, value));
}

struct SurfaceMapping {
    int left = 0;
    int top = 0;
    int width = 0;
    int height = 0;
};

SurfaceMapping computeSurfaceMapping(int surfaceWidth, int surfaceHeight, int guestWidth, int guestHeight) {
    if (surfaceWidth <= 0 || surfaceHeight <= 0 || guestWidth <= 0 || guestHeight <= 0) {
        return {};
    }
    const double scale = std::min(
        static_cast<double>(surfaceWidth) / static_cast<double>(guestWidth),
        static_cast<double>(surfaceHeight) / static_cast<double>(guestHeight)
    );
    const int mappedWidth = std::max(1, static_cast<int>(std::round(guestWidth * scale)));
    const int mappedHeight = std::max(1, static_cast<int>(std::round(guestHeight * scale)));
    return {
        (surfaceWidth - mappedWidth) / 2,
        (surfaceHeight - mappedHeight) / 2,
        mappedWidth,
        mappedHeight,
    };
}

void ensureFramebufferLocked(Instance& instance) {
    if (instance.framebufferWidth <= 0) {
        instance.framebufferWidth = 720;
    }
    if (instance.framebufferHeight <= 0) {
        instance.framebufferHeight = 1280;
    }
    const auto requiredSize = static_cast<std::size_t>(instance.framebufferWidth * instance.framebufferHeight);
    if (instance.framebuffer.size() != requiredSize) {
        instance.framebuffer.assign(requiredSize, 0xFF101010u);
        instance.framebufferDirty = true;
    }
}

uint32_t framebufferColorFor(int x, int y, int width, int height, uint32_t frame) {
    const uint8_t r = static_cast<uint8_t>((x * 255) / (width <= 0 ? 1 : width));
    const uint8_t g = static_cast<uint8_t>((y * 255) / (height <= 0 ? 1 : height));
    const uint8_t b = static_cast<uint8_t>((frame * 5 + x + y) & 0xFF);
    return (0xFFu << 24u) | (static_cast<uint32_t>(b) << 16u) |
           (static_cast<uint32_t>(g) << 8u) | static_cast<uint32_t>(r);
}

void writeFramebufferPatternLocked(Instance& instance, uint32_t frame, const std::string& source) {
    ensureFramebufferLocked(instance);
    for (int y = 0; y < instance.framebufferHeight; ++y) {
        for (int x = 0; x < instance.framebufferWidth; ++x) {
            instance.framebuffer[static_cast<std::size_t>(y * instance.framebufferWidth + x)] =
                framebufferColorFor(x, y, instance.framebufferWidth, instance.framebufferHeight, frame);
        }
    }
    instance.framebufferSource = source;
    instance.framebufferFrames++;
    instance.framebufferDirty = true;
}

GuestInputEvent mapTouchEventLocked(
    Instance& instance,
    int action,
    int pointerId,
    float hostX,
    float hostY
) {
    ensureFramebufferLocked(instance);
    const auto mapping = computeSurfaceMapping(
        instance.width,
        instance.height,
        instance.framebufferWidth,
        instance.framebufferHeight
    );
    const float normalizedX = mapping.width <= 0
        ? 0.0f
        : clampValue((hostX - static_cast<float>(mapping.left)) / static_cast<float>(mapping.width), 0.0f, 1.0f);
    const float normalizedY = mapping.height <= 0
        ? 0.0f
        : clampValue((hostY - static_cast<float>(mapping.top)) / static_cast<float>(mapping.height), 0.0f, 1.0f);
    return {
        "touch",
        action,
        pointerId,
        0,
        0,
        hostX,
        hostY,
        normalizedX * static_cast<float>(instance.framebufferWidth),
        normalizedY * static_cast<float>(instance.framebufferHeight),
    };
}

int parseFirstPositiveInt(const std::string& payload, int fallback) {
    std::size_t cursor = 0;
    while (cursor < payload.size() && !std::isdigit(static_cast<unsigned char>(payload[cursor]))) {
        ++cursor;
    }
    if (cursor == payload.size()) {
        return fallback;
    }
    std::size_t end = cursor;
    while (end < payload.size() && std::isdigit(static_cast<unsigned char>(payload[end]))) {
        ++end;
    }
    const int parsed = std::stoi(payload.substr(cursor, end - cursor));
    return parsed > 0 ? parsed : fallback;
}

int parseNamedPositiveInt(const std::string& payload, const std::string& name, int fallback) {
    const auto keyPosition = payload.find(name);
    if (keyPosition == std::string::npos) {
        return fallback;
    }
    return parseFirstPositiveInt(payload.substr(keyPosition + name.size()), fallback);
}

void handleVirtualDeviceWriteLocked(Instance& instance, const OpenFile& openFile, const std::string& payload) {
    if (openFile.guestPath == "/dev/graphics/fb0" || openFile.guestPath == "/dev/fb0") {
        const int frame = parseNamedPositiveInt(payload, "frame", static_cast<int>(instance.frame));
        writeFramebufferPatternLocked(instance, static_cast<uint32_t>(frame), "guest_fb0");
        return;
    }
    if (openFile.guestPath == "/dev/snd/pcmC0D0p") {
        const int sampleRate = parseNamedPositiveInt(payload, "rate", instance.audioSampleRate);
        const int frames = parseNamedPositiveInt(payload, "frames", std::max(1, static_cast<int>(payload.size() / 4)));
        instance.audioSampleRate = sampleRate;
        instance.audioFramesGenerated += frames;
        instance.audioMuted = payload.find("muted=true") != std::string::npos;
    }
}

std::string virtualNodeContent(const std::string& guestPath) {
    if (guestPath == "/proc/cpuinfo") {
        return "Processor\t: AArch64 Processor rev 0 (aarch64)\nHardware\t: AndroidVirtualMachine\n";
    }
    if (guestPath == "/proc/meminfo") {
        return "MemTotal:        1048576 kB\nMemFree:          524288 kB\n";
    }
    if (guestPath == "/proc/self/status") {
        return "Name:\tavm-guest\nState:\tR (running)\n";
    }
    if (guestPath == "/sys/devices/system/cpu/online") {
        return "0-3\n";
    }
    if (guestPath == "/property/context") {
        return "virtual property namespace\n";
    }
    if (guestPath == "/dev/graphics/fb0" || guestPath == "/dev/fb0") {
        return "android-vm-framebuffer format=RGBA_8888 protocol=pattern-command\n";
    }
    if (guestPath == "/dev/snd/pcmC0D0p") {
        return "android-vm-audio-output format=PCM16 channels=2 protocol=tone-command\n";
    }
    return {};
}

std::string trim(const std::string& value) {
    std::size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        ++start;
    }
    std::size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(start, end - start);
}

std::map<std::string, std::string> loadGuestProperties(const std::string& rootfsPath) {
    std::map<std::string, std::string> properties = {
        {"ro.product.brand", "CleanRoom"},
        {"ro.product.manufacturer", "CleanRoom"},
        {"ro.product.model", "Android Virtual Machine"},
        {"ro.product.device", "android_virtual_machine"},
        {"ro.build.version.release", "7.1.2"},
        {"ro.build.version.sdk", "25"},
        {"ro.zygote", "zygote64_32"},
        {"ro.hardware", "avm"},
        {"ro.kernel.qemu", "1"},
        {"persist.sys.language", "en"},
        {"persist.sys.country", "US"},
    };

    std::istringstream input(readWholeFile(rootfsPath + "/system/build.prop"));
    std::string line;
    while (std::getline(input, line)) {
        const auto trimmed = trim(line);
        if (trimmed.empty() || trimmed.front() == '#') {
            continue;
        }
        const auto separator = trimmed.find('=');
        if (separator == std::string::npos) {
            continue;
        }
        const auto key = trim(trimmed.substr(0, separator));
        const auto value = trim(trimmed.substr(separator + 1));
        if (!key.empty()) {
            properties[key] = value;
        }
    }
    return properties;
}

int registerBinderService(const std::shared_ptr<Instance>& instance, const std::string& serviceName) {
    std::lock_guard<std::mutex> guard(instance->lock);
    const auto found = instance->binderServices.find(serviceName);
    if (found != instance->binderServices.end()) {
        return found->second;
    }
    const int handle = instance->nextBinderHandle++;
    instance->binderServices[serviceName] = handle;
    return handle;
}

bool runSyscallSmoke(const std::shared_ptr<Instance>& instance) {
    const auto readResolution = resolveGuestPathForInstance(instance, "/system/build.prop", false);
    if (!readResolution.ok() || readWholeFile(readResolution.hostPath).find("ro.product.model") == std::string::npos) {
        return false;
    }

    const auto writeResolution = resolveGuestPathForInstance(instance, "/data/local/tmp/syscall-smoke.txt", true);
    if (!writeResolution.ok()) {
        return false;
    }
    return writeWholeFile(writeResolution.hostPath, "syscall-smoke-ok") &&
        readWholeFile(writeResolution.hostPath) == "syscall-smoke-ok";
}

void dummyGuestEntrypoint(const std::shared_ptr<Instance>& instance, const std::string& instanceId) {
    instance->guestProcessRunning.store(true);
    appendInstanceLog(instance, "guest_process entrypoint reached id=" + instanceId);

    std::string model;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        const auto found = instance->properties.find("ro.product.model");
        model = found == instance->properties.end() ? "" : found->second;
    }
    appendInstanceLog(instance, "guest stdout property ro.product.model=" + model);

    const bool syscallOk = runSyscallSmoke(instance);
    appendInstanceLog(instance, std::string("syscall smoke ") + (syscallOk ? "ok" : "failed"));

    registerBinderService(instance, "servicemanager");
    registerBinderService(instance, "package");
    registerBinderService(instance, "activity");
    registerBinderService(instance, "window");
    registerBinderService(instance, "surfaceflinger");
    registerBinderService(instance, "input");
    registerBinderService(instance, "power");
    appendInstanceLog(instance, "binder smoke registered core services");

    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->bootstrapStatus =
            "virtual_init=ok;property_service=ok;servicemanager=ok;"
            "zygote=attempted;system_server=blocked:elf_loader_missing";
    }
    appendInstanceLog(instance, "virtual init -> property service -> servicemanager -> zygote");
    appendInstanceLog(instance, "zygote process start attempted");
    appendInstanceLog(instance, "system_server blocked: ELF loader is not implemented yet");
    instance->guestProcessRunning.store(false);
}

void startGuestProcessThread(const std::shared_ptr<Instance>& instance, const std::string& instanceId) {
    if (instance->guestThread.joinable()) {
        instance->guestThread.join();
    }
    instance->guestThread = std::thread(dummyGuestEntrypoint, instance, instanceId);
}

void stopGuestProcessThread(const std::shared_ptr<Instance>& instance) {
    instance->guestProcessRunning.store(false);
    if (instance->guestThread.joinable()) {
        instance->guestThread.join();
    }
}

void drawFrame(Instance& instance, ANativeWindow_Buffer& buffer) {
    const auto width = buffer.width;
    const auto height = buffer.height;
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    std::vector<uint32_t> framebuffer;
    int framebufferWidth = 0;
    int framebufferHeight = 0;
    int64_t nextCopies = 0;
    {
        std::lock_guard<std::mutex> guard(instance.lock);
        ensureFramebufferLocked(instance);
        framebuffer = instance.framebuffer;
        framebufferWidth = instance.framebufferWidth;
        framebufferHeight = instance.framebufferHeight;
        nextCopies = ++instance.surfaceCopies;
        instance.framebufferDirty = false;
    }

    const auto mapping = computeSurfaceMapping(width, height, framebufferWidth, framebufferHeight);
    for (int y = 0; y < height; ++y) {
        uint32_t* row = pixels + (y * buffer.stride);
        for (int x = 0; x < width; ++x) {
            if (x < mapping.left ||
                x >= mapping.left + mapping.width ||
                y < mapping.top ||
                y >= mapping.top + mapping.height) {
                row[x] = 0xFF050505u;
                continue;
            }
            const int srcX = clampValue(
                ((x - mapping.left) * framebufferWidth) / std::max(1, mapping.width),
                0,
                framebufferWidth - 1
            );
            const int srcY = clampValue(
                ((y - mapping.top) * framebufferHeight) / std::max(1, mapping.height),
                0,
                framebufferHeight - 1
            );
            row[x] = framebuffer[static_cast<std::size_t>(srcY * framebufferWidth + srcX)];
        }
    }
    if (nextCopies % 120 == 0) {
        AVM_LOGI(
            "software framebuffer copied frame=%lld size=%dx%d surface=%dx%d",
            static_cast<long long>(nextCopies),
            framebufferWidth,
            framebufferHeight,
            width,
            height
        );
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
    const auto logsDir = extractJsonString(config, "logsDir");
    const auto displayWidth = extractJsonInt(config, "width", 720);
    const auto displayHeight = extractJsonInt(config, "height", 1280);
    if (config.empty() || rootfsPath.empty() || dataDir.empty() || cacheDir.empty() || logsDir.empty()) {
        AVM_LOGW("invalid config for %s", id.c_str());
        auto instance = instanceFor(id);
        return setInstanceError(instance, kConfigParseFailed, "VM config is empty or missing guest paths");
    }
    mkdirRecursive(logsDir);
    auto instance = instanceFor(id);
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->configJson = config;
        instance->rootfsPath = rootfsPath;
        instance->dataDir = dataDir;
        instance->cacheDir = cacheDir;
        instance->logsDir = logsDir;
        instance->logPath = logsDir + "/native_runtime.log";
        instance->properties = loadGuestProperties(rootfsPath);
        instance->framebufferWidth = displayWidth > 0 ? displayWidth : 720;
        instance->framebufferHeight = displayHeight > 0 ? displayHeight : 1280;
        instance->framebuffer.clear();
        instance->framebufferFrames = 0;
        instance->surfaceCopies = 0;
        instance->frame = 0;
        writeFramebufferPatternLocked(*instance, instance->frame++, "initial_test_pattern");
        instance->binderServices.clear();
        instance->bootstrapStatus.clear();
        instance->nextBinderHandle = 1;
        instance->state = instance->guestRunning.load() ? kStateRunning : kStateCreated;
        instance->lastError.clear();
    }
    appendInstanceLog(instance, "instance initialized id=" + id);
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
    startGuestProcessThread(instance, id);
    if (hasWindow) {
        startRenderer(instance, id);
    }
    setInstanceState(instance, kStateRunning);
    appendInstanceLog(instance, "guest started");
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
    stopGuestProcessThread(instance);
    stopRenderer(instance);
    clearWindow(instance);
    setInstanceState(instance, kStateStopped);
    appendInstanceLog(instance, "guest stopped");
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
    stopGuestProcessThread(instance);
    stopRenderer(instance);
    clearWindow(instance);
    appendInstanceLog(instance, "instance destroyed");
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
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getInstanceLogPath(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    return env->NewStringUTF(instance->logPath.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getGuestProperty(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring key,
    jstring fallback
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto propertyKey = ScopedUtfChars(env, key).str();
    const auto fallbackValue = ScopedUtfChars(env, fallback).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF(fallbackValue.c_str());
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    const auto found = instance->properties.find(propertyKey);
    if (found == instance->properties.end()) {
        return env->NewStringUTF(fallbackValue.c_str());
    }
    return env->NewStringUTF(found->second.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_setGuestPropertyOverride(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring key,
    jstring value
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto propertyKey = ScopedUtfChars(env, key).str();
    const auto propertyValue = ScopedUtfChars(env, value).str();
    auto instance = findInstance(id);
    if (!instance || propertyKey.empty()) {
        return kInvalidInstance;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->properties[propertyKey] = propertyValue;
    }
    appendInstanceLog(instance, "property override " + propertyKey);
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getBinderServiceHandle(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring serviceName
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto service = ScopedUtfChars(env, serviceName).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -kInvalidInstance;
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    const auto found = instance->binderServices.find(service);
    return found == instance->binderServices.end() ? -1 : found->second;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getBootstrapStatus(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    return env->NewStringUTF(instance->bootstrapStatus.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_writeFramebufferTestPattern(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint frameIndex
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return kInvalidInstance;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        writeFramebufferPatternLocked(*instance, static_cast<uint32_t>(frameIndex), "diagnostic_test_pattern");
    }
    appendInstanceLog(instance, "framebuffer test pattern frame=" + std::to_string(frameIndex));
    return kOk;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getGraphicsStats(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF("{}");
    }
    std::ostringstream json;
    const bool renderRunning = instance->renderRunning.load();
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        const auto mapping = computeSurfaceMapping(
            instance->width,
            instance->height,
            instance->framebufferWidth,
            instance->framebufferHeight
        );
        json << "{"
             << "\"framebufferWidth\":" << instance->framebufferWidth << ","
             << "\"framebufferHeight\":" << instance->framebufferHeight << ","
             << "\"surfaceWidth\":" << instance->width << ","
             << "\"surfaceHeight\":" << instance->height << ","
             << "\"mappingLeft\":" << mapping.left << ","
             << "\"mappingTop\":" << mapping.top << ","
             << "\"mappingWidth\":" << mapping.width << ","
             << "\"mappingHeight\":" << mapping.height << ","
             << "\"framebufferFrames\":" << instance->framebufferFrames << ","
             << "\"surfaceCopies\":" << instance->surfaceCopies << ","
             << "\"dirty\":" << (instance->framebufferDirty ? "true" : "false") << ","
             << "\"framebufferSource\":\"" << escapeJson(instance->framebufferSource) << "\","
             << "\"surfaceAttached\":" << (instance->window != nullptr ? "true" : "false") << ","
             << "\"renderRunning\":" << (renderRunning ? "true" : "false")
             << "}";
    }
    const auto result = json.str();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getInputStats(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF("{}");
    }
    std::ostringstream json;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        GuestInputEvent lastEvent;
        if (!instance->inputQueue.empty()) {
            lastEvent = instance->inputQueue.back();
        }
        json << "{"
             << "\"queueSize\":" << instance->inputQueue.size() << ","
             << "\"resets\":" << instance->inputQueueResets << ","
             << "\"lastType\":\"" << escapeJson(lastEvent.type) << "\","
             << "\"lastAction\":" << lastEvent.action << ","
             << "\"lastPointerId\":" << lastEvent.pointerId << ","
             << "\"lastKeyCode\":" << lastEvent.keyCode << ","
             << "\"lastMetaState\":" << lastEvent.metaState << ","
             << "\"lastGuestX\":" << lastEvent.guestX << ","
             << "\"lastGuestY\":" << lastEvent.guestY
             << "}";
    }
    const auto result = json.str();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_resetInputQueue(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return kInvalidInstance;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->inputQueue.clear();
        instance->inputQueueResets++;
    }
    appendInstanceLog(instance, "input queue reset");
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_generateAudioTestTone(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint sampleRate,
    jint frames,
    jboolean muted
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance || sampleRate <= 0 || frames <= 0) {
        return kInvalidInstance;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->audioSampleRate = sampleRate;
        instance->audioFramesGenerated += frames;
        instance->audioMuted = muted == JNI_TRUE;
    }
    appendInstanceLog(instance, "audio test tone frames=" + std::to_string(frames));
    return frames;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getAudioStats(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return env->NewStringUTF("{}");
    }
    std::ostringstream json;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        json << "{"
             << "\"sampleRate\":" << instance->audioSampleRate << ","
             << "\"framesGenerated\":" << instance->audioFramesGenerated << ","
             << "\"channels\":" << instance->audioChannels << ","
             << "\"muted\":" << (instance->audioMuted ? "true" : "false")
             << "}";
    }
    const auto result = json.str();
    return env->NewStringUTF(result.c_str());
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

    const auto result = jsonPathResolution(
        resolveGuestPathForInstance(instance, requestedPath, writeAccess == JNI_TRUE)
    );
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_openGuestPath(
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
        return -kInvalidInstance;
    }

    const auto resolution = resolveGuestPathForInstance(instance, requestedPath, writeAccess == JNI_TRUE);
    if (!resolution.ok()) {
        appendInstanceLog(instance, "open denied path=" + requestedPath + " status=" + resolution.status);
        return -1;
    }

    OpenFile openFile;
    openFile.guestPath = resolution.guestPath;
    openFile.hostPath = resolution.hostPath;
    openFile.writable = resolution.writable && writeAccess == JNI_TRUE;
    openFile.virtualNode = resolution.virtualNode;
    openFile.deviceNode = resolution.deviceNode;

    if (openFile.virtualNode) {
        openFile.content = virtualNodeContent(openFile.guestPath);
    } else if (writeAccess == JNI_TRUE) {
        const auto parent = parentPath(openFile.hostPath);
        if (!parent.empty() && !mkdirRecursive(parent)) {
            appendInstanceLog(instance, "open failed mkdir path=" + openFile.hostPath);
            return -1;
        }
    } else if (fileExists(openFile.hostPath)) {
        openFile.content = readWholeFile(openFile.hostPath);
    } else {
        appendInstanceLog(instance, "open missing path=" + openFile.hostPath);
        return -1;
    }

    int fd = -1;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        fd = instance->nextFd++;
        instance->fdTable[fd] = openFile;
    }
    appendInstanceLog(instance, "open fd=" + std::to_string(fd) + " path=" + openFile.guestPath);
    return fd;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_readGuestFile(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint fd,
    jint maxBytes
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance || maxBytes <= 0) {
        return env->NewStringUTF("");
    }

    std::string chunk;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        auto found = instance->fdTable.find(fd);
        if (found == instance->fdTable.end()) {
            return env->NewStringUTF("");
        }
        auto& openFile = found->second;
        const auto remaining = openFile.content.size() - std::min(openFile.cursor, openFile.content.size());
        const auto bytes = std::min<std::size_t>(static_cast<std::size_t>(maxBytes), remaining);
        chunk = openFile.content.substr(openFile.cursor, bytes);
        openFile.cursor += bytes;
    }
    return env->NewStringUTF(chunk.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_writeGuestFile(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint fd,
    jstring data
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto payload = ScopedUtfChars(env, data).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -kInvalidInstance;
    }

    std::string hostPath;
    std::string nextContent;
    bool shouldWriteHostFile = false;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        auto found = instance->fdTable.find(fd);
        if (found == instance->fdTable.end() || !found->second.writable) {
            return -1;
        }
        auto& openFile = found->second;
        openFile.content += payload;
        openFile.cursor = openFile.content.size();
        if (openFile.virtualNode && openFile.deviceNode) {
            handleVirtualDeviceWriteLocked(*instance, openFile, payload);
        }
        if (!openFile.virtualNode) {
            hostPath = openFile.hostPath;
            nextContent = openFile.content;
            shouldWriteHostFile = true;
        }
    }

    if (shouldWriteHostFile && !writeWholeFile(hostPath, nextContent)) {
        appendInstanceLog(instance, "write failed path=" + hostPath);
        return -1;
    }
    appendInstanceLog(instance, "write fd=" + std::to_string(fd) + " bytes=" + std::to_string(payload.size()));
    return static_cast<jint>(payload.size());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_closeGuestFile(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint fd
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -kInvalidInstance;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        if (instance->fdTable.erase(fd) == 0) {
            return -1;
        }
    }
    appendInstanceLog(instance, "close fd=" + std::to_string(fd));
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getOpenFdCount(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) {
        return -kInvalidInstance;
    }
    std::lock_guard<std::mutex> guard(instance->lock);
    return static_cast<jint>(instance->fdTable.size());
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
        ensureFramebufferLocked(*instance);
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
    ensureFramebufferLocked(*instance);
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
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->inputQueue.clear();
        instance->inputQueueResets++;
    }
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
    GuestInputEvent mapped;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        mapped = mapTouchEventLocked(*instance, action, pointerId, x, y);
        instance->inputQueue.push_back(mapped);
        if (instance->inputQueue.size() > 256) {
            instance->inputQueue.erase(instance->inputQueue.begin());
        }
    }
    const auto count = ++instance->inputEvents;
    if (count % 120 == 0) {
        AVM_LOGI(
            "touch id=%s action=%d pointer=%d host=%.1f,%.1f guest=%.1f,%.1f",
            id.c_str(),
            action,
            pointerId,
            x,
            y,
            mapped.guestX,
            mapped.guestY
        );
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
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->inputQueue.push_back({
            "key",
            action,
            0,
            keyCode,
            metaState,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
        });
        if (instance->inputQueue.size() > 256) {
            instance->inputQueue.erase(instance->inputQueue.begin());
        }
    }
    const auto count = ++instance->inputEvents;
    if (count % 40 == 0) {
        AVM_LOGI("key id=%s action=%d key=%d meta=%d", id.c_str(), action, keyCode, metaState);
    }
    return kOk;
}
