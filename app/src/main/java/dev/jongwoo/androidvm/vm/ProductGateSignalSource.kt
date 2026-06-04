package dev.jongwoo.androidvm.vm

/**
 * EP0.3 — single source of the raw, *measured* facts that back the five
 * `PRODUCT_*_RESULT` gate lines. [ProductGateRunner] turns these into the gate
 * result and emits the lines.
 *
 * Contract: every method returns a fact derived from a real signal, never a
 * hardcoded `true`. Anything that cannot yet be measured truthfully (because the
 * feature it depends on is not implemented) MUST return `false` — the gate is
 * fail-closed by design. Each EP flips its own fields to `true` only when the
 * underlying behavior is real and verifiable.
 *
 * `*Real` / guest-origin fields are judged solely from guest-produced signals;
 * canned host status (see the simulated boot path slated for removal in EP2)
 * never counts as a pass.
 */
interface ProductGateSignalSource {
    // --- runtime (PRODUCT_RUNTIME_RESULT) — flipped by G-track (EP2–EP4) ---
    fun bootRealGuestOrigin(): Boolean
    fun installReal(): Boolean
    fun launchReal(): Boolean
    fun inputReal(): Boolean
    fun graphicsReal(): Boolean
    fun audioReal(): Boolean

    // --- bridge (PRODUCT_BRIDGE_RESULT) — flipped by EP6 / EP5 ---
    fun clipboardFunctional(): Boolean
    fun fileFunctional(): Boolean
    fun networkFunctional(): Boolean
    fun cameraPolicyDefaultOff(): Boolean
    fun micPolicyDefaultOff(): Boolean
    fun auditTrailPresent(): Boolean

    // --- resilience (PRODUCT_RESILIENCE_RESULT) — flipped by EP7 ---
    fun snapshotFunctional(): Boolean
    fun rollbackFunctional(): Boolean
    fun crashReportFunctional(): Boolean
    fun bootRepairFunctional(): Boolean
    fun dataExportFunctional(): Boolean

    // --- security (PRODUCT_SECURITY_RESULT) — flipped by EP8 ---
    fun permissionsMinimal(): Boolean
    fun updateUsesEd25519(): Boolean
    fun offlineOnly(): Boolean
    fun telemetryOff(): Boolean
    fun noBundledSecrets(): Boolean

    // --- release (PRODUCT_RELEASE_RESULT) — flipped by EP9 / EP10 ---
    fun debugSurfaceClosed(): Boolean
    fun signed(): Boolean
    fun storeReady(): Boolean
    fun docsPresent(): Boolean
    fun supportPresent(): Boolean
}

/**
 * Fail-closed baseline: every signal is `false`. Concrete sources delegate the
 * not-yet-measurable fields here so the default is always "not proven".
 */
object ConservativeProductGateSignalSource : ProductGateSignalSource {
    override fun bootRealGuestOrigin() = false
    override fun installReal() = false
    override fun launchReal() = false
    override fun inputReal() = false
    override fun graphicsReal() = false
    override fun audioReal() = false

    override fun clipboardFunctional() = false
    override fun fileFunctional() = false
    override fun networkFunctional() = false
    override fun cameraPolicyDefaultOff() = false
    override fun micPolicyDefaultOff() = false
    override fun auditTrailPresent() = false

    override fun snapshotFunctional() = false
    override fun rollbackFunctional() = false
    override fun crashReportFunctional() = false
    override fun bootRepairFunctional() = false
    override fun dataExportFunctional() = false

    override fun permissionsMinimal() = false
    override fun updateUsesEd25519() = false
    override fun offlineOnly() = false
    override fun telemetryOff() = false
    override fun noBundledSecrets() = false

    override fun debugSurfaceClosed() = false
    override fun signed() = false
    override fun storeReady() = false
    override fun docsPresent() = false
    override fun supportPresent() = false
}
