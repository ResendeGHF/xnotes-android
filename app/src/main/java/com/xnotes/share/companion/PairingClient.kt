package com.xnotes.share.companion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** UI surface the pairing handshake drives. Implemented by ConnectPane. */
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
    /** Minimal send surface so tests need no socket. */
    fun interface Sender {
        fun send(msg: JSONObject)
    }

    companion object {
        const val STEP_TIMEOUT_MS = 15_000L
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

    fun close() {
        synchronized(lock) {
            timeout?.cancel(false)
            timeout = null
        }
        timers.shutdownNow()
    }

    private fun hello(): JSONObject = JSONObject()
        .put("t", "hello")
        .put("app", APP)
        .put("version", VERSION)
        .put("device_name", deviceName)
        .put("host_cert_fp", ownFp)

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
                if (state == State.HELLO_SENT || state == State.CODE_REQUIRED || state == State.WAIT_RESULT) {
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
