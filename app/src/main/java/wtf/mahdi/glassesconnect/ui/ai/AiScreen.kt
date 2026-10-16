package wtf.mahdi.glassesconnect.ui.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import wtf.mahdi.glassesconnect.GlassesController
import wtf.mahdi.glassesconnect.R
import wtf.mahdi.glassesconnect.ai.AiTurn
import wtf.mahdi.glassesconnect.ui.CollectMessages
import wtf.mahdi.glassesconnect.ui.ControllerViewModel
import wtf.mahdi.glassesconnect.ui.LocalController
import wtf.mahdi.glassesconnect.ui.LocalNav
import wtf.mahdi.glassesconnect.ui.Route
import wtf.mahdi.glassesconnect.ui.components.AppBar
import wtf.mahdi.glassesconnect.ui.components.IconBtn
import wtf.mahdi.glassesconnect.ui.components.Pill
import wtf.mahdi.glassesconnect.ui.components.Tone
import wtf.mahdi.glassesconnect.ui.components.XButton
import wtf.mahdi.glassesconnect.ui.components.XCard
import wtf.mahdi.glassesconnect.ui.theme.Xiao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AiState(val phase: String, val hasKey: Boolean)

/** Turns of one Gemini Live session, oldest first. */
data class AiSession(val id: Long, val turns: List<AiTurn>)

class AiViewModel(c: GlassesController) : ControllerViewModel(c) {
    /** [wtf.mahdi.glassesconnect.ai.AiAssistant] phase names: Idle, Listening, Thinking, Speaking. */
    val state: StateFlow<AiState> = derive { AiState(it.aiState, it.aiSettings.key.isNotBlank()) }

    /** History grouped by session, newest session first. */
    val sessions: StateFlow<List<AiSession>> = c.aiHistory.turns
        .map { turns -> turns.groupBy { it.session }.map { (id, t) -> AiSession(id, t) }.asReversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun stop() = c.stopAi()
}

@Composable
fun AiScreen() {
    val controller = LocalController.current
    val vm = viewModel { AiViewModel(controller) }
    CollectMessages(vm)
    val st by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val nav = LocalNav.current

    Column(Modifier.fillMaxSize()) {
        AppBar("Gemini") { IconBtn(R.drawable.ic_tune, "Gemini settings", { nav.go(Route.AiSettings) }) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "status") { StatusCard(st, onStop = vm::stop, onSetup = { nav.go(Route.AiSettings) }) }
            if (sessions.isEmpty()) item(key = "empty") {
                Text("Conversations show up here: what you asked, what Gemini answered and the photos it looked at.",
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Xiao.colors.muted, textAlign = TextAlign.Center)
            }
            items(sessions, key = { it.id }) { s ->
                SessionCard(s, onPhoto = { nav.go(Route.AiPhoto(it)) }, onGallery = { nav.go(Route.Gallery) })
            }
        }
    }
}

@Composable
private fun StatusCard(st: AiState, onStop: () -> Unit, onSetup: () -> Unit) {
    val c = Xiao.colors
    val (title, hint) = when {
        !st.hasKey -> "Not set up" to "Add your Gemini API key to use Gemini on the glasses."
        st.phase == "Listening" -> "Listening" to "The glasses microphone is streaming to Gemini"
        st.phase == "Thinking" -> "Thinking" to "Waiting for Gemini; it may take a photo with the glasses"
        st.phase == "Speaking" -> "Speaking" to "The answer plays on the glasses speakers"
        else -> "Ready" to "Touch the temple or say the wake word to ask."
    }
    XCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Orb(if (st.hasKey) st.phase else "Idle", Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            when {
                !st.hasKey -> XButton("Set up", onSetup)
                st.phase != "Idle" -> XButton("Stop", onStop, icon = R.drawable.ic_stop_fill, tone = Tone.Stop)
            }
        }
    }
}

@Composable
private fun SessionCard(session: AiSession, onPhoto: (String) -> Unit, onGallery: () -> Unit) {
    val c = Xiao.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(sessionTitle(session.turns.first().time), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = c.muted)
        XCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                session.turns.forEach { TurnView(it, onPhoto, onGallery) }
            }
        }
    }
}

@Composable
private fun TurnView(turn: AiTurn, onPhoto: (String) -> Unit, onGallery: () -> Unit) {
    val c = Xiao.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(turn.question.ifBlank { "(not transcribed)" }, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge, color = if (turn.question.isBlank()) c.muted else c.text)
        turn.photo?.takeIf { File(it).exists() }?.let { path ->
            AsyncImage(File(path), "Photo Gemini looked at",
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Image) { onPhoto(path) },
                contentScale = ContentScale.Crop)
        }
        if (turn.answer.isNotBlank()) Text(turn.answer,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium)
        if (turn.captured) Row(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onGallery)) {
            Pill("Photo saved · open Gallery", icon = R.drawable.ic_photo_camera, fg = c.accent, bg = c.accentSoft)
        }
        turn.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.rec) }
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(turn.time)), style = MaterialTheme.typography.labelSmall, color = c.muted)
    }
}

/** "Today, 14:03", "Yesterday, 09:12" or "Mon 13 Oct, 23:05". */
private fun sessionTitle(time: Long): String {
    val day = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) } }
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
    return when (day(System.currentTimeMillis()) - day(time)) {
        0 -> "Today, $clock"
        1 -> "Yesterday, $clock"
        else -> SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(time))
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
