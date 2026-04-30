package dev.jongwoo.androidvm.bridge

enum class BridgeSupport {
    SUPPORTED,
    UNSUPPORTED_MVP,
}

object Stage7BridgeScope {
    /**
     * Phase D ships the CAMERA / MICROPHONE bridges, so both flip to SUPPORTED here. The default
     * policy in [DefaultBridgePolicies] still ships them as UNSUPPORTED + disabled, so they stay
     * dark until the user explicitly enables them; the broker only requests host permissions on
     * actual use.
     */
    val support: Map<BridgeType, BridgeSupport> = mapOf(
        BridgeType.CLIPBOARD to BridgeSupport.SUPPORTED,
        BridgeType.LOCATION to BridgeSupport.SUPPORTED,
        BridgeType.CAMERA to BridgeSupport.SUPPORTED,
        BridgeType.MICROPHONE to BridgeSupport.SUPPORTED,
        BridgeType.AUDIO_OUTPUT to BridgeSupport.SUPPORTED,
        BridgeType.NETWORK to BridgeSupport.SUPPORTED,
        BridgeType.DEVICE_PROFILE to BridgeSupport.SUPPORTED,
        BridgeType.VIBRATION to BridgeSupport.SUPPORTED,
    )

    val forbiddenHostIdentityFields: Set<String> = setOf(
        "imei",
        "meid",
        "phoneNumber",
        "simSerialNumber",
        "advertisingId",
        "hostInstalledPackages",
    )

    val forbiddenManifestPermissions: List<String> = listOf(
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.WRITE_SETTINGS",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.SYSTEM_OVERLAY_WINDOW",
        "android.permission.AD_ID",
        "com.google.android.gms.permission.AD_ID",
    )

    fun isSupported(type: BridgeType): Boolean = support[type] == BridgeSupport.SUPPORTED
}
