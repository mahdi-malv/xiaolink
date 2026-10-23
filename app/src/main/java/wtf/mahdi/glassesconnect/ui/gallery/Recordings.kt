package wtf.mahdi.glassesconnect.ui.gallery

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.protocol.GlassesEvents
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.Tone
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.theme.Xiao
import java.util.Date

/** One ExoPlayer for the list (Ogg Opus plays natively); one recording plays at a time. */
@Stable
class RecordingPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    var current by mutableStateOf<Uri?>(null); private set
    var playing by mutableStateOf(false); private set
    var position by mutableLongStateOf(0); private set
    var duration by mutableLongStateOf(0); private set

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; tick() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.pause(); player.seekTo(0) }
                tick()
            }
        })
    }

    fun toggle(uri: Uri) {
        if (current == uri) { if (player.isPlaying) player.pause() else player.play(); return }
        current = uri; position = 0; duration = 0
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.play()
    }

    fun seek(ms: Long) { player.seekTo(ms); position = ms }
    fun pause() = player.pause()
    fun tick() { position = player.currentPosition; if (player.duration > 0) duration = player.duration }
    fun release() = player.release()
}

@Composable
fun rememberRecordingPlayer(): RecordingPlayer {
    val context = LocalContext.current
    val player = remember { RecordingPlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) { while (player.playing) { player.tick(); delay(200) } }
    return player
}

/** Recordings section of the Gallery grid: recorder state, unsynced glasses files, then synced files. */
fun LazyGridScope.recordings(st: GalleryState, player: RecordingPlayer, onRecord: (Boolean) -> Unit, onShare: (Recording) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "rec:control") { RecorderCard(st.ready, st.audioSince, onRecord) }
    if (st.pendingRecordings.isEmpty() && st.recordings.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyRecordings() }
    items(st.pendingRecordings, key = { "rg:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { GlassesRecordingRow(it) }
    items(st.recordings, key = { "rp:${it.uri}" }, span = { GridItemSpan(maxLineSpan) }) { RecordingRow(it, player, onShare) }
}

@Composable
private fun RecorderCard(ready: Boolean, since: Long?, onRecord: (Boolean) -> Unit) {
    val c = Xiao.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (since != null) c.recSoft else c.surface).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (since != null) {
            val elapsed by produceState(0L, since) { while (true) { value = (System.currentTimeMillis() - since) / 1000; delay(1000) } }
            Box(Modifier.size(10.dp).clip(CircleShape).background(c.rec))
            Column(Modifier.weight(1f)) {
                Text("Recording on glasses", style = MaterialTheme.typography.titleSmall, color = c.rec)
                Text(clock(elapsed * 1000), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            XButton("Stop", { onRecord(false) }, icon = R.drawable.ic_stop_fill, enabled = ready, tone = Tone.Stop)
        } else {
            XIcon(R.drawable.ic_mic, tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text("Record audio", style = MaterialTheme.typography.titleSmall)
                Text("Two-finger gesture on the glasses, or start here", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            XButton("Start", { onRecord(true) }, icon = R.drawable.ic_mic_fill, enabled = ready, tone = Tone.Ghost)
        }
    }
}

@Composable
private fun RecordingRow(rec: Recording, player: RecordingPlayer, onShare: (Recording) -> Unit) {
    val c = Xiao.colors
    val active = player.current == rec.uri
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val playing = active && player.playing
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.accent)
                    .clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play") { player.toggle(rec.uri) },
                contentAlignment = Alignment.Center,
            ) { XIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow_fill, tint = c.onAccent) }
            Column(Modifier.weight(1f)) {
                Text(title(rec.takenAt), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(listOfNotNull(rec.durationMs?.let(::clock), megabytes(rec.size)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            IconBtn(R.drawable.ic_share, "Share", { onShare(rec) }, tint = c.muted)
        }
        if (active) {
            val total = player.duration.takeIf { it > 0 } ?: rec.durationMs ?: 0
            Slider(
                value = player.position.coerceIn(0, total).toFloat(), onValueChange = { player.seek(it.toLong()) },
                valueRange = 0f..total.coerceAtLeast(1).toFloat(), modifier = Modifier.padding(end = 6.dp),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface2),
            )
            Row(Modifier.padding(end = 6.dp)) {
                Text(clock(player.position), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.muted)
                Text(clock(total), style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
        }
    }
}

@Composable
private fun GlassesRecordingRow(file: GlassesEvents.MediaFile) {
    val c = Xiao.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
            XIcon(R.drawable.ic_cloud_download, tint = c.muted)
        }
        Column(Modifier.weight(1f)) {
            Text(title(GalleryItem.millis(file.timestamp)), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(listOfNotNull("On glasses", file.duration?.let { clock(it * 1000) }, megabytes(file.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

@Composable
private fun EmptyRecordings() {
    val c = Xiao.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            XIcon(R.drawable.ic_graphic_eq, tint = c.muted, size = 30.dp)
        }
        Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
        Text("Record with the two-finger gesture on the glasses, then tap Sync to copy it to this phone.",
            style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun title(millis: Long): String {
    val context = LocalContext.current
    return "${dayLabel(millis)}, ${DateFormat.getTimeFormat(context).format(Date(millis))}"
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun megabytes(bytes: Long): String? = bytes.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_000_000.0) }
