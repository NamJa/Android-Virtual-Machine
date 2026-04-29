#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
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
#include <dirent.h>
#include <unistd.h>
#include <ctime>

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

struct AudioOutputResult {
    bool opened = false;
    int framesWritten = 0;
    std::string status;
};

struct DirtyRect {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;

    bool empty() const {
        return right <= left || bottom <= top;
    }

    int width() const {
        return empty() ? 0 : right - left;
    }

    int height() const {
        return empty() ? 0 : bottom - top;
    }
};

struct GraphicsBuffer {
    int id = 0;
    int width = 0;
    int height = 0;
    std::string format;
    int usage = 0;
    int commits = 0;
};

struct Instance {
    std::mutex lock;
    std::string configJson;
    std::string lastError;
    std::string rootfsPath;
    std::string dataDir;
    std::string cacheDir;
    std::string logsDir;
    std::string runtimeDir;
    std::string stagingDir;
    std::string logPath;
    std::map<int, OpenFile> fdTable;
    std::map<int, GraphicsBuffer> graphicsBuffers;
    std::map<std::string, std::string> properties;
    std::map<std::string, int> binderServices;
    std::string bootstrapStatus;
    std::string framebufferSource = "empty";
    std::vector<uint32_t> framebuffer;
    std::vector<GuestInputEvent> inputQueue;
    DirtyRect framebufferDirtyRect;
    int nextFd = 1000;
    int nextBinderHandle = 1;
    int nextGraphicsBufferId = 1;
    int framebufferWidth = 720;
    int framebufferHeight = 1280;
    int framebufferRotation = 0;
    int64_t framebufferFrames = 0;
    int64_t surfaceCopies = 0;
    int64_t inputQueueResets = 0;
    int64_t graphicsAllocations = 0;
    int64_t graphicsCompositions = 0;
    int64_t graphicsCommittedBuffers = 0;
    std::string foregroundPackage;
    std::string foregroundActivity;
    std::string foregroundLabel;
    std::string foregroundInstalledPath;
    std::string foregroundDataPath;
    std::string lastPackageOperation;
    std::string lastPackageName;
    std::string lastPackageOutcome = "idle";
    std::string lastPackageMessage;
    int64_t lastPackageEpochMillis = 0;
    int64_t launchAttempts = 0;
    int64_t launchSuccesses = 0;
    int64_t stopAttempts = 0;
    int64_t uninstallCount = 0;
    int64_t clearDataCount = 0;
    int64_t importCount = 0;
    int64_t activityManagerTransactions = 0;
    int64_t appProcessLaunches = 0;
    int64_t windowAttachCount = 0;
    int64_t windowCommitCount = 0;
    int64_t inputDispatchCount = 0;
    int64_t foregroundTouchEvents = 0;
    int64_t foregroundKeyEvents = 0;
    uint32_t foregroundColor = 0xFF202830u;
    uint32_t foregroundAccent = 0xFFB0B0FFu;
    int foregroundLastTouchX = -1;
    int foregroundLastTouchY = -1;
    int foregroundLastKeyCode = -1;
    int foregroundPid = -1;
    int nextGuestPid = 2000;
    bool foregroundAppProcessRunning = false;
    bool foregroundWindowAttached = false;
    std::string foregroundLaunchMode;
    int lastGraphicsBufferId = 0;
    int lastGraphicsBufferWidth = 0;
    int lastGraphicsBufferHeight = 0;
    int lastGraphicsBufferUsage = 0;
    int lastComposerBufferId = 0;
    int lastComposerLayers = 0;
    int audioSampleRate = 48000;
    int audioFramesGenerated = 0;
    int audioOutputAttempts = 0;
    int audioFramesWritten = 0;
    int audioLastFramesWritten = 0;
    int audioChannels = 2;
    std::string graphicsAccelerationMode = "software_framebuffer";
    std::string lastGraphicsBufferFormat = "RGBA_8888";
    std::string graphicsDeviceStatus = "not_started";
    std::string audioOutputStatus = "not_started";
    bool glesPassthroughReady = false;
    bool virglReady = false;
    bool venusReady = false;
    bool framebufferDirty = false;
    bool audioMuted = false;
    std::string lastBridge;
    std::string lastBridgeOperation;
    std::string lastBridgeResult;
    std::string lastBridgeReason;
    int64_t bridgeRequestCount = 0;
    int64_t bridgeAllowedCount = 0;
    int64_t bridgeDeniedCount = 0;
    int64_t bridgeUnavailableCount = 0;
    int64_t bridgeUnsupportedCount = 0;
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

bool isSafePackageName(const std::string& value) {
    if (value.empty() || value.front() == '.' || value.back() == '.') {
        return false;
    }
    bool sawDot = false;
    bool previousDot = false;
    for (const char ch : value) {
        const bool segmentChar =
            std::isalnum(static_cast<unsigned char>(ch)) ||
            ch == '_';
        if (ch == '.') {
            if (previousDot) return false;
            sawDot = true;
            previousDot = true;
            continue;
        }
        if (!segmentChar) return false;
        previousDot = false;
    }
    return sawDot;
}

bool renamePath(const std::string& from, const std::string& to) {
    if (from.empty() || to.empty()) return false;
    const auto parent = parentPath(to);
    if (!parent.empty() && !mkdirRecursive(parent)) return false;
    return std::rename(from.c_str(), to.c_str()) == 0;
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
        path == "/dev/gralloc" ||
        path == "/dev/graphics/gralloc" ||
        path == "/dev/hwcomposer" ||
        path == "/dev/graphics/hwcomposer0" ||
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

struct DisplayDimensions {
    int width = 0;
    int height = 0;
};

int normalizeRotation(int rotation) {
    int normalized = rotation % 360;
    if (normalized < 0) {
        normalized += 360;
    }
    return normalized;
}

bool isSupportedRotation(int rotation) {
    const int normalized = normalizeRotation(rotation);
    return normalized == 0 || normalized == 90 || normalized == 180 || normalized == 270;
}

DisplayDimensions orientedFramebufferDimensions(int framebufferWidth, int framebufferHeight, int rotation) {
    const int normalized = normalizeRotation(rotation);
    if (normalized == 90 || normalized == 270) {
        return {framebufferHeight, framebufferWidth};
    }
    return {framebufferWidth, framebufferHeight};
}

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

void orientedToFramebufferPixel(
    int orientedX,
    int orientedY,
    int framebufferWidth,
    int framebufferHeight,
    int rotation,
    int& sourceX,
    int& sourceY
) {
    const int normalized = normalizeRotation(rotation);
    switch (normalized) {
        case 90:
            sourceX = orientedY;
            sourceY = framebufferHeight - 1 - orientedX;
            break;
        case 180:
            sourceX = framebufferWidth - 1 - orientedX;
            sourceY = framebufferHeight - 1 - orientedY;
            break;
        case 270:
            sourceX = framebufferWidth - 1 - orientedY;
            sourceY = orientedX;
            break;
        default:
            sourceX = orientedX;
            sourceY = orientedY;
            break;
    }
    sourceX = clampValue(sourceX, 0, framebufferWidth - 1);
    sourceY = clampValue(sourceY, 0, framebufferHeight - 1);
}

void orientedToFramebufferPoint(
    float orientedX,
    float orientedY,
    int framebufferWidth,
    int framebufferHeight,
    int rotation,
    float& guestX,
    float& guestY
) {
    const int normalized = normalizeRotation(rotation);
    switch (normalized) {
        case 90:
            guestX = orientedY;
            guestY = static_cast<float>(framebufferHeight) - orientedX;
            break;
        case 180:
            guestX = static_cast<float>(framebufferWidth) - orientedX;
            guestY = static_cast<float>(framebufferHeight) - orientedY;
            break;
        case 270:
            guestX = static_cast<float>(framebufferWidth) - orientedY;
            guestY = orientedX;
            break;
        default:
            guestX = orientedX;
            guestY = orientedY;
            break;
    }
    guestX = clampValue(guestX, 0.0f, static_cast<float>(framebufferWidth));
    guestY = clampValue(guestY, 0.0f, static_cast<float>(framebufferHeight));
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
        instance.framebufferDirtyRect = {0, 0, instance.framebufferWidth, instance.framebufferHeight};
        instance.framebufferDirty = true;
    }
}

void markFramebufferDirtyLocked(Instance& instance, int left, int top, int width, int height) {
    if (width <= 0 || height <= 0) {
        return;
    }
    const int clampedLeft = clampValue(left, 0, instance.framebufferWidth);
    const int clampedTop = clampValue(top, 0, instance.framebufferHeight);
    const int clampedRight = clampValue(left + width, 0, instance.framebufferWidth);
    const int clampedBottom = clampValue(top + height, 0, instance.framebufferHeight);
    if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) {
        return;
    }
    const DirtyRect next = {clampedLeft, clampedTop, clampedRight, clampedBottom};
    if (instance.framebufferDirtyRect.empty()) {
        instance.framebufferDirtyRect = next;
    } else {
        instance.framebufferDirtyRect = {
            std::min(instance.framebufferDirtyRect.left, next.left),
            std::min(instance.framebufferDirtyRect.top, next.top),
            std::max(instance.framebufferDirtyRect.right, next.right),
            std::max(instance.framebufferDirtyRect.bottom, next.bottom),
        };
    }
    instance.framebufferDirty = true;
}

void clearFramebufferDirtyLocked(Instance& instance) {
    instance.framebufferDirtyRect = {};
    instance.framebufferDirty = false;
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
    markFramebufferDirtyLocked(instance, 0, 0, instance.framebufferWidth, instance.framebufferHeight);
}

GuestInputEvent mapTouchEventLocked(
    Instance& instance,
    int action,
    int pointerId,
    float hostX,
    float hostY
) {
    ensureFramebufferLocked(instance);
    const auto oriented = orientedFramebufferDimensions(
        instance.framebufferWidth,
        instance.framebufferHeight,
        instance.framebufferRotation
    );
    const auto mapping = computeSurfaceMapping(
        instance.width,
        instance.height,
        oriented.width,
        oriented.height
    );
    const float normalizedX = mapping.width <= 0
        ? 0.0f
        : clampValue((hostX - static_cast<float>(mapping.left)) / static_cast<float>(mapping.width), 0.0f, 1.0f);
    const float normalizedY = mapping.height <= 0
        ? 0.0f
        : clampValue((hostY - static_cast<float>(mapping.top)) / static_cast<float>(mapping.height), 0.0f, 1.0f);
    float guestX = 0.0f;
    float guestY = 0.0f;
    orientedToFramebufferPoint(
        normalizedX * static_cast<float>(oriented.width),
        normalizedY * static_cast<float>(oriented.height),
        instance.framebufferWidth,
        instance.framebufferHeight,
        instance.framebufferRotation,
        guestX,
        guestY
    );
    return {
        "touch",
        action,
        pointerId,
        0,
        0,
        hostX,
        hostY,
        guestX,
        guestY,
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

std::string parseNamedToken(const std::string& payload, const std::string& name, const std::string& fallback) {
    const auto keyPosition = payload.find(name);
    if (keyPosition == std::string::npos) {
        return fallback;
    }
    auto cursor = keyPosition + name.size();
    while (cursor < payload.size() && (payload[cursor] == '=' || std::isspace(static_cast<unsigned char>(payload[cursor])))) {
        ++cursor;
    }
    if (cursor >= payload.size()) {
        return fallback;
    }
    auto end = cursor;
    while (end < payload.size()) {
        const auto ch = static_cast<unsigned char>(payload[end]);
        if (std::isspace(ch) || payload[end] == ',' || payload[end] == ';') {
            break;
        }
        ++end;
    }
    return end > cursor ? payload.substr(cursor, end - cursor) : fallback;
}

bool isGrallocPath(const std::string& path) {
    return path == "/dev/gralloc" || path == "/dev/graphics/gralloc";
}

bool isHwcomposerPath(const std::string& path) {
    return path == "/dev/hwcomposer" || path == "/dev/graphics/hwcomposer0";
}

void handleVirtualDeviceWriteLocked(Instance& instance, const OpenFile& openFile, const std::string& payload) {
    if (openFile.guestPath == "/dev/graphics/fb0" || openFile.guestPath == "/dev/fb0") {
        const int frame = parseNamedPositiveInt(payload, "frame", static_cast<int>(instance.frame));
        writeFramebufferPatternLocked(instance, static_cast<uint32_t>(frame), "guest_fb0");
        return;
    }
    if (isGrallocPath(openFile.guestPath)) {
        const int width = parseNamedPositiveInt(payload, "width", instance.framebufferWidth);
        const int height = parseNamedPositiveInt(payload, "height", instance.framebufferHeight);
        const int usage = parseNamedPositiveInt(payload, "usage", 0);
        const auto format = parseNamedToken(payload, "format", "RGBA_8888");
        const int bufferId = instance.nextGraphicsBufferId++;
        const GraphicsBuffer buffer = {bufferId, width, height, format, usage, 0};
        instance.graphicsBuffers[bufferId] = buffer;
        instance.graphicsAllocations++;
        instance.lastGraphicsBufferId = bufferId;
        instance.lastGraphicsBufferWidth = width;
        instance.lastGraphicsBufferHeight = height;
        instance.lastGraphicsBufferFormat = format;
        instance.lastGraphicsBufferUsage = usage;
        instance.graphicsDeviceStatus = "gralloc_allocated";
        return;
    }
    if (isHwcomposerPath(openFile.guestPath)) {
        const int bufferId = parseNamedPositiveInt(payload, "buffer", instance.lastGraphicsBufferId);
        const int layers = parseNamedPositiveInt(payload, "layers", 1);
        const int frame = parseNamedPositiveInt(payload, "frame", static_cast<int>(instance.frame));
        instance.graphicsCompositions++;
        instance.lastComposerBufferId = bufferId;
        instance.lastComposerLayers = layers;
        auto found = instance.graphicsBuffers.find(bufferId);
        if (found == instance.graphicsBuffers.end() || layers <= 0) {
            instance.graphicsDeviceStatus = "compose_rejected";
            return;
        }
        found->second.commits++;
        instance.graphicsCommittedBuffers++;
        instance.graphicsDeviceStatus = "committed";
        writeFramebufferPatternLocked(instance, static_cast<uint32_t>(frame), "hwcomposer");
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

AudioOutputResult playToneToAAudio(int sampleRate, int frames, bool muted) {
    if (muted) {
        return {false, 0, "muted"};
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        return {false, 0, AAudio_convertResultToText(result)};
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        return {false, 0, AAudio_convertResultToText(result)};
    }

    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        const std::string status = AAudio_convertResultToText(result);
        AAudioStream_close(stream);
        return {true, 0, status};
    }

    constexpr double kPi = 3.14159265358979323846;
    constexpr double kToneHz = 440.0;
    constexpr int kChunkFrames = 256;
    constexpr int kChannels = 2;
    constexpr int16_t kAmplitude = 4096;
    constexpr int64_t kTimeoutNanos = 100000000;

    std::vector<int16_t> buffer(static_cast<std::size_t>(kChunkFrames * kChannels));
    double phase = 0.0;
    const double phaseStep = 2.0 * kPi * kToneHz / static_cast<double>(sampleRate);
    int framesWritten = 0;
    std::string status = "ok";
    while (framesWritten < frames) {
        const int framesThisChunk = std::min(kChunkFrames, frames - framesWritten);
        for (int frameIndex = 0; frameIndex < framesThisChunk; ++frameIndex) {
            const auto sample = static_cast<int16_t>(std::sin(phase) * static_cast<double>(kAmplitude));
            buffer[static_cast<std::size_t>(frameIndex * kChannels)] = sample;
            buffer[static_cast<std::size_t>(frameIndex * kChannels + 1)] = sample;
            phase += phaseStep;
            if (phase >= 2.0 * kPi) {
                phase -= 2.0 * kPi;
            }
        }

        const auto writeResult = AAudioStream_write(stream, buffer.data(), framesThisChunk, kTimeoutNanos);
        if (writeResult < 0) {
            status = AAudio_convertResultToText(static_cast<aaudio_result_t>(writeResult));
            break;
        }
        if (writeResult == 0) {
            status = "write_timeout";
            break;
        }
        framesWritten += static_cast<int>(writeResult);
    }

    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    return {true, framesWritten, status};
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
    if (isGrallocPath(guestPath)) {
        return "android-vm-gralloc protocol=ALLOC width height format usage\n";
    }
    if (isHwcomposerPath(guestPath)) {
        return "android-vm-hwcomposer protocol=COMPOSE buffer layers frame\n";
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

void phaseBGuestRuntimeEntrypoint(const std::shared_ptr<Instance>& instance, const std::string& instanceId) {
    instance->guestProcessRunning.store(true);
    appendInstanceLog(instance, "guest runtime entrypoint reached id=" + instanceId);

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
            "zygote=attempted;system_server=blocked:phase_c_pending";
    }
    appendInstanceLog(instance, "virtual init -> property service -> servicemanager -> zygote");
    appendInstanceLog(instance, "zygote process start attempted");
    appendInstanceLog(instance, "system_server blocked: Phase C process tree pending");
    instance->guestProcessRunning.store(false);
}

void startGuestProcessThread(const std::shared_ptr<Instance>& instance, const std::string& instanceId) {
    if (instance->guestThread.joinable()) {
        instance->guestThread.join();
    }
    instance->guestThread = std::thread(phaseBGuestRuntimeEntrypoint, instance, instanceId);
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
    int framebufferRotation = 0;
    int64_t nextCopies = 0;
    {
        std::lock_guard<std::mutex> guard(instance.lock);
        ensureFramebufferLocked(instance);
        framebuffer = instance.framebuffer;
        framebufferWidth = instance.framebufferWidth;
        framebufferHeight = instance.framebufferHeight;
        framebufferRotation = instance.framebufferRotation;
        nextCopies = ++instance.surfaceCopies;
        clearFramebufferDirtyLocked(instance);
    }

    const auto oriented = orientedFramebufferDimensions(framebufferWidth, framebufferHeight, framebufferRotation);
    const auto mapping = computeSurfaceMapping(width, height, oriented.width, oriented.height);
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
            const int orientedX = clampValue(
                ((x - mapping.left) * oriented.width) / std::max(1, mapping.width),
                0,
                oriented.width - 1
            );
            const int orientedY = clampValue(
                ((y - mapping.top) * oriented.height) / std::max(1, mapping.height),
                0,
                oriented.height - 1
            );
            int srcX = 0;
            int srcY = 0;
            orientedToFramebufferPixel(
                orientedX,
                orientedY,
                framebufferWidth,
                framebufferHeight,
                framebufferRotation,
                srcX,
                srcY
            );
            row[x] = framebuffer[static_cast<std::size_t>(srcY * framebufferWidth + srcX)];
        }
    }
    if (nextCopies % 120 == 0) {
        AVM_LOGI(
            "software framebuffer copied frame=%lld size=%dx%d rotation=%d surface=%dx%d",
            static_cast<long long>(nextCopies),
            framebufferWidth,
            framebufferHeight,
            framebufferRotation,
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

namespace {
void renderTouchTrailLocked(Instance& instance, int gx, int gy);
void renderKeyMarkerLocked(Instance& instance, int keyCode);
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
    const auto runtimeDir = extractJsonString(config, "runtimeDir");
    const auto stagingDir = extractJsonString(config, "stagingDir");
    const auto displayWidth = extractJsonInt(config, "width", 720);
    const auto displayHeight = extractJsonInt(config, "height", 1280);
    if (config.empty() || rootfsPath.empty() || dataDir.empty() || cacheDir.empty() || logsDir.empty()) {
        AVM_LOGW("invalid config for %s", id.c_str());
        auto instance = instanceFor(id);
        return setInstanceError(instance, kConfigParseFailed, "VM config is empty or missing guest paths");
    }
    mkdirRecursive(logsDir);
    if (!runtimeDir.empty()) mkdirRecursive(runtimeDir + "/packages");
    auto instance = instanceFor(id);
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->configJson = config;
        instance->rootfsPath = rootfsPath;
        instance->dataDir = dataDir;
        instance->cacheDir = cacheDir;
        instance->logsDir = logsDir;
        instance->runtimeDir = runtimeDir;
        instance->stagingDir = stagingDir;
        instance->logPath = logsDir + "/native_runtime.log";
        instance->properties = loadGuestProperties(rootfsPath);
        instance->framebufferWidth = displayWidth > 0 ? displayWidth : 720;
        instance->framebufferHeight = displayHeight > 0 ? displayHeight : 1280;
        instance->framebufferRotation = 0;
        instance->framebuffer.clear();
        instance->framebufferDirtyRect = {};
        instance->framebufferFrames = 0;
        instance->surfaceCopies = 0;
        instance->graphicsBuffers.clear();
        instance->nextGraphicsBufferId = 1;
        instance->graphicsAllocations = 0;
        instance->graphicsCompositions = 0;
        instance->graphicsCommittedBuffers = 0;
        instance->lastGraphicsBufferId = 0;
        instance->lastGraphicsBufferWidth = 0;
        instance->lastGraphicsBufferHeight = 0;
        instance->lastGraphicsBufferUsage = 0;
        instance->lastComposerBufferId = 0;
        instance->lastComposerLayers = 0;
        instance->graphicsAccelerationMode = "software_framebuffer";
        instance->lastGraphicsBufferFormat = "RGBA_8888";
        instance->graphicsDeviceStatus = "not_started";
        instance->glesPassthroughReady = false;
        instance->virglReady = false;
        instance->venusReady = false;
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
        return kOk;
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
        const auto oriented = orientedFramebufferDimensions(
            instance->framebufferWidth,
            instance->framebufferHeight,
            instance->framebufferRotation
        );
        const auto mapping = computeSurfaceMapping(
            instance->width,
            instance->height,
            oriented.width,
            oriented.height
        );
        json << "{"
             << "\"framebufferWidth\":" << instance->framebufferWidth << ","
             << "\"framebufferHeight\":" << instance->framebufferHeight << ","
             << "\"framebufferRotation\":" << instance->framebufferRotation << ","
             << "\"orientedWidth\":" << oriented.width << ","
             << "\"orientedHeight\":" << oriented.height << ","
             << "\"surfaceWidth\":" << instance->width << ","
             << "\"surfaceHeight\":" << instance->height << ","
             << "\"mappingLeft\":" << mapping.left << ","
             << "\"mappingTop\":" << mapping.top << ","
             << "\"mappingWidth\":" << mapping.width << ","
             << "\"mappingHeight\":" << mapping.height << ","
             << "\"framebufferFrames\":" << instance->framebufferFrames << ","
             << "\"surfaceCopies\":" << instance->surfaceCopies << ","
             << "\"graphicsAllocations\":" << instance->graphicsAllocations << ","
             << "\"graphicsBuffers\":" << instance->graphicsBuffers.size() << ","
             << "\"lastGraphicsBufferId\":" << instance->lastGraphicsBufferId << ","
             << "\"lastGraphicsBufferWidth\":" << instance->lastGraphicsBufferWidth << ","
             << "\"lastGraphicsBufferHeight\":" << instance->lastGraphicsBufferHeight << ","
             << "\"lastGraphicsBufferFormat\":\"" << escapeJson(instance->lastGraphicsBufferFormat) << "\","
             << "\"lastGraphicsBufferUsage\":" << instance->lastGraphicsBufferUsage << ","
             << "\"graphicsCompositions\":" << instance->graphicsCompositions << ","
             << "\"graphicsCommittedBuffers\":" << instance->graphicsCommittedBuffers << ","
             << "\"lastComposerBufferId\":" << instance->lastComposerBufferId << ","
             << "\"lastComposerLayers\":" << instance->lastComposerLayers << ","
             << "\"graphicsAccelerationMode\":\"" << escapeJson(instance->graphicsAccelerationMode) << "\","
             << "\"glesPassthroughReady\":" << (instance->glesPassthroughReady ? "true" : "false") << ","
             << "\"virglReady\":" << (instance->virglReady ? "true" : "false") << ","
             << "\"venusReady\":" << (instance->venusReady ? "true" : "false") << ","
             << "\"graphicsDeviceStatus\":\"" << escapeJson(instance->graphicsDeviceStatus) << "\","
             << "\"dirty\":" << (instance->framebufferDirty ? "true" : "false") << ","
             << "\"dirtyLeft\":" << instance->framebufferDirtyRect.left << ","
             << "\"dirtyTop\":" << instance->framebufferDirtyRect.top << ","
             << "\"dirtyWidth\":" << instance->framebufferDirtyRect.width() << ","
             << "\"dirtyHeight\":" << instance->framebufferDirtyRect.height() << ","
             << "\"framebufferSource\":\"" << escapeJson(instance->framebufferSource) << "\","
             << "\"surfaceAttached\":" << (instance->window != nullptr ? "true" : "false") << ","
             << "\"renderRunning\":" << (renderRunning ? "true" : "false")
             << "}";
    }
    const auto result = json.str();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_setFramebufferRotation(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jint rotationDegrees
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance || !isSupportedRotation(rotationDegrees)) {
        return kInvalidInstance;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->framebufferRotation = normalizeRotation(rotationDegrees);
        markFramebufferDirtyLocked(*instance, 0, 0, instance->framebufferWidth, instance->framebufferHeight);
    }
    appendInstanceLog(instance, "framebuffer rotation=" + std::to_string(normalizeRotation(rotationDegrees)));
    return kOk;
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
    const auto output = playToneToAAudio(sampleRate, frames, muted == JNI_TRUE);
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->audioSampleRate = sampleRate;
        instance->audioFramesGenerated += frames;
        instance->audioMuted = muted == JNI_TRUE;
        instance->audioOutputAttempts++;
        instance->audioFramesWritten += output.framesWritten;
        instance->audioLastFramesWritten = output.framesWritten;
        instance->audioOutputStatus = output.status;
    }
    appendInstanceLog(
        instance,
        "audio test tone frames=" + std::to_string(frames) +
            " written=" + std::to_string(output.framesWritten) +
            " status=" + output.status
    );
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
             << "\"outputAttempts\":" << instance->audioOutputAttempts << ","
             << "\"framesWritten\":" << instance->audioFramesWritten << ","
             << "\"lastFramesWritten\":" << instance->audioLastFramesWritten << ","
             << "\"channels\":" << instance->audioChannels << ","
             << "\"muted\":" << (instance->audioMuted ? "true" : "false") << ","
             << "\"outputStatus\":\"" << escapeJson(instance->audioOutputStatus) << "\""
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
        if (!instance->foregroundPackage.empty()) {
            const int gx = static_cast<int>(mapped.guestX);
            const int gy = static_cast<int>(mapped.guestY);
            renderTouchTrailLocked(*instance, gx, gy);
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
        if (!instance->foregroundPackage.empty()) {
            renderKeyMarkerLocked(*instance, keyCode);
        }
    }
    const auto count = ++instance->inputEvents;
    if (count % 40 == 0) {
        AVM_LOGI("key id=%s action=%d key=%d meta=%d", id.c_str(), action, keyCode, metaState);
    }
    return kOk;
}

namespace {
int64_t epochMillisNow() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

std::string formatTimestampUtc(int64_t epochMillis) {
    std::time_t seconds = static_cast<std::time_t>(epochMillis / 1000);
    std::tm tm {};
    gmtime_r(&seconds, &tm);
    char buffer[32];
    std::snprintf(
        buffer,
        sizeof(buffer),
        "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tm.tm_year + 1900,
        tm.tm_mon + 1,
        tm.tm_mday,
        tm.tm_hour,
        tm.tm_min,
        tm.tm_sec
    );
    return std::string(buffer);
}

uint32_t packageColor(const std::string& packageName) {
    uint32_t hash = 0x811c9dc5u;
    for (char ch : packageName) {
        hash ^= static_cast<uint8_t>(ch);
        hash *= 0x01000193u;
    }
    const uint32_t r = (((hash >> 16) & 0xFF) | 0x40);
    const uint32_t g = (((hash >> 8) & 0xFF) | 0x40);
    const uint32_t b = ((hash & 0xFF) | 0x40);
    return (0xFFu << 24) | (r << 16) | (g << 8) | b;
}

uint32_t accentFor(uint32_t base) {
    const uint32_t r = std::min<uint32_t>(0xFF, ((base >> 16) & 0xFF) + 0x60);
    const uint32_t g = std::min<uint32_t>(0xFF, ((base >> 8) & 0xFF) + 0x60);
    const uint32_t b = std::min<uint32_t>(0xFF, (base & 0xFF) + 0x60);
    return (0xFFu << 24) | (r << 16) | (g << 8) | b;
}

std::string nativePackageDirOf(const Instance& instance) {
    return instance.runtimeDir.empty() ? std::string() : instance.runtimeDir + "/packages";
}

std::string nativePackageEntryPath(const Instance& instance, const std::string& name) {
    const auto dir = nativePackageDirOf(instance);
    return dir.empty() ? std::string() : dir + "/" + name + ".json";
}

std::string appDirOf(const Instance& instance, const std::string& name) {
    return instance.dataDir + "/app/" + name;
}

std::string dataDirOf(const Instance& instance, const std::string& name) {
    return instance.dataDir + "/data/" + name;
}

bool removeRecursivePath(const std::string& path) {
    struct stat info {};
    if (lstat(path.c_str(), &info) != 0) {
        return errno == ENOENT;
    }
    if (S_ISDIR(info.st_mode)) {
        DIR* dir = opendir(path.c_str());
        if (dir == nullptr) return false;
        bool ok = true;
        while (auto* entry = readdir(dir)) {
            const std::string name = entry->d_name;
            if (name == "." || name == "..") continue;
            ok = removeRecursivePath(path + "/" + name) && ok;
        }
        closedir(dir);
        if (rmdir(path.c_str()) != 0 && errno != ENOENT) ok = false;
        return ok;
    }
    return unlink(path.c_str()) == 0 || errno == ENOENT;
}

bool copyFileBytes(const std::string& src, const std::string& dst, int64_t* outSize) {
    std::ifstream input(src, std::ios::binary);
    if (!input) return false;
    if (!mkdirRecursive(parentPath(dst))) return false;
    std::ofstream output(dst, std::ios::binary | std::ios::trunc);
    if (!output) return false;
    char buffer[64 * 1024];
    int64_t total = 0;
    while (input) {
        input.read(buffer, sizeof(buffer));
        const auto read = input.gcount();
        if (read > 0) {
            output.write(buffer, read);
            if (!output) return false;
            total += read;
        }
    }
    if (outSize) *outSize = total;
    return true;
}

std::string sidecarPathFor(const std::string& stagedApkPath) {
    const auto dot = stagedApkPath.find_last_of('.');
    if (dot == std::string::npos) return stagedApkPath + ".json";
    return stagedApkPath.substr(0, dot) + ".json";
}

std::string serializePackageEntry(
    const std::string& packageName,
    const std::string& label,
    int64_t versionCode,
    const std::string& versionName,
    const std::string& installedPath,
    const std::string& dataPath,
    const std::string& sha256,
    const std::string& sourceName,
    const std::string& installedAt,
    const std::string& updatedAt,
    bool enabled,
    bool launchable,
    const std::string& launcherActivity,
    const std::vector<std::string>& nativeAbis
) {
    std::ostringstream os;
    os << '{';
    os << "\"packageName\":\"" << escapeJson(packageName) << "\",";
    os << "\"label\":\"" << escapeJson(label.empty() ? packageName : label) << "\",";
    os << "\"versionCode\":" << versionCode << ',';
    if (versionName.empty()) {
        os << "\"versionName\":null,";
    } else {
        os << "\"versionName\":\"" << escapeJson(versionName) << "\",";
    }
    os << "\"installedPath\":\"" << escapeJson(installedPath) << "\",";
    os << "\"dataPath\":\"" << escapeJson(dataPath) << "\",";
    if (sha256.empty()) {
        os << "\"sha256\":null,";
    } else {
        os << "\"sha256\":\"" << escapeJson(sha256) << "\",";
    }
    if (sourceName.empty()) {
        os << "\"sourceName\":null,";
    } else {
        os << "\"sourceName\":\"" << escapeJson(sourceName) << "\",";
    }
    os << "\"installedAt\":\"" << escapeJson(installedAt) << "\",";
    os << "\"updatedAt\":\"" << escapeJson(updatedAt) << "\",";
    os << "\"enabled\":" << (enabled ? "true" : "false") << ',';
    os << "\"launchable\":" << (launchable ? "true" : "false") << ',';
    if (launcherActivity.empty()) {
        os << "\"launcherActivity\":null,";
    } else {
        os << "\"launcherActivity\":\"" << escapeJson(launcherActivity) << "\",";
    }
    os << "\"nativeAbis\":[";
    for (std::size_t i = 0; i < nativeAbis.size(); ++i) {
        if (i > 0) os << ',';
        os << "\"" << escapeJson(nativeAbis[i]) << "\"";
    }
    os << "]}";
    return os.str();
}

std::vector<std::string> listNativePackageEntries(const Instance& instance) {
    std::vector<std::string> entries;
    const auto dir = nativePackageDirOf(instance);
    if (dir.empty()) return entries;
    DIR* d = opendir(dir.c_str());
    if (d == nullptr) return entries;
    while (auto* entry = readdir(d)) {
        const std::string name = entry->d_name;
        if (name == "." || name == "..") continue;
        if (name.size() > 5 && name.compare(name.size() - 5, 5, ".json") == 0) {
            entries.push_back(dir + "/" + name);
        }
    }
    closedir(d);
    std::sort(entries.begin(), entries.end());
    return entries;
}

std::string buildAggregateIndex(const Instance& instance, const std::string& nowIso) {
    std::ostringstream os;
    os << "{\n  \"version\": 1,\n  \"instanceId\": \"" << escapeJson(instance.configJson.empty() ? std::string() : extractJsonString(instance.configJson, "instanceId")) << "\",\n";
    os << "  \"updatedAt\": \"" << escapeJson(nowIso) << "\",\n";
    os << "  \"packages\": [";
    bool first = true;
    for (const auto& path : listNativePackageEntries(instance)) {
        const auto content = readWholeFile(path);
        if (content.empty()) continue;
        if (!first) os << ",";
        os << "\n    " << content;
        first = false;
    }
    os << "\n  ]\n}\n";
    return os.str();
}

bool persistAggregateIndex(const Instance& instance, const std::string& nowIso) {
    if (instance.runtimeDir.empty()) return false;
    const auto path = instance.runtimeDir + "/package-index.json";
    return writeWholeFile(path, buildAggregateIndex(instance, nowIso));
}

void appendPackageInstallLog(const Instance& instance, const std::string& line) {
    if (instance.logsDir.empty()) return;
    mkdirRecursive(instance.logsDir);
    const auto logPath = instance.logsDir + "/package_install.log";
    const auto timestamp = formatTimestampUtc(epochMillisNow());
    std::ofstream output(logPath, std::ios::app);
    if (output) {
        output << timestamp << " " << line << "\n";
    }
}

void clearForegroundLocked(Instance& instance) {
    instance.foregroundPackage.clear();
    instance.foregroundActivity.clear();
    instance.foregroundLabel.clear();
    instance.foregroundInstalledPath.clear();
    instance.foregroundDataPath.clear();
    instance.foregroundLastTouchX = -1;
    instance.foregroundLastTouchY = -1;
    instance.foregroundLastKeyCode = -1;
    instance.foregroundTouchEvents = 0;
    instance.foregroundKeyEvents = 0;
    instance.foregroundPid = -1;
    instance.foregroundAppProcessRunning = false;
    instance.foregroundWindowAttached = false;
    instance.foregroundLaunchMode.clear();
    const auto pixels = static_cast<std::size_t>(instance.framebufferWidth) *
        static_cast<std::size_t>(instance.framebufferHeight);
    if (instance.framebuffer.size() < pixels) instance.framebuffer.assign(pixels, 0);
    std::fill(
        instance.framebuffer.begin(),
        instance.framebuffer.begin() + pixels,
        0xFF101015u
    );
    instance.framebufferDirty = true;
    instance.framebufferDirtyRect = {0, 0, instance.framebufferWidth, instance.framebufferHeight};
    instance.framebufferSource = "package_stopped";
    instance.framebufferFrames++;
}

void renderForegroundLocked(Instance& instance) {
    const int width = instance.framebufferWidth;
    const int height = instance.framebufferHeight;
    if (width <= 0 || height <= 0) return;
    const auto pixels = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    if (instance.framebuffer.size() < pixels) instance.framebuffer.assign(pixels, 0);

    const uint32_t base = packageColor(instance.foregroundPackage);
    const uint32_t accent = accentFor(base);
    instance.foregroundColor = base;
    instance.foregroundAccent = accent;

    const int headerHeight = std::max(8, height / 12);
    const int footerHeight = std::max(8, height / 14);
    const int contentTop = headerHeight;
    const int contentBottom = height - footerHeight;

    for (int y = 0; y < height; ++y) {
        uint32_t color;
        if (y < headerHeight) {
            color = accent;
        } else if (y >= contentBottom) {
            color = 0xFF202028u;
        } else {
            color = base;
        }
        const std::size_t row = static_cast<std::size_t>(y) * static_cast<std::size_t>(width);
        std::fill(
            instance.framebuffer.begin() + row,
            instance.framebuffer.begin() + row + width,
            color
        );
    }

    // Draw a launcher-id stripe across the header from a hash of the package
    // name + activity, so different packages produce visibly distinct frames.
    const auto launcherSignature = packageColor(
        instance.foregroundPackage + "/" + instance.foregroundActivity
    );
    const int stripeBase = headerHeight / 4;
    for (int y = stripeBase; y < headerHeight - stripeBase && y < height; ++y) {
        const std::size_t row = static_cast<std::size_t>(y) * static_cast<std::size_t>(width);
        for (int x = 0; x < width; ++x) {
            if ((x % 24) < 12) instance.framebuffer[row + x] = launcherSignature;
        }
    }

    instance.framebufferDirty = true;
    instance.framebufferDirtyRect = {0, 0, width, height};
    instance.framebufferSource = "runtime_app_window";
    instance.framebufferFrames++;
    instance.windowCommitCount++;
    (void)contentTop;
}

void stampPixelBlockLocked(Instance& instance, int cx, int cy, int radius, uint32_t color) {
    const int width = instance.framebufferWidth;
    const int height = instance.framebufferHeight;
    if (width <= 0 || height <= 0) return;
    const int x0 = std::max(0, cx - radius);
    const int x1 = std::min(width - 1, cx + radius);
    const int y0 = std::max(0, cy - radius);
    const int y1 = std::min(height - 1, cy + radius);
    for (int y = y0; y <= y1; ++y) {
        const std::size_t row = static_cast<std::size_t>(y) * static_cast<std::size_t>(width);
        for (int x = x0; x <= x1; ++x) {
            instance.framebuffer[row + x] = color;
        }
    }
    instance.framebufferDirty = true;
    instance.framebufferDirtyRect = {x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    instance.framebufferFrames++;
    instance.windowCommitCount++;
}

void renderTouchTrailLocked(Instance& instance, int gx, int gy) {
    if (instance.foregroundPackage.empty() || !instance.foregroundAppProcessRunning) return;
    instance.foregroundLastTouchX = gx;
    instance.foregroundLastTouchY = gy;
    instance.foregroundTouchEvents++;
    instance.inputDispatchCount++;
    stampPixelBlockLocked(instance, gx, gy, 12, instance.foregroundAccent);
}

void renderKeyMarkerLocked(Instance& instance, int keyCode) {
    if (instance.foregroundPackage.empty() || !instance.foregroundAppProcessRunning) return;
    instance.foregroundLastKeyCode = keyCode;
    instance.foregroundKeyEvents++;
    instance.inputDispatchCount++;
    const int width = instance.framebufferWidth;
    const int height = instance.framebufferHeight;
    if (width <= 0 || height <= 0) return;
    const int footerHeight = std::max(8, height / 14);
    const int footerTop = height - footerHeight;
    const uint32_t color = packageColor(
        instance.foregroundPackage + ":" + std::to_string(keyCode) +
        ":" + std::to_string(instance.foregroundKeyEvents)
    );
    for (int y = footerTop; y < height; ++y) {
        const std::size_t row = static_cast<std::size_t>(y) * static_cast<std::size_t>(width);
        std::fill(
            instance.framebuffer.begin() + row,
            instance.framebuffer.begin() + row + width,
            color
        );
    }
    instance.framebufferDirty = true;
    instance.framebufferDirtyRect = {0, footerTop, width, footerHeight};
    instance.framebufferFrames++;
    instance.windowCommitCount++;
}

std::string packageOperationStatusJson(const Instance& instance) {
    std::ostringstream os;
    os << '{';
    os << "\"foregroundPackage\":\"" << escapeJson(instance.foregroundPackage) << "\","
       << "\"foregroundActivity\":\"" << escapeJson(instance.foregroundActivity) << "\","
       << "\"foregroundLabel\":\"" << escapeJson(instance.foregroundLabel) << "\","
       << "\"foregroundInstalledPath\":\"" << escapeJson(instance.foregroundInstalledPath) << "\","
       << "\"foregroundDataPath\":\"" << escapeJson(instance.foregroundDataPath) << "\","
       << "\"lastOperation\":\"" << escapeJson(instance.lastPackageOperation) << "\","
       << "\"lastPackageName\":\"" << escapeJson(instance.lastPackageName) << "\","
       << "\"lastOutcome\":\"" << escapeJson(instance.lastPackageOutcome) << "\","
       << "\"lastMessage\":\"" << escapeJson(instance.lastPackageMessage) << "\","
       << "\"lastEpochMillis\":" << instance.lastPackageEpochMillis << ','
       << "\"importCount\":" << instance.importCount << ','
       << "\"launchAttempts\":" << instance.launchAttempts << ','
       << "\"launchSuccesses\":" << instance.launchSuccesses << ','
       << "\"stopAttempts\":" << instance.stopAttempts << ','
       << "\"uninstallCount\":" << instance.uninstallCount << ','
       << "\"clearDataCount\":" << instance.clearDataCount << ','
       << "\"activityManagerTransactions\":" << instance.activityManagerTransactions << ','
       << "\"appProcessLaunches\":" << instance.appProcessLaunches << ','
       << "\"windowAttachCount\":" << instance.windowAttachCount << ','
       << "\"windowCommitCount\":" << instance.windowCommitCount << ','
       << "\"inputDispatchCount\":" << instance.inputDispatchCount << ','
       << "\"foregroundTouchEvents\":" << instance.foregroundTouchEvents << ','
       << "\"foregroundKeyEvents\":" << instance.foregroundKeyEvents << ','
       << "\"foregroundLastTouchX\":" << instance.foregroundLastTouchX << ','
       << "\"foregroundLastTouchY\":" << instance.foregroundLastTouchY << ','
       << "\"foregroundLastKeyCode\":" << instance.foregroundLastKeyCode << ','
       << "\"foregroundPid\":" << instance.foregroundPid << ','
       << "\"foregroundAppProcessRunning\":" << (instance.foregroundAppProcessRunning ? "true" : "false") << ','
       << "\"foregroundWindowAttached\":" << (instance.foregroundWindowAttached ? "true" : "false") << ','
       << "\"foregroundLaunchMode\":\"" << escapeJson(instance.foregroundLaunchMode) << "\","
       << "\"lastBridge\":\"" << escapeJson(instance.lastBridge) << "\","
       << "\"lastBridgeOperation\":\"" << escapeJson(instance.lastBridgeOperation) << "\","
       << "\"lastBridgeResult\":\"" << escapeJson(instance.lastBridgeResult) << "\","
       << "\"lastBridgeReason\":\"" << escapeJson(instance.lastBridgeReason) << "\","
       << "\"bridgeRequestCount\":" << instance.bridgeRequestCount;
    os << '}';
    return os.str();
}

std::string bridgeRuntimeStatusJson(const Instance& instance) {
    std::ostringstream os;
    os << '{';
    os << "\"lastBridge\":\"" << escapeJson(instance.lastBridge) << "\","
       << "\"lastOperation\":\"" << escapeJson(instance.lastBridgeOperation) << "\","
       << "\"lastResult\":\"" << escapeJson(instance.lastBridgeResult) << "\","
       << "\"lastReason\":\"" << escapeJson(instance.lastBridgeReason) << "\","
       << "\"requestCount\":" << instance.bridgeRequestCount << ','
       << "\"allowedCount\":" << instance.bridgeAllowedCount << ','
       << "\"deniedCount\":" << instance.bridgeDeniedCount << ','
       << "\"unavailableCount\":" << instance.bridgeUnavailableCount << ','
       << "\"unsupportedCount\":" << instance.bridgeUnsupportedCount;
    os << '}';
    return os.str();
}

bool hasRuntimeLaunchServicesLocked(const Instance& instance) {
    return instance.binderServices.find("activity") != instance.binderServices.end() &&
        instance.binderServices.find("window") != instance.binderServices.end() &&
        instance.binderServices.find("input") != instance.binderServices.end();
}

bool awaitRuntimeLaunchServices(const std::shared_ptr<Instance>& instance) {
    for (int attempt = 0; attempt < 50; ++attempt) {
        {
            std::lock_guard<std::mutex> guard(instance->lock);
            if (hasRuntimeLaunchServicesLocked(*instance)) return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return false;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_importApk(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring stagedApkPath
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto stagedPath = ScopedUtfChars(env, stagedApkPath).str();
    auto instance = findInstance(id);
    if (!instance) return kInvalidInstance;
    if (stagedPath.empty() || !fileExists(stagedPath)) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "import";
        instance->lastPackageOutcome = "missing_staged";
        instance->lastPackageMessage = "staged APK not found: " + stagedPath;
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }

    const auto sidecarPath = sidecarPathFor(stagedPath);
    const auto sidecarRaw = readWholeFile(sidecarPath);
    if (sidecarRaw.empty()) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "import";
        instance->lastPackageOutcome = "missing_sidecar";
        instance->lastPackageMessage = "missing metadata sidecar at " + sidecarPath;
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }

    const auto packageName = extractJsonString(sidecarRaw, "packageName");
    if (!isSafePackageName(packageName)) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "import";
        instance->lastPackageOutcome = "invalid_package_name";
        instance->lastPackageMessage = "sidecar has invalid packageName";
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }
    const auto label = extractJsonString(sidecarRaw, "label");
    const auto versionName = extractJsonString(sidecarRaw, "versionName");
    const int64_t versionCode = extractJsonInt(sidecarRaw, "versionCode", 0);
    const auto launcherActivity = extractJsonString(sidecarRaw, "launcherActivity");
    const auto sha256 = extractJsonString(sidecarRaw, "sha256");
    const auto sourceName = extractJsonString(sidecarRaw, "sourceName");

    std::vector<std::string> abis;
    {
        const std::string key = "\"nativeAbis\"";
        auto pos = sidecarRaw.find(key);
        if (pos != std::string::npos) {
            const auto open = sidecarRaw.find('[', pos);
            const auto close = sidecarRaw.find(']', open);
            if (open != std::string::npos && close != std::string::npos) {
                std::string body = sidecarRaw.substr(open + 1, close - open - 1);
                std::string current;
                bool inQuote = false;
                for (char ch : body) {
                    if (ch == '"') {
                        if (inQuote) {
                            if (!current.empty()) abis.push_back(current);
                            current.clear();
                        }
                        inQuote = !inQuote;
                    } else if (inQuote) {
                        current += ch;
                    }
                }
            }
        }
    }

    std::string installedPath;
    std::string dataPath;
    std::string entryPath;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        installedPath = appDirOf(*instance, packageName) + "/base.apk";
        dataPath = dataDirOf(*instance, packageName);
        entryPath = nativePackageEntryPath(*instance, packageName);
        if (instance->runtimeDir.empty() || instance->dataDir.empty()) {
            instance->lastPackageOperation = "import";
            instance->lastPackageOutcome = "config_missing";
            instance->lastPackageMessage = "instance paths not initialised";
            instance->lastPackageEpochMillis = epochMillisNow();
            return kConfigParseFailed;
        }
    }

    std::string previousJson;
    std::string installedAtIso = formatTimestampUtc(epochMillisNow());
    const bool hadEntry = fileExists(entryPath);
    const bool hadInstalledApk = fileExists(installedPath);
    const bool hadDataDir = fileExists(dataPath);
    if (fileExists(entryPath)) {
        previousJson = readWholeFile(entryPath);
        const auto previousInstalledAt = extractJsonString(previousJson, "installedAt");
        if (!previousInstalledAt.empty()) installedAtIso = previousInstalledAt;
    }
    const auto updatedAtIso = formatTimestampUtc(epochMillisNow());
    const auto token = std::to_string(epochMillisNow());
    const auto tempInstalledPath = installedPath + ".tmp-" + token;
    const auto backupInstalledPath = installedPath + ".bak-" + token;
    const auto tempEntryPath = entryPath + ".tmp-" + token;
    const auto backupEntryPath = entryPath + ".bak-" + token;

    auto failImport = [&](const std::string& outcome, const std::string& message) -> jint {
        unlink(tempInstalledPath.c_str());
        unlink(tempEntryPath.c_str());
        if (fileExists(backupInstalledPath)) {
            unlink(installedPath.c_str());
            renamePath(backupInstalledPath, installedPath);
        }
        if (fileExists(backupEntryPath)) {
            unlink(entryPath.c_str());
            renamePath(backupEntryPath, entryPath);
        }
        if (!hadInstalledApk) {
            unlink(installedPath.c_str());
        }
        if (!hadEntry) {
            unlink(entryPath.c_str());
        }
        if (!hadDataDir && !previousJson.empty()) {
            // Defensive: updates should preserve app data, but only remove a
            // data dir we know was created by this failed transaction.
            removeRecursivePath(dataPath);
        } else if (!hadDataDir && previousJson.empty()) {
            removeRecursivePath(dataPath);
        }
        {
            std::lock_guard<std::mutex> guard(instance->lock);
            persistAggregateIndex(*instance, formatTimestampUtc(epochMillisNow()));
            appendPackageInstallLog(*instance, "IMPORT_FAILED " + packageName + " " + outcome);
            instance->lastPackageOperation = "import";
            instance->lastPackageName = packageName;
            instance->lastPackageOutcome = outcome;
            instance->lastPackageMessage = message;
            instance->lastPackageEpochMillis = epochMillisNow();
        }
        return kInternalError;
    };

    if (!mkdirRecursive(parentPath(installedPath))) {
        return failImport("io_error", "cannot create app dir");
    }
    int64_t copiedBytes = 0;
    if (!copyFileBytes(stagedPath, tempInstalledPath, &copiedBytes)) {
        return failImport("io_error", "failed to copy staged APK");
    }
    if (!mkdirRecursive(dataPath)) {
        return failImport("io_error", "cannot create data dir");
    }

    const bool launchable = !launcherActivity.empty();
    const auto entryJson = serializePackageEntry(
        packageName,
        label,
        versionCode,
        versionName,
        installedPath,
        dataPath,
        sha256,
        sourceName,
        installedAtIso,
        updatedAtIso,
        true,
        launchable,
        launcherActivity,
        abis
    );
    if (!writeWholeFile(tempEntryPath, entryJson)) {
        return failImport("index_error", "cannot write package entry");
    }

    if (hadInstalledApk && !renamePath(installedPath, backupInstalledPath)) {
        return failImport("io_error", "cannot back up existing base.apk");
    }
    if (!renamePath(tempInstalledPath, installedPath)) {
        return failImport("io_error", "cannot commit base.apk");
    }
    if (hadEntry && !renamePath(entryPath, backupEntryPath)) {
        return failImport("index_error", "cannot back up package entry");
    }
    if (!renamePath(tempEntryPath, entryPath)) {
        return failImport("index_error", "cannot commit package entry");
    }

    {
        std::lock_guard<std::mutex> guard(instance->lock);
        if (!persistAggregateIndex(*instance, updatedAtIso)) {
            // Roll back both the APK and per-package entry so the aggregate
            // package state cannot point at a partially committed install.
            unlink(installedPath.c_str());
            if (fileExists(backupInstalledPath)) {
                renamePath(backupInstalledPath, installedPath);
            }
            unlink(entryPath.c_str());
            if (fileExists(backupEntryPath)) {
                renamePath(backupEntryPath, entryPath);
            }
            if (!hadDataDir) {
                removeRecursivePath(dataPath);
            }
            persistAggregateIndex(*instance, formatTimestampUtc(epochMillisNow()));
            appendPackageInstallLog(*instance, "IMPORT_FAILED " + packageName + " index_error");
            instance->lastPackageOperation = "import";
            instance->lastPackageName = packageName;
            instance->lastPackageOutcome = "index_error";
            instance->lastPackageMessage = "cannot persist aggregate package index";
            instance->lastPackageEpochMillis = epochMillisNow();
            return kInternalError;
        }
    }

    unlink(backupInstalledPath.c_str());
    unlink(backupEntryPath.c_str());

    {
        std::lock_guard<std::mutex> guard(instance->lock);
        appendPackageInstallLog(
            *instance,
            std::string(previousJson.empty() ? "INSTALLED" : "UPDATED") +
                " " + packageName +
                " versionCode=" + std::to_string(versionCode) +
                " sha256=" + sha256 +
                " size=" + std::to_string(copiedBytes)
        );
        instance->importCount++;
        instance->lastPackageOperation = "import";
        instance->lastPackageName = packageName;
        instance->lastPackageOutcome = "ok";
        instance->lastPackageMessage =
            (previousJson.empty() ? "installed " : "updated ") + packageName;
        instance->lastPackageEpochMillis = epochMillisNow();
    }
    AVM_LOGI(
        "importApk id=%s package=%s versionCode=%lld",
        id.c_str(),
        packageName.c_str(),
        static_cast<long long>(versionCode)
    );
    return kOk;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_listPackages(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) return env->NewStringUTF("{\"packages\":[]}");
    std::string nowIso = formatTimestampUtc(epochMillisNow());
    std::ostringstream os;
    os << "{\"version\":1,\"updatedAt\":\"" << escapeJson(nowIso) << "\",\"packages\":[";
    bool first;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        first = true;
        for (const auto& path : listNativePackageEntries(*instance)) {
            const auto content = readWholeFile(path);
            if (content.empty()) continue;
            if (!first) os << ',';
            os << content;
            first = false;
        }
    }
    os << "]}";
    return env->NewStringUTF(os.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_uninstallPackage(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring packageName
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto pkg = ScopedUtfChars(env, packageName).str();
    auto instance = findInstance(id);
    if (!instance) return kInvalidInstance;
    if (pkg.empty()) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "uninstall";
        instance->lastPackageOutcome = "invalid_package";
        instance->lastPackageMessage = "package name is empty";
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInvalidInstance;
    }
    std::string entryPath;
    std::string appDir;
    std::string dataDir;
    bool wasForeground;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        entryPath = nativePackageEntryPath(*instance, pkg);
        appDir = appDirOf(*instance, pkg);
        dataDir = dataDirOf(*instance, pkg);
        wasForeground = instance->foregroundPackage == pkg;
    }
    if (!fileExists(entryPath)) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "uninstall";
        instance->lastPackageOutcome = "not_found";
        instance->lastPackageMessage = "package not installed: " + pkg;
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }
    bool ok = removeRecursivePath(appDir);
    ok = removeRecursivePath(dataDir) && ok;
    if (unlink(entryPath.c_str()) != 0 && errno != ENOENT) ok = false;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        if (wasForeground) clearForegroundLocked(*instance);
        persistAggregateIndex(*instance, formatTimestampUtc(epochMillisNow()));
        appendPackageInstallLog(*instance, std::string(ok ? "UNINSTALLED" : "UNINSTALL_PARTIAL") + " " + pkg);
        instance->uninstallCount++;
        instance->lastPackageOperation = "uninstall";
        instance->lastPackageName = pkg;
        instance->lastPackageOutcome = ok ? "ok" : "io_error";
        instance->lastPackageMessage = (ok ? "uninstalled " : "partial uninstall ") + pkg;
        instance->lastPackageEpochMillis = epochMillisNow();
    }
    AVM_LOGI("uninstallPackage id=%s package=%s ok=%d", id.c_str(), pkg.c_str(), ok ? 1 : 0);
    return ok ? kOk : kInternalError;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_clearPackageData(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring packageName
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto pkg = ScopedUtfChars(env, packageName).str();
    auto instance = findInstance(id);
    if (!instance) return kInvalidInstance;
    std::string entryPath;
    std::string dataDir;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        entryPath = nativePackageEntryPath(*instance, pkg);
        dataDir = dataDirOf(*instance, pkg);
    }
    if (!fileExists(entryPath)) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "clear_data";
        instance->lastPackageOutcome = "not_found";
        instance->lastPackageMessage = "package not installed: " + pkg;
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }
    bool ok = true;
    DIR* dir = opendir(dataDir.c_str());
    if (dir != nullptr) {
        while (auto* entry = readdir(dir)) {
            const std::string name = entry->d_name;
            if (name == "." || name == "..") continue;
            ok = removeRecursivePath(dataDir + "/" + name) && ok;
        }
        closedir(dir);
    } else if (!mkdirRecursive(dataDir)) {
        ok = false;
    }
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        appendPackageInstallLog(*instance, std::string(ok ? "CLEARED_DATA" : "CLEAR_DATA_PARTIAL") + " " + pkg);
        instance->clearDataCount++;
        instance->lastPackageOperation = "clear_data";
        instance->lastPackageName = pkg;
        instance->lastPackageOutcome = ok ? "ok" : "io_error";
        instance->lastPackageMessage = (ok ? "cleared data for " : "partial clear ") + pkg;
        instance->lastPackageEpochMillis = epochMillisNow();
    }
    AVM_LOGI("clearPackageData id=%s package=%s ok=%d", id.c_str(), pkg.c_str(), ok ? 1 : 0);
    return ok ? kOk : kInternalError;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_launchPackage(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring packageName
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto pkg = ScopedUtfChars(env, packageName).str();
    auto instance = findInstance(id);
    if (!instance) return kInvalidInstance;
    if (pkg.empty()) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "launch";
        instance->lastPackageOutcome = "invalid_package";
        instance->lastPackageMessage = "package name is empty";
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInvalidInstance;
    }
    if (!instance->guestRunning.load()) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "launch";
        instance->lastPackageName = pkg;
        instance->lastPackageOutcome = "guest_not_running";
        instance->lastPackageMessage = "guest runtime is not running";
        instance->lastPackageEpochMillis = epochMillisNow();
        appendPackageInstallLog(*instance, "LAUNCH_FAILED " + pkg + " guest_not_running");
        return kProcessStartFailed;
    }
    if (!awaitRuntimeLaunchServices(instance)) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "launch";
        instance->lastPackageName = pkg;
        instance->lastPackageOutcome = "runtime_services_unavailable";
        instance->lastPackageMessage = "activity/window/input runtime services are unavailable";
        instance->lastPackageEpochMillis = epochMillisNow();
        appendPackageInstallLog(*instance, "LAUNCH_FAILED " + pkg + " runtime_services_unavailable");
        return kInternalError;
    }
    std::string entryPath;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        entryPath = nativePackageEntryPath(*instance, pkg);
    }
    if (!fileExists(entryPath)) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "launch";
        instance->lastPackageOutcome = "not_found";
        instance->lastPackageMessage = "package not installed: " + pkg;
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }
    const auto entryJson = readWholeFile(entryPath);
    const auto launcher = extractJsonString(entryJson, "launcherActivity");
    const auto label = extractJsonString(entryJson, "label");
    const auto installedPath = extractJsonString(entryJson, "installedPath");
    const auto dataPath = extractJsonString(entryJson, "dataPath");
    if (launcher.empty()) {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastPackageOperation = "launch";
        instance->lastPackageOutcome = "not_launchable";
        instance->lastPackageMessage = "no launcher activity for " + pkg;
        instance->lastPackageEpochMillis = epochMillisNow();
        return kInternalError;
    }

    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->activityManagerTransactions++;
        instance->launchAttempts++;
        instance->launchSuccesses++;
        instance->appProcessLaunches++;
        instance->windowAttachCount++;
        instance->foregroundPid = instance->nextGuestPid++;
        instance->foregroundPackage = pkg;
        instance->foregroundActivity = launcher;
        instance->foregroundLabel = label.empty() ? pkg : label;
        instance->foregroundInstalledPath = installedPath;
        instance->foregroundDataPath = dataPath;
        instance->foregroundAppProcessRunning = true;
        instance->foregroundWindowAttached = true;
        instance->foregroundLaunchMode = "runtime_compatible_activity";
        instance->foregroundLastTouchX = -1;
        instance->foregroundLastTouchY = -1;
        instance->foregroundLastKeyCode = -1;
        instance->foregroundTouchEvents = 0;
        instance->foregroundKeyEvents = 0;
        renderForegroundLocked(*instance);
        instance->lastPackageOperation = "launch";
        instance->lastPackageName = pkg;
        instance->lastPackageOutcome = "ok";
        instance->lastPackageMessage =
            "activity manager started " + pkg + "/" + launcher +
            " pid=" + std::to_string(instance->foregroundPid);
        instance->lastPackageEpochMillis = epochMillisNow();
        appendPackageInstallLog(
            *instance,
            "ACTIVITY_STARTED " + pkg + " " + launcher +
                " pid=" + std::to_string(instance->foregroundPid) +
                " mode=" + instance->foregroundLaunchMode
        );
    }
    appendInstanceLog(
        instance,
        "runtime activity launch package=" + pkg +
            " activity=" + launcher
    );
    AVM_LOGI("launchPackage id=%s package=%s activity=%s", id.c_str(), pkg.c_str(), launcher.c_str());
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_stopPackage(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring packageName
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto pkg = ScopedUtfChars(env, packageName).str();
    auto instance = findInstance(id);
    if (!instance) return kInvalidInstance;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->stopAttempts++;
        const bool wasForeground = !instance->foregroundPackage.empty() &&
            (pkg.empty() || pkg == instance->foregroundPackage);
        if (wasForeground) {
            const auto stopped = instance->foregroundPackage;
            clearForegroundLocked(*instance);
            instance->lastPackageOperation = "stop";
            instance->lastPackageName = stopped;
            instance->lastPackageOutcome = "ok";
            instance->lastPackageMessage = "stopped " + stopped;
            appendPackageInstallLog(*instance, "STOPPED " + stopped);
        } else {
            instance->lastPackageOperation = "stop";
            instance->lastPackageName = pkg;
            instance->lastPackageOutcome = "not_foreground";
            instance->lastPackageMessage = pkg.empty() ? "no foreground package" :
                pkg + " is not foreground";
        }
        instance->lastPackageEpochMillis = epochMillisNow();
    }
    AVM_LOGI("stopPackage id=%s package=%s", id.c_str(), pkg.c_str());
    return kOk;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getPackageOperationStatus(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) return env->NewStringUTF("{}");
    std::string payload;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        payload = packageOperationStatusJson(*instance);
    }
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_publishBridgeResult(
    JNIEnv* env,
    jclass,
    jstring instanceId,
    jstring bridge,
    jstring operation,
    jstring result,
    jstring reason,
    jstring payloadJson
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    const auto bridgeName = ScopedUtfChars(env, bridge).str();
    const auto op = ScopedUtfChars(env, operation).str();
    const auto resultText = ScopedUtfChars(env, result).str();
    const auto reasonText = ScopedUtfChars(env, reason).str();
    (void) ScopedUtfChars(env, payloadJson).str(); // payload is intentionally not stored

    auto instance = findInstance(id);
    if (!instance) {
        std::ostringstream err;
        err << "{\"result\":\"unsupported\",\"reason\":\"unknown_instance\","
            << "\"requestCount\":0}";
        return env->NewStringUTF(err.str().c_str());
    }

    std::string snapshot;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        instance->lastBridge = bridgeName;
        instance->lastBridgeOperation = op;
        instance->lastBridgeResult = resultText;
        instance->lastBridgeReason = reasonText;
        instance->bridgeRequestCount++;
        if (resultText == "allowed") {
            instance->bridgeAllowedCount++;
        } else if (resultText == "denied") {
            instance->bridgeDeniedCount++;
        } else if (resultText == "unavailable") {
            instance->bridgeUnavailableCount++;
        } else if (resultText == "unsupported") {
            instance->bridgeUnsupportedCount++;
        }
        snapshot = bridgeRuntimeStatusJson(*instance);
    }
    appendInstanceLog(
        instance,
        "bridge dispatch bridge=" + bridgeName + " op=" + op +
            " result=" + resultText + " reason=" + reasonText
    );
    return env->NewStringUTF(snapshot.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_VmNativeBridge_getBridgeRuntimeStatus(
    JNIEnv* env,
    jclass,
    jstring instanceId
) {
    const auto id = ScopedUtfChars(env, instanceId).str();
    auto instance = findInstance(id);
    if (!instance) return env->NewStringUTF("{}");
    std::string snapshot;
    {
        std::lock_guard<std::mutex> guard(instance->lock);
        snapshot = bridgeRuntimeStatusJson(*instance);
    }
    return env->NewStringUTF(snapshot.c_str());
}
