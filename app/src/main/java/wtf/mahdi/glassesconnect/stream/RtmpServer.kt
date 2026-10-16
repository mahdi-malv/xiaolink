package wtf.mahdi.glassesconnect.stream

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal RTMP ingest for one publisher at a time (the glasses push here; see StartGlassLiveStreaming liveUrl).
 * Simple handshake (no digest), chunk stream demux, AMF0 connect/createStream/publish replies in the style of
 * nginx-rtmp, then FLV audio (type 8), video (type 9) and metadata (type 18) messages to [Listener].
 */
class RtmpServer(private val port: Int, private val listener: Listener) {
    interface Listener {
        fun onLog(line: String)
        fun onPublish(client: String, app: String, stream: String) {}
        fun onMetadata(values: Map<String, Any?>) {}
        fun onVideo(timestamp: Long, data: ByteArray)
        fun onAudio(timestamp: Long, data: ByteArray) {}
        fun onDisconnect() {}
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    val running get() = serverSocket != null

    /** Binds [port] and accepts on a daemon thread. Throws if the port is in use. */
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        serverSocket = ss
        thread(name = "rtmp-server", isDaemon = true) {
            listener.onLog("RTMP: listening on :$port")
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrElse { break }
                socket = s
                s.tcpNoDelay = true
                val peer = s.inetAddress.hostAddress.orEmpty()
                listener.onLog("RTMP: client $peer")
                runCatching { Session(s, peer).run() }.onFailure {
                    if (!ss.isClosed) listener.onLog("RTMP: client closed (${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""})")
                }
                runCatching { s.close() }
                socket = null
                listener.onDisconnect()
            }
            listener.onLog("RTMP: stopped")
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }; serverSocket = null
        runCatching { socket?.close() }
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private class Chunk {
        var timestamp = 0L; var delta = 0L; var length = 0; var type = 0; var streamId = 0
        var extended = false; var received = 0; var payload: ByteArray? = null
    }

    private inner class Session(s: Socket, private val peer: String) {
        private val counting = Counting(BufferedInputStream(s.getInputStream(), 64 * 1024))
        private val input = DataInputStream(counting)
        private val output = BufferedOutputStream(s.getOutputStream())
        private val chunks = HashMap<Int, Chunk>()
        private var inChunk = 128
        private var outChunk = 128
        private var ackWindow = 0L
        private var lastAck = 0L
        private var app = ""

        private fun u24() = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        private fun u32() = input.readInt().toLong() and 0xffffffffL
        private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun be32(b: ByteArray) = if (b.size < 4) 0L else
            ((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)

        fun run() {
            handshake()
            while (true) readChunk()
        }

        /** C0+C1 → S0+S1+S2 (S2 echoes C1) → C2. */
        private fun handshake() {
            val version = input.readUnsignedByte()
            if (version != 3) listener.onLog("RTMP: handshake version $version")
            val c1 = ByteArray(1536).also(input::readFully)
            val s1 = ByteArray(1536).also { java.util.Random().nextBytes(it); it.fill(0, 0, 8) }
            output.write(3); output.write(s1); output.write(c1); output.flush()
            input.readFully(ByteArray(1536))
        }

        private fun readChunk() {
            val b = input.readUnsignedByte()
            val fmt = b ushr 6
            val csid = when (val id = b and 0x3f) {
                0 -> 64 + input.readUnsignedByte()
                1 -> 64 + input.readUnsignedByte() + input.readUnsignedByte() * 256
                else -> id
            }
            val c = chunks.getOrPut(csid) { Chunk() }
            when (fmt) {
                0 -> {
                    val ts = u24(); c.length = u24(); c.type = input.readUnsignedByte(); c.streamId = Integer.reverseBytes(input.readInt())
                    c.extended = ts == 0xffffff; c.timestamp = if (c.extended) u32() else ts.toLong(); c.delta = 0; c.received = 0
                }
                1 -> {
                    val d = u24(); c.length = u24(); c.type = input.readUnsignedByte()
                    c.extended = d == 0xffffff; c.delta = if (c.extended) u32() else d.toLong(); c.timestamp += c.delta; c.received = 0
                }
                2 -> {
                    val d = u24(); c.extended = d == 0xffffff; c.delta = if (c.extended) u32() else d.toLong(); c.timestamp += c.delta; c.received = 0
                }
                else -> {
                    val ext = if (c.extended) u32() else null
                    if (c.received == 0) { ext?.let { c.delta = it }; c.timestamp += c.delta }
                }
            }
            require(c.length in 0..(16 shl 20)) { "message too large: ${c.length}" }
            if (c.received == 0) c.payload = ByteArray(c.length)
            val payload = c.payload!!
            val n = minOf(inChunk, c.length - c.received)
            input.readFully(payload, c.received, n)
            c.received += n
            if (c.received >= c.length) {
                c.received = 0; c.payload = null
                message(c.type, c.streamId, c.timestamp, payload)
            }
            if (ackWindow > 0 && counting.count - lastAck >= ackWindow) {
                lastAck = counting.count
                send(2, 3, 0, be32(counting.count and 0xffffffffL))
            }
        }

        private fun message(type: Int, streamId: Int, timestamp: Long, data: ByteArray) {
            when (type) {
                1 -> inChunk = (be32(data) and 0x7fffffff).toInt().coerceIn(1, 16 shl 20)
                5 -> ackWindow = be32(data)
                8 -> listener.onAudio(timestamp, data)
                9 -> listener.onVideo(timestamp, data)
                15 -> metadata(Amf0.decode(data, 1))
                17 -> command(Amf0.decode(data, 1))
                18 -> metadata(Amf0.decode(data))
                20 -> command(Amf0.decode(data))
                else -> Unit // 2 abort, 3 ack, 4 user control, 6 peer bandwidth
            }
        }

        private fun metadata(values: List<Any?>) {
            val v = if (values.firstOrNull() == "@setDataFrame") values.drop(1) else values
            if (v.firstOrNull() == "onMetaData") (v.getOrNull(1) as? Map<*, *>)?.let { m ->
                listener.onMetadata(m.entries.associate { it.key.toString() to it.value })
            }
        }

        private fun command(v: List<Any?>) {
            val name = v.getOrNull(0) as? String ?: return
            val txn = v.getOrNull(1) as? Double ?: 0.0
            when (name) {
                "connect" -> {
                    app = (v.getOrNull(2) as? Map<*, *>)?.get("app") as? String ?: ""
                    send(2, 5, 0, be32(2_500_000))
                    send(2, 6, 0, be32(2_500_000) + byteArrayOf(2))
                    send(2, 1, 0, be32(4096)); outChunk = 4096
                    send(3, 20, 0, Amf0.encode("_result", txn,
                        mapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31),
                        mapOf("level" to "status", "code" to "NetConnection.Connect.Success",
                            "description" to "Connection succeeded.", "objectEncoding" to 0)))
                }
                "createStream" -> send(3, 20, 0, Amf0.encode("_result", txn, null, 1))
                "publish" -> {
                    val stream = v.getOrNull(3) as? String ?: ""
                    send(2, 4, 0, byteArrayOf(0, 0, 0, 0, 0, 1)) // User Control: Stream Begin, stream 1
                    send(5, 20, 1, Amf0.encode("onStatus", 0, null,
                        mapOf("level" to "status", "code" to "NetStream.Publish.Start", "description" to "Start publishing")))
                    listener.onLog("RTMP: publish app=$app stream=$stream")
                    listener.onPublish(peer, app, stream)
                }
                "releaseStream", "FCPublish", "FCUnpublish", "deleteStream", "_checkbw" -> Unit
                else -> listener.onLog("RTMP: command $name")
            }
        }

        /** One message: fmt 0 header, then fmt 3 continuation chunks of [outChunk] bytes. */
        private fun send(csid: Int, type: Int, streamId: Int, payload: ByteArray) {
            output.write(csid); repeat(3) { output.write(0) }
            output.write(payload.size ushr 16); output.write(payload.size ushr 8); output.write(payload.size)
            output.write(type)
            output.write(streamId); output.write(streamId ushr 8); output.write(streamId ushr 16); output.write(streamId ushr 24)
            var p = 0
            while (p < payload.size) {
                if (p > 0) output.write(0xc0 or csid)
                val n = minOf(outChunk, payload.size - p)
                output.write(payload, p, n); p += n
            }
            output.flush()
        }
    }
}
