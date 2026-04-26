package dev.jongwoo.androidvm.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.storage.RomPipelineSnapshot
import dev.jongwoo.androidvm.ui.theme.AvmAppTheme
import dev.jongwoo.androidvm.vm.VmConfig
import dev.jongwoo.androidvm.vm.VmInstanceService
import dev.jongwoo.androidvm.vm.VmNativeActivity
import dev.jongwoo.androidvm.vm.VmState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = InstanceStore(this).ensureDefaultConfig()

        setContent {
            AvmAppTheme {
                MainScreen(config = config)
            }
        }
    }
}

@Composable
private fun MainScreen(config: VmConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val romInstaller = remember(context) { RomInstaller(context) }
    var state by remember { mutableStateOf(VmState.STOPPED) }
    var romSnapshot by remember { mutableStateOf(romInstaller.snapshot(config.instanceId)) }
    var romMessage by remember { mutableStateOf("ROM pipeline is ready") }
    var installingRom by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Android Virtual Machine",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Instance ${config.instanceId} / Android ${config.runtime.guestAndroidVersion} / ${config.runtime.guestAbi}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Runtime",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                VmInstanceService.start(context)
                                state = VmState.STARTING
                            },
                        ) {
                            Text("Start")
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(context, VmNativeActivity::class.java))
                                state = VmState.RUNNING
                            },
                        ) {
                            Text("Open Display")
                        }
                        OutlinedButton(
                            onClick = {
                                VmInstanceService.stop(context)
                                state = VmState.STOPPING
                            },
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Display",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${config.display.width} x ${config.display.height} / ${config.display.densityDpi} dpi")
                }
            }

            RomPipelineCard(
                snapshot = romSnapshot,
                message = romMessage,
                installing = installingRom,
                onRefresh = {
                    romSnapshot = romInstaller.snapshot(config.instanceId)
                    romMessage = "ROM scan refreshed"
                },
                onInstall = {
                    scope.launch {
                        installingRom = true
                        romMessage = "Installing guest image"
                        val result = withContext(Dispatchers.IO) {
                            romInstaller.installDefault(config.instanceId)
                        }
                        romMessage = result.message
                        romSnapshot = romInstaller.snapshot(config.instanceId)
                        installingRom = false
                    }
                },
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Bridge Policy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Audio: ${config.bridgePolicy.audioOutput}, Vibration: ${config.bridgePolicy.vibration}")
                    Text("Clipboard: ${config.bridgePolicy.clipboard}, Files: ${config.bridgePolicy.files}")
                    Text("Location: ${config.bridgePolicy.location}, Microphone: ${config.bridgePolicy.microphone}")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Planning docs are tracked under docs/planning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.64f),
            )
        }
    }
}

@Composable
private fun RomPipelineCard(
    snapshot: RomPipelineSnapshot,
    message: String,
    installing: Boolean,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ROM Image",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Candidates: ${snapshot.candidates.size}")
            Text("Installed: ${snapshot.installedManifest?.name ?: "none"}")
            Text("Health: ${snapshot.health.summary}")
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !installing) {
                    Text("Scan")
                }
                Button(
                    onClick = onInstall,
                    enabled = !installing,
                ) {
                    Text(if (installing) "Installing" else "Install")
                }
            }
        }
    }
}
