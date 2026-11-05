package com.xnotes.share.companion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** UI surface the pairing handshake drives. Implemented by CompanionPane. */
interface PairingUi {
    fun showCodeEntry()
    fun showStatus(s: String)
    fun pairingDone(name: String)
    fun pairingFailed(reason: String)
}

/**
 * SYNC.md v1 §Handshake client side, driven by [CompanionClient] callbacks.
 * Pure JVM (no android.*), so it unit-tests on the host.
 *
 * Flow: on connect send `hello`; on `hello_ack` either finish (already
 * pinned) or ask for the 6-digit code; on `pair_request` the companion shows
 * a desktop approval, then `pair_result ok` pins the fingerprint. Stray and
 * unknown messages are ignored. Each step has a 15 s timeout.
 */
class PairingClient(
    private val deviceName: String,
    private val ownFp: String,
    private val pins: CompanionPins,
    private val ui: PairingUi,
    private val sender: Sender,
    var onPaired: (name: String) -> Unit = {},
) {
    /**
     * Android-to-Android mutual-confirm SAS: set by the dialer UI flow. When
     * the host answers `hello_ack` with `pair_mode:"sas"`, [onSasPeer] fires
     * with the code both phones display; the UI calls [confirmSas] after the
     * user verifies the match, or [cancelPairing] to abort.
     */
    var onSasPeer: ((sas: String, name: String) -> Unit)? = null    /**
     * This phone's listener port, advertised in `hello` so an Android peer
     * can dial back. Zero while the listener is down.
     */
    var listenPort: Int = 0

    /** This connection's peer fingerprint, for the device registry. */
    val peerFingerprint: String? get() = synchronized(lock) { peerFp }
    /** Minimal send surface so tests need no socket. */
    fun interface Sender {
        fun send(msg: JSONObject)
    }

    companion object {
        const val STEP_TIMEOUT_MS = 15_000L
        private const val VERSION = 1
        private const val APP = "xnotes-android"
    }

    private enum class State { IDLE, HELLO_SENT, CODE_REQUIRED, SAS_REQUIRED, WAIT_RESULT, DONE, FAILED }

    private val lock = Any()
    private var state = State.IDLE
    private var peerFp: String? = null
    /** hello_ack that arrived before onConnected processed it. */
    private var pendingAck: JSONObject? = null
    /** Set after the first successful pairing: later hellos (reconnects) finish quietly. */
    private var everPaired = false
    private val timers = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pairing-timeout").apply { isDaemon = true }
    }
    private var timeout: ScheduledFuture<*>? = null

    /** CompanionClient.onConnected entry: send `hello`, arm the step timer. */
    fun onConnected(peerFp: String) {
        var ack: JSONObject? = null
        synchronized(lock) {
            if (state == State.DONE) {
                // Reconnect while paired: re-hello quietly; PairingUi stays done.
                sender.send(hello())
                armLocked()
                state = State.HELLO_SENT
                return
            }
            if (state != State.IDLE && state != State.FAILED) return
            state = State.HELLO_SENT
            this.peerFp = peerFp
            sender.send(hello())
            armLocked()
            ack = pendingAck
            pendingAck = null
        }
        ack?.let { onMessage(it) }
    }

    /** CompanionClient.onMessage entry; unknown `t` values are ignored. */
    fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello_ack" -> onHelloAck(msg)
            "pair_result" -> onPairResult(msg)
            else -> Unit // stray: presence/heartbeat/index handled by the host engine
        }
    }

    /** Code typed by the user after [PairingUi.showCodeEntry]. */
    fun submitCode(code: String) {
        synchronized(lock) {
            if (state != State.CODE_REQUIRED) return
            state = State.WAIT_RESULT
            sender.send(
                JSONObject()
                    .put("t", "pair_request")
                    .put("device_name", deviceName)
                    .put("host_cert_fp", ownFp)
                    .put("code", code.trim()),
            )
            ui.showStatus("Waiting for approval on the desktop…")
            armLocked()
        }
    }

    /**
     * The user verified the SAS matches on both phones: send `pair_confirm`.
     * The peer fingerprint comes from TLS ([peerFp]), never from the message,
     * so a relay cannot substitute its own cert.
     */
    fun confirmSas() {
        synchronized(lock) {
            if (state != State.SAS_REQUIRED) return
            val fp = peerFp ?: return
            state = State.WAIT_RESULT
            sender.send(
                JSONObject()
                    .put("t", "pair_confirm")
                    .put("device_name", deviceName)
                    .put("host_cert_fp", ownFp)
                    .put("sas", Pairing.sasFor(ownFp, fp)),
            )
            ui.showStatus("Waiting for the other phone to confirm…")
            armLocked()
        }
    }

    /** Abort an SAS pairing after the code was shown. */
    fun cancelPairing() {
        synchronized(lock) {
            if (state != State.SAS_REQUIRED) return
            failLocked("Pairing cancelled.")
        }
    }

    fun close() {
        synchronized(lock) {
            timeout?.cancel(false)
            timeout = null
        }
        timers.shutdownNow()
    }

    private fun hello(): JSONObject {
        val o = JSONObject()
            .put("t", "hello")
            .put("app", APP)
            .put("version", VERSION)
            .put("device_name", deviceName)
            .put("host_cert_fp", ownFp)
        if (listenPort > 0) o.put("listen_port", listenPort)
        return o
    }

    private fun onHelloAck(ack: JSONObject) {
        synchronized(lock) {
            if (state == State.IDLE) {
                // Ack beat onConnected (or a reconnect ack): stash it.
                pendingAck = ack
                return
            }
            if (state != State.HELLO_SENT) return
            if (isPinned(ack)) {
                cancelLocked()
                state = State.DONE
                val name = pinnedName(ack) ?: "companion"
                if (!everPaired) {
                    everPaired = true
                    ui.pairingDone(name)
                }
                onPaired(name)
                return
            }
            // Android-to-Android mutual confirm: the ack carries the SAS both
            // phones display; no code is typed.
            if (ack.optString("pair_mode") == HostHandshake.PAIR_MODE || ack.has("sas")) {
                val sas = ack.optString("sas")
                    .ifEmpty { Pairing.sasFor(ownFp, peerFp ?: "") }
                state = State.SAS_REQUIRED
                val name = ack.optString("companion_name").ifEmpty { "phone" }
                ui.showStatus("Confirm this code matches on both phones: $sas")
                onSasPeer?.invoke(sas, name)
                armLocked()
                return
            }
            if (ack.optBoolean("code_required", false) || !ack.optBoolean("paired", false)) {
                state = State.CODE_REQUIRED
                ui.showCodeEntry()
                ui.showStatus("Enter the 6-digit code shown on the desktop.")
                armLocked()
                return
            }
            // Paired flag set but the fingerprint is not our pin: hostile or
            // rotated companion. Fail rather than silently re-pairing.
            failLocked("Companion fingerprint does not match the pin.")
        }
    }

    private fun onPairResult(res: JSONObject) {
        synchronized(lock) {
            if (state != State.WAIT_RESULT) return
            if (res.optBoolean("ok", false)) {
                cancelLocked()
                state = State.DONE
                everPaired = true
                val fp = peerFp ?: res.optString("companion_cert_fp", "")
                val name = res.optString("companion_name", "").ifEmpty { "companion" }
                if (fp.isNotEmpty()) pins.pin(name, fp)
                ui.pairingDone(name)
                onPaired(name)
            } else {
                failLocked(res.optString("reason", "Pairing rejected."))
            }
        }
    }

    private fun isPinned(ack: JSONObject): Boolean {
        val fp = ack.optString("companion_cert_fp", "").ifEmpty { peerFp } ?: return false
        return pins.nameFor(fp.lowercase()) != null
    }

    private fun pinnedName(ack: JSONObject): String? {
        val fp = ack.optString("companion_cert_fp", "").ifEmpty { peerFp } ?: return null
        return pins.nameFor(fp.lowercase())
    }

    private fun armLocked() {
        timeout?.cancel(false)
        timeout = timers.schedule({
            synchronized(lock) {
                if (state == State.HELLO_SENT || state == State.CODE_REQUIRED || state == State.SAS_REQUIRED || state == State.WAIT_RESULT) {
                    failLocked("Timed out waiting for the companion.")
                }
            }
        }, STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelLocked() {
        timeout?.cancel(false)
        timeout = null
    }

    private fun failLocked(reason: String) {
        cancelLocked()
        state = State.FAILED
        ui.pairingFailed(reason)
    }
}
