package wtf.mahdi.glassesconnect.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.xiaoai.sbc_decoder.SBCDecoder
import org.json.JSONObject
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Replaces XiaoAI with Gemini Live. Flow (device-verified parts in analysis/commands.md, AIVS section):
 * wake-up (glasses touch or phone) -> AivsStatus recording -> mSBC on voice channel -> PCM to Gemini ->
 * first reply audio: STOP_CAPTURE, play reply as media (A2DP to the glasses) -> turn complete: AivsStatus idle.
 * The Gemini session stays open for follow-up questions until [IDLE_CLOSE_S] without a wake-up.
 * Vision: Gemini calls take_photo -> Aivs MultiModal START_MM_IMG -> glasses send a 576x432 JPEG on the voice
 * channel ([onImage]) -> STOP_MM_IMG (as AiSpeechEngine.postImageEnd, event 3; without it the glasses LED keeps
 * blinking) -> [GeminiVision] answers the tool's question from the photo -> text tool response. The Live session never
 * gets images: realtime video frames were applied a turn late on device (answers described the previous photo).
 * No image in [PHOTO_TIMEOUT_S] -> error response. The server ends the turn right after toolCall; that turnComplete
 * must not end our turn (answer audio follows).
 */
class AiAssistant(
    private val apiKey: String,
    private val model: String,
    private val visionModel: String,
    private val sendToGlasses: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val onState: (String) -> Unit,
) {
    private enum class Phase { Idle, Listening, Thinking, Speaking }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "ai") }
    private val vision = GeminiVision(apiKey, visionModel)
    private val visionWorker = Executors.newSingleThreadExecutor { Thread(it, "ai-vision") }
    private val player = Executors.newSingleThreadExecutor { Thread(it, "ai-audio") }
    private val decoder by lazy { SBCDecoder() }
    private var gemini: GeminiLive? = null
    private var phase = Phase.Idle
    private var idleClose: ScheduledFuture<*>? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var framesWritten = 0L
    private val heard = StringBuilder()
    private val said = StringBuilder()
    private var photoCall: PhotoCall? = null
    private var photoTimeout: ScheduledFuture<*>? = null
    /** Tool response sent; waiting for the answer audio. */
    private var awaitingAnswer = false

    val configured get() = apiKey.isNotBlank()

    /** Glasses reported a wake-up (gesture or wake word): they are already streaming the microphone. */
    fun onWake(cmd: Int) = worker.execute { startListening("glasses wake cmd=$cmd") }

    /** Phone-initiated question: asks the glasses to open the microphone. */
    fun askFromPhone() = worker.execute {
        if (startListening("phone")) sendToGlasses(GlassesCommands.aivsWake())
    }

    /** Glasses ended the microphone stream (STOP_CAPTURE instruction, e.g. own timeout or video recording). */
    fun onStopCapture() = worker.execute {
        if (phase != Phase.Listening) return@execute
        log("AI: glasses stopped the microphone; waiting for Gemini")
        gemini?.endAudio()
        setPhase(Phase.Thinking)
    }

    /** Voice channel packet (VoiceMassData). Only FIRST_VOICE (1) / FOLLOW_VOICE (2) DATA packets carry the question. */
    fun onVoice(data: ByteArray) {
        if (data.size <= 12 || data[1].toInt() != 2) return
        val type = ((data[4].toInt() and 255) shl 8) or (data[5].toInt() and 255)
        if (type != 1 && type != 2) return
        worker.execute {
            if (phase != Phase.Listening) return@execute
            val sbc = data.copyOfRange(12, data.size)
            val pcm = ByteArray(sbc.size * 6)
            val n = runCatching { decoder.decodeSBC(sbc, sbc.size, pcm) }.getOrElse { log("AI: SBC decode failed: ${it.message}"); 0 }
            if (n > 0) gemini?.sendAudio(pcm.copyOf(n))
        }
    }

    /** Complete AI image (JPEG) from the glasses. */
    fun onImage(jpeg: ByteArray) = worker.execute {
        val call = photoCall ?: return@execute log("AI: image without a pending take_photo call; ignored")
        photoCall = null; photoTimeout?.cancel(false)
        sendToGlasses(GlassesCommands.aivsMultiModal(2))
        log("AI: asking $visionModel about the photo (${jpeg.size} B): ${call.question}")
        val started = System.currentTimeMillis()
        visionWorker.execute {
            val result = runCatching { vision.ask(jpeg, call.question) }
            worker.execute {
                val response = result.fold(
                    { log("AI vision (${System.currentTimeMillis() - started} ms): $it"); JSONObject().put("result", it) },
                    { log("AI vision failed: ${it.message}"); JSONObject().put("error", "Could not analyse the photo.") })
                answerToolCall(call.id, call.name, response)
            }
        }
    }

    private fun answerToolCall(id: String, name: String, response: JSONObject) {
        gemini?.sendToolResponse(id, name, response)
        awaitingAnswer = true
        photoTimeout = worker.schedule({
            if (!awaitingAnswer) return@schedule
            awaitingAnswer = false
            log("AI: no answer from Gemini in ${ANSWER_TIMEOUT_S}s")
            if (phase != Phase.Idle) finishTurn()
        }, ANSWER_TIMEOUT_S, TimeUnit.SECONDS)
    }

    fun close() = worker.execute {
        gemini?.close(); gemini = null
        stopPlayback()
        player.shutdown(); visionWorker.shutdown(); worker.shutdown()
    }

    private fun startListening(source: String): Boolean {
        if (!configured) { log("AI: no Gemini key; set gemini.apiKey in local.properties and rebuild"); return false }
        if (!SBCDecoder.available) { log("AI: libsbc not available on this ABI"); return false }
        idleClose?.cancel(false)
        if (phase == Phase.Speaking) stopPlayback()
        heard.clear(); said.clear()
        if (gemini?.open != true) {
            log("AI: connecting to Gemini ($model)")
            gemini = GeminiLive(apiKey, model, SYSTEM_PROMPT, callbacks).also { it.connect() }
        }
        log("AI: listening ($source)")
        setPhase(Phase.Listening)
        return true
    }

    private val callbacks = object : GeminiLive.Listener {
        override fun onReady() = log("AI: Gemini session ready")
        override fun onInputText(text: String) { worker.execute { heard.append(text) } }
        override fun onOutputText(text: String) { worker.execute { said.append(text) } }
        override fun onAudio(pcm: ByteArray, sampleRate: Int) = worker.execute {
            if (awaitingAnswer) { awaitingAnswer = false; photoTimeout?.cancel(false) }
            if (phase == Phase.Listening || phase == Phase.Thinking) {
                // Close the glasses microphone so the reply is not picked up again.
                if (phase == Phase.Listening) sendToGlasses(GlassesCommands.aivsStopCapture())
                setPhase(Phase.Speaking)
            }
            if (phase == Phase.Speaking) player.execute { play(pcm, sampleRate) }
        }
        override fun onToolCall(id: String, name: String, args: JSONObject) = worker.execute {
            if (name != GeminiLive.TOOL_TAKE_PHOTO) {
                gemini?.sendToolResponse(id, name, JSONObject().put("error", "Unknown function $name"))
                return@execute
            }
            log("AI: Gemini asked for a photo")
            if (phase == Phase.Listening) {
                sendToGlasses(GlassesCommands.aivsStopCapture())
                setPhase(Phase.Thinking)
            }
            photoCall = PhotoCall(id, name, args.optString("question").ifBlank { heard.toString().trim().ifBlank { "What do you see?" } })
            sendToGlasses(GlassesCommands.aivsMultiModal(1))
            photoTimeout?.cancel(false)
            photoTimeout = worker.schedule({
                if (photoCall?.id != id) return@schedule
                photoCall = null
                sendToGlasses(GlassesCommands.aivsMultiModal(2))
                log("AI: no photo from the glasses in ${PHOTO_TIMEOUT_S}s")
                answerToolCall(id, name, JSONObject().put("error", "The camera did not return a photo."))
            }, PHOTO_TIMEOUT_S, TimeUnit.SECONDS)
        }
        override fun onInterrupted() = worker.execute { log("AI: interrupted"); stopPlayback() }
        override fun onTurnComplete() = worker.execute {
            if (heard.isNotBlank()) log("AI you: ${heard.toString().trim()}")
            if (said.isNotBlank()) log("AI gemini: ${said.toString().trim()}")
            heard.clear(); said.clear()
            if (photoCall != null || awaitingAnswer) return@execute
            // Report idle after the queued audio finishes playing.
            player.execute {
                drain()
                worker.execute { if (phase == Phase.Speaking || phase == Phase.Thinking) finishTurn() }
            }
        }
        override fun onClosed(reason: String) = worker.execute {
            log("AI: Gemini session closed: $reason")
            gemini = null
            photoCall = null; awaitingAnswer = false; photoTimeout?.cancel(false)
            if (phase == Phase.Listening) sendToGlasses(GlassesCommands.aivsStopCapture())
            if (phase != Phase.Idle) finishTurn()
        }
    }

    private fun finishTurn() {
        // The glasses keep the mic open after a photo unless told to stop.
        sendToGlasses(GlassesCommands.aivsStopCapture())
        setPhase(Phase.Idle)
        idleClose?.cancel(false)
        idleClose = worker.schedule({
            if (phase == Phase.Idle) { gemini?.close(); gemini = null; log("AI: Gemini session closed (idle)") }
        }, IDLE_CLOSE_S, TimeUnit.SECONDS)
    }

    private fun setPhase(p: Phase) {
        phase = p
        onState(p.name)
        // AivsStatus: voice 0 idle / 2 recording / 3 standby; tts 0 idle / 1 playing / 2 thinking.
        val (voice, tts) = when (p) {
            Phase.Idle -> 0 to 0; Phase.Listening -> 2 to 0; Phase.Thinking -> 3 to 2; Phase.Speaking -> 3 to 1
        }
        sendToGlasses(GlassesCommands.aivsStatus(voice, tts))
    }

    // Runs on the player thread.
    private fun play(pcm: ByteArray, rate: Int) {
        if (track == null || trackRate != rate) {
            track?.release()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, rate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
                .also { it.play() }
            trackRate = rate; framesWritten = 0
        }
        track?.write(pcm, 0, pcm.size)
        framesWritten += pcm.size / 2
    }

    // Runs on the player thread: waits until written audio has played (max 30 s).
    private fun drain() {
        val t = track ?: return
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && (t.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten) Thread.sleep(50)
    }

    private fun stopPlayback() = player.execute {
        track?.let { it.pause(); it.flush(); it.release() }
        track = null; trackRate = 0; framesWritten = 0
    }

    private class PhotoCall(val id: String, val name: String, val question: String)

    companion object {
        const val IDLE_CLOSE_S = 120L
        const val PHOTO_TIMEOUT_S = 5L
        const val ANSWER_TIMEOUT_S = 12L
        const val SYSTEM_PROMPT = "You are a voice assistant running on smart glasses. The user hears you through small " +
            "speakers, so answer briefly and conversationally, in the language the user speaks. No markdown or lists. " +
            "The glasses have a camera: when a question is about what the user sees, call take_photo with the question and " +
            "answer from its result. Call it again for every new visual question; never reuse an earlier result for a new view."
    }
}
