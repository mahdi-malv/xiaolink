package wtf.mahdi.glassesconnect.ui.device

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.protocol.GlassesSettings
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.Route
import wtf.mahdi.glassesconnect.ui.components.ChoiceSheet
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.Pill
import wtf.mahdi.glassesconnect.ui.components.SectionHeader
import wtf.mahdi.glassesconnect.ui.components.SwitchRow
import wtf.mahdi.glassesconnect.ui.components.Tone
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.gallery.MediaThumb
import wtf.mahdi.glassesconnect.ui.gallery.PhoneGallery
import wtf.mahdi.glassesconnect.ui.settings.SettingsFormat
import wtf.mahdi.glassesconnect.ui.theme.Xiao

@Composable
fun DeviceScreen() {
    val controller = LocalController.current
    val context = LocalContext.current
    val vm = viewModel { DeviceViewModel(controller, PhoneGallery.get(context)) }
    CollectMessages(vm)
    val st by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var flashes by remember { mutableIntStateOf(0) }
    LaunchedEffect(st.ready) { vm.loadSettings() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            XIcon(R.drawable.ic_eyeglasses, tint = Xiao.colors.accent, size = 26.dp)
            Text("XiaoLink", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall)
            IconBtn(R.drawable.ic_tune, "Glasses settings", { nav.go(Route.Settings) })
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Hero(st, flashes)
            if (!st.ready) ConnectCard(st, onConnect = vm::connect, onSetup = { nav.go(Route.Onboarding()) })
            Actions(st, onPhoto = { vm.photo(); if (st.ready && !st.recording) flashes++ }, onVideo = vm::video, onSync = vm::sync)
            if (st.ui.syncing || st.ui.syncStatus.startsWith("Failed")) SyncStatus(st)
            if (st.recent.isNotEmpty()) {
                SectionHeader("Recent", "See all") { nav.go(Route.Gallery) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.recent.forEach { item -> MediaThumb(item, { nav.go(Route.Viewer(item.key)) }, Modifier.weight(1f), corner = 14.dp) }
                    repeat(4 - st.recent.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            st.ui.settings?.let { QuickSettings(it, vm, onAll = { nav.go(Route.Settings) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(st: DeviceState, flashes: Int) {
    val c = Xiao.colors
    val shape = RoundedCornerShape(28.dp)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashes) { if (flashes > 0) { flash.snapTo(0.85f); flash.animateTo(0f, tween(500)) } }
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.radialGradient(listOf(c.heroTop, c.heroBottom), radius = 900f))
            .border(1.dp, c.line, shape),
    ) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = 30.dp).size(260.dp, 120.dp)
            .background(Brush.radialGradient(listOf(c.accentSoft, Color.Transparent))))
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Xiaomi AI Glasses", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text(st.ui.address.takeLast(5).ifEmpty { "O95" }, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Image(
                painterResource(R.drawable.glasses_hero), "Xiaomi AI Glasses",
                Modifier.fillMaxWidth(0.86f).align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
                    .graphicsLayer { alpha = if (st.ready) 1f else 0.6f },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val (label, dot) = when {
                    st.ready -> "Connected" to c.ok
                    st.ui.connecting -> "Connecting…" to c.warn
                    else -> st.ui.state to c.muted
                }
                Pill(label, dot = dot)
                st.ui.battery?.let { Pill("$it%", icon = batteryIcon(it)) }
                if (st.ready) CameraPill(st)
            }
        }
        Box(Modifier.matchParentSize().graphicsLayer { alpha = flash.value }.background(Color.White))
    }
}

@Composable
private fun CameraPill(st: DeviceState) {
    val c = Xiao.colors
    val since = st.ui.recordingSince
    if (since != null) {
        val elapsed by produceState(0L, since) { while (true) { value = (System.currentTimeMillis() - since) / 1000; delay(1000) } }
        Pill("Recording ${clock(elapsed)}", dot = c.rec, fg = c.rec, bg = c.recSoft)
    } else Pill(if (st.cameraBusy) "Camera busy" else "Camera ready", icon = R.drawable.ic_photo_camera)
}

@Composable
private fun ConnectCard(st: DeviceState, onConnect: () -> Unit, onSetup: () -> Unit) {
    val c = Xiao.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accentSoft).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        XIcon(R.drawable.ic_bluetooth, tint = c.accent)
        Column(Modifier.weight(1f)) {
            Text(if (st.needsSetup) "Set up your glasses" else "Not connected", style = MaterialTheme.typography.titleSmall)
            Text(if (st.needsSetup) "Sign in to Xiaomi to get the glasses key" else st.ui.state, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (st.needsSetup) XButton("Set up", onSetup) else XButton("Connect", onConnect, enabled = !st.ui.connecting)
    }
}

@Composable
private fun Actions(st: DeviceState, onPhoto: () -> Unit, onVideo: () -> Unit, onSync: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile("Photo", R.drawable.ic_photo_camera_fill, onPhoto, Modifier.weight(1f), enabled = st.ready)
        val since = st.ui.recordingSince
        val elapsed by produceState(0L, since) { while (since != null) { value = (System.currentTimeMillis() - since) / 1000; delay(1000) } }
        ActionTile(
            if (since != null) "Stop" else "Video", if (since != null) R.drawable.ic_stop_fill else R.drawable.ic_videocam_fill, onVideo,
            Modifier.weight(1f), enabled = st.ready, recording = since != null, caption = since?.let { clock(elapsed) },
        )
        ActionTile(
            if (st.ui.syncing) "Syncing" else "Sync", if (st.ui.syncing) R.drawable.ic_downloading else R.drawable.ic_sync, onSync,
            Modifier.weight(1f), enabled = st.ready && !st.ui.syncing, badge = st.ui.pending.size.takeIf { it > 0 && !st.ui.syncing },
        )
    }
}

@Composable
private fun ActionTile(
    label: String, icon: Int, onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    recording: Boolean = false, caption: String? = null, badge: Int? = null,
) {
    val c = Xiao.colors
    val fg = if (recording) c.rec else c.text
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.height(104.dp), shape = RoundedCornerShape(24.dp),
        color = if (recording) c.recSoft else c.surface, contentColor = fg,
        border = if (recording) null else androidx.compose.foundation.BorderStroke(1.dp, c.line),
    ) {
        Box {
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = if (enabled) 1f else 0.45f },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(if (recording) c.rec else c.accent), contentAlignment = Alignment.Center) {
                    XIcon(icon, tint = if (recording) Color.White else c.onAccent, size = 25.dp)
                }
                Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                if (caption != null) Text(caption, style = MaterialTheme.typography.labelMedium, color = fg)
            }
            if (badge != null) Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).heightIn(min = 22.dp).clip(CircleShape).background(c.rec).padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$badge", color = Color.White, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SyncStatus(st: DeviceState) {
    val c = Xiao.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(st.ui.syncStatus.ifEmpty { "Starting sync…" }, style = MaterialTheme.typography.bodyMedium,
            color = if (st.ui.syncStatus.startsWith("Failed")) c.rec else c.text)
        if (st.ui.syncing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
    }
}

@Composable
private fun QuickSettings(setting: ByteArray, vm: DeviceViewModel, onAll: () -> Unit) {
    var choice by remember { mutableStateOf<Pair<GlassesSettings.Group, GlassesSettings.Item>?>(null) }
    SectionHeader("Quick settings", "All", onAll)
    ListGroup {
        listOf(SettingsFormat.videoDuration to ("Video length" to R.drawable.ic_timer), SettingsFormat.shootingMode to ("Button" to R.drawable.ic_touch_app))
            .forEach { (gi, meta) ->
                val (g, item) = gi
                val v = GlassesSettings.get(setting, g, item)
                if (v != null) row { ListRow(meta.first, icon = meta.second, value = SettingsFormat.value(item, v), onClick = { choice = gi }) }
            }
        val (wg, wi) = SettingsFormat.voiceWake
        GlassesSettings.get(setting, wg, wi)?.let { v ->
            row { SwitchRow("Voice wake-up", checked = v == 1L, icon = R.drawable.ic_record_voice_over) { vm.writeSetting(wg, wi, if (it) 1 else 0) } }
        }
    }
    choice?.let { (g, item) ->
        val kind = item.kind as GlassesSettings.Choice
        val current = GlassesSettings.get(setting, g, item)
        ChoiceSheet(SettingsFormat.title(g) + " · " + item.label.substringBefore(" ("), SettingsFormat.options(kind, current), current,
            onPick = { vm.writeSetting(g, item, it); choice = null }, onDismiss = { choice = null })
    }
}

fun batteryIcon(level: Int) = when {
    level >= 98 -> R.drawable.ic_battery_full
    else -> listOf(R.drawable.ic_battery_0_bar, R.drawable.ic_battery_1_bar, R.drawable.ic_battery_2_bar, R.drawable.ic_battery_3_bar,
        R.drawable.ic_battery_4_bar, R.drawable.ic_battery_5_bar, R.drawable.ic_battery_6_bar)[(level * 7 / 100).coerceIn(0, 6)]
}

fun clock(sec: Long) = "%02d:%02d".format(sec / 60, sec % 60)
