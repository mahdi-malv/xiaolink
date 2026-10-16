package wtf.mahdi.glassesconnect.protocol

/** Observed O95 protobuf commands. See analysis/commands.md for APK evidence. */
object GlassesCommands {
    data class Command(
        val key: String,
        val title: String,
        val type: Int,
        val id: Int,
        val explanation: String,
        val changesDevice: Boolean = false,
        val payload: () -> ByteArray,
    )
    fun packet(type: Int, id: Int, payloadField: Int, payload: ByteArray = byteArrayOf()): ByteArray = Proto.encode {
        uint(1, type); uint(2, id); bytes(payloadField, payload)
    }
    fun system(id: Int, payload: ByteArray = byteArrayOf()) = packet(2, id, 4, payload)
    fun media(id: Int, payload: ByteArray = byteArrayOf()) = packet(18, id, 20, payload)
    fun interconnection(id: Int, payload: ByteArray = byteArrayOf()) = packet(23, id, 25, payload)
    /** Glasses MIoT did (e.g. "969050158"); learned from MIS reports (type 23 / id 30). */
    @Volatile var misDid: String = ""
    private var misPacketId = 0
    /**
     * MIoT spec action (miwear.phovideo.o95cn on miot-spec.org): siid 2 photography-video,
     * aiid 1 toggle-record, 2 start-special-event-record, 3 take-photo.
     * MipPacket {id f1, mipVer=17 f2, type=2 f3, MipSpec f6 {type=9 ACTION_REQ f1, ActionReq f10 {did f1, miid f2, siid f3, aiid f4}}}
     * (MisProxyDeviceManager.send), wrapped in Interconnection SEND_MIS_PACKET (id 33) Mis.Payload {spec="" f1, packet f2}.
     */
    fun misAction(siid: Int, aiid: Int, did: String = misDid, miid: Int = 1, emptyIn: Boolean = true): ByteArray {
        val mip = Proto.encode {
            uint(1, ++misPacketId); uint(2, 17); uint(3, 2)
            message(6) {
                uint(1, 9)
                message(10) {
                    if (did.isNotEmpty()) string(1, did); uint(2, miid); uint(3, siid); uint(4, aiid)
                    // Without f5 (Arguments in) the glasses answer -4015 SPECV3_ACTION_PARAM_INVALID.
                    if (emptyIn) bytes(5, byteArrayOf())
                }
            }
        }
        return interconnection(33, Proto.encode { message(22) { string(1, ""); bytes(2, mip) } })
    }
    /** RequestMediaFileList (id 18): Media f16 Identifier.List {repeated Identifier f1 {id f1, thumbnail f2}}. Reply on mass channel. */
    fun requestThumbnails(ids: List<String>): ByteArray = media(18, Proto.encode {
        message(16) { ids.forEach { id -> message(1) { string(1, id); uint(2, 1) } } }
    })
    /** ConfirmMediaFile (id 19): Media f15 Identifier {id, thumbnail=true}; official app sends it after saving a thumbnail. */
    fun confirmThumbnail(id: String): ByteArray = media(19, Proto.encode { message(15) { string(1, id); uint(2, 1) } })
    /**
     * EnableWiFiP2PGC (System id 99): System f65 WiFiP2P {ssid f1, password f2, frequency f3 MHz, taskId f4}.
     * Phone is Wi-Fi Direct group owner; glasses join and reply System f66 {code, ipAddress, taskId}.
     */
    fun enableP2p(ssid: String, password: String, frequency: Int): ByteArray = system(99, Proto.encode {
        message(65) { string(1, ssid); string(2, password); uint(3, frequency) }
    })
    /**
     * Device-verified. ADD_WIFI_CONFIG (System id 56, SetWiFIConfig.java:234): System f32 WiFiConfig {basicInfo f1 {ssid f1,
     * authMode f2 (1 open, 2 PSK), frequency f3 (app always sends 2)}, oneof auth: psk f2 {password f1} | stub f4 {}}.
     * Glasses join and report NOTIFY_WIFI_CONFIG_RESULT (id 98) System f64 {ssid f1, code f2 (0 ok), ip f3}.
     */
    fun addWifi(ssid: String, password: String): ByteArray = system(56, Proto.encode {
        message(32) {
            message(1) { string(1, ssid); uint(2, if (password.isEmpty()) 1 else 2); uint(3, 2) }
            if (password.isEmpty()) message(4) {} else message(2) { string(1, password) }
        }
    })
    /** Untested. GET_WIFI_CONFIG_LIST (System id 86): saved networks on the glasses. */
    fun wifiList(): ByteArray = system(86)
    /** Device-verified (reply supported=1). QUERY_LIVE_STREAMING (Interconnection id 45, command o95/i.java): reply f33 {supportedLiveMode f1, currentLiveMode f2}. */
    fun liveQuery(): ByteArray = interconnection(45)
    /**
     * Device-verified (480p/1080p, modes 1-3). REQUEST_LIVE_STREAMING (Interconnection id 46): f34 Request {cmd f1 (1 start, 2 stop), param f2 {resolution f1,
     * fps f2, bps f3, livePlatform f4, liveMode f5, liveUrl f6}} (StartGlassLiveStreaming.java, StopLiveLiveStreaming.java).
     * Values (livestream/data/model): resolution 1 480p, 2 720p, 3 1080p; fps 15/24/25/30; "bps" carries StreamMode
     * 1 auto, 2 frame rate first, 3 quality first (RTMPLiveManager.startPushLiveStream); livePlatform 0 RTMP;
     * liveMode 1 Wi-Fi/hotspot, 2 wired, 4 via Bluetooth (LiveStreamType.toFirmwareValue). The glasses push RTMP themselves.
     */
    fun liveStart(url: String, resolution: Int = 2, fps: Int = 30, streamMode: Int = 1, liveMode: Int = 1): ByteArray =
        interconnection(46, Proto.encode {
            message(34) {
                uint(1, 1)
                message(2) { uint(1, resolution); uint(2, fps); uint(3, streamMode); uint(4, 0); uint(5, liveMode); string(6, url) }
            }
        })
    fun liveStop(): ByteArray = interconnection(46, Proto.encode { message(34) { uint(1, 2) } })
    fun aivs(id: Int, payload: ByteArray = byteArrayOf()) = packet(14, id, 16, payload)
    /** AivsStatus (Aivs f5): voice 0 idle/1 wake/2 recording/3 standby, tts 0 idle/1 playing/2 thinking, device 0 online. */
    fun aivsStatus(voice: Int, tts: Int = 0, device: Int = 0): ByteArray = aivs(5, Proto.encode {
        message(5) { uint(1, voice); uint(2, tts); uint(3, 0); uint(4, 0); uint(5, 0); uint(6, device) }
    })
    /**
     * InstructionList with one resultType 3 (STOP_CAPTURE), as MiWearModuleImpl.sendAivsInstruction → SyncInstructionList.
     * AivsInstruction.writeTo (AivsProtos.java:1876, 1918) always writes f1 resultType and f13 sessionId (0); without
     * f13 the glasses ignored the stop; with it the mic stream ends at once (device-verified).
     */
    fun aivsStopCapture(): ByteArray = aivs(0, Proto.encode { message(1) { message(1) { uint(1, 3); uint(13, 0) } } })
    /** Phone-initiated wake-up (OpenDevice): WakeUpEvent cmd 5 with a fresh request id. */
    fun aivsWake(): ByteArray = aivs(10, Proto.encode {
        message(11) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            message(1) { string(1, id); string(2, id) }
            uint(2, 5)
        }
    })
    /** Request id of the last [aivsMultiModal] START_MM_IMG; START_ORI_IMG reuses it. */
    var multiModalId = ""
    /**
     * Device-verified (cmd 1, 3). RequestAivsMultiModal (bluetooth/command/o95/RequestAivsMultiModal.java, packed id 4): Aivs f4 MultiModal
     * {f1 AivsRequestInfo {requestId f1, transactionId f2}, f2 cmd} (AivsProtos.java:738, 4803). cmd (MultiModalCmd):
     * 1 START_MM_IMG, 2 STOP_MM_IMG, 3 START_ORI_IMG, 4 STOP_ORI_IMG. Cloud UploadMultiModal → ImageEventProcess.java:296
     * sends cmd 1; LargePictureViewModel sends cmd 3. Glasses reply on the voice channel with image mass types 3/4 (cropped)
     * or 9/11 (original).
     */
    fun aivsMultiModal(cmd: Int): ByteArray = aivs(4, Proto.encode {
        if (cmd == 1 || multiModalId.isEmpty()) multiModalId = java.util.UUID.randomUUID().toString().replace("-", "")
        message(4) {
            message(1) { string(1, multiModalId); string(2, multiModalId) }
            uint(2, cmd)
        }
    })
    fun deviceStatus(): ByteArray = system(1)
    fun settings(): ByteArray = system(14, Proto.encode { message(34) { uint(1, 496) } })
    /** Normal audio recording: type=0; action=0 start, action=1 stop. */
    fun record(start: Boolean): ByteArray = media(20, Proto.encode {
        message(17) { uint(1, 0); uint(2, if (start) 0 else 1) }
    })
    val catalog: List<Command> = listOf(
        Command("status", "Battery and device status", 2, 1, "Queries battery, storage and device state.", payload = ::deviceStatus),
        Command("info", "Device information", 2, 2, "Queries firmware and device identity.", payload = { system(2) }),
        Command("settings", "Read settings", 2, 14, "Queries headset, media, AI, indicator and OTA settings.", payload = ::settings),
        Command("basic", "Basic status", 2, 78, "Queries basic status; firmware support needs hardware verification.", payload = { system(78) }),
        Command("wear", "Wear status", 2, 93, "Queries whether the glasses are worn.", payload = { system(93) }),
        Command("summary", "Media summary", 18, 14, "Queries the media file summary; does not download or delete files.", payload = { media(14) }),
        Command("media_sync", "Sync media list", 18, 15, "Asks the glasses to report captured files (reply: id 16). Does not download or delete.", payload = { media(15) }),
        Command("record_status", "Audio recording status", 18, 21, "Queries the audio recording state.", payload = { media(21) }),
        Command("record_start", "Start audio recording", 18, 20, "Starts normal audio recording on the glasses. Unverified on this hardware.", true, { record(true) }),
        Command("record_stop", "Stop audio recording", 18, 20, "Stops normal audio recording. Unverified on this hardware.", true, { record(false) }),
        Command("ai_online", "AI: report online", 14, 5, "AivsStatus voice=idle, device=online. Tells the glasses the phone AI is ready.", true) { aivsStatus(0) },
        Command("ai_listening", "AI: report listening", 14, 5, "AivsStatus voice=recording (2).", true) { aivsStatus(2) },
        Command("ai_wake", "AI: wake from phone", 14, 10, "WakeUpEvent cmd=5 (phone-initiated). Glasses should start streaming mic audio.", true) { aivsWake() },
        Command("ai_stop", "AI: stop capture", 14, 0, "InstructionList STOP_CAPTURE. Ends mic streaming.", true) { aivsStopCapture() },
        Command("ai_img", "AI: capture image", 14, 4, "MultiModal START_MM_IMG (cmd 1). Glasses take a photo and send a 576x432 JPEG on the voice channel.", true) { aivsMultiModal(1) },
        Command("ai_img_stop", "AI: stop image", 14, 4, "MultiModal STOP_MM_IMG (cmd 2). Untested.", true) { aivsMultiModal(2) },
        Command("ai_img_ori", "AI: original image", 14, 4, "MultiModal START_ORI_IMG (cmd 3): last capture again as 960x720 JPEG. No new photo.", true) { aivsMultiModal(3) },
        Command("ai_img_ori_stop", "AI: stop original", 14, 4, "MultiModal STOP_ORI_IMG (cmd 4). Untested.", true) { aivsMultiModal(4) },
        Command("take_photo", "Take photo", 23, 33, "MIoT action 2.3 take-photo via MIS bridge. Needs did from a MIS report.", true) { misAction(2, 3) },
        Command("toggle_record", "Start/stop video", 23, 33, "MIoT action 2.1 toggle-record via MIS bridge.", true) { misAction(2, 1) },
        Command("special_record", "Special-event record", 23, 33, "MIoT action 2.2 start-special-event-record; meaning unknown.", true) { misAction(2, 2) },
    )
    /** Returns a battery percentage only when the documented status structure is present. */
    fun batteryCapacity(packet: ByteArray): Int? {
        val envelope = Proto.decode(packet)
        if (Proto.scalar(envelope, 1) != 2L) return null
        val system = Proto.decode(Proto.bytes(envelope, 4) ?: return null)
        val batteryBytes = if (Proto.scalar(envelope, 2) == 97L) Proto.bytes(system, 63)
            else Proto.bytes(system, 2)?.let { Proto.bytes(Proto.decode(it), 1) }
        // DeviceStatus.Battery {capacity f1, chargeStatus f2} (device log id 97: {1: 95, 2: 0}).
        return batteryBytes?.let { Proto.scalar(Proto.decode(it), 1) }?.takeIf { it in 0..100 }?.toInt()
    }

    /** Generic protobuf tree dump; nested messages are guessed (bytes that parse cleanly). */
    fun dump(packet: ByteArray, indent: String = ""): String = buildString {
        val fields = runCatching { Proto.decode(packet) }.getOrNull() ?: return "${indent}${Proto.hex(packet)}"
        for (f in fields) {
            when (f.wireType) {
                0 -> appendLine("$indent${f.number}: ${f.value}")
                2 -> {
                    val nested = f.data.isNotEmpty() && runCatching { Proto.decode(f.data) }.isSuccess && indent.length < 24
                    val text = f.data.toString(Charsets.UTF_8)
                    if (nested && !text.all { it.isLetterOrDigit() || it in " .-_:/" }) {
                        appendLine("$indent${f.number} {"); append(dump(f.data, "$indent  ")); appendLine("$indent}")
                    } else if (text.isNotEmpty() && text.all { !it.isISOControl() && it != '\uFFFD' }) appendLine("$indent${f.number}: \"$text\"")
                    else appendLine("$indent${f.number}: ${Proto.hex(f.data)}")
                }
                else -> appendLine("$indent${f.number}: ${Proto.hex(f.data)}")
            }
        }
    }
}
