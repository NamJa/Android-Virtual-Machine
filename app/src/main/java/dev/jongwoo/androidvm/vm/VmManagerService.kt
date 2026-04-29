package dev.jongwoo.androidvm.vm

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.PathLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase A orchestrator for VM instances. Holds the authoritative per-instance [VmState] in the
 * default process, persists it across restarts via [VmRuntimeStateStore], and brokers lifecycle
 * commands to [VmInstanceService] (which lives in the `:vm1` process). UI binds to the
 * [LocalBinder] and observes [observe] to react to state changes.
 *
 * Cross-process state reconciliation goes through the [VmIpc] Messenger contract: [VmInstanceService]
 * owns the authoritative runtime state in `:vm1` and pushes every transition back to this manager.
 */
class VmManagerService : Service() {
    private val binder = LocalBinder()
    private val states = MutableStateFlow<Map<String, VmState>>(emptyMap())
    private val knownInstances = linkedSetOf<String>()
    private lateinit var store: VmRuntimeStateStore
    private var instanceMessenger: Messenger? = null
    private lateinit var replyMessenger: Messenger
    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VmIpc.MSG_STATE_UPDATE -> {
                    val payload = msg.data?.getString(VmIpc.KEY_PAYLOAD_JSON) ?: return
                    val (id, state) = VmIpcCodec.decodeState(payload) ?: return
                    updateState(id, state, persist = true, push = false)
                }
                else -> super.handleMessage(msg)
            }
        }
    }
    private val instanceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val messenger = service?.let { Messenger(it) } ?: return
            instanceMessenger = messenger
            registerReply(messenger)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            instanceMessenger = null
            // The :vm1 process has died — anything we believed was RUNNING/STARTING is no longer
            // accurate. Fold it back to STOPPED so the UI does not display stale state.
            reconcileAfterInstanceDeath()
        }
    }

    override fun onCreate() {
        super.onCreate()
        replyMessenger = Messenger(replyHandler)
        val pathLayout = PathLayout(applicationContext)
        pathLayout.ensureRoot()
        store = VmRuntimeStateStore(pathLayout.runtimeStateFile)
        val instanceStore = InstanceStore(applicationContext)
        // Make sure the default instance directory exists before we start observing it; the rest
        // of the app already does this on cold start, but the manager must be safe to bind first.
        instanceStore.ensureDefaultConfig()

        val persisted = store.load()
        val merged = linkedMapOf<String, VmState>()
        instanceStore.list().forEach { id ->
            merged[id] = persisted[id] ?: VmState.STOPPED
        }
        persisted.forEach { (id, state) ->
            if (id !in merged) merged[id] = state
        }
        knownInstances.addAll(merged.keys)
        states.value = merged
        bindInstanceServiceIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        instanceMessenger?.let { unregisterReply(it) }
        runCatching { unbindService(instanceConnection) }
        super.onDestroy()
    }

    private fun bindInstanceServiceIfNeeded() {
        if (instanceMessenger != null) return
        runCatching {
            val intent = Intent(this, VmInstanceService::class.java)
            bindService(intent, instanceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun registerReply(messenger: Messenger) {
        val msg = Message.obtain().apply {
            what = VmIpc.MSG_REGISTER_REPLY
            replyTo = replyMessenger
        }
        try {
            messenger.send(msg)
        } catch (_: RemoteException) {
            instanceMessenger = null
        }
    }

    private fun unregisterReply(messenger: Messenger) {
        val msg = Message.obtain().apply {
            what = VmIpc.MSG_UNREGISTER_REPLY
            replyTo = replyMessenger
        }
        runCatching { messenger.send(msg) }
    }

    fun start(instanceId: String): Boolean {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        updateState(instanceId, VmState.STARTING)
        VmInstanceService.start(applicationContext, instanceId)
        bindInstanceServiceIfNeeded()
        return true
    }

    fun stop(instanceId: String): Boolean {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        updateState(instanceId, VmState.STOPPING)
        VmInstanceService.stop(applicationContext, instanceId)
        return true
    }

    fun status(instanceId: String): VmState =
        states.value[instanceId] ?: VmState.STOPPED

    fun observe(): StateFlow<Map<String, VmState>> = states.asStateFlow()

    fun listInstances(): List<String> = states.value.keys.toList()

    fun updateState(instanceId: String, state: VmState) {
        updateState(instanceId, state, persist = true, push = false)
    }

    private fun updateState(instanceId: String, state: VmState, persist: Boolean, push: Boolean) {
        synchronized(this) {
            knownInstances.add(instanceId)
            val current = states.value.toMutableMap()
            current[instanceId] = state
            states.value = current.toMap()
            if (persist) runCatching { store.save(current) }
        }
        // `push` is reserved for forwarding manager-originated commands back over the contract;
        // currently the manager only consumes pushes, so this stays false.
        if (push) Unit
    }

    private fun reconcileAfterInstanceDeath() {
        synchronized(this) {
            val current = states.value.toMutableMap()
            var changed = false
            current.entries.forEach { entry ->
                if (entry.value == VmState.STARTING || entry.value == VmState.RUNNING) {
                    entry.setValue(VmState.STOPPED)
                    changed = true
                }
            }
            if (changed) {
                states.value = current.toMap()
                runCatching { store.save(current) }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun start(instanceId: String): Boolean = this@VmManagerService.start(instanceId)
        fun stop(instanceId: String): Boolean = this@VmManagerService.stop(instanceId)
        fun status(instanceId: String): VmState = this@VmManagerService.status(instanceId)
        fun observe(): StateFlow<Map<String, VmState>> = this@VmManagerService.observe()
        fun listInstances(): List<String> = this@VmManagerService.listInstances()
        fun markRunning(instanceId: String) {
            this@VmManagerService.updateState(instanceId, VmState.RUNNING)
        }
        fun markStopped(instanceId: String) {
            this@VmManagerService.updateState(instanceId, VmState.STOPPED)
        }
    }

    companion object {
        fun bind(context: Context, connection: ServiceConnection): Boolean {
            val intent = Intent(context, VmManagerService::class.java)
            return context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
}
