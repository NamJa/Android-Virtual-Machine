package dev.jongwoo.androidvm.vm

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI probes for Phase C's device-required gates. The Kotlin diagnostics still keep a pure JVM
 * oracle for unit tests, but the debug receiver must only pass C.1-C.6 when these native probes
 * prove the runtime paths are wired on device.
 */
object PhaseCNativeBridge {
    private var loaded: Boolean = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("avm_host")
            loaded = true
        }
    }

    fun binderProbe(): PhaseCProbe {
        ensureLoaded()
        return PhaseCProbe.fromJson(nativeBinderProbe())
    }

    fun ashmemProbe(): PhaseCProbe {
        ensureLoaded()
        return PhaseCProbe.fromJson(nativeAshmemProbe())
    }

    fun propertyProbe(instanceId: String): PhaseCProbe {
        ensureLoaded()
        return PhaseCProbe.fromJson(nativePropertyProbe(instanceId))
    }

    fun bootProbe(instanceId: String): StagePhaseCBootProbe {
        ensureLoaded()
        val o = runCatching { JSONObject(nativeBootProbe(instanceId)) }.getOrNull()
            ?: return StagePhaseCBootProbe(reason = "boot_probe_json_unparseable")
        val services = o.optJSONArray("registered_services")?.toStringList().orEmpty()
        return StagePhaseCBootProbe(
            zygoteAccepting = o.optBoolean("zygote_accepting", false),
            libsLoaded = o.optInt("libs_loaded", 0),
            bootCompleted = o.optBoolean("boot_completed", false),
            registeredServices = services,
            firstFrameDelivered = o.optBoolean("first_frame_delivered", false),
            firstFrameMillis = o.optLong("first_frame_ms", -1L),
            reason = o.optString("reason", ""),
        )
    }

    @JvmStatic external fun nativeBinderProbe(): String
    @JvmStatic external fun nativeAshmemProbe(): String
    @JvmStatic external fun nativePropertyProbe(instanceId: String): String
    @JvmStatic external fun nativeBootProbe(instanceId: String): String

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotEmpty() } }
}

data class PhaseCProbe(
    val passed: Boolean,
    val reason: String = "",
) {
    companion object {
        fun fromJson(json: String): PhaseCProbe {
            val o = runCatching { JSONObject(json) }.getOrNull()
                ?: return PhaseCProbe(false, "probe_json_unparseable")
            return PhaseCProbe(
                passed = o.optBoolean("passed", false),
                reason = o.optString("reason", ""),
            )
        }
    }
}
