package wtf.mahdi.glassesconnect.ui.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.Roadmap
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.Segmented
import wtf.mahdi.glassesconnect.ui.components.XCard
import wtf.mahdi.glassesconnect.ui.theme.Xiao

data class DevUi(val log: List<String>, val ready: Boolean, val lastResponse: String, val events: List<String>)

class DeveloperViewModel(c: GlassesController) : ControllerViewModel(c) {
    val state: StateFlow<DevUi> = derive { DevUi(it.log, it.ready, it.lastResponse, it.events) }
    fun clear() { c.clearLog(); message("Log cleared") }
    fun send(cmd: GlassesCommands.Command) {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        c.send(cmd); message("Sent ${cmd.title}")
    }
    fun copied(n: Int) = message("Copied $n lines")
}

@Composable
fun DeveloperScreen(startTab: Int) {
    val controller = LocalController.current
    val vm = viewModel { DeveloperViewModel(controller) }
    CollectMessages(vm)
    val ui by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        AppBar("Developer", onBack = nav::back) {
            if (tab == 0) {
                IconBtn(R.drawable.ic_content_copy, "Copy log", { clipboard.setText(AnnotatedString(ui.log.joinToString("\n"))); vm.copied(ui.log.size) }, enabled = ui.log.isNotEmpty())
                IconBtn(R.drawable.ic_delete_sweep, "Clear log", vm::clear, enabled = ui.log.isNotEmpty())
            }
        }
        Segmented(listOf("Log", "Commands", "Roadmap"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when (tab) {
            0 -> LogList(ui.log)
            1 -> Commands(ui, vm)
            else -> RoadmapList()
        }
    }
}

@Composable
private fun LogList(log: List<String>) {
    val c = Xiao.colors
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.lastIndex) }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = state, contentPadding = PaddingValues(16.dp)) {
            itemsIndexed(log) { _, line ->
                val color = when {
                    Regex("fail|error|timeout|BAD|refused", RegexOption.IGNORE_CASE).containsMatchIn(line) -> c.rec
                    "Event:" in line -> c.ok
                    "Requesting" in line || "Writing" in line || "Sending" in line -> c.accent
                    else -> c.text
                }
                Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun Commands(ui: DevUi, vm: DeveloperViewModel) {
    val c = Xiao.colors
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(GlassesCommands.catalog, key = { it.key }) { cmd ->
            XCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(cmd.title, style = MaterialTheme.typography.bodyLarge)
                        Text("type ${cmd.type} / id ${cmd.id}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.muted)
                        Text(cmd.explanation, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Surface(onClick = { vm.send(cmd) }, enabled = ui.ready, shape = RoundedCornerShape(16.dp), color = c.surface2,
                        contentColor = if (cmd.changesDevice) c.warn else c.accent) {
                        Text(if (cmd.changesDevice) "Run" else "Send", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (ui.lastResponse.isNotEmpty()) item {
            Text("Last response", Modifier.padding(top = 10.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(ui.lastResponse, Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        if (ui.events.isNotEmpty()) {
            item { Text("Events", Modifier.padding(top = 10.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall) }
            items(ui.events.asReversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.muted) }
        }
    }
}

@Composable
private fun RoadmapList() {
    val c = Xiao.colors
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Roadmap.items) { item ->
            val (fg, bg) = when (item.status) {
                Roadmap.Status.Done -> c.ok to c.ok.copy(alpha = 0.16f)
                Roadmap.Status.Untested -> c.warn to c.warn.copy(alpha = 0.16f)
                Roadmap.Status.Todo -> c.muted to c.surface2
            }
            XCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(if (item.status == Roadmap.Status.Untested) "Untested" else item.status.label,
                            Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
                            color = fg, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(item.note, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
    }
}
