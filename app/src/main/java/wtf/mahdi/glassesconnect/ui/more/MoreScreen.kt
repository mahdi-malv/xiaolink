package wtf.mahdi.glassesconnect.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import wtf.mahdi.glassesconnect.BuildConfig
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.Route
import wtf.mahdi.glassesconnect.ui.ai.KeySheet
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.SectionLabel
import wtf.mahdi.glassesconnect.ui.theme.Xiao

data class MoreState(val ready: Boolean, val connecting: Boolean, val state: String, val signedIn: Boolean, val user: String,
                     val key: String, val logLines: Int, val hasToken: Boolean)

class MoreViewModel(c: GlassesController) : ControllerViewModel(c) {
    val state: StateFlow<MoreState> = derive {
        MoreState(it.ready, it.connecting, it.state, it.cloud.signedIn, it.cloud.user, it.geminiKey, it.log.size, it.token.isNotBlank())
    }
    fun toggleConnection() = if (state.value.ready || state.value.connecting) { c.disconnect(); message("Disconnected") } else c.connect()
    fun setKey(key: String) { c.setGeminiKey(key); message("API key saved") }
}

@Composable
fun MoreScreen() {
    val controller = LocalController.current
    val vm = viewModel { MoreViewModel(controller) }
    CollectMessages(vm)
    val st by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var editKey by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppBar("More")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Glasses")
            ListGroup {
                row { ListRow("Glasses settings", "Camera, AI, audio, light", R.drawable.ic_tune, onClick = { nav.go(Route.Settings) }) }
                row { ListRow("Live stream", "RTMP push, phone preview, Wi-Fi", R.drawable.ic_sensors, onClick = { nav.go(Route.Stream) }) }
            }
            SectionLabel("App")
            ListGroup {
                row {
                    ListRow("Connection", if (st.signedIn) "Xiaomi account ${st.user}" else if (st.hasToken) "Key saved" else "Not set up",
                        R.drawable.ic_link, onClick = { nav.go(Route.Onboarding()) })
                }
                row {
                    ListRow(if (st.ready || st.connecting) "Disconnect" else "Connect", st.state, R.drawable.ic_bluetooth,
                        enabled = st.hasToken, onClick = vm::toggleConnection, trailing = {})
                }
                row { ListRow("Gemini API key", if (st.key.isBlank()) "Not set" else "••••••••" + st.key.takeLast(4), R.drawable.ic_key, monoSubtitle = true, onClick = { editKey = true }) }
            }
            SectionLabel("Developer")
            ListGroup {
                row { ListRow("Log", "${st.logLines} lines · copy or clear", R.drawable.ic_receipt_long, onClick = { nav.go(Route.Developer(0)) }) }
                row { ListRow("Commands", "Raw protocol commands", R.drawable.ic_terminal, onClick = { nav.go(Route.Developer(1)) }) }
                row { ListRow("Roadmap", "Done, untested and todo items", R.drawable.ic_checklist, onClick = { nav.go(Route.Developer(2)) }) }
            }
            Text("XiaoLink ${BuildConfig.VERSION_NAME} · GPL-3.0", Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = Xiao.colors.muted, textAlign = TextAlign.Center)
        }
    }
    if (editKey) KeySheet(st.key, onSave = { vm.setKey(it); editKey = false }, onDismiss = { editKey = false })
}
