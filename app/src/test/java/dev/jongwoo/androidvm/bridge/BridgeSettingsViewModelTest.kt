package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSettingsViewModelTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun initialStateReflectsDefaultPoliciesAndEmptyAuditLog() {
        val (vm, _, _) = newVm()

        val state = vm.state.value

        assertEquals("vm1", state.instanceId)
        assertEquals(DefaultBridgePolicies.all, state.policies)
        assertEquals(emptyList<BridgeAuditEntry>(), state.auditEntries)
    }

    @Test
    fun setPolicyPersistsAndAppendsAuditEntry() {
        val (vm, store, audit) = newVm()

        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.LOCATION, BridgeMode.LOCATION_FIXED))

        val saved = store.load().getValue(BridgeType.LOCATION)
        assertEquals(BridgeMode.LOCATION_FIXED, saved.mode)
        assertTrue(saved.enabled)
        val entry = audit.read().single()
        assertEquals("policy_change", entry.operation)
        assertTrue(entry.reason.contains("location_fixed"))
    }

    @Test
    fun setPolicyOnUnsupportedBridgeIsIgnored() {
        val (vm, store, audit) = newVm()

        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.CAMERA, BridgeMode.ENABLED))

        val saved = store.load().getValue(BridgeType.CAMERA)
        assertEquals(BridgeMode.UNSUPPORTED, saved.mode)
        assertFalse(saved.enabled)
        assertTrue(audit.read().isEmpty())
    }

    @Test
    fun toggleEnabledOffSetsModeToOff() {
        val (vm, store, _) = newVm()
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.LOCATION, BridgeMode.LOCATION_FIXED))

        vm.onAction(BridgeSettingsAction.ToggleEnabled(BridgeType.LOCATION, false))

        val saved = store.load().getValue(BridgeType.LOCATION)
        assertEquals(BridgeMode.OFF, saved.mode)
        assertFalse(saved.enabled)
    }

    @Test
    fun toggleEnabledOnReusesPriorModeWhenAvailable() {
        val (vm, store, _) = newVm()
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.LOCATION, BridgeMode.LOCATION_HOST_REAL))
        vm.onAction(BridgeSettingsAction.ToggleEnabled(BridgeType.LOCATION, false))

        vm.onAction(BridgeSettingsAction.ToggleEnabled(BridgeType.LOCATION, true))

        val saved = store.load().getValue(BridgeType.LOCATION)
        assertEquals(true, saved.enabled)
        assertEquals(BridgeMode.ENABLED, saved.mode)
    }

    @Test
    fun resetPoliciesRestoresDefaultsAndLogsChange() {
        val (vm, store, audit) = newVm()
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.NETWORK, BridgeMode.OFF))
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.CLIPBOARD, BridgeMode.CLIPBOARD_BIDIRECTIONAL))

        vm.onAction(BridgeSettingsAction.ResetPolicies)

        assertEquals(DefaultBridgePolicies.all, store.load())
        assertEquals(BridgeType.entries.size + 2, audit.read().size)
    }

    @Test
    fun clearAuditLogEmptiesEntries() {
        val (vm, _, audit) = newVm()
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.NETWORK, BridgeMode.OFF))
        assertFalse(audit.read().isEmpty())

        vm.onAction(BridgeSettingsAction.ClearAuditLog)

        assertEquals(emptyList<BridgeAuditEntry>(), audit.read())
        assertEquals(0, vm.state.value.auditEntries.size)
    }

    @Test
    fun availableModesForCameraOnlyExposesUnsupported() {
        val (vm, _, _) = newVm()
        assertEquals(listOf(BridgeMode.UNSUPPORTED), vm.availableModesFor(BridgeType.CAMERA))
        assertEquals(listOf(BridgeMode.UNSUPPORTED), vm.availableModesFor(BridgeType.MICROPHONE))
    }

    @Test
    fun availableModesForClipboardIncludesAllDirections() {
        val (vm, _, _) = newVm()
        val modes = vm.availableModesFor(BridgeType.CLIPBOARD)
        assertTrue(modes.contains(BridgeMode.OFF))
        assertTrue(modes.contains(BridgeMode.CLIPBOARD_HOST_TO_GUEST))
        assertTrue(modes.contains(BridgeMode.CLIPBOARD_GUEST_TO_HOST))
        assertTrue(modes.contains(BridgeMode.CLIPBOARD_BIDIRECTIONAL))
    }

    @Test
    fun availableModesForLocationIncludesFixedAndReal() {
        val (vm, _, _) = newVm()
        val modes = vm.availableModesFor(BridgeType.LOCATION)
        assertEquals(listOf(BridgeMode.OFF, BridgeMode.LOCATION_FIXED, BridgeMode.LOCATION_HOST_REAL), modes)
    }

    @Test
    fun stateRefreshesAfterEachAction() {
        val (vm, _, _) = newVm()
        val initialMode = vm.state.value.policies.getValue(BridgeType.AUDIO_OUTPUT).mode

        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.AUDIO_OUTPUT, BridgeMode.OFF))
        val updated = vm.state.value.policies.getValue(BridgeType.AUDIO_OUTPUT)

        assertEquals(BridgeMode.ENABLED, initialMode)
        assertEquals(BridgeMode.OFF, updated.mode)
        assertEquals(false, updated.enabled)
    }

    @Test
    fun refreshActionRereadsStoreAndLog() {
        val (vm, store, _) = newVm()
        store.update(BridgeType.NETWORK) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        // External mutation: viewmodel state is stale until Refresh.
        assertEquals(BridgeMode.ENABLED, vm.state.value.policies.getValue(BridgeType.NETWORK).mode)

        vm.onAction(BridgeSettingsAction.Refresh)

        assertEquals(BridgeMode.OFF, vm.state.value.policies.getValue(BridgeType.NETWORK).mode)
    }

    @Test
    fun pendingPermissionReasonStartsNull() {
        val (vm, _, _) = newVm()
        assertNotNull(vm.state.value)
        assertEquals(null, vm.state.value.pendingPermissionReason)
    }

    private fun newVm(): Triple<BridgeSettingsViewModel, BridgePolicyStore, BridgeAuditLog> {
        val root = tempDir("bridge-settings")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val vm = BridgeSettingsViewModel(
            instanceId = "vm1",
            policyStore = store,
            auditLog = audit,
        )
        return Triple(vm, store, audit)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}
