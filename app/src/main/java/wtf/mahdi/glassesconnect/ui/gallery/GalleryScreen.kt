package wtf.mahdi.glassesconnect.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.Permissions
import wtf.mahdi.glassesconnect.ui.Route
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.ChipRow
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XIcon
import wtf.mahdi.glassesconnect.ui.components.XLink
import wtf.mahdi.glassesconnect.ui.theme.Xiao
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen() {
    val controller = LocalController.current
    val context = LocalContext.current
    val vm = viewModel { GalleryViewModel(controller, PhoneGallery.get(context)) }
    CollectMessages(vm)
    val st by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val needsMediaPermission = remember(st.items.size) { Permissions.missing(context, Permissions.media).isNotEmpty() }

    val shown = st.items.filter { filter == 0 || (filter == 1) == !it.isVideo }
    val days = shown.groupBy { dayLabel(it.takenAt) }
    val c = Xiao.colors

    Column(Modifier.fillMaxSize()) {
        AppBar("Gallery") { IconBtn(R.drawable.ic_sync, "Sync", vm::sync, enabled = st.ready && !st.syncing) }
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChipRow(listOf("All", "Photos", "Videos"), filter) { filter = it }
                    when {
                        st.syncing -> Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(st.syncStatus.ifEmpty { "Starting sync…" }, style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
                        }
                        st.pending > 0 -> Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accentSoft).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            XIcon(R.drawable.ic_cloud_download, tint = c.accent)
                            Column(Modifier.weight(1f)) {
                                Text("${st.pending} new on your glasses", style = MaterialTheme.typography.titleSmall)
                                Text("Downloads over Wi-Fi Direct, then saves to Photos", style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            XButton("Sync", vm::sync, enabled = st.ready)
                        }
                    }
                    if (needsMediaPermission && st.items.none { it is GalleryItem.Phone }) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Synced earlier but not shown?", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.muted)
                        XLink("Allow photo access", { mediaPermission.launch(Permissions.media) })
                    }
                }
            }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyGallery() }
            days.forEach { (day, items) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h:$day") {
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp)) {
                        Text(day, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("${items.size} items", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                items(items, key = { it.key }) { item -> MediaThumb(item, { nav.go(Route.Viewer(item.key)) }) }
            }
        }
    }
}

@Composable
private fun EmptyGallery() {
    val c = Xiao.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            XIcon(R.drawable.ic_photo_library, tint = c.muted, size = 30.dp)
        }
        Text("No photos yet", style = MaterialTheme.typography.titleMedium)
        Text("Take a photo with the glasses, then tap Sync to copy it to this phone.", style = MaterialTheme.typography.bodyMedium,
            color = c.muted, textAlign = TextAlign.Center)
    }
}

private fun dayLabel(millis: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(day, today) -> "Today"
        same(day, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }) -> "Yesterday"
        day.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
    }
}
