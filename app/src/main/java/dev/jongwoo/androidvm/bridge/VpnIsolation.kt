package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

/**
 * Phase D.7 VPN consent state. The host must call `VpnService.prepare(context)` before opening a
 * tun fd; if the user has not yet granted consent the bridge keeps the network in the prior policy
 * mode rather than silently bypassing the dialog.
 */
enum class VpnConsentState {
    NOT_REQUESTED,
    PENDING,
    GRANTED,
    DENIED,
    ;

    val wireName: String get() = name.lowercase()

    val active: Boolean get() = this == GRANTED
}

/**
 * Per-instance VPN session description. The native side reads from this struct when configuring
 * the tun device; tests assert the configuration is well-formed.
 */
data class VpnSession(
    val instanceId: String,
    val sessionLabel: String,
    val ipv4Address: String,
    val ipv4Prefix: Int,
    val routes: List<String>,
    val dnsServers: List<String>,
    val mtu: Int,
    val dnsProxyEnabled: Boolean,
) {
    init {
        require(mtu in 576..9000) { "MTU $mtu outside reasonable bounds" }
        require(ipv4Prefix in 0..32) { "Invalid IPv4 prefix $ipv4Prefix" }
        require(routes.isNotEmpty()) { "VpnSession must declare at least one route" }
        require(dnsServers.isNotEmpty()) { "VpnSession must declare at least one DNS server" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("instanceId", instanceId)
        .put("sessionLabel", sessionLabel)
        .put("ipv4Address", ipv4Address)
        .put("ipv4Prefix", ipv4Prefix)
        .put("routes", routes)
        .put("dnsServers", dnsServers)
        .put("mtu", mtu)
        .put("dnsProxyEnabled", dnsProxyEnabled)

    companion object {
        const val DEFAULT_MTU: Int = 1500
        const val DEFAULT_PREFIX: Int = 24

        fun defaultFor(instanceId: String, dnsProxyEnabled: Boolean = true): VpnSession =
            VpnSession(
                instanceId = instanceId,
                sessionLabel = "AVM-$instanceId",
                ipv4Address = "10.0.0.2",
                ipv4Prefix = DEFAULT_PREFIX,
                routes = listOf("0.0.0.0/0"),
                dnsServers = listOf("1.1.1.1", "9.9.9.9"),
                mtu = DEFAULT_MTU,
                dnsProxyEnabled = dnsProxyEnabled,
            )
    }
}

/**
 * Phase D.7 controller. Consolidates: which network mode is configured, whether VPN consent has
 * been granted, and whether the tun fd is currently attached. The pure-JVM oracle drives unit
 * tests and the Phase D diagnostics receiver.
 */
class NetworkIsolationController(
    initialMode: NetworkEgressMode = NetworkEgressMode.HOST_NAT,
    private val initialSession: VpnSession? = null,
) {
    @Volatile
    private var modeInternal: NetworkEgressMode = initialMode

    @Volatile
    private var consentInternal: VpnConsentState = VpnConsentState.NOT_REQUESTED

    @Volatile
    private var tunAttachedInternal: Boolean = false

    @Volatile
    private var sessionInternal: VpnSession? = initialSession

    val mode: NetworkEgressMode get() = modeInternal
    val consent: VpnConsentState get() = consentInternal
    val tunAttached: Boolean get() = tunAttachedInternal
    val session: VpnSession? get() = sessionInternal

    @Synchronized
    fun requestConsent(): VpnConsentState {
        if (consentInternal == VpnConsentState.NOT_REQUESTED) {
            consentInternal = VpnConsentState.PENDING
        }
        return consentInternal
    }

    @Synchronized
    fun applyConsent(granted: Boolean) {
        consentInternal = if (granted) VpnConsentState.GRANTED else VpnConsentState.DENIED
    }

    @Synchronized
    fun switchMode(target: NetworkEgressMode, session: VpnSession? = null) {
        modeInternal = target
        if (target == NetworkEgressMode.VPN_ISOLATED) {
            sessionInternal = session ?: sessionInternal
            // tun is only attached after consent is granted; switching mode does not auto-attach.
        } else {
            tunAttachedInternal = false
        }
    }

    @Synchronized
    fun attachTun(): Boolean {
        if (modeInternal != NetworkEgressMode.VPN_ISOLATED) return false
        if (consentInternal != VpnConsentState.GRANTED) return false
        if (sessionInternal == null) return false
        tunAttachedInternal = true
        return true
    }

    @Synchronized
    fun detachTun() {
        tunAttachedInternal = false
    }

    fun decideSyscall(): NetworkSyscallGate.SyscallDecision =
        NetworkSyscallGate.decide(modeInternal, vpnAttached = tunAttachedInternal)

    fun snapshot(): JSONObject = JSONObject()
        .put("mode", modeInternal.wireName)
        .put("consent", consentInternal.wireName)
        .put("tunAttached", tunAttachedInternal)
        .put("session", sessionInternal?.toJson() ?: JSONObject.NULL)
}
