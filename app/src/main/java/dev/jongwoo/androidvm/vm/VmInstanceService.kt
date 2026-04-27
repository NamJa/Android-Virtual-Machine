package dev.jongwoo.androidvm.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.jongwoo.androidvm.R

class VmInstanceService : Service() {
    private val binder = LocalBinder()
    private var state: VmState = VmState.STOPPED

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopRuntime()
            else -> startRuntime()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        super.onDestroy()
    }

    fun currentState(): VmState = state

    private fun startRuntime() {
        if (state == VmState.RUNNING || state == VmState.STARTING) return
        state = VmState.STARTING
        startForegroundCompat()

        val preflight = RuntimePreflightCheck.run(this)
        if (preflight is RuntimePreflightResult.Blocked) {
            state = VmState.ERROR
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val config = preflight.config
        val nativeState = NativeRuntimeState.fromCode(VmNativeBridge.getInstanceState(config.instanceId))
        if (nativeState == NativeRuntimeState.RUNNING) {
            state = VmState.RUNNING
            return
        }

        val initResult = VmNativeBridge.initHost(
            filesDir.absolutePath,
            applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        val instanceResult = VmNativeBridge.initInstance(config.instanceId, config.toJson())
        val startResult = VmNativeBridge.startGuest(config.instanceId)
        state = if (initResult == 0 && instanceResult == 0 && startResult == 0) {
            VmState.RUNNING
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            VmState.ERROR
        }
    }

    private fun stopRuntime() {
        if (state == VmState.STOPPED || state == VmState.STOPPING) return
        state = VmState.STOPPING
        VmNativeBridge.stopGuest(VmConfig.DEFAULT_INSTANCE_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        state = VmState.STOPPED
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(getString(R.string.vm_notification_title))
        .setContentText(getString(R.string.vm_notification_body))
        .setOngoing(true)
        .build()

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vm_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder() {
        fun service(): VmInstanceService = this@VmInstanceService
    }

    companion object {
        private const val CHANNEL_ID = "vm-runtime"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "dev.jongwoo.androidvm.action.START_VM"
        private const val ACTION_STOP = "dev.jongwoo.androidvm.action.STOP_VM"

        fun start(context: Context) {
            val intent = Intent(context, VmInstanceService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VmInstanceService::class.java).setAction(ACTION_STOP))
        }
    }
}
