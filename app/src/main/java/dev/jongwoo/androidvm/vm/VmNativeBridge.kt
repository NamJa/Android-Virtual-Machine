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
    external fun resolveGuestPath(instanceId: String, guestPath: String, writeAccess: Boolean): String

    fun resolveGuestPathResult(
        instanceId: String,
        guestPath: String,
        writeAccess: Boolean,
    ): GuestPathResolution = GuestPathResolution.fromJson(
        resolveGuestPath(instanceId, guestPath, writeAccess),
    )

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
