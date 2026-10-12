package wtf.mahdi.glassesconnect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Full-size media over Wi-Fi Direct, per official app (MiWearWiFiP2PConfigHandler, DeviceMediaDataHandler, MediaDownloadHelper).
 * Phone = group owner; glasses join after EnableWiFiP2PGC (System 99) and serve HTTP on port 8080.
 * The official app DELETEs each file after download; this class never does.
 */
@RequiresApi(29)
@SuppressLint("MissingPermission")
class MediaSync(context: Context) {
    data class Group(val ssid: String, val password: String, val frequency: Int)
    data class RemoteFile(
        val identifier: String, val fileName: String, val url: String, val mimeType: String,
        val size: Long, val md5: String, val fileAdded: Long,
    ) {
        /** Plain files can be saved as-is; zip/folder bundles need Xiaomi's native algoprocessor. */
        val plain: Boolean get() = !mimeType.endsWith("/zip") && !mimeType.endsWith("/folder")
        val name: String get() = fileName.ifEmpty { url.substringAfterLast('/') }
    }

    private val manager = context.getSystemService(WifiP2pManager::class.java) ?: error("Wi-Fi Direct not supported")
    private val channel = manager.initialize(context, Looper.getMainLooper(), null)

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) = suspendCancellableCoroutine { c ->
        block(object : WifiP2pManager.ActionListener {
            override fun onSuccess() = c.resume(Unit)
            override fun onFailure(reason: Int) = c.resumeWithException(IllegalStateException("Wi-Fi P2P failure reason=$reason"))
        })
    }

    suspend fun removeGroup() = runCatching { action { manager.removeGroup(channel, it) } }

    suspend fun createGroup(): Group {
        removeGroup()
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val group = Group(
            ssid = "DIRECT-" + java.util.UUID.randomUUID().toString().take(8),
            password = (1..8).map { chars.random() }.joinToString(""),
            frequency = listOf(5745, 5765, 5785, 5805).random(),
        )
        val config = WifiP2pConfig.Builder()
            .setNetworkName(group.ssid).setPassphrase(group.password)
            .setGroupOperatingFrequency(group.frequency).build()
        action { manager.createGroup(channel, config, it) }
        return group
    }

    fun close() { if (Build.VERSION.SDK_INT >= 27) channel.close() }

    companion object {
        private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 15_000
        }

        fun getText(url: String) = get(url).run { try { inputStream.bufferedReader().readText() } finally { disconnect() } }

        /** GET /v1/filelists: JSON array of O95FileListResponse. */
        fun listFiles(ip: String): List<RemoteFile> = parseList(getText("http://$ip:8080/v1/filelists"))

        /** Folder bundle (image/folder, video/folder): GET /v1/<url> returns the children list. */
        fun listFolder(ip: String, folder: RemoteFile): List<RemoteFile> = parseList(getText("http://$ip:8080/v1/${folder.url}"))

        private fun parseList(text: String): List<RemoteFile> {
            val array = JSONArray(text)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RemoteFile(o.optString("identifier"), o.optString("fileName"), o.optString("url"), o.optString("mimeType"),
                    o.optLong("size"), o.optString("md5sum"), o.optLong("fileAdded"))
            }
        }

        /**
         * Picks the usable file inside a folder bundle (device 2026-10-12):
         * video/folder = one .mp4 + EIS gyro/meta (stabilisation skipped);
         * image/folder = N full-res .heic frames for Xiaomi multi-frame merge (we keep one ev0 frame).
         * Returns the child and the gallery name.
         */
        fun pickFromFolder(folder: RemoteFile, children: List<RemoteFile>): Pair<RemoteFile, String>? {
            val ext = { f: RemoteFile -> f.url.substringAfterLast('.', "").lowercase() }
            return if (folder.mimeType.startsWith("video")) {
                children.firstOrNull { ext(it) == "mp4" }?.let { it to "${folder.name}.mp4" }
            } else {
                val frames = children.filter { ext(it) in setOf("heic", "jpg", "jpeg") }.sortedBy { it.url }
                val frame = frames.firstOrNull { "ev0_" in it.url } ?: frames.firstOrNull()
                frame?.let { it to "${folder.name}.${ext(it)}" }
            }
        }

        /** Downloads to [dest] and verifies md5sum (when given). Throws on mismatch. */
        fun download(ip: String, file: RemoteFile, dest: File, progress: (Long) -> Unit) {
            val connection = get("http://$ip:8080/v1/files/${file.url}")
            val digest = MessageDigest.getInstance("MD5")
            try {
                connection.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            out.write(buffer, 0, n); digest.update(buffer, 0, n); total += n; progress(total)
                        }
                    }
                }
            } finally { connection.disconnect() }
            val md5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (file.md5.isNotEmpty() && !file.md5.equals(md5, ignoreCase = true)) {
                dest.delete(); error("MD5 mismatch for ${file.name}: got $md5, expected ${file.md5}")
            }
        }
    }
}
