package wtf.mahdi.glassesconnect

/** In-app TODO list. Keep in sync with analysis/commands.md. */
object Roadmap {
    enum class Status(val label: String) { Done("Done"), Untested("Built, not tested on glasses"), Todo("TODO") }
    data class Item(val title: String, val status: Status, val note: String)

    val items = listOf(
        Item("Classic BT RFCOMM (SPP UUID 1101)", Status.Untested, "Pair glasses in Android Bluetooth settings first."),
        Item("SPP version probe + L1 (0xA5A5) negotiation", Status.Untested, "WearTransport. CRC16/ACK/NAK/MPS per APK TransportV2."),
        Item("WearAuthV2 handshake (AppVerify/AppConfirm)", Status.Untested, "Needs encrypt_key from Xiaomi cloud (Connect tab or tools/fetch_key.py)."),
        Item("AES-CTR session encryption (type 101)", Status.Untested, "Key = IV = appKey (send) / deviceKey (receive)."),
        Item("Battery / device status", Status.Done, "System id 1; unsolicited battery report id 97."),
        Item("Device info, settings, wear status, basic status", Status.Untested, "Raw protobuf dump only; typed decoding TODO."),
        Item("Audio recording start/stop/status", Status.Untested, "Media id 20/21, RecordRequest type 0 normal."),
        Item("Media file summary", Status.Untested, "Media id 14."),
        Item("Fetch encrypt_key inside the app", Status.Untested, "cloud/XiaomiCloud.kt: port of tools/fetch_key.py (passport login, 2FA, RC4 signing). Session cookies saved in prefs."),
        Item("SPP v1 framing (0xBADCFE) + AES-CCM counter mode", Status.Todo, "Only if glasses report protocol v1."),
        Item("Typed decoders (DeviceInfo, SystemSetting)", Status.Todo, "Map SystemProtos fields to readable UI."),
        Item("Settings tab (read id 14, write id 15)", Status.Untested, "Read-modify-write per group; gesture/wear enums unknown (official app never writes them)."),
        Item("Set system time (id 3), device name (id 107)", Status.Todo, "Wrappers exist in APK (SetSystemTime/SetDeviceName)."),
        Item("Thumbnail download (Bluetooth mass channel)", Status.Done, "Media 18 request (thumbnail=true) -> L2 channel 2 slices, cmd 131, CRC32 -> confirm 19."),
        Item("Sync full-size media to gallery (Wi-Fi Direct)", Status.Done, "Phone GO + System 99; HTTP :8080 /v1/filelists. MP4 as-is; photos = one HEIC frame. Never deletes."),
        Item("Multi-frame photo merge (MFNR/LLHDR)", Status.Todo, "Official app merges 6 HEIC frames with native libalgoprocessor; we keep one frame."),
        Item("Video stabilisation (EIS gyro)", Status.Todo, "Video folders carry .gyro/.meta for phone-side EIS; we save the raw MP4."),
        Item("Button capture events (Media tab)", Status.Done, "Media 16 file report, 22 record status, 23 sync request; Interconnection 59 vlog, 60 glass event."),
        Item("Photo / video capture from phone", Status.Done, "MIoT action 2.3 take-photo / 2.1 toggle-record via SEND_MIS_PACKET (23/33). Needs empty 'in' arguments."),
        Item("AI assistant (AIVS) / voice", Status.Todo, "Xiaomi XiaoAi cloud; likely not usable outside China."),
        Item("Kotlin mSBC decoder (replaces libsbc.so)", Status.Untested, "ai/MsbcDecoder; matches ffmpeg in unit tests. Confirm Gemini hears speech on device."),
        Item("Pass photo to Gemini", Status.Done, "Gemini Live take_photo(question) -> Aivs MultiModal cmd 1 -> 576x432 JPEG -> one-shot vision model (gemini.visionModel) -> text toolResponse. Live session holds no images."),
        Item("AI image capture (Aivs MultiModal)", Status.Done, "Aivs id4 MultiModal cmd 1 -> 576x432 JPEG (types 3/4) on voice channel; cmd 3 -> 960x720 original of last capture (types 9/11). Saved to files/ai-images."),
        Item("Live streaming (Interconnection 23/46)", Status.Done, "Stream tab: System 56 add Wi-Fi (result 98), 23/45 query modes, 23/46 start/stop RTMP push (480p-1080p, 15-30 fps), 23/47 status."),
        Item("Firmware update (OTA)", Status.Todo, "Out of scope; risky."),
        Item("Background session + auto-reconnect", Status.Untested, "GlassesService (foreground, connectedDevice), backoff reconnect, ACL/BT-on trigger, CDM presence (Android 13+), boot restart, AI wake lock."),
        Item("Fresh bind without official app", Status.Done, "PSK bind: glasses <-> /glasses/bind/hello|ecdh|bind relay (WearBind + GlassesBinder). Glasses must be unbound first."),
    )
}
