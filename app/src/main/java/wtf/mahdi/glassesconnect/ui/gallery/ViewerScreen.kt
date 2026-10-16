package wtf.mahdi.glassesconnect.ui.gallery

import android.content.Intent
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.LocalSnack
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.components.XSheet
import wtf.mahdi.glassesconnect.ui.components.XTextField
import wtf.mahdi.glassesconnect.ui.theme.Xiao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen black viewer over the combined gallery list; swipe between items. */
@Composable
fun ViewerScreen(startKey: String) {
    val controller = LocalController.current
    val context = LocalContext.current
    val vm = viewModel { ViewerViewModel(controller, PhoneGallery.get(context), context.applicationContext) }
    CollectMessages(vm)
    val items by vm.items.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val snack = LocalSnack.current
    LightStatusBarIcons()

    val start = remember { items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    var asking by rememberSaveable { mutableStateOf(false) }
    val item = items.getOrNull(pager.currentPage)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_close, "Close", nav::back, tint = Color.White)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (item != null) {
                    Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(item.takenAt)),
                        color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(meta(item), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
            }
            IconBtn(R.drawable.ic_info, "Details", { item?.let { snack(details(it)) } }, tint = Color.White)
        }
        if (items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Nothing to show", color = Color.White.copy(alpha = 0.7f))
        } else HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { items[it].key }) { page ->
            Page(items[page], active = page == pager.currentPage)
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val phone = item as? GalleryItem.Phone
            ViewerAction("Share", R.drawable.ic_share, enabled = phone != null) {
                phone?.let {
                    val send = Intent(Intent.ACTION_SEND).setType(if (it.isVideo) "video/*" else "image/*")
                        .putExtra(Intent.EXTRA_STREAM, it.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
            ViewerAction("Ask Gemini", R.drawable.ic_auto_awesome, enabled = phone != null && !phone.isVideo, tint = Color(0xFF8FE3EE)) {
                vm.resetAnswer(); asking = true
            }
            ViewerAction("Open", R.drawable.ic_photo_library, enabled = phone != null) {
                phone?.let {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, if (it.isVideo) "video/*" else "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { snack("No app can open this file") }
                }
            }
        }
    }
    val phone = item as? GalleryItem.Phone
    if (asking && phone != null) AskSheet(vm, phone) { asking = false }
}

@Composable
private fun Page(item: GalleryItem, active: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (item) {
            is GalleryItem.Phone -> if (item.isVideo && active) VideoPlayer(item) else AsyncImage(item.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            is GalleryItem.Glasses -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (item.thumb != null) AsyncImage(File(item.thumb), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                Text("Still on the glasses. This is a small preview;\nSync copies the full file to this phone.",
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun VideoPlayer(item: GalleryItem.Phone) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(item.uri)); prepare(); playWhenReady = true }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1); this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ViewerAction(label: String, icon: Int, enabled: Boolean, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val a = if (enabled) 1f else 0.35f
        XIcon(icon, tint = tint.copy(alpha = a))
        Text(label, color = Color.White.copy(alpha = a), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AskSheet(vm: ViewerViewModel, item: GalleryItem.Phone, onDismiss: () -> Unit) {
    val c = Xiao.colors
    var question by rememberSaveable { mutableStateOf("What is in this photo?") }
    val answer by vm.answer.collectAsStateWithLifecycle()
    XSheet(onDismiss, "Ask Gemini about this photo") {
        if (!vm.hasKey) Text("Add a Gemini API key on the Gemini tab first.", color = c.rec, style = MaterialTheme.typography.bodyMedium)
        XTextField(question, { question = it }, "Question")
        when (val a = answer) {
            ViewerViewModel.Answer.Busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                Text("Asking Gemini…", color = c.muted)
            }
            is ViewerViewModel.Answer.Text -> Text(a.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(14.dp),
                style = MaterialTheme.typography.bodyLarge)
            is ViewerViewModel.Answer.Error -> Text(a.text, color = c.rec, style = MaterialTheme.typography.bodyMedium)
            ViewerViewModel.Answer.Idle -> Unit
        }
        XButton("Ask", { vm.ask(item, question) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_auto_awesome,
            enabled = vm.hasKey && answer != ViewerViewModel.Answer.Busy, big = true)
    }
}

/** White status bar icons on the black viewer; restores the theme default on exit. */
@Composable
private fun LightStatusBarIcons() {
    val activity = LocalActivity.current ?: return
    val dark = Xiao.colors.isDark
    DisposableEffect(activity) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.isAppearanceLightStatusBars = false
        onDispose { ctl.isAppearanceLightStatusBars = !dark }
    }
}

private fun meta(item: GalleryItem) = when (item) {
    is GalleryItem.Phone -> listOfNotNull(if (item.isVideo) "Video" else "Photo",
        if (item.width > 0) "${item.width} × ${item.height}" else null, item.durationMs?.let(::formatDuration)).joinToString(" · ")
    is GalleryItem.Glasses -> (if (item.isVideo) "Video" else "Photo") + " · on the glasses"
}

private fun details(item: GalleryItem) = when (item) {
    is GalleryItem.Phone -> "${item.name} · ${"%.1f".format(item.size / 1_000_000.0)} MB"
    is GalleryItem.Glasses -> "${item.file.id} · ${item.file.kind} · ${item.file.size / 1024} KB"
}
