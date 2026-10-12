package wtf.mahdi.glassesconnect.protocol

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One authenticated session over a byte link (SPP socket).
 * Flow: SPP version probe -> L1 negotiation (V2) -> WearAuth (type 100) -> encrypted commands (type 101).
 * All protocol state lives on one worker thread.
 */
class WearSession(
    token: ByteArray,
    phoneModel: String,
    sdkInt: Int,
    private val write: (ByteArray) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String)
        fun onLog(line: String)
        /** Any decrypted WearPacket from the glasses (responses and unsolicited reports). */
        fun onPacket(packet: ByteArray, response: Boolean)
        /** Mass channel data (Wear type 104, L2 channel 2): media thumbnails. */
        fun onMass(data: ByteArray) {}
        /** Voice channel data (Wear type 103, L2 channel 3): AIVS mic audio and AI images. */
        fun onVoice(data: ByteArray) {}
    }
    enum class State { Negotiating, Authenticating, Ready, Failed, Closed }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "wear-session") }
    private val auth = WearAuth(token, phoneModel, sdkInt)
    private var keys: WearCrypto.Keys? = null
    private var authStep = 0
    private var state = State.Negotiating
    private var retry: ScheduledFuture<*>? = null
    private var timeout: ScheduledFuture<*>? = null
    private val transport = WearTransport(
        send = { frame -> write(frame) },
        onMessage = ::onMessage,
        onReady = ::onTransportReady,
    )

    fun start() = worker.execute {
        setState(State.Negotiating, "Waiting for SPP version")
        transport.start()
        retry = worker.scheduleWithFixedDelay({ runCatching { transport.retryPending() } }, 3, 3, TimeUnit.SECONDS)
        timeout = worker.schedule({ if (state != State.Ready) fail("Handshake timed out") }, 30, TimeUnit.SECONDS)
    }

    fun receive(bytes: ByteArray) = worker.execute {
        try { transport.receive(bytes) } catch (e: Exception) { fail("Transport: ${e.message}") }
    }

    /** Sends an encrypted command WearPacket (type 101). */
    fun send(packet: ByteArray) = worker.execute {
        val k = keys
        if (state != State.Ready || k == null) { listener.onLog("Not ready; command dropped"); return@execute }
        try {
            listener.onLog("→ ${describe(packet)}")
            transport.sendMessage(WearCrypto.ctr(k.appKey, packet), encrypted = true)
        } catch (e: Exception) { listener.onLog("Send failed: ${e.message}") }
    }

    fun close() {
        worker.execute { keys?.clear(); keys = null; setState(State.Closed, "Closed") }
        worker.shutdown()
    }

    private fun onTransportReady(version: String) {
        listener.onLog("SPP protocol version $version")
        if (!transport.isV2) return fail("SPP v1 framing ($version) is not implemented yet (TODO)")
        setState(State.Authenticating, "Protocol $version, sending AppVerify")
        authStep = 1
        transport.sendMessage(auth.appVerify(), encrypted = false)
    }

    private fun onMessage(channel: Int, encrypted: Boolean, payload: ByteArray) {
        if (channel == 2) {
            val k = keys ?: return listener.onLog("Mass data before auth")
            return listener.onMass(if (encrypted) WearCrypto.ctr(k.deviceKey, payload) else payload)
        }
        if (channel == 3) {
            val k = keys ?: return listener.onLog("Voice data before auth")
            return listener.onVoice(if (encrypted) WearCrypto.ctr(k.deviceKey, payload) else payload)
        }
        if (channel != 1) { listener.onLog("Ignored channel $channel (${payload.size} B)"); return }
        try {
            if (!encrypted) return onAuthMessage(payload)
            val k = keys ?: return listener.onLog("Encrypted data before auth")
            val plain = WearCrypto.ctr(k.deviceKey, payload)
            listener.onLog("← ${describe(plain)}")
            listener.onPacket(plain, response = true)
        } catch (e: Exception) { fail(e.message ?: e.toString()) }
    }

    private fun onAuthMessage(payload: ByteArray) {
        when (authStep) {
            1 -> {
                val confirm = auth.onDeviceVerify(payload)
                authStep = 2
                listener.onLog("Device signature OK, sending AppConfirm")
                transport.sendMessage(confirm, encrypted = false)
            }
            2 -> {
                val caps = auth.onDeviceConfirm(payload)
                keys = auth.keys
                authStep = 3
                retry?.cancel(false); timeout?.cancel(false)
                retry = worker.scheduleWithFixedDelay({ runCatching { transport.retryPending() } }, 10, 10, TimeUnit.SECONDS)
                setState(State.Ready, "Authenticated ($caps)")
            }
            else -> listener.onLog("Plain packet: ${describe(payload)}")
        }
    }

    private fun setState(s: State, detail: String) {
        state = s
        listener.onState(s, detail)
        listener.onLog("[$s] $detail")
    }

    private fun fail(reason: String) {
        if (state == State.Failed || state == State.Closed) return
        retry?.cancel(false); timeout?.cancel(false)
        setState(State.Failed, reason)
    }

    companion object {
        fun describe(packet: ByteArray): String = runCatching {
            val f = Proto.decode(packet)
            val err = Proto.scalar(f, 100)?.let { " err=$it" } ?: ""
            "type=${Proto.scalar(f, 1)} id=${Proto.scalar(f, 2)}$err (${packet.size} B)"
        }.getOrElse { "${packet.size} B: ${Proto.hex(packet.take(32).toByteArray())}" }
    }
}
