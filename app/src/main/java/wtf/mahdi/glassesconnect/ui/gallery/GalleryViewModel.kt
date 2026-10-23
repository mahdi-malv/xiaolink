package wtf.mahdi.glassesconnect.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import wtf.mahdi.glassesconnect.protocol.GlassesEvents
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import java.io.ByteArrayOutputStream

data class GalleryState(
    val items: List<GalleryItem> = emptyList(),
    val pending: Int = 0,
    val ready: Boolean = false,
    val syncing: Boolean = false,
    val syncStatus: String = "",
    val recordings: List<Recording> = emptyList(),
    /** Audio files reported by the glasses and not synced yet. */
    val pendingRecordings: List<GlassesEvents.MediaFile> = emptyList(),
    /** Phone time when the glasses recorder started; null when idle. */
    val audioSince: Long? = null,
)

class GalleryViewModel(c: GlassesController, private val gallery: PhoneGallery) : ControllerViewModel(c) {
    val state: StateFlow<GalleryState> = combine(c.ui, gallery.items, gallery.recordings) { ui, phone, recordings ->
        GalleryState(GalleryItem.combine(phone, ui.pending, ui.thumbs), ui.pending.size, ui.ready, ui.syncing, ui.syncStatus,
            recordings, GalleryItem.pendingRecordings(ui.pending), ui.audioSince)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryState())

    init {
        // Reload the phone list and report the result when a sync ends.
        viewModelScope.launch {
            c.ui.map { it.syncing }.distinctUntilChanged().drop(1).filter { !it }.collect {
                gallery.refresh()
                c.ui.value.syncStatus.takeIf { it.isNotEmpty() }?.let(::message)
            }
        }
    }

    fun refresh() = gallery.refresh()

    fun sync() {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        c.syncGallery()
    }

    /**
     * RequestRecord (Media 20) normal audio, the same recording the two-finger gesture starts. Untested on device.
     * The glasses should answer with RecordStatus (id 22); the status query is a fallback.
     */
    fun record(start: Boolean) {
        if (!c.ui.value.ready) return message("Connect the glasses first")
        c.send(GlassesCommands.catalog.first { it.key == if (start) "record_start" else "record_stop" })
        viewModelScope.launch { delay(1_500); c.send(GlassesCommands.catalog.first { it.key == "record_status" }) }
    }
}

/** Shared by the viewer: the same combined list, plus the photo question. */
class ViewerViewModel(c: GlassesController, gallery: PhoneGallery, private val context: Context) : ControllerViewModel(c) {
    val items: StateFlow<List<GalleryItem>> = combine(c.ui, gallery.items) { ui, phone -> GalleryItem.combine(phone, ui.pending, ui.thumbs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryItem.combine(gallery.items.value, c.ui.value.pending, c.ui.value.thumbs))

    sealed interface Answer { data object Idle : Answer; data object Busy : Answer; data class Text(val text: String) : Answer; data class Error(val text: String) : Answer }
    private val _answer = MutableStateFlow<Answer>(Answer.Idle)
    val answer: StateFlow<Answer> = _answer.asStateFlow()
    val hasKey get() = c.ui.value.aiSettings.key.isNotBlank()

    fun resetAnswer() { _answer.value = Answer.Idle }

    /** Downscales to <= 1536 px JPEG (the gallery file may be HEIC) and asks the Gemini vision model. */
    fun ask(item: GalleryItem.Phone, question: String) {
        _answer.value = Answer.Busy
        viewModelScope.launch {
            _answer.value = runCatching {
                withContext(Dispatchers.IO) { c.askAboutPhoto(jpeg(item), question.ifBlank { "What is in this photo?" }) }
            }.fold({ Answer.Text(it) }, { Answer.Error(it.message ?: "Gemini request failed") })
        }
    }

    private fun jpeg(item: GalleryItem.Phone): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1536) sample *= 2
        val bitmap = resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Cannot decode ${item.name}")
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
