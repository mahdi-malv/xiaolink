package wtf.mahdi.glassesconnect.ui.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import wtf.mahdi.glassesconnect.BuildConfig
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.ListGroup
import wtf.mahdi.glassesconnect.ui.components.ListRow
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XCard
import wtf.mahdi.glassesconnect.ui.components.XSheet
import wtf.mahdi.glassesconnect.ui.components.XTextField
import wtf.mahdi.glassesconnect.ui.theme.Xiao

data class AiState(val phase: String, val ready: Boolean, val key: String)

class AiViewModel(c: GlassesController) : ControllerViewModel(c) {
    /** [AiAssistant] phase names: Idle, Listening, Thinking, Speaking. */
    val state: StateFlow<AiState> = derive { AiState(it.aiState, it.ready, it.geminiKey) }

    fun talk() {
        val s = state.value
        when {
            s.key.isBlank() -> message("Add a Gemini API key first")
            !s.ready -> message("Connect the glasses first")
            else -> c.askAi()
        }
    }

    fun setKey(key: String) { c.setGeminiKey(key); message(if (key.isBlank()) "API key removed" else "API key saved") }
}

@Composable
fun AiScreen() {
    val controller = LocalController.current
    val vm = viewModel { AiViewModel(controller) }
    CollectMessages(vm)
    val st by vm.state.collectAsStateWithLifecycle()
    var editKey by rememberSaveable { mutableStateOf(false) }
    val c = Xiao.colors
    val (title, hint) = when (st.phase) {
        "Listening" -> "Listening" to "The glasses microphone is streaming to Gemini Live"
        "Thinking" -> "Thinking" to "Waiting for Gemini; it may take a photo with the glasses"
        "Speaking" -> "Speaking" to "The answer plays on the glasses speakers"
        else -> "Ready" to "Wake it by voice or by touch on the glasses, or tap Talk."
    }

    Column(Modifier.fillMaxSize()) {
        AppBar("Gemini") { IconBtn(R.drawable.ic_key, "API key", { editKey = true }) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Orb(st.phase, Modifier.size(172.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(hint, Modifier.padding(horizontal = 32.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
            }
            XButton(if (st.phase == "Idle") "Talk" else title, vm::talk, Modifier.fillMaxWidth(), icon = R.drawable.ic_mic_fill,
                enabled = st.phase == "Idle", big = true)
            if (st.key.isBlank()) XCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add your Gemini API key", style = MaterialTheme.typography.titleSmall)
                    Text("Create one at aistudio.google.com. It stays on this phone and goes only to Google.",
                        style = MaterialTheme.typography.bodySmall, color = c.muted)
                    XButton("Add key", { editKey = true })
                }
            }
            ListGroup {
                row { ListRow("API key", if (st.key.isBlank()) "Not set" else "••••••••" + st.key.takeLast(4), R.drawable.ic_key, monoSubtitle = true, onClick = { editKey = true }) }
                row { ListRow("Live model", BuildConfig.GEMINI_MODEL, R.drawable.ic_graphic_eq, monoSubtitle = true) }
                row { ListRow("Photo questions", BuildConfig.GEMINI_VISION_MODEL, R.drawable.ic_visibility, monoSubtitle = true) }
            }
        }
    }
    if (editKey) KeySheet(st.key, onSave = { vm.setKey(it); editKey = false }, onDismiss = { editKey = false })
}

@Composable
fun KeySheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(current) }
    XSheet(onDismiss, "Gemini API key") {
        Text("From aistudio.google.com. Stored on this phone only; sent only to Google.", style = MaterialTheme.typography.bodySmall, color = Xiao.colors.muted)
        XTextField(key, { key = it }, "API key", mono = true, password = true)
        XButton("Save", { onSave(key) }, Modifier.fillMaxWidth(), big = true)
    }
}

/** State orb: idle = small and dim, listening = pulsing ring, thinking = spinning sweep, speaking = fast beat. */
@Composable
private fun Orb(phase: String, modifier: Modifier) {
    val c = Xiao.colors
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "spin")
    val beat by t.animateFloat(0.88f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "beat")
    val base by animateFloatAsState(if (phase == "Idle") 0.72f else 0.9f, tween(400), label = "base")
    val light = Color(0xFFC9F3F8); val deep = Color(0xFF1E6F7C); val deepest = Color(0xFF0D3940)
    Canvas(modifier) {
        val r = size.minDimension / 2
        val scale = when (phase) { "Speaking" -> beat; "Listening" -> base - 0.04f * kotlin.math.sin(pulse * Math.PI.toFloat() * 2); else -> base }
        if (phase == "Listening") drawCircle(c.accent.copy(alpha = 0.8f * (1 - pulse)), r * (0.9f + 0.45f * pulse), style = Stroke(2.dp.toPx()))
        if (phase != "Idle") drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.35f), Color.Transparent), radius = r * 1.1f), r * 1.1f)
        if (phase == "Thinking") rotate(spin) {
            drawCircle(Brush.sweepGradient(listOf(light, c.accent, deep, light)), r * 0.85f)
        } else drawCircle(
            Brush.radialGradient(listOf(light, c.accent, deep, deepest), center = Offset(size.width * 0.38f, size.height * 0.32f), radius = r * scale * 1.3f),
            r * scale, alpha = if (phase == "Idle") 0.65f else 1f,
        )
    }
}
