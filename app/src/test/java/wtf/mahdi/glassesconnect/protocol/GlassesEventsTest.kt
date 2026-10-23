package wtf.mahdi.glassesconnect.protocol

import org.junit.Assert.*
import org.junit.Test

class GlassesEventsTest {
    private fun file(id: String, type: Int) = Proto.encode {
        message(1) { string(1, id); uint(2, 0) }; uint(2, type); uint(3, 2048); uint(4, 1_760_000_000); uint(5, 7)
    }

    @Test fun photoReportFromButtonPress() {
        val packet = GlassesCommands.media(16, Proto.encode { message(14) { bytes(1, file("IMG_1.jpg", 0)) } })
        val event = GlassesEvents.parse(packet) as GlassesEvents.MediaReport
        assertEquals(GlassesEvents.MediaFile("IMG_1.jpg", 0, 2048, 1_760_000_000, 7), event.files.single())
        assertEquals("JPEG", event.files.single().kind)
    }

    @Test fun recordStatusAndInterconnectionEvents() {
        // RecordStatus {duration f1 LimitValue {current, max}, size f2, extraData f3 {mediaFile f1, status f2}}.
        val status = GlassesCommands.media(22, Proto.encode {
            message(19) { message(1) { uint(1, 42); uint(2, 7200) }; message(3) { bytes(1, file("REC_1", 5)); uint(2, 2) } }
        })
        val rs = GlassesEvents.parse(status) as GlassesEvents.RecordStatus
        assertEquals(2L, rs.status); assertEquals(42L, rs.duration); assertEquals(7200L, rs.maxDuration)
        assertEquals("Opus", rs.files.single().kind); assertFalse(rs.recording)
        // Firmware omits zero fields: extraData without status = 0 (recording).
        val recording = GlassesCommands.media(21, Proto.encode { message(19) { message(3) { bytes(1, file("REC_2", 5)) } } })
        assertTrue((GlassesEvents.parse(recording) as GlassesEvents.RecordStatus).recording)
        val response = GlassesCommands.media(20, Proto.encode { message(18) { uint(1, 0); message(2) { string(1, "REC_2") }; uint(3, 5) } })
        assertEquals(GlassesEvents.RecordResponse(0, "REC_2", 5), GlassesEvents.parse(response))
        val glass = GlassesCommands.packet(23, 60, 25, Proto.encode { message(48) { uint(1, 3) } })
        assertEquals(GlassesEvents.GlassEvent(3), GlassesEvents.parse(glass))
        val vlog = GlassesCommands.packet(23, 59, 25, Proto.encode { message(47) { uint(1, 1) } })
        assertEquals(GlassesEvents.Vlog(true), GlassesEvents.parse(vlog))
    }

    @Test fun alertStatusAndAivsError() {
        val alert = GlassesCommands.system(91, Proto.encode {
            message(58) { message(1) { uint(1, 0); uint(2, 64) }; message(6) { uint(1, 2) }; message(8) { uint(1, 0) } }
        })
        val a = GlassesEvents.parse(alert) as GlassesEvents.AlertStatus
        assertEquals(64, a.battery); assertEquals(2L, a.cameraStatus); assertEquals(0L, a.recorderStatus)
        val err = GlassesCommands.packet(14, 6, 16, Proto.encode { message(6) { uint(1, 7); string(2, "net") } })
        assertEquals(GlassesEvents.AivsError(7, "net"), GlassesEvents.parse(err))
    }

    @Test fun misPropertyFromDeviceLog() {
        // Captured from real glasses on photo button press (2026-10-12).
        val prop = Proto.encode { string(1, "969050158"); uint(2, 1); uint(3, 3); uint(4, 2); uint(8, 99) }
        val mis = Proto.encode { uint(1, 14); uint(2, 17); uint(3, 2); message(6) { uint(1, 7); message(8) { bytes(1, prop) } }; string(14, "969050158") }
        val packet = GlassesCommands.packet(23, 30, 25, Proto.encode { message(22) { bytes(2, mis) } })
        assertEquals(GlassesEvents.MisProperties("969050158", listOf(GlassesEvents.MisProperty(1, 3, 2, "99"))), GlassesEvents.parse(packet))
        val zeroCamera = GlassesCommands.system(91, Proto.encode { message(58) { bytes(6, byteArrayOf()) } })
        assertEquals(0L, (GlassesEvents.parse(zeroCamera) as GlassesEvents.AlertStatus).cameraStatus)
    }

    @Test fun misTakePhotoActionAndResponse() {
        val req = Proto.decode(GlassesCommands.misAction(2, 3, did = "969050158"))
        assertEquals(23L, Proto.scalar(req, 1)); assertEquals(33L, Proto.scalar(req, 2))
        val mip = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(req, 25)!!), 22)!!), 2)!!)
        assertEquals(17L, Proto.scalar(mip, 2)); assertEquals(2L, Proto.scalar(mip, 3))
        val spec = Proto.decode(Proto.bytes(mip, 6)!!)
        assertEquals(9L, Proto.scalar(spec, 1))
        val action = Proto.decode(Proto.bytes(spec, 10)!!)
        assertEquals("969050158", Proto.bytes(action, 1)!!.toString(Charsets.UTF_8))
        assertEquals(listOf(1L, 2L, 3L), listOf(2, 3, 4).map { Proto.scalar(action, it) })
        val rspMip = Proto.encode { uint(1, 5); uint(2, 17); uint(3, 2)
            message(6) { uint(1, 11); message(12) { string(1, "969050158"); uint(2, 1); uint(3, 2); uint(4, 3) } } }
        val rsp = GlassesCommands.interconnection(33, Proto.encode { message(22) { bytes(2, rspMip) } })
        assertEquals(GlassesEvents.MisActionResponse("969050158", 2, 3, 0), GlassesEvents.parse(rsp))
    }

    @Test fun cameraReplies() {
        val rsp = GlassesCommands.interconnection(41, Proto.encode { message(29) { uint(1, 1); uint(2, 0) } })
        assertEquals(GlassesEvents.CameraResponse(1, 0), GlassesEvents.parse(rsp))
        val thumb = GlassesCommands.interconnection(48, Proto.encode {
            message(36) { string(1, "k"); uint(2, 1); message(4) { uint(1, 0); uint(2, 2); bytes(3, byteArrayOf(-1, -40)) } }
        })
        val t = GlassesEvents.parse(thumb) as GlassesEvents.CameraThumbnail
        assertEquals("k", t.key); assertEquals(2, t.total); assertArrayEquals(byteArrayOf(-1, -40), t.data)
    }

    @Test fun unrelatedOrMalformedPacketsAreIgnored() {
        assertNull(GlassesEvents.parse(GlassesCommands.deviceStatus()))
        assertNull(GlassesEvents.parse(byteArrayOf(8, -128)))
    }

    @Test fun aivsWakeUpAndStopCapture() {
        val wake = GlassesCommands.aivs(10, Proto.encode {
            message(11) { message(1) { string(1, "r1"); string(2, "t1"); string(3, "{}") }; uint(2, 2) }
        })
        assertEquals(GlassesEvents.AivsWakeUp(2, "r1", "t1", "{}"), GlassesEvents.parse(wake))
        // Observed on device 2026-10-12 when video recording starts: {1: {1: {1: 3, 13: 0}}}.
        val stop = GlassesCommands.aivs(0, Proto.encode { message(1) { message(1) { uint(1, 3); uint(13, 0) } } })
        assertEquals(GlassesEvents.AivsInstructions(listOf(3)), GlassesEvents.parse(stop))
        assertEquals(GlassesEvents.AivsInstructions(listOf(3)), GlassesEvents.parse(GlassesCommands.aivsStopCapture()))
        assertEquals(5, (GlassesEvents.parse(GlassesCommands.aivsWake()) as GlassesEvents.AivsWakeUp).cmd)
    }

    @Test fun liveStreamCommandsAndReports() {
        // Interconnection=f25; Request f34 {cmd f1, Param f2 {resolution, fps, bps(stream mode), platform, liveMode, url}}.
        val start = Proto.decode(Proto.bytes(Proto.decode(GlassesCommands.liveStart("rtmp://h/a", 3, 25, 2)), 25)!!)
        val req = Proto.decode(Proto.bytes(start, 34)!!)
        assertEquals(1L, Proto.scalar(req, 1))
        val param = Proto.decode(Proto.bytes(req, 2)!!)
        assertEquals(listOf(3L, 25L, 2L, 0L, 1L), (1..5).map { Proto.scalar(param, it) })
        assertEquals("rtmp://h/a", Proto.bytes(param, 6)!!.toString(Charsets.UTF_8))
        assertEquals("08 17 10 2e ca 01 05 92 02 02 08 02", Proto.hex(GlassesCommands.liveStop()))
        // System=f4; WiFiConfig f32 {basicInfo f1 {ssid, authMode 2, frequency 2}, psk f2 {password}}; open network uses stub f4.
        assertEquals("08 02 10 38 22 11 82 02 0e 0a 07 0a 01 6e 10 02 18 02 12 03 0a 01 70", Proto.hex(GlassesCommands.addWifi("n", "p")))
        assertTrue(Proto.hex(GlassesCommands.addWifi("n", "")).endsWith("10 01 18 02 22 00"))
        val status = GlassesCommands.interconnection(47, Proto.encode { message(35) { uint(1, 5) } })
        assertEquals(GlassesEvents.LiveStatus(5, null), GlassesEvents.parse(status))
        val modes = GlassesCommands.interconnection(45, Proto.encode { message(33) { uint(1, 5); uint(2, 1) } })
        assertEquals(GlassesEvents.LiveModes(5, 1), GlassesEvents.parse(modes))
        val wifi = GlassesCommands.system(98, Proto.encode { message(64) { string(1, "n"); uint(2, 0); string(3, "10.0.0.5") } })
        assertEquals(GlassesEvents.WifiResult("n", 0, "10.0.0.5"), GlassesEvents.parse(wifi))
    }
}
