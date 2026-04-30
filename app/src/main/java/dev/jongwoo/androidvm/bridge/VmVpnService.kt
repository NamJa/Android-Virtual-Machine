package dev.jongwoo.androidvm.bridge

import android.net.VpnService

/**
 * Phase D VPN endpoint declaration. The diagnostics exercise the consent/session controller in
 * Kotlin; this service gives Android a concrete `BIND_VPN_SERVICE` target for the user-consented
 * tun fd path.
 */
class VmVpnService : VpnService()
