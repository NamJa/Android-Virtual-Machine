// EP2.2 spike — thin JNI wrapper over the promoted production boot core
// (guest/guest_boot.cpp). Validates, on real arm64 hardware, the Option B
// bootstrap by pointing bootGuestViaLinker() at the device's OWN
// /system/bin/{app_process64,linker64} (present, not bundled). The clean-room
// guest boot runs the SAME core (guest::bootGuestViaLinker) against the user ROM.

#include <jni.h>

#include <cstdio>
#include <string>

#include "guest/guest_boot.h"

namespace {

std::string jescape(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 16);
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += " "; break;
            case '\r': break;
            case '\t': o += ' '; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) o += ' ';
                else o += c;
        }
    }
    return o;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_LinkerBootstrapProbe_nativeProbe(
    JNIEnv* env, jclass, jstring jExecPath, jstring jLinkerPath, jboolean jGateway) {
    const char* ep = env->GetStringUTFChars(jExecPath, nullptr);
    const char* lp = env->GetStringUTFChars(jLinkerPath, nullptr);
    const std::string execPath = ep ? ep : "";
    const std::string linkerPath = lp ? lp : "";
    if (ep) env->ReleaseStringUTFChars(jExecPath, ep);
    if (lp) env->ReleaseStringUTFChars(jLinkerPath, lp);

    const avm::guest::GatewayMode mode =
        jGateway ? avm::guest::GatewayMode::BOOTSTRAP_COMPAT : avm::guest::GatewayMode::NONE;
    // rootfs "/" is transparent for the BOOTSTRAP_COMPAT/NONE probe modes.
    const avm::guest::GuestBootResult r =
        avm::guest::bootGuestViaLinker("/", execPath, linkerPath, mode, /*timeoutMs=*/5000);

    if (!r.ok) {
        const std::string j = "{\"ok\":false,\"reason\":\"" + jescape(r.reason) + "\"}";
        return env->NewStringUTF(j.c_str());
    }

    char head[288];
    snprintf(head, sizeof(head),
             "{\"ok\":true,\"exec_mapped\":%s,\"linker_mapped\":%s,\"gateway\":%s,"
             "\"exec_entry\":\"%p\",\"linker_base\":\"%p\","
             "\"child_signal\":%d,\"child_exit\":%d,\"linker_ran\":%s,\"output\":\"",
             r.execMapped ? "true" : "false", r.linkerMapped ? "true" : "false",
             jGateway ? "true" : "false", r.execEntry, r.linkerBase,
             r.childSignal, r.childExit, r.linkerRan ? "true" : "false");
    std::string j = head;
    j += jescape(r.output);
    j += "\"}";
    return env->NewStringUTF(j.c_str());
}
