package wtf.mahdi.glassesconnect

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val state: String = "Disconnected",
    val ready: Boolean = false,
    val connecting: Boolean = false,
    val battery: Int? = null,
    val lastResponse: String = "",
    val media: List<GlassesEvents.MediaFile> = emptyList(),
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
)

/** Xiaomi cloud key fetch. [needsCode] is non-null while 2FA waits for a code. */
data class CloudState(
    val user: String = "",
    val busy: Boolean = false,
    val status: String = "",
    val needsCode: XiaomiCloud.LoginResult.NeedsCode? = null,
    val codeSent: Boolean = false,
    val devices: List<XiaomiCloud.Device> = emptyList(),
)

@SuppressLint("MissingPermission")
class GlassesViewModel(app: Application) : AndroidViewModel(app) {
    private val logFile = app.getExternalFilesDir(null)?.let { java.io.File(it, "log.txt") }
    private val prefs = app.getSharedPreferences("glasses", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UiState(
        address = prefs.getString("address", "").orEmpty(),
        token = prefs.getString("token", "").orEmpty(),
        cloud = CloudState(user = prefs.getString("cloud_user", "").orEmpty()),
    ))
    private val cloud = XiaomiCloud(prefs)
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    var autoThumbnails = true
    init {
        GlassesCommands.misDid = prefs.getString("mis_did", "").orEmpty()
        _ui.update { s -> s.copy(thumbs = thumbDir.listFiles().orEmpty().associate { it.name to it.absolutePath }) }
    }
    private var link: SppLink? = null
    private var session: WearSession? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ai = AiAssistant(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL,
        sendToGlasses = { packet -> session?.send(packet) },
        log = ::log,
        onState = { st -> _ui.update { it.copy(aiState = st) } })

    /** Asks Gemini from the phone: opens the glasses microphone without the gesture. */
    fun askAi() = ai.askFromPhone()

    fun log(line: String) {
        android.util.Log.i("GlassesConnect", line)
        // Debug aid: adb pull /sdcard/Android/data/wtf.mahdi.glassesconnect/files/log.txt
        runCatching { logFile?.appendText("${time.format(Date())} $line\n") }
        _ui.update { it.copy(log = (it.log + "${time.format(Date())} $line").takeLast(300)) }
    }

    fun clearLog() { _ui.update { it.copy(log = emptyList()) }; runCatching { logFile?.writeText("") } }

    fun refreshPaired() {
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        val devices = runCatching { adapter?.bondedDevices.orEmpty().map { PairedDevice(it.address, it.name ?: it.address) } }
            .getOrElse { log("Cannot list paired devices: ${it.message}"); emptyList() }
        _ui.update { it.copy(paired = devices.sortedBy { d -> d.name }) }
    }

    fun setAddress(value: String) { _ui.update { it.copy(address = value.trim().uppercase()) }; prefs.edit().putString("address", _ui.value.address).apply() }
    fun setToken(value: String) { _ui.update { it.copy(token = value.trim()) }; prefs.edit().putString("token", _ui.value.token).apply() }

    private fun cloudUpdate(f: (CloudState) -> CloudState) = _ui.update { it.copy(cloud = f(it.cloud)) }

    /** Runs a blocking cloud call on IO; errors go to the cloud status line and the log. */
    private fun cloudCall(label: String, block: () -> Unit) {
        cloudUpdate { it.copy(busy = true, status = label) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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

    fun cloudLogout() { cloud.logout(); cloudUpdate { CloudState(user = it.user, status = "Signed out") } }

    private fun cloudDevices(auth: XiaomiCloud.Auth) {
        cloudUpdate { it.copy(status = "Signed in as ${auth.userId}. Loading devices…") }
        val devices = cloud.devices(auth)
        log("Cloud: ${devices.size} device(s): " + devices.joinToString { "${it.model} ${it.mac}" })
        cloudUpdate { it.copy(devices = devices, status = "Signed in as ${auth.userId}. Tap a device to use its key.") }
    }

    /** Uses a cloud device's key and MAC for the next connect. */
    fun useCloudDevice(device: XiaomiCloud.Device) {
        setToken(device.encryptKey)
        if (device.mac.isNotBlank()) setAddress(device.mac)
        log("Using encrypt_key of ${device.name.ifEmpty { device.model }} (${device.mac})")
    }

    fun connect() {
        val s = _ui.value
        val token = s.token.replace(" ", "")
        if (!Regex("[0-9a-fA-F]+").matches(token) || token.length % 2 != 0) return log("Token must be the hex encrypt_key")
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
            ?: return log("No Bluetooth adapter")
        val device = runCatching { adapter.getRemoteDevice(s.address) }.getOrElse { return log("Bad MAC address") }
        disconnect()
        _ui.update { it.copy(connecting = true, state = "Connecting RFCOMM…") }
        val tokenBytes = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        Thread({
            lateinit var newSession: WearSession
            val newLink = SppLink(device,
                onBytes = { newSession.receive(it) },
                onClosed = { e ->
                    log("Socket closed${e?.let { ": ${it.message}" } ?: ""}")
                    _ui.update { it.copy(ready = false, connecting = false, state = "Disconnected") }
                })
            newSession = WearSession(tokenBytes, Build.MODEL ?: "Android", Build.VERSION.SDK_INT,
                write = { runCatching { newLink.write(it) }.onFailure { e -> log("Write failed: ${e.message}") } },
                listener = object : WearSession.Listener {
                    override fun onState(state: WearSession.State, detail: String) = _ui.update {
                        it.copy(state = "$state: $detail", ready = state == WearSession.State.Ready,
                            connecting = state == WearSession.State.Negotiating || state == WearSession.State.Authenticating)
                    }
                    override fun onLog(line: String) = log(line)
                    override fun onPacket(packet: ByteArray, response: Boolean) = handlePacket(packet)
                    override fun onMass(data: ByteArray) = handleMass(data)
                    override fun onVoice(data: ByteArray) = handleVoice(data)
                })
            try {
                newLink.open()
                link = newLink; session = newSession
                log("RFCOMM connected to ${s.address}")
                newSession.start()
            } catch (e: Exception) {
                newLink.close(); newSession.close()
                log("Connect failed: ${e.message}")
                _ui.update { it.copy(connecting = false, state = "Connect failed") }
            }
        }, "spp-connect").start()
    }

    fun disconnect() {
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
        viewModelScope.launch { kotlinx.coroutines.delay(800); readSettings() }
    }

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
            s.copy(battery = event.battery ?: s.battery, cameraStatus = event.cameraStatus ?: s.cameraStatus)
        }
        val did = (event as? GlassesEvents.MisProperties)?.did ?: (event as? GlassesEvents.MisActionResponse)?.did
        if (!did.isNullOrEmpty() && did != GlassesCommands.misDid) {
            GlassesCommands.misDid = did; prefs.edit().putString("mis_did", did).apply(); log("MIS did = $did")
        }
        if (event is GlassesEvents.P2pResult) p2pResult?.complete(event)
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

    private val thumbDir get() = java.io.File(getApplication<Application>().getExternalFilesDir(null), "thumbnails").apply { mkdirs() }
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

    private val voiceDir get() = java.io.File(getApplication<Application>().getExternalFilesDir(null), "voice").apply { mkdirs() }
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
        if (voicePackets <= 5 || cmd != 2 || voicePackets % 50 == 0) {
            val head = Proto.hex(data.copyOfRange(0, minOf(data.size, 24)))
            log("Voice #$voicePackets cmd=$cmd type=$type size=${data.size} head=$head")
        }
        if (cmd == 3 || cmd == 4) closeVoiceCapture()
    }

    private fun closeVoiceCapture() {
        if (voiceRaw == null) return
        log("Voice capture ended: $voicePackets packets, $voiceBytes B")
        runCatching { voiceRaw?.close(); voicePayload?.close() }
        voiceRaw = null; voicePayload = null
    }

    private val gallery by lazy { GallerySaver(getApplication()) }
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sync = runCatching { MediaSync(getApplication()) }.getOrElse { syncStatus("Wi-Fi Direct unavailable: ${it.message}"); _ui.update { u -> u.copy(syncing = false) }; return@launch }
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
                    val tmp = java.io.File(getApplication<Application>().cacheDir, "dl_$name")
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
            } catch (e: Exception) {
                syncStatus("Failed: ${e.message}")
            } finally {
                p2pResult = null
                sync.removeGroup(); sync.close()
                _ui.update { it.copy(syncing = false) }
            }
        }
    }

    override fun onCleared() { disconnect(); ai.close() }
}
