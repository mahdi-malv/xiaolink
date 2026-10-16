package wtf.mahdi.glassesconnect.stream

/**
 * FLV video tag body (RTMP message type 9) → codec config or frame. Legacy tags: byte 0 = frameType << 4 | codecId
 * (7 AVC, 12 HEVC as used by Chinese CDNs), byte 1 packet type (0 config, 1 NALUs), bytes 2..4 composition time.
 * Enhanced RTMP (byte 0 bit 7): low nibble packetType (0 SequenceStart, 1 CodedFrames, 3 CodedFramesX), FourCC at 1..4.
 */
object Flv {
    enum class Codec(val mime: String) { AVC("video/avc"), HEVC("video/hevc") }

    sealed interface Video
    /** Parameter sets (AVC: SPS/PPS; HEVC: VPS/SPS/PPS) and the NALU length size of the frames. */
    data class Config(val codec: Codec, val nalus: List<ByteArray>, val lengthSize: Int) : Video
    /** [data] holds length-prefixed NALUs (AVCC/HVCC). */
    class Frame(val codec: Codec, val key: Boolean, val data: ByteArray) : Video
    data class Unsupported(val what: String) : Video

    fun parseVideo(tag: ByteArray): Video? {
        if (tag.size < 5) return null
        val b0 = tag[0].toInt() and 255
        val key = (b0 shr 4) and 7 == 1
        if (b0 and 0x80 != 0) {
            val codec = when (val fourCc = String(tag, 1, 4, Charsets.US_ASCII)) {
                "avc1" -> Codec.AVC
                "hvc1" -> Codec.HEVC
                else -> return Unsupported("FourCC $fourCc")
            }
            return when (b0 and 15) {
                0 -> config(codec, tag, 5)
                1 -> if (tag.size > 8) Frame(codec, key, tag.copyOfRange(8, tag.size)) else null
                3 -> Frame(codec, key, tag.copyOfRange(5, tag.size))
                else -> null
            }
        }
        val codec = when (b0 and 15) {
            7 -> Codec.AVC
            12 -> Codec.HEVC
            else -> return Unsupported("codec id ${b0 and 15}")
        }
        return when (tag[1].toInt()) {
            0 -> config(codec, tag, 5)
            1 -> Frame(codec, key, tag.copyOfRange(5, tag.size))
            else -> null
        }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)

    /** AVCDecoderConfigurationRecord (ISO 14496-15 5.2.4.1) or HEVCDecoderConfigurationRecord (8.3.3.1). */
    private fun config(codec: Codec, b: ByteArray, start: Int): Config? = runCatching {
        val nalus = mutableListOf<ByteArray>()
        var p = 0
        fun take() { val n = u16(b, p); nalus += b.copyOfRange(p + 2, p + 2 + n); p += 2 + n }
        val lengthSize: Int
        if (codec == Codec.AVC) {
            lengthSize = (b[start + 4].toInt() and 3) + 1
            p = start + 6
            repeat(b[start + 5].toInt() and 31) { take() }
            val pps = b[p++].toInt() and 255
            repeat(pps) { take() }
        } else {
            lengthSize = (b[start + 21].toInt() and 3) + 1
            val arrays = b[start + 22].toInt() and 255
            p = start + 23
            repeat(arrays) {
                val count = u16(b, p + 1); p += 3
                repeat(count) { take() }
            }
        }
        Config(codec, nalus, lengthSize)
    }.getOrNull()

    private val START = byteArrayOf(0, 0, 0, 1)

    fun annexB(nalus: List<ByteArray>): ByteArray = java.io.ByteArrayOutputStream().apply {
        nalus.forEach { write(START); write(it) }
    }.toByteArray()

    /** Length-prefixed NALUs → Annex B (start codes). Stops at a truncated NALU. */
    fun annexB(data: ByteArray, lengthSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var p = 0
        while (p + lengthSize <= data.size) {
            var n = 0
            repeat(lengthSize) { n = (n shl 8) or (data[p + it].toInt() and 255) }
            p += lengthSize
            if (n < 0 || n > data.size - p) break
            out.write(START); out.write(data, p, n)
            p += n
        }
        return out.toByteArray()
    }
}
