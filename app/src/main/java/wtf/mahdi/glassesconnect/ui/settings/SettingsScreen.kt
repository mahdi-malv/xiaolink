package wtf.mahdi.glassesconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.protocol.GlassesSettings
import wtf.mahdi.glassesconnect.protocol.Proto
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.ChoiceSheet
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.SectionLabel
import wtf.mahdi.glassesconnect.ui.components.SwitchRow
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XCard
import wtf.mahdi.glassesconnect.ui.components.XSheet
import wtf.mahdi.glassesconnect.ui.components.XTextField
import wtf.mahdi.glassesconnect.ui.components.splitNote
import wtf.mahdi.glassesconnect.ui.theme.Xiao

data class SettingsUi(val setting: ByteArray?, val ready: Boolean)

/** Glasses settings (System id 14 read / id 15 write). Each change is written at once; see [ControllerViewModel.writeSetting]. */
class SettingsViewModel(c: GlassesController) : ControllerViewModel(c) {
    val state: StateFlow<SettingsUi> = derive { SettingsUi(it.settings, it.ready) }
    fun read() { if (c.ui.value.ready) { c.readSettings(); message("Reading settings from the glasses") } else message("Connect the glasses first") }
    fun loadOnce() { if (c.ui.value.ready && c.ui.value.settings == null) c.readSettings() }
}

private sealed interface Edit {
    val group: GlassesSettings.Group; val item: GlassesSettings.Item
    data class Pick(override val group: GlassesSettings.Group, override val item: GlassesSettings.Item) : Edit
    data class Num(override val group: GlassesSettings.Group, override val item: GlassesSettings.Item) : Edit
}

/** Choice with exactly On/Off (1/2, 0 = unsupported) shows as a switch. */
private fun GlassesSettings.Kind.isOnOff() = this is GlassesSettings.Choice && options == mapOf(1L to "On", 2L to "Off")

@Composable
fun SettingsScreen() {
    val controller = LocalController.current
    val vm = viewModel { SettingsViewModel(controller) }
    CollectMessages(vm)
    val ui by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val c = Xiao.colors
    var edit by remember { mutableStateOf<Edit?>(null) }
    LaunchedEffect(ui.ready) { vm.loadOnce() }
    val setting = ui.setting

    Column(Modifier.fillMaxSize()) {
        AppBar("Glasses settings", onBack = nav::back) { IconBtn(R.drawable.ic_refresh, "Read from glasses", vm::read, enabled = ui.ready) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!ui.ready) XCard(Modifier.fillMaxWidth()) {
                Text("Connect the glasses to change settings.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            if (setting == null) {
                if (ui.ready) Text("Reading settings…", Modifier.padding(8.dp), color = c.muted)
                return@Column
            }
            val decoded = remember(setting) { runCatching { Proto.decode(setting) }.getOrNull() }
            val (reported, missing) = SettingsFormat.ordered.partition { g -> decoded?.let { Proto.bytes(it, g.field) != null } == true }
            reported.forEach { g ->
                SectionLabel(SettingsFormat.title(g))
                ListGroup {
                    g.items.forEach { item ->
                        val v = GlassesSettings.get(setting, g, item)
                        val (title, note) = splitNote(item.label)
                        row {
                            when {
                                SettingsFormat.readOnly(item) -> ListRow(title, note, value = SettingsFormat.value(item, v), trailing = {})
                                item.kind == GlassesSettings.Bool -> SwitchRow(title, note, v == 1L, enabled = ui.ready) { vm.writeSetting(g, item, if (it) 1 else 0) }
                                item.kind.isOnOff() && v != 0L -> SwitchRow(title, note, v == 1L, enabled = ui.ready) { vm.writeSetting(g, item, if (it) 1 else 2) }
                                item.kind is GlassesSettings.Choice -> ListRow(title, SettingsFormat.value(item, v), enabled = ui.ready, onClick = { edit = Edit.Pick(g, item) })
                                else -> ListRow(title, note ?: "Raw value", value = v?.toString() ?: "Not set", enabled = ui.ready, onClick = { edit = Edit.Num(g, item) })
                            }
                        }
                    }
                }
            }
            if (missing.isNotEmpty()) Text("Not reported by these glasses: " + missing.joinToString { SettingsFormat.title(it) },
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }

    val s = setting ?: return
    when (val e = edit) {
        is Edit.Pick -> {
            val current = GlassesSettings.get(s, e.group, e.item)
            ChoiceSheet(splitNote(e.item.label).first, SettingsFormat.options(e.item.kind as GlassesSettings.Choice, current), current,
                onPick = { vm.writeSetting(e.group, e.item, it); edit = null }, onDismiss = { edit = null })
        }
        is Edit.Num -> NumberSheet(e, GlassesSettings.get(s, e.group, e.item), onSave = { vm.writeSetting(e.group, e.item, it); edit = null }) { edit = null }
        null -> Unit
    }
}

@Composable
private fun NumberSheet(e: Edit, current: Long?, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    val kind = e.item.kind as GlassesSettings.Number
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val value = text.toLongOrNull()?.takeIf { it in kind.min..kind.max }
    XSheet(onDismiss, splitNote(e.item.label).first) {
        Text("Raw protocol value (${kind.min}..${kind.max}). The meaning is not confirmed; the official app does not write it.",
            style = MaterialTheme.typography.bodySmall, color = Xiao.colors.warn)
        XTextField(text, { text = it }, "Value", mono = true, keyboardType = KeyboardType.Number)
        XButton("Write to glasses", { value?.let(onSave) }, Modifier.fillMaxWidth(), enabled = value != null && value != current, big = true)
    }
}
