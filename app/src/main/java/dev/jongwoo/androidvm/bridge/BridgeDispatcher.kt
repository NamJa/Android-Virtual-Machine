package dev.jongwoo.androidvm.bridge

import dev.jongwoo.androidvm.vm.VmNativeBridge

/**
 * Single entry point that funnels every bridge request through the policy [PermissionBroker], the
 * per-instance [BridgeAuditLog], and finally a per-bridge [BridgeHandler].
 *
 * Off / unsupported bridges short-circuit at the broker; their handlers are never invoked. Unknown
 * bridges and bridges without a registered handler return [BridgeResult.UNSUPPORTED] without
 * crashing. Every dispatch — regardless of outcome — is appended to the audit log.
 */
class BridgeDispatcher(
    private val broker: PermissionBroker,
    private val auditLogFor: (instanceId: String) -> BridgeAuditLog,
    private val handlers: Map<BridgeType, BridgeHandler>,
    private val nativePublisher: BridgeNativePublisher = BridgeNativePublisher.NoOp,
) {
    suspend fun dispatch(request: BridgeRequest): BridgeResponse {
        val auditLog = auditLogFor(request.instanceId)
        val decision = runCatching {
            broker.decide(
                instanceId = request.instanceId,
                bridge = request.bridge,
                operation = request.operation,
                reason = request.reason,
            )
        }.getOrElse { cause ->
            BridgeDecision.unsupported("dispatcher_error:${cause.javaClass.simpleName}")
        }

        val response = if (!decision.allowed) {
            BridgeResponse(decision.result, decision.reason)
        } else {
            val handler = handlers[request.bridge]
            if (handler == null) {
                BridgeResponse(BridgeResult.UNSUPPORTED, "handler_missing")
            } else {
                runCatching { handler.handle(request) }.getOrElse { cause ->
                    BridgeResponse(
                        result = BridgeResult.UNSUPPORTED,
                        reason = "handler_error:${cause.javaClass.simpleName}",
                    )
                }
            }
        }

        auditLog.appendDecision(
            request.instanceId,
            request.bridge,
            request.operation,
            BridgeDecision(
                allowed = response.result == BridgeResult.ALLOWED,
                result = response.result,
                reason = response.reason,
            ),
        )
        nativePublisher.publish(request, response)
        return response
    }
}

/**
 * Publishes the resolved bridge decision into native runtime state for diagnostics. The default
 * implementation is a no-op so unit tests can run without loading the native library.
 */
fun interface BridgeNativePublisher {
    fun publish(request: BridgeRequest, response: BridgeResponse)

    companion object {
        val NoOp: BridgeNativePublisher = BridgeNativePublisher { _, _ -> }
    }
}

class VmNativeBridgePublisher : BridgeNativePublisher {
    override fun publish(request: BridgeRequest, response: BridgeResponse) {
        VmNativeBridge.publishBridgeResult(
            request.instanceId,
            request.bridge.wireName,
            request.operation,
            response.result.wireName,
            response.reason,
            response.payloadJson,
        )
    }
}
