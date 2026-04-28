package dev.jongwoo.androidvm.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeSettingsState(
    val instanceId: String,
    val policies: Map<BridgeType, BridgePolicy>,
    val auditEntries: List<BridgeAuditEntry>,
    val pendingPermissionReason: PermissionReason? = null,
)

sealed interface BridgeSettingsAction {
    data class SetPolicy(val bridge: BridgeType, val mode: BridgeMode) : BridgeSettingsAction
    data class ToggleEnabled(val bridge: BridgeType, val enabled: Boolean) : BridgeSettingsAction
    data object ClearAuditLog : BridgeSettingsAction
    data object ResetPolicies : BridgeSettingsAction
    data object Refresh : BridgeSettingsAction
}

/**
 * State holder for the bridge settings screen. Plain class (not androidx ViewModel) so unit tests
 * do not need a Robolectric / lifecycle setup. The screen owns one viewmodel per VM instance.
 */
class BridgeSettingsViewModel(
    val instanceId: String,
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val auditLimit: Int = DEFAULT_AUDIT_LIMIT,
) {
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<BridgeSettingsState> = mutableState.asStateFlow()

    fun onAction(action: BridgeSettingsAction) {
        when (action) {
            is BridgeSettingsAction.SetPolicy -> applyPolicy(action.bridge, action.mode)
            is BridgeSettingsAction.ToggleEnabled -> toggleEnabled(action.bridge, action.enabled)
            BridgeSettingsAction.ClearAuditLog -> clearAuditLog()
            BridgeSettingsAction.ResetPolicies -> resetPolicies()
            BridgeSettingsAction.Refresh -> refresh()
        }
    }

    private fun applyPolicy(bridge: BridgeType, mode: BridgeMode) {
        if (Stage7BridgeScope.support[bridge] == BridgeSupport.UNSUPPORTED_MVP) {
            // Unsupported bridges cannot be flipped on.
            return
        }
        val updated = policyStore.update(bridge) { current ->
            current.copy(
                mode = mode,
                enabled = when (mode) {
                    BridgeMode.OFF, BridgeMode.UNSUPPORTED -> false
                    else -> true
                },
            )
        }
        auditLog.appendPolicyChange(instanceId, bridge, updated.mode, updated.enabled)
        refresh()
    }

    private fun toggleEnabled(bridge: BridgeType, enabled: Boolean) {
        if (Stage7BridgeScope.support[bridge] == BridgeSupport.UNSUPPORTED_MVP) {
            return
        }
        val updated = policyStore.update(bridge) { current ->
            val nextMode = when {
                !enabled -> BridgeMode.OFF
                current.mode == BridgeMode.OFF || current.mode == BridgeMode.UNSUPPORTED -> BridgeMode.ENABLED
                else -> current.mode
            }
            current.copy(enabled = enabled, mode = nextMode)
        }
        auditLog.appendPolicyChange(instanceId, bridge, updated.mode, updated.enabled)
        refresh()
    }

    private fun clearAuditLog() {
        auditLog.clear()
        refresh()
    }

    private fun resetPolicies() {
        policyStore.reset()
        BridgeType.entries.forEach { bridge ->
            val policy = DefaultBridgePolicies.forBridge(bridge)
            auditLog.appendPolicyChange(instanceId, bridge, policy.mode, policy.enabled)
        }
        refresh()
    }

    private fun refresh() {
        mutableState.value = loadState()
    }

    private fun loadState(): BridgeSettingsState = BridgeSettingsState(
        instanceId = instanceId,
        policies = policyStore.load(),
        auditEntries = auditLog.read(limit = auditLimit),
    )

    fun availableModesFor(bridge: BridgeType): List<BridgeMode> = when (bridge) {
        BridgeType.CLIPBOARD -> listOf(
            BridgeMode.OFF,
            BridgeMode.CLIPBOARD_HOST_TO_GUEST,
            BridgeMode.CLIPBOARD_GUEST_TO_HOST,
            BridgeMode.CLIPBOARD_BIDIRECTIONAL,
        )
        BridgeType.LOCATION -> listOf(
            BridgeMode.OFF,
            BridgeMode.LOCATION_FIXED,
            BridgeMode.LOCATION_HOST_REAL,
        )
        BridgeType.CAMERA, BridgeType.MICROPHONE -> listOf(BridgeMode.UNSUPPORTED)
        BridgeType.AUDIO_OUTPUT,
        BridgeType.NETWORK,
        BridgeType.DEVICE_PROFILE,
        BridgeType.VIBRATION,
        -> listOf(BridgeMode.OFF, BridgeMode.ENABLED)
    }

    companion object {
        const val DEFAULT_AUDIT_LIMIT = 50
    }
}
