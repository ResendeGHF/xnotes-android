package com.xnotes.share.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xnotes.R
import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.share.ShareBackendImpl
import com.xnotes.share.ShareCrypto
import com.xnotes.ui.Editor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host side of SYNC.md v1, in the foreground while sharing is on: dials the
 * Linux companion over mutual-TLS, serves the shared index and file bytes,
 * applies companion ops to open documents (or straight to files), broadcasts
 * local commits, and streams live strokes both ways with no batching.
 *
 * Everything runs off the main thread except model access, which hops there.
 */
class CompanionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var client: CompanionClient? = null
    private var pairing: PairingClient? = null
    private var engine: HostEngine? = null
    private var backend: ShareBackendImpl? = null
    private var identity: ShareCrypto.ServerIdentity? = null
    private var pins: CompanionPins? = null
    private var store: com.xnotes.share.db.ShareStore? = null
    private var grants: com.xnotes.share.db.DeviceGrants? = null
    private var deviceName: String = "Android"
    private var targetHost: String = "127.0.0.1"
    private var targetPort: Int = 42317
    private var starting = AtomicBoolean(false)
    private var coreReady = false
    private val approvals = HashMap<String, (Boolean) -> Unit>()
    private var approvalSeq = 0

    // --- Android-to-Android (multi-peer) ---

    /** Versions shared by every host engine serving this device's files. */
    private var sharedVersions: SyncVersions? = null
    private var listener: AndroidListener? = null
    private var guestCacheDir: File? = null
    private val peerLock = Any()
    private val peerSessions = HashMap<String, PeerSession>()
    private val guestSessions = HashMap<String, GuestSession>()
    /** Display names by peer fp (Room lookup is async; UI needs it now). */
    private val peerNames = HashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        runningService = this
        CompanionEditors.onTracked = { editor, uri ->
            if (engine == null) {
                // Session not up yet; the startSession sweep wires everything tracked.
            } else if (uri == null) {
                detachEditor(editor)
            } else {
                attachEditor(editor, uri)
            }
        }
    }

    override fun onDestroy() {
        runningService = null
        CompanionEditors.onTracked = null
        _active.value = false
        _companionOn.value = false
        _sharingOn.value = false
        shutdown()
        _status.value = Status("Off", null, false)
        _companionStatus.value = Status("Off", null, false)
        _sharingStatus.value = Status("Off", null, false)
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_COMPANION)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_SHARING)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_COMPANION -> {
                setCompanionEnabled(this, false)
                return START_NOT_STICKY
            }
            ACTION_STOP_SHARING -> {
                setSharingEnabled(this, false)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                // Legacy single stop button: turn both halves off.
                setCompanionEnabled(this, false)
                setSharingEnabled(this, false)
                return START_NOT_STICKY
            }
            ACTION_APPROVAL -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
                val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
                synchronized(approvals) { approvals.remove(token) }?.invoke(allow)
                // Approval taps must never keep a switched-off service alive.
                if (!coreReady && !isCompanionOn(this) && !isSharingOn(this)) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SAS -> {
                val key = intent.getStringExtra(EXTRA_SAS_KEY) ?: return START_NOT_STICKY
                if (intent.getBooleanExtra(EXTRA_ALLOW, false)) confirmInboundSas(this, key)
                else denyInboundSas(this, key)
                if (!coreReady && !isCompanionOn(this) && !isSharingOn(this)) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                engine?.refreshPolicy()
                synchronized(peerLock) { peerSessions.values.forEach { it.engine.refreshPolicy() } }
                if (!coreReady && !isCompanionOn(this) && !isSharingOn(this)) stopSelf()
                return START_NOT_STICKY
            }
        }
        // Boot (or system restart): only come up when at least one feature
        // is switched on. Otherwise stop immediately — no phantom notification.
        if (!isCompanionOn(this) && !isSharingOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureCore { applyEnabledFeatures() }
        return START_STICKY
    }

    // --- session ---

    /**
     * One-time process setup: identity, store, backend, engines and the
     * editor bridge. Idempotent; network halves start separately in
     * [applyCompanionState]/[applySharingState].
     */
    private fun ensureCore(onReady: () -> Unit = {}) {
        if (coreReady) {
            main.post { onReady() }
            return
        }
        synchronized(coreQueue) { coreQueue.add(onReady) }
        if (!starting.compareAndSet(false, true)) return
        // Claim foreground immediately: startForegroundService requires a
        // notification within seconds, long before Room/crypto finish.
        runCatching {
            val compOn = isCompanionOn(this)
            val id = if (compOn || !isSharingOn(this)) NOTIF_COMPANION else NOTIF_SHARING
            ServiceCompat.startForeground(this, id, startingNotification(), fgsType())
        }
        Thread({
            try {
                val identity = ShareCrypto.loadOrCreate(filesDir)
                val shareDir = File(filesDir, "share").apply { mkdirs() }
                val pins = CompanionPins.of(shareDir)
                val store = com.xnotes.share.db.ShareStore.of(applicationContext)
                val grants = com.xnotes.share.db.DeviceGrants.of(applicationContext)
                val backend = ShareBackendImpl(applicationContext)
                val prefs = getSharedPreferences(PREFS, 0)
                // One-time import from the pre-Room files; afterwards Room owns devices + grants.
                kotlinx.coroutines.runBlocking {
                    store.migrateLegacy(
                        prefs.getString(KEY_IP, "") ?: "",
                        prefs.getString(KEY_PORT, "42317")?.toIntOrNull() ?: 0,
                    )
                }
                val deviceName = prefs.getString(KEY_NAME, "")?.ifBlank { null }
                    ?: android.os.Build.MODEL ?: "Android"
                this.deviceName = deviceName
                this.targetHost = prefs.getString(KEY_IP, "")?.ifEmpty { null } ?: "127.0.0.1"
                this.targetPort = prefs.getString(KEY_PORT, "42317")?.toIntOrNull() ?: 42317
                this.identity = identity
                this.pins = pins
                this.store = store
                this.grants = grants
                val versions = SyncVersions(shareDir)
                this.sharedVersions = versions
                val host = hostBridge { msg -> client?.send(msg) }
                val engine = HostEngine(
                    backend, BackendFiles(backend), grants, shareDir, host, deviceName,
                    imageDir = File(cacheDir, "sync-images").apply { mkdirs() },
                    store = versions,
                )
                val ui = object : PairingUi {
                    override fun showCodeEntry() {
                        _codeNeeded.value = true
                        postCompanionStatus("Enter the code shown on the companion.")
                    }
                    override fun showStatus(s: String) = postCompanionStatus(s)
                    override fun pairingDone(name: String) {
                        starting.set(false)
                        _codeNeeded.value = false
                        notifyUp(name, targetPort)
                    }

                    override fun pairingFailed(reason: String) {
                        _codeNeeded.value = false
                        postCompanionStatus(reason)
                    }
                }
                this.backend = backend
                this.engine = engine
                this.ui = ui
                _active.value = isCompanionOn(this) || isSharingOn(this)
                coreReady = true
                starting.set(false)
                main.post {
                    _companionOn.value = isCompanionOn(this)
                    _sharingOn.value = isSharingOn(this)
                    _active.value = _companionOn.value || _sharingOn.value
                    // Files opened before the session started never fired onTracked
                    // while the engine existed: wire them now.
                    for (e in CompanionEditors.editors()) {
                        val uri = CompanionEditors.uriFor(e) ?: continue
                        attachEditor(e, uri)
                    }
                    applyEnabledFeatures()
                    onReady()
                    drainCoreQueue()
                }
            } catch (e: Exception) {
                postCompanionStatus("Start failed: ${e.message}")
                starting.set(false)
                stopSelfIfIdle()
            }
        }, "companion-start").apply { isDaemon = true; start() }
    }

    /** Start every half whose flag is on; called once core is ready. */
    private fun applyEnabledFeatures() {
        if (isCompanionOn(this)) startCompanionDial() else stopCompanionDial()
        if (isSharingOn(this)) startSharingNet() else stopSharingNet()
        updateForeground()
    }

    /** No-op unless the core is up; used by passive paths that must never boot the service. */
    private fun runIfCoreReady(fn: () -> Unit) {
        if (coreReady) {
            main.post { fn() }
            return
        }
        synchronized(coreQueue) { coreQueue.add { main.post { fn() } } }
    }

    private val coreQueue = ArrayList<() -> Unit>()

    /** Drain queued core waiters (runs on the main thread). */
    private fun drainCoreQueue() {
        val pending = synchronized(coreQueue) {
            if (coreQueue.isEmpty()) return
            ArrayList(coreQueue).also { coreQueue.clear() }
        }
        for (fn in pending) runCatching { fn() }
    }

    /** Stop the service when neither feature is on; otherwise refresh notifications. */
    private fun stopSelfIfIdle() {
        main.post {
            if (!isCompanionOn(this) && !isSharingOn(this)) {
                stopSelf()
            } else {
                updateForeground()
            }
        }
    }

    private var ui: PairingUi? = null

    /**
     * Document-access bridge for a [HostEngine], sending over [sendFn]. One
     * instance serves the dialed Linux companion; each inbound Android peer
     * gets its own with its connection's sender. All engines share the
     * [SyncVersions] store and the open-document registry, so concurrent
     * editors of one file see one version.
     */
    private fun hostBridge(sendFn: (JSONObject) -> Unit): HostEngine.HostEngineHost =
        object : HostEngine.HostEngineHost {
            override fun send(msg: JSONObject) = sendFn(msg)

            override fun openDocument(uri: String) =
                CompanionEditors.editorFor(uri)?.state?.document

            override fun snapshotBytes(doc: com.xnotes.core.model.Document): ByteArray? =
                CompanionEditors.editors().firstOrNull { it.state.document === doc }?.syncSnapshotBytes()

            override fun repaintDocument(uri: String) {
                main.post { CompanionEditors.editorFor(uri)?.syncRepaint() }
            }

            override fun repaintLive(uri: String) {
                main.post {
                    val editor = CompanionEditors.editorFor(uri)
                    editor?.syncLiveRender(editor.controller.remoteLive?.dirtyViewportRect())
                }
            }

            override fun editorState(uri: String): com.xnotes.canvas.CanvasState? =
                CompanionEditors.editorFor(uri)?.state

            override fun structureChanged(uri: String) {
                main.post { CompanionEditors.editorFor(uri)?.syncStructureChanged() }
            }

            override fun previewRoom(uri: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) {
                main.post {
                    val editor = CompanionEditors.editorFor(uri) ?: return@post
                    if (editor.controller.roomGestureActive) return@post
                    editor.controller.previewRemoteRoom(page, sideways, split, resume, shift)
                }
            }

            override fun clearRoomPreview(uri: String) {
                main.post { CompanionEditors.editorFor(uri)?.controller?.clearRemoteRoom() }
            }

            override fun applyRoomEdit(uri: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean {
                val editor = CompanionEditors.editorFor(uri) ?: return false
                if (!editor.controller.applyRemoteRoomEdit(page, sideways, open, a, b)) return false
                editor.syncStructureChanged()
                return true
            }

            override fun liveOverlay(uri: String): RemoteLiveStrokes? =
                CompanionEditors.editorFor(uri)?.controller?.remoteLive

            override fun onApproval(kind: String, device: String, name: String, respond: (Boolean) -> Unit) {
                askApproval(kind, device, name, respond)
            }

            override fun onError(reason: String) {
                main.post {
                    _companionStatus.value = Status(reason, _companionStatus.value.paired, connected = false)
                    _sharingStatus.value = Status(reason, _sharingStatus.value.paired, _sharingStatus.value.connected)
                    mirrorStatus()
                }
            }
        }

    /** (Re)connect with the given companion address. Safe to call any time. */
    fun reconnectTo(host: String, port: Int) {
        targetHost = host
        targetPort = port
        dialDevice(null)
    }

    /** Dial the active companion (or the remembered raw address when none is set). */
    private fun dialActive() {
        val store = store ?: return
        Thread({
            runCatching {
                val active = kotlinx.coroutines.runBlocking { store.activeDeviceOnce() }
                val dev = if (active == null) null
                    else kotlinx.coroutines.runBlocking { store.device(active.fp) }
                main.post { dialDevice(dev) }
            }
        }, "companion-dial").apply { isDaemon = true; start() }
    }

    /**
     * Dial one companion by fingerprint, or an ad-hoc address when [fp] is null
     * (pairing then registers it). The previous session is torn down first, so
     * at most one companion is ever connected. Blocking store reads happen off
     * the main thread; the actual dial runs on it.
     */
    private fun dialStored(fp: String?) {
        val store = store ?: return
        Thread({
            runCatching {
                val dev = if (fp == null) null else kotlinx.coroutines.runBlocking { store.device(fp) }
                main.post { dialDevice(dev) }
            }
        }, "companion-dial").apply { isDaemon = true; start() }
    }

    private fun dialDevice(dev: com.xnotes.share.db.DeviceEntity?) {
        val identity = identity ?: return
        val pins = pins ?: return
        val store = store ?: return
        val grants = grants ?: return
        if (store == null || grants == null) return
        runCatching { pairing?.close() }
        runCatching { client?.disconnect() }
        pairing = null
        client = null
        val host = if (!dev?.host.isNullOrEmpty()) dev.host else targetHost
        val port = if (dev != null && dev.port in 1..65535) dev.port else targetPort
        if (dev != null) {
            targetHost = host
            targetPort = port
            getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_IP, host).putString(KEY_PORT, port.toString()).apply()
        }
        val sender = PairingClient.Sender { msg -> client?.send(msg) }
        val pairing = PairingClient(deviceName, identity.fingerprintHex, pins, ui ?: return, sender)
        pairing.listenPort = listener?.boundPort ?: 0
        var sasPeer = false
        // An Android peer answers hello_ack in SAS mode; surface it the same way.
        pairing.onSasPeer = { sas, name ->
            sasPeer = true
            _sasOutbound.value = SasRequest(dev?.fp ?: "$host:$port", name, sas, inbound = false)
        }
        pairing.onPaired = { name ->
            val peerFp = pairing.peerFingerprint
            if (peerFp != null) {
                val wasSas = sasPeer
                Thread({
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            store.remember(peerFp, name, host, port, kind = if (wasSas) com.xnotes.share.db.DeviceKind.PHONE else null)
                            // First companion ever becomes active; otherwise the
                            // active one keeps its slot. SAS (phone) peers must
                            // never take the companion dial slot.
                            if (!wasSas && store.activeDeviceOnce() == null) {
                                store.setActive(peerFp)
                            }
                        }
                        grants.activeFp = peerFp
                        kotlinx.coroutines.runBlocking { grants.refreshBlocking(peerFp) }
                        val dev = kotlinx.coroutines.runBlocking { store.device(peerFp) }
                        main.post {
                            _companionStatus.value = Status("Connected to ${dev?.displayName ?: name}.", dev?.displayName ?: name, connected = true)
                            mirrorStatus()
                        }
                    }
                }, "companion-remember").apply { isDaemon = true; start() }
            } else {
                main.post {
                    _companionStatus.value = Status("Connected to $name.", name, connected = true)
                    mirrorStatus()
                }
            }
        }
        val client = CompanionClient(
            host = host,
            port = port,
            identity = identity,
            // Per-device pin: a known companion must present its pinned cert;
            // an unknown address uses TOFU (the 6-digit code + desktop approval
            // is the actual authentication) so a second companion can pair.
            pinnedFp = dev?.fp,
            autoReconnect = true,
        )
        client.onConnected = { peerFp ->
            Thread({
                runCatching {
                    val known = kotlinx.coroutines.runBlocking { store.device(peerFp) }
                    if (known != null) {
                        kotlinx.coroutines.runBlocking { store.touch(peerFp, host, port, known.name) }
                        grants.activeFp = peerFp
                        kotlinx.coroutines.runBlocking { grants.refreshBlocking(peerFp) }
                    }
                    val label = known?.displayName ?: pins.nameFor(peerFp)
                    main.post {
                        _companionStatus.value = Status("Connected. Pairing…", label, connected = true)
                        mirrorStatus()
                        pairing.onConnected(peerFp)
                    }
                }
            }, "companion-touch").apply { isDaemon = true; start() }
        }
        client.onMessage = { msg -> main.post { route(msg) } }
        client.onDisconnected = { willRetry ->
            main.post {
                if (!willRetry) {
                    _companionStatus.value = Status("Disconnected.", _companionStatus.value.paired, connected = false)
                    mirrorStatus()
                }
            }
        }
        client.onError = { err -> postCompanionStatus(err) }
        this.pairing = pairing
        this.client = client
        main.post {
            val label = dev?.displayName ?: pins.all().firstOrNull()?.name
            _companionStatus.value = Status("Connecting…", label, connected = false)
            mirrorStatus()
        }
        client.connect()
    }

    private fun postCompanionStatus(s: String) {
        main.post {
            _companionStatus.value = Status(s, _companionStatus.value.paired, _companionStatus.value.connected)
            mirrorStatus()
        }
    }

    private fun postSharingStatus(s: String) {
        main.post {
            _sharingStatus.value = Status(s, _sharingStatus.value.paired, _sharingStatus.value.connected)
            mirrorStatus()
        }
    }

    /** Legacy combined status mirrors the enabled half (companion wins when both on). */
    private fun mirrorStatus() {
        _status.value = if (isCompanionOn(this)) _companionStatus.value else _sharingStatus.value
    }

    private fun postStatus(s: String) {
        // Old single-half callers (now split below); default to companion to
        // preserve prior behavior until each site is repointed.
        postCompanionStatus(s)
    }

    private fun route(msg: JSONObject) {
        runCatching {
            when (msg.optString("t")) {
                "hello_ack", "pair_result" -> pairing?.onMessage(msg)
                else -> engine?.onMessage(msg)
            }
        }
    }

    // --- Android peers: inbound hosting ---

    private class PeerSession(
        val fp: String,
        val conn: AndroidListener.PeerConn,
        val handshake: HostHandshake,
        val engine: HostEngine,
        val grants: PeerGrants,
    )

    private class GuestSession(
        val key: String,
        val host: String,
        val port: Int,
        val client: CompanionClient,
        val pairing: PairingClient,
        val engine: GuestEngine,
        var fp: String? = null,
    )

    /** Start the inbound TLS listener (auto-increment port when busy). */
    private fun startListener() {
        val identity = identity ?: return
        guestCacheDir = File(cacheDir, "guest").apply { mkdirs() }
        guestCachePrefix = "file://" + guestCacheDir!!.absolutePath
        val l = AndroidListener(identity)
        l.onPeer = { fp, conn -> main.post { onPeer(fp, conn) } }
        l.onMessage = { fp, msg -> main.post { onPeerMessage(fp, msg) } }
        l.onLost = { fp -> main.post { onPeerLost(fp) } }
        try {
            l.start()
            listener = l
            _listenPort.value = l.boundPort
            postSharingStatus("Listening for phones on port ${l.boundPort}.")
        } catch (e: Exception) {
            postSharingStatus("Phone listener failed: ${e.message}")
        }
    }

    private fun onPeer(fp: String, conn: AndroidListener.PeerConn) {
        val store = store ?: return
        val pins = pins ?: return
        val backend = backend ?: return
        val versions = sharedVersions ?: return
        val identity = identity ?: return
        val key = fp.lowercase()
        synchronized(peerLock) { peerSessions.remove(key) }?.let { runCatching { it.handshake.close() } }
        val grants = PeerGrants(store, key)
        val handshake = HostHandshake(
            deviceName, identity.fingerprintHex, pins,
            object : HostHandshake.Events {
                override fun showSasApproval(peerFp: String, deviceName: String, sas: String) {
                    _sasInbound.value = SasRequest(key, deviceName, sas, inbound = true)
                    askSasApproval(key, deviceName, sas)
                }
                override fun pairingDone(peerFp: String, name: String) {
                    Thread({
                        val port = synchronized(peerLock) {
                            peerSessions[key]?.handshake?.listenPortFor(key) ?: 0
                        }
                        synchronized(peerLock) { peerNames[key] = name }
                        runCatching {
                            kotlinx.coroutines.runBlocking {
                                store.remember(key, name, conn.remoteHost, port, kind = com.xnotes.share.db.DeviceKind.PHONE)
                                grants.refresh()
                            }
                        }
                        main.post {
                            peerSessions[key]?.engine?.refreshPolicy()
                            _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                            publishPeers()
                            postSharingStatus("$name connected.")
                        }
                    }, "peer-remember").apply { isDaemon = true; start() }
                }
                override fun pairingFailed(peerFp: String, reason: String) {
                    _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                    postSharingStatus("Pairing failed: $reason")
                    publishPeers()
                }
                override fun showStatus(s: String) = postSharingStatus(s)
            },
            HostHandshake.Sender { conn.send(it) },
        )
        val eng = HostEngine(
            backend, BackendFiles(backend), grants, File(filesDir, "share"),
            hostBridge { conn.send(it) }, deviceName,
            imageDir = File(cacheDir, "sync-images").apply { mkdirs() },
            store = versions,
        )
        synchronized(peerLock) { peerSessions[key] = PeerSession(key, conn, handshake, eng, grants) }
        Thread({
            runCatching { kotlinx.coroutines.runBlocking { grants.refresh() } }
            main.post {
                peerSessions[key]?.engine?.refreshPolicy()
                publishPeers()
            }
        }, "peer-grants").apply { isDaemon = true; start() }
        publishPeers()
    }

    private fun onPeerMessage(fp: String, msg: JSONObject) {
        val s = synchronized(peerLock) { peerSessions[fp.lowercase()] } ?: return
        runCatching { s.handshake.onMessage(fp, msg) }
        runCatching { s.engine.onMessage(msg) }
    }

    private fun onPeerLost(fp: String) {
        val key = fp.lowercase()
        synchronized(peerLock) { peerSessions.remove(key) }?.let { runCatching { it.handshake.close() } }
        _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
        publishPeers()
    }

    private fun publishPeers() {
        val s = store ?: return
        Thread({
            val devs = runCatching {
                kotlinx.coroutines.runBlocking { s.devicesOnce() }
            }.getOrDefault(emptyList())
            val byFp = devs.associateBy { it.fp.lowercase() }
            val connected = synchronized(peerLock) { peerSessions.keys.toSet() }
            // Known phones (paired before, currently offline) stay listed so the
            // user can redial them as a guest; inbound ones show connected.
            val fps = (byFp.keys + connected).sorted()
            main.post {
                _peers.value = fps.mapNotNull { fp ->
                    val dev = byFp[fp]
                    val kind = dev?.kind ?: com.xnotes.share.db.DeviceKind.PHONE
                    if (kind != com.xnotes.share.db.DeviceKind.PHONE) return@mapNotNull null
                    PeerInfo(fp, dev?.displayName ?: "phone", fp in connected, kind)
                }
            }
        }, "peers-publish").apply { isDaemon = true; start() }
    }

    // --- Android peers: outbound guest sessions ---

    private fun guestBridge(): GuestEngine.GuestHost = object : GuestEngine.GuestHost {
        override fun openDocument(cacheUri: String) =
            CompanionEditors.editorFor(cacheUri)?.state?.document
        override fun repaintDocument(cacheUri: String) {
            main.post { CompanionEditors.editorFor(cacheUri)?.syncRepaint() }
        }
        override fun repaintLive(cacheUri: String) {
            main.post {
                val editor = CompanionEditors.editorFor(cacheUri)
                editor?.syncLiveRender(editor.controller.remoteLive?.dirtyViewportRect())
            }
        }
        override fun editorState(cacheUri: String): com.xnotes.canvas.CanvasState? =
            CompanionEditors.editorFor(cacheUri)?.state
        override fun structureChanged(cacheUri: String) {
            main.post { CompanionEditors.editorFor(cacheUri)?.syncStructureChanged() }
        }
        override fun previewRoom(cacheUri: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) {
            main.post {
                val editor = CompanionEditors.editorFor(cacheUri) ?: return@post
                if (editor.controller.roomGestureActive) return@post
                editor.controller.previewRemoteRoom(page, sideways, split, resume, shift)
            }
        }
        override fun clearRoomPreview(cacheUri: String) {
            main.post { CompanionEditors.editorFor(cacheUri)?.controller?.clearRemoteRoom() }
        }
        override fun applyRoomEdit(cacheUri: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double): Boolean {
            val editor = CompanionEditors.editorFor(cacheUri) ?: return false
            if (!editor.controller.applyRemoteRoomEdit(page, sideways, open, a, b)) return false
            editor.syncStructureChanged()
            return true
        }
        override fun liveOverlay(cacheUri: String): RemoteLiveStrokes? =
            CompanionEditors.editorFor(cacheUri)?.controller?.remoteLive
        override fun onIndexChanged(hostFp: String) {
            val eng = synchronized(peerLock) {
                guestSessions.values.firstOrNull { it.fp == hostFp }?.engine
            } ?: return
            _guestIndex.value = _guestIndex.value + (hostFp to eng.remoteIndex())
            publishGuests()
        }
        override fun onFileReady(hostFp: String, remoteId: String, cacheUri: String, version: Long, openNow: Boolean) {
            if (openNow) {
                main.post {
                    CompanionService.onOpenGuestFile?.invoke(cacheUri)
                        ?: postSharingStatus("Shared file is ready.")
                }
            }
        }
        override fun onResult(hostFp: String, ok: Boolean, reason: String) {
            postSharingStatus(if (ok) "Done." else "Declined${if (reason.isNotEmpty()) ": $reason" else "."}")
        }
        override fun onLinkResult(hostFp: String, remoteId: String, ok: Boolean, reason: String) {
            if (!ok) {
                postSharingStatus("No access${if (reason.isNotEmpty()) ": $reason" else "."}")
                return
            }
            // Granted: the pushed index now covers the target; fetch and open it.
            val eng = synchronized(peerLock) {
                guestSessions.values.firstOrNull { it.fp == hostFp }?.engine
            } ?: return
            eng.fetch(remoteId, openNow = true)
        }
        override fun onError(reason: String) = postSharingStatus(reason)
    }

    /**
     * Dial an Android peer as guest. Several may be connected at once; each
     * gets its own TLS client, SAS pairing and [GuestEngine].
     */
    private fun startGuestSession(host: String, port: Int) {
        val identity = identity ?: return
        val pins = pins ?: return
        val store = store ?: return
        val cacheRoot = guestCacheDir ?: return
        val key = "$host:$port"
        synchronized(peerLock) { guestSessions.remove(key) }?.let {
            runCatching { it.pairing.close() }
            runCatching { it.client.disconnect() }
        }
        val scoped = File(cacheRoot, key.replace(Regex("[^A-Za-z0-9.-]"), "_")).apply { mkdirs() }
        val client = CompanionClient(host, port, identity, pinnedFp = null, autoReconnect = true)
        val engine = GuestEngine("", GuestEngine.Sender { client.send(it) }, guestBridge(), scoped, key)
        engine.isConnected = { client.connected }
        val sender = PairingClient.Sender { msg -> client.send(msg) }
        val pairing = PairingClient(deviceName, identity.fingerprintHex, pins, guestPairingUi(key), sender)
        pairing.listenPort = listener?.boundPort ?: 0
        pairing.onSasPeer = { sas, name -> _sasOutbound.value = SasRequest(key, name, sas, inbound = false) }
        pairing.onPaired = { name -> onGuestPaired(key, name) }
        val session = GuestSession(key, host, port, client, pairing, engine)
        // Route through main like the Linux path (engines assume main-thread calls).
        client.onConnected = { fp ->
            main.post {
                engine.hostFp = fp.lowercase()
                session.fp = fp.lowercase()
                pairing.onConnected(fp)
                publishGuests()
            }
        }
        client.onMessage = { msg -> main.post { guestRoute(session, msg) } }
        client.onDisconnected = { willRetry ->
            main.post {
                if (!willRetry) {
                    _guestIndex.value = _guestIndex.value - (session.fp ?: key)
                    publishGuests()
                }
                session.engine.onDisconnected(!willRetry)
            }
        }
        client.onError = { err -> postSharingStatus(err) }
        synchronized(peerLock) { guestSessions[key] = session }
        rememberGuestTarget(key)
        publishGuests()
        client.connect()
    }

    private fun guestRoute(s: GuestSession, msg: JSONObject) {
        runCatching {
            when (msg.optString("t")) {
                "hello_ack", "pair_result" -> s.pairing.onMessage(msg)
                else -> s.engine.onMessage(msg)
            }
        }
    }

    private fun guestPairingUi(key: String): PairingUi = object : PairingUi {
        override fun showCodeEntry() = Unit
        override fun showStatus(s: String) = postSharingStatus(s)
        override fun pairingDone(name: String) {
            _sasOutbound.value?.takeIf { it.key == key }?.let { _sasOutbound.value = null }
            postSharingStatus("Paired with $name.")
        }
        override fun pairingFailed(reason: String) {
            _sasOutbound.value?.takeIf { it.key == key }?.let { _sasOutbound.value = null }
            postSharingStatus("Pairing failed: $reason")
        }
    }

    private fun onGuestPaired(key: String, name: String) {
        val s = synchronized(peerLock) { guestSessions[key] } ?: return
        val fp = s.pairing.peerFingerprint ?: return
        s.fp = fp.lowercase()
        synchronized(peerLock) { peerNames[fp.lowercase()] = name }
        s.engine.peerName = name
        s.engine.start()
        Thread({
            runCatching {
                kotlinx.coroutines.runBlocking { store?.remember(fp.lowercase(), name, s.host, s.port, kind = com.xnotes.share.db.DeviceKind.PHONE) }
            }
            main.post { publishGuests() }
        }, "guest-remember").apply { isDaemon = true; start() }
        publishGuests()
    }

    private fun publishGuests() {
        main.post {
            val idx = _guestIndex.value
            _guests.value = synchronized(peerLock) {
                guestSessions.values.map { s ->
                    val fp = s.fp
                    GuestInfo(
                        key = s.key,
                        fp = fp,
                        name = synchronized(peerLock) { fp?.let { peerNames[it] } } ?: s.engine.peerName,
                        connected = fp != null,
                        fileCount = fp?.let { idx[it]?.size } ?: 0,
                    )
                }
            }
        }
    }

    /** Notification approval for an inbound SAS request (works screen-off). */
    private fun askSasApproval(key: String, device: String, sas: String) {
        val allow = PendingIntent.getService(
            this, key.hashCode(), Intent(this, CompanionService::class.java).setAction(ACTION_SAS)
                .putExtra(EXTRA_SAS_KEY, key).putExtra(EXTRA_ALLOW, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getService(
            this, -key.hashCode(), Intent(this, CompanionService::class.java).setAction(ACTION_SAS)
                .putExtra(EXTRA_SAS_KEY, key).putExtra(EXTRA_ALLOW, false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle("Pair with $device? Code: $sas")
            .setContentText("Confirm this code matches the one on their phone.")
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .addAction(0, getString(R.string.companion_allow), allow)
            .addAction(0, getString(R.string.companion_deny), deny)
            .setTimeoutAfter(120_000L)
            .setDeleteIntent(deny)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(APPROVAL_ID + approvalSeq++, note)
    }

    /** Remember a guest dial target so it auto-connects on next launch. */
    private fun rememberGuestTarget(key: String) {
        try {
            val prefs = getSharedPreferences(PREFS, 0)
            val cur = prefs.getStringSet(KEY_GUESTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (cur.add(key)) prefs.edit().putStringSet(KEY_GUESTS, cur).apply()
        } catch (_: Exception) {
        }
    }

    private fun autoDialGuests() {
        val targets = try {
            getSharedPreferences(PREFS, 0).getStringSet(KEY_GUESTS, emptySet())?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (key in targets) {
            val host = key.substringBeforeLast(':')
            val port = key.substringAfterLast(':').toIntOrNull() ?: continue
            if (host.isEmpty() || port !in 1..65535) continue
            startGuestSession(host, port)
        }
    }

    /** Start the Linux-companion half: dial the active (or remembered) device. */
    private fun startCompanionDial() {
        dialActive()
        updateForeground()
    }

    /** Stop the Linux-companion half: drop the dial, keep everything else. */
    private fun stopCompanionDial() {
        runCatching { pairing?.close() }
        runCatching { client?.disconnect() }
        pairing = null
        client = null
        postCompanionStatus("Companion off.")
        updateForeground()
    }

    /** Start the phone-sharing half: listen + rejoin remembered guests. */
    private fun startSharingNet() {
        if (listener == null) startListener()
        autoDialGuests()
        updateForeground()
    }

    /** Stop the phone-sharing half: drop listener, peers and guests. */
    private fun stopSharingNet() {
        runCatching { listener?.stop() }
        listener = null
        _listenPort.value = 0
        synchronized(peerLock) {
            peerSessions.values.forEach { runCatching { it.handshake.close() } }
            peerSessions.clear()
            guestSessions.values.forEach {
                runCatching { it.pairing.close() }
                runCatching { it.client.disconnect() }
            }
            guestSessions.clear()
        }
        _peers.value = emptyList()
        _guests.value = emptyList()
        _guestIndex.value = emptyMap()
        _sasInbound.value = null
        _sasOutbound.value = null
        postSharingStatus("Phone sharing off.")
        updateForeground()
    }

    private fun shutdown() {
        _active.value = false
        starting.set(false)
        runCatching { pairing?.close() }
        runCatching { client?.disconnect() }
        pairing = null
        client = null
        engine = null
        backend = null
        runCatching { listener?.stop() }
        listener = null
        _listenPort.value = 0
        synchronized(peerLock) {
            peerSessions.values.forEach { runCatching { it.handshake.close() } }
            peerSessions.clear()
            guestSessions.values.forEach {
                runCatching { it.pairing.close() }
                runCatching { it.client.disconnect() }
            }
            guestSessions.clear()
        }
        _peers.value = emptyList()
        _guests.value = emptyList()
        _guestIndex.value = emptyMap()
        _sasInbound.value = null
        _sasOutbound.value = null
        CompanionEditors.onTracked = null
    }

    // --- editors ---

    private fun attachEditor(editor: Editor, uri: String) {
        val engine = engine ?: return
        if (CompanionEditors.uriFor(editor) != uri) return
        val guest = guestForCacheUri(uri)
        if (guest != null) {
            // Shared docs resolve note-link taps through the sync host:
            // open when shared, ask the owner for permission when not.
            editor.onOpenSharedNoteLink = { targetId -> openGuestLink(guest, targetId) }
        } else {
            editor.onOpenSharedNoteLink = null
        }
        val overlay = RemoteLiveStrokes(editor.state).also { it.showingFile = uri }
        editor.controller.remoteLive = overlay
        editor.controller.onStrokeStarted = {
            // A previous gesture that never committed (dismiss tap): close its run first.
            pendingLive.remove(editor)?.let { stale ->
                if (guest != null) guest.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color, stale.style)
                else eachHost { it.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color, stale.style) }
            }
        }
        editor.controller.onStrokeSample = { page, x, y, p ->
            liveSample(editor, uri, page, x, y, p)
        }
        editor.controller.onStrokeCommitted = { page, stroke ->
            val live = pendingLive.remove(editor)
            if (guest != null) {
                if (live != null) {
                    guest.finishStroke(uri, page, live.id, live.tool, live.width, live.color, live.style)
                    guest.submitLocalStroke(uri, page, stroke, live.id)
                } else {
                    guest.submitLocalStroke(uri, page, stroke, newDedupe())
                }
            } else if (live != null) {
                eachHost {
                    it.finishStroke(uri, page, live.id, live.tool, live.width, live.color, live.style)
                    it.submitLocalStroke(uri, page, stroke, live.id)
                }
            } else {
                val id = newDedupe()
                eachHost { it.submitLocalStroke(uri, page, stroke, id) }
            }
        }
        editor.controller.onStrokesErased = { page, strokes ->
            val ids = strokes.map { SyncWire.fingerprintOf(it) }
            if (ids.isNotEmpty()) {
                if (guest != null) guest.submitLocalErase(uri, page, ids, newDedupe())
                else {
                    val id = newDedupe()
                    eachHost { it.submitLocalErase(uri, page, ids, id) }
                }
            }
        }
        editor.controller.onStrokesReplaced = { changes ->
            if (guest != null) guest.submitLocalReplacements(uri, changes, newDedupe())
            else {
                val id = newDedupe()
                eachHost { it.submitLocalReplacements(uri, changes, id) }
            }
        }
        editor.controller.onRoomPreview = { page, sideways, split, resume, shift ->
            var live = pendingRoom
            if (live == null || live.uri != uri || live.page != page) {
                live = PendingRoom(uri, page, newDedupe())
                pendingRoom = live
            }
            if (guest != null) guest.roomPreview(uri, page, live.id, sideways, split, resume, shift)
            else eachHost { it.roomPreview(uri, page, live.id, sideways, split, resume, shift) }
        }
        editor.controller.onRoomCommitted = { edit ->
            val live = pendingRoom?.takeIf { it.uri == uri && it.page == edit.page }
            pendingRoom = null
            val id = live?.id ?: newDedupe()
            val (split, resume, shift) = if (edit.open) Triple(edit.a, edit.a, edit.b - edit.a) else Triple(edit.a, edit.b, edit.a - edit.b)
            if (guest != null) {
                guest.finishRoom(uri, edit.page, id, edit.sideways, split, resume, shift)
                if (edit.open) guest.submitLocalRoomOpen(uri, edit.page, edit.sideways, edit.a, edit.b - edit.a, id)
                else guest.submitLocalRoomClose(uri, edit.page, edit.sideways, edit.a, edit.b, id)
            } else {
                eachHost { it.finishRoom(uri, edit.page, id, edit.sideways, split, resume, shift) }
                if (edit.open) eachHost { it.submitLocalRoomOpen(uri, edit.page, edit.sideways, edit.a, edit.b - edit.a, id) }
                else eachHost { it.submitLocalRoomClose(uri, edit.page, edit.sideways, edit.a, edit.b, id) }
            }
        }
        editor.onFileContentReplaced = {
            // Host docs fan out a full-file push; guest docs already sync op-by-op.
            if (guest == null) eachHost { it.pushFile(uri) }
        }
        editor.onMarkupsChanged = { added, removed ->
            if (added.isNotEmpty() || removed.isNotEmpty()) {
                if (guest != null) guest.submitLocalMarkups(uri, added, removed, newDedupe())
                else {
                    val id = newDedupe()
                    eachHost { it.submitLocalMarkups(uri, added, removed, id) }
                }
            }
        }
        editor.onHistoryStepSync = { cmd -> syncContentCommand(editor, uri, guest, cmd) }
        editor.onPageStyleChanged = { entries, docStyle, docMargins ->
            // Style/margin commits carry no undo step; the callback fires once per set.
            if (guest != null) guest.submitLocalPageStyle(uri, entries, docStyle, docMargins, newDedupe())
            else {
                val id = newDedupe()
                eachHost { it.submitLocalPageStyle(uri, entries, docStyle, docMargins, id) }
            }
        }
        editor.controller.onMarkupsErased = { page, ids ->
            if (ids.isNotEmpty()) {
                if (guest != null) guest.submitLocalMarkups(uri, emptyList(), ids.map { page to it }, newDedupe())
                else {
                    val id = newDedupe()
                    eachHost { it.submitLocalMarkups(uri, emptyList(), ids.map { page to it }, id) }
                }
            }
        }
        // Text/table/image/audio edits fan out as granular uid-keyed ops both
        // ways (upsert on change, remove on delete); strokes/markups/pages/rooms
        // keep their dedicated hooks. Anything else converges via debounced
        // full-file push (host only; guests have no push path for it).
        val prevPush = editor.history.onPush
        editor.history.onPush = { cmd ->
            try { prevPush?.invoke(cmd) } catch (_: Exception) {}
            val covered = try {
                syncContentCommand(editor, uri, guest, cmd)
            } catch (_: Exception) {
                false
            }
            if (!covered) {
                val name = cmd.javaClass.simpleName
                val isGranular = name == "AddMarkups" || name == "RemoveMarkup" || name == "ReplaceMarkup" ||
                    name == "CompositeCommand" || name.startsWith("AddPage") || name.startsWith("DeletePage") ||
                    name.startsWith("MovePages")
                if (!isGranular) pushFileSoon(uri, guest)
            }
        }
        editor.controller.onRoomPreviewDone = {
            pendingRoom?.takeIf { it.uri == uri }?.let {
                pendingRoom = null
                if (guest != null) guest.finishRoom(uri, it.page, it.id, false, 0.0, 0.0, 0.0)
                else eachHost { e -> e.finishRoom(uri, it.page, it.id, false, 0.0, 0.0, 0.0) }
            }
        }
        editor.state.onPageChanged = { page ->
            if (guest != null) guest.presence(uri, page)
            else eachHost { it.presence(uri, page) }
        }
        editor.onPageInserted = { index, w, h ->
            val id = newDedupe()
            if (guest != null) guest.submitLocalPageAdd(uri, index, w, h, id)
            else eachHost { it.submitLocalPageAdd(uri, index, w, h, id) }
        }
        editor.onPageRemoved = { index ->
            val id = newDedupe()
            if (guest != null) guest.submitLocalPageRemove(uri, index, id)
            else eachHost { it.submitLocalPageRemove(uri, index, id) }
        }
        if (guest != null) guest.presence(uri, editor.state.currentPageIndex())
        else eachHost { it.presence(uri, editor.state.currentPageIndex()) }
    }

    private data class LiveStroke(val uri: String, val page: Int, val id: String, val tool: String, val width: Double, val color: String, val style: org.json.JSONObject? = null)

    private data class PendingRoom(val uri: String, val page: Int, val id: String)

    private var pendingRoom: PendingRoom? = null

    private val pendingLive = HashMap<Editor, LiveStroke>()
    private var dedupeCounter = 0
    private val pushDebounce = HashMap<String, Runnable>()

    private fun newDedupe(): String = "h${dedupeCounter++}-${System.nanoTime().toString(16)}"

    /**
     * Translate one local history step into uid-keyed content ops (text, table,
     * image, audio), both as host and as guest. Strokes keep their dedicated
     * hooks and are skipped here. Returns true when nothing remains for the
     * pushFile fallback (every touched non-stroke item was covered).
     *
     * Removes come from pages that no longer hold the uid; everything still
     * present upserts its current snapshot, so creates, moves, resizes,
     * restyles and cell or text edits all converge with one code path.
     */
    private fun syncContentCommand(editor: Editor, uri: String, guest: GuestEngine?, cmd: com.xnotes.core.history.Command): Boolean {
        val doc = try { editor.state.document } catch (_: Exception) { return false }
        val touched = try {
            cmd.touched { item -> doc.pages.firstOrNull { p -> p.items.any { it === item } } }
        } catch (_: Exception) { null } ?: return false
        val content = touched.filter { it.second !is com.xnotes.core.model.Stroke }
        if (content.isEmpty()) return true
        var covered = true
        val byPage = content.groupBy({ it.first }, { it.second })
        for ((page, items) in byPage) {
            val pi = doc.pages.indexOf(page)
            if (pi < 0) continue
            val cur = doc.pages[pi].items
            val curByUid = HashMap<String, com.xnotes.core.model.CanvasItem>()
            for (it in cur) uidOf(it)?.takeIf { u -> u.isNotEmpty() }?.let { u -> curByUid[u] = it }
            val touchedUids = LinkedHashSet<String>()
            for (it in items) uidOf(it)?.takeIf { u -> u.isNotEmpty() }?.let { u -> touchedUids.add(u) }
            val removed = touchedUids.filter { it !in curByUid }
            val added = touchedUids.filter { it in curByUid }.mapNotNull { curByUid[it] }
            val ops = JSONArray()
            for (uid in removed) {
                val kind = kindOfUid(items, uid) ?: kindOfUid(cur, uid) ?: "text"
                when (kind) {
                    "table" -> ops.put(JSONObject().put("op", "remove_table").put("page", pi).put("ids", JSONArray(listOf(uid))))
                    "image" -> ops.put(JSONObject().put("op", "remove_image").put("page", pi).put("ids", JSONArray(listOf(uid))))
                    "audio" -> ops.put(JSONObject().put("op", "remove_audio").put("page", pi).put("ids", JSONArray(listOf(uid))))
                    else -> ops.put(JSONObject().put("op", "remove_text").put("page", pi).put("ids", JSONArray(listOf(uid))))
                }
            }
            for (it in added) {
                when (it) {
                    is com.xnotes.core.model.TextItem -> {
                        if (it.uid.isEmpty()) it.uid = newDedupe()
                        ops.put(JSONObject().put("op", "add_text").put("page", pi).put("text", SyncWire.toWireText(it)))
                    }
                    is com.xnotes.core.model.TableItem -> {
                        if (it.uid.isEmpty()) it.uid = newDedupe()
                        ops.put(JSONObject().put("op", "add_table").put("page", pi).put("table", SyncWire.toWireTable(it)))
                    }
                    is com.xnotes.core.model.ImageItem -> {
                        if (it.uid.isEmpty()) it.uid = newDedupe()
                        val bytes = runCatching { it.image.file.readBytes() }.getOrNull() ?: continue
                        val imageId = "img-" + it.uid
                        sendContentBytes(uri, guest, fileOf(editor, uri), imageId, bytes, kind = "image")
                        val op = JSONObject().put("op", "add_image").put("page", pi).put("image_id", imageId)
                            .put("width", it.image.width).put("height", it.image.height)
                            .put("rect", JSONArray().put(it.rect.x).put(it.rect.y).put(it.rect.w).put(it.rect.h))
                            .put("orientation", it.orientation).put("angle", it.angle)
                        if (it.flip.x) op.put("flip_x", true)
                        if (it.flip.y) op.put("flip_y", true)
                        it.part?.let { q -> op.put("part", JSONArray().put(q.x).put(q.y).put(q.right).put(q.bottom)) }
                        op.put("uid", it.uid)
                        ops.put(op)
                    }
                    is com.xnotes.core.model.AudioItem -> {
                        if (it.uid.isEmpty()) it.uid = newDedupe()
                        val bytes = runCatching { it.audio.file.readBytes() }.getOrNull() ?: continue
                        val audioId = "aud-" + it.uid
                        sendContentBytes(uri, guest, fileOf(editor, uri), audioId, bytes, kind = "audio")
                        sendContentBytes(uri, guest, fileOf(editor, uri), audioId, it.audio.peaks, kind = "peaks")
                        ops.put(JSONObject().put("op", "add_audio").put("page", pi).put("audio", SyncWire.toWireAudio(audioId, it, pi)))
                    }
                    else -> covered = false
                }
            }
            if (ops.length() > 0) {
                val id = newDedupe()
                if (guest != null) guest.submitContentOps(fileOf(editor, uri), ops, id)
                else eachHost { it.submitContentOps(uri, ops, id) }
            }
            if (items.any { it !is com.xnotes.core.model.TextItem && it !is com.xnotes.core.model.TableItem && it !is com.xnotes.core.model.ImageItem && it !is com.xnotes.core.model.AudioItem }) covered = false
        }
        return covered
    }

    private fun uidOf(item: com.xnotes.core.model.CanvasItem): String? = when (item) {
        is com.xnotes.core.model.TextItem -> item.uid
        is com.xnotes.core.model.TableItem -> item.uid
        is com.xnotes.core.model.ImageItem -> item.uid
        is com.xnotes.core.model.AudioItem -> item.uid
        else -> null
    }

    private fun kindOfUid(items: List<com.xnotes.core.model.CanvasItem>, uid: String): String? {
        val it = items.firstOrNull { uidOf(it) == uid } ?: return null
        return when (it) {
            is com.xnotes.core.model.TextItem -> "text"
            is com.xnotes.core.model.TableItem -> "table"
            is com.xnotes.core.model.ImageItem -> "image"
            is com.xnotes.core.model.AudioItem -> "audio"
            else -> null
        }
    }

    private fun fileOf(editor: Editor, uri: String): String {
        val g = guestForCacheUri(uri)
        return if (g != null) "" else uri
    }

    private fun sendContentBytes(uri: String, guest: GuestEngine?, file: String, id: String, bytes: ByteArray, kind: String) {
        val target = if (file.isNotEmpty()) file else uri
        if (guest != null) {
            if (kind == "audio") guest.sendAudioBytes(target, id, bytes)
            else if (kind == "peaks") guest.sendAudioPeaks(target, id, bytes)
            else guest.sendImageBytes(target, id, bytes)
        } else eachHost {
            if (kind == "audio") it.sendAudioBytes(uri, id, bytes)
            else if (kind == "peaks") it.sendAudioPeaks(uri, id, bytes)
            else it.sendImageBytes(uri, id, bytes)
        }
    }

    /**
     * Debounced full-file push for content ops have no wire form (text/table
     * edits, image moves/removes, shape refills): collapses a typing burst
     * into one reload so peers converge in real time without per-keystroke
     * full files.
     */
    private fun pushFileSoon(uri: String, guest: GuestEngine?) {
        if (guest != null) return // guest docs already sync op-by-op; host fans file
        synchronized(pushDebounce) { pushDebounce.remove(uri)?.let { main.removeCallbacks(it) } }
        val r = Runnable {
            synchronized(pushDebounce) { pushDebounce.remove(uri) }
            eachHost { it.pushFile(uri) }
        }
        synchronized(pushDebounce) { pushDebounce[uri] = r }
        main.postDelayed(r, 1200)
    }

    private fun liveSample(editor: Editor, uri: String, page: Int, x: Double, y: Double, p: Double) {
        val guest = guestForCacheUri(uri)
        if (guest == null && engine == null && peerSessions.isEmpty()) return
        var live = pendingLive[editor]
        if (live != null && (live.uri != uri || live.page != page)) {
            // Crossed onto another page mid-stroke: close this run, open the next.
            val stale = live
            if (guest != null) guest.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color, stale.style)
            else eachHost { it.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color, stale.style) }
            live = null
        }
        if (live == null) {
            val tool = editor.controller.tool
            val cfg = editor.controller.resolvedStrokeConfig(tool)
            val style = SyncWire.toWireStyle(tool, cfg)
            live = LiveStroke(uri, page, newDedupe(), tool.id, cfg.baseWidth, SyncWire.hexColor(cfg.rgba), style).also { pendingLive[editor] = it }
        }
        val cur = live ?: return
        if (guest != null) guest.liveSample(uri, page, cur.id, cur.tool, cur.width, cur.color, x, y, p, cur.style)
        else eachHost { it.liveSample(uri, page, cur.id, cur.tool, cur.width, cur.color, x, y, p, cur.style) }
    }

    /** Every host engine serving this device's files (Linux companion + inbound peers). */
    private fun eachHost(fn: (HostEngine) -> Unit) {
        engine?.let(fn)
        synchronized(peerLock) { peerSessions.values.forEach { fn(it.engine) } }
    }

    /** The guest engine owning a fetched peer file, or null for own files. */
    private fun guestForCacheUri(uri: String): GuestEngine? {
        val prefix = guestCachePrefix ?: return null
        if (!uri.startsWith(prefix)) return null
        return synchronized(peerLock) {
            guestSessions.values.mapNotNull { it.engine }.firstOrNull { it.isGuestUri(uri) }
        }
    }

    /** Follow a note-link tap from a guest document (open or permission request). */
    private fun openGuestLink(engine: GuestEngine, targetId: String) {
        main.post {
            if (engine.remoteIndex().any { it.id == targetId }) {
                engine.fetch(targetId, openNow = true)
            } else if (engine.isConnected()) {
                postSharingStatus("Asking the owner for access…")
                engine.requestLinkAccess(targetId)
            } else {
                postSharingStatus("Not connected.")
            }
        }
    }

    @Volatile private var guestCachePrefix: String? = null

    private fun detachEditor(editor: Editor) {
        editor.controller.onStrokeStarted = null
        editor.controller.onStrokeSample = null
        editor.controller.onStrokeCommitted = null
        editor.controller.onStrokesErased = null
        editor.controller.onStrokesReplaced = null
        editor.state.onPageChanged = null
        editor.onPageInserted = null
        editor.onPageRemoved = null
        editor.onImageInserted = null
        editor.onFileContentReplaced = null
        editor.onMarkupsChanged = null
        editor.onHistoryStepSync = null
        editor.onPageStyleChanged = null
        editor.controller.onMarkupsErased = null
        editor.onOpenSharedNoteLink = null
        editor.controller.onRoomPreview = null
        editor.controller.onRoomCommitted = null
        editor.controller.onRoomPreviewDone = null
        // Restore the audio-stamp hook (attachEditor chains it); drop debounces.
        try {
            editor.history.onPush = { command ->
                try {
                    com.xnotes.ui.AudioRecording.stamp(editor, command.added())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        if (pendingRoom?.uri == CompanionEditors.uriFor(editor)) pendingRoom = null
        editor.controller.remoteLive = null
        pendingLive.remove(editor)
    }

    // --- approval (notification actions; works with the screen off) ---

    private fun askApproval(kind: String, device: String, name: String, respond: (Boolean) -> Unit) {
        val token = "ap${approvalSeq++}-${System.nanoTime()}"
        synchronized(approvals) { approvals[token] = respond }
        val allow = PendingIntent.getService(
            this, token.hashCode(), Intent(this, CompanionService::class.java).setAction(ACTION_APPROVAL)
                .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ALLOW, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getService(
            this, -token.hashCode(), Intent(this, CompanionService::class.java).setAction(ACTION_APPROVAL)
                .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ALLOW, false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, token.hashCode(), Intent(this, CompanionService::class.java).setAction(ACTION_APPROVAL)
                .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ALLOW, false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(getString(R.string.companion_approval_title, device, kind, name))
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .addAction(0, getString(R.string.companion_allow), allow)
            .addAction(0, getString(R.string.companion_deny), deny)
            .setTimeoutAfter(120_000L)
            .setDeleteIntent(cancel)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(APPROVAL_ID + approvalSeq, note)
    }

    // --- notifications ---

    private fun startingNotification(): Notification {
        ensureChannel(this)
        val compOn = try { isCompanionOn(this) } catch (_: Exception) { true }
        val title = if (compOn) getString(R.string.share_notif_title)
        else getString(R.string.share_channel)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(title)
            .setContentText(getString(R.string.share_starting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Ongoing notification for the Linux-companion half (own foreground id). */
    private fun companionNotification(): Notification {
        ensureChannel(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val stop = PendingIntent.getService(
            this, 11, Intent(this, CompanionService::class.java).setAction(ACTION_STOP_COMPANION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = _companionStatus.value.paired ?: _companionStatus.value.text
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(getString(R.string.share_notif_title))
            .setContentText(getString(R.string.companion_notif_text, label, targetPort))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.share_stop), stop)
            .build()
    }

    /** Ongoing notification for the phone-sharing half (own foreground id). */
    private fun sharingNotification(): Notification {
        ensureChannel(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val stop = PendingIntent.getService(
            this, 12, Intent(this, CompanionService::class.java).setAction(ACTION_STOP_SHARING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val port = _listenPort.value
        val text = if (port > 0) "Listening for phones on port $port."
        else getString(R.string.share_starting)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(getString(R.string.share_channel))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.share_stop), stop)
            .build()
    }

    /**
     * Keep exactly one foreground notification per enabled half, none when
     * both halves are off (then stop instead of lingering with a phantom
     * "sharing is on" card).
     */
    private fun updateForeground() {
        val compOn = isCompanionOn(this)
        val shareOn = isSharingOn(this)
        _companionOn.value = compOn
        _sharingOn.value = shareOn
        _active.value = compOn || shareOn
        if (!compOn && !shareOn) {
            runCatching {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_COMPANION)
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_SHARING)
            }
            stopSelf()
            return
        }
        try {
            if (compOn && shareOn) {
                ServiceCompat.startForeground(this, NOTIF_COMPANION, companionNotification(), fgsType())
                getSystemService(NotificationManager::class.java)?.notify(NOTIF_SHARING, sharingNotification())
            } else if (compOn) {
                ServiceCompat.startForeground(this, NOTIF_COMPANION, companionNotification(), fgsType())
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_SHARING)
            } else {
                ServiceCompat.startForeground(this, NOTIF_SHARING, sharingNotification(), fgsType())
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_COMPANION)
            }
        } catch (_: Exception) {
        }
    }

    private fun notifyUp(device: String, port: Int) {
        // Companion dial finished: refresh its half (no-op when it is off).
        runCatching { updateForeground() }
    }

    private fun fgsType(): Int =
        if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    data class Status(val text: String, val paired: String?, val connected: Boolean)

    /** An inbound Android peer (this phone hosts files for it). */
    data class PeerInfo(val fp: String, val name: String, val connected: Boolean, val kind: String = com.xnotes.share.db.DeviceKind.PHONE)

    /** An outbound Android peer (this phone reads that phone's files). */
    data class GuestInfo(
        val key: String,
        val fp: String?,
        val name: String,
        val connected: Boolean,
        val fileCount: Int,
    )

    /** A mutual-confirm SAS code shown on both phones. */
    data class SasRequest(val key: String, val name: String, val sas: String, val inbound: Boolean)

    companion object {
        private const val CHANNEL = "companion"
        private const val NOTIFICATION_ID = 43 // legacy single id; kept for cancel compat
        private const val NOTIF_COMPANION = 43
        private const val NOTIF_SHARING = 44
        private const val APPROVAL_ID = 100
        const val ACTION_STOP = "com.xnotes.share.companion.STOP"
        const val ACTION_STOP_COMPANION = "com.xnotes.share.companion.STOP_COMPANION"
        const val ACTION_STOP_SHARING = "com.xnotes.share.companion.STOP_SHARING"
        const val ACTION_REFRESH = "com.xnotes.share.companion.REFRESH"
        const val ACTION_APPROVAL = "com.xnotes.share.companion.APPROVAL"
        const val ACTION_SAS = "com.xnotes.share.companion.SAS"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_SAS_KEY = "sas_key"

        private val _status = MutableStateFlow(Status("Off", null, false))
        val status: StateFlow<Status> = _status.asStateFlow()
        private val _companionStatus = MutableStateFlow(Status("Off", null, false))
        /** Linux-companion half status; Companion screen observes this only. */
        val companionStatus: StateFlow<Status> = _companionStatus.asStateFlow()
        private val _sharingStatus = MutableStateFlow(Status("Off", null, false))
        /** Phone-sharing half status; Shared screen observes this only. */
        val sharingStatus: StateFlow<Status> = _sharingStatus.asStateFlow()
        private val _codeNeeded = MutableStateFlow(false)
        val codeNeeded: StateFlow<Boolean> = _codeNeeded.asStateFlow()
        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()
        private val _listenPort = MutableStateFlow(0)
        /** This phone's listener port for Android peers (0 while down). */
        val listenPort: StateFlow<Int> = _listenPort.asStateFlow()
        private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
        /** Paired Android phones (inbound hosting), with connection state. */
        val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()
        private val _guests = MutableStateFlow<List<GuestInfo>>(emptyList())
        /** Outbound guest sessions (this phone reads their files). */
        val guests: StateFlow<List<GuestInfo>> = _guests.asStateFlow()
        private val _guestIndex = MutableStateFlow<Map<String, List<GuestEngine.RemoteEntry>>>(emptyMap())
        /** Remote shared index per guest host fingerprint. */
        val guestIndex: StateFlow<Map<String, List<GuestEngine.RemoteEntry>>> = _guestIndex.asStateFlow()
        private val _sasInbound = MutableStateFlow<SasRequest?>(null)
        /** Inbound SAS approval waiting for the user's verdict. */
        val sasInbound: StateFlow<SasRequest?> = _sasInbound.asStateFlow()
        private val _sasOutbound = MutableStateFlow<SasRequest?>(null)
        /** Outbound SAS code to confirm against the other phone. */
        val sasOutbound: StateFlow<SasRequest?> = _sasOutbound.asStateFlow()

        /** Opens a fetched guest file in the editor; wired by MainActivity. */
        var onOpenGuestFile: ((String) -> Unit)? = null

        const val PREFS = "companion"
        const val KEY_IP = "companion_last_ip"
        const val KEY_PORT = "companion_last_port"
        const val KEY_ENABLED = "companion_enabled" // legacy: migrated to the two flags below
        const val KEY_COMPANION = "companion_on"
        const val KEY_SHARING = "sharing_on"
        const val KEY_NAME = "companion_own_name"
        const val KEY_GUESTS = "companion_guest_targets"

        private val _companionOn = MutableStateFlow(false)
        /** Linux companion dialing is switched on. */
        val companionOn: StateFlow<Boolean> = _companionOn.asStateFlow()
        private val _sharingOn = MutableStateFlow(false)
        /** Phone sharing (listener + guests) is switched on. */
        val sharingOn: StateFlow<Boolean> = _sharingOn.asStateFlow()

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

        /** One-time migration from the old single toggle (which meant everything). */
        private fun migrateFeatureFlags(context: Context) {
            try {
                val p = prefs(context)
                if (!p.contains(KEY_COMPANION) && !p.contains(KEY_SHARING) && p.contains(KEY_ENABLED)) {
                    val on = p.getBoolean(KEY_ENABLED, false)
                    p.edit().putBoolean(KEY_COMPANION, on).putBoolean(KEY_SHARING, on)
                        .remove(KEY_ENABLED).apply()
                }
            } catch (_: Exception) {
            }
        }

        fun isCompanionOn(context: Context): Boolean = try {
            migrateFeatureFlags(context)
            prefs(context).getBoolean(KEY_COMPANION, false)
        } catch (_: Exception) {
            false
        }

        fun isSharingOn(context: Context): Boolean = try {
            migrateFeatureFlags(context)
            prefs(context).getBoolean(KEY_SHARING, false)
        } catch (_: Exception) {
            false
        }

        private fun setFlag(context: Context, key: String, on: Boolean) {
            try {
                migrateFeatureFlags(context)
                prefs(context).edit().putBoolean(key, on).apply()
            } catch (_: Exception) {
            }
        }

        /** Start the service on app launch when either feature was left on. */
        fun startIfEnabled(context: Context) {
            syncFlagFlows(context)
            if (!isCompanionOn(context) && !isSharingOn(context)) return
            ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
        }

        /** Push persisted toggles into the flows (cold UI before the service boots). */
        fun syncFlagFlows(context: Context) {
            try {
                val comp = isCompanionOn(context)
                val share = isSharingOn(context)
                _companionOn.value = comp
                _sharingOn.value = share
                _active.value = comp || share
            } catch (_: Exception) {
            }
        }

        /**
         * Switch the Linux-companion half on/off, independently of phone
         * sharing. Turning it on boots the service when needed and dials;
         * turning it off drops the dial and its notification.
         */
        fun setCompanionEnabled(context: Context, on: Boolean) {
            setFlag(context, KEY_COMPANION, on)
            _companionOn.value = on
            _active.value = on || _sharingOn.value
            val svc = runningService
            if (svc == null) {
                if (on) ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                return
            }
            svc.main.post {
                if (!svc.coreReady) {
                    svc.ensureCore { svc.applyEnabledFeatures() }
                    return@post
                }
                if (on) svc.startCompanionDial() else svc.stopCompanionDial()
                svc.updateForeground()
            }
        }

        /**
         * Switch the phone-sharing half on/off, independently of the Linux
         * companion. Turning it on boots the service when needed, listens and
         * redials remembered guests; turning it off drops the listener and
         * every peer/guest session plus their notifications.
         */
        fun setSharingEnabled(context: Context, on: Boolean) {
            setFlag(context, KEY_SHARING, on)
            _sharingOn.value = on
            _active.value = on || _companionOn.value
            val svc = runningService
            if (svc == null) {
                if (on) ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                return
            }
            svc.main.post {
                if (!svc.coreReady) {
                    svc.ensureCore { svc.applyEnabledFeatures() }
                    return@post
                }
                if (on) svc.startSharingNet() else svc.stopSharingNet()
                svc.updateForeground()
            }
        }

        fun submitCode(context: Context, code: String) {
            // Explicit pairing step: the companion half comes on with it.
            setCompanionEnabled(context, true)
            val svc = runningService ?: return
            svc.main.post { svc.pairing?.submitCode(code) ?: svc.postCompanionStatus("Not connected yet — try again in a moment.") }
        }

        /** Re-dial an ad-hoc address (explicit: turns the companion half on). */
        fun reconnect(context: Context, host: String, port: Int) {
            setCompanionEnabled(context, true)
            val svc = runningService ?: return
            svc.main.post {
                svc.targetHost = host
                svc.targetPort = port
                if (!svc.coreReady) {
                    svc.ensureCore { svc.applyEnabledFeatures() }
                    return@post
                }
                if (svc.engine == null) {
                    svc.ensureCore { svc.startCompanionDial() }
                } else {
                    svc.dialDevice(null)
                }
            }
        }

        // --- Android-to-Android phones ---

        /**
         * Dial an Android peer as guest (this phone reads that phone's files).
         * Several guests may be connected at once. When [port] is 0 the
         * listener port is probed (42327..42356, the Android range) so the
         * user only types the IP. Explicit ports (incl. legacy 42317 peers)
         * are honored as given.
         */
        fun dialPeer(context: Context, host: String, port: Int) {
            // Explicit user action: the phone-sharing half comes on with it.
            if (!isSharingOn(context)) setSharingEnabled(context, true)
            val svc = runningService
            if (svc == null) {
                // Service is booting; queue the dial once its core is ready.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runningService?.let { s ->
                        s.main.post {
                            if (!s.coreReady) {
                                s.ensureCore {
                                    s.applyEnabledFeatures()
                                    s.startGuestSession(host, if (port > 0) port else com.xnotes.share.companion.AndroidListener.DEFAULT_PORT)
                                }
                            } else {
                                s.startGuestSession(host, if (port > 0) port else com.xnotes.share.companion.AndroidListener.DEFAULT_PORT)
                            }
                        }
                    }
                }, 3000)
                return
            }
            svc.main.post {
                if (svc.sharedVersions == null) {
                    svc.postSharingStatus("Sharing is still starting — try again in a moment.")
                    return@post
                }
                if (port in 1..65535) {
                    svc.startGuestSession(host, port)
                } else {
                    svc.postSharingStatus("Probing $host for a phone…")
                    Thread({
                        val found = probePeer(host)
                        svc.main.post {
                            if (found != null) svc.startGuestSession(host, found)
                            else svc.postSharingStatus("No phone found on $host (tried 42327–42356).")
                        }
                    }, "peer-probe").apply { isDaemon = true; start() }
                }
            }
        }

        /** TCP probe for an Android listener on [host] (42327..42356). */
        private fun probePeer(host: String): Int? {
            val threads = ArrayList<Thread>()
            val found = java.util.concurrent.atomic.AtomicInteger(-1)
            for (p in com.xnotes.share.companion.AndroidListener.DEFAULT_PORT..com.xnotes.share.companion.AndroidListener.DEFAULT_PORT + com.xnotes.share.companion.AndroidListener.PORT_TRIES - 1) {
                threads += Thread({
                    if (found.get() != -1) return@Thread
                    try {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(host, p), 900)
                            found.compareAndSet(-1, p)
                        }
                    } catch (_: Exception) {
                    }
                }).apply { isDaemon = true; start() }
            }
            val deadline = System.currentTimeMillis() + 4000
            for (t in threads) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                runCatching { t.join(left) }
                if (found.get() != -1) break
            }
            return found.get().takeIf { it != -1 }
        }

        /** Fetch a peer file into the cache; it opens in the editor on arrival. */
        fun fetchShared(context: Context, hostFp: String, remoteId: String) {
            val svc = runningService ?: return
            svc.main.post {
                val eng = synchronized(svc.peerLock) {
                    svc.guestSessions.values.firstOrNull { it.fp == hostFp }?.engine
                } ?: return@post
                eng.fetch(remoteId, openNow = true)
            }
        }

        /** Cached bytes of a peer file, for the save-a-copy flow. */
        fun readGuestBytes(hostFp: String, remoteId: String): ByteArray? {
            val svc = runningService ?: return null
            val eng = synchronized(svc.peerLock) {
                svc.guestSessions.values.firstOrNull { it.fp == hostFp }?.engine
            } ?: return null
            return eng.cachedBytes(remoteId)
        }

        /**
         * Open a note-link target shared by a connected host: fetches and
         * opens it. Returns true when a guest session covers [uri].
         */
        fun openSharedTarget(uri: String): Boolean {
            val svc = runningService ?: return false
            val eng = synchronized(svc.peerLock) {
                svc.guestSessions.values.mapNotNull { it.engine }
                    .firstOrNull { e -> e.remoteIndex().any { it.id == uri } }
            } ?: return false
            svc.main.post { eng.fetch(uri, openNow = true) }
            return true
        }

        /** Display name for a guest cache URI ("External - {name}"), or null. */
        fun guestDisplayName(cacheUri: String): String? {
            val svc = runningService ?: return null
            return synchronized(svc.peerLock) {
                svc.guestSessions.values.mapNotNull { it.engine }
                    .firstNotNullOfOrNull { it.displayNameForCache(cacheUri) }
            }
        }

        /** Allow an inbound peer whose SAS matches ([key] from [sasInbound]). */
        fun confirmInboundSas(context: Context, key: String) {
            val svc = runningService ?: return
            svc.main.post {
                _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                synchronized(svc.peerLock) { svc.peerSessions[key] }?.handshake?.setApproved(key, true)
                    ?: svc.postSharingStatus("That phone already left.")
            }
        }

        /** Deny an inbound pairing request. */
        fun denyInboundSas(context: Context, key: String) {
            val svc = runningService ?: return
            svc.main.post {
                _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                val s = synchronized(svc.peerLock) { svc.peerSessions.remove(key) }
                s?.handshake?.setApproved(key, false)
                s?.conn?.close()
                svc.publishPeers()
            }
        }

        /** Confirm the outbound SAS matches the other phone's screen. */
        fun confirmOutboundSas(key: String) {
            val svc = runningService ?: return
            svc.main.post {
                val s = synchronized(svc.peerLock) { svc.guestSessions[key] }
                if (s != null) s.pairing.confirmSas()
                else svc.pairing?.confirmSas()
            }
        }

        /** Cancel an outbound SAS pairing (drops the guest session). */
        fun cancelOutboundSas(key: String) {
            val svc = runningService ?: return
            svc.main.post {
                _sasOutbound.value?.takeIf { it.key == key }?.let { _sasOutbound.value = null }
                synchronized(svc.peerLock) { svc.guestSessions.remove(key) }?.let {
                    runCatching { it.pairing.close() }
                    runCatching { it.client.disconnect() }
                } ?: svc.pairing?.cancelPairing()
                svc.publishGuests()
            }
        }

        /** Disconnect a guest session (it stays remembered for auto-connect). */
        fun disconnectGuest(key: String) {
            val svc = runningService ?: return
            svc.main.post {
                synchronized(svc.peerLock) { svc.guestSessions.remove(key) }?.let {
                    runCatching { it.pairing.close() }
                    runCatching { it.client.disconnect() }
                }
                svc.publishGuests()
            }
        }

        /** Forget a phone entirely: drop pins, grants, sessions and auto-connect. */
        fun forgetPeer(context: Context, fp: String) {
            Thread({
                runCatching {
                    val store = com.xnotes.share.db.ShareStore.of(context)
                    val dev = kotlinx.coroutines.runBlocking { store.device(fp) }
                    if (dev != null) {
                        CompanionPins.of(File(context.filesDir, "share")).unpin(dev.name)
                    }
                    kotlinx.coroutines.runBlocking { store.forget(fp) }
                    try {
                        val prefs = context.getSharedPreferences(PREFS, 0)
                        val cur = prefs.getStringSet(KEY_GUESTS, emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (cur.removeIf { it == fp || it.startsWith("$fp:") }) {
                            prefs.edit().putStringSet(KEY_GUESTS, cur).apply()
                        }
                    } catch (_: Exception) {
                    }
                }
                runningService?.let { svc ->
                    svc.main.post {
                        synchronized(svc.peerLock) {
                            svc.peerSessions.remove(fp)?.let {
                                runCatching { it.handshake.close() }
                            }
                            svc.guestSessions.entries.removeIf { e ->
                                (e.value.fp == fp).also { drop ->
                                    if (drop) {
                                        runCatching { e.value.pairing.close() }
                                        runCatching { e.value.client.disconnect() }
                                    }
                                }
                            }
                            svc.peerNames.remove(fp)
                        }
                        if (svc.grants?.activeFp == fp) {
                            svc.grants?.activeFp = null
                            svc.client?.disconnect()
                        }
                        _sasInbound.value?.takeIf { it.key == fp }?.let { _sasInbound.value = null }
                        svc.publishPeers()
                        svc.publishGuests()
                        _sharingStatus.value = Status("Forgot phone.", _sharingStatus.value.paired, connected = false)
                        svc.mirrorStatus()
                    }
                }
            }, "peer-forget").apply { isDaemon = true; start() }
        }

        /** Make [fp] the active companion and dial it, parking the others. */
        fun switchDevice(context: Context, fp: String) {
            // Explicit user action: the companion half comes on with it.
            if (!isCompanionOn(context)) setCompanionEnabled(context, true)
            val svc = runningService
            if (svc == null) {
                // The fresh session dials the active device on its own.
                Thread({
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            com.xnotes.share.db.ShareStore.of(context).setActive(fp)
                        }
                    }
                }, "companion-switch").apply { isDaemon = true; start() }
                return
            }
            svc.main.post {
                Thread({
                    runCatching {
                        kotlinx.coroutines.runBlocking { svc.store?.setActive(fp) }
                        val dev = kotlinx.coroutines.runBlocking { svc.store?.device(fp) }
                        if (dev != null) {
                            svc.grants?.activeFp = fp
                            kotlinx.coroutines.runBlocking { svc.grants?.refreshBlocking(fp) }
                        }
                        svc.main.post { svc.dialStored(fp) }
                    }
                }, "companion-switch").apply { isDaemon = true; start() }
            }
        }

        /** Forget a companion: drop its TLS pin and grants, disconnect if it was active. */
        fun forgetDevice(context: Context, fp: String) {
            Thread({
                runCatching {
                    val store = com.xnotes.share.db.ShareStore.of(context)
                    val dev = kotlinx.coroutines.runBlocking { store.device(fp) }
                    if (dev != null) {
                        CompanionPins.of(File(context.filesDir, "share")).unpin(dev.name)
                    }
                    // The pin file is keyed by name; unpin every alias just in case.
                    kotlinx.coroutines.runBlocking { store.forget(fp) }
                }
                runningService?.let { svc ->
                    svc.main.post {
                        if (svc.grants?.activeFp == fp) {
                            svc.grants?.activeFp = null
                            svc.client?.disconnect()
                            _companionStatus.value = Status("Forgot companion.", null, connected = false)
                            svc.mirrorStatus()
                        }
                    }
                }
            }, "companion-forget").apply { isDaemon = true; start() }
        }

        /** Change a remembered companion's address (e.g. after DHCP moves it); reconnects if active. */
    fun editDevice(context: Context, fp: String, host: String, port: Int) {
        Thread({
            runCatching {
                val store = com.xnotes.share.db.ShareStore.of(context)
                val dev = kotlinx.coroutines.runBlocking { store.device(fp) }
                if (dev != null) kotlinx.coroutines.runBlocking { store.touch(fp, host, port, dev.name) }
                runningService?.let { svc ->
                    svc.main.post {
                        if (svc.grants?.activeFp == fp) {
                            svc.targetHost = host
                            svc.targetPort = port
                            svc.dialStored(fp)
                        }
                    }
                }
            }
        }, "companion-edit").apply { isDaemon = true; start() }
    }

    /** Rename a companion locally (alias shown instead of its device name). */
        fun renameDevice(context: Context, fp: String, nickname: String) {
            Thread({
                runCatching {
                    val store = com.xnotes.share.db.ShareStore.of(context)
                    kotlinx.coroutines.runBlocking { store.rename(fp, nickname) }
                    val dev = kotlinx.coroutines.runBlocking { store.device(fp) }
                    runningService?.let { svc ->
                        svc.main.post {
                            if (svc.grants?.activeFp == fp && dev != null) {
                                _companionStatus.value = _companionStatus.value.copy(paired = dev.displayName)
                                svc.mirrorStatus()
                            }
                        }
                    }
                }
            }, "companion-rename").apply { isDaemon = true; start() }
        }

        /** This phone's own nickname, sent as its device name when pairing. */
        fun setOwnName(context: Context, name: String) {
            try {
                context.getSharedPreferences(PREFS, 0).edit().putString(KEY_NAME, name).apply()
            } catch (_: Exception) {
            }
            runningService?.let { svc ->
                svc.main.post { svc.deviceName = name.ifBlank { android.os.Build.MODEL ?: "Android" } }
            }
        }

        fun disconnect() {
            runningService?.let { svc ->
                svc.main.post {
                    svc.client?.autoReconnect = false
                    svc.client?.disconnect()
                    _companionStatus.value = Status("Disconnected.", _companionStatus.value.paired, connected = false)
                    svc.mirrorStatus()
                }
            }
        }

        @Volatile private var runningService: CompanionService? = null

        /** Legacy single toggle (kept for compat): enables both halves. */
        fun start(context: Context) {
            ensureChannel(context)
            setCompanionEnabled(context, true)
            setSharingEnabled(context, true)
        }

        /** Legacy single stop (kept for compat): disables both halves. */
        fun stop(context: Context) {
            setCompanionEnabled(context, false)
            setSharingEnabled(context, false)
        }

        /**
         * Push a policy refresh to the running session. Passive: never boots
         * the service (that would resurrect a phantom notification after the
         * user switched everything off).
         */
        fun refreshPolicy(context: Context) {
            val svc = runningService ?: return
            svc.main.post {
                svc.engine?.refreshPolicy()
                synchronized(svc.peerLock) { svc.peerSessions.values.forEach { it.engine.refreshPolicy() } }
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.share_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        /** Production file ops: SAF reads/writes behind sync ids. */
        class BackendFiles(private val backend: ShareBackendImpl) : SyncFiles {
            override fun read(id: String): ByteArray? = runCatching { backend.rawBytes(id)?.bytes }.getOrNull()
            override fun write(id: String, bytes: ByteArray): Boolean = backend.writeRawBytes(id, bytes)
            override fun delete(id: String): Boolean = backend.deleteDoc(id)
            override fun create(dirId: String?, name: String, isDir: Boolean): String? = backend.createDoc(dirId, name, isDir)
        }
    }
}
