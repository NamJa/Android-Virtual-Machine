package dev.jongwoo.androidvm.vm

/**
 * EP0.2/EP0.3 — the product gate runner. It wires a [ProductGateSignalSource]
 * (real, measured facts) into [ProductReadinessDiagnostics] and emits the five
 * `PRODUCT_*_RESULT` lines.
 *
 * This is the release-equivalent counterpart to the debug-only Stage/Phase
 * diagnostic receivers: it carries no canned success path. With the current
 * (simulated) guest runtime most fields are `false`; fields flip to `true` only
 * as their owning EP delivers real behavior.
 */
class ProductGateRunner(
    private val signals: ProductGateSignalSource = ConservativeProductGateSignalSource,
    private val emit: (String) -> Unit = {},
) {
    fun run(): ProductReadinessResult =
        ProductReadinessDiagnostics(
            runtimeProbe = {
                ProductRuntimeResultLine(
                    boot = signals.bootRealGuestOrigin(),
                    install = signals.installReal(),
                    launch = signals.launchReal(),
                    input = signals.inputReal(),
                    graphics = signals.graphicsReal(),
                    audio = signals.audioReal(),
                )
            },
            bridgeProbe = {
                ProductBridgeResultLine(
                    clipboard = signals.clipboardFunctional(),
                    file = signals.fileFunctional(),
                    network = signals.networkFunctional(),
                    cameraPolicy = signals.cameraPolicyDefaultOff(),
                    micPolicy = signals.micPolicyDefaultOff(),
                    audit = signals.auditTrailPresent(),
                )
            },
            resilienceProbe = {
                ProductResilienceResultLine(
                    snapshot = signals.snapshotFunctional(),
                    rollback = signals.rollbackFunctional(),
                    crashReport = signals.crashReportFunctional(),
                    bootRepair = signals.bootRepairFunctional(),
                    dataExport = signals.dataExportFunctional(),
                )
            },
            securityProbe = {
                ProductSecurityResultLine(
                    permissionsMinimal = signals.permissionsMinimal(),
                    updateEd25519 = signals.updateUsesEd25519(),
                    offline = signals.offlineOnly(),
                    telemetryOff = signals.telemetryOff(),
                    secretsNone = signals.noBundledSecrets(),
                )
            },
            releaseProbe = {
                ProductReleaseResultLine(
                    debugSurfaceClosed = signals.debugSurfaceClosed(),
                    signed = signals.signed(),
                    storeReady = signals.storeReady(),
                    docs = signals.docsPresent(),
                    support = signals.supportPresent(),
                )
            },
            emit = emit,
        ).run()
}
