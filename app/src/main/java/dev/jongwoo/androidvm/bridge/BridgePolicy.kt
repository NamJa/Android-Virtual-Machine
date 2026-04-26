package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

enum class BridgeKind {
    AUDIO_OUTPUT,
    CLIPBOARD,
    CONTACTS,
    FILES,
    LOCATION,
    MICROPHONE,
    VIBRATION,
}

data class BridgePolicy(
    val audioOutput: Boolean = true,
    val vibration: Boolean = true,
    val clipboard: Boolean = false,
    val contacts: Boolean = false,
    val files: Boolean = false,
    val location: Boolean = false,
    val microphone: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("audioOutput", audioOutput)
        .put("vibration", vibration)
        .put("clipboard", clipboard)
        .put("contacts", contacts)
        .put("files", files)
        .put("location", location)
        .put("microphone", microphone)
}
