package com.xnotes.share.companion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Host side of Android-to-Android pairing (mutual-confirm SAS).
 *
 * The inbound peer sends `hello`; when its fingerprint is not pinned yet,
 * both sides display the same 6-digit SAS ([Pairing.sasFor] over the two
 * certificate fingerprints) and each user confirms the match:
 *
 * - dialer: `hello` → `hello_ack{paired:false, pair_mode:"sas", sas}` shows
 *   the SAS with Confirm/Cancel → Confirm sends `pair_confirm{sas}`.
 * - host (here): shows the SAS with Allow/Deny → Allow + a matching
 *   `pair_confirm` pins the peer and answers `pair_result{ok:true}`.
 *
 * A relay MITM cannot produce the same SAS on both ends, so confirming the
 * match authenticates both certificates at once. Re-hellos from an already
 * paired peer finish quietly with `paired:true`.
 *
 * Pure JVM (no android.*), so it unit-tests on the host.
 */
class HostHandshake(
    private val ownName: String,
    private val ownFp: String,
    private val pins: CompanionPins,
    private val events: Events,
    private val sender: Sender,
) {
    interface Events {
        /** A new unpaired peer: show [sas] with Allow/Deny for [deviceName]. */
        fun showSasApproval(peerFp: String, deviceName: String, sas: String)
        /** Pairing finished (or failed): update UI / connect the host engine. */
        fun pairingDone(peerFp: String, name: String)
        fun pairingFailed(peerFp: String, reason: String)
        fun showStatus(s: String)
    }

    fun interface Sender {
        fun send(msg: JSONObject)
    }

    companion object {
        const val PAIR_MODE = "sas"
        const val STEP_TIMEOUT_MS = 120_000L
    }

    private val lock = Any()
    private val approved = HashSet<String>()
    private val pendingConfirms = HashMap<String, JSONObject>()
    /** listen_port advertised in each peer's hello (0 when unknown). */
    private val helloPorts = HashMap<String, Int>()
    private val timers = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "host-handshake-timeout").apply { isDaemon = true }
    }
    private var timeout: ScheduledFuture<*>? = null

    /** Inbound message entry; unknown `t` values belong to the sync engine. */
    fun onMessage(peerFp: String, msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> onHello(peerFp, msg)
            "pair_confirm" -> onPairConfirm(peerFp, msg)
            else -> Unit
        }
    }

    /** The UI allowed (or denied) the peer showing [sas]. */
    fun setApproved(peerFp: String, allow: Boolean) {
        val key = peerFp.lowercase()
        var confirm: JSONObject? = null
        synchronized(lock) {
            if (allow) approved.add(key) else approved.remove(key)
            confirm = pendingConfirms.remove(key)
        }
        if (confirm != null) {
            if (allow) finishPairing(key, confirm!!) else fail(key, "Pairing rejected.")
        }
    }

    /** listen_port the peer advertised in its hello, or 0. */
    fun listenPortFor(peerFp: String): Int = synchronized(lock) { helloPorts[peerFp.lowercase()] ?: 0 }

    fun close() {
        synchronized(lock) { timeout?.cancel(false) }
        timers.shutdownNow()
    }

    private fun onHello(peerFp: String, msg: JSONObject) {
        val key = peerFp.lowercase()
        val deviceName = msg.optString("device_name").ifEmpty { "Android" }
        synchronized(lock) { helloPorts[key] = msg.optInt("listen_port", 0) }
        if (pins.nameFor(key) != null) {
            sender.send(
                JSONObject()
                    .put("t", "hello_ack")
                    .put("paired", true)
                    .put("pair_mode", PAIR_MODE)
                    .put("companion_name", ownName)
                    .put("companion_cert_fp", ownFp),
            )
            events.pairingDone(key, pins.nameFor(key) ?: deviceName)
            return
        }
        val sas = Pairing.sasFor(ownFp, key)
        sender.send(
            JSONObject()
                .put("t", "hello_ack")
                .put("paired", false)
                .put("pair_mode", PAIR_MODE)
                .put("sas", sas)
                .put("companion_name", ownName)
                .put("companion_cert_fp", ownFp),
        )
        events.showSasApproval(key, deviceName, sas)
        events.showStatus("Pair with $deviceName? Confirm the code matches on both phones.")
        arm()
    }

    private fun onPairConfirm(peerFp: String, msg: JSONObject) {
        val key = peerFp.lowercase()
        if (pins.nameFor(key) != null) {
            sender.send(okResult())
            events.pairingDone(key, pins.nameFor(key) ?: "Android")
            return
        }
        val got = msg.optString("sas")
        val want = Pairing.sasFor(ownFp, key)
        if (got != want) {
            fail(key, "Code mismatch — connection may be relayed. Rejected.")
            sender.send(JSONObject().put("t", "pair_result").put("ok", false).put("reason", "sas mismatch"))
            return
        }
        val allowed = synchronized(lock) {
            if (approved.contains(key)) true
            else {
                pendingConfirms[key] = msg
                false
            }
        }
        if (allowed) finishPairing(key, msg)
        else {
            events.showStatus("Confirm pairing on this phone to finish.")
            arm()
        }
    }

    private fun finishPairing(peerFp: String, confirm: JSONObject) {
        val name = confirm.optString("device_name").ifEmpty { "Android" }
        pins.pin(name, peerFp)
        cancel()
        sender.send(okResult())
        events.pairingDone(peerFp, name)
    }

    private fun okResult(): JSONObject = JSONObject()
        .put("t", "pair_result")
        .put("ok", true)
        .put("companion_name", ownName)
        .put("companion_cert_fp", ownFp)

    private fun fail(peerFp: String, reason: String) {
        cancel()
        sender.send(JSONObject().put("t", "pair_result").put("ok", false).put("reason", reason))
        events.pairingFailed(peerFp, reason)
    }

    private fun arm() {
        synchronized(lock) {
            timeout?.cancel(false)
            timeout = try {
                timers.schedule({
                synchronized(lock) {
                    pendingConfirms.clear()
                }
                events.showStatus("Pairing timed out.")
                }, STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun cancel() {
        synchronized(lock) {
            timeout?.cancel(false)
            timeout = null
            pendingConfirms.clear()
        }
    }
}
