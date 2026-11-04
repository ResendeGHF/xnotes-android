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
import com.xnotes.share.SharePolicy
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
    private var starting = AtomicBoolean(false)
    private val approvals = HashMap<String, (Boolean) -> Unit>()
    private var approvalSeq = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        runningService = this
        CompanionEditors.onTracked = { editor, uri ->
            if (engine == null) {
                CompanionEditors.onTracked = null
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
                val backend = ShareBackendImpl(applicationContext)
                val policy = SharePolicy.of(shareDir)
                val versions = File(shareDir, "sync_versions.json")
                val prefs = getSharedPreferences(PREFS, 0)
                val targetHost = prefs.getString(KEY_IP, "")?.ifEmpty { null }
                val targetPort = prefs.getString(KEY_PORT, "42317")?.toIntOrNull() ?: 42317
                val deviceName = android.os.Build.MODEL ?: "Android"
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

                    override fun liveOverlay(uri: String): RemoteLiveStrokes? =
                        CompanionEditors.editorFor(uri)?.controller?.remoteLive

                    override fun onApproval(kind: String, device: String, name: String, respond: (Boolean) -> Unit) {
                        askApproval(kind, device, name, respond)
                    }

                    override fun onError(reason: String) {
                        _status.value = Status(reason, _status.value.paired, connected = false)
                    }
                }
                val engine = HostEngine(backend, BackendFiles(backend), policy, versions, host, deviceName)
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
                var pairingRef: PairingClient? = null
                val client = CompanionClient(
                    host = targetHost ?: "127.0.0.1",
                    port = targetPort,
                    identity = identity,
                    pinnedFp = pins.all().firstOrNull()?.fp,
                    autoReconnect = true,
                )
                val sender = PairingClient.Sender { msg -> client.send(msg) }
                val pairing = PairingClient(deviceName, identity.fingerprintHex, pins, ui, sender)
                pairingRef = pairing
                pairing.onPaired = { postStatus("Connected.") }
                client.onConnected = { peerFp ->
                    main.post {
                        _status.value = Status("Connected. Pairing…", _status.value.paired, connected = true)
                        pairing.onConnected(peerFp)
                    }
                }
                client.onMessage = { msg -> main.post { route(msg, pairing, engine) } }
                client.onDisconnected = { willRetry ->
                    main.post {
                        if (!willRetry) _status.value = Status("Disconnected.", _status.value.paired, connected = false)
                    }
                }
                client.onError = { err -> postStatus(err) }
                this.backend = backend
                this.engine = engine
                this.pairing = pairing
                this.client = client
                main.post { _status.value = Status("Connecting…", pins.all().firstOrNull()?.name, connected = false) }
                client.connect()
            } catch (e: Exception) {
                postStatus("Start failed: ${e.message}")
                starting.set(false)
                stopSelf()
            }
        }, "companion-start").apply { isDaemon = true; start() }
    }

    private fun postStatus(s: String) {
        main.post { _status.value = Status(s, _status.value.paired, _status.value.connected) }
    }

    private fun route(msg: JSONObject, pairing: PairingClient, engine: HostEngine) {
        when (msg.optString("t")) {
            "hello_ack", "pair_result" -> pairing.onMessage(msg)
            else -> engine.onMessage(msg)
        }
    }

    private fun shutdown() {
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
                engine.finishStroke(uri, editor.state.currentPageIndex(), stale.id, stale.tool, stale.width, stale.color)
            }
        }
        editor.controller.onStrokeSample = { page, x, y, p ->
            liveSample(editor, uri, page, x, y, p)
        }
        editor.controller.onStrokeCommitted = { page, stroke ->
            pendingLive.remove(editor)
            engine.submitLocalStroke(uri, page, stroke, newDedupe())
        }
        editor.controller.onStrokesErased = { page, strokes ->
            val ids = strokes.map { SyncWire.fingerprintOf(it) }
            if (ids.isNotEmpty()) engine.submitLocalErase(uri, page, ids, newDedupe())
        }
        editor.state.onPageChanged = { page -> engine.presence(uri, page) }
        engine.presence(uri, editor.state.currentPageIndex())
    }

    private data class LiveStroke(val id: String, val tool: String, val width: Double, val color: String)

    private val pendingLive = HashMap<Editor, LiveStroke>()
    private var dedupeCounter = 0

    private fun newDedupe(): String = "h${dedupeCounter++}-${System.nanoTime().toString(16)}"

    private fun liveSample(editor: Editor, uri: String, page: Int, x: Double, y: Double, p: Double) {
        val engine = engine ?: return
        var live = pendingLive[editor]
        val tool = editor.controller.tool
        if (live == null) {
            val cfg = editor.controller.configFor(tool)
            live = LiveStroke(newDedupe(), tool.id, cfg.baseWidth, SyncWire.hexColor(cfg.rgba)).also { pendingLive[editor] = it }
        }
        engine.liveSample(uri, page, live.id, live.tool, live.width, live.color, x, y, p)
    }

    private fun detachEditor(editor: Editor) {
        editor.controller.onStrokeStarted = null
        editor.controller.onStrokeSample = null
        editor.controller.onStrokeCommitted = null
        editor.controller.onStrokesErased = null
        editor.state.onPageChanged = null
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

        const val PREFS = "companion"
        const val KEY_IP = "companion_last_ip"
        const val KEY_PORT = "companion_last_port"

        fun submitCode(context: Context, code: String) {
            val svc = runningService
            if (svc == null) {
                ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
                return
            }
            svc.main.post { svc.pairing?.submitCode(code) }
        }

        fun unpair(context: Context, name: String) {
            Thread({
                runCatching {
                    val pins = CompanionPins.of(File(context.filesDir, "share"))
                    pins.unpin(name)
                }
                runningService?.let { svc ->
                    svc.main.post {
                        svc.client?.disconnect()
                        _status.value = Status("Unpaired $name.", null, connected = false)
                    }
                }
            }, "companion-unpair").apply { isDaemon = true; start() }
        }

        @Volatile private var runningService: CompanionService? = null

        fun start(context: Context) {
            ensureChannel(context)
            ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
        }

        fun stop(context: Context) {
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
