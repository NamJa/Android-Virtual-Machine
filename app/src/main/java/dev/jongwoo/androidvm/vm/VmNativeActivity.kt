package dev.jongwoo.androidvm.vm

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.jongwoo.androidvm.R

class VmNativeActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var instanceId: String
    private lateinit var surfaceView: SurfaceView
    private var attached = false
    private var densityDpi = 320

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        densityDpi = resources.displayMetrics.densityDpi
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }
            ?: VmConfig.DEFAULT_INSTANCE_ID

        val preflight = RuntimePreflightCheck.run(this, instanceId)
        if (preflight is RuntimePreflightResult.Blocked) {
            setContentView(createBlockedView(preflight.message))
            return
        }

        val config = preflight.config
        VmNativeBridge.initHost(
            filesDir.absolutePath,
            applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        val nativeState = NativeRuntimeState.fromCode(VmNativeBridge.getInstanceState(config.instanceId))
        if (nativeState != NativeRuntimeState.RUNNING) {
            VmNativeBridge.initInstance(config.instanceId, config.toJson())
        }
        VmInstanceService.start(this, instanceId)

        surfaceView = VmSurfaceView(this).apply {
            onInput = { event -> forwardTouch(event) }
            holder.addCallback(this@VmNativeActivity)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(createContentView())
    }

    override fun onResume() {
        super.onResume()
        if (::surfaceView.isInitialized) {
            surfaceView.requestFocus()
        }
    }

    override fun onPause() {
        VmNativeBridge.resetInputQueue(instanceId)
        super.onPause()
    }

    override fun onDestroy() {
        if (attached) {
            VmNativeBridge.detachSurface(instanceId)
            attached = false
        }
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachOrResize(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        attachOrResize(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VmNativeBridge.detachSurface(instanceId)
        attached = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        VmNativeBridge.sendKey(instanceId, event.action, event.keyCode, event.metaState)
        return super.dispatchKeyEvent(event)
    }

    private fun createContentView(): FrameLayout {
        val root = FrameLayout(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val status = TextView(this).apply {
            text = getString(R.string.vm_display_label)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            setPadding(24, 12, 24, 12)
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        return root
    }

    private fun createBlockedView(message: String): TextView = TextView(this).apply {
        text = getString(R.string.vm_runtime_blocked, message)
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF111111.toInt())
    }

    private fun attachOrResize(holder: SurfaceHolder, width: Int = surfaceView.width, height: Int = surfaceView.height) {
        if (width <= 0 || height <= 0) return
        if (!attached) {
            VmNativeBridge.attachSurface(instanceId, holder.surface, width, height, densityDpi)
            attached = true
        } else {
            VmNativeBridge.resizeSurface(instanceId, width, height, densityDpi)
        }
    }

    private fun forwardTouch(event: MotionEvent) {
        for (index in 0 until event.pointerCount) {
            VmNativeBridge.sendTouch(
                instanceId,
                event.actionMasked,
                event.getPointerId(index),
                event.getX(index),
                event.getY(index),
            )
        }
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "extra.instanceId"
    }
}

private class VmSurfaceView(context: Context) : SurfaceView(context) {
    var onInput: ((MotionEvent) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onInput?.invoke(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
