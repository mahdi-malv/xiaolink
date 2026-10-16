package wtf.mahdi.glassesconnect.stream

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RtmpServerTest {
    @Test fun amf0RoundTrip() {
        val bytes = Amf0.encode("connect", 1, mapOf("app" to "live", "n" to 2.5, "ok" to true), null)
        assertEquals(listOf("connect", 1.0, mapOf("app" to "live", "n" to 2.5, "ok" to true), null), Amf0.decode(bytes))
    }

    @Test fun flvAvcConfigAndFrame() {
        val sps = byteArrayOf(0x67, 1, 2); val pps = byteArrayOf(0x68, 3)
        val record = byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, 3) + sps + byteArrayOf(1, 0, 2) + pps
        val cfg = Flv.parseVideo(byteArrayOf(0x17, 0, 0, 0, 0) + record) as Flv.Config
        assertEquals(Flv.Codec.AVC, cfg.codec); assertEquals(4, cfg.lengthSize)
        assertArrayEquals(sps, cfg.nalus[0]); assertArrayEquals(pps, cfg.nalus[1])
        val frame = Flv.parseVideo(byteArrayOf(0x17, 1, 0, 0, 0, 0, 0, 0, 2, 0x65, 9)) as Flv.Frame
        assertTrue(frame.key)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 9), Flv.annexB(frame.data, 4))
    }

    /** Real client: ffmpeg pushes a short H.264 (legacy FLV) or H.265 (enhanced RTMP) test stream. Skipped without ffmpeg. */
    private fun pushWithFfmpeg(port: Int, encoder: String): Pair<List<String>, List<Flv.Video>> {
        val ffmpeg = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").firstOrNull { java.io.File(it).canExecute() }
        assumeTrue("ffmpeg not installed", ffmpeg != null)
        val events = CopyOnWriteArrayList<String>()
        val videos = CopyOnWriteArrayList<Flv.Video>()
        val server = RtmpServer(port, object : RtmpServer.Listener {
            override fun onLog(line: String) { println(line) }
            override fun onPublish(client: String, app: String, stream: String) { events += "publish $app/$stream" }
            override fun onMetadata(values: Map<String, Any?>) { events += "meta ${values["width"]}x${values["height"]}" }
            override fun onVideo(timestamp: Long, data: ByteArray) { Flv.parseVideo(data)?.let(videos::add) }
            override fun onDisconnect() { events += "disconnect" }
        })
        server.start()
        try {
            val p = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15",
                "-t", "2", "-c:v", encoder, "-g", "15", "-pix_fmt", "yuv420p", "-f", "flv", "rtmp://127.0.0.1:$port/live/test")
                .redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            assertTrue("ffmpeg timeout", p.waitFor(30, TimeUnit.SECONDS))
            assertEquals("ffmpeg failed: $output", 0, p.exitValue())
            Thread.sleep(300)
        } finally { server.stop() }
        return events to videos
    }

    @Test fun ffmpegPublishesH264() {
        val (events, videos) = pushWithFfmpeg(19350, "libx264")
        assertTrue(events.toString(), "publish live/test" in events)
        assertTrue(events.toString(), "meta 320.0x240.0" in events)
        val cfg = videos.filterIsInstance<Flv.Config>().first()
        assertEquals(Flv.Codec.AVC, cfg.codec)
        assertEquals(listOf(7, 8), cfg.nalus.map { it[0].toInt() and 31 })
        val frames = videos.filterIsInstance<Flv.Frame>()
        assertEquals(30, frames.size)
        assertTrue(frames.first().key)
        val annexB = Flv.annexB(frames.first().data, cfg.lengthSize)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), annexB.copyOfRange(0, 4))
    }

    @Test fun ffmpegPublishesH265() {
        val (_, videos) = pushWithFfmpeg(19351, "libx265")
        val cfg = videos.filterIsInstance<Flv.Config>().first()
        assertEquals(Flv.Codec.HEVC, cfg.codec)
        // VPS 32, SPS 33, PPS 34 (x265 also adds a prefix SEI 39).
        assertEquals(listOf(32, 33, 34), cfg.nalus.map { (it[0].toInt() shr 1) and 63 }.take(3))
        assertEquals(30, videos.filterIsInstance<Flv.Frame>().size)
    }
}
