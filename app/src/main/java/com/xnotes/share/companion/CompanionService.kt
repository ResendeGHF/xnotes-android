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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

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
        shutdown()
        _status.value = Status("Off", null, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVAL -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
                val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
                synchronized(approvals) { approvals.remove(token) }?.invoke(allow)
                return START_STICKY
            }
            ACTION_SAS -> {
                val key = intent.getStringExtra(EXTRA_SAS_KEY) ?: return START_NOT_STICKY
                if (intent.getBooleanExtra(EXTRA_ALLOW, false)) confirmInboundSas(this, key)
                else denyInboundSas(this, key)
                return START_STICKY
            }
            ACTION_REFRESH -> {
                engine?.refreshPolicy()
                synchronized(peerLock) { peerSessions.values.forEach { it.engine.refreshPolicy() } }
                return START_STICKY
            }
        }
        startSession()
        return START_STICKY
    }

    // --- session ---

    private fun startSession() {
        if (!starting.compareAndSet(false, true)) return
        ServiceCompat.startForeground(this, NOTIFICATION_ID, startingNotification(), fgsType())
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
                        postStatus("Enter the code shown on the companion.")
                    }
                    override fun showStatus(s: String) = postStatus(s)
                    override fun pairingDone(name: String) {
                        starting.set(false)
                        _codeNeeded.value = false
                        notifyUp(name, targetPort)
                    }

                    override fun pairingFailed(reason: String) {
                        _codeNeeded.value = false
                        postStatus(reason)
                    }
                }
                this.backend = backend
                this.engine = engine
                this.ui = ui
                _active.value = true
                main.post {
                    // Files opened before the session started never fired onTracked
                    // while the engine existed: wire them now.
                    for (e in CompanionEditors.editors()) {
                        val uri = CompanionEditors.uriFor(e) ?: continue
                        attachEditor(e, uri)
                    }
                    // Listen for Android peers (auto-increment port when busy)…
                    startListener()
                    // …redial the active companion (or the remembered address)…
                    dialActive()
                    // …and rejoin remembered guest sessions.
                    autoDialGuests()
                }
            } catch (e: Exception) {
                postStatus("Start failed: ${e.message}")
                starting.set(false)
                stopSelf()
            }
        }, "companion-start").apply { isDaemon = true; start() }
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
                _status.value = Status(reason, _status.value.paired, connected = false)
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
        // An Android peer answers hello_ack in SAS mode; surface it the same way.
        pairing.onSasPeer = { sas, name ->
            _sasOutbound.value = SasRequest(dev?.fp ?: "$host:$port", name, sas, inbound = false)
        }
        pairing.onPaired = { name ->
            val peerFp = pairing.peerFingerprint
            if (peerFp != null) {
                Thread({
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            store.remember(peerFp, name, host, port)
                            // First companion ever becomes active; otherwise the
                            // active one keeps its slot.
                            if (store.activeDeviceOnce() == null) {
                                store.setActive(peerFp)
                            }
                        }
                        grants.activeFp = peerFp
                        kotlinx.coroutines.runBlocking { grants.refreshBlocking(peerFp) }
                        val dev = kotlinx.coroutines.runBlocking { store.device(peerFp) }
                        main.post { _status.value = Status("Connected to ${dev?.displayName ?: name}.", dev?.displayName ?: name, connected = true) }
                    }
                }, "companion-remember").apply { isDaemon = true; start() }
            } else {
                main.post { _status.value = Status("Connected to $name.", name, connected = true) }
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
                        _status.value = Status("Connected. Pairing…", label, connected = true)
                        pairing.onConnected(peerFp)
                    }
                }
            }, "companion-touch").apply { isDaemon = true; start() }
        }
        client.onMessage = { msg -> main.post { route(msg) } }
        client.onDisconnected = { willRetry ->
            main.post {
                if (!willRetry) _status.value = Status("Disconnected.", _status.value.paired, connected = false)
            }
        }
        client.onError = { err -> postStatus(err) }
        this.pairing = pairing
        this.client = client
        main.post {
            val label = dev?.displayName ?: pins.all().firstOrNull()?.name
            _status.value = Status("Connecting…", label, connected = false)
        }
        client.connect()
    }

    private fun postStatus(s: String) {
        main.post { _status.value = Status(s, _status.value.paired, _status.value.connected) }
    }

    private fun route(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello_ack", "pair_result" -> pairing?.onMessage(msg)
            else -> engine?.onMessage(msg)
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
            postStatus("Listening for phones on port ${l.boundPort}.")
        } catch (e: Exception) {
            postStatus("Phone listener failed: ${e.message}")
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
                                store.remember(key, name, conn.remoteHost, port)
                                grants.refresh()
                            }
                        }
                        main.post {
                            peerSessions[key]?.engine?.refreshPolicy()
                            _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                            publishPeers()
                            postStatus("$name connected.")
                        }
                    }, "peer-remember").apply { isDaemon = true; start() }
                }
                override fun pairingFailed(peerFp: String, reason: String) {
                    _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                    postStatus("Pairing failed: $reason")
                    publishPeers()
                }
                override fun showStatus(s: String) = postStatus(s)
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
        s.handshake.onMessage(fp, msg)
        s.engine.onMessage(msg)
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
                _peers.value = fps.map { fp ->
                    PeerInfo(fp, byFp[fp]?.displayName ?: "phone", fp in connected)
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
                val eng = synchronized(peerLock) {
                    guestSessions.values.firstOrNull { it.fp == hostFp }?.engine
                }
                val name = eng?.remoteIndex()?.firstOrNull { it.id == remoteId }?.path
                    ?.substringAfterLast('/')?.ifEmpty { null } ?: "shared note"
                _guestOpen.value = GuestOpen(hostFp, remoteId, cacheUri, name)
            }
        }
        override fun onResult(hostFp: String, ok: Boolean, reason: String) {
            postStatus(if (ok) "Done." else "Declined${if (reason.isNotEmpty()) ": $reason" else "."}")
        }
        override fun onError(reason: String) = postStatus(reason)
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
            }
        }
        client.onError = { err -> postStatus(err) }
        synchronized(peerLock) { guestSessions[key] = session }
        rememberGuestTarget(key)
        publishGuests()
        client.connect()
    }

    private fun guestRoute(s: GuestSession, msg: JSONObject) {
        when (msg.optString("t")) {
            "hello_ack", "pair_result" -> s.pairing.onMessage(msg)
            else -> s.engine.onMessage(msg)
        }
    }

    private fun guestPairingUi(key: String): PairingUi = object : PairingUi {
        override fun showCodeEntry() = Unit
        override fun showStatus(s: String) = postStatus(s)
        override fun pairingDone(name: String) {
            _sasOutbound.value?.takeIf { it.key == key }?.let { _sasOutbound.value = null }
            postStatus("Paired with $name.")
        }
        override fun pairingFailed(reason: String) {
            _sasOutbound.value?.takeIf { it.key == key }?.let { _sasOutbound.value = null }
            postStatus("Pairing failed: $reason")
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
                kotlinx.coroutines.runBlocking { store?.remember(fp.lowercase(), name, s.host, s.port) }
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
        val overlay = RemoteLiveStrokes(editor.state).also { it.showingFile = uri }
        editor.controller.remoteLive = overlay
        editor.controller.onStrokeStarted = {
            // A previous gesture that never committed (dismiss tap): close its run first.
            pendingLive.remove(editor)?.let { stale ->
                if (guest != null) guest.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color)
                else eachHost { it.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color) }
            }
        }
        editor.controller.onStrokeSample = { page, x, y, p ->
            liveSample(editor, uri, page, x, y, p)
        }
        editor.controller.onStrokeCommitted = { page, stroke ->
            val live = pendingLive.remove(editor)
            if (guest != null) {
                if (live != null) {
                    guest.finishStroke(uri, page, live.id, live.tool, live.width, live.color)
                    guest.submitLocalStroke(uri, page, stroke, live.id)
                } else {
                    guest.submitLocalStroke(uri, page, stroke, newDedupe())
                }
            } else if (live != null) {
                eachHost {
                    it.finishStroke(uri, page, live.id, live.tool, live.width, live.color)
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
        editor.onImageInserted = { page, item ->
            val id = newDedupe()
            runCatching { item.image.file.readBytes() }.getOrNull()?.let { bytes ->
                val part = item.part?.let { com.xnotes.core.geometry.Rect(it.x, it.y, it.right, it.bottom) }
                if (guest != null) {
                    guest.submitLocalImage(uri, page, id, bytes, item.image.width, item.image.height,
                        item.rect, item.orientation, item.angle, item.flip.x, item.flip.y, part, id)
                } else eachHost {
                    it.submitLocalImage(uri, page, id, bytes, item.image.width, item.image.height,
                        item.rect, item.orientation, item.angle, item.flip.x, item.flip.y, part, id)
                }
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

    private data class LiveStroke(val uri: String, val page: Int, val id: String, val tool: String, val width: Double, val color: String)

    private data class PendingRoom(val uri: String, val page: Int, val id: String)

    private var pendingRoom: PendingRoom? = null

    private val pendingLive = HashMap<Editor, LiveStroke>()
    private var dedupeCounter = 0

    private fun newDedupe(): String = "h${dedupeCounter++}-${System.nanoTime().toString(16)}"

    private fun liveSample(editor: Editor, uri: String, page: Int, x: Double, y: Double, p: Double) {
        val guest = guestForCacheUri(uri)
        if (guest == null && engine == null && peerSessions.isEmpty()) return
        var live = pendingLive[editor]
        if (live != null && (live.uri != uri || live.page != page)) {
            // Crossed onto another page mid-stroke: close this run, open the next.
            val stale = live
            if (guest != null) guest.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color)
            else eachHost { it.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color) }
            live = null
        }
        if (live == null) {
            val tool = editor.controller.tool
            val cfg = editor.controller.configFor(tool)
            live = LiveStroke(uri, page, newDedupe(), tool.id, cfg.baseWidth, SyncWire.hexColor(cfg.rgba)).also { pendingLive[editor] = it }
        }
        val cur = live ?: return
        if (guest != null) guest.liveSample(uri, page, cur.id, cur.tool, cur.width, cur.color, x, y, p)
        else eachHost { it.liveSample(uri, page, cur.id, cur.tool, cur.width, cur.color, x, y, p) }
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

    @Volatile private var guestCachePrefix: String? = null

    private fun detachEditor(editor: Editor) {
        editor.controller.onStrokeStarted = null
        editor.controller.onStrokeSample = null
        editor.controller.onStrokeCommitted = null
        editor.controller.onStrokesErased = null
        editor.state.onPageChanged = null
        editor.onPageInserted = null
        editor.onPageRemoved = null
        editor.onImageInserted = null
        editor.onFileContentReplaced = null
        editor.controller.onRoomPreview = null
        editor.controller.onRoomCommitted = null
        editor.controller.onRoomPreviewDone = null
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(getString(R.string.share_notif_title))
            .setContentText(getString(R.string.share_starting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyUp(device: String, port: Int) {
        runCatching {
            val open = packageManager.getLaunchIntentForPackage(packageName)
                ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val stop = PendingIntent.getService(
                this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification_share)
                    .setContentTitle(getString(R.string.share_notif_title))
                    .setContentText(getString(R.string.companion_notif_text, device, port))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .setContentIntent(open)
                    .addAction(0, getString(R.string.share_stop), stop)
                    .build(),
            )
        }
    }

    private fun fgsType(): Int =
        if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    data class Status(val text: String, val paired: String?, val connected: Boolean)

    /** An inbound Android peer (this phone hosts files for it). */
    data class PeerInfo(val fp: String, val name: String, val connected: Boolean)

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

    /** A fetched peer file ready to open in the editor. */
    data class GuestOpen(val hostFp: String, val remoteId: String, val cacheUri: String, val name: String)

    companion object {
        private const val CHANNEL = "companion"
        private const val NOTIFICATION_ID = 43
        private const val APPROVAL_ID = 100
        const val ACTION_STOP = "com.xnotes.share.companion.STOP"
        const val ACTION_REFRESH = "com.xnotes.share.companion.REFRESH"
        const val ACTION_APPROVAL = "com.xnotes.share.companion.APPROVAL"
        const val ACTION_SAS = "com.xnotes.share.companion.SAS"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_SAS_KEY = "sas_key"

        private val _status = MutableStateFlow(Status("Off", null, false))
        val status: StateFlow<Status> = _status.asStateFlow()
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
        private val _guestOpen = MutableStateFlow<GuestOpen?>(null)
        /** A fetched peer file the UI should open; cleared after consuming. */
        val guestOpen: StateFlow<GuestOpen?> = _guestOpen.asStateFlow()

        const val PREFS = "companion"
        const val KEY_IP = "companion_last_ip"
        const val KEY_PORT = "companion_last_port"
        const val KEY_ENABLED = "companion_enabled"
        const val KEY_NAME = "companion_own_name"
        const val KEY_GUESTS = "companion_guest_targets"

        /** Start the service on app launch when sharing was left on. */
        fun startIfEnabled(context: Context) {
            val on = try {
                context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, false)
            } catch (_: Exception) {
                false
            }
            if (on) start(context)
        }

        fun submitCode(context: Context, code: String) {
            val svc = runningService
            if (svc == null) {
                ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                return
            }
            svc.main.post { svc.pairing?.submitCode(code) ?: svc.postStatus("Not connected yet — try again in a moment.") }
        }

        /** Re-dial an ad-hoc address without stopping sharing (pairing then registers it). */
        fun reconnect(context: Context, host: String, port: Int) {            val svc = runningService
            if (svc == null) {
                ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                return
            }
            svc.main.post {
                svc.targetHost = host
                svc.targetPort = port
                if (svc.engine == null) {
                    svc.startSession()
                } else {
                    svc.dialDevice(null)
                }
            }
        }

        // --- Android-to-Android phones ---

        /**
         * Dial an Android peer as guest (this phone reads that phone's files).
         * Several guests may be connected at once. When [port] is 0 the
         * listener port is probed (42317..42347) so the user only types the IP.
         */
        fun dialPeer(context: Context, host: String, port: Int) {
            val svc = runningService
            if (svc == null) {
                ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                // The fresh session auto-dials remembered guests only; retry shortly.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runningService?.let { s ->
                        s.main.post { s.startGuestSession(host, if (port > 0) port else 42317) }
                    }
                }, 3000)
                return
            }
            svc.main.post {
                if (svc.sharedVersions == null) {
                    svc.postStatus("Sharing is still starting — try again in a moment.")
                    return@post
                }
                if (port in 1..65535) {
                    svc.startGuestSession(host, port)
                } else {
                    svc.postStatus("Probing $host for a phone…")
                    Thread({
                        val found = probePeer(host)
                        svc.main.post {
                            if (found != null) svc.startGuestSession(host, found)
                            else svc.postStatus("No phone found on $host (tried 42317–42347).")
                        }
                    }, "peer-probe").apply { isDaemon = true; start() }
                }
            }
        }

        /** TCP probe for an Android listener on [host] (42317..42347). */
        private fun probePeer(host: String): Int? {
            val threads = ArrayList<Thread>()
            val found = java.util.concurrent.atomic.AtomicInteger(-1)
            for (p in 42317..42347) {
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

        /** The UI opened the fetched file: clear the one-shot request. */
        fun consumeGuestOpen() {
            _guestOpen.value = null
        }

        /** Allow an inbound peer whose SAS matches ([key] from [sasInbound]). */
        fun confirmInboundSas(context: Context, key: String) {
            val svc = runningService ?: return
            svc.main.post {
                _sasInbound.value?.takeIf { it.key == key }?.let { _sasInbound.value = null }
                synchronized(svc.peerLock) { svc.peerSessions[key] }?.handshake?.setApproved(key, true)
                    ?: svc.postStatus("That phone already left.")
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
                        _status.value = Status("Forgot phone.", _status.value.paired, connected = false)
                    }
                }
            }, "peer-forget").apply { isDaemon = true; start() }
        }

        /** Make [fp] the active companion and dial it, parking the others. */
        fun switchDevice(context: Context, fp: String) {
            val svc = runningService
            if (svc == null) {
                ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
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
                            _status.value = Status("Forgot companion.", null, connected = false)
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
                                _status.value = _status.value.copy(paired = dev.displayName)
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
                    _status.value = Status("Disconnected.", _status.value.paired, connected = false)
                }
            }
        }

        @Volatile private var runningService: CompanionService? = null

        fun start(context: Context) {
            ensureChannel(context)
            try {
                context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, true).apply()
            } catch (_: Exception) {
            }
            ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
        }

        fun stop(context: Context) {
            try {
                context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, false).apply()
            } catch (_: Exception) {
            }
            context.stopService(Intent(context, CompanionService::class.java))
        }

        fun refreshPolicy(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, CompanionService::class.java).setAction(ACTION_REFRESH),
            )
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
