package wtf.mahdi.glassesconnect.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.theme.Xiao
import java.io.File

/** Square tile: phone file via MediaStore uri, glasses item via its thumbnail (faded, cloud badge). */
@Composable
fun MediaThumb(item: GalleryItem, onClick: () -> Unit, modifier: Modifier = Modifier, corner: Dp = 0.dp) {
    val c = Xiao.colors
    val model: Any? = when (item) {
        is GalleryItem.Phone -> item.uri
        is GalleryItem.Glasses -> item.thumb?.let(::File)
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).background(c.surface2).clickable(onClick = onClick)) {
        if (model != null) AsyncImage(
            model, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (item is GalleryItem.Glasses) 0.55f else 1f),
        ) else XIcon(if (item.isVideo) R.drawable.ic_videocam else R.drawable.ic_photo_camera, Modifier.align(Alignment.Center), tint = c.muted)
        if (item is GalleryItem.Glasses) Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) { XIcon(R.drawable.ic_cloud, tint = Color.White, size = 15.dp) }
        if (item.isVideo) Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            XIcon(R.drawable.ic_play_arrow_fill, tint = Color.White, size = 16.dp)
            item.durationMs?.let {
                Text(formatDuration(it), color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 4f)))
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
