package wtf.mahdi.glassesconnect

/** In-app TODO list. Keep in sync with analysis/commands.md. */
object Roadmap {
    enum class Status(val label: String) { Done("Done"), Untested("Built, not tested on glasses"), Todo("TODO") }
    data class Item(val title: String, val status: Status, val note: String)

    val items = listOf(
        Item("Classic BT RFCOMM (SPP UUID 1101)", Status.Untested, "Pair glasses in Android Bluetooth settings first."),
        Item("SPP version probe + L1 (0xA5A5) negotiation", Status.Untested, "WearTransport. CRC16/ACK/NAK/MPS per APK TransportV2."),
        Item("WearAuthV2 handshake (AppVerify/AppConfirm)", Status.Untested, "Needs encrypt_key from Xiaomi cloud (onboarding or tools/fetch_key.py)."),
        Item("AES-CTR session encryption (type 101)", Status.Untested, "Key = IV = appKey (send) / deviceKey (receive)."),
        Item("Battery / device status", Status.Done, "System id 1; unsolicited battery report id 97."),
        Item("Device info, settings, wear status, basic status", Status.Untested, "Raw protobuf dump only; typed decoding TODO."),
        Item("Audio recording start/stop/status", Status.Done, "Media id 20/21/22, RecordRequest type 0 normal; RecordStatus extraData.status 0 recording, 1 idle, 2 ended (inferred). Gallery -> Recordings Start/Stop."),
        Item("Audio recordings list and playback", Status.Done, "Two-finger gesture files (audio/ogg) come with the Wi-Fi sync -> Recordings/GlassesConnect; Gallery -> Recordings plays them (ExoPlayer). Transcription out of scope."),
        Item("Media file summary", Status.Untested, "Media id 14."),
        Item("Fetch encrypt_key inside the app", Status.Untested, "cloud/XiaomiCloud.kt: port of tools/fetch_key.py (passport login, 2FA, RC4 signing). Session cookies saved in prefs."),
        Item("SPP v1 framing (0xBADCFE) + AES-CCM counter mode", Status.Todo, "Only if glasses report protocol v1."),
        Item("Typed decoders (DeviceInfo, SystemSetting)", Status.Todo, "Map SystemProtos fields to readable UI."),
        Item("Glasses settings (read id 14, write id 15)", Status.Untested, "Written on each change, then re-read to confirm; gesture/wear enums unknown (official app never writes them)."),
        Item("Set system time (id 3)", Status.Done, "SystemTime {date, time, timeZone sint32 15-min offset + id, is12Hours}; sent once after each connect. Official app also resends every 30 min."),
        Item("Set device name (id 107)", Status.Todo, "Wrapper exists in APK (SetDeviceName)."),
        Item("Thumbnail download (Bluetooth mass channel)", Status.Done, "Media 18 request (thumbnail=true) -> L2 channel 2 slices, cmd 131, CRC32 -> confirm 19."),
        Item("Sync full-size media to gallery (Wi-Fi Direct)", Status.Done, "Phone GO + System 99; HTTP :8080 /v1/filelists. MP4 as-is; photos = one HEIC frame. Never deletes."),
        Item("Multi-frame photo merge (MFNR/LLHDR)", Status.Todo, "Official app merges 6 HEIC frames with native libalgoprocessor; we keep one frame."),
        Item("Video stabilisation (EIS gyro)", Status.Todo, "Video folders carry .gyro/.meta for phone-side EIS; we save the raw MP4."),
        Item("Button capture events", Status.Done, "Media 16 file report, 22 record status, 23 sync request; Interconnection 59 vlog, 60 glass event."),
        Item("Photo / video capture from phone", Status.Done, "MIoT action 2.3 take-photo / 2.1 toggle-record via SEND_MIS_PACKET (23/33). Needs empty 'in' arguments."),
        Item("AI assistant (AIVS) / voice", Status.Todo, "Xiaomi XiaoAi cloud; likely not usable outside China."),
        Item("Kotlin mSBC decoder (replaces libsbc.so)", Status.Untested, "ai/MsbcDecoder; matches ffmpeg in unit tests. Confirm Gemini hears speech on device."),
        Item("Pass photo to Gemini", Status.Done, "Gemini Live take_photo(question) -> Aivs MultiModal cmd 1 -> 576x432 JPEG -> one-shot vision model (gemini.visionModel) -> text toolResponse. Live session holds no images."),
        Item("Gemini history and settings", Status.Untested, "Gemini tab: turns grouped by Live session (files/ai-history.json, last 400) with the take_photo images; settings: key, Live/photo model, voice, answer language, custom instructions (added to the base prompt)."),
        Item("Save photo by voice", Status.Untested, "Gemini Live capture_photo tool -> MIoT take-photo 2.3 (full size, shows in Gallery). Not verified that the camera accepts it during an AI turn."),
        Item("AI image capture (Aivs MultiModal)", Status.Done, "Aivs id4 MultiModal cmd 1 -> 576x432 JPEG (types 3/4) on voice channel; cmd 3 -> 960x720 original of last capture (types 9/11). Saved to files/ai-images."),
        Item("Live streaming (Interconnection 23/46)", Status.Done, "Stream screen: System 56 add Wi-Fi (result 98), 23/45 query modes, 23/46 start/stop RTMP push (480p-1080p, 15-30 fps), 23/47 status."),
        Item("Live preview on the phone", Status.Done, "stream/RtmpServer (pure Kotlin RTMP ingest, :1935) + LivePreview (MediaCodec to SurfaceView). Glasses send H.264 720p30 + AAC; audio not played yet."),
        Item("Firmware update (OTA)", Status.Todo, "Out of scope; risky."),
        Item("Background session + auto-reconnect", Status.Untested, "GlassesService (foreground, connectedDevice), backoff reconnect, ACL/BT-on trigger, CDM presence (Android 13+), boot restart, AI wake lock."),
        Item("New UI (Device, Gallery, Gemini, More)", Status.Untested, "Compose rewrite: Navigation 3, ViewModel per screen, Coil gallery from MediaStore, ExoPlayer viewer, instant settings writes, onboarding wizard."),
        Item("Fresh bind without official app", Status.Done, "PSK bind: glasses <-> /glasses/bind/hello|ecdh|bind relay (WearBind + GlassesBinder). Glasses must be unbound first."),
    )
}
