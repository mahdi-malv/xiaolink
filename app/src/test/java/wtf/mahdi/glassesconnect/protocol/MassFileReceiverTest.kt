package wtf.mahdi.glassesconnect.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class MassFileReceiverTest {
    private fun joined(id: String, content: ByteArray): ByteArray {
        val body = byteArrayOf(id.length.toByte()) + id.toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + content
        val crc = CRC32().apply { update(body) }.value
        return body + ByteArray(4) { (crc ushr (it * 8)).toByte() }
    }

    private fun slice(total: Int, index: Int, data: ByteArray) =
        byteArrayOf(0, 131.toByte(), total.toByte(), 0, index.toByte(), 0) + data

    @Test fun joinsSlicesAndChecksCrc() {
        val content = ByteArray(300) { it.toByte() }
        val all = joined("IMG_1.jpg", content)
        val r = MassFileReceiver()
        assertNull(r.feed(slice(3, 1, all.copyOfRange(0, 100))))
        assertNull(r.feed(slice(3, 2, all.copyOfRange(100, 200))))
        val file = r.feed(slice(3, 3, all.copyOfRange(200, all.size)))!!
        assertEquals("IMG_1.jpg", file.id); assertTrue(file.crcOk); assertArrayEquals(content, file.content)
    }

    @Test fun ignoresOrphanSlicesAndOtherCommands() {
        val r = MassFileReceiver()
        assertNull(r.feed(slice(2, 2, ByteArray(20))))
        assertNull(r.feed(byteArrayOf(0, 130.toByte(), 1, 0, 1, 0, 9, 9, 9)))
        val bad = joined("x", ByteArray(20)).also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFalse(r.feed(slice(1, 1, bad))!!.crcOk)
    }

    @Test fun thumbnailRequestPayload() {
        assertEquals("08 12 10 12 a2 01 0b 82 01 08 0a 06 0a 02 69 64 10 01", Proto.hex(GlassesCommands.requestThumbnails(listOf("id"))))
    }
}
