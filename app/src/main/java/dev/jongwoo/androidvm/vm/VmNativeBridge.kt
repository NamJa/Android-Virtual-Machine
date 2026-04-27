package dev.jongwoo.androidvm.vm

import android.view.Surface

object VmNativeBridge {
    init {
        System.loadLibrary("avm_host")
    }

    @JvmStatic
    external fun initHost(filesDir: String, nativeLibraryDir: String, sdkInt: Int): Int

    @JvmStatic
    external fun initInstance(instanceId: String, configJson: String): Int

    @JvmStatic
    external fun startGuest(instanceId: String): Int

    @JvmStatic
    external fun stopGuest(instanceId: String): Int

    @JvmStatic
    external fun destroyInstance(instanceId: String): Int

    @JvmStatic
    external fun getInstanceState(instanceId: String): Int

    @JvmStatic
    external fun getLastError(instanceId: String): String

    @JvmStatic
    external fun getInstanceLogPath(instanceId: String): String

    @JvmStatic
    external fun getGuestProperty(instanceId: String, key: String, fallback: String): String

    @JvmStatic
    external fun setGuestPropertyOverride(instanceId: String, key: String, value: String): Int

    @JvmStatic
    external fun getBinderServiceHandle(instanceId: String, serviceName: String): Int

    @JvmStatic
    external fun getBootstrapStatus(instanceId: String): String

    @JvmStatic
    external fun writeFramebufferTestPattern(instanceId: String, frameIndex: Int): Int

    @JvmStatic
    external fun getGraphicsStats(instanceId: String): String

    @JvmStatic
    external fun getInputStats(instanceId: String): String

    @JvmStatic
    external fun resetInputQueue(instanceId: String): Int

    @JvmStatic
    external fun generateAudioTestTone(
        instanceId: String,
        sampleRate: Int,
        frames: Int,
        muted: Boolean,
    ): Int

    @JvmStatic
    external fun getAudioStats(instanceId: String): String

    @JvmStatic
    external fun resolveGuestPath(instanceId: String, guestPath: String, writeAccess: Boolean): String

    fun resolveGuestPathResult(
        instanceId: String,
        guestPath: String,
        writeAccess: Boolean,
    ): GuestPathResolution = GuestPathResolution.fromJson(
        resolveGuestPath(instanceId, guestPath, writeAccess),
    )

    @JvmStatic
    external fun openGuestPath(instanceId: String, guestPath: String, writeAccess: Boolean): Int

    @JvmStatic
    external fun readGuestFile(instanceId: String, fd: Int, maxBytes: Int): String

    @JvmStatic
    external fun writeGuestFile(instanceId: String, fd: Int, data: String): Int

    @JvmStatic
    external fun closeGuestFile(instanceId: String, fd: Int): Int

    @JvmStatic
    external fun getOpenFdCount(instanceId: String): Int

    @JvmStatic
    external fun attachSurface(
        instanceId: String,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Int

    @JvmStatic
    external fun resizeSurface(instanceId: String, width: Int, height: Int, densityDpi: Int): Int

    @JvmStatic
    external fun detachSurface(instanceId: String): Int

    @JvmStatic
    external fun sendTouch(instanceId: String, action: Int, pointerId: Int, x: Float, y: Float): Int

    @JvmStatic
    external fun sendKey(instanceId: String, action: Int, keyCode: Int, metaState: Int): Int
}
