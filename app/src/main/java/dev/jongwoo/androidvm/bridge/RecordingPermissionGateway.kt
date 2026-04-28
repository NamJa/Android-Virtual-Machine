package dev.jongwoo.androidvm.bridge

/**
 * Test double that records every permission request without prompting the user.
 *
 * Used by Stage 07 unit tests to assert that off / unsupported bridge paths never trigger a host
 * runtime permission prompt, and that enabled bridges request the matching permission only when
 * the bridge is actually exercised.
 */
class RecordingPermissionGateway : PermissionRequestGateway {
    private val internalRequests = mutableListOf<PermissionReason>()

    val requests: List<PermissionReason>
        get() = synchronized(internalRequests) { internalRequests.toList() }

    @Volatile
    var nextResult: Boolean = false

    override suspend fun request(permission: String, reason: PermissionReason): Boolean {
        check(permission == reason.permission) {
            "Permission and reason.permission must match (permission=$permission, reason=${reason.permission})"
        }
        synchronized(internalRequests) { internalRequests += reason }
        return nextResult
    }

    fun reset() {
        synchronized(internalRequests) { internalRequests.clear() }
        nextResult = false
    }
}
