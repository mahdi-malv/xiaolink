package wtf.mahdi.glassesconnect.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.mahdi.glassesconnect.protocol.GlassesEvents

sealed interface GalleryItem {
    val key: String
    val takenAt: Long
    val isVideo: Boolean
    /** Duration in ms, when known. */
    val durationMs: Long?

    /** Full-size file synced into the phone gallery (Pictures|Movies/GlassesConnect). */
    data class Phone(val uri: Uri, val name: String, override val takenAt: Long, override val isVideo: Boolean,
                     override val durationMs: Long?, val width: Int, val height: Int, val size: Long) : GalleryItem {
        override val key get() = "p:$uri"
    }

    /** Reported by the glasses, not synced yet; [thumb] is the mass-channel thumbnail file if downloaded. */
    data class Glasses(val file: GlassesEvents.MediaFile, val thumb: String?) : GalleryItem {
        override val key get() = "g:${file.id}"
        override val takenAt get() = millis(file.timestamp)
        override val isVideo get() = file.type in VIDEO_TYPES
        override val durationMs get() = file.duration?.times(1000)
    }

    companion object {
        /** GlassesEvents.MEDIA_TYPES: 2 MP4, 3 QuickTime, 10 MP4 meta. Audio (4..7) is not gallery media. */
        val VIDEO_TYPES = setOf(2, 3, 10)
        val AUDIO_TYPES = setOf(4, 5, 6, 7)

        /** MediaFile.timestamp to ms; unit unconfirmed (s or ms). */
        fun millis(timestamp: Long) = if (timestamp > 1_000_000_000_000L) timestamp else timestamp * 1000

        /** Recordings the glasses reported and the phone has not synced yet, newest first. */
        fun pendingRecordings(pending: List<GlassesEvents.MediaFile>) = pending.filter { it.type in AUDIO_TYPES }.sortedByDescending { it.timestamp }

        /** Phone items plus unsynced glasses items, newest first. */
        fun combine(phone: List<Phone>, pending: List<GlassesEvents.MediaFile>, thumbs: Map<String, String>): List<GalleryItem> =
            (phone + pending.filter { it.type !in AUDIO_TYPES }.map { Glasses(it, thumbs[it.id]) }).sortedByDescending { it.takenAt }
    }
}

/** Audio recording synced into Recordings|Music/GlassesConnect (Ogg Opus). */
data class Recording(val uri: Uri, val name: String, val takenAt: Long, val durationMs: Long?, val size: Long)

/** Reads the app's gallery folder from MediaStore. Process-wide so Gallery and Viewer share one list. */
class PhoneGallery private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<GalleryItem.Phone>>(emptyList())
    val items: StateFlow<List<GalleryItem.Phone>> = _items.asStateFlow()
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun refresh() {
        scope.launch {
            _items.value = runCatching { query() }.getOrDefault(_items.value)
            _recordings.value = runCatching { queryRecordings() }.getOrDefault(_recordings.value)
        }
    }

    private fun query(): List<GalleryItem.Phone> {
        val volume = if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL else "external"
        return listOf(
            MediaStore.Images.Media.getContentUri(volume) to false,
            MediaStore.Video.Media.getContentUri(volume) to true,
        ).flatMap { (collection, video) -> query(collection, video) }.sortedByDescending { it.takenAt }
    }

    @Suppress("DEPRECATION")
    private fun query(collection: Uri, video: Boolean): List<GalleryItem.Phone> {
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE, if (video) MediaStore.Video.VideoColumns.DURATION else MediaStore.MediaColumns._ID,
        )
        val out = mutableListOf<GalleryItem.Phone>()
        context.contentResolver.query(collection, columns, "$pathColumn LIKE ?", arrayOf("%/$FOLDER/%"), null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val taken = cur.getLong(2).takeIf { it > 0 } ?: (cur.getLong(3) * 1000)
                out += GalleryItem.Phone(
                    uri = ContentUris.withAppendedId(collection, id), name = cur.getString(1).orEmpty(), takenAt = taken,
                    isVideo = video, durationMs = if (video) cur.getLong(7).takeIf { it > 0 } else null,
                    width = cur.getInt(4), height = cur.getInt(5), size = cur.getLong(6),
                )
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun queryRecordings(): List<Recording> {
        val collection = MediaStore.Audio.Media.getContentUri(if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL else "external")
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.Audio.AudioColumns.DURATION, MediaStore.MediaColumns.SIZE,
        )
        val out = mutableListOf<Recording>()
        context.contentResolver.query(collection, columns, "$pathColumn LIKE ?", arrayOf("%/$FOLDER/%"), null)?.use { cur ->
            while (cur.moveToNext()) {
                out += Recording(
                    uri = ContentUris.withAppendedId(collection, cur.getLong(0)), name = cur.getString(1).orEmpty(),
                    takenAt = cur.getLong(2).takeIf { it > 0 } ?: (cur.getLong(3) * 1000),
                    durationMs = cur.getLong(4).takeIf { it > 0 }, size = cur.getLong(5),
                )
            }
        }
        return out.sortedByDescending { it.takenAt }
    }

    companion object {
        /** Same folder as [wtf.mahdi.glassesconnect.GallerySaver]. */
        const val FOLDER = "GlassesConnect"
        @Volatile private var instance: PhoneGallery? = null
        fun get(context: Context): PhoneGallery = instance ?: synchronized(this) {
            instance ?: PhoneGallery(context.applicationContext).also { instance = it; it.refresh() }
        }
    }
}
