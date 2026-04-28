package dev.jongwoo.androidvm.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jongwoo.androidvm.apk.ApkImportRequest
import dev.jongwoo.androidvm.apk.ApkInstallPipeline
import dev.jongwoo.androidvm.apk.ApkStager
import dev.jongwoo.androidvm.apk.GuestPackageInfo
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
    val apkPipeline = remember(context) { ApkInstallPipeline(context) }
    var state by remember { mutableStateOf(VmState.STOPPED) }
    var romSnapshot by remember { mutableStateOf(romInstaller.snapshot(config.instanceId)) }
    var romMessage by remember { mutableStateOf("ROM pipeline is ready") }
    var installingRom by remember { mutableStateOf(false) }
    var packages by remember { mutableStateOf(apkPipeline.loadPackages(config.instanceId)) }
    var packageMessage by remember { mutableStateOf("No packages installed yet") }
    var importingApk by remember { mutableStateOf(false) }
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            packageMessage = "APK selection cancelled"
            return@rememberLauncherForActivityResult
        }
        if (!romSnapshot.isInstalled) {
            packageMessage = "Install the guest ROM before importing APKs"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            importingApk = true
            packageMessage = "Importing ${uri.lastPathSegment ?: "APK"}"
            val result = withContext(Dispatchers.IO) {
                apkPipeline.importAndInstall(
                    request = ApkImportRequest(
                        instanceId = config.instanceId,
                        sourceUri = uri,
                        displayName = null,
                        sizeLimitBytes = ApkStager.DEFAULT_SIZE_LIMIT_BYTES,
                    ),
                    onProgress = { progress ->
                        scope.launch {
                            packageMessage = progress.toPackageMessage()
                        }
                    },
                )
            }
            packageMessage = result.message
            packages = withContext(Dispatchers.IO) { apkPipeline.loadPackages(config.instanceId) }
            importingApk = false
        }
    }

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
                onRepair = {
                    scope.launch {
                        installingRom = true
                        romMessage = "Repairing guest image"
                        val result = withContext(Dispatchers.IO) {
                            romInstaller.repair(config.instanceId)
                        }
                        romMessage = result.message
                        romSnapshot = romInstaller.snapshot(config.instanceId)
                        installingRom = false
                    }
                },
            )

            PackageListCard(
                packages = packages,
                message = packageMessage,
                importing = importingApk,
                canImport = romSnapshot.isInstalled,
                onPickApk = {
                    apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                },
                onRefresh = {
                    scope.launch {
                        val refreshed = withContext(Dispatchers.IO) {
                            apkPipeline.loadPackages(config.instanceId)
                        }
                        packages = refreshed
                        packageMessage = if (refreshed.isEmpty()) {
                            "Package list is empty"
                        } else {
                            "Loaded ${refreshed.size} package(s)"
                        }
                    }
                },
                onLaunchPackage = { pkg ->
                    apkPipeline.launch(config.instanceId, pkg.packageName)
                    context.startActivity(Intent(context, VmNativeActivity::class.java))
                    state = VmState.RUNNING
                    packageMessage = "Launching ${pkg.label}"
                },
                onUninstallPackage = { pkg ->
                    scope.launch {
                        apkPipeline.uninstall(config.instanceId, pkg.packageName)
                        packageMessage = "Uninstall dispatched: ${pkg.packageName}"
                        kotlinx.coroutines.delay(750)
                        packages = withContext(Dispatchers.IO) { apkPipeline.loadPackages(config.instanceId) }
                    }
                },
                onClearPackageData = { pkg ->
                    scope.launch {
                        apkPipeline.clearData(config.instanceId, pkg.packageName)
                        packageMessage = "Clear data dispatched: ${pkg.packageName}"
                        kotlinx.coroutines.delay(500)
                        packages = withContext(Dispatchers.IO) { apkPipeline.loadPackages(config.instanceId) }
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

private fun dev.jongwoo.androidvm.apk.ApkPipelineProgress.toPackageMessage(): String {
    staging?.let { progress ->
        val total = progress.totalBytes
        return if (total != null && total > 0) {
            "${progress.message} (${progress.bytesCopied}/${total} bytes)"
        } else {
            progress.message
        }
    }
    return when (stage) {
        dev.jongwoo.androidvm.apk.ApkPipelineStage.INSPECT -> "Inspecting APK"
        dev.jongwoo.androidvm.apk.ApkPipelineStage.ENRICH -> "Preparing runtime metadata"
        dev.jongwoo.androidvm.apk.ApkPipelineStage.DISPATCH -> "Installing into guest runtime"
        dev.jongwoo.androidvm.apk.ApkPipelineStage.DONE -> "Package installed"
        dev.jongwoo.androidvm.apk.ApkPipelineStage.STAGING -> "Copying APK"
    }
}

@Composable
private fun PackageListCard(
    packages: List<GuestPackageInfo>,
    message: String,
    importing: Boolean,
    canImport: Boolean,
    onPickApk: () -> Unit,
    onRefresh: () -> Unit,
    onLaunchPackage: (GuestPackageInfo) -> Unit,
    onUninstallPackage: (GuestPackageInfo) -> Unit,
    onClearPackageData: (GuestPackageInfo) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Packages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${packages.size} installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            if (packages.isEmpty()) {
                Text(
                    text = "Use \"Install APK\" to import a single APK from a content URI.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    packages.forEach { pkg ->
                        PackageRow(
                            pkg = pkg,
                            onLaunch = { onLaunchPackage(pkg) },
                            onUninstall = { onUninstallPackage(pkg) },
                            onClearData = { onClearPackageData(pkg) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPickApk, enabled = !importing && canImport) {
                    Text(if (importing) "Installing" else "Install APK")
                }
                OutlinedButton(onClick = onRefresh, enabled = !importing) {
                    Text("Refresh")
                }
            }
            if (!canImport) {
                Text(
                    text = "Install the guest ROM image first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PackageRow(
    pkg: GuestPackageInfo,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onClearData: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val initial = pkg.label.firstOrNull()?.uppercaseChar()?.toString()
                ?: pkg.packageName.firstOrNull()?.uppercaseChar()?.toString()
                ?: "?"
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Text(text = pkg.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${pkg.packageName} v${pkg.versionName ?: pkg.versionCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Text(
                text = if (pkg.launchable) "Launchable" else "Non-launchable",
                style = MaterialTheme.typography.labelSmall,
                color = if (pkg.launchable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onLaunch, enabled = pkg.launchable && pkg.enabled) { Text("Launch") }
                TextButton(onClick = onUninstall) { Text("Uninstall") }
                TextButton(onClick = onClearData) { Text("Clear data") }
            }
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
    onRepair: () -> Unit,
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
            Text("Image: ${snapshot.imageState.name}")
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
                OutlinedButton(
                    onClick = onRepair,
                    enabled = !installing && snapshot.needsRepair,
                ) {
                    Text("Repair")
                }
            }
        }
    }
}
