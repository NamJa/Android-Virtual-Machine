package dev.jongwoo.androidvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jongwoo.androidvm.bridge.BridgeMode
import dev.jongwoo.androidvm.bridge.BridgePolicy
import dev.jongwoo.androidvm.bridge.BridgeSettingsAction
import dev.jongwoo.androidvm.bridge.BridgeSettingsState
import dev.jongwoo.androidvm.bridge.BridgeSettingsViewModel
import dev.jongwoo.androidvm.bridge.BridgeType
import dev.jongwoo.androidvm.bridge.Stage7BridgeScope
import dev.jongwoo.androidvm.bridge.BridgeSupport

@Composable
fun BridgeSettingsScreen(
    viewModel: BridgeSettingsViewModel,
    state: BridgeSettingsState,
) {
    val onAction: (BridgeSettingsAction) -> Unit = viewModel::onAction
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Bridge Privacy (instance ${state.instanceId})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item { SectionLabel("Privacy") }
        item { BridgeRow(BridgeType.CLIPBOARD, state, viewModel, onAction) }
        item { BridgeRow(BridgeType.LOCATION, state, viewModel, onAction) }
        item { BridgeRow(BridgeType.CAMERA, state, viewModel, onAction) }
        item { BridgeRow(BridgeType.MICROPHONE, state, viewModel, onAction) }
        item { BridgeRow(BridgeType.DEVICE_PROFILE, state, viewModel, onAction) }
        item { HorizontalDivider() }
        item { SectionLabel("Network") }
        item { BridgeRow(BridgeType.NETWORK, state, viewModel, onAction) }
        item { HorizontalDivider() }
        item { SectionLabel("Runtime") }
        item { BridgeRow(BridgeType.AUDIO_OUTPUT, state, viewModel, onAction) }
        item { BridgeRow(BridgeType.VIBRATION, state, viewModel, onAction) }
        item {
            OutlinedButton(onClick = { onAction(BridgeSettingsAction.ResetPolicies) }) {
                Text("Reset bridge policies to defaults")
            }
        }
        item { HorizontalDivider() }
        item { SectionLabel("Audit Log") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.auditEntries.size} entries",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onAction(BridgeSettingsAction.ClearAuditLog) }) {
                    Text("Clear")
                }
            }
        }
        items(state.auditEntries) { entry ->
            Text(
                text = "${entry.bridge.name} ${entry.operation} -> ${entry.result.name} (${entry.reason})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun BridgeRow(
    bridge: BridgeType,
    state: BridgeSettingsState,
    viewModel: BridgeSettingsViewModel,
    onAction: (BridgeSettingsAction) -> Unit,
) {
    val policy: BridgePolicy = state.policies.getValue(bridge)
    val unsupported = Stage7BridgeScope.support[bridge] == BridgeSupport.UNSUPPORTED_MVP
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bridge.name.replace('_', ' '),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (unsupported) {
                    Text(
                        text = "Unsupported (Stage 07 MVP)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Switch(
                        checked = policy.enabled,
                        onCheckedChange = { onAction(BridgeSettingsAction.ToggleEnabled(bridge, it)) },
                    )
                }
            }
            if (!unsupported) {
                val modes = viewModel.availableModesFor(bridge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEach { mode ->
                        FilterChip(
                            selected = policy.mode == mode,
                            enabled = policy.enabled || mode == BridgeMode.OFF,
                            onClick = { onAction(BridgeSettingsAction.SetPolicy(bridge, mode)) },
                            label = { Text(mode.name.replace('_', ' ').lowercase()) },
                        )
                    }
                }
            }
            Text(
                text = "mode=${policy.mode.name.lowercase()} enabled=${policy.enabled}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
