package wtf.mahdi.glassesconnect.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.net.Inet4Address
import java.net.NetworkInterface

/** Receiver state for the UI. [width]/[height] come from the decoder output format. */
data class PreviewState(
    val listening: Boolean = false,
    val client: String? = null,
    val codec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val kbps: Int = 0,
    val error: String? = null,
)

/**
 * Phone-side receiver: [RtmpServer] on [PORT] + MediaCodec decode to the preview [Surface].
 * The decoder is (re)created on the first key frame after a config or surface change. Audio is ignored.
 */
class LivePreview(private val log: (String) -> Unit, private val onState: (PreviewState) -> Unit) : RtmpServer.Listener {
    private val server = RtmpServer(PORT, this)
    private val lock = Any()
    private var state = PreviewState()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var config: Flv.Config? = null
    private var metaSize = 1920 to 1080
    private var unsupportedLogged = false
    private var frames = 0
    private var bytes = 0L
    private var statsStart = 0L

    private fun update(f: (PreviewState) -> PreviewState) { state = f(state); onState(state) }

    fun start() = synchronized(lock) {
        runCatching { server.start() }
            .onSuccess { update { PreviewState(listening = true) } }
            .onFailure { log("RTMP: cannot listen on :$PORT: ${it.message}"); update { s -> s.copy(error = "Port $PORT: ${it.message}") } }
        Unit
    }

    fun stop() = synchronized(lock) {
        server.stop(); release(); config = null
        update { PreviewState() }
    }

    /** Called from the UI thread when the SurfaceView surface appears or goes away. */
    fun setSurface(s: Surface?) = synchronized(lock) { surface = s; release() }

    private fun release() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        codec = null
    }

    override fun onLog(line: String) = log(line)

    override fun onPublish(client: String, app: String, stream: String) = synchronized(lock) {
        config = null; release(); unsupportedLogged = false
        frames = 0; bytes = 0; statsStart = System.currentTimeMillis()
        update { it.copy(client = client, codec = null, width = 0, height = 0, fps = 0, kbps = 0, error = null) }
    }

    override fun onMetadata(values: Map<String, Any?>) {
        log("RTMP: metadata " + values.entries.joinToString { "${it.key}=${it.value}" })
        val w = (values["width"] as? Double)?.toInt() ?: 0
        val h = (values["height"] as? Double)?.toInt() ?: 0
        if (w > 0 && h > 0) synchronized(lock) { metaSize = w to h }
    }

    override fun onDisconnect() = synchronized(lock) {
        release(); config = null
        update { it.copy(client = null, fps = 0, kbps = 0) }
    }

    override fun onVideo(timestamp: Long, data: ByteArray) = synchronized(lock) {
        stats(data.size)
        when (val v = Flv.parseVideo(data)) {
            is Flv.Config -> {
                val same = config?.let { old ->
                    old.codec == v.codec && old.lengthSize == v.lengthSize && old.nalus.size == v.nalus.size &&
                        old.nalus.zip(v.nalus).all { (a, b) -> a.contentEquals(b) }
                } == true
                if (!same) {
                    log("RTMP: video config ${v.codec} (${v.nalus.size} parameter sets, NALU length ${v.lengthSize})")
                    config = v; release()
                    update { it.copy(codec = v.codec.name) }
                }
            }
            is Flv.Frame -> decode(v, timestamp)
            is Flv.Unsupported -> if (!unsupportedLogged) {
                unsupportedLogged = true; log("RTMP: unsupported video ${v.what}"); update { it.copy(error = "Unsupported video ${v.what}") }
            }
            null -> Unit
        }
    }

    private fun stats(size: Int) {
        frames++; bytes += size
        val now = System.currentTimeMillis()
        if (now - statsStart >= 1000) {
            val sec = (now - statsStart) / 1000.0
            val fps = (frames / sec).toInt(); val kbps = (bytes * 8 / sec / 1000).toInt()
            frames = 0; bytes = 0; statsStart = now
            update { it.copy(fps = fps, kbps = kbps) }
        }
    }

    private fun decode(frame: Flv.Frame, timestamp: Long) {
        val cfg = config ?: return
        val s = surface ?: return
        val c = codec ?: run {
            if (!frame.key) return
            create(cfg, s) ?: return
        }.also { codec = it }
        try {
            val buf = Flv.annexB(frame.data, cfg.lengthSize)
            val index = c.dequeueInputBuffer(20_000)
            if (index >= 0) {
                c.getInputBuffer(index)!!.apply { clear(); put(buf) }
                c.queueInputBuffer(index, 0, buf.size, timestamp * 1000, if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            drain(c)
        } catch (e: Exception) {
            log("Preview: decoder error ${e.javaClass.simpleName}: ${e.message}")
            release()
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> c.releaseOutputBuffer(out, true)
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    fun int(key: String) = if (f.containsKey(key)) f.getInteger(key) else null
                    val w = int("crop-right")?.let { r -> r - (int("crop-left") ?: 0) + 1 } ?: f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = int("crop-bottom")?.let { b -> b - (int("crop-top") ?: 0) + 1 } ?: f.getInteger(MediaFormat.KEY_HEIGHT)
                    log("Preview: decoding ${w}x$h")
                    update { it.copy(width = w, height = h) }
                }
                else -> return
            }
        }
    }

    private fun create(cfg: Flv.Config, s: Surface): MediaCodec? {
        val (w, h) = metaSize
        fun format(lowLatency: Boolean) = MediaFormat.createVideoFormat(cfg.codec.mime, w, h).apply {
            if (cfg.codec == Flv.Codec.AVC) {
                // csd-0 = SPS (NAL type 7), csd-1 = PPS (type 8).
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(Flv.annexB(cfg.nalus.filter { it.isNotEmpty() && it[0].toInt() and 31 == 7 })))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(Flv.annexB(cfg.nalus.filter { it.isNotEmpty() && it[0].toInt() and 31 == 8 })))
            } else {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(Flv.annexB(cfg.nalus))) // VPS + SPS + PPS
            }
            if (lowLatency && Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        for (lowLatency in listOf(true, false)) {
            val c = runCatching { MediaCodec.createDecoderByType(cfg.codec.mime) }
                .getOrElse { log("Preview: no ${cfg.codec.mime} decoder: ${it.message}"); return null }
            try {
                c.configure(format(lowLatency), s, null, 0); c.start()
                log("Preview: decoder ${c.name} started (low latency ${lowLatency && Build.VERSION.SDK_INT >= 30})")
                return c
            } catch (e: Exception) {
                log("Preview: configure failed (low latency $lowLatency): ${e.message}")
                runCatching { c.release() }
            }
        }
        return null
    }

    companion object {
        const val PORT = 1935

        /** IPv4 addresses of this phone the glasses can reach: (interface, address), Wi-Fi first. */
        fun addresses(): List<Pair<String, String>> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif.name to it.hostAddress.orEmpty() } }
                .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }
        }.getOrDefault(emptyList())
    }
}
