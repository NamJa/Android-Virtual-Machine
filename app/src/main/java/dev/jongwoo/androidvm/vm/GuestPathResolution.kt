package dev.jongwoo.androidvm.vm

import org.json.JSONObject

data class GuestPathResolution(
    val status: GuestPathStatus,
    val guestPath: String,
    val hostPath: String,
    val writable: Boolean,
    val virtualNode: Boolean,
) {
    val ok: Boolean
        get() = status == GuestPathStatus.OK

    companion object {
        fun fromJson(json: String): GuestPathResolution {
            val value = JSONObject(json)
            return GuestPathResolution(
                status = GuestPathStatus.fromWireName(value.optString("status")),
                guestPath = value.optString("guestPath"),
                hostPath = value.optString("hostPath"),
                writable = value.optBoolean("writable", false),
                virtualNode = value.optBoolean("virtualNode", false),
            )
        }
    }
}

enum class GuestPathStatus(val wireName: String) {
    OK("OK"),
    INVALID_INSTANCE("INVALID_INSTANCE"),
    INVALID_PATH("INVALID_PATH"),
    PATH_TRAVERSAL("PATH_TRAVERSAL"),
    UNKNOWN_MOUNT("UNKNOWN_MOUNT"),
    READ_ONLY("READ_ONLY"),
    CONFIG_MISSING("CONFIG_MISSING"),
    ;

    companion object {
        fun fromWireName(value: String): GuestPathStatus =
            entries.firstOrNull { it.wireName == value } ?: INVALID_PATH
    }
}
