package wtf.mahdi.glassesconnect.ui.stream

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.StreamState
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.ChipRow
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.Segmented
import wtf.mahdi.glassesconnect.ui.components.Tone
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XSheet
import wtf.mahdi.glassesconnect.ui.components.XTextField
import wtf.mahdi.glassesconnect.ui.theme.Xiao

data class StreamUi(val st: StreamState, val ready: Boolean)

/** Live stream (device-verified 2026-10-16): glasses join Wi-Fi and push RTMP to [StreamState.url]; optional phone receiver. */
class StreamViewModel(c: GlassesController) : ControllerViewModel(c) {
    val state: StateFlow<StreamUi> = derive { StreamUi(it.stream, it.ready) }
    var streaming by mutableStateOf(false); private set

    fun edit(f: (StreamState) -> StreamState) = c.editStream(f)
    fun usePhone(phone: Boolean) {
        if (phone) { c.previewStart(); c.ui.value.stream.phoneIps.firstOrNull()?.let { c.usePhoneUrl(it.second) } } else c.previewStop()
    }
    fun useIp(ip: String) = c.usePhoneUrl(ip)
    fun refreshIps() = c.previewRefreshIps()
    fun surface(s: android.view.Surface?) = c.previewSurface(s)
    fun query() = if (ready()) c.streamQuery() else Unit
    fun sendWifi() = if (ready()) c.streamWifi() else Unit
    fun wifiList() = if (ready()) c.streamWifiList() else Unit

    fun toggle() {
        if (!ready()) return
        if (streaming) c.streamStop() else c.streamStart()
        streaming = !streaming
    }

    private fun ready() = c.ui.value.ready.also { if (!it) message("Connect the glasses first") }
}

private val resolutions = listOf(1 to "480p", 2 to "720p", 3 to "1080p")
private val fpsOptions = listOf(15, 24, 25, 30)
private val modes = listOf(1 to "Auto", 2 to "Frame rate", 3 to "Quality")

@Composable
fun StreamScreen() {
    val controller = LocalController.current
    val vm = viewModel { StreamViewModel(controller) }
    CollectMessages(vm)
    val ui by vm.state.collectAsStateWithLifecycle()
    val st = ui.st
    val p = st.preview
    val nav = LocalNav.current
    val c = Xiao.colors
    var wifiSheet by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppBar("Live stream", onBack = nav::back) { IconBtn(R.drawable.ic_refresh, "Query stream modes", vm::query) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Preview(st, onSurface = vm::surface)
            Segmented(listOf("This phone", "RTMP server"), if (p.listening) 0 else 1, { vm.usePhone(it == 0) })
            if (p.listening) {
                if (st.phoneIps.isEmpty()) Text("No network address. Join Wi-Fi or turn on the hotspot.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                else Row(Modifier.horizontalScroll(rememberScrollState())) {
                    val selected = st.phoneIps.indexOfFirst { st.url.contains(it.second) }
                    ChipRow(st.phoneIps.map { "${it.second} · ${it.first}" }, selected) { vm.useIp(st.phoneIps[it].second) }
                }
            }
            XTextField(st.url, { v -> vm.edit { it.copy(url = v) } }, "Stream URL", mono = true, placeholder = "rtmp://192.168.1.10:1935/live/test")
            Option("Resolution") { Segmented(resolutions.map { it.second }, resolutions.indexOfFirst { it.first == st.resolution }, { i -> vm.edit { it.copy(resolution = resolutions[i].first) } }) }
            Option("Frame rate") { Segmented(fpsOptions.map { "$it fps" }, fpsOptions.indexOf(st.fps), { i -> vm.edit { it.copy(fps = fpsOptions[i]) } }) }
            Option("Priority") { Segmented(modes.map { it.second }, modes.indexOfFirst { it.first == st.mode }, { i -> vm.edit { it.copy(mode = modes[i].first) } }) }
            ListGroup {
                row { ListRow("Glasses Wi-Fi", st.ssid.ifEmpty { "Not sent yet" } + if (st.wifi.isNotEmpty()) " · ${st.wifi}" else "", R.drawable.ic_wifi, onClick = { wifiSheet = true }) }
            }
            XButton(if (vm.streaming) "Stop stream" else "Start stream", vm::toggle, Modifier.fillMaxWidth(),
                icon = if (vm.streaming) R.drawable.ic_stop_fill else R.drawable.ic_sensors_fill, tone = if (vm.streaming) Tone.Stop else Tone.Accent,
                enabled = ui.ready && (vm.streaming || st.url.isNotBlank()), big = true)
            if (st.status.isNotEmpty()) Text(st.status, style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (st.modes.isNotEmpty()) Text("Modes: ${st.modes} (1 Wi-Fi, 2 wired, 4 Bluetooth)", style = MaterialTheme.typography.bodySmall, color = c.muted)
            p.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.rec) }
        }
    }
    if (wifiSheet) XSheet({ wifiSheet = false }, "Glasses Wi-Fi") {
        Text("The glasses join this network to push the stream. Use the same network as the receiver, or the phone hotspot.",
            style = MaterialTheme.typography.bodySmall, color = c.muted)
        XTextField(st.ssid, { v -> vm.edit { it.copy(ssid = v) } }, "Network name")
        XTextField(st.password, { v -> vm.edit { it.copy(password = v) } }, "Password (empty = open)", password = true)
        if (st.wifi.isNotEmpty()) Text(st.wifi, style = MaterialTheme.typography.bodySmall, color = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            XButton("Send to glasses", vm::sendWifi, Modifier.weight(1f))
            XButton("Saved list", vm::wifiList, tone = Tone.Ghost)
        }
    }
}

@Composable
private fun Option(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = Xiao.colors.muted)
        content()
    }
}

@Composable
private fun Preview(st: StreamState, onSurface: (android.view.Surface?) -> Unit) {
    val p = st.preview
    val ratio = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 16f / 9f
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF23343A), Color(0xFF0B1214))))) {
        if (p.listening) AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = onSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) = onSurface(null)
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (p.client != null) Text("LIVE", Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Xiao.colors.rec).padding(horizontal = 8.dp, vertical = 3.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
        val stats = listOfNotNull(p.codec, if (p.width > 0) "${p.width}×${p.height}" else null,
            if (p.client != null) "${p.fps} fps · ${p.kbps} kbps" else null).joinToString(" · ")
        if (stats.isNotEmpty()) Text(stats, Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White,
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (p.client == null) Text(
            if (p.listening) "Receiver on :1935. Waiting for the glasses…" else "Choose This phone to preview here",
            Modifier.align(Alignment.Center).padding(24.dp), color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
}
