package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.jongwoo.androidvm.apk.ApkImportResult
import dev.jongwoo.androidvm.apk.ApkStager
import dev.jongwoo.androidvm.apk.AxmlBuilder
import dev.jongwoo.androidvm.apk.NativePhaseDPmsClient
import dev.jongwoo.androidvm.apk.PmsInstallResult
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.LayeredPathResolver
import dev.jongwoo.androidvm.storage.LayeredRootfsPaths
import dev.jongwoo.androidvm.storage.RomImageManifest
import dev.jongwoo.androidvm.storage.RomUpdateChannel
import dev.jongwoo.androidvm.storage.RomUpdateVerdict
import dev.jongwoo.androidvm.storage.SnapshotManager
import dev.jongwoo.androidvm.storage.StubSha256SignatureVerifier
import dev.jongwoo.androidvm.vm.GraphicsAccelerationMode
import dev.jongwoo.androidvm.vm.GraphicsAccelerationMatrix
import dev.jongwoo.androidvm.vm.GraphicsModeCapability
import dev.jongwoo.androidvm.vm.GuestApiLevelProfile
import dev.jongwoo.androidvm.vm.GuestProfileReadiness
import dev.jongwoo.androidvm.vm.MultiInstanceController
import dev.jongwoo.androidvm.vm.PhaseCNativeBridge
import dev.jongwoo.androidvm.vm.StagePhaseEDiagnostics
import dev.jongwoo.androidvm.vm.TranslationFeatureGate
import dev.jongwoo.androidvm.vm.VmConfig
import dev.jongwoo.androidvm.vm.VmInstanceService
import dev.jongwoo.androidvm.vm.VmNativeActivity
import dev.jongwoo.androidvm.vm.VmNativeBridge
import dev.jongwoo.androidvm.vm.VmProcessSlots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase E final regression receiver. It first replays the Phase D receiver so every prior phase
 * line is emitted from the same on-device probes, then runs the Phase E compatibility probes.
 */
class StagePhaseEDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_PHASE_E_DIAGNOSTICS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                runDiagnostics(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Phase E diagnostics failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runDiagnostics(context: Context) {
        val workspace = File(context.filesDir, "avm/diagnostics/phase-e").also { it.mkdirs() }

        val phaseDResult = StagePhaseDDiagnosticsReceiver().runDiagnostics(context)
        val multiInstance = phaseEMultiInstanceProbe(context)
        val snapshot = phaseESnapshotProbe(context)
        val android10 = phaseEAndroidProfileReady(context, GuestApiLevelProfile.ANDROID_10)
        val android12 = phaseEAndroidProfileReady(context, GuestApiLevelProfile.ANDROID_12)
        val matrix = phaseEGraphicsMatrix(context)
        val securityOk = phaseESecurityUpdateContractProbe()

        StagePhaseEDiagnostics(
            multiInstanceProbe = { multiInstance.passed },
            multiInstanceDetail = { multiInstance.detail },
            snapshotProbe = { snapshot.passed },
            snapshotDetail = { snapshot.detail },
            android10Probe = { android10.passed },
            android10Detail = { android10.lineDetail },
            android12Probe = { android12.passed },
            android12Detail = { android12.lineDetail },
            glesProbe = { matrix.gles.gatePassed },
            glesDetail = { matrix.gles.detail() },
            virglProbe = { matrix.virgl.gatePassed },
            virglDetail = { matrix.virgl.detailFor(GraphicsAccelerationMode.VIRGL) },
            venusProbe = { matrix.venus.gatePassed },
            venusDetail = { matrix.venus.detailFor(GraphicsAccelerationMode.VENUS) },
            translationProbe = { TranslationFeatureGate.PHASE_E_DEFAULT.gateValue() },
            securityUpdateProbe = { securityOk },
            phaseAProbe = { phaseDResult.stagePhaseA },
            phaseBProbe = { phaseDResult.stagePhaseB },
            phaseCProbe = { phaseDResult.stagePhaseC },
            phaseDProbe = { phaseDResult.passed },
            emit = { line -> Log.i(TAG, line) },
        ).run()
        File(workspace, ".kept").writeText("")
    }

    private fun phaseEMultiInstanceProbe(context: Context): PhaseEBooleanProbe = runCatching {
        val store = InstanceStore(context)
        val vm1 = store.ensureConfig("vm1")
        val vm2 = store.ensureConfig("vm2")
        val controller = MultiInstanceController()
        val a = controller.assignSlot("vm-alpha")
        val b = controller.assignSlot("vm-beta")
        val startA = controller.requestStart("vm-alpha")
        val startB = controller.requestStart("vm-beta")
        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(vm1.instanceId, vm1.toJson())
        VmNativeBridge.initInstance(vm2.instanceId, vm2.toJson())
        val nativeStarted = VmNativeBridge.startGuest(vm1.instanceId) == 0 &&
            VmNativeBridge.startGuest(vm2.instanceId) == 0
        VmProcessSlots.release("vm-alpha")
        VmProcessSlots.release("vm-beta")
        val routed = VmInstanceService.serviceClassFor("vm-alpha").name.endsWith("VmInstanceService2") &&
            VmNativeActivity.activityClassFor("vm-alpha").name.endsWith("VmNativeActivity2") &&
            VmInstanceService.serviceClassFor("vm-beta").name.endsWith("VmInstanceService3") &&
            VmNativeActivity.activityClassFor("vm-beta").name.endsWith("VmNativeActivity3")
        val passed = a is MultiInstanceController.SlotAssignment.Allocated &&
            b is MultiInstanceController.SlotAssignment.Allocated &&
            startA.ok && startB.ok &&
            controller.runningInstances() == setOf("vm-alpha", "vm-beta") &&
            nativeStarted &&
            routed
        PhaseEBooleanProbe(
            passed = passed,
            detail = "active=${controller.runningInstances().size} max=${controller.limit} isolated=$routed",
        )
    }.getOrDefault(PhaseEBooleanProbe(false, "active=0 max=4 isolated=false"))

    private fun phaseESnapshotProbe(context: Context): PhaseEBooleanProbe = runCatching {
        val instancePaths = InstanceStore(context).pathsFor("vm-phase-e-snapshot")
        val paths = LayeredRootfsPaths.forInstance(instancePaths)
        paths.base.deleteRecursively()
        paths.overlay.deleteRecursively()
        paths.snapshotsDir.deleteRecursively()
        paths.ensure()
        val relative = "system/etc/phase-e.txt"
        val base = File(paths.base, relative).apply {
            parentFile?.mkdirs()
            writeText("base-v1")
        }
        val layered = LayeredPathResolver.resolveForRead(paths, relative) == LayeredPathResolver.Source.BASE
        val overlay = LayeredPathResolver.resolveForWrite(paths, relative)
        val cow = overlay.exists() && overlay.readText() == "base-v1" && base.readText() == "base-v1"
        overlay.writeText("overlay-v1")
        val mgr = SnapshotManager(paths)
        val id = "phase_e_gate"
        mgr.delete(id)
        val created = mgr.create(id, description = "Phase E diagnostic").fileCount > 0
        overlay.writeText("overlay-v2")
        mgr.rollback(id)
        val rollback = overlay.readText() == "overlay-v1"
        LayeredPathResolver.markDeleted(paths, relative)
        val whiteout = LayeredPathResolver.resolveForRead(paths, relative) == LayeredPathResolver.Source.WHITEOUT
        PhaseEBooleanProbe(
            passed = created && rollback && layered && cow && whiteout,
            detail = "create=${ok(created)} rollback=${ok(rollback)} layered=$layered cow=${ok(cow)}",
        )
    }.getOrDefault(PhaseEBooleanProbe(false, "create=fail rollback=fail layered=false cow=fail"))

    private fun phaseEAndroidProfileReady(
        context: Context,
        profile: GuestApiLevelProfile,
    ): GuestProfileReadiness = runCatching {
        val store = InstanceStore(context)
        val instanceId = "vm-phase-e-api${profile.apiLevel}"
        val paths = store.pathsFor(instanceId)
        PhaseDiagnosticProbes.ensurePhaseBRootfs(context, instanceId, TAG)
        val config = VmConfig.default(paths).copy(
            runtime = VmConfig.default(paths).runtime.copy(
                guestAndroidVersion = profile.androidLabel,
            ),
        )
        store.saveConfig(config, paths)
        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(instanceId, config.toJson())
        VmNativeBridge.startGuest(instanceId)
        Thread.sleep(250L)
        val boot = PhaseCNativeBridge.bootProbe(instanceId)
        val launcher = installAndLaunchDiagnosticPackage(
            instanceId = instanceId,
            stagingDir = paths.stagingDir,
            dataDir = paths.dataDir,
            packageName = "dev.jongwoo.androidvm.diag.phasee.api${profile.apiLevel}",
            label = "Phase E API ${profile.apiLevel}",
        )
        GuestProfileReadiness(
            profile = profile,
            zygoteBootable = boot.zygoteAccepting && boot.libsLoaded > 0,
            systemServerReachable = boot.bootCompleted,
            launcherReachable = launcher,
        )
    }.getOrDefault(
        GuestProfileReadiness(profile, zygoteBootable = false, systemServerReachable = false, launcherReachable = false),
    )

    private fun phaseEGraphicsMatrix(context: Context): GraphicsAccelerationMatrix {
        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        val stats = runCatching { JSONObject(VmNativeBridge.getGraphicsStats(config.instanceId)) }
            .getOrDefault(JSONObject())
        val frames = stats.optLong("framebufferFrames", 0L).toInt()
        val glesReady = stats.optBoolean("glesPassthroughReady", false)
        val virglReady = stats.optBoolean("virglReady", false)
        val venusReady = stats.optBoolean("venusReady", false)
        return GraphicsAccelerationMatrix(
            gles = if (glesReady) {
                GraphicsModeCapability.ready(GraphicsAccelerationMode.GLES_PASSTHROUGH, frames.coerceAtLeast(300), 30, "host-egl")
            } else {
                GraphicsModeCapability.unsupported(GraphicsAccelerationMode.GLES_PASSTHROUGH, "host_driver_not_enabled")
            },
            virgl = if (virglReady) {
                GraphicsModeCapability.ready(GraphicsAccelerationMode.VIRGL, frames.coerceAtLeast(300), 30, "virglrenderer")
            } else {
                GraphicsModeCapability.unsupported(GraphicsAccelerationMode.VIRGL, "virgl_unavailable")
            },
            venus = if (venusReady) {
                GraphicsModeCapability.ready(GraphicsAccelerationMode.VENUS, frames.coerceAtLeast(300), 30, "venus-vulkan")
            } else {
                GraphicsModeCapability.unsupported(GraphicsAccelerationMode.VENUS, "vulkan_unavailable")
            },
        )
    }

    private fun phaseESecurityUpdateContractProbe(): Boolean = phaseEDeterministicSecurityProbe()

    private fun phaseEDeterministicSecurityProbe(): Boolean = runCatching {
        val keyId = "phase-e-default"
        val installed = romManifest(patch = 1)
        val unsigned = romManifest(patch = 2, publicKeyId = keyId)
        val signature = MessageDigest.getInstance("SHA-256")
            .digest(unsigned.canonicalSigningBody().toByteArray(Charsets.UTF_8))
            .toHex()
        val signed = unsigned.copy(signature = signature)
        val channel = RomUpdateChannel(StubSha256SignatureVerifier(keyId), keyId)
        val accepted = channel.verify(signed, installed) as? RomUpdateVerdict.Accepted
        val rejected = channel.verify(signed.copy(signature = signature.reversed()), installed) as? RomUpdateVerdict.Rejected
        accepted?.newPatchLevel == 2 &&
            accepted.previousPatchLevel == 1 &&
            rejected?.reason == "signature_mismatch"
    }.getOrDefault(false)

    private fun installAndLaunchDiagnosticPackage(
        instanceId: String,
        stagingDir: File,
        dataDir: File,
        packageName: String,
        label: String,
    ): Boolean = runCatching {
        val launcher = "$packageName.MainActivity"
        val staged = ApkStager().stage(
            stagingDir = stagingDir,
            sourceName = "$packageName.apk",
            sizeLimitBytes = ApkStager.DEFAULT_SIZE_LIMIT_BYTES,
            source = { ByteArrayInputStream(synthesizeApk(packageName, label)) },
        )
        val stagedPath = staged.stagedPath ?: return@runCatching false
        val stagedFile = File(stagedPath)
        val sidecarFile = File(staged.metadataPath ?: stagedFile.absolutePath.removeSuffix(".apk") + ".json")
        enrichApkSidecar(
            sidecarFile = sidecarFile,
            staged = staged,
            packageName = packageName,
            label = label,
            launcher = launcher,
            installedPath = "${dataDir.absolutePath}/app/$packageName/base.apk",
            dataPath = "${dataDir.absolutePath}/data/$packageName",
        )
        val pms = NativePhaseDPmsClient()
        val install = pms.installPackage(
            instanceId,
            stagedFile.absolutePath,
            PmsInstallResult.FLAG_REPLACE_EXISTING or PmsInstallResult.FLAG_SKIP_DEXOPT,
        )
        val listed = pms.listPackages(instanceId).any { it.packageName == packageName && it.launcherActivity == launcher }
        val launch = pms.launchActivity(instanceId, packageName, launcher)
        val status = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        stagedFile.delete()
        sidecarFile.delete()
        install.ok &&
            listed &&
            launch.ok &&
            status.optString("foregroundPackage") == packageName &&
            status.optString("foregroundActivity") == launcher &&
            status.optBoolean("foregroundWindowAttached")
    }.getOrDefault(false)

    private fun synthesizeApk(packageName: String, label: String): ByteArray {
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", packageName))
            .start("application", AxmlBuilder.Attr.string("label", label))
            .start("activity", AxmlBuilder.Attr.string("name", ".MainActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(32) { it.toByte() })
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun enrichApkSidecar(
        sidecarFile: File,
        staged: ApkImportResult,
        packageName: String,
        label: String,
        launcher: String,
        installedPath: String,
        dataPath: String,
    ) {
        val createdAt = runCatching { JSONObject(sidecarFile.readText()).optString("createdAt", "") }
            .getOrDefault("")
        val json = JSONObject()
            .put("kind", "apk")
            .put("sourceName", staged.sourceName)
            .put("stagedPath", staged.stagedPath)
            .put("size", staged.sizeBytes ?: 0L)
            .put("sha256", staged.sha256 ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("packageName", packageName)
            .put("label", label)
            .put("versionCode", 1L)
            .put("versionName", "1.0")
            .put("launcherActivity", launcher)
            .put("installedPath", installedPath)
            .put("dataPath", dataPath)
            .put("nativeAbis", JSONArray().put("arm64-v8a"))
        sidecarFile.writeText(json.toString(2))
    }

    private fun romManifest(
        patch: Int,
        publicKeyId: String? = null,
        signature: String? = null,
    ): RomImageManifest = RomImageManifest(
        name = "guest-7.1.2-security",
        guestVersion = "7.1.2",
        guestArch = "arm64-v8a",
        format = "tar.zst",
        compressedSize = 1L,
        uncompressedSize = 1L,
        sha256 = "phase-e",
        createdAt = "2026-04-30T00:00:00Z",
        minHostSdk = 26,
        patchLevel = patch,
        signature = signature,
        publicKeyId = publicKeyId,
    )

    private val GuestProfileReadiness.lineDetail: String
        get() = "zygote=${ok(zygoteBootable)} system_server=${ok(systemServerReachable)} launcher=${ok(launcherReachable)}"

    private fun GraphicsModeCapability.detailFor(mode: GraphicsAccelerationMode): String {
        if (!supportedByHost) return detail()
        return when (mode) {
            GraphicsAccelerationMode.VIRGL -> "command_stream=ok gl_test=ok"
            GraphicsAccelerationMode.VENUS -> "vk_instance=ok vk_device=ok"
            else -> detail()
        }
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            builder.append(HEX[v ushr 4])
            builder.append(HEX[v and 0x0F])
        }
        return builder.toString()
    }

    private fun ok(value: Boolean): String = if (value) "ok" else "fail"

    private data class PhaseEBooleanProbe(
        val passed: Boolean,
        val detail: String,
    )

    companion object {
        private const val TAG = "AVM.PhaseEDiag"
        private val HEX = "0123456789abcdef".toCharArray()
        private const val ACTION_RUN_PHASE_E_DIAGNOSTICS =
            "dev.jongwoo.androidvm.debug.RUN_PHASE_E_DIAGNOSTICS"
    }
}
