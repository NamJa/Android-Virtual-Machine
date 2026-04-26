package dev.jongwoo.androidvm.storage

import org.json.JSONObject

data class RomImageManifest(
    val name: String,
    val guestVersion: String,
    val guestArch: String,
    val format: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val sha256: String,
    val createdAt: String,
    val minHostSdk: Int,
) {
    val archiveFileName: String
        get() = "$name.$format"

    val checksumFileName: String
        get() = "$name.sha256"

    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("guestVersion", guestVersion)
        .put("guestArch", guestArch)
        .put("format", format)
        .put("compressedSize", compressedSize)
        .put("uncompressedSize", uncompressedSize)
        .put("sha256", sha256)
        .put("createdAt", createdAt)
        .put("minHostSdk", minHostSdk)
        .toString(2)

    companion object {
        fun fromJson(json: String): RomImageManifest {
            val obj = JSONObject(json)
            return RomImageManifest(
                name = obj.getString("name"),
                guestVersion = obj.getString("guestVersion"),
                guestArch = obj.getString("guestArch"),
                format = obj.getString("format"),
                compressedSize = obj.optLong("compressedSize", 0L),
                uncompressedSize = obj.optLong("uncompressedSize", 0L),
                sha256 = obj.optString("sha256"),
                createdAt = obj.optString("createdAt"),
                minHostSdk = obj.optInt("minHostSdk", 26),
            )
        }
    }
}

data class RomImageCandidate(
    val manifest: RomImageManifest,
    val manifestAssetPath: String,
    val archiveAssetPath: String,
    val checksumAssetPath: String,
    val archiveExists: Boolean,
    val checksumExists: Boolean,
)

data class RomVerification(
    val candidate: RomImageCandidate,
    val status: RomVerificationStatus,
    val actualSha256: String? = null,
    val expectedSha256: String? = null,
    val message: String,
) {
    val ok: Boolean
        get() = status == RomVerificationStatus.OK
}

enum class RomVerificationStatus {
    OK,
    MISSING_ARCHIVE,
    MISSING_CHECKSUM,
    CHECKSUM_MISMATCH,
    HOST_SDK_TOO_LOW,
    UNSUPPORTED_FORMAT,
    INVALID_MANIFEST,
}

data class RomPipelineSnapshot(
    val instanceId: String,
    val candidates: List<RomImageCandidate>,
    val installedManifest: RomImageManifest?,
    val health: RootfsHealthResult,
) {
    val isInstalled: Boolean
        get() = installedManifest != null && health.ok
}

data class RomInstallResult(
    val status: RomInstallStatus,
    val message: String,
    val manifest: RomImageManifest? = null,
    val verification: RomVerification? = null,
    val health: RootfsHealthResult? = null,
) {
    val ok: Boolean
        get() = status == RomInstallStatus.INSTALLED
}

enum class RomInstallStatus {
    INSTALLED,
    NO_CANDIDATE,
    VERIFICATION_FAILED,
    UNSUPPORTED_FORMAT,
    HEALTH_CHECK_FAILED,
    IO_ERROR,
}

data class RomInstallProgress(
    val phase: RomInstallPhase,
    val message: String,
)

enum class RomInstallPhase {
    DISCOVER,
    VERIFY,
    EXTRACT,
    HEALTH_CHECK,
    COMMIT,
    DONE,
}
