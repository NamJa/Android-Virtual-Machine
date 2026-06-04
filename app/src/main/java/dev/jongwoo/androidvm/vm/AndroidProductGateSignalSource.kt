package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.BuildConfig
import dev.jongwoo.androidvm.bridge.BridgeType
import dev.jongwoo.androidvm.bridge.DefaultBridgePolicies

/**
 * EP0.3 — concrete [ProductGateSignalSource] that wires the gate fields which can
 * be measured truthfully *today* and delegates everything else to
 * [ConservativeProductGateSignalSource] (fail-closed `false`).
 *
 * Fields that can already be proven:
 *  - [debugSurfaceClosed]: true only in the `product`/release-equivalent build,
 *    where `app/src/debug/` diagnostic receivers are not compiled in.
 *    `ProductReleaseSurfaceGuardTest` statically enforces that absence.
 *  - [cameraPolicyDefaultOff] / [micPolicyDefaultOff]: read straight from the
 *    shipped [DefaultBridgePolicies]; privacy bridges default off.
 *
 * Everything else stays `false` until its owning EP delivers real behavior:
 *  - runtime boot/install/launch/input/graphics/audio: EP2–EP4 (real guest)
 *  - bridge clipboard/file/network + audit: EP5/EP6
 *  - resilience snapshot/rollback/crash/repair/export: EP7
 *  - security ed25519/offline/telemetry/secrets/permissions: EP8
 *  - release signed/storeReady/docs/support: EP9/EP10
 */
class AndroidProductGateSignalSource :
    ProductGateSignalSource by ConservativeProductGateSignalSource {

    override fun debugSurfaceClosed(): Boolean = BuildConfig.PRODUCT_GATE

    override fun cameraPolicyDefaultOff(): Boolean =
        DefaultBridgePolicies.all[BridgeType.CAMERA]?.enabled == false

    override fun micPolicyDefaultOff(): Boolean =
        DefaultBridgePolicies.all[BridgeType.MICROPHONE]?.enabled == false
}
