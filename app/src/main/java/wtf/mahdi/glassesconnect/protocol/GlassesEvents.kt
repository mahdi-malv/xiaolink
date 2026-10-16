package wtf.mahdi.glassesconnect.protocol

/**
 * Unsolicited reports from the glasses (button press = photo, hold = video, etc).
 * Dispatch per O95DeviceDecorator.c1: System (type 2) ids 91/98/99, AIVS (type 14) id 6,
 * Media (type 18) ids 16/22/23, Interconnection (type 23) ids 30/33/41/42/45/47/48/59/60.
 * Field numbers from MediaProtos / InterconnectionProtos. See analysis/commands.md.
 */
object GlassesEvents {
    /** MediaFile: identifier=1 {id=1, thumbnail=2}, type=2, size=3, timestamp=4, duration=5. */
    data class MediaFile(val id: String, val type: Int, val size: Long, val timestamp: Long, val duration: Long?) {
        val kind: String get() = MEDIA_TYPES[type] ?: "type $type"
    }

    sealed interface Event { val summary: String }
    data class MediaReport(val files: List<MediaFile>) : Event {
        override val summary get() = "New media: " + files.joinToString { "${it.kind} ${it.id}" }
    }
    data class RecordStatus(val status: Long?, val files: List<MediaFile>) : Event {
        override val summary get() = "Record status=$status" + files.joinToString("") { ", ${it.kind} ${it.id}" }
    }
    data class MediaSyncRequest(val channel: Long?) : Event {
        override val summary get() = "Glasses request media sync (channel=$channel)"
    }
    /**
     * SystemProtos.AlertStatus (System f58). Camera status from device log 2026-10-12:
     * 0 idle, 1 capturing/saving, 5 video recording. Official app also treats 2|3 as busy (O95StateLiveData.n).
     */
    data class AlertStatus(
        val battery: Int?, val cameraStatus: Long?, val recorderStatus: Long?,
        val storageFree: Long?, val wearMode: Long?, val miscEvent: Long?,
    ) : Event {
        override val summary get() = listOfNotNull(
            battery?.let { "battery=$it%" }, cameraStatus?.let { "camera=$it (${cameraLabel(it)})" },
            recorderStatus?.let { "recorder=$it" }, storageFree?.let { "free=$it" },
            wearMode?.let { "wear=$it" }, miscEvent?.let { "misc=$it" },
        ).joinToString().ifEmpty { "(empty)" }.let { "Alert: $it" }
    }
    data class AivsError(val code: Int?, val message: String?) : Event {
        override val summary get() = "AI error code=$code" + (AIVS_ERRORS[code]?.let { " ($it)" } ?: "") +
            (message?.let { " \"$it\"" } ?: "")
    }
    /** AIVS WakeUpEvent (Aivs f11): cmd 1 voice, 2 touch (manual), 3 suspect wake word, 4 inhibition. */
    data class AivsWakeUp(val cmd: Int, val requestId: String?, val transactionId: String?, val payload: String?) : Event {
        override val summary get() = "AI wake-up cmd=$cmd (${WAKE_CMDS[cmd] ?: "unknown"}) req=$requestId" +
            (payload?.takeIf { it.isNotEmpty() }?.let { " payload=$it" } ?: "")
    }
    /** AIVS InstructionList (Aivs f1): resultType 3 = STOP_CAPTURE. */
    data class AivsInstructions(val resultTypes: List<Int>) : Event {
        override val summary get() = "AI instructions " + resultTypes.joinToString { if (it == 3) "STOP_CAPTURE" else "type $it" }
    }
    /** MIoT spec property (MipSpecProto.PropertyChangedInd): miid.siid.piid = value. Meaning per id is unknown. */
    data class MisProperty(val miid: Long, val siid: Long, val piid: Long, val value: String)
    data class MisProperties(val did: String?, val properties: List<MisProperty>) : Event {
        /** 1.3.2 tracks battery percent (device log: 99 -> 98 between reports). */
        val battery: Int? get() = properties.lastOrNull { it.miid == 1L && it.siid == 3L && it.piid == 2L }
            ?.value?.toIntOrNull()?.takeIf { it in 0..100 }
        override val summary get() = "MIS " + properties.joinToString { "${it.miid}.${it.siid}.${it.piid}=${it.value}" }
    }
    /** MipSpecProto.ActionRsp {did f1, miid f2, siid f3, aiid f4, code f5, out f6}. */
    data class MisActionResponse(val did: String?, val siid: Long, val aiid: Long, val code: Long) : Event {
        override val summary get() = "MIS action ${siid}.${aiid} response code=$code"
    }
    data class MisOther(val did: String?, val type: Long?) : Event { override val summary get() = "MIS packet type=$type" }
    data class CameraResponse(val requestId: Long?, val code: Long?) : Event {
        override val summary get() = "Camera response req=$requestId code=$code"
    }
    data class CameraEvent(val status: Long?, val countDown: Long?) : Event {
        override val summary get() = "Camera event status=$status" + (countDown?.let { " countdown=$it" } ?: "")
    }
    /** Camera.Thumbnail {key f1, status f2, type f3, packet f4 {index f1, totalNumber f2, data f3}, totalSize f5}. */
    data class CameraThumbnail(val key: String, val status: Long?, val type: Long?, val index: Int, val total: Int, val data: ByteArray) : Event {
        override val summary get() = "Camera thumbnail $key ${index}/$total (${data.size} B) status=$status type=$type"
    }
    /** WiFiP2P.Result (System f66): code 0 ok, 1 connect fail, 2 low battery, 3 high temperature, 4 recording. */
    data class P2pResult(val code: Long, val ip: String, val taskId: Long) : Event {
        override val summary get() = "Wi-Fi P2P result code=$code (${P2P_CODES[code] ?: "unknown"}) ip=$ip taskId=$taskId"
    }
    /** WiFiConfig.Result (System id 98, f64): code 0 connected (WifiDeviceFragment.java:1148), 2 open failed, 3 low battery, 4 hot. */
    data class WifiResult(val ssid: String, val code: Long, val ip: String) : Event {
        override val summary get() = "Wi-Fi result ssid=$ssid code=$code (${WIFI_CODES[code] ?: "unknown"}) ip=$ip"
    }
    /** LiveStreaming (Interconnection id 45 reply, f33): bitmasks of liveMode (1 Wi-Fi, 2 wired, 4 Bluetooth). */
    data class LiveModes(val supported: Long, val current: Long) : Event {
        override val summary get() = "Live modes supported=$supported current=$current"
    }
    /** LiveStreaming.Status (Interconnection id 47, f35); codes per MiWearLiveStreamingHandler.e. */
    data class LiveStatus(val code: Long, val rtspUrl: String?) : Event {
        override val summary get() = "Live status code=$code (${LIVE_CODES[code] ?: "unknown"})" + (rtspUrl?.let { " rtsp=$it" } ?: "")
    }
    data class GlassEvent(val code: Long?) : Event { override val summary get() = "Glass event code=$code" }
    data class Vlog(val enable: Boolean) : Event { override val summary get() = "Vlog ${if (enable) "on" else "off"}" }

    fun cameraLabel(status: Long): String = when (status) {
        0L -> "idle"; 1L -> "capturing"; 5L -> "recording video"; 2L, 3L -> "busy"; else -> "unknown"
    }

    val WAKE_CMDS = mapOf(1 to "voice", 2 to "touch", 3 to "suspect", 4 to "inhibition", 5 to "phone")

    /** O95DeviceDecorator.X0 error-code to tip string mapping. */
    val AIVS_ERRORS = mapOf(
        -1 to "unknown", 2 to "poor connection", 3 to "media volume zero", 4 to "glasses taken off",
        5 to "BT profiles disconnected", 6 to "voice interrupted", 7 to "phone network",
    )

    val P2P_CODES = mapOf(0L to "ok", 1L to "connect failed", 2L to "low battery", 3L to "high temperature", 4L to "recording")

    val WIFI_CODES = mapOf(0L to "connected", 2L to "open failed", 3L to "low battery", 4L to "high temperature")

    val LIVE_CODES = mapOf(
        1L to "live", 2L to "stopped", 3L to "device occupied", 4L to "Wi-Fi failed", 5L to "RTMP failed",
        6L to "high temperature", 7L to "low battery", 8L to "not worn", 9L to "camera blocked", 10L to "busy",
        11L to "recording", 12L to "in a call", 13L to "busy", 14L to "updating", 15L to "reconnecting",
    )

    val MEDIA_TYPES = mapOf(
        0 to "JPEG", 1 to "PNG", 2 to "MP4", 3 to "QuickTime", 4 to "PCM", 5 to "Opus", 6 to "SBC", 7 to "mSBC",
        8 to "YUV meta", 9 to "HEIC meta", 10 to "MP4 meta", 11 to "Opus image",
    )

    fun parse(packet: ByteArray): Event? = runCatching {
        val envelope = Proto.decode(packet)
        val id = Proto.scalar(envelope, 2)?.toInt()
        when (Proto.scalar(envelope, 1)?.toInt()) {
            2 -> {
                if (id == 99) {
                    val r = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(envelope, 4) ?: return null), 66) ?: return null)
                    return P2pResult(Proto.scalar(r, 1) ?: 0, Proto.bytes(r, 2)?.toString(Charsets.UTF_8).orEmpty(), Proto.scalar(r, 3) ?: 0)
                }
                if (id == 98) {
                    val r = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(envelope, 4) ?: return null), 64) ?: return null)
                    return WifiResult(Proto.bytes(r, 1)?.toString(Charsets.UTF_8).orEmpty(), Proto.scalar(r, 2) ?: 0,
                        Proto.bytes(r, 3)?.toString(Charsets.UTF_8).orEmpty())
                }
                if (id != 91) return null
                val alert = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(envelope, 4) ?: return null), 58) ?: return null)
                // Firmware omits zero values: a present sub-message without the field means 0.
                fun sub(field: Int, inner: Int) = Proto.bytes(alert, field)?.let { Proto.scalar(Proto.decode(it), inner) ?: 0L }
                AlertStatus(
                    battery = sub(1, 2)?.takeIf { it in 0..100 }?.toInt(), cameraStatus = sub(6, 1),
                    recorderStatus = sub(8, 1), storageFree = sub(3, 1), wearMode = sub(7, 1), miscEvent = sub(9, 1),
                )
            }
            14 -> {
                val aivs = Proto.decode(Proto.bytes(envelope, 16) ?: return null)
                if (id == 10) {
                    val w = Proto.decode(Proto.bytes(aivs, 11) ?: return null)
                    val info = Proto.bytes(w, 1)?.let { Proto.decode(it) }.orEmpty()
                    return AivsWakeUp(Proto.scalar(w, 2)?.toInt() ?: 0, Proto.bytes(info, 1)?.toString(Charsets.UTF_8),
                        Proto.bytes(info, 2)?.toString(Charsets.UTF_8), Proto.bytes(info, 3)?.toString(Charsets.UTF_8))
                }
                if (id == 0) {
                    val list = Proto.decode(Proto.bytes(aivs, 1) ?: return null)
                    return AivsInstructions(list.filter { it.number == 1 && it.wireType == 2 }
                        .map { (Proto.scalar(Proto.decode(it.data), 1) ?: 0).toInt() })
                }
                if (id != 6) return null
                val err = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(envelope, 16) ?: return null), 6) ?: return null)
                AivsError(Proto.scalar(err, 1)?.toInt(), Proto.bytes(err, 2)?.toString(Charsets.UTF_8))
            }
            18 -> {
                val media = Proto.decode(Proto.bytes(envelope, 20) ?: return null)
                when (id) {
                    16 -> MediaReport(mediaFiles(Proto.bytes(media, 14)))
                    22 -> Proto.bytes(media, 19)?.let { Proto.decode(it) }
                        ?.let { s -> RecordStatus(Proto.scalar(s, 2), s.filter { it.number == 1 && it.wireType == 2 }.mapNotNull { mediaFile(it.data) }) }
                    23 -> MediaSyncRequest(Proto.bytes(media, 20)?.let { Proto.scalar(Proto.decode(it), 1) })
                    else -> null
                }
            }
            23 -> {
                val ic = Proto.decode(Proto.bytes(envelope, 25) ?: return null)
                when (id) {
                    30, 33 -> misPacket(Proto.bytes(Proto.decode(Proto.bytes(ic, 22) ?: return null), 2) ?: return null)
                    41 -> Proto.bytes(ic, 29)?.let { Proto.decode(it) }?.let { CameraResponse(Proto.scalar(it, 1), Proto.scalar(it, 2) ?: 0) }
                    42 -> Proto.bytes(ic, 30)?.let { Proto.decode(it) }?.let { CameraEvent(Proto.scalar(it, 1) ?: 0, Proto.scalar(it, 10)) }
                    48 -> Proto.bytes(ic, 36)?.let { Proto.decode(it) }?.let { t ->
                        val p = Proto.bytes(t, 4)?.let { Proto.decode(it) }.orEmpty()
                        CameraThumbnail(Proto.bytes(t, 1)?.toString(Charsets.UTF_8).orEmpty(), Proto.scalar(t, 2) ?: 0, Proto.scalar(t, 3) ?: 0,
                            (Proto.scalar(p, 1) ?: 0).toInt(), (Proto.scalar(p, 2) ?: 0).toInt(), Proto.bytes(p, 3) ?: byteArrayOf())
                    }
                    45 -> Proto.bytes(ic, 33)?.let { Proto.decode(it) }?.let { LiveModes(Proto.scalar(it, 1) ?: 0, Proto.scalar(it, 2) ?: 0) }
                    47 -> Proto.bytes(ic, 35)?.let { Proto.decode(it) }?.let { LiveStatus(Proto.scalar(it, 1) ?: 0, Proto.bytes(it, 2)?.toString(Charsets.UTF_8)) }
                    60 -> GlassEvent(Proto.bytes(ic, 48)?.let { Proto.scalar(Proto.decode(it), 1) })
                    59 -> Vlog(Proto.bytes(ic, 47)?.let { Proto.scalar(Proto.decode(it), 1) } == 1L)
                    else -> null
                }
            }
            else -> null
        }
    }.getOrNull()

    /**
     * Mis.Payload.packet (f2) is MipProto.MipPacket {id f1, mipVer f2, type f3, spec f6, forwardFrom f14, forwardTo f15}.
     * MipSpec {type f1; payload f2..27}. Types 6/7 (PROPERTIES_CHANGED_IND[_NO_CNF]) carry f7/f8 {repeated PropertyChangedInd f1}.
     * PropertyChangedInd {did f1, miid f2, siid f3, piid f4, value f8 int32 | f9 int64 | f12 bool | f13 string ...}.
     * Type 11 ACTION_RSP carries ActionRsp at f12.
     */
    private fun misPacket(packet: ByteArray): Event? {
        val mip = Proto.decode(packet)
        val spec = Proto.decode(Proto.bytes(mip, 6) ?: return null)
        var did = Proto.bytes(mip, 14)?.toString(Charsets.UTF_8)
        val type = Proto.scalar(spec, 1)
        if (type == 11L) {
            val r = Proto.decode(Proto.bytes(spec, 12) ?: return MisOther(did, type))
            return MisActionResponse(Proto.bytes(r, 1)?.toString(Charsets.UTF_8) ?: did,
                Proto.scalar(r, 3) ?: 0, Proto.scalar(r, 4) ?: 0, Proto.scalar(r, 5) ?: 0)
        }
        val list = when (type) { 6L -> Proto.bytes(spec, 7); 7L -> Proto.bytes(spec, 8); else -> null } ?: return MisOther(did, type)
        val props = Proto.decode(list).filter { it.number == 1 && it.wireType == 2 }.map { item ->
            val f = Proto.decode(item.data)
            if (did == null) did = Proto.bytes(f, 1)?.toString(Charsets.UTF_8)
            val v = f.firstOrNull { it.number in 8..17 }
            val value = when {
                v == null -> "0"
                v.number == 12 -> (v.value != 0L).toString()
                v.wireType == 0 -> v.value.toInt().takeIf { v.number == 8 }?.toString() ?: v.value.toString()
                v.number == 13 || v.number == 15 -> "\"${v.data.toString(Charsets.UTF_8)}\""
                else -> Proto.hex(v.data)
            }
            MisProperty(Proto.scalar(f, 2) ?: 0, Proto.scalar(f, 3) ?: 0, Proto.scalar(f, 4) ?: 0, value)
        }
        return MisProperties(did, props)
    }

    /** MediaFile.List: repeated MediaFile list = 1. */
    fun mediaFiles(list: ByteArray?): List<MediaFile> = list?.let { Proto.decode(it) }.orEmpty()
        .filter { it.number == 1 && it.wireType == 2 }.mapNotNull { mediaFile(it.data) }

    private fun mediaFile(bytes: ByteArray): MediaFile? = runCatching {
        val f = Proto.decode(bytes)
        val ident = Proto.bytes(f, 1)?.let { Proto.decode(it) }
        MediaFile(
            id = ident?.let { Proto.bytes(it, 1) }?.toString(Charsets.UTF_8).orEmpty(),
            type = Proto.scalar(f, 2)?.toInt() ?: 0,
            size = Proto.scalar(f, 3) ?: 0,
            timestamp = Proto.scalar(f, 4) ?: 0,
            duration = Proto.scalar(f, 5),
        )
    }.getOrNull()
}
