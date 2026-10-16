package wtf.mahdi.glassesconnect.ui.onboarding

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import wtf.mahdi.glassesconnect.CompanionLink
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.UiState
import wtf.mahdi.glassesconnect.cloud.XiaomiCloud
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.Permissions
import wtf.mahdi.glassesconnect.ui.Route
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.Segmented
import wtf.mahdi.glassesconnect.ui.components.Tone
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XCard
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.components.XLink
import wtf.mahdi.glassesconnect.ui.components.XTextField
import wtf.mahdi.glassesconnect.ui.theme.Xiao

/** Setup: Xiaomi cloud key (or a pasted key), then glasses + background permissions, then connect. */
class OnboardingViewModel(c: GlassesController) : ControllerViewModel(c) {
    val state: StateFlow<UiState> = c.ui
    fun login(user: String, password: String) = c.cloudLogin(user, password)
    fun sendCode(email: Boolean) = c.cloudSendCode(email)
    fun verify(email: Boolean, code: String) = c.cloudVerify(email, code)
    fun logout() = c.cloudLogout()
    fun useDevice(d: XiaomiCloud.Device) = c.useCloudDevice(d)
    fun setAddress(v: String) = c.setAddress(v)
    fun setToken(v: String) = c.setToken(v)
    fun refreshPaired() = c.refreshPaired()
    fun bind() = c.bind()
    fun cancelBind() = c.cancelBind()
    fun log(line: String) = c.log(line)
    fun connect() = c.connect()
}

@Composable
fun OnboardingScreen(first: Boolean) {
    val controller = LocalController.current
    val vm = viewModel { OnboardingViewModel(controller) }
    CollectMessages(vm)
    val ui by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var step by rememberSaveable { mutableIntStateOf(if (ui.token.isNotBlank()) 2 else 1) }
    var manualKey by rememberSaveable { mutableStateOf(false) }
    // Cloud sign-in finished with devices: go on to step 2 once.
    LaunchedEffect(ui.cloud.devices.size) { if (step == 1 && ui.cloud.devices.isNotEmpty()) step = 2 }
    BackHandler(enabled = step == 2) { step = 1 }

    val finish = {
        vm.connect()
        if (first) nav.replaceAll(Route.Device) else nav.back()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        AppBar(if (first) "" else "Connection", onBack = when {
            step == 2 -> ({ step = 1 })
            !first -> nav::back
            else -> null
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Steps(step)
            if (step == 1) SignInStep(ui, vm, manualKey, onManual = { manualKey = !manualKey }, onNext = { step = 2 })
            else DeviceStep(ui, vm)
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 16.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (step == 2) XButton("Connect", finish, Modifier.fillMaxWidth(), big = true,
                enabled = ui.address.isNotBlank() && ui.token.isNotBlank() && !ui.bind.running)
            if (step == 1 && !first && ui.token.isNotBlank()) XLink("Keep the saved key", { step = 2 })
        }
    }
}

@Composable
private fun Steps(step: Int) {
    val c = Xiao.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < step) c.accent else c.surface2)) }
    }
}

@Composable
private fun Headline(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Xiao.colors.muted)
    }
}

@Composable
private fun SignInStep(ui: UiState, vm: OnboardingViewModel, manual: Boolean, onManual: () -> Unit, onNext: () -> Unit) {
    val c = Xiao.colors
    val cloud = ui.cloud
    var user by rememberSaveable(cloud.user) { mutableStateOf(cloud.user) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf(true) }

    Headline("Sign in to Xiaomi", "XiaoLink needs your glasses' key from Xiaomi cloud. Your password goes only to Xiaomi.")
    XTextField(user, { user = it }, "E-mail, phone or Xiaomi ID")
    XTextField(password, { password = it }, if (cloud.signedIn) "Password (empty = saved session)" else "Password", password = true)
    cloud.needsCode?.let { need ->
        XCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Xiaomi asks for a verification code", style = MaterialTheme.typography.titleSmall)
                if (need.email && need.sms) Segmented(listOf("E-mail", "SMS"), if (email) 0 else 1, { email = it == 0 })
                val useEmail = if (need.email && need.sms) email else need.email
                XButton(if (cloud.codeSent) "Send again" else "Send code by ${if (useEmail) "e-mail" else "SMS"}", { vm.sendCode(useEmail) },
                    tone = Tone.Ghost, enabled = !cloud.busy)
                if (cloud.codeSent) {
                    XTextField(code, { code = it }, "Code", mono = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    XButton("Verify", { vm.verify(useEmail, code); code = "" }, Modifier.fillMaxWidth(), enabled = !cloud.busy && code.isNotBlank())
                }
            }
        }
    }
    if (cloud.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
    if (cloud.status.isNotEmpty()) Text(cloud.status, style = MaterialTheme.typography.bodySmall, color = c.muted)
    if (cloud.needsCode == null) XButton(if (cloud.signedIn) "Fetch key again" else "Sign in", { vm.login(user, password); password = "" },
        Modifier.fillMaxWidth(), big = true, enabled = !cloud.busy && user.isNotBlank())
    Row(verticalAlignment = Alignment.CenterVertically) {
        XLink(if (manual) "Hide manual key" else "I already have a key", onManual)
        if (cloud.signedIn) { Spacer(Modifier.weight(1f)); XLink("Sign out", vm::logout) }
    }
    if (manual) {
        Text("Paste the hex encrypt_key (tools/fetch_key.py) and the glasses Bluetooth address.", style = MaterialTheme.typography.bodySmall, color = c.muted)
        XTextField(ui.token, vm::setToken, "encrypt_key (hex)", mono = true)
        XTextField(ui.address, vm::setAddress, "Glasses MAC", mono = true, placeholder = "AA:BB:CC:DD:EE:FF")
        XButton("Next", onNext, Modifier.fillMaxWidth(), enabled = ui.token.isNotBlank())
    }
}

@Composable
private fun DeviceStep(ui: UiState, vm: OnboardingViewModel) {
    val c = Xiao.colors
    val context = LocalContext.current
    var bindOpen by rememberSaveable { mutableStateOf(false) }

    Headline("Choose your glasses",
        if (ui.cloud.devices.isNotEmpty()) "Found ${ui.cloud.devices.size} on your Xiaomi account. Pair them in Android Bluetooth settings first."
        else "Pick the paired glasses. Pair them in Android Bluetooth settings first.")

    ui.cloud.devices.forEach { d ->
        DeviceCard(d.name.ifEmpty { d.model }, "${d.model} · ${d.mac}", selected = d.mac.equals(ui.address, true),
            enabled = d.encryptKey.isNotEmpty()) { vm.useDevice(d) }
    }
    val cloudMacs = ui.cloud.devices.map { it.mac.uppercase() }.toSet()
    val others = ui.paired.filter { it.address.uppercase() !in cloudMacs }
    if (others.isNotEmpty()) {
        Text("Paired with this phone", style = MaterialTheme.typography.titleSmall)
        ListGroup {
            others.forEach { d ->
                row {
                    ListRow(d.name, d.address, R.drawable.ic_bluetooth, monoSubtitle = true, onClick = { vm.setAddress(d.address) }, trailing = {
                        if (d.address.equals(ui.address, true)) XIcon(R.drawable.ic_check_circle_fill, tint = c.accent)
                    })
                }
            }
        }
    }
    XLink("Open Bluetooth settings", { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })

    PermissionsGroup(ui, vm)

    if (bindOpen) BindCard(ui, vm) else XLink("Pair new (reset) glasses instead", { bindOpen = true }, enabled = ui.cloud.signedIn)
}

@Composable
private fun DeviceCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Xiao.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) c.accentSoft else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(painterResource(R.drawable.glasses_hero), null, Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, fontFamily = FontFamily.Monospace)
            if (!enabled) Text("No key on the account", style = MaterialTheme.typography.bodySmall, color = c.rec)
        }
        if (selected) XIcon(R.drawable.ic_check_circle_fill, tint = c.accent)
    }
}

/** Runtime permissions, CDM association (Android 13+) and battery exemption keep the session alive in the background. */
@SuppressLint("BatteryLife")
@Composable
private fun PermissionsGroup(ui: UiState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; vm.refreshPaired() }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }
    val missing = remember(tick) { Permissions.missing(context) }
    val associated = remember(tick, ui.address) { CompanionLink.isAssociated(context, ui.address) }
    val batteryOk = remember(tick) { CompanionLink.ignoresBatteryOptimizations(context) }

    Text("Stay connected", style = MaterialTheme.typography.titleSmall)
    ListGroup {
        row {
            PermissionRow("Nearby devices", "Bluetooth, Wi-Fi Direct and notifications", R.drawable.ic_bluetooth, missing.isEmpty()) { runtime.launch(missing) }
        }
        if (CompanionLink.supported) row {
            PermissionRow("Companion device", "Android wakes the app when the glasses are near", R.drawable.ic_devices, associated, enabled = ui.address.isNotBlank()) {
                CompanionLink.associate(context, ui.address, log = vm::log, onPending = { dialog.launch(IntentSenderRequest.Builder(it).build()) })
            }
        }
        row {
            PermissionRow("No battery limits", "Keeps the session and the AI working with the screen off", R.drawable.ic_battery_saver, batteryOk) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, icon: Int, granted: Boolean, enabled: Boolean = true, onAllow: () -> Unit) {
    val c = Xiao.colors
    ListRow(title, subtitle, icon, trailing = {
        if (granted) Row(verticalAlignment = Alignment.CenterVertically) {
            XIcon(R.drawable.ic_check, tint = c.ok, size = 18.dp)
            Text("Done", style = MaterialTheme.typography.labelLarge, color = c.ok)
        } else XButton("Allow", onAllow, tone = Tone.Ghost, enabled = enabled)
    })
}

@Composable
private fun BindCard(ui: UiState, vm: OnboardingViewModel) {
    val c = Xiao.colors
    XCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pair new glasses", style = MaterialTheme.typography.titleSmall)
            Text("Binds reset glasses to your Xiaomi account, then fetches the new key and connects. Select the paired glasses above first.",
                style = MaterialTheme.typography.bodySmall, color = c.muted)
            ui.bind.oob?.let { Text("Pairing code: $it", style = MaterialTheme.typography.titleLarge, color = c.accent) }
            if (ui.bind.running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
            if (ui.bind.status.isNotEmpty()) Text(ui.bind.status, style = MaterialTheme.typography.bodySmall, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                XButton("Bind", vm::bind, enabled = !ui.bind.running && ui.cloud.signedIn && ui.address.isNotBlank())
                if (ui.bind.running) XButton("Cancel", vm::cancelBind, tone = Tone.Ghost)
            }
        }
    }
}
