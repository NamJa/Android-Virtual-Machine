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
    /** Phase E.9: monotonically increasing patch level. Higher = newer. */
    val patchLevel: Int = 0,
    /** Phase E.9: detached signature over the canonical manifest body, hex-encoded. */
    val signature: String? = null,
    /** Phase E.9: public-key identifier (e.g. fingerprint suffix) used for signature verification. */
    val publicKeyId: String? = null,
) {
    val archiveFileName: String
        get() = "$name.$format"

    val checksumFileName: String
        get() = "$name.sha256"

    /**
     * Canonical bytes used for signature computation. Must NOT include the signature/publicKeyId
     * fields — those wrap the canonical body.
     */
    fun canonicalSigningBody(): String = JSONObject()
        .put("name", name)
        .put("guestVersion", guestVersion)
        .put("guestArch", guestArch)
        .put("format", format)
        .put("compressedSize", compressedSize)
        .put("uncompressedSize", uncompressedSize)
        .put("sha256", sha256)
        .put("createdAt", createdAt)
        .put("minHostSdk", minHostSdk)
        .put("patchLevel", patchLevel)
        .toString()

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
        .put("patchLevel", patchLevel)
        .put("signature", signature ?: JSONObject.NULL)
        .put("publicKeyId", publicKeyId ?: JSONObject.NULL)
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
                patchLevel = obj.optInt("patchLevel", 0),
                signature = obj.optString("signature").takeIf {
                    it.isNotBlank() && !obj.isNull("signature")
                },
                publicKeyId = obj.optString("publicKeyId").takeIf {
                    it.isNotBlank() && !obj.isNull("publicKeyId")
                },
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
    val imageState: RomImageState,
) {
    val isInstalled: Boolean
        get() = imageState == RomImageState.INSTALLED

    val needsRepair: Boolean
        get() = imageState == RomImageState.DAMAGED ||
            imageState == RomImageState.VERSION_MISMATCH ||
            imageState == RomImageState.ASSET_MISSING

    val repairCandidate: RomImageCandidate?
        get() = installedManifest
            ?.let { installed -> candidates.firstOrNull { it.manifest.name == installed.name } }
            ?: candidates.firstOrNull()
}

enum class RomImageState {
    NOT_INSTALLED,
    INSTALLED,
    DAMAGED,
    VERSION_MISMATCH,
    ASSET_MISSING,
}

fun resolveRomImageState(
    installedManifest: RomImageManifest?,
    candidates: List<RomImageCandidate>,
    health: RootfsHealthResult,
): RomImageState {
    if (installedManifest == null) {
        return RomImageState.NOT_INSTALLED
    }
    val matchingCandidate = candidates.firstOrNull { it.manifest.name == installedManifest.name }
        ?: return RomImageState.ASSET_MISSING
    if (matchingCandidate.manifest != installedManifest) {
        return RomImageState.VERSION_MISMATCH
    }
    if (!health.ok) {
        return RomImageState.DAMAGED
    }
    return RomImageState.INSTALLED
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
    ALREADY_HEALTHY,
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
