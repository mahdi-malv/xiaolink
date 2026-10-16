package wtf.mahdi.glassesconnect

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import wtf.mahdi.glassesconnect.bluetooth.SppLink
import wtf.mahdi.glassesconnect.ai.AiAssistant
import wtf.mahdi.glassesconnect.cloud.XiaomiCloud
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import wtf.mahdi.glassesconnect.protocol.GlassesEvents
import wtf.mahdi.glassesconnect.protocol.GlassesSettings
import wtf.mahdi.glassesconnect.protocol.MassFileReceiver
import wtf.mahdi.glassesconnect.protocol.Proto
import wtf.mahdi.glassesconnect.protocol.WearSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PairedDevice(val address: String, val name: String)

data class UiState(
    val paired: List<PairedDevice> = emptyList(),
    val address: String = "",
    val token: String = "",
    /** Gemini assistant settings; key default: gemini.apiKey from local.properties (dev builds). */
    val aiSettings: wtf.mahdi.glassesconnect.ai.AiSettings = wtf.mahdi.glassesconnect.ai.AiSettings(),
    val state: String = "Disconnected",
    val ready: Boolean = false,
    val connecting: Boolean = false,
    val battery: Int? = null,
    val lastResponse: String = "",
    val media: List<GlassesEvents.MediaFile> = emptyList(),
    /** Files the glasses reported since the last successful gallery sync (the "new" badge). Saved in prefs. */
    val pending: List<GlassesEvents.MediaFile> = emptyList(),
    /** Phone time when the camera status turned to video recording (5); null when not recording. */
    val recordingSince: Long? = null,
    val events: List<String> = emptyList(),
    val cameraStatus: Long? = null,
    val thumbs: Map<String, String> = emptyMap(),
    val downloading: String? = null,
    val thumbQueue: Int = 0,
    val syncing: Boolean = false,
    val syncStatus: String = "",
    val log: List<String> = emptyList(),
    val aiState: String = "Idle",
    /** SystemSetting bytes as read from the glasses plus local edits. */
    val settings: ByteArray? = null,
    /** Group fields with unsent edits. */
    val settingsDirty: Set<Int> = emptySet(),
    val cloud: CloudState = CloudState(),
    val bind: BindState = BindState(),
    val stream: StreamState = StreamState(),
)

/** Live stream (RTMP pushed by the glasses over their own Wi-Fi). Device-verified 2026-10-16. */
data class StreamState(
    val ssid: String = "",
    val password: String = "",
    val url: String = "",
    /** 1 480p, 2 720p, 3 1080p. */
    val resolution: Int = 2,
    val fps: Int = 30,
    /** 1 auto, 2 frame rate first, 3 quality first. */
    val mode: Int = 1,
    val wifi: String = "",
    val modes: String = "",
    val status: String = "",
    val preview: wtf.mahdi.glassesconnect.stream.PreviewState = wtf.mahdi.glassesconnect.stream.PreviewState(),
    /** Phone IPv4 addresses (interface to address) for the receiver URL. */
    val phoneIps: List<Pair<String, String>> = emptyList(),
)

/** Fresh bind progress. [oob] is the pairing code when the glasses use oobMode 1. */
data class BindState(val running: Boolean = false, val status: String = "", val oob: String? = null)

/** Xiaomi cloud key fetch. [needsCode] is non-null while 2FA waits for a code. */
data class CloudState(
    val user: String = "",
    val busy: Boolean = false,
    val status: String = "",
    val needsCode: XiaomiCloud.LoginResult.NeedsCode? = null,
    val codeSent: Boolean = false,
    val devices: List<XiaomiCloud.Device> = emptyList(),
    val signedIn: Boolean = false,
)

/**
 * App-wide owner of the glasses session, UI state and features. Lives as long as the process;
 * [GlassesService] keeps the process alive while [keepConnected] is set, so the session and the
 * AI assistant work with the app in the background or the screen off.
 */
@SuppressLint("MissingPermission")
class GlassesController private constructor(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val logFile = app.getExternalFilesDir(null)?.let { java.io.File(it, "log.txt") }
    private val prefs = app.getSharedPreferences("glasses", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UiState(
        address = prefs.getString("address", "").orEmpty(),
        token = prefs.getString("token", "").orEmpty(),
        aiSettings = wtf.mahdi.glassesconnect.ai.AiSettings.load(prefs),
        cloud = CloudState(user = prefs.getString("cloud_user", "").orEmpty()),
        pending = loadPending(prefs),
        stream = StreamState(
            ssid = prefs.getString("stream_ssid", "").orEmpty(),
            password = prefs.getString("stream_password", "").orEmpty(),
            url = prefs.getString("stream_url", "").orEmpty(),
            resolution = prefs.getInt("stream_resolution", 2),
            fps = prefs.getInt("stream_fps", 30),
            mode = prefs.getInt("stream_mode", 1),
        ),
    ))
    private val cloud = XiaomiCloud(prefs)
    @Volatile private var cloudAuth: XiaomiCloud.Auth? = null
    @Volatile private var binder: wtf.mahdi.glassesconnect.cloud.GlassesBinder? = null
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    var autoThumbnails = true
    init {
        GlassesCommands.misDid = prefs.getString("mis_did", "").orEmpty()
        _ui.update { s -> s.copy(thumbs = thumbDir.listFiles().orEmpty().associate { it.name to it.absolutePath }) }
    }
    private var link: SppLink? = null
    private var session: WearSession? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    /** Gemini conversations for the Gemini tab (files/ai-history.json). */
    val aiHistory = wtf.mahdi.glassesconnect.ai.AiHistory(java.io.File(app.filesDir, "ai-history.json"))
    private val ai = AiAssistant({ _ui.value.aiSettings },
        sendToGlasses = { packet -> session?.send(packet) },
        log = ::log,
        onState = { st -> _ui.update { it.copy(aiState = st) }; aiWakeLock(st != "Idle") },
        onTurn = aiHistory::add)

    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassesConnect:ai").apply { setReferenceCounted(false) }

    /** Keeps the CPU on during an AI turn (screen off): audio decode, Gemini WebSocket, TTS playback. */
    private fun aiWakeLock(on: Boolean) = if (on) wakeLock.acquire(10 * 60_000L) else if (wakeLock.isHeld) wakeLock.release() else Unit

    /** Ends the current AI turn (Stop on the Gemini tab). */
    fun stopAi() = ai.stop()

    fun log(line: String) {
        android.util.Log.i("GlassesConnect", line)
        // Debug aid: adb pull /sdcard/Android/data/wtf.mahdi.glassesconnect/files/log.txt
        runCatching { logFile?.appendText("${time.format(Date())} $line\n") }
        _ui.update { it.copy(log = (it.log + "${time.format(Date())} $line").takeLast(300)) }
    }

    fun clearLog() { _ui.update { it.copy(log = emptyList()) }; runCatching { logFile?.writeText("") } }

    fun refreshPaired() {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        val devices = runCatching { adapter?.bondedDevices.orEmpty().map { PairedDevice(it.address, it.name ?: it.address) } }
            .getOrElse { log("Cannot list paired devices: ${it.message}"); emptyList() }
        _ui.update { it.copy(paired = devices.sortedBy { d -> d.name }) }
    }

    fun setAddress(value: String) { _ui.update { it.copy(address = value.trim().uppercase()) }; prefs.edit().putString("address", _ui.value.address).apply() }
    /** Saves Gemini settings; an open Gemini session closes so the next wake-up uses them. */
    fun setAiSettings(value: wtf.mahdi.glassesconnect.ai.AiSettings) {
        val clean = value.copy(key = value.key.trim(), liveModel = value.liveModel.trim(), visionModel = value.visionModel.trim(),
            language = value.language.trim(), instructions = value.instructions.trim())
        _ui.update { it.copy(aiSettings = clean) }
        clean.save(prefs)
        ai.resetSession()
    }
    fun setToken(value: String) { _ui.update { it.copy(token = value.trim()) }; prefs.edit().putString("token", _ui.value.token).apply() }

    private fun cloudUpdate(f: (CloudState) -> CloudState) = _ui.update { it.copy(cloud = f(it.cloud)) }

    /** Runs a blocking cloud call on IO; errors go to the cloud status line and the log. */
    private fun cloudCall(label: String, block: () -> Unit) {
        cloudUpdate { it.copy(busy = true, status = label) }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching(block).onFailure { e ->
                log("Cloud: ${e.message}")
                cloudUpdate { it.copy(status = "Error: ${e.message}") }
            }
            cloudUpdate { it.copy(busy = false) }
        }
    }

    /** Signs in (saved session first, then [user]/[password]) and lists bound glasses. */
    fun cloudLogin(user: String, password: String) {
        prefs.edit().putString("cloud_user", user.trim()).apply()
        cloudUpdate { it.copy(user = user.trim(), needsCode = null, codeSent = false, devices = emptyList()) }
        cloudCall("Signing in…") {
            when (val r = cloud.login(user.trim(), password)) {
                is XiaomiCloud.LoginResult.Done -> cloudDevices(r.auth)
                is XiaomiCloud.LoginResult.NeedsCode -> cloudUpdate { it.copy(needsCode = r, status = "Verification needed: send a code") }
            }
        }
    }

    fun cloudSendCode(email: Boolean) = cloudCall("Sending code…") {
        cloud.sendCode(email)
        cloudUpdate { it.copy(codeSent = true, status = "Code sent by ${if (email) "e-mail" else "SMS"}") }
    }

    fun cloudVerify(email: Boolean, code: String) = cloudCall("Verifying…") {
        val auth = cloud.verifyCode(email, code)
        cloudUpdate { it.copy(needsCode = null, codeSent = false) }
        cloudDevices(auth)
    }

    fun cloudLogout() { cloud.logout(); cloudAuth = null; cloudUpdate { CloudState(user = it.user, status = "Signed out") } }

    private fun cloudDevices(auth: XiaomiCloud.Auth) {
        cloudAuth = auth
        cloudUpdate { it.copy(signedIn = true) }
        cloudUpdate { it.copy(status = "Signed in as ${auth.userId}. Loading devices…") }
        val devices = cloud.devices(auth)
        log("Cloud: ${devices.size} device(s): " + devices.joinToString { "${it.model} ${it.mac}" })
        cloudUpdate { it.copy(devices = devices, status = "Signed in as ${auth.userId}. " +
            if (devices.isEmpty()) "No glasses bound to this account (use Fresh bind below)." else "Tap a device to use its key.") }
    }

    /** Uses a cloud device's key and MAC for the next connect. */
    fun useCloudDevice(device: XiaomiCloud.Device) {
        setToken(device.encryptKey)
        if (device.mac.isNotBlank()) setAddress(device.mac)
        log("Using encrypt_key of ${device.name.ifEmpty { device.model }} (${device.mac})")
    }

    /** User connect: keeps the session up in the background (foreground service + auto-reconnect). */
    fun connect() {
        setKeepConnected(true)
        GlassesService.start(app)
        CompanionLink.observe(app, _ui.value.address, ::log)
        reconnectJob?.cancel(); reconnectAttempt = 0
        openWithToken()
    }

    private fun openWithToken() {
        val s = _ui.value
        val token = s.token.replace(" ", "")
        if (!Regex("[0-9a-fA-F]+").matches(token) || token.length % 2 != 0) return log("Token must be the hex encrypt_key")
        val tokenBytes = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        open(s.address, tokenBytes)
    }

    /** Set by [connect], cleared by [disconnect]. Survives restarts: the service and boot receiver read it. */
    val keepConnected get() = prefs.getBoolean("keep_connected", false)
    private fun setKeepConnected(on: Boolean) = prefs.edit().putBoolean("keep_connected", on).apply()

    /** Incremented by every [open]/[closeLink]; callbacks of an older link are ignored. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    /** Retries with backoff (2 s .. 60 s) while [keepConnected]. Not in bind mode. */
    private fun scheduleReconnect(reason: String) = scope.launch {
        if (!keepConnected || _ui.value.bind.running) return@launch
        if (reconnectJob?.isActive == true) return@launch
        val wait = listOf(2, 5, 10, 20, 30, 60)[minOf(reconnectAttempt, 5)]
        reconnectAttempt++
        log("Reconnect in $wait s ($reason)")
        _ui.update { it.copy(state = "Waiting to reconnect ($wait s)") }
        reconnectJob = scope.launch {
            delay(wait * 1000L)
            if (keepConnected && !_ui.value.ready && !_ui.value.connecting) openWithToken()
        }
    }

    /** Fast path when the glasses come back (ACL connect, Bluetooth on, CDM presence). */
    fun reconnectNow(reason: String) = scope.launch {
        if (!keepConnected || _ui.value.ready || _ui.value.connecting || _ui.value.bind.running) return@launch
        log("Reconnect now ($reason)")
        reconnectJob?.cancel(); reconnectAttempt = 0
        openWithToken()
    }

    /**
     * Opens RFCOMM and a session. [token] null = bind mode: [onUnauth] runs (on a new thread) once L1 is up,
     * and plain packets go to [onPlain].
     */
    private fun open(address: String, token: ByteArray?, onUnauth: ((WearSession) -> Unit)? = null, onPlain: ((ByteArray) -> Unit)? = null) {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return log("No Bluetooth adapter")
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrElse { return log("Bad MAC address") }
        closeLink()
        val gen = generation.incrementAndGet()
        _ui.update { it.copy(connecting = true, state = "Connecting RFCOMM…") }
        Thread({
            lateinit var newSession: WearSession
            val newLink = SppLink(device,
                onBytes = { newSession.receive(it) },
                onClosed = { e ->
                    log("Socket closed${e?.let { ": ${it.message}" } ?: ""}")
                    binder?.cancel()
                    if (generation.get() == gen) {
                        _ui.update { it.copy(ready = false, connecting = false, state = "Disconnected") }
                        if (token != null) scheduleReconnect("socket closed")
                    }
                })
            newSession = WearSession(token, Build.MODEL ?: "Android", Build.VERSION.SDK_INT,
                write = { runCatching { newLink.write(it) }.onFailure { e -> log("Write failed: ${e.message}") } },
                listener = object : WearSession.Listener {
                    override fun onState(state: WearSession.State, detail: String) {
                        if (generation.get() != gen) return
                        _ui.update {
                            it.copy(state = "$state: $detail", ready = state == WearSession.State.Ready,
                                connecting = state == WearSession.State.Negotiating || state == WearSession.State.Authenticating)
                        }
                        if (state == WearSession.State.Ready) reconnectAttempt = 0
                        if (state == WearSession.State.Unauthenticated && onUnauth != null) Thread({ onUnauth(newSession) }, "bind").start()
                        if (state == WearSession.State.Failed) {
                            binder?.cancel()
                            // Handshake timeout or transport error: drop the socket so the next attempt starts clean.
                            if (token != null) scope.launch {
                                if (generation.get() == gen) { closeLink(); scheduleReconnect("session failed: $detail") }
                            }
                        }
                    }
                    override fun onLog(line: String) = log(line)
                    override fun onPacket(packet: ByteArray, response: Boolean) = handlePacket(packet)
                    override fun onMass(data: ByteArray) = handleMass(data)
                    override fun onVoice(data: ByteArray) = handleVoice(data)
                    override fun onPlain(packet: ByteArray) { onPlain?.invoke(packet) }
                })
            try {
                log("RFCOMM connecting to $address (bond state ${device.bondState}, name ${device.name})")
                newLink.open()
                if (generation.get() != gen) { newLink.close(); newSession.close(); return@Thread }
                link = newLink; session = newSession
                log("RFCOMM connected to $address")
                newSession.start()
            } catch (e: Exception) {
                newLink.close(); newSession.close()
                log("Connect failed: ${e.message}")
                binder?.cancel()
                if (generation.get() == gen) {
                    _ui.update { it.copy(connecting = false, state = "Connect failed") }
                    if (token != null) scheduleReconnect("connect failed")
                }
            }
        }, "spp-connect").start()
    }

    private fun bindUpdate(f: (BindState) -> BindState) = _ui.update { it.copy(bind = f(it.bind)) }

    /**
     * Fresh bind (UNTESTED): glasses must be unbound (reset). Needs cloud sign-in and the glasses MAC.
     * On success fetches the new encrypt_key from the cloud and connects normally.
     */
    @SuppressLint("HardwareIds")
    fun bind() {
        val auth = cloudAuth ?: return bindUpdate { it.copy(status = "Sign in to Xiaomi cloud first") }
        val address = _ui.value.address
        if (address.isBlank()) return bindUpdate { it.copy(status = "Select the glasses MAC first") }
        if (_ui.value.bind.running) return
        val app = app
        val btName = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)?.name }.getOrNull()
        val phoneId = android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()
        val loc = Locale.getDefault()
        val locale = if (loc.country.isEmpty()) loc.language.lowercase() else "${loc.language.lowercase()}_${loc.country.lowercase()}"
        disconnect() // no auto-reconnect during bind
        bindUpdate { BindState(running = true, status = "Connecting in bind mode…") }
        log("Bind: start for $address")
        open(address, null,
            onUnauth = { s ->
                val b = wtf.mahdi.glassesconnect.cloud.GlassesBinder(cloud, auth, s, address, btName ?: "Xiaomi AI Glasses",
                    phoneId, locale, Build.MODEL ?: "Android", Build.VERSION.SDK_INT, log = { line -> log(line); bindUpdate { it.copy(status = line.removePrefix("Bind: ")) } },
                    onOob = { code -> bindUpdate { it.copy(oob = code) } })
                binder = b
                try {
                    val result = b.run()
                    bindUpdate { it.copy(status = "Bound (did ${result.did}). Fetching key…") }
                    closeLink()
                    val devices = cloud.devices(auth)
                    log("Cloud: ${devices.size} device(s) after bind: " + devices.joinToString { "${it.model} ${it.mac} did=${it.did}" })
                    cloudUpdate { it.copy(devices = devices) }
                    val device = devices.firstOrNull { it.did == result.did } ?: devices.firstOrNull { it.mac.equals(address, true) }
                    if (device == null || device.encryptKey.isEmpty()) {
                        bindUpdate { it.copy(running = false, status = "Bound, but no key in device list yet. Fetch key again later.") }
                    } else {
                        useCloudDevice(device)
                        bindUpdate { it.copy(running = false, oob = null, status = "Bound. Connecting with the new key…") }
                        connect()
                    }
                } catch (e: Exception) {
                    log("Bind failed: ${e.message}")
                    bindUpdate { it.copy(running = false, status = "Failed: ${e.message}") }
                    Thread.sleep(500) // let a queued error report reach the glasses
                    disconnect()
                } finally { binder = null }
            },
            onPlain = { packet -> binder?.onPlain(packet) ?: log("Plain packet before binder ready: ${Proto.hex(packet)}") })
        // The bind thread may never start (RFCOMM or L1 failure); clear running so the button comes back.
        scope.launch {
            kotlinx.coroutines.delay(35_000)
            if (binder == null && _ui.value.bind.running && _ui.value.state.let { !it.startsWith("Unauthenticated") }) {
                bindUpdate { it.copy(running = false, status = "Failed: no bind-mode session (${_ui.value.state})") }
            }
        }
    }

    fun cancelBind() { binder?.cancel(); disconnect(); bindUpdate { it.copy(running = false, status = "Cancelled") } }

    /** User disconnect: stops auto-reconnect and the foreground service. */
    fun disconnect() {
        setKeepConnected(false)
        reconnectJob?.cancel()
        closeLink()
        GlassesService.stop(app)
    }

    /** Closes the current link without changing [keepConnected]. */
    private fun closeLink() {
        generation.incrementAndGet()
        synchronized(thumbQueue) { thumbQueue.clear(); thumbCurrent = null }
        _ui.update { it.copy(downloading = null, thumbQueue = 0) }
        session?.close(); session = null
        link?.close(); link = null
        _ui.update { it.copy(ready = false, connecting = false, state = "Disconnected") }
    }

    fun send(command: GlassesCommands.Command) {
        val s = session ?: return log("Not connected")
        if (command.id == 33 && GlassesCommands.misDid.isEmpty()) log("Warning: MIS did unknown; press the glasses button once so a MIS report arrives")
        s.send(command.payload())
    }

    fun readSettings() = session?.send(GlassesSettings.request()) ?: log("Not connected")

    fun editSetting(group: GlassesSettings.Group, item: GlassesSettings.Item, value: Long) = _ui.update { s ->
        val current = s.settings ?: return@update s
        s.copy(settings = GlassesSettings.set(current, group, item, value), settingsDirty = s.settingsDirty + group.field)
    }

    /** Sends one edited group, then reads all settings again to show what the glasses accepted. */
    fun applySettings(group: GlassesSettings.Group) {
        val s = session ?: return log("Not connected")
        val setting = _ui.value.settings ?: return
        log("Writing ${group.title} settings")
        s.send(GlassesSettings.write(group, setting))
        scope.launch { kotlinx.coroutines.delay(800); readSettings() }
    }

    fun editStream(f: (StreamState) -> StreamState) {
        _ui.update { it.copy(stream = f(it.stream)) }
        val st = _ui.value.stream
        prefs.edit().putString("stream_ssid", st.ssid).putString("stream_password", st.password).putString("stream_url", st.url)
            .putInt("stream_resolution", st.resolution).putInt("stream_fps", st.fps).putInt("stream_mode", st.mode).apply()
    }

    private fun streamStatus(text: String) { log("Stream: $text"); _ui.update { it.copy(stream = it.stream.copy(status = text)) } }

    /** Saves the network on the glasses; they join it and report a Wi-Fi result (id 98). */
    fun streamWifi() {
        val s = session ?: return log("Not connected")
        val st = _ui.value.stream
        if (st.ssid.isBlank()) return streamStatus("Enter the Wi-Fi name")
        streamStatus("Sending Wi-Fi ${st.ssid}…")
        s.send(GlassesCommands.addWifi(st.ssid.trim(), st.password))
    }

    fun streamWifiList() = session?.send(GlassesCommands.wifiList()) ?: log("Not connected")

    fun streamQuery() = session?.send(GlassesCommands.liveQuery()) ?: log("Not connected")

    fun streamStart() {
        val s = session ?: return log("Not connected")
        val st = _ui.value.stream
        if (!st.url.trim().let { it.startsWith("rtmp://") || it.startsWith("rtmps://") }) return streamStatus("URL must start with rtmp:// or rtmps://")
        streamStatus("Starting ${st.url.trim()} (res ${st.resolution}, ${st.fps} fps, mode ${st.mode})…")
        s.send(GlassesCommands.liveStart(st.url.trim(), st.resolution, st.fps, st.mode))
    }

    private val preview = wtf.mahdi.glassesconnect.stream.LivePreview(::log) { st -> _ui.update { it.copy(stream = it.stream.copy(preview = st)) } }

    /** Starts the phone RTMP receiver (port 1935) and lists phone IPs for the URL. */
    fun previewStart() { preview.start(); previewRefreshIps() }
    fun previewStop() = preview.stop()
    fun previewSurface(surface: android.view.Surface?) = preview.setSurface(surface)
    fun previewRefreshIps() = _ui.update { it.copy(stream = it.stream.copy(phoneIps = wtf.mahdi.glassesconnect.stream.LivePreview.addresses())) }
    fun usePhoneUrl(ip: String) = editStream { it.copy(url = "rtmp://$ip:${wtf.mahdi.glassesconnect.stream.LivePreview.PORT}/live/xiaolink") }

    fun streamStop() { val s = session ?: return log("Not connected"); streamStatus("Stopping…"); s.send(GlassesCommands.liveStop()) }

    private fun handlePacket(packet: ByteArray) {
        GlassesSettings.parse(packet)?.let { setting -> _ui.update { it.copy(settings = setting, settingsDirty = emptySet()) } }
        GlassesCommands.batteryCapacity(packet)?.let { b -> _ui.update { it.copy(battery = b) } }
        _ui.update { it.copy(lastResponse = GlassesCommands.dump(packet)) }
        val event = GlassesEvents.parse(packet)
            ?: return GlassesCommands.batteryCapacity(packet)?.let { log("Battery $it%") } ?: log("Undecoded:\n" + GlassesCommands.dump(packet).trimEnd())
        log("Event: ${event.summary}")
        val files = when (event) {
            is GlassesEvents.MediaReport -> event.files
            is GlassesEvents.RecordStatus -> event.files
            else -> emptyList()
        }
        if (event is GlassesEvents.AlertStatus) _ui.update { s ->
            val camera = event.cameraStatus ?: s.cameraStatus
            val since = if (camera == 5L) s.recordingSince ?: System.currentTimeMillis() else null
            s.copy(battery = event.battery ?: s.battery, cameraStatus = camera, recordingSince = since)
        }
        if (files.isNotEmpty()) setPending((files + _ui.value.pending).distinctBy { it.id }.take(200))
        val did = (event as? GlassesEvents.MisProperties)?.did ?: (event as? GlassesEvents.MisActionResponse)?.did
        if (!did.isNullOrEmpty() && did != GlassesCommands.misDid) {
            GlassesCommands.misDid = did; prefs.edit().putString("mis_did", did).apply(); log("MIS did = $did")
        }
        if (event is GlassesEvents.P2pResult) p2pResult?.complete(event)
        when (event) {
            is GlassesEvents.WifiResult -> _ui.update { it.copy(stream = it.stream.copy(wifi = event.summary.removePrefix("Wi-Fi result "))) }
            is GlassesEvents.LiveModes -> _ui.update { it.copy(stream = it.stream.copy(modes = "supported ${event.supported}, current ${event.current}")) }
            is GlassesEvents.LiveStatus -> _ui.update { it.copy(stream = it.stream.copy(status = event.summary)) }
            else -> Unit
        }
        if (event is GlassesEvents.AivsWakeUp || event is GlassesEvents.AivsInstructions) closeVoiceCapture()
        if (event is GlassesEvents.AivsWakeUp) ai.onWake(event.cmd)
        if (event is GlassesEvents.AivsInstructions && 3 in event.resultTypes) ai.onStopCapture()
        if (event is GlassesEvents.MisProperties) event.battery?.let { b -> _ui.update { it.copy(battery = b) } }
        _ui.update { s ->
            s.copy(events = (s.events + "${time.format(Date())} ${event.summary}").takeLast(50),
                media = (files + s.media).distinctBy { it.id }.take(200))
        }
        if (event is GlassesEvents.MediaReport && autoThumbnails) downloadThumbnails(files.map { it.id })
        else if (thumbCurrent != null) nextThumbnail()
    }

    private val thumbDir get() = java.io.File(app.getExternalFilesDir(null), "thumbnails").apply { mkdirs() }
    private val mass = MassFileReceiver()
    private val thumbQueue = ArrayDeque<String>()
    private var thumbCurrent: String? = null
    private var thumbStarted = 0L

    private fun thumbFile(id: String) = java.io.File(thumbDir, id.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".jpg")) it else "$it.jpg" })

    /** Queues thumbnail downloads for files without a saved thumbnail; one request at a time (like the official app). */
    fun downloadThumbnails(ids: List<String> = _ui.value.media.map { it.id }) = synchronized(thumbQueue) {
        ids.filter { !thumbFile(it).exists() && it != thumbCurrent && it !in thumbQueue }.forEach(thumbQueue::addLast)
        _ui.update { it.copy(thumbQueue = thumbQueue.size) }
        nextThumbnail()
    }

    private fun nextThumbnail(): Unit = synchronized(thumbQueue) {
        // Give up on a request with no data after 20 s.
        if (thumbCurrent != null && System.currentTimeMillis() - thumbStarted < 20_000) return
        thumbCurrent?.let { log("Thumbnail timeout: $it") }
        val s = session
        val id = thumbQueue.removeFirstOrNull()
        thumbCurrent = id
        _ui.update { it.copy(downloading = id, thumbQueue = thumbQueue.size) }
        if (id == null || s == null) { thumbCurrent = null; return }
        thumbStarted = System.currentTimeMillis()
        log("Requesting thumbnail $id")
        s.send(GlassesCommands.requestThumbnails(listOf(id)))
    }

    private fun handleMass(data: ByteArray) {
        if (data.size > 1 && (data[1].toInt() and 255) != MassFileReceiver.THUMBNAIL) return log("Mass cmd ${data[1].toInt() and 255} (${data.size} B) ignored")
        thumbStarted = System.currentTimeMillis()
        val file = mass.feed(data)
        if (file == null) {
            val (got, total) = mass.progress
            if (got == 1 || got % 20 == 0) log("Mass slice $got/$total")
            return
        }
        log("Thumbnail ${file.id}: ${file.content.size} B, flag=${file.flag}, crc=${if (file.crcOk) "ok" else "BAD"}")
        val target = thumbFile(file.id)
        runCatching { target.writeBytes(file.content) }
            .onSuccess { _ui.update { it.copy(thumbs = it.thumbs + (file.id to target.absolutePath)) } }
            .onFailure { log("Thumbnail save failed: ${it.message}") }
        session?.send(GlassesCommands.confirmThumbnail(file.id))
        synchronized(thumbQueue) { thumbCurrent = null }
        nextThumbnail()
    }

    private val voiceDir get() = java.io.File(app.getExternalFilesDir(null), "voice").apply { mkdirs() }
    private var voiceRaw: java.io.FileOutputStream? = null
    private var voicePayload: java.io.FileOutputStream? = null
    private var voicePackets = 0
    private var voiceBytes = 0L

    /**
     * Voice channel (L2 ch3) per VoiceMassData: b1 cmd (1 START, 2 DATA, 3 END, 4 QUIT, 65..69 recordings);
     * DATA body from offset 4: u16 massType (1 first voice, 2 follow voice, 3/4 image, 6/7 wake-up voice), u32, u16, payload.
     * Debug only: logs headers and saves files/voice/<time>.raw (length-prefixed packets) and .sbc (payloads).
     */
    private fun handleVoice(data: ByteArray) {
        ai.onVoice(data)
        fun u16(o: Int) = ((data[o].toInt() and 255) shl 8) or (data[o + 1].toInt() and 255)
        val cmd = if (data.size > 1) data[1].toInt() and 255 else -1
        val type = if (cmd == 2 && data.size >= 6) u16(4) else -1
        val payload = if (cmd == 2 && data.size > 12) data.copyOfRange(12, data.size) else null
        if (voiceRaw == null) {
            val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            voiceRaw = runCatching { java.io.FileOutputStream(java.io.File(voiceDir, "$name.raw")) }.getOrNull()
            voicePayload = runCatching { java.io.FileOutputStream(java.io.File(voiceDir, "$name.sbc")) }.getOrNull()
            voicePackets = 0; voiceBytes = 0
            log("Voice capture started: voice/$name.*")
        }
        runCatching {
            voiceRaw?.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array()); voiceRaw?.write(data)
            payload?.let { voicePayload?.write(it) }
        }
        voicePackets++; voiceBytes += data.size
        if (type != -1 && type != 1 && type != 2) handleVoiceImage(type, data)
        if (voicePackets <= 5 || cmd != 2 || voicePackets % 50 == 0) {
            val head = Proto.hex(data.copyOfRange(0, minOf(data.size, 24)))
            log("Voice #$voicePackets cmd=$cmd type=$type size=${data.size} head=$head")
        }
        if (cmd == 3 || cmd == 4) closeVoiceCapture()
    }

    private val aiImageDir get() = java.io.File(app.getExternalFilesDir(null), "ai-images").apply { mkdirs() }
    private var aiImage: java.io.ByteArrayOutputStream? = null
    private var aiImageType = 0
    private var aiImageTotal = 0

    /**
     * Device-verified: AI images on the voice channel (MiWearVoiceHandler$onReceivedVoiceData$1$result$1.java:165-247,
     * VoiceMassData.j). Types: 3 FIRST_IMAGE (cropped), 4 FOLLOW_IMAGE, 5 IMAGE_OCR, 8/9 small/large picture
     * of conversation record, 11 IMAGE_HD_FOLLOW. Header after type: u32 BE totalLength, u16 BE contentLength.
     * First types (3/5/8/9/10) start a buffer, follow types append; saved to files/ai-images/.
     * A complete type 3 JPEG goes to [AiAssistant.onImage] (take_photo tool).
     */
    private fun handleVoiceImage(type: Int, data: ByteArray) {
        val total = if (data.size >= 12) java.nio.ByteBuffer.wrap(data, 6, 4).int else -1
        val content = if (data.size >= 12) ((data[10].toInt() and 255) shl 8) or (data[11].toInt() and 255) else -1
        val payload = if (data.size > 12) data.copyOfRange(12, data.size) else ByteArray(0)
        if (type != 4 && type != 11) {
            log("AI image type=$type total=$total content=$content")
            saveAiImage()
            aiImage = java.io.ByteArrayOutputStream(); aiImageType = type; aiImageTotal = total
        }
        aiImage?.write(payload)
        if (aiImageTotal > 0 && (aiImage?.size() ?: 0) >= aiImageTotal) saveAiImage()
    }

    private fun saveAiImage() {
        val bytes = aiImage?.toByteArray() ?: return
        aiImage = null
        val ext = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            else -> "bin"
        }
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-t$aiImageType.$ext"
        val file = java.io.File(aiImageDir, name)
        runCatching { file.writeBytes(bytes) }
        log("AI image saved: ai-images/$name (${bytes.size} of $aiImageTotal B)")
        if (aiImageType == 3 && ext == "jpg" && bytes.size == aiImageTotal) ai.onImage(bytes, file)
    }

    private fun closeVoiceCapture() {
        saveAiImage()
        if (voiceRaw == null) return
        log("Voice capture ended: $voicePackets packets, $voiceBytes B")
        runCatching { voiceRaw?.close(); voicePayload?.close() }
        voiceRaw = null; voicePayload = null
    }

    private val gallery by lazy { GallerySaver(app) }
    @Volatile private var p2pResult: kotlinx.coroutines.CompletableDeferred<GlassesEvents.P2pResult>? = null

    private fun syncStatus(text: String) { log("Sync: $text"); _ui.update { it.copy(syncStatus = text) } }

    /**
     * Full-size sync: Wi-Fi Direct group -> EnableWiFiP2PGC -> HTTP list -> download new plain files -> gallery.
     * Never deletes from the glasses (the official app sends DELETE /v1/files/?id=...; we skip it).
     */
    fun syncGallery() {
        if (_ui.value.syncing) return
        val s = session ?: return log("Not connected")
        if (Build.VERSION.SDK_INT < 29) return syncStatus("Needs Android 10+")
        _ui.update { it.copy(syncing = true) }
        val pendingAtStart = _ui.value.pending.map { it.id }.toSet()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sync = runCatching { MediaSync(app) }.getOrElse { syncStatus("Wi-Fi Direct unavailable: ${it.message}"); _ui.update { u -> u.copy(syncing = false) }; return@launch }
            try {
                syncStatus("Creating Wi-Fi Direct group…")
                val group = sync.createGroup()
                log("P2P group ${group.ssid} @ ${group.frequency} MHz")
                val deferred = kotlinx.coroutines.CompletableDeferred<GlassesEvents.P2pResult>().also { p2pResult = it }
                syncStatus("Asking glasses to join…")
                s.send(GlassesCommands.enableP2p(group.ssid, group.password, group.frequency))
                val result = kotlinx.coroutines.withTimeoutOrNull(45_000) { deferred.await() } ?: error("Glasses did not join (timeout)")
                if (result.code != 0L || result.ip.isEmpty()) error("Glasses refused: ${GlassesEvents.P2P_CODES[result.code] ?: "code ${result.code}"}")
                syncStatus("Listing files on ${result.ip}…")
                val files = MediaSync.listFiles(result.ip)
                files.forEach { log("Remote: ${it.name} ${it.mimeType} ${it.size} B id=${it.identifier}") }
                // Resolve each entry to (file to download, gallery name); skip names already in the gallery.
                val todo = mutableListOf<Pair<MediaSync.RemoteFile, MediaSync.RemoteFile>>() // (entry, download)
                val names = mutableMapOf<MediaSync.RemoteFile, String>()
                var present = 0; var skipped = 0
                for (f in files) {
                    val pick = if (f.plain) f to f.name
                        else runCatching { MediaSync.pickFromFolder(f, MediaSync.listFolder(result.ip, f)) }
                            .onFailure { log("Folder list failed ${f.name}: ${it.message}") }.getOrNull()
                    when {
                        pick == null -> skipped++
                        gallery.exists(pick.second) -> present++
                        else -> { todo += f to pick.first; names[pick.first] = pick.second }
                    }
                }
                var saved = 0
                todo.forEachIndexed { i, (entry, f) ->
                    val name = names.getValue(f)
                    syncStatus("Downloading ${i + 1}/${todo.size}: $name")
                    val tmp = java.io.File(app.cacheDir, "dl_$name")
                    var last = 0L
                    try {
                        MediaSync.download(result.ip, f, tmp) { n ->
                            if (n - last > 2_000_000) { last = n; _ui.update { it.copy(syncStatus = "Downloading ${i + 1}/${todo.size}: $name ${n / 1_000_000}/${f.size / 1_000_000} MB") } }
                        }
                        val added = entry.fileAdded
                        val taken = if (added > 1_000_000_000_000L) added else added.takeIf { it > 0 }?.times(1000)
                        tmp.inputStream().use { gallery.save(name, taken, it) }
                        saved++
                        log("Saved $name (${tmp.length()} B)")
                    } catch (e: Exception) { log("Download failed $name: ${e.message}") } finally { tmp.delete() }
                }
                syncStatus("Done: $saved saved, $present already in gallery" +
                    if (skipped > 0) ", $skipped unsupported skipped" else "")
                setPending(_ui.value.pending.filter { it.id !in pendingAtStart })
            } catch (e: Exception) {
                syncStatus("Failed: ${e.message}")
            } finally {
                p2pResult = null
                sync.removeGroup(); sync.close()
                _ui.update { it.copy(syncing = false) }
            }
        }
    }

    private fun setPending(files: List<GlassesEvents.MediaFile>) {
        _ui.update { it.copy(pending = files) }
        prefs.edit().putStringSet("pending_media", files.map { f ->
            listOf(f.id, f.type, f.size, f.timestamp, f.duration ?: "").joinToString("|")
        }.toSet()).apply()
    }

    /** One-shot photo question (Gemini vision model), for photos already in the gallery. Blocking: call off the main thread. */
    fun askAboutPhoto(jpeg: ByteArray, question: String): String {
        val s = _ui.value.aiSettings
        if (s.key.isBlank()) error("Set the Gemini API key first")
        return wtf.mahdi.glassesconnect.ai.GeminiVision().ask(s.key, s.vision, jpeg, question)
    }

    /** Copies an AI photo (files/ai-images) into the phone gallery. Blocking: call off the main thread. */
    fun saveAiPhoto(path: String) {
        val file = java.io.File(path)
        file.inputStream().use { gallery.save("AI_${file.name}", file.lastModified(), it) }
    }

    companion object {
        private fun loadPending(prefs: android.content.SharedPreferences) = prefs.getStringSet("pending_media", emptySet()).orEmpty()
            .mapNotNull { line ->
                val p = line.split("|")
                runCatching { GlassesEvents.MediaFile(p[0], p[1].toInt(), p[2].toLong(), p[3].toLong(), p[4].toLongOrNull()) }.getOrNull()
            }.sortedByDescending { it.timestamp }

        @Volatile private var instance: GlassesController? = null
        fun get(context: Context): GlassesController = instance ?: synchronized(this) {
            instance ?: GlassesController(context.applicationContext as Application).also { instance = it }
        }
    }
}
