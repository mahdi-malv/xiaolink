package wtf.mahdi.glassesconnect

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * Saves glasses media into the phone gallery: Pictures/GlassesConnect and Movies/GlassesConnect.
 * API 29+: MediaStore with RELATIVE_PATH, no permission. API 24-28: public dir + WRITE_EXTERNAL_STORAGE.
 */
class GallerySaver(private val context: Context) {
    private val folder = "GlassesConnect"

    fun isVideo(name: String) = name.substringAfterLast('.').lowercase() in setOf("mp4", "mov", "3gp")

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "png" -> "image/png"; "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    /** True when a file with this display name is already in our gallery folder. */
    fun exists(name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return legacyFile(name).exists()
        val collection = collection(name)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "%$folder%"), null,
        )?.use { return it.count > 0 }
        return false
    }

    /** Writes [input] as [name]; the item stays hidden (IS_PENDING) until the copy is complete. */
    fun save(name: String, takenAtMillis: Long?, input: InputStream): Uri {
        if (Build.VERSION.SDK_INT < 29) {
            val file = legacyFile(name).apply { parentFile?.mkdirs() }
            file.outputStream().use { input.copyTo(it) }
            takenAtMillis?.let { file.setLastModified(it) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime(name)), null)
            return Uri.fromFile(file)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (isVideo(name)) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/$folder")
            takenAtMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(name), values) ?: error("MediaStore insert failed for $name")
        try {
            resolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: error("Cannot open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        return uri
    }

    private fun collection(name: String): Uri =
        if (isVideo(name)) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Suppress("DEPRECATION")
    private fun legacyFile(name: String) = File(
        Environment.getExternalStoragePublicDirectory(if (isVideo(name)) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES),
        "$folder/$name",
    )
}
