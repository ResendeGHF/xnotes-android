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
            ACTION_REFRESH -> {
                engine?.refreshPolicy()
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
                val versions = File(shareDir, "sync_versions.json")
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
                val host = object : HostEngine.HostEngineHost {
                    override fun send(msg: JSONObject) {
                        client?.send(msg)
                    }

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
                val engine = HostEngine(
                    backend, BackendFiles(backend), grants, versions, host, deviceName,
                    imageDir = File(cacheDir, "sync-images").apply { mkdirs() },
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
                    // Redial the active companion (or the remembered address).
                    dialActive()
                }
            } catch (e: Exception) {
                postStatus("Start failed: ${e.message}")
                starting.set(false)
                stopSelf()
            }
        }, "companion-start").apply { isDaemon = true; start() }
    }

    private var ui: PairingUi? = null

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
                engine.finishStroke(stale.uri, stale.page, stale.id, stale.tool, stale.width, stale.color)
            }
        }
        editor.controller.onStrokeSample = { page, x, y, p ->
            liveSample(editor, uri, page, x, y, p)
        }
        editor.controller.onStrokeCommitted = { page, stroke ->
            val live = pendingLive.remove(editor)
            if (live != null) {
                engine.finishStroke(uri, page, live.id, live.tool, live.width, live.color)
                engine.submitLocalStroke(uri, page, stroke, live.id)
            } else {
                engine.submitLocalStroke(uri, page, stroke, newDedupe())
            }
        }
        editor.controller.onStrokesErased = { page, strokes ->
            val ids = strokes.map { SyncWire.fingerprintOf(it) }
            if (ids.isNotEmpty()) engine.submitLocalErase(uri, page, ids, newDedupe())
        }
        editor.controller.onRoomPreview = { page, sideways, split, resume, shift ->
            var live = pendingRoom
            if (live == null || live.uri != uri || live.page != page) {
                live = PendingRoom(uri, page, newDedupe())
                pendingRoom = live
            }
            engine.roomPreview(uri, page, live.id, sideways, split, resume, shift)
        }
        editor.controller.onRoomCommitted = { edit ->
            val live = pendingRoom?.takeIf { it.uri == uri && it.page == edit.page }
            pendingRoom = null
            val id = live?.id ?: newDedupe()
            val (split, resume, shift) = if (edit.open) Triple(edit.a, edit.a, edit.b - edit.a) else Triple(edit.a, edit.b, edit.a - edit.b)
            engine.finishRoom(uri, edit.page, id, edit.sideways, split, resume, shift)
            if (edit.open) engine.submitLocalRoomOpen(uri, edit.page, edit.sideways, edit.a, edit.b - edit.a, id)
            else engine.submitLocalRoomClose(uri, edit.page, edit.sideways, edit.a, edit.b, id)
        }
        editor.onFileContentReplaced = { engine?.pushFile(uri) }
        editor.onImageInserted = { page, item ->
            val eng = engine
            if (eng != null) {
                val id = newDedupe()
                runCatching { item.image.file.readBytes() }.getOrNull()?.let { bytes ->
                    val part = item.part?.let { com.xnotes.core.geometry.Rect(it.x, it.y, it.right, it.bottom) }
                    eng.submitLocalImage(uri, page, id, bytes, item.image.width, item.image.height,
                        item.rect, item.orientation, item.angle, item.flip.x, item.flip.y, part, id)
                }
            }
        }
        editor.controller.onRoomPreviewDone = {
            pendingRoom?.takeIf { it.uri == uri }?.let {
                pendingRoom = null
                engine.finishRoom(uri, it.page, it.id, false, 0.0, 0.0, 0.0)
            }
        }
        editor.state.onPageChanged = { page -> engine.presence(uri, page) }
        editor.onPageInserted = { index, w, h -> engine.submitLocalPageAdd(uri, index, w, h, newDedupe()) }
        editor.onPageRemoved = { index -> engine.submitLocalPageRemove(uri, index, newDedupe()) }
        engine.presence(uri, editor.state.currentPageIndex())
    }

    private data class LiveStroke(val uri: String, val page: Int, val id: String, val tool: String, val width: Double, val color: String)

    private data class PendingRoom(val uri: String, val page: Int, val id: String)

    private var pendingRoom: PendingRoom? = null

    private val pendingLive = HashMap<Editor, LiveStroke>()
    private var dedupeCounter = 0

    private fun newDedupe(): String = "h${dedupeCounter++}-${System.nanoTime().toString(16)}"

    private fun liveSample(editor: Editor, uri: String, page: Int, x: Double, y: Double, p: Double) {
        val engine = engine ?: return
        var live = pendingLive[editor]
        if (live != null && (live.uri != uri || live.page != page)) {
            // Crossed onto another page mid-stroke: close this run, open the next.
            engine.finishStroke(live.uri, live.page, live.id, live.tool, live.width, live.color)
            live = null
        }
        if (live == null) {
            val tool = editor.controller.tool
            val cfg = editor.controller.configFor(tool)
            live = LiveStroke(uri, page, newDedupe(), tool.id, cfg.baseWidth, SyncWire.hexColor(cfg.rgba)).also { pendingLive[editor] = it }
        }
        engine.liveSample(uri, page, live.id, live.tool, live.width, live.color, x, y, p)
    }

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

    companion object {
        private const val CHANNEL = "companion"
        private const val NOTIFICATION_ID = 43
        private const val APPROVAL_ID = 100
        const val ACTION_STOP = "com.xnotes.share.companion.STOP"
        const val ACTION_REFRESH = "com.xnotes.share.companion.REFRESH"
        const val ACTION_APPROVAL = "com.xnotes.share.companion.APPROVAL"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALLOW = "allow"

        private val _status = MutableStateFlow(Status("Off", null, false))
        val status: StateFlow<Status> = _status.asStateFlow()
        private val _codeNeeded = MutableStateFlow(false)
        val codeNeeded: StateFlow<Boolean> = _codeNeeded.asStateFlow()
        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        const val PREFS = "companion"
        const val KEY_IP = "companion_last_ip"
        const val KEY_PORT = "companion_last_port"
        const val KEY_ENABLED = "companion_enabled"
        const val KEY_NAME = "companion_own_name"

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
        fun reconnect(context: Context, host: String, port: Int) {
            val svc = runningService
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
