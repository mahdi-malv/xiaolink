package wtf.mahdi.glassesconnect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.UiState
import wtf.mahdi.glassesconnect.protocol.GlassesSettings
import wtf.mahdi.glassesconnect.ui.components.LocalSnack
import wtf.mahdi.glassesconnect.ui.components.splitNote

val LocalController = staticCompositionLocalOf<GlassesController> { error("No GlassesController") }

/** Screen ViewModel over the process-wide [GlassesController]. [messages] go to the app snackbar. */
abstract class ControllerViewModel(protected val c: GlassesController) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    protected fun message(text: String) { _messages.trySend(text) }

    protected fun <T> derive(f: (UiState) -> T): StateFlow<T> =
        c.ui.map(f).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), f(c.ui.value))

    /**
     * Writes one setting at once (no Apply step): edit, send the group, wait for the re-read (the controller
     * reads all settings ~0.8 s after a write) and report whether the glasses kept the new value.
     */
    fun writeSetting(group: GlassesSettings.Group, item: GlassesSettings.Item, value: Long) {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        c.editSetting(group, item, value)
        c.applySettings(group)
        val name = splitNote(item.label).first
        viewModelScope.launch {
            val reread = withTimeoutOrNull(5_000) { c.ui.first { it.settingsDirty.isEmpty() && it.settings != null } }
            when {
                reread == null -> message("No reply from the glasses")
                GlassesSettings.get(reread.settings!!, group, item) == value -> message("$name saved")
                else -> message("The glasses kept the old $name")
            }
        }
    }
}

/** Shows a ViewModel's [ControllerViewModel.messages] in the snackbar. */
@Composable
fun CollectMessages(vm: ControllerViewModel) {
    val snack = LocalSnack.current
    LaunchedEffect(vm) { vm.messages.collect { snack(it) } }
}
