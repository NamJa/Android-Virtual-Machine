package dev.jongwoo.androidvm.storage

import android.content.Context
import android.os.Build
import dev.jongwoo.androidvm.vm.VmConfig
import java.io.File

class RomInstaller(private val context: Context) {
    private val paths = PathLayout(context)
    private val healthCheck = RootfsHealthCheck()
    private val archiveReader = RomArchiveReader(context)

    fun snapshot(instanceId: String = VmConfig.DEFAULT_INSTANCE_ID): RomPipelineSnapshot {
        val instancePaths = paths.ensureInstance(instanceId)
        val installedManifest = readInstalledManifest(instancePaths)
        val candidates = bundledCandidates()
        val health = healthCheck.check(instancePaths)
        return RomPipelineSnapshot(
            instanceId = instanceId,
            candidates = candidates,
            installedManifest = installedManifest,
            health = health,
            imageState = resolveRomImageState(installedManifest, candidates, health),
        )
    }

    fun bundledCandidates(): List<RomImageCandidate> {
        val manifestNames = runCatching {
            context.assets.list(GUEST_ASSET_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
            .filter { it.endsWith(".manifest.json") }
            .sorted()

        return manifestNames.mapNotNull { manifestName ->
            val manifestPath = "$GUEST_ASSET_DIR/$manifestName"
            runCatching {
                val manifest = RomImageManifest.fromJson(AssetVerifier.assetText(context, manifestPath))
                val archivePath = "$GUEST_ASSET_DIR/${manifest.archiveFileName}"
                val checksumPath = "$GUEST_ASSET_DIR/${manifest.checksumFileName}"
                RomImageCandidate(
                    manifest = manifest,
                    manifestAssetPath = manifestPath,
                    archiveAssetPath = archivePath,
                    checksumAssetPath = checksumPath,
                    archiveExists = AssetVerifier.assetExists(context, archivePath),
                    checksumExists = AssetVerifier.assetExists(context, checksumPath),
                )
            }.getOrNull()
        }
    }

    fun verify(candidate: RomImageCandidate): RomVerification {
        if (Build.VERSION.SDK_INT < candidate.manifest.minHostSdk) {
            return RomVerification(
                candidate = candidate,
                status = RomVerificationStatus.HOST_SDK_TOO_LOW,
                message = "Host SDK ${Build.VERSION.SDK_INT} is lower than ${candidate.manifest.minHostSdk}",
            )
        }
        if (!candidate.archiveExists) {
            return RomVerification(
                candidate = candidate,
                status = RomVerificationStatus.MISSING_ARCHIVE,
                message = "Missing archive asset: ${candidate.archiveAssetPath}",
            )
        }
        if (!candidate.checksumExists) {
            return RomVerification(
                candidate = candidate,
                status = RomVerificationStatus.MISSING_CHECKSUM,
                message = "Missing checksum asset: ${candidate.checksumAssetPath}",
            )
        }
        if (candidate.manifest.format !in supportedFormats) {
            return RomVerification(
                candidate = candidate,
                status = RomVerificationStatus.UNSUPPORTED_FORMAT,
                message = "Unsupported image format: ${candidate.manifest.format}",
            )
        }

        val expected = expectedChecksum(candidate)
        val actual = AssetVerifier.sha256Asset(context, candidate.archiveAssetPath)
        if (!expected.equals(actual, ignoreCase = true)) {
            return RomVerification(
                candidate = candidate,
                status = RomVerificationStatus.CHECKSUM_MISMATCH,
                actualSha256 = actual,
                expectedSha256 = expected,
                message = "Checksum mismatch for ${candidate.archiveAssetPath}",
            )
        }
        return RomVerification(
            candidate = candidate,
            status = RomVerificationStatus.OK,
            actualSha256 = actual,
            expectedSha256 = expected,
            message = "Checksum verified",
        )
    }

    fun installDefault(
        instanceId: String = VmConfig.DEFAULT_INSTANCE_ID,
        onProgress: (RomInstallProgress) -> Unit = {},
    ): RomInstallResult {
        onProgress(RomInstallProgress(RomInstallPhase.DISCOVER, "Scanning bundled guest images"))
        val candidate = bundledCandidates().firstOrNull()
            ?: return RomInstallResult(
                status = RomInstallStatus.NO_CANDIDATE,
                message = "No guest image manifest found in assets/$GUEST_ASSET_DIR",
            )
        return install(candidate, instanceId, onProgress)
    }

    fun install(
        candidate: RomImageCandidate,
        instanceId: String = VmConfig.DEFAULT_INSTANCE_ID,
        onProgress: (RomInstallProgress) -> Unit = {},
    ): RomInstallResult {
        var stagingRootRef: File? = null
        return runCatching {
            onProgress(RomInstallProgress(RomInstallPhase.VERIFY, "Verifying ${candidate.manifest.name}"))
            val verification = verify(candidate)
            if (!verification.ok) {
                return RomInstallResult(
                    status = RomInstallStatus.VERIFICATION_FAILED,
                    message = verification.message,
                    manifest = candidate.manifest,
                    verification = verification,
                )
            }

            val instancePaths = paths.ensureInstance(instanceId)
            val stagingRoot = File(instancePaths.stagingDir, "install-${System.currentTimeMillis()}")
            stagingRootRef = stagingRoot
            val stagingRootfs = File(stagingRoot, "rootfs")
            stagingRoot.deleteRecursively()
            stagingRootfs.mkdirs()

            onProgress(RomInstallProgress(RomInstallPhase.EXTRACT, "Extracting ${candidate.manifest.archiveFileName}"))
            when (val extracted = archiveReader.extract(candidate, stagingRootfs, onProgress)) {
                RomArchiveExtractionResult.Extracted -> Unit
                is RomArchiveExtractionResult.Unsupported -> {
                    stagingRoot.deleteRecursively()
                    return RomInstallResult(
                        status = RomInstallStatus.UNSUPPORTED_FORMAT,
                        message = extracted.reason,
                        manifest = candidate.manifest,
                        verification = verification,
                    )
                }
                is RomArchiveExtractionResult.Failed -> {
                    stagingRoot.deleteRecursively()
                    return RomInstallResult(
                        status = RomInstallStatus.IO_ERROR,
                        message = extracted.reason,
                        manifest = candidate.manifest,
                        verification = verification,
                    )
                }
            }

            onProgress(RomInstallProgress(RomInstallPhase.HEALTH_CHECK, "Checking extracted rootfs"))
            val stagedHealth = healthCheck.check(stagingRootfs, null)
            if (!stagedHealth.copy(markerMissing = false).ok) {
                stagingRoot.deleteRecursively()
                return RomInstallResult(
                    status = RomInstallStatus.HEALTH_CHECK_FAILED,
                    message = stagedHealth.summary,
                    manifest = candidate.manifest,
                    verification = verification,
                    health = stagedHealth,
                )
            }

            onProgress(RomInstallProgress(RomInstallPhase.COMMIT, "Committing rootfs"))
            commitRootfs(stagingRootfs, instancePaths.rootfsDir)
            instancePaths.imageManifestFile.writeText(candidate.manifest.toJson())
            InstanceStore(context).saveConfig(VmConfig.default(instancePaths), instancePaths)
            stagingRoot.deleteRecursively()

            val committedHealth = healthCheck.check(instancePaths)
            onProgress(RomInstallProgress(RomInstallPhase.DONE, "Installed ${candidate.manifest.name}"))
            RomInstallResult(
                status = RomInstallStatus.INSTALLED,
                message = "Installed ${candidate.manifest.name}",
                manifest = candidate.manifest,
                verification = verification,
                health = committedHealth,
            )
        }.getOrElse { error ->
            stagingRootRef?.deleteRecursively()
            RomInstallResult(
                status = RomInstallStatus.IO_ERROR,
                message = error.message ?: error::class.java.simpleName,
                manifest = candidate.manifest,
            )
        }
    }

    fun repair(
        instanceId: String = VmConfig.DEFAULT_INSTANCE_ID,
        onProgress: (RomInstallProgress) -> Unit = {},
    ): RomInstallResult {
        val snapshot = snapshot(instanceId)
        if (snapshot.isInstalled) {
            return RomInstallResult(
                status = RomInstallStatus.ALREADY_HEALTHY,
                message = "Rootfs is already installed and healthy",
                manifest = snapshot.installedManifest,
                health = snapshot.health,
            )
        }

        val candidate = snapshot.repairCandidate
            ?: return RomInstallResult(
                status = RomInstallStatus.NO_CANDIDATE,
                message = "No repair candidate found in assets/$GUEST_ASSET_DIR",
            )

        val instancePaths = paths.ensureInstance(instanceId)
        onProgress(RomInstallProgress(RomInstallPhase.DISCOVER, "Repairing ${candidate.manifest.name}"))
        instancePaths.rootfsDir.deleteRecursively()
        instancePaths.imageManifestFile.delete()
        clearStaging(instancePaths)
        return install(candidate, instanceId, onProgress)
    }

    private fun readInstalledManifest(instancePaths: InstancePaths): RomImageManifest? {
        if (!instancePaths.imageManifestFile.exists()) return null
        return runCatching {
            RomImageManifest.fromJson(instancePaths.imageManifestFile.readText())
        }.getOrNull()
    }

    private fun expectedChecksum(candidate: RomImageCandidate): String {
        val checksumText = AssetVerifier.assetText(context, candidate.checksumAssetPath)
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
        return checksumText.ifBlank { candidate.manifest.sha256 }
    }

    private fun commitRootfs(stagedRootfs: File, destinationRootfs: File) {
        destinationRootfs.deleteRecursively()
        destinationRootfs.parentFile?.mkdirs()
        if (!stagedRootfs.renameTo(destinationRootfs)) {
            copyDirectory(stagedRootfs, destinationRootfs)
            stagedRootfs.deleteRecursively()
        }
    }

    private fun clearStaging(instancePaths: InstancePaths) {
        instancePaths.stagingDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        instancePaths.stagingDir.mkdirs()
    }

    private fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                copyDirectory(child, File(destination, child.name))
            }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }

    companion object {
        private const val GUEST_ASSET_DIR = "guest"
        private val supportedFormats = setOf("zip", "tar.zst")
    }
}
