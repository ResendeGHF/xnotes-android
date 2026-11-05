package com.xnotes.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xnotes.R
import java.net.NetworkInterface

/**
 * Hosts the LAN share server in the foreground while sharing is on.
 *
 * Everything the server exposes is TLS-only with a device-generated
 * certificate plus a bearer token (see [ShareCrypto]); there is no plaintext
 * listener, which is what makes this safe to run on public networks and at a
 * university. Pairing is trust-on-first-use: the notification shows the
 * certificate fingerprint and the desktop client pins it on first connect,
 * then authenticates every request with the token.
 *
 * Snapshot semantics: the backend derives versions from committed file
 * stamps and the PDF is (re)generated from stored bundle bytes, so the
 * desktop sees device edits once the device's save commits — never live
 * strokes. Desktop writes land through the same sealed temp+copy path as
 * local saves; if the device's editor has the same note open with unsaved
 * changes, its existing fork-on-external-change path keeps both copies.
 */
class ShareService : Service() {

    @Volatile private var server: ShareServer? = null
    @Volatile private var backend: ShareBackendImpl? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    /** High-perf Wi-Fi lock: without it large uploads stall once the phone idles. */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    /** Guards the async bring-up: the key ceremony runs off the main thread. */
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // HTTP serving is currently unwired (no toggle): never come up, so it
        // can never notify — this also stops sticky resurrections quietly.
        if (intent?.action == ACTION_STOP) stopSharing()
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSharing()
    }

    /**
     * Foreground first, key ceremony second. Generating the RSA identity can
     * take seconds on first run, and Android kills a foreground service that
     * dawdles before its notification — worse, it ANRs the settings screen
     * that just flipped the toggle. So the "starting" notice goes up on the
     * calling thread and the bind happens behind it.
     */
    private fun startSharing() {
        if (!starting.compareAndSet(false, true)) return
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildStartingNotification(), connectedType())
        Thread({
            try {
                val identity = ShareCrypto.loadOrCreate(filesDir)
                val locks = ShareLockManager()
                val backend = ShareBackendImpl(applicationContext)
                val server = ShareServer(
                    ShareProtocol.DEFAULT_PORT,
                    ShareCrypto.serverSslContext(identity),
                    backend,
                    locks,
                    identity.token,
                    logger = { msg -> android.util.Log.d("xnotes-share", msg) },
                )
                this.backend = backend
                this.server = server
                backend.startWatching()
                holdWifi()
                registerDiscovery(server.boundPort, identity.fingerprintHex)
                notifyUp(identity, server.boundPort)
                writePairingFile(identity, server.boundPort)
            } catch (_: Exception) {
                stopSelf()
            }
        }, "share-start").apply { isDaemon = true; start() }
    }

    private fun connectedType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    /** Swap the "starting" notice for the live one once the socket is bound. */
    private fun notifyUp(identity: ShareCrypto.ServerIdentity, port: Int) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(identity, port))
        }
    }

    private fun stopSharing() {
        runCatching {
            val listener = nsdListener
            if (listener != null) nsd?.unregisterService(listener)
        }
        nsdListener = null
        runCatching { server?.stop() }
        server = null
        backend = null
        starting.set(false)
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Keep the radio awake at full throughput while serving; released in [stopSharing]. */
    @Suppress("DEPRECATION") // FULL_HIGH_PERF is deprecated but still the only mode that holds throughput.
    private fun holdWifi() {
        runCatching {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xnotes:share").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun registerDiscovery(port: Int, fingerprintHex: String) {
        val manager = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "xnotes-share"
            serviceType = ShareProtocol.MDNS_TYPE
            setPort(port)
            setAttribute("fp", fingerprintHex.take(16))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = manager
            nsdListener = listener
        } catch (_: Exception) {
            // Discovery is best-effort; the server works via direct IP without it.
        }
    }

    /** Pairing material for the desktop client; private app storage, pulled over adb until the settings UI lands. */
    private fun writePairingFile(identity: ShareCrypto.ServerIdentity, port: Int) {
        runCatching {
            val addresses = hostAddresses()
            val first = addresses.firstOrNull() ?: "127.0.0.1"
            val json = org.json.JSONObject()
                .put("url", "https://$first:$port")
                .put("port", port)
                .put("fingerprint", identity.fingerprintHex)
                .put("token", identity.token)
            val dir = java.io.File(filesDir, "share")
            val tmp = java.io.File(dir, "pairing.json.tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            val dst = java.io.File(dir, "pairing.json")
            if (!tmp.renameTo(dst)) {
                dst.delete()
                tmp.renameTo(dst)
            }
        }
    }

    private fun buildStartingNotification(): Notification {
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

    private fun buildNotification(identity: ShareCrypto.ServerIdentity, port: Int): Notification {
        ensureChannel(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val address = hostAddresses().firstOrNull() ?: "…"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_share)
            .setContentTitle(getString(R.string.share_notif_title))
            .setContentText(getString(R.string.share_notif_text, address, port))
            .setSubText(getString(R.string.share_notif_fp, ShareCrypto.displayFingerprint(identity.fingerprintHex).take(23)) + "…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.share_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "share"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.xnotes.share.STOP"

        fun start(context: Context) {
            ensureChannel(context)
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }

        /** Drop a lingering status card when the service itself is not running. */
        fun cancelNotifications(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                // The ID is a stable constant, never the display name: the name
                // is localized and the ID must survive locale changes.
                val channel = NotificationChannel(CHANNEL, context.getString(R.string.share_channel), NotificationManager.IMPORTANCE_LOW)
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
            // One-time cleanup: the first build created the channel under its
            // display name instead of the stable ID; remove that stillborn one.
            val bogus = context.getString(R.string.share_channel)
            if (bogus != CHANNEL) runCatching { nm.deleteNotificationChannel(bogus) }
        }

        /** LAN-reachable IPv4 addresses, site-local first. */
        fun hostAddresses(): List<String> {
            val out = ArrayList<String>()
            val site = ArrayList<String>()
            runCatching {
                val ifs = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
                while (ifs.hasMoreElements()) {
                    val nic = ifs.nextElement()
                    if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
                    val addrs = nic.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                        if (addr !is java.net.Inet4Address) continue
                        val host = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress) site.add(host) else out.add(host)
                    }
                }
            }
            return site + out
        }
    }
}
