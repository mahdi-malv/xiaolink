package wtf.mahdi.glassesconnect.protocol

import org.junit.Assert.*
import org.junit.Test

class GlassesCommandsTest {
    @Test fun observedNanoSerializationGoldenVectors() {
        // WearProtos: type=f1, id=f2, System=f4, Media=f20. Zero-valued record fields are written.
        assertEquals("08 02 10 01 22 00", Proto.hex(GlassesCommands.deviceStatus()))
        assertEquals("08 02 10 0e 22 06 92 02 03 08 f0 03", Proto.hex(GlassesCommands.settings()))
        assertEquals("08 12 10 14 a2 01 07 8a 01 04 08 00 10 00", Proto.hex(GlassesCommands.record(true)))
        assertEquals("08 12 10 14 a2 01 07 8a 01 04 08 00 10 01", Proto.hex(GlassesCommands.record(false)))
        // Aivs=f16; AivsStatus f5 {voice, tts, media, image, operating, device}; InstructionList f1 {f1 {resultType f1, sessionId f13}}.
        assertEquals("08 0e 10 05 82 01 0e 2a 0c 08 00 10 00 18 00 20 00 28 00 30 00", Proto.hex(GlassesCommands.aivsStatus(0)))
        assertEquals("08 0e 10 00 82 01 08 0a 06 0a 04 08 03 68 00", Proto.hex(GlassesCommands.aivsStopCapture()))
    }
    @Test fun batteryResponseAndUnsolicitedReport() {
        val battery = Proto.encode { uint(1, 73); uint(2, 0) }
        val status = GlassesCommands.system(1, Proto.encode { message(2) { bytes(1, battery) } })
        val report = GlassesCommands.system(97, Proto.encode { bytes(63, battery) })
        assertEquals(73, GlassesCommands.batteryCapacity(status))
        assertEquals(73, GlassesCommands.batteryCapacity(report))
        assertNull(GlassesCommands.batteryCapacity(GlassesCommands.deviceStatus()))
        assertNull(GlassesCommands.batteryCapacity(GlassesCommands.media(1)))
    }
    @Test fun parserRejectsTruncatedAndOverflowInput() {
        listOf(byteArrayOf(0), byteArrayOf(8, -128), byteArrayOf(18, 3, 1),
            byteArrayOf(8, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2)).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { Proto.decode(bytes) }
        }
    }
    @Test fun decoderPreservesUnknownFieldsAndLargeVarints() {
        val encoded = Proto.encode { uint(99, Long.MAX_VALUE); bytes(33, byteArrayOf(4, 5)); uint(99, 2) }
        val fields = Proto.decode(encoded)
        assertEquals(Long.MAX_VALUE, fields.first().value)
        assertEquals(2L, Proto.scalar(fields, 99))
        assertArrayEquals(byteArrayOf(4, 5), Proto.bytes(fields, 33))
    }
}
