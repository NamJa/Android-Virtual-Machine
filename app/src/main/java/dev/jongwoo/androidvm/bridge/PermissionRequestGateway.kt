package dev.jongwoo.androidvm.bridge

/**
 * Single entry point for runtime dangerous permission prompts.
 *
 * Bridge handlers must route every Android runtime permission request through this gateway so the
 * Stage 07 audit/redaction policy can intercept the request and so tests can assert that a user
 * flow does not pop dangerous permission prompts.
 */
interface PermissionRequestGateway {
    suspend fun request(permission: String, reason: PermissionReason): Boolean
}

/**
 * Gateway used in production for unsupported / off bridges and as the default in test contexts.
 *
 * Stage 07 MVP never grants dangerous permissions silently — every dangerous permission must be
 * proxied through a UI-backed gateway. This implementation always denies and is safe to use as a
 * placeholder for paths that should never reach a host permission prompt.
 */
class DenyAllPermissionGateway : PermissionRequestGateway {
    override suspend fun request(permission: String, reason: PermissionReason): Boolean = false
}
