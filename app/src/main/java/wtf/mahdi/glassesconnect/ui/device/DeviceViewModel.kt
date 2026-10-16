package wtf.mahdi.glassesconnect.ui.device

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.UiState
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.gallery.GalleryItem
import wtf.mahdi.glassesconnect.ui.gallery.PhoneGallery

data class DeviceState(
    val ui: UiState,
    val recent: List<GalleryItem>,
) {
    val ready get() = ui.ready
    val recording get() = ui.recordingSince != null
    /** cameraStatus (GlassesEvents.cameraLabel): 0 idle, 1 capturing, 5 recording video, 2/3 busy. */
    val cameraBusy get() = ui.cameraStatus?.let { it != 0L && it != 5L } == true
    val needsSetup get() = ui.token.isBlank() || ui.address.isBlank()
}

class DeviceViewModel(c: GlassesController, private val gallery: PhoneGallery) : ControllerViewModel(c) {
    val state: StateFlow<DeviceState> = combine(c.ui, gallery.items) { ui, phone ->
        DeviceState(ui, GalleryItem.combine(phone, ui.pending, ui.thumbs).take(4))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceState(c.ui.value, emptyList()))

    fun connect() = c.connect()

    fun photo() {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        if (c.ui.value.recordingSince != null) return message("Stop the video first")
        if (GlassesCommands.misDid.isEmpty()) message("Press the glasses button once so the app learns the camera id")
        c.send(command("take_photo"))
    }

    /** MIoT toggle-record: the same command starts and stops; state comes back as cameraStatus 5. */
    fun video() {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        c.send(command("toggle_record"))
    }

    fun sync() {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        c.syncGallery()
    }

    fun loadSettings() { if (c.ui.value.ready && c.ui.value.settings == null) c.readSettings() }

    private fun command(key: String) = GlassesCommands.catalog.first { it.key == key }
}
