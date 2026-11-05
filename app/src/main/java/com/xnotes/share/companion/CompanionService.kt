package com.xnotes.share.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.xnotes.share.ShareProtocol
import com.xnotes.share.ShareService
import com.xnotes.ui.Editor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host side of SYNC.md v1, in the foreground while the companion half is on:
 * dials the Linux companion over mutual-TLS, serves the shared index and
 * file bytes, applies companion ops to open documents (or straight to
 * files), broadcasts local commits, and streams live strokes both ways with
 * no batching.
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
        shutdown()
        _status.value = Status("Off", null, false)
        _companionStatus.value = Status("Off", null, false)
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_COMPANION)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_COMPANION -> {
                setCompanionEnabled(this, false)
                return START_NOT_STICKY
            }
            ACTION_APPROVAL -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
                val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
                synchronized(approvals) { approvals.remove(token) }?.invoke(allow)
                // Approval taps must never keep a switched-off service alive.
                if (!coreReady && !isCompanionOn(this)) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                engine?.refreshPolicy()
                if (!coreReady && !isCompanionOn(this)) stopSelf()
                return START_NOT_STICKY
            }
        }
        // Boot (or system restart): only come up when the companion half is
        // switched on. Otherwise stop immediately — no phantom notification.
        if (!isCompanionOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureCore { applyEnabledFeatures() }
        return START_STICKY
    }

    // --- session ---

    /**
     * One-time process setup: identity, store, backend, engine and the
     * editor bridge. Idempotent; the network half starts separately in
     * [applyEnabledFeatures].
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
            ServiceCompat.startForeground(this, NOTIF_COMPANION, startingNotification(), fgsType())
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
                this.targetHost = prefs.getString(KEY_IP, "") ?: ""
                this.targetPort = prefs.getString(KEY_PORT, "42317")?.toIntOrNull() ?: 42317
                this.identity = identity
                this.pins = pins
                this.store = store
                this.grants = grants
                val versions = SyncVersions(shareDir)
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
                _active.value = isCompanionOn(this)
                coreReady = true
                starting.set(false)
                main.post {
                    _companionOn.value = isCompanionOn(this)
                    _active.value = _companionOn.value
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

    /** Start the companion half whose flag is on; called once core is ready. */
    private fun applyEnabledFeatures() {
        if (isCompanionOn(this)) startCompanionDial() else stopCompanionDial()
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

    /** Stop the service when the companion half is off; otherwise refresh notifications. */
    private fun stopSelfIfIdle() {
        main.post {
            if (!isCompanionOn(this)) {
                stopSelf()
            } else {
                updateForeground()
            }
        }
    }

    private var ui: PairingUi? = null

    /**
     * Document-access bridge for the [HostEngine], sending over [sendFn].
     * Serves the dialed Linux companion through the open-document registry,
     * so the on-screen editor and the sync engine converge on one version.
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

    /**
     * Force a dial socket out the Wi-Fi interface when one is up. Without
     * this, mobile data or a VPN can become the default route and a
     * same-LAN companion dial fails with "No route to host". Best effort:
     * any failure falls back to default routing.
     */
    private fun bindWifi(sock: java.net.Socket) {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            // Default route already goes out Wi-Fi: binding would only risk a
            // stale Network object, so leave the socket alone.
            val active = runCatching { cm.activeNetwork }.getOrNull()
            val activeCaps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return
            for (net in cm.allNetworks) {
                val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    net.bindSocket(sock)
                    return
                }
            }
        } catch (_: Exception) {
        }
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
        val host = if (!dev?.host.isNullOrEmpty()) dev.host else targetHost
        val port = if (dev != null && dev.port in 1..65535) dev.port else targetPort
        if (host.isEmpty()) {
            // Nothing remembered (e.g. right after forgetting): never dial
            // blindly. Pairing again sets an explicit target.
            postCompanionStatus("No companion remembered — pair a new one.")
            return
        }
        // Reuse a live session while a handshake is in flight: toggle
        // auto-dial plus a manual Pair tap (or two taps) must not tear down
        // a pairing in progress — each new socket kills the previous one
        // server-side and in-flight acks/results die with it (reconnect
        // storm). A finished/failed pairing redials fresh so Pair retries.
        val live = client
        if (live != null && live.connected && live.isTarget(host, port) && pairing?.isInFlight == true) return
        runCatching { pairing?.close() }
        runCatching { client?.disconnect() }
        pairing = null
        client = null
        if (dev != null) {
            targetHost = host
            targetPort = port
            getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_IP, host).putString(KEY_PORT, port.toString()).apply()
        }
        val sender = PairingClient.Sender { msg -> client?.send(msg) }
        val pairing = PairingClient(deviceName, identity.fingerprintHex, pins, ui ?: return, sender)
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
        // Pin the dial to Wi-Fi (never for loopback/USB): otherwise mobile
        // data or a VPN can swallow the route and a same-LAN companion fails
        // with "No route to host".
        if (!CompanionClient.isLoopbackHost(host)) {
            client.socketBinder = { sock -> bindWifi(sock) }
        }
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

    /** Legacy combined status mirrors the companion half. */
    private fun mirrorStatus() {
        _status.value = _companionStatus.value
    }

    private fun route(msg: JSONObject) {
        runCatching {
            when (msg.optString("t")) {
                "hello_ack", "pair_result" -> pairing?.onMessage(msg)
                else -> engine?.onMessage(msg)
            }
        }
    }

    /** Start the Linux-companion half: dial the active (or remembered) device. */
    private fun startCompanionDial() {
        // Refresh the remembered target: prefs are the source of truth and
        // may have moved on (typed IP, forget purge) since the field was set.
        // Without this the dial keeps using a stale address after DHCP moves
        // the desktop or after forgetting (which clears the target).
        val prefs = getSharedPreferences(PREFS, 0)
        targetHost = prefs.getString(KEY_IP, "") ?: ""
        targetPort = prefs.getString(KEY_PORT, "42317")?.toIntOrNull() ?: 42317
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

    private fun shutdown() {
        _active.value = false
        starting.set(false)
        runCatching { pairing?.close() }
        runCatching { client?.disconnect() }
        pairing = null
        client = null
        engine = null
        backend = null
        CompanionEditors.onTracked = null
    }

    // --- editors ---

    private fun attachEditor(editor: Editor, uri: String) {
        val engine = engine ?: return
        if (CompanionEditors.uriFor(editor) != uri) return
        val overlay = RemoteLiveStrokes(editor.state).also { it.showingFile = uri }
        editor.controller.remoteLive = overlay
        editor.controller.onStrokeStarted = {
            // A previous gesture that never committed (dismiss tap): close its run first.
            pendingLive.remove(editor)?.let { stale ->
                eachHost { it.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color, stale.style) }
            }
        }
        editor.controller.onStrokeSample = { page, x, y, p ->
            liveSample(editor, uri, page, x, y, p)
        }
        editor.controller.onStrokeCommitted = { page, stroke ->
            val live = pendingLive.remove(editor)
            if (live != null) {
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
                val id = newDedupe()
                eachHost { it.submitLocalErase(uri, page, ids, id) }
            }
        }
        editor.controller.onStrokesReplaced = { changes ->
            val id = newDedupe()
            eachHost { it.submitLocalReplacements(uri, changes, id) }
        }
        editor.controller.onRoomPreview = { page, sideways, split, resume, shift ->
            var live = pendingRoom
            if (live == null || live.uri != uri || live.page != page) {
                live = PendingRoom(uri, page, newDedupe())
                pendingRoom = live
            }
            eachHost { it.roomPreview(uri, page, live.id, sideways, split, resume, shift) }
        }
        editor.controller.onRoomCommitted = { edit ->
            val live = pendingRoom?.takeIf { it.uri == uri && it.page == edit.page }
            pendingRoom = null
            val id = live?.id ?: newDedupe()
            val (split, resume, shift) = if (edit.open) Triple(edit.a, edit.a, edit.b - edit.a) else Triple(edit.a, edit.b, edit.a - edit.b)
            eachHost { it.finishRoom(uri, edit.page, id, edit.sideways, split, resume, shift) }
            if (edit.open) eachHost { it.submitLocalRoomOpen(uri, edit.page, edit.sideways, edit.a, edit.b - edit.a, id) }
            else eachHost { it.submitLocalRoomClose(uri, edit.page, edit.sideways, edit.a, edit.b, id) }
        }
        editor.onFileContentReplaced = {
            eachHost { it.pushFile(uri) }
        }
        editor.onMarkupsChanged = { added, removed ->
            if (added.isNotEmpty() || removed.isNotEmpty()) {
                val id = newDedupe()
                eachHost { it.submitLocalMarkups(uri, added, removed, id) }
            }
        }
        editor.onHistoryStepSync = { cmd -> syncContentCommand(editor, uri, cmd) }
        editor.onPageStyleChanged = { entries, docStyle, docMargins ->
            // Style/margin commits carry no undo step; the callback fires once per set.
            val id = newDedupe()
            eachHost { it.submitLocalPageStyle(uri, entries, docStyle, docMargins, id) }
        }
        editor.controller.onMarkupsErased = { page, ids ->
            if (ids.isNotEmpty()) {
                val id = newDedupe()
                eachHost { it.submitLocalMarkups(uri, emptyList(), ids.map { page to it }, id) }
            }
        }
        // Text/table/image/audio edits fan out as granular uid-keyed ops both
        // ways (upsert on change, remove on delete); strokes/markups/pages/rooms
        // keep their dedicated hooks. Anything else converges via debounced
        // full-file push.
        val prevPush = editor.history.onPush
        editor.history.onPush = { cmd ->
            try { prevPush?.invoke(cmd) } catch (_: Exception) {}
            val covered = try {
                syncContentCommand(editor, uri, cmd)
            } catch (_: Exception) {
                false
            }
            if (!covered) {
                val name = cmd.javaClass.simpleName
                val isGranular = name == "AddMarkups" || name == "RemoveMarkup" || name == "ReplaceMarkup" ||
                    name == "CompositeCommand" || name.startsWith("AddPage") || name.startsWith("DeletePage") ||
                    name.startsWith("MovePages")
                if (!isGranular) pushFileSoon(uri)
            }
        }
        editor.controller.onRoomPreviewDone = {
            pendingRoom?.takeIf { it.uri == uri }?.let {
                pendingRoom = null
                eachHost { e -> e.finishRoom(uri, it.page, it.id, false, 0.0, 0.0, 0.0) }
            }
        }
        editor.state.onPageChanged = { page ->
            eachHost { it.presence(uri, page) }
        }
        editor.onPageInserted = { index, w, h ->
            val id = newDedupe()
            eachHost { it.submitLocalPageAdd(uri, index, w, h, id) }
        }
        editor.onPageRemoved = { index ->
            val id = newDedupe()
            eachHost { it.submitLocalPageRemove(uri, index, id) }
        }
        eachHost { it.presence(uri, editor.state.currentPageIndex()) }
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
     * image, audio) for the host engine. Strokes keep their dedicated
     * hooks and are skipped here. Returns true when nothing remains for the
     * pushFile fallback (every touched non-stroke item was covered).
     *
     * Removes come from pages that no longer hold the uid; everything still
     * present upserts its current snapshot, so creates, moves, restyles and
     * cell or text edits all converge with one code path.
     */
    private fun syncContentCommand(editor: Editor, uri: String, cmd: com.xnotes.core.history.Command): Boolean {
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
                        sendContentBytes(uri, imageId, bytes, kind = "image")
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
                        sendContentBytes(uri, audioId, bytes, kind = "audio")
                        sendContentBytes(uri, audioId, it.audio.peaks, kind = "peaks")
                        ops.put(JSONObject().put("op", "add_audio").put("page", pi).put("audio", SyncWire.toWireAudio(audioId, it, pi)))
                    }
                    else -> covered = false
                }
            }
            if (ops.length() > 0) {
                val id = newDedupe()
                eachHost { it.submitContentOps(uri, ops, id) }
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

    private fun sendContentBytes(uri: String, id: String, bytes: ByteArray, kind: String) {
        eachHost {
            if (kind == "audio") it.sendAudioBytes(uri, id, bytes)
            else if (kind == "peaks") it.sendAudioPeaks(uri, id, bytes)
            else it.sendImageBytes(uri, id, bytes)
        }
    }

    /**
     * Debounced full-file push for content ops have no wire form (text/table
     * edits, image moves/removes, shape refills): collapses a typing burst
     * into one reload so the companion converges in real time without
     * per-keystroke full files.
     */
    private fun pushFileSoon(uri: String) {
        synchronized(pushDebounce) { pushDebounce.remove(uri)?.let { main.removeCallbacks(it) } }
        val r = Runnable {
            synchronized(pushDebounce) { pushDebounce.remove(uri) }
            eachHost { it.pushFile(uri) }
        }
        synchronized(pushDebounce) { pushDebounce[uri] = r }
        main.postDelayed(r, 1200)
    }

    private fun liveSample(editor: Editor, uri: String, page: Int, x: Double, y: Double, p: Double) {
        if (engine == null) return
        var live = pendingLive[editor]
        if (live != null && (live.uri != uri || live.page != page)) {
            // Crossed onto another page mid-stroke: close this run, open the next.
            val stale = live
            eachHost { it.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color, stale.style) }
            live = null
        }
        if (live == null) {
            val tool = editor.controller.tool
            val cfg = editor.controller.resolvedStrokeConfig(tool)
            val style = SyncWire.toWireStyle(tool, cfg)
            live = LiveStroke(uri, page, newDedupe(), tool.id, cfg.baseWidth, SyncWire.hexColor(cfg.rgba), style).also { pendingLive[editor] = it }
        }
        val cur = live ?: return
        eachHost { it.liveSample(uri, page, cur.id, cur.tool, cur.width, cur.color, x, y, p, cur.style) }
    }

    /** The host engine serving this device's files to the Linux companion. */
    private fun eachHost(fn: (HostEngine) -> Unit) {
        engine?.let(fn)
    }

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

    /** Ongoing notification for the Linux-companion half (own foreground id). */
    private fun companionNotification(): Notification {
        ensureChannel(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val stop = PendingIntent.getService(
            this, 11, Intent(this, CompanionService::class.java).setAction(ACTION_STOP_COMPANION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val address = ShareService.hostAddresses().firstOrNull() ?: "…"
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(getString(R.string.share_notif_title))
            .setContentText(getString(R.string.share_notif_text, address, ShareProtocol.DEFAULT_PORT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.share_stop), stop)
        identity?.let { id ->
            note.setSubText(getString(R.string.share_notif_fp, ShareCrypto.displayFingerprint(id.fingerprintHex).take(23)) + "…")
        }
        return note.build()
    }

    /**
     * Keep the single foreground notification while the companion half is on;
     * none when it is off (then stop instead of lingering with a phantom
     * "sharing is on" card).
     */
    private fun updateForeground() {
        val compOn = isCompanionOn(this)
        _companionOn.value = compOn
        _active.value = compOn
        if (!compOn) {
            runCatching {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_COMPANION)
                // Stale id from when phone sharing had its own card.
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_SHARING)
            }
            stopSelf()
            return
        }
        try {
            // Stale id from when phone sharing had its own card; never posted now.
            runCatching {
                getSystemService(NotificationManager::class.java)?.cancel(NOTIF_SHARING)
            }
            ServiceCompat.startForeground(this, NOTIF_COMPANION, companionNotification(), fgsType())
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

    companion object {
        private const val CHANNEL = "companion"
        private const val NOTIFICATION_ID = 43 // legacy single id; kept for cancel compat
        private const val NOTIF_COMPANION = 43
        private const val NOTIF_SHARING = 44
        private const val APPROVAL_ID = 100
        const val ACTION_STOP_COMPANION = "com.xnotes.share.companion.STOP_COMPANION"
        const val ACTION_REFRESH = "com.xnotes.share.companion.REFRESH"
        const val ACTION_APPROVAL = "com.xnotes.share.companion.APPROVAL"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALLOW = "allow"

        private val _status = MutableStateFlow(Status("Off", null, false))
        val status: StateFlow<Status> = _status.asStateFlow()
        private val _companionStatus = MutableStateFlow(Status("Off", null, false))
        /** Linux-companion half status; Companion screen observes this only. */
        val companionStatus: StateFlow<Status> = _companionStatus.asStateFlow()
        private val _codeNeeded = MutableStateFlow(false)
        val codeNeeded: StateFlow<Boolean> = _codeNeeded.asStateFlow()
        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        const val PREFS = "companion"
        const val KEY_IP = "companion_last_ip"
        const val KEY_PORT = "companion_last_port"
        const val KEY_ENABLED = "companion_enabled" // legacy: migrated to the two flags below
        const val KEY_COMPANION = "companion_on"
        const val KEY_NAME = "companion_own_name"

        private val _companionOn = MutableStateFlow(false)
        /** Linux companion dialing is switched on. */
        val companionOn: StateFlow<Boolean> = _companionOn.asStateFlow()
        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

        /** One-time migration from the old single toggle. */
        private fun migrateFeatureFlags(context: Context) {
            try {
                val p = prefs(context)
                if (!p.contains(KEY_COMPANION) && p.contains(KEY_ENABLED)) {
                    val on = p.getBoolean(KEY_ENABLED, false)
                    p.edit().putBoolean(KEY_COMPANION, on).remove(KEY_ENABLED).apply()
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

        private fun setFlag(context: Context, key: String, on: Boolean) {
            try {
                migrateFeatureFlags(context)
                prefs(context).edit().putBoolean(key, on).apply()
            } catch (_: Exception) {
            }
        }

        /** Start the service when the companion half is switched on. */
        fun startIfEnabled(context: Context) {
            syncFlagFlows(context)
            if (!isCompanionOn(context)) return
            ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
        }

        /** Push persisted toggles into the flows (cold UI before the service boots). */
        fun syncFlagFlows(context: Context) {
            try {
                val comp = isCompanionOn(context)
                _companionOn.value = comp
                _active.value = comp
            } catch (_: Exception) {
            }
        }

        /**
         * Switch the Linux-companion half on/off. It is the single gate for
         * every LAN-sharing notification: turning it on boots the service
         * when needed, dials, and serves files over HTTP for the desktop
         * companion; turning it off drops the dial, stops the HTTP server,
         * and clears their notifications.
         */
        fun setCompanionEnabled(context: Context, on: Boolean) {
            setFlag(context, KEY_COMPANION, on)
            _companionOn.value = on
            _active.value = on
            // The HTTP file server has no toggle of its own: keep it stopped
            // so it can never notify on its own. (The desktop HTTP companion
            // path is dormant until it gets an explicit switch.)
            if (!on) ShareService.stop(context)
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

        /** Drop lingering status cards when the companion half is switched off. */
        fun cancelNotifications(context: Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.cancel(NOTIF_COMPANION)
                // Stale id from when phone sharing had its own card.
                nm.cancel(NOTIF_SHARING)
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
                    // Purge the remembered dial target so the forgotten
                    // companion is never redialed: no address, no
                    // auto-connect, until it is paired again from scratch.
                    try {
                        context.getSharedPreferences(PREFS, 0).edit()
                            .remove(KEY_IP).remove(KEY_PORT).apply()
                    } catch (_: Exception) {
                    }
                }
                runningService?.let { svc ->
                    svc.main.post {
                        svc.targetHost = ""
                        svc.targetPort = 42317
                        if (svc.grants?.activeFp == fp) {
                            svc.grants?.activeFp = null
                        }
                        runCatching { svc.pairing?.close() }
                        svc.pairing = null
                        runCatching { svc.client?.disconnect() }
                        svc.client = null
                        _companionStatus.value = Status("Forgot companion.", null, connected = false)
                        svc.mirrorStatus()
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

        /** Legacy single toggle (kept for compat): enables the companion half. */
        fun start(context: Context) {
            ensureChannel(context)
            setCompanionEnabled(context, true)
        }

        /** Legacy single stop (kept for compat): disables the companion half. */
        fun stop(context: Context) {
            setCompanionEnabled(context, false)
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
