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
        Item("Pass photo to Gemini", Status.Todo, "Default (XiaoAi) assistant auto-captures a photo and sends it to the AI; Gemini path does not. Capture photo + send image to Gemini Live."),
        Item("Live streaming (Interconnection 23/46)", Status.Todo, "Needs RTMP URL + Wi-Fi setup."),
        Item("Firmware update (OTA)", Status.Todo, "Out of scope; risky."),
        Item("Fresh bind without official app", Status.Todo, "Bind needs Xiaomi server signatures (/glasses/bind/*); keep existing CN bind."),
    )
}
