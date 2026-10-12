package wtf.mahdi.glassesconnect.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Mass channel (Wear type 104 = SPP v2 L2 channel 2) media file receiver.
 * APK: MassCommandParser, MiWearMediaFileParser (proto/media/a.java), MiWearMediaFileParserKt.a (CRC32).
 *
 * Slice: [0]=? [1]=command (131 thumbnail image/video, 130 filling point) [2..3] total u16 LE [4..5] index u16 LE (1-based) [6..] data.
 * Joined: [idLength u8][id][encrypt/compress u8][reserved 4][content][CRC32 LE over all previous bytes].
 */
class MassFileReceiver {
    data class File(val id: String, val flag: Int, val content: ByteArray, val crcOk: Boolean)

    private val slices = mutableMapOf<Int, ByteArray>()
    private var total = 0

    /** Returns a completed file, or null when more slices are needed or the slice is not a thumbnail. */
    fun feed(data: ByteArray): File? {
        if (data.size <= 6 || (data[1].toInt() and 255) != THUMBNAIL) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getShort(2).toInt() and 0xffff
        val index = buffer.getShort(4).toInt() and 0xffff
        if (index == 1) { slices.clear(); total = count } else if (slices.isEmpty()) return null
        slices[index] = data.copyOfRange(6, data.size)
        if (total == 0 || slices.size != total) return null
        val joined = (1..total).fold(byteArrayOf()) { acc, i -> acc + (slices[i] ?: return reset()) }
        reset()
        return parse(joined)
    }

    val progress: Pair<Int, Int> get() = slices.size to total

    private fun reset(): File? { slices.clear(); total = 0; return null }

    companion object {
        const val THUMBNAIL = 131

        fun parse(joined: ByteArray): File? {
            if (joined.size <= 10) return null
            val idLength = joined[0].toInt() and 255
            val header = 1 + idLength + 1 + 4
            if (joined.size < header + 4) return null
            val body = joined.size - 4
            val crc = ByteBuffer.wrap(joined, body, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val calc = CRC32().apply { update(joined, 0, body) }.value
            return File(
                id = joined.copyOfRange(1, 1 + idLength).toString(Charsets.UTF_8),
                flag = joined[1 + idLength].toInt() and 255,
                content = joined.copyOfRange(header, body),
                crcOk = crc == calc,
            )
        }
    }
}
