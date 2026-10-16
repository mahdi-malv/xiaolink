package wtf.mahdi.glassesconnect.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import wtf.mahdi.glassesconnect.BuildConfig
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ai.AiSettings
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.SectionLabel
import wtf.mahdi.glassesconnect.ui.components.Tone
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.components.XSheet
import wtf.mahdi.glassesconnect.ui.components.XTextField
import wtf.mahdi.glassesconnect.ui.theme.Xiao

class AiSettingsViewModel(c: GlassesController) : ControllerViewModel(c) {
    val settings: StateFlow<AiSettings> = derive { it.aiSettings }
    val turns: StateFlow<Int> = c.aiHistory.turns.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.aiHistory.turns.value.size)

    fun save(s: AiSettings) { c.setAiSettings(s); message("Saved. Applies from the next wake-up") }
    fun clearHistory() { c.aiHistory.clear(); message("History cleared") }
}

@Composable
fun AiSettingsScreen() {
    val controller = LocalController.current
    val vm = viewModel { AiSettingsViewModel(controller) }
    CollectMessages(vm)
    val saved by vm.settings.collectAsStateWithLifecycle()
    val turns by vm.turns.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val c = Xiao.colors

    var key by rememberSaveable { mutableStateOf(saved.key) }
    var live by rememberSaveable { mutableStateOf(saved.liveModel) }
    var vision by rememberSaveable { mutableStateOf(saved.visionModel) }
    var voice by rememberSaveable { mutableStateOf(saved.voice) }
    var language by rememberSaveable { mutableStateOf(saved.language) }
    var instructions by rememberSaveable { mutableStateOf(saved.instructions) }
    var pickVoice by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val draft = AiSettings(key, live, vision, voice, language, instructions)

    Column(Modifier.fillMaxSize()) {
        AppBar("Gemini settings", onBack = nav::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Account")
            XTextField(key, { key = it }, "API key", mono = true, password = true)
            Text("From aistudio.google.com. Stored on this phone only; sent only to Google.",
                Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, color = c.muted)

            SectionLabel("Models")
            XTextField(live, { live = it }, "Live model (voice)", mono = true, placeholder = BuildConfig.GEMINI_MODEL)
            XTextField(vision, { vision = it }, "Photo model", mono = true, placeholder = BuildConfig.GEMINI_VISION_MODEL)
            Text("Leave empty for the default. The photo model answers questions about what the glasses camera sees.",
                Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, color = c.muted)

            SectionLabel("Answers")
            ListGroup {
                row { ListRow("Voice", null, R.drawable.ic_record_voice_over, value = voice.ifBlank { "Default" }, onClick = { pickVoice = true }) }
            }
            XTextField(language, { language = it }, "Answer language", placeholder = "Same as the question")
            XTextField(instructions, { instructions = it }, "Custom instructions", minLines = 4,
                placeholder = "e.g. Keep answers to one sentence. I am vegetarian.")
            Text("Added to the built-in instructions, which handle the camera tools.",
                Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, color = c.muted)

            XButton("Save", { vm.save(draft) }, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = draft != saved, big = true)

            SectionLabel("History")
            ListGroup {
                row { ListRow("Clear history", "$turns of ${wtf.mahdi.glassesconnect.ai.AiHistory.MAX} turns stored on this phone",
                    R.drawable.ic_delete_sweep, enabled = turns > 0, onClick = { confirmClear = true }) }
            }
        }
    }
    if (pickVoice) VoiceSheet(voice, onPick = { voice = it; pickVoice = false }, onDismiss = { pickVoice = false })
    if (confirmClear) XSheet({ confirmClear = false }, "Clear history?") {
        Text("Deletes all $turns turns and their photos from this phone.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        XButton("Clear history", { vm.clearHistory(); confirmClear = false }, Modifier.fillMaxWidth(), tone = Tone.Stop, big = true)
    }
}

@Composable
private fun VoiceSheet(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) = XSheet(onDismiss, "Voice") {
    val c = Xiao.colors
    LazyColumn(Modifier.heightIn(max = 480.dp)) {
        items(listOf("") + AiSettings.VOICES) { v ->
            val on = v == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton) { onPick(v) }.padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                XIcon(if (on) R.drawable.ic_radio_button_checked_fill else R.drawable.ic_radio_button_unchecked, tint = if (on) c.accent else c.muted)
                Text(v.ifBlank { "Default" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
