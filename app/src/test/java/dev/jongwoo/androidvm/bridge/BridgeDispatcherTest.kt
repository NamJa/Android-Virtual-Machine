package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDispatcherTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun offBridgeShortCircuitsBeforeHandlerAndIsAuditLogged() = runTest {
        val context = newDispatcherContext()
        val handler = RecordingHandler(BridgeType.CLIPBOARD)
        context.handlers[BridgeType.CLIPBOARD] = handler

        val response = context.dispatcher().dispatch(clipboardRequest())

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("bridge_disabled", response.reason)
        assertEquals(0, handler.handleCount)
        val audit = context.auditLog().read()
        assertEquals(1, audit.size)
        assertEquals(BridgeResult.UNAVAILABLE, audit.single().result)
    }

    @Test
    fun unsupportedBridgeShortCircuitsBeforeHandler() = runTest {
        val context = newDispatcherContext()
        val handler = RecordingHandler(BridgeType.CAMERA)
        context.handlers[BridgeType.CAMERA] = handler

        val response = context.dispatcher().dispatch(cameraRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertEquals(0, handler.handleCount)
        assertTrue(context.gateway.requests.isEmpty())
    }

    @Test
    fun missingHandlerReturnsUnsupportedAndAuditsFinalResult() = runTest {
        val context = newDispatcherContext()
        // Audio output is enabled by default and has no dangerous permission, but we don't
        // register a handler for it.
        val response = context.dispatcher().dispatch(audioRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertEquals("handler_missing", response.reason)
        val audit = context.auditLog().read()
        assertEquals(BridgeResult.UNSUPPORTED, audit.single().result)
        assertEquals("handler_missing", audit.single().reason)
    }

    @Test
    fun handlerErrorIsCaughtAndReportedAsUnsupported() = runTest {
        val context = newDispatcherContext()
        context.handlers[BridgeType.AUDIO_OUTPUT] = ThrowingHandler(BridgeType.AUDIO_OUTPUT)

        val response = context.dispatcher().dispatch(audioRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertTrue(response.reason.startsWith("handler_error:"))
        val audit = context.auditLog().read()
        assertEquals(BridgeResult.UNSUPPORTED, audit.single().result)
        assertTrue(audit.single().reason.startsWith("handler_error:"))
    }

    @Test
    fun allowedRequestReachesHandlerAndIsPublishedToNative() = runTest {
        val context = newDispatcherContext()
        val handler = StaticPayloadHandler(BridgeType.AUDIO_OUTPUT, "{\"frames\":42}")
        context.handlers[BridgeType.AUDIO_OUTPUT] = handler

        val publishedResponses = mutableListOf<Pair<BridgeRequest, BridgeResponse>>()
        val publisher = BridgeNativePublisher { request, response ->
            publishedResponses += request to response
        }

        val response = context.dispatcher(publisher = publisher).dispatch(audioRequest())

        assertEquals(BridgeResult.ALLOWED, response.result)
        assertEquals("{\"frames\":42}", response.payloadJson)
        assertEquals(1, handler.handleCount)
        assertEquals(1, publishedResponses.size)
        assertEquals(BridgeType.AUDIO_OUTPUT, publishedResponses.single().first.bridge)
    }

    @Test
    fun unknownBridgeReturnsUnsupportedAndDoesNotInvokeAnyHandler() = runTest {
        val context = newDispatcherContext()
        // Only register a handler for CAMERA which is unsupported anyway.
        context.handlers[BridgeType.CAMERA] = RecordingHandler(BridgeType.CAMERA)
        // Build a request that the broker says ALLOWED but no handler exists.
        val store = context.policyStore
        store.update(BridgeType.NETWORK) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }

        val response = context.dispatcher().dispatch(networkRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertEquals("handler_missing", response.reason)
    }

    @Test
    fun deniedDangerousPermissionResultsInDeniedResponse() = runTest {
        val context = newDispatcherContext()
        context.policyStore.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        context.gateway.nextResult = false
        val handler = RecordingHandler(BridgeType.LOCATION)
        context.handlers[BridgeType.LOCATION] = handler

        val response = context.dispatcher().dispatch(locationRequest())

        assertEquals(BridgeResult.DENIED, response.result)
        assertEquals(0, handler.handleCount)
    }

    @Test
    fun invalidPayloadDoesNotCrashDispatcher() = runTest {
        val context = newDispatcherContext()
        context.policyStore.update(BridgeType.AUDIO_OUTPUT) { it } // ensure store exists

        val handler = StaticPayloadHandler(BridgeType.AUDIO_OUTPUT, "{}")
        context.handlers[BridgeType.AUDIO_OUTPUT] = handler

        val response = context.dispatcher().dispatch(
            audioRequest().copy(payloadJson = "not-json"),
        )

        assertEquals(BridgeResult.ALLOWED, response.result)
        assertEquals(1, handler.handleCount)
    }

    @Test
    fun bridgeResponseSerializesToJsonWithKnownFields() {
        val response = BridgeResponse(BridgeResult.UNAVAILABLE, "bridge_disabled")
        val json = response.toJson()
        assertEquals("unavailable", json.getString("result"))
        assertEquals("bridge_disabled", json.getString("reason"))
        assertNotNull(json.getJSONObject("payload"))
    }

    @Test
    fun bridgeTypeRoundTripsThroughWireName() {
        BridgeType.entries.forEach {
            val parsed = BridgeType.fromWireName(it.wireName)
            assertEquals(it, parsed)
        }
        assertNull(BridgeType.fromWireName("unknown"))
    }

    @Test
    fun nativePublisherNoOpDoesNotThrow() {
        BridgeNativePublisher.NoOp.publish(
            audioRequest(),
            BridgeResponse(BridgeResult.ALLOWED, "ok"),
        )
    }

    @Test
    fun decisionErrorPathIsAuditedAsUnsupported() = runTest {
        val context = newDispatcherContext()
        val brokerThatThrows = object : PermissionBroker {
            override suspend fun decide(
                instanceId: String,
                bridge: BridgeType,
                operation: String,
                reason: PermissionReason,
            ): BridgeDecision = error("broker boom")
            override suspend fun ensurePermission(permission: String, reason: PermissionReason) = false
            override fun isBridgeEnabled(instanceId: String, bridge: BridgeType) = false
            override fun setBridgePolicy(instanceId: String, bridge: BridgeType, mode: BridgeMode) = Unit
        }
        val dispatcher = BridgeDispatcher(
            broker = brokerThatThrows,
            auditLogFor = { context.auditLog() },
            handlers = emptyMap(),
        )

        val response = dispatcher.dispatch(audioRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertTrue(response.reason.startsWith("dispatcher_error:"))
        assertFalse(context.auditLog().read().isEmpty())
    }

    private class RecordingHandler(override val bridge: BridgeType) : BridgeHandler {
        var handleCount = 0
        override suspend fun handle(request: BridgeRequest): BridgeResponse {
            handleCount++
            return BridgeResponse(BridgeResult.ALLOWED, "handled")
        }
    }

    private class ThrowingHandler(override val bridge: BridgeType) : BridgeHandler {
        override suspend fun handle(request: BridgeRequest): BridgeResponse =
            error("handler boom")
    }

    private class StaticPayloadHandler(
        override val bridge: BridgeType,
        private val payload: String,
    ) : BridgeHandler {
        var handleCount = 0
        override suspend fun handle(request: BridgeRequest): BridgeResponse {
            handleCount++
            return BridgeResponse(BridgeResult.ALLOWED, "handled", payloadJson = payload)
        }
    }

    private class DispatcherContext(
        val policyStore: BridgePolicyStore,
        val auditFile: File,
        val gateway: RecordingPermissionGateway,
        val handlers: MutableMap<BridgeType, BridgeHandler>,
    ) {
        private val sharedAudit = BridgeAuditLog(auditFile)
        fun auditLog(): BridgeAuditLog = sharedAudit
        fun dispatcher(
            publisher: BridgeNativePublisher = BridgeNativePublisher.NoOp,
        ): BridgeDispatcher = BridgeDispatcher(
            broker = DefaultPermissionBroker({ policyStore }, gateway),
            auditLogFor = { auditLog() },
            handlers = handlers,
            nativePublisher = publisher,
        )
    }

    private fun newDispatcherContext(): DispatcherContext {
        val instanceRoot = tempDir("dispatch")
        return DispatcherContext(
            policyStore = BridgePolicyStore(instanceRoot),
            auditFile = instanceRoot,
            gateway = RecordingPermissionGateway(),
            handlers = mutableMapOf(),
        )
    }

    private fun clipboardRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.CLIPBOARD,
        operation = "host_to_guest",
        reason = PermissionReason(BridgeType.CLIPBOARD, "host_to_guest", "", "Clipboard"),
    )

    private fun cameraRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.CAMERA,
        operation = "open",
        reason = PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", "Camera"),
    )

    private fun audioRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.AUDIO_OUTPUT,
        operation = "write_pcm",
        reason = PermissionReason(BridgeType.AUDIO_OUTPUT, "write_pcm", "", "Audio"),
    )

    private fun networkRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.NETWORK,
        operation = "connect",
        reason = PermissionReason(BridgeType.NETWORK, "connect", "", "Network"),
    )

    private fun locationRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.LOCATION,
        operation = "request_current_location",
        reason = PermissionReason(
            BridgeType.LOCATION,
            "request_current_location",
            "android.permission.ACCESS_FINE_LOCATION",
            "Location",
        ),
    )

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}
