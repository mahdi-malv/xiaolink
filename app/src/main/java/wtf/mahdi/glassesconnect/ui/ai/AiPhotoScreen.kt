package wtf.mahdi.glassesconnect.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.XButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiPhotoViewModel(c: GlassesController) : ControllerViewModel(c) {
    fun save(path: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { c.saveAiPhoto(path) }
            .onSuccess { message("Saved to the phone gallery") }
            .onFailure { message("Save failed: ${it.message}") }
    }
}

/** Full-screen AI photo (576x432 JPEG the glasses sent for a Gemini question). */
@Composable
fun AiPhotoScreen(path: String) {
    val controller = LocalController.current
    val vm = viewModel { AiPhotoViewModel(controller) }
    CollectMessages(vm)
    val nav = LocalNav.current
    val file = File(path)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_close, "Close", nav::back, tint = Color.White)
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (file.exists()) AsyncImage(file, "Photo Gemini looked at", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Photo deleted", color = Color.White.copy(alpha = 0.7f))
        }
        XButton("Save to phone gallery", { vm.save(path) }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            icon = R.drawable.ic_download_done, enabled = file.exists(), big = true)
    }
}
