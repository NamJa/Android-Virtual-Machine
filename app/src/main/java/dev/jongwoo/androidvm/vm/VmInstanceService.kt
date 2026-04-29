package dev.jongwoo.androidvm.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.jongwoo.androidvm.R

class VmInstanceService : Service() {
    private var state: VmState = VmState.STOPPED
    private var activeInstanceId: String = VmConfig.DEFAULT_INSTANCE_ID
    private val replyMessengers = mutableSetOf<Messenger>()
    private lateinit var inboundMessenger: Messenger

    private val inboundHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VmIpc.MSG_REGISTER_REPLY -> msg.replyTo?.let { reply ->
                    synchronized(replyMessengers) { replyMessengers.add(reply) }
                    pushSnapshot(reply)
                }
                VmIpc.MSG_UNREGISTER_REPLY -> msg.replyTo?.let { reply ->
                    synchronized(replyMessengers) { replyMessengers.remove(reply) }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        inboundMessenger = Messenger(inboundHandler)
    }

    override fun onBind(intent: Intent?): IBinder = inboundMessenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }
            ?: activeInstanceId
        activeInstanceId = instanceId
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopRuntime(instanceId)
            ACTION_IMPORT_APK -> {
                startRuntime(instanceId)
                if (state == VmState.RUNNING) {
                    val staged = intent?.getStringExtra(EXTRA_STAGED_PATH).orEmpty()
                    if (staged.isNotEmpty()) {
                        VmNativeBridge.importApk(instanceId, staged)
                    }
                }
            }
            ACTION_LAUNCH_PACKAGE -> {
                startRuntime(instanceId)
                if (state == VmState.RUNNING) {
                    val pkg = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                    if (pkg.isNotEmpty()) {
                        VmNativeBridge.launchPackage(instanceId, pkg)
                    }
                }
            }
            ACTION_STOP_PACKAGE -> {
                if (state == VmState.RUNNING) {
                    VmNativeBridge.stopPackage(
                        instanceId,
                        intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty(),
                    )
                }
            }
            ACTION_UNINSTALL_PACKAGE -> {
                startRuntime(instanceId)
                if (state == VmState.RUNNING) {
                    VmNativeBridge.uninstallPackage(
                        instanceId,
                        intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty(),
                    )
                }
            }
            ACTION_CLEAR_DATA -> {
                startRuntime(instanceId)
                if (state == VmState.RUNNING) {
                    VmNativeBridge.clearPackageData(
                        instanceId,
                        intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty(),
                    )
                }
            }
            else -> startRuntime(instanceId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime(activeInstanceId)
        super.onDestroy()
    }

    fun currentState(): VmState = state

    fun currentInstanceId(): String = activeInstanceId

    private fun startRuntime(instanceId: String) {
        if (state == VmState.RUNNING || state == VmState.STARTING) return
        setState(VmState.STARTING)
        startForegroundCompat()

        val preflight = RuntimePreflightCheck.run(this, instanceId)
        if (preflight is RuntimePreflightResult.Blocked) {
            setState(VmState.ERROR)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val config = preflight.config
        val nativeState = NativeRuntimeState.fromCode(VmNativeBridge.getInstanceState(config.instanceId))
        if (nativeState == NativeRuntimeState.RUNNING) {
            setState(VmState.RUNNING)
            return
        }

        val initResult = VmNativeBridge.initHost(
            filesDir.absolutePath,
            applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        val instanceResult = VmNativeBridge.initInstance(config.instanceId, config.toJson())
        val startResult = VmNativeBridge.startGuest(config.instanceId)
        if (initResult == 0 && instanceResult == 0 && startResult == 0) {
            setState(VmState.RUNNING)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            setState(VmState.ERROR)
        }
    }

    private fun stopRuntime(instanceId: String) {
        if (state == VmState.STOPPED || state == VmState.STOPPING) return
        setState(VmState.STOPPING)
        VmNativeBridge.stopGuest(instanceId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        setState(VmState.STOPPED)
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

    private fun setState(next: VmState) {
        if (state == next) return
        state = next
        broadcastState()
    }

    private fun broadcastState() {
        val targets = synchronized(replyMessengers) { replyMessengers.toList() }
        if (targets.isEmpty()) return
        targets.forEach { messenger ->
            val msg = Message.obtain().apply {
                what = VmIpc.MSG_STATE_UPDATE
                data = VmIpc.bundle(activeInstanceId, VmIpcCodec.encodeState(activeInstanceId, state))
            }
            try {
                messenger.send(msg)
            } catch (_: RemoteException) {
                synchronized(replyMessengers) { replyMessengers.remove(messenger) }
            }
        }
    }

    private fun pushSnapshot(messenger: Messenger) {
        val msg = Message.obtain().apply {
            what = VmIpc.MSG_STATE_UPDATE
            data = VmIpc.bundle(activeInstanceId, VmIpcCodec.encodeState(activeInstanceId, state))
        }
        try {
            messenger.send(msg)
        } catch (_: RemoteException) {
            synchronized(replyMessengers) { replyMessengers.remove(messenger) }
        }
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "extra.instanceId"
        private const val CHANNEL_ID = "vm-runtime"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "dev.jongwoo.androidvm.action.START_VM"
        private const val ACTION_STOP = "dev.jongwoo.androidvm.action.STOP_VM"
        private const val ACTION_IMPORT_APK = "dev.jongwoo.androidvm.action.IMPORT_APK"
        private const val ACTION_LAUNCH_PACKAGE = "dev.jongwoo.androidvm.action.LAUNCH_PACKAGE"
        private const val ACTION_STOP_PACKAGE = "dev.jongwoo.androidvm.action.STOP_PACKAGE"
        private const val ACTION_UNINSTALL_PACKAGE = "dev.jongwoo.androidvm.action.UNINSTALL_PACKAGE"
        private const val ACTION_CLEAR_DATA = "dev.jongwoo.androidvm.action.CLEAR_PACKAGE_DATA"
        private const val EXTRA_PACKAGE_NAME = "extra.packageName"
        private const val EXTRA_STAGED_PATH = "extra.stagedPath"

        fun start(context: Context, instanceId: String = VmConfig.DEFAULT_INSTANCE_ID) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context, instanceId: String = VmConfig.DEFAULT_INSTANCE_ID) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
            context.startService(intent)
        }

        fun importApk(context: Context, instanceId: String, stagedPath: String) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_IMPORT_APK)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_STAGED_PATH, stagedPath)
            context.startForegroundService(intent)
        }

        fun launchPackage(context: Context, instanceId: String, packageName: String) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_LAUNCH_PACKAGE)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
            context.startForegroundService(intent)
        }

        fun stopPackage(context: Context, instanceId: String, packageName: String) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_STOP_PACKAGE)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
            context.startService(intent)
        }

        fun uninstallPackage(context: Context, instanceId: String, packageName: String) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_UNINSTALL_PACKAGE)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
            context.startForegroundService(intent)
        }

        fun clearPackageData(context: Context, instanceId: String, packageName: String) {
            val intent = Intent(context, VmInstanceService::class.java)
                .setAction(ACTION_CLEAR_DATA)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
            context.startForegroundService(intent)
        }
    }
}
