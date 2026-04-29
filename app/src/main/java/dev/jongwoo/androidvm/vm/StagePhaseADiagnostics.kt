package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.bridge.BridgeAuditLog
import dev.jongwoo.androidvm.bridge.BridgeDecision
import dev.jongwoo.androidvm.bridge.BridgeMode
import dev.jongwoo.androidvm.bridge.BridgePolicyStore
import dev.jongwoo.androidvm.bridge.BridgeType
import dev.jongwoo.androidvm.bridge.Stage7BridgeScope
import dev.jongwoo.androidvm.storage.PathLayout
import java.io.File
import java.util.UUID

/** Per-step + composite verdict for the Phase A final gate. */
data class StagePhaseAResultLine(
    val vmManager: Boolean,
    val multiInstanceReady: Boolean,
    val ipc: Boolean,
    val ci: Boolean,
    val stage4: Boolean,
    val stage5: Boolean,
    val stage6: Boolean,
    val stage7: Boolean,
) {
    val passed: Boolean = vmManager && multiInstanceReady && ipc && ci &&
        stage4 && stage5 && stage6 && stage7

    fun format(): String =
        "STAGE_PHASE_A_RESULT passed=$passed vm_manager=$vmManager " +
            "multi_instance_ready=$multiInstanceReady ipc=$ipc ci=$ci " +
            "stage4=$stage4 stage5=$stage5 stage6=$stage6 stage7=$stage7"
}

/** Per-Stage breakdown the receiver feeds into the Phase A diagnostics. */
data class StagePhaseAStageRegressionResult(
    val stage4: Boolean,
    val stage5: Boolean,
    val stage6: Boolean,
    val stage7: Boolean,
)

/**
 * Pure-JVM harness that bundles the runtime checks for each Phase A step into a single emit
 * stream and a [StagePhaseAResultLine]. The on-device receiver wraps this with a
 * [dev.jongwoo.androidvm.debug.Stage7RegressionRunner]-backed regression probe so the same code
 * is exercised from both unit tests and the broadcast receiver.
 */
class StagePhaseADiagnostics(
    private val workspaceRoot: File,
    private val manifestText: String,
    private val regressionProbe: () -> StagePhaseAStageRegressionResult = {
        StagePhaseAStageRegressionResult(false, false, false, false)
    },
    private val crossProcessStateProbe: () -> Boolean = { false },
    private val emit: (String) -> Unit = {},
) {
    fun run(): StagePhaseAResultLine {
        val vm = check("STAGE_PHASE_A_VM_MANAGER", "persisted=true bound=true") { verifyVmManager() }
        val multi = check(
            "STAGE_PHASE_A_MULTI_INSTANCE_READY",
            "store=isolated audit=isolated",
        ) { verifyMultiInstance() }
        val ipc = check(
            "STAGE_PHASE_A_IPC",
            "contract=ok cross_process_state=consistent",
        ) { verifyIpc() }
        val ci = check("STAGE_PHASE_A_CI", "manifest_clean=true") { verifyCiMarker() }
        val regression = regressionProbe()
        val stage4 = report("STAGE_PHASE_A_STAGE4", regression.stage4)
        val stage5 = report("STAGE_PHASE_A_STAGE5", regression.stage5)
        val stage6 = report("STAGE_PHASE_A_STAGE6", regression.stage6)
        val stage7 = report("STAGE_PHASE_A_STAGE7", regression.stage7)
        val line = StagePhaseAResultLine(vm, multi, ipc, ci, stage4, stage5, stage6, stage7)
        emit(line.format())
        return line
    }

    private inline fun check(label: String, extra: String? = null, block: () -> Boolean): Boolean {
        val passed = runCatching(block).getOrElse { false }
        val suffix = if (passed && extra != null) " $extra" else ""
        emit("$label passed=$passed$suffix")
        return passed
    }

    private fun report(label: String, passed: Boolean): Boolean {
        emit("$label passed=$passed")
        return passed
    }

    private fun fresh(prefix: String): File {
        val dir = File(workspaceRoot, "${prefix}-${UUID.randomUUID()}").also { it.mkdirs() }
        return dir
    }

    private fun verifyVmManager(): Boolean {
        val dir = fresh("vm-manager")
        val storeFile = File(dir, VmRuntimeStateStore.FILE_NAME)
        val store = VmRuntimeStateStore(storeFile)
        val sample = mapOf("vm1" to VmState.RUNNING, "vm-phaseA" to VmState.STOPPING)
        store.save(sample)
        val reloaded = VmRuntimeStateStore(storeFile).load()
        if (reloaded != sample) return false
        // Corrupt input must recover without throwing.
        storeFile.writeText("{not json")
        val recovered = VmRuntimeStateStore(storeFile)
        if (recovered.load() != emptyMap<String, VmState>()) return false
        recovered.save(mapOf("vm1" to VmState.RUNNING))
        return VmRuntimeStateStore(storeFile).load() == mapOf("vm1" to VmState.RUNNING)
    }

    private fun verifyMultiInstance(): Boolean {
        val avmRoot = fresh("multi")
        val layout = PathLayout.forRoot(avmRoot)
        val a = layout.ensureInstance("vm1")
        val b = layout.ensureInstance("vm-phaseA")
        BridgePolicyStore(a.root).update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_HOST_TO_GUEST, enabled = true)
        }
        val isolatedPolicy = BridgePolicyStore(b.root).load()
            .getValue(BridgeType.CLIPBOARD).mode == BridgeMode.OFF
        BridgeAuditLog(a.root).appendDecision(
            "vm1",
            BridgeType.AUDIO_OUTPUT,
            "write_pcm",
            BridgeDecision.allowed("ok"),
        )
        val isolatedAudit = BridgeAuditLog(b.root).count() == 0
        val listed = layout.listInstanceIds().toSet() == setOf("vm1", "vm-phaseA")
        val deleted = layout.deleteInstance("vm-phaseA")
        val afterDelete = layout.listInstanceIds() == listOf("vm1")
        return isolatedPolicy && isolatedAudit && listed && deleted && afterDelete
    }

    private fun verifyIpc(): Boolean {
        val codecOk = VmState.entries.all { state ->
            VmIpcCodec.decodeState(VmIpcCodec.encodeState("vm1", state)) == ("vm1" to state)
        }
        val keysOk = VmIpc.KEY_INSTANCE_ID == "instanceId" && VmIpc.KEY_PAYLOAD_JSON == "payloadJson"
        // Distinct, non-zero message codes prevent silent collisions.
        val codes = listOf(
            VmIpc.MSG_REGISTER_REPLY,
            VmIpc.MSG_UNREGISTER_REPLY,
            VmIpc.MSG_STATE_UPDATE,
            VmIpc.MSG_BOOTSTRAP_STATUS,
            VmIpc.MSG_BRIDGE_STATUS,
            VmIpc.MSG_PACKAGE_STATUS,
            VmIpc.MSG_GENERIC_LOG,
        )
        val codesOk = codes.toSet().size == codes.size && codes.all { it != 0 }
        return codecOk && keysOk && codesOk && crossProcessStateProbe()
    }

    private fun verifyCiMarker(): Boolean {
        // The receiver cannot inspect the GitHub workflow file from inside the APK; instead it
        // verifies the same forbidden-permission invariant that CI guards (a denylisted
        // permission would have to be declared in the manifest to slip past the gate).
        if (Stage7BridgeScope.forbiddenManifestPermissions.any { manifestText.contains(it) }) return false
        if (manifestText.contains("android.permission.CAMERA")) return false
        if (manifestText.contains("android.permission.RECORD_AUDIO")) return false
        return true
    }
}
