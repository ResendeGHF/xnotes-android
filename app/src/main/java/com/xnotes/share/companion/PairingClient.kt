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
    /** This connection's peer fingerprint, for the device registry. */
    val peerFingerprint: String? get() = synchronized(lock) { peerFp }

    /** True while a handshake step is in flight (hello sent, code shown, waiting). */
    val isInFlight: Boolean
        get() = synchronized(lock) {
            state == State.HELLO_SENT || state == State.CODE_REQUIRED ||
                state == State.WAIT_RESULT
        }
    /** Minimal send surface so tests need no socket. */
    fun interface Sender {
        fun send(msg: JSONObject)
    }

    companion object {
        const val STEP_TIMEOUT_MS = 15_000L
        /** Longer window while the user reads the desktop and types 6 digits. */
        const val STEP_CODE_TIMEOUT_MS = 120_000L
        private const val VERSION = 1
        private const val APP = "xnotes-android"
    }

    private enum class State { IDLE, HELLO_SENT, CODE_REQUIRED, WAIT_RESULT, DONE, FAILED }

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
            if (state == State.CODE_REQUIRED) {
                // Redialed while the user is typing: re-hello so the host
                // re-acks and the entry stays live instead of timing out.
                state = State.HELLO_SENT
                this.peerFp = peerFp
                sender.send(hello())
                armLocked()
                ack = pendingAck
                pendingAck = null
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
            armCodeLocked()
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
            if (ack.optBoolean("code_required", false) || !ack.optBoolean("paired", false)) {
                state = State.CODE_REQUIRED
                ui.showCodeEntry()
                ui.showStatus("Enter the 6-digit code shown on the desktop.")
                armCodeLocked()
                return
            }
            // Paired flag set but the fingerprint is not our pin: the other
            // side still has us paired (or its cert rotated). Refuse rather
            // than silently re-pairing: forget it there too, then pair again.
            failLocked("Companion fingerprint does not match the pin. If it was forgotten, forget this phone on the desktop too and pair again.")
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
        armLockedMs(STEP_TIMEOUT_MS)
    }

    private fun armCodeLocked() {
        armLockedMs(STEP_CODE_TIMEOUT_MS)
    }

    private fun armLockedMs(ms: Long) {
        timeout?.cancel(false)
        timeout = try {
            timers.schedule({
            synchronized(lock) {
                if (state == State.HELLO_SENT || state == State.CODE_REQUIRED || state == State.WAIT_RESULT) {
                    failLocked("Timed out waiting for the companion.")
                }
            }
            }, ms, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
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
