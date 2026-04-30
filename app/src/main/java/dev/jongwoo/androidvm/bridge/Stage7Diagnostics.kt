package dev.jongwoo.androidvm.bridge

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Pure-JVM diagnostic harness for Stage 07. Runs the same checks the
 * `Stage7DiagnosticsReceiver` runs on-device, but isolated from any Android dependency so it can
 * be exercised from unit tests.
 *
 * Each check writes to a fresh temp directory under [workspaceRoot] and returns a boolean. The
 * harness emits per-step result lines via [emit] in the order Stage 07 documents.
 */
class Stage7Diagnostics(
    private val workspaceRoot: File,
    private val manifestText: String,
    private val regressionProbe: () -> Stage7RegressionResult = {
        Stage7RegressionResult(stage4 = false, stage5 = false, stage6 = false)
    },
    private val emit: (String) -> Unit = {},
) {
    fun run(): Stage7ResultLine {
        val manifest = check("STAGE7_MANIFEST_RESULT") { verifyManifest(manifestText) }
        val policy = check("STAGE7_POLICY_RESULT") { verifyPolicy() }
        val broker = check("STAGE7_BROKER_RESULT") { verifyBroker() }
        val audit = check("STAGE7_AUDIT_RESULT") { verifyAudit() }
        val dispatcher = check("STAGE7_DISPATCHER_RESULT") { verifyDispatcher() }
        val ui = check("STAGE7_UI_RESULT") { verifyUiModel() }
        val deviceProfile = check("STAGE7_DEVICE_PROFILE_RESULT") { verifyDeviceProfile() }
        val output = check("STAGE7_OUTPUT_RESULT") { verifyOutputBridges() }
        val clipboard = check("STAGE7_CLIPBOARD_RESULT") { verifyClipboard() }
        val location = check("STAGE7_LOCATION_RESULT") { verifyLocation() }
        val unsupportedMedia = check("STAGE7_UNSUPPORTED_MEDIA_RESULT") { verifyUnsupportedMedia() }
        val regressionResult = regressionProbe()
        val regressions = check(
            "STAGE7_REGRESSION_RESULT",
            extra = "stage4=${regressionResult.stage4} " +
                "stage5=${regressionResult.stage5} stage6=${regressionResult.stage6}",
        ) { regressionResult.passed }

        val line = Stage7ResultLine(
            manifest = manifest,
            policy = policy,
            broker = broker,
            audit = audit,
            dispatcher = dispatcher,
            ui = ui,
            deviceProfile = deviceProfile,
            output = output,
            clipboard = clipboard,
            location = location,
            unsupportedMedia = unsupportedMedia,
            regressions = regressions,
        )
        emit(line.format())
        return line
    }

    private inline fun check(label: String, extra: String? = null, block: () -> Boolean): Boolean {
        val passed = runCatching(block).getOrElse { false }
        val suffix = if (extra != null) " $extra" else ""
        emit("$label passed=$passed$suffix")
        return passed
    }

    private fun fresh(prefix: String): File {
        val dir = File(workspaceRoot, "${prefix}-${UUID.randomUUID()}")
        dir.mkdirs()
        return dir
    }

    private fun verifyManifest(text: String): Boolean {
        // Phase D ships the camera / microphone bridges, so the manifest may now declare CAMERA
        // and RECORD_AUDIO. The denylist still locks the always-forbidden permissions.
        if (Stage7BridgeScope.forbiddenManifestPermissions.any { text.contains(it) }) return false
        return true
    }

    private fun verifyPolicy(): Boolean {
        val rootA = fresh("policy-a")
        val rootB = fresh("policy-b")
        val audit = BridgeAuditLog(rootA)
        val a = BridgePolicyStore(rootA) { reason ->
            audit.appendPolicyRecovery("vm1", reason)
        }
        val b = BridgePolicyStore(rootB)
        if (a.load() != DefaultBridgePolicies.all) return false
        a.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_FIXED, enabled = true)
        }
        if (BridgePolicyStore(rootA).load().getValue(BridgeType.LOCATION).mode != BridgeMode.LOCATION_FIXED) {
            return false
        }
        if (b.load().getValue(BridgeType.LOCATION).mode != BridgeMode.OFF) return false
        File(rootA, BridgePolicyStore.POLICY_FILE_NAME).writeText("{not json")
        val recovered = a.loadDetailed()
        val reloaded = BridgePolicyStore(rootA).loadDetailed()
        return recovered is BridgePolicyLoadResult.RecoveredFromCorruption &&
            reloaded is BridgePolicyLoadResult.Loaded &&
            a.fileForTest().readText().contains("\"bridges\"") &&
            audit.read().any { it.operation == "policy_recovery" }
    }

    private fun verifyBroker(): Boolean {
        val store = BridgePolicyStore(fresh("broker"))
        val gateway = RecordingPermissionGateway()
        val broker = DefaultPermissionBroker({ store }, gateway)
        return runBlocking {
            val off = broker.decide(
                "vm1", BridgeType.CLIPBOARD, "host_to_guest",
                PermissionReason(BridgeType.CLIPBOARD, "host_to_guest", "", ""),
            )
            val unsupported = broker.decide(
                "vm1", BridgeType.CAMERA, "open",
                PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", ""),
            )
            store.update(BridgeType.LOCATION) {
                it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
            }
            gateway.nextResult = false
            val denied = broker.decide(
                "vm1", BridgeType.LOCATION, "request_current_location",
                PermissionReason(BridgeType.LOCATION, "request_current_location", "android.permission.ACCESS_FINE_LOCATION", ""),
            )
            gateway.reset()
            gateway.nextResult = true
            val allowed = broker.decide(
                "vm1", BridgeType.LOCATION, "request_current_location",
                PermissionReason(BridgeType.LOCATION, "request_current_location", "android.permission.ACCESS_FINE_LOCATION", ""),
            )
            off.result == BridgeResult.UNAVAILABLE &&
                unsupported.result == BridgeResult.UNSUPPORTED &&
                denied.result == BridgeResult.DENIED &&
                allowed.result == BridgeResult.ALLOWED
        }
    }

    private fun verifyAudit(): Boolean {
        val log = BridgeAuditLog(fresh("audit"), maxEntries = 3)
        repeat(5) { i ->
            log.appendDecision("vm1", BridgeType.AUDIO_OUTPUT, "op_$i", BridgeDecision.allowed("ok"))
        }
        if (log.count() != 3) return false
        log.clear()
        if (log.count() != 0) return false
        log.appendDecision(
            "vm1",
            BridgeType.CLIPBOARD,
            "host_to_guest",
            BridgeDecision.denied("clipboard_too_large"),
        )
        val raw = log.logFile.readText()
        return !raw.contains("secret") && raw.contains("clipboard_too_large")
    }

    private fun verifyDispatcher(): Boolean {
        val root = fresh("dispatcher")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway()
        val publisher = TrackingPublisher()
        val dispatcher = BridgeDispatcher(
            broker = DefaultPermissionBroker({ store }, gateway),
            auditLogFor = { audit },
            handlers = mapOf(BridgeType.AUDIO_OUTPUT to AudioOutputHandler()),
            nativePublisher = publisher,
        )
        return runBlocking {
            val off = dispatcher.dispatch(BridgeRequest(
                "vm1", BridgeType.CLIPBOARD, "host_to_guest",
                PermissionReason(BridgeType.CLIPBOARD, "host_to_guest", "", ""),
            ))
            val unsupported = dispatcher.dispatch(BridgeRequest(
                "vm1", BridgeType.CAMERA, "open",
                PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", ""),
            ))
            val allowed = dispatcher.dispatch(BridgeRequest(
                "vm1", BridgeType.AUDIO_OUTPUT, "write_pcm",
                PermissionReason(BridgeType.AUDIO_OUTPUT, "write_pcm", "", ""),
            ))
            off.result == BridgeResult.UNAVAILABLE &&
                unsupported.result == BridgeResult.UNSUPPORTED &&
                allowed.result == BridgeResult.ALLOWED &&
                publisher.publishCount == 3 &&
                audit.count() == 3 &&
                gateway.requests.isEmpty()
        }
    }

    private fun verifyUiModel(): Boolean {
        val root = fresh("ui")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val vm = BridgeSettingsViewModel("vm1", store, audit)
        if (vm.state.value.policies != DefaultBridgePolicies.all) return false
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.CLIPBOARD, BridgeMode.CLIPBOARD_BIDIRECTIONAL))
        if (store.load().getValue(BridgeType.CLIPBOARD).mode != BridgeMode.CLIPBOARD_BIDIRECTIONAL) return false
        // Phase D: camera/mic bridges are now supported — flipping ENABLED must persist.
        vm.onAction(BridgeSettingsAction.SetPolicy(BridgeType.CAMERA, BridgeMode.ENABLED))
        if (store.load().getValue(BridgeType.CAMERA).mode != BridgeMode.ENABLED) return false
        vm.onAction(BridgeSettingsAction.ResetPolicies)
        return store.load() == DefaultBridgePolicies.all
    }

    private fun verifyDeviceProfile(): Boolean {
        val rootA = fresh("device-a")
        val rootB = fresh("device-b")
        val auditA = BridgeAuditLog(rootA)
        val auditB = BridgeAuditLog(rootB)
        val a = DeviceProfileBridge(rootA, auditA)
        val b = DeviceProfileBridge(rootB, auditB)
        return runBlocking {
            val first = a.handle(deviceRequest()).payloadJson
            val second = a.handle(deviceRequest()).payloadJson
            val other = b.handle(deviceRequest()).payloadJson
            val firstJson = org.json.JSONObject(first)
            val secondJson = org.json.JSONObject(second)
            val otherJson = org.json.JSONObject(other)
            firstJson.getString("androidId") == secondJson.getString("androidId") &&
                firstJson.getString("androidId") != otherJson.getString("androidId") &&
                firstJson.getString("phoneNumber") == "" &&
                firstJson.getString("imei") == "" &&
                !firstJson.has("advertisingId") &&
                !firstJson.has("hostInstalledPackages")
        }
    }

    private fun verifyOutputBridges(): Boolean {
        val root = fresh("output")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val sink = BoundedAudioSink()
        val audio = AudioOutputBridge(store, audit, sink)
        val vibrator = NoopHostVibrator()
        val vibration = VibrationBridge(store, audit, vibrator, maxDurationMs = 250L)
        // audio off
        store.update(BridgeType.AUDIO_OUTPUT) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        if (audio.writePcm("vm1", ShortArray(8) { 1 }).result != BridgeResult.UNAVAILABLE) return false
        if (sink.writes != 0) return false
        // audio enabled but muted does not write
        store.update(BridgeType.AUDIO_OUTPUT) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        audio.writePcm("vm1", ShortArray(8) { 1 }, muted = true)
        if (sink.writes != 0) return false
        audio.writePcm("vm1", ShortArray(8) { 1 })
        if (sink.writes != 1) return false
        // vibration cap
        vibration.vibrate("vm1", 5_000L)
        if (vibrator.lastDurationMs != 250L) return false
        // network disabled
        store.update(BridgeType.NETWORK) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        val networkResponse = runBlocking {
            NetworkBridge(store, audit).handle(BridgeRequest(
                "vm1", BridgeType.NETWORK, "connect",
                PermissionReason(BridgeType.NETWORK, "connect", "", ""),
            ))
        }
        return networkResponse.result == BridgeResult.UNAVAILABLE
    }

    private fun verifyClipboard(): Boolean {
        val root = fresh("clipboard")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val hostClipboard = InMemoryHostClipboard()
        val clipboard = ClipboardBridge(store, audit, hostClipboard)
        // off blocks both
        hostClipboard.setPlainText("payload")
        if (clipboard.hostToGuest("vm1").result != BridgeResult.UNAVAILABLE) return false
        if (clipboard.guestToHost("vm1", "payload").result != BridgeResult.UNAVAILABLE) return false
        // host to guest only
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_HOST_TO_GUEST, enabled = true)
        }
        if (clipboard.hostToGuest("vm1").result != BridgeResult.ALLOWED) return false
        if (clipboard.guestToHost("vm1", "g").result != BridgeResult.UNAVAILABLE) return false
        // size cap
        val big = "a".repeat(ClipboardBridge.DEFAULT_MAX_BYTES + 1)
        hostClipboard.setPlainText(big)
        if (clipboard.hostToGuest("vm1").result != BridgeResult.DENIED) return false
        // bi works in both directions
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }
        hostClipboard.setPlainText("hi")
        if (clipboard.guestToHost("vm1", "world").result != BridgeResult.ALLOWED) return false
        if (hostClipboard.lastWrittenText != "world") return false
        // audit doesn't leak payload
        val raw = audit.logFile.readText()
        return !raw.contains(big) && !raw.contains("world")
    }

    private fun verifyLocation(): Boolean {
        val root = fresh("location")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway()
        val provider = FixedHostLocationProvider(GuestLocation(1.234, 5.678, 5f))
        val handler = LocationBridge(store, audit, gateway, provider)
        return runBlocking {
            val off = handler.handle(locationRequest())
            if (off.result != BridgeResult.UNAVAILABLE || gateway.requests.isNotEmpty()) return@runBlocking false
            store.update(BridgeType.LOCATION) {
                it.copy(
                    mode = BridgeMode.LOCATION_FIXED,
                    enabled = true,
                    options = mapOf("latitude" to "10.0", "longitude" to "20.0"),
                )
            }
            val fixed = handler.handle(locationRequest())
            if (fixed.result != BridgeResult.ALLOWED || gateway.requests.isNotEmpty()) return@runBlocking false
            store.update(BridgeType.LOCATION) {
                it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
            }
            gateway.nextResult = false
            val denied = handler.handle(locationRequest())
            if (denied.result != BridgeResult.DENIED) return@runBlocking false
            gateway.reset()
            gateway.nextResult = true
            val allowed = handler.handle(locationRequest())
            allowed.result == BridgeResult.ALLOWED
        }
    }

    private fun verifyUnsupportedMedia(): Boolean {
        val root = fresh("media")
        val audit = BridgeAuditLog(root)
        val camera = UnsupportedMediaBridge(BridgeType.CAMERA, audit)
        val mic = UnsupportedMediaBridge(BridgeType.MICROPHONE, audit)
        return runBlocking {
            val cam = camera.handle(BridgeRequest(
                "vm1", BridgeType.CAMERA, "open",
                PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", ""),
            ))
            val m = mic.handle(BridgeRequest(
                "vm1", BridgeType.MICROPHONE, "open",
                PermissionReason(BridgeType.MICROPHONE, "open", "android.permission.RECORD_AUDIO", ""),
            ))
            cam.result == BridgeResult.UNSUPPORTED &&
                m.result == BridgeResult.UNSUPPORTED &&
                audit.count() == 2
        }
    }
    private fun deviceRequest() = BridgeRequest(
        "vm1", BridgeType.DEVICE_PROFILE, "describe",
        PermissionReason(BridgeType.DEVICE_PROFILE, "describe", "", ""),
    )

    private fun locationRequest() = BridgeRequest(
        "vm1", BridgeType.LOCATION, "request_current_location",
        PermissionReason(
            BridgeType.LOCATION,
            "request_current_location",
            "android.permission.ACCESS_FINE_LOCATION",
            "",
        ),
    )

    private class TrackingPublisher : BridgeNativePublisher {
        var publishCount = 0
        override fun publish(request: BridgeRequest, response: BridgeResponse) {
            publishCount++
        }
    }

    private class AudioOutputHandler : BridgeHandler {
        override val bridge: BridgeType = BridgeType.AUDIO_OUTPUT
        override suspend fun handle(request: BridgeRequest): BridgeResponse =
            BridgeResponse(BridgeResult.ALLOWED, "audio_output_handled")
    }

    private class BoundedAudioSink : AudioSink {
        var writes = 0
        override fun write(pcm: ShortArray): Int {
            writes++
            return pcm.size
        }
    }
}

data class Stage7RegressionResult(
    val stage4: Boolean,
    val stage5: Boolean,
    val stage6: Boolean,
) {
    val passed: Boolean
        get() = stage4 && stage5 && stage6
}
