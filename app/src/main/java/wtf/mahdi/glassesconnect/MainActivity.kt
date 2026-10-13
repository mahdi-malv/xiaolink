package wtf.mahdi.glassesconnect

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import wtf.mahdi.glassesconnect.protocol.GlassesEvents
import wtf.mahdi.glassesconnect.protocol.GlassesSettings
import wtf.mahdi.glassesconnect.protocol.Proto
import wtf.mahdi.glassesconnect.ui.theme.GlassesConnectTheme

class MainActivity : ComponentActivity() {
    private val vm by lazy { GlassesController.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GlassesConnectTheme { App(vm) } }
    }
}

private val permissions = when {
    // Wi-Fi Direct (gallery sync) needs NEARBY_WIFI_DEVICES on 33+, location below.
    // POST_NOTIFICATIONS: foreground service notification (background session).
    android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.NEARBY_WIFI_DEVICES,
        android.Manifest.permission.POST_NOTIFICATIONS)
    android.os.Build.VERSION.SDK_INT >= 31 -> arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.ACCESS_FINE_LOCATION)
    else -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
private fun App(vm: GlassesController) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPaired() }
    LaunchedEffect(Unit) { launcher.launch(permissions) }
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBar(ui)
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Connect", "Commands", "Media", "Settings", "Log", "TODO").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> ConnectTab(ui, vm) { launcher.launch(permissions) }
                1 -> CommandsTab(ui, vm)
                2 -> MediaTab(ui, vm)
                3 -> SettingsTab(ui, vm)
                4 -> LogTab(ui.log, vm::clearLog)
                else -> TodoTab()
            }
        }
    }
}

@Composable
private fun StatusBar(ui: UiState) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(ui.state, style = MaterialTheme.typography.bodyMedium)
            ui.battery?.let { Text("Battery: $it%", style = MaterialTheme.typography.titleMedium) }
            ui.cameraStatus?.let {
                val busy = it != 0L
                Text("Camera: ${GlassesEvents.cameraLabel(it)} [$it]", style = MaterialTheme.typography.bodyMedium,
                    color = if (busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ConnectTab(ui: UiState, vm: GlassesController, requestPermissions: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("1. Pair the glasses in Android Bluetooth settings.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("BT settings") }
            OutlinedButton(onClick = { requestPermissions(); vm.refreshPaired() }) { Text("Refresh") }
        }
        ui.paired.forEach { d ->
            ListItem(
                headlineContent = { Text(d.name) },
                supportingContent = { Text(d.address) },
                trailingContent = { RadioButton(selected = d.address == ui.address, onClick = { vm.setAddress(d.address) }) },
                modifier = Modifier.clickable { vm.setAddress(d.address) },
            )
        }
        OutlinedTextField(ui.address, vm::setAddress, label = { Text("Glasses MAC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("2. Fetch encrypt_key from Xiaomi cloud, or paste it (hex) from tools/fetch_key.py.", style = MaterialTheme.typography.bodyMedium)
        CloudKeySection(ui.cloud, vm)
        OutlinedTextField(ui.token, vm::setToken, label = { Text("encrypt_key (hex)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect, enabled = !ui.connecting && ui.address.isNotBlank() && ui.token.isNotBlank()) { Text("Connect") }
            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
        }
        if (ui.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        BackgroundSection(ui, vm)
        BindSection(ui, vm)
        GeminiSection(ui, vm)
    }
}

@Composable
private fun GeminiSection(ui: UiState, vm: GlassesController) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gemini (AI assistant)", style = MaterialTheme.typography.titleSmall)
            Text("API key from aistudio.google.com. Stored on this phone only; sent only to Google.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ui.geminiKey, vm::setGeminiKey, label = { Text("Gemini API key") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Keeps the session alive in the background: CDM association and battery exemption. */
@Composable
private fun BackgroundSection(ui: UiState, vm: GlassesController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read on resume (the user returns from a system dialog or settings screen).
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val associated = remember(tick, ui.address) { CompanionLink.isAssociated(context, ui.address) }
    val batteryOk = remember(tick) { CompanionLink.ignoresBatteryOptimizations(context) }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Background", style = MaterialTheme.typography.titleSmall)
            Text("Connect keeps the session up with a notification and reconnects when the glasses return. " +
                "Disconnect stops it.", style = MaterialTheme.typography.bodySmall)
            Text("Companion device: " + when {
                !CompanionLink.supported -> "needs Android 13"
                associated -> "associated"
                else -> "not associated"
            }, style = MaterialTheme.typography.bodySmall)
            Text("Battery optimization: ${if (batteryOk) "off (unrestricted)" else "on"}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (CompanionLink.supported && !associated) OutlinedButton(
                    onClick = {
                        CompanionLink.associate(context, ui.address, log = vm::log,
                            onPending = { dialog.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) })
                    },
                    enabled = ui.address.isNotBlank(),
                ) { Text("Associate") }
                if (!batteryOk) OutlinedButton(onClick = {
                    @android.annotation.SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }) { Text("Unrestrict battery") }
            }
        }
    }
}

@Composable
private fun BindSection(ui: UiState, vm: GlassesController) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fresh bind (untested)", style = MaterialTheme.typography.titleSmall)
            Text("Binds unbound (reset) glasses to the signed-in Xiaomi account, then fetches the new key and connects. " +
                "Needs: cloud sign-in above and the glasses MAC.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::bind, enabled = !ui.bind.running && ui.cloud.signedIn && ui.address.isNotBlank()) { Text("Bind") }
                OutlinedButton(onClick = vm::cancelBind, enabled = ui.bind.running) { Text("Cancel") }
            }
            if (ui.bind.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.bind.oob?.let { Text("Pairing code: $it", style = MaterialTheme.typography.titleMedium) }
            if (ui.bind.status.isNotEmpty()) Text(ui.bind.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CloudKeySection(cloud: CloudState, vm: GlassesController) {
    var user by rememberSaveable(cloud.user) { mutableStateOf(cloud.user) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf(true) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Xiaomi account (glasses bound in the CN app)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(user, { user = it }, label = { Text("E-mail / phone / ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password (empty = saved session)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.cloudLogin(user, password); password = "" }, enabled = !cloud.busy) { Text("Fetch key") }
                OutlinedButton(onClick = vm::cloudLogout, enabled = !cloud.busy) { Text("Sign out") }
            }
            cloud.needsCode?.let { need ->
                if (need.email && need.sms) Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(email, { email = true }); Text("E-mail")
                    RadioButton(!email, { email = false }); Text("SMS")
                }
                val useEmail = if (need.email && need.sms) email else need.email
                OutlinedButton(onClick = { vm.cloudSendCode(useEmail) }, enabled = !cloud.busy) {
                    Text(if (cloud.codeSent) "Resend code" else "Send code by ${if (useEmail) "e-mail" else "SMS"}")
                }
                if (cloud.codeSent) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.cloudVerify(useEmail, code); code = "" }, enabled = !cloud.busy && code.isNotBlank()) { Text("Verify") }
                }
            }
            if (cloud.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (cloud.status.isNotEmpty()) Text(cloud.status, style = MaterialTheme.typography.bodySmall)
            cloud.devices.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name.ifEmpty { d.model }) },
                    supportingContent = { Text("${d.model}  ${d.mac}\nkey ${d.encryptKey.take(8)}…", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    trailingContent = { TextButton(onClick = { vm.useCloudDevice(d) }, enabled = d.encryptKey.isNotEmpty()) { Text("Use") } },
                    modifier = Modifier.clickable(enabled = d.encryptKey.isNotEmpty()) { vm.useCloudDevice(d) },
                )
            }
        }
    }
}

@Composable
private fun CommandsTab(ui: UiState, vm: GlassesController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.ready) item { Text("Connect and authenticate first.", color = MaterialTheme.colorScheme.error) }
        items(GlassesCommands.catalog, key = { it.key }) { c ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        Text("type ${c.type} / id ${c.id} — ${c.explanation}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { vm.send(c) }, enabled = ui.ready) { Text(if (c.changesDevice) "Run" else "Send") }
                }
            }
        }
        if (ui.lastResponse.isNotEmpty()) item {
            Text("Last response", style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(ui.lastResponse, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MediaTab(ui: UiState, vm: GlassesController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Press the glasses button (photo) or hold it (video). Sync to gallery copies full-size files over Wi-Fi Direct (files stay on the glasses).",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("take_photo" to "Photo", "toggle_record" to "Video", "media_sync" to "Sync").forEach { (key, label) ->
                    Button(onClick = { GlassesCommands.catalog.first { it.key == key }.let(vm::send) }, enabled = ui.ready) { Text(label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::syncGallery, enabled = ui.ready && !ui.syncing) { Text("Sync to gallery") }
                OutlinedButton(onClick = { vm.downloadThumbnails() }, enabled = ui.ready && ui.media.isNotEmpty()) { Text("Thumbnails") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::askAi, enabled = ui.ready) { Text("Ask Gemini") }
                Text("AI: ${ui.aiState} (or use the XiaoAI gesture)", style = MaterialTheme.typography.bodySmall)
            }
            if (ui.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.syncStatus.isNotEmpty()) Text(ui.syncStatus, style = MaterialTheme.typography.bodySmall)
            ui.downloading?.let { Text("Downloading $it (${ui.thumbQueue} queued)", style = MaterialTheme.typography.bodySmall) }
        }
        if (ui.media.isNotEmpty()) item { Text("Files", style = MaterialTheme.typography.titleSmall) }
        items(ui.media, key = { it.id }) { f -> MediaRow(f, ui.thumbs[f.id]) }
        if (ui.events.isNotEmpty()) item { Text("Events", style = MaterialTheme.typography.titleSmall) }
        items(ui.events.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}

@Composable
private fun MediaRow(f: GlassesEvents.MediaFile, thumb: String?) {
    // Timestamp unit is not confirmed; treat values above 1e12 as milliseconds.
    val millis = if (f.timestamp > 1_000_000_000_000L) f.timestamp else f.timestamp * 1000
    val date = remember(millis) { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(millis)) }
    val bitmap = remember(thumb) { thumb?.let { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.clickable(enabled = bitmap != null) { expanded = !expanded }) {
        ListItem(
            leadingContent = bitmap?.takeIf { !expanded }?.let { b ->
                { androidx.compose.foundation.Image(b, contentDescription = f.id, modifier = Modifier.size(72.dp)) }
            },
            headlineContent = { Text(f.id) },
            supportingContent = {
                Text("${f.kind} · ${f.size / 1024} KB · $date" + (f.duration?.let { " · ${it}s" } ?: "") +
                    if (thumb != null && bitmap == null) " · thumbnail not decodable" else "")
            },
        )
        if (expanded && bitmap != null) androidx.compose.foundation.Image(bitmap, contentDescription = f.id, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsTab(ui: UiState, vm: GlassesController) {
    LaunchedEffect(ui.ready) { if (ui.ready && ui.settings == null) vm.readSettings() }
    val setting = ui.settings
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Values are raw protocol numbers; meanings are partly unverified. Apply writes one group, then re-reads.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::readSettings, enabled = ui.ready) { Text("Read from glasses") }
            if (!ui.ready) Text("Connect and authenticate first.", color = MaterialTheme.colorScheme.error)
        }
        if (setting != null) items(GlassesSettings.groups, key = { it.field }) { group ->
            val reported = runCatching { Proto.bytes(Proto.decode(setting), group.field) != null }.getOrDefault(false)
            SettingsGroup(group, setting, reported, dirty = group.field in ui.settingsDirty, ready = ui.ready,
                onEdit = { item, value -> vm.editSetting(group, item, value) }, onApply = { vm.applySettings(group) })
        }
    }
}

@Composable
private fun SettingsGroup(
    group: GlassesSettings.Group, setting: ByteArray, reported: Boolean, dirty: Boolean, ready: Boolean,
    onEdit: (GlassesSettings.Item, Long) -> Unit, onApply: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(reported) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (reported) "Reported by glasses" else "Not reported (probably unsupported)", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onApply, enabled = ready && dirty) { Text("Apply") }
            }
            if (expanded) group.items.forEach { item ->
                SettingRow(item, GlassesSettings.get(setting, group, item)) { onEdit(item, it) }
            }
        }
    }
}

@Composable
private fun SettingRow(item: GlassesSettings.Item, value: Long?, onChange: (Long) -> Unit) {
    when (val kind = item.kind) {
        GlassesSettings.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label + if (value == null) " (unset)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = value == 1L, onCheckedChange = { onChange(if (it) 1 else 0) })
        }
        is GlassesSettings.Choice -> Column {
            Text("${item.label}: ${value ?: "unset"}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (kind.options.keys + listOfNotNull(value)).distinct().forEach { v ->
                    FilterChip(selected = v == value, onClick = { onChange(v) },
                        label = { Text(kind.options[v] ?: "raw $v") })
                }
            }
        }
        is GlassesSettings.Number -> {
            var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
            OutlinedTextField(text, { t ->
                text = t
                t.toLongOrNull()?.takeIf { it in kind.min..kind.max && it != value }?.let(onChange)
            }, label = { Text(item.label) }, placeholder = { Text("unset") }, singleLine = true,
                isError = text.isNotEmpty() && text.toLongOrNull()?.let { it in kind.min..kind.max } != true,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LogTab(log: List<String>, clear: () -> Unit) {
    val state = rememberLazyListState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(log.joinToString("\n")))
                android.widget.Toast.makeText(context, "Copied ${log.size} lines", android.widget.Toast.LENGTH_SHORT).show()
            }, enabled = log.isNotEmpty()) { Text("Copy all") }
            OutlinedButton(onClick = clear, enabled = log.isNotEmpty()) { Text("Clear") }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(12.dp)) {
                items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun TodoTab() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Roadmap.items) { item ->
            val color = when (item.status) {
                Roadmap.Status.Done -> MaterialTheme.colorScheme.primary
                Roadmap.Status.Untested -> MaterialTheme.colorScheme.tertiary
                Roadmap.Status.Todo -> MaterialTheme.colorScheme.outline
            }
            Column {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.status.label, color = color, style = MaterialTheme.typography.labelMedium)
                Text(item.note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
