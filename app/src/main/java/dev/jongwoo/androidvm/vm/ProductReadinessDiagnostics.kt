package dev.jongwoo.androidvm.vm

data class ProductRuntimeResultLine(
    val boot: Boolean,
    val install: Boolean,
    val launch: Boolean,
    val input: Boolean,
    val graphics: Boolean,
    val audio: Boolean,
) {
    val passed: Boolean = boot && install && launch && input && graphics && audio

    fun format(): String =
        "PRODUCT_RUNTIME_RESULT passed=$passed boot=$boot install=$install launch=$launch " +
            "input=$input graphics=$graphics audio=$audio"
}

data class ProductBridgeResultLine(
    val clipboard: Boolean,
    val file: Boolean,
    val network: Boolean,
    val cameraPolicy: Boolean,
    val micPolicy: Boolean,
    val audit: Boolean,
) {
    val passed: Boolean = clipboard && file && network && cameraPolicy && micPolicy && audit

    fun format(): String =
        "PRODUCT_BRIDGE_RESULT passed=$passed clipboard=$clipboard file=$file network=$network " +
            "camera_policy=$cameraPolicy mic_policy=$micPolicy audit=$audit"
}

data class ProductResilienceResultLine(
    val snapshot: Boolean,
    val rollback: Boolean,
    val crashReport: Boolean,
    val bootRepair: Boolean,
    val dataExport: Boolean,
) {
    val passed: Boolean = snapshot && rollback && crashReport && bootRepair && dataExport

    fun format(): String =
        "PRODUCT_RESILIENCE_RESULT passed=$passed snapshot=$snapshot rollback=$rollback " +
            "crash_report=$crashReport boot_repair=$bootRepair data_export=$dataExport"
}

data class ProductSecurityResultLine(
    val permissionsMinimal: Boolean,
    val updateEd25519: Boolean,
    val offline: Boolean,
    val telemetryOff: Boolean,
    val secretsNone: Boolean,
) {
    val passed: Boolean = permissionsMinimal && updateEd25519 && offline && telemetryOff && secretsNone

    fun format(): String =
        "PRODUCT_SECURITY_RESULT passed=$passed permissions=minimal update=ed25519 " +
            "offline=$offline telemetry=${if (telemetryOff) "off" else "on"} " +
            "secrets=${if (secretsNone) "none" else "present"}"
}

data class ProductReleaseResultLine(
    val debugSurfaceClosed: Boolean,
    val signed: Boolean,
    val storeReady: Boolean,
    val docs: Boolean,
    val support: Boolean,
) {
    val passed: Boolean = debugSurfaceClosed && signed && storeReady && docs && support

    fun format(): String =
        "PRODUCT_RELEASE_RESULT passed=$passed debug_surface=${if (debugSurfaceClosed) "closed" else "open"} " +
            "signed=$signed store_ready=$storeReady docs=$docs support=$support"
}

data class ProductReadinessResult(
    val runtime: ProductRuntimeResultLine,
    val bridge: ProductBridgeResultLine,
    val resilience: ProductResilienceResultLine,
    val security: ProductSecurityResultLine,
    val release: ProductReleaseResultLine,
) {
    val passed: Boolean = runtime.passed && bridge.passed && resilience.passed &&
        security.passed && release.passed
}

class ProductReadinessDiagnostics(
    private val runtimeProbe: () -> ProductRuntimeResultLine = {
        ProductRuntimeResultLine(false, false, false, false, false, false)
    },
    private val bridgeProbe: () -> ProductBridgeResultLine = {
        ProductBridgeResultLine(false, false, false, false, false, false)
    },
    private val resilienceProbe: () -> ProductResilienceResultLine = {
        ProductResilienceResultLine(false, false, false, false, false)
    },
    private val securityProbe: () -> ProductSecurityResultLine = {
        ProductSecurityResultLine(false, false, false, false, false)
    },
    private val releaseProbe: () -> ProductReleaseResultLine = {
        ProductReleaseResultLine(false, false, false, false, false)
    },
    private val emit: (String) -> Unit = {},
) {
    fun run(): ProductReadinessResult {
        val runtime = runtimeProbe()
        val bridge = bridgeProbe()
        val resilience = resilienceProbe()
        val security = securityProbe()
        val release = releaseProbe()
        listOf(
            runtime.format(),
            bridge.format(),
            resilience.format(),
            security.format(),
            release.format(),
        ).forEach(emit)
        return ProductReadinessResult(runtime, bridge, resilience, security, release)
    }
}
