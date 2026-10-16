package wtf.mahdi.glassesconnect.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import wtf.mahdi.glassesconnect.protocol.GlassesCommands
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Replaces XiaoAI with Gemini Live. Flow (device-verified parts in analysis/commands.md, AIVS section):
 * wake-up (glasses touch or wake word) -> AivsStatus recording -> mSBC on voice channel -> PCM to Gemini ->
 * first reply audio: STOP_CAPTURE, play reply as media (A2DP to the glasses) -> turn complete: AivsStatus idle.
 * The Gemini session stays open for follow-up questions until [IDLE_CLOSE_S] without a wake-up.
 * Vision: Gemini calls take_photo -> Aivs MultiModal START_MM_IMG -> glasses send a 576x432 JPEG on the voice
 * channel ([onImage]) -> STOP_MM_IMG (as AiSpeechEngine.postImageEnd, event 3; without it the glasses LED keeps
 * blinking) -> [GeminiVision] answers the tool's question from the photo -> text tool response. The Live session never
 * gets images: realtime video frames were applied a turn late on device (answers described the previous photo).
 * No image in [PHOTO_TIMEOUT_S] -> error response. The server ends the turn right after toolCall; that turnComplete
 * must not end our turn (answer audio follows).
 * Each finished turn goes to [onTurn] (history on the Gemini tab); turns where nothing was heard are skipped.
 */
class AiAssistant(
    /** Read on each session start, so changed settings apply from the next wake-up. */
    private val settings: () -> AiSettings,
    private val sendToGlasses: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onTurn: (AiTurn) -> Unit,
) {
    private enum class Phase { Idle, Listening, Thinking, Speaking }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "ai") }
    private val vision = GeminiVision()
    private val visionWorker = Executors.newSingleThreadExecutor { Thread(it, "ai-vision") }
    private val player = Executors.newSingleThreadExecutor { Thread(it, "ai-audio") }
    private val decoder = MsbcDecoder()
    private var gemini: GeminiLive? = null
    /** Settings of the open [gemini] session. */
    private var sessionSettings = AiSettings()
    private var session = 0L
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
    /** Voice packets sent to Gemini in this turn. */
    private var micPackets = 0
    private var thinkCheck: ScheduledFuture<*>? = null
    private var turn = TurnDraft()

    /** Glasses reported a wake-up (gesture or wake word): they are already streaming the microphone. */
    fun onWake(cmd: Int) = worker.execute { startListening("glasses wake cmd=$cmd") }

    /** User ends the turn from the phone (Stop on the Gemini tab). */
    fun stop() = worker.execute {
        if (phase == Phase.Idle) return@execute
        log("AI: stopped by user")
        stopPlayback()
        photoCall = null; awaitingAnswer = false; photoTimeout?.cancel(false)
        turn.error = turn.error ?: "Stopped"
        finishTurn()
    }

    /** Settings changed: close the open session so the next wake-up uses them. */
    fun resetSession() = worker.execute {
        if (gemini == null) return@execute
        log("AI: settings changed; closing the Gemini session")
        gemini?.close(); gemini = null
        if (phase != Phase.Idle) {
            stopPlayback()
            photoCall = null; awaitingAnswer = false; photoTimeout?.cancel(false)
            turn.error = turn.error ?: "Settings changed"
            finishTurn()
        }
    }

    /** Glasses ended the microphone stream (STOP_CAPTURE instruction, e.g. own timeout or video recording). */
    fun onStopCapture() = worker.execute {
        if (phase != Phase.Listening) return@execute
        if (micPackets == 0) {
            // Gemini heard nothing, so it never answers: without this the turn stayed in Thinking.
            log("AI: glasses stopped the microphone before any audio")
            return@execute finishTurn()
        }
        log("AI: glasses stopped the microphone; waiting for Gemini")
        gemini?.endAudio()
        setPhase(Phase.Thinking)
        thinkCheck?.cancel(false)
        thinkCheck = worker.schedule({
            if (phase != Phase.Thinking || photoCall != null || awaitingAnswer) return@schedule
            log("AI: no answer from Gemini in ${THINK_TIMEOUT_S}s")
            turn.error = "Gemini did not answer"
            finishTurn()
        }, THINK_TIMEOUT_S, TimeUnit.SECONDS)
    }

    /** Voice channel packet (VoiceMassData). Only FIRST_VOICE (1) / FOLLOW_VOICE (2) DATA packets carry the question. */
    fun onVoice(data: ByteArray) {
        if (data.size <= 12 || data[1].toInt() != 2) return
        val type = ((data[4].toInt() and 255) shl 8) or (data[5].toInt() and 255)
        if (type != 1 && type != 2) return
        worker.execute {
            if (phase != Phase.Listening) return@execute
            micPackets++
            val pcm = decoder.decode(data.copyOfRange(12, data.size))
            if (pcm.isNotEmpty()) gemini?.sendAudio(pcm)
        }
    }

    /** Complete AI image (JPEG) from the glasses; [file] is its saved copy in files/ai-images. */
    fun onImage(jpeg: ByteArray, file: File) = worker.execute {
        val call = photoCall ?: return@execute log("AI: image without a pending take_photo call; ignored")
        photoCall = null; photoTimeout?.cancel(false)
        sendToGlasses(GlassesCommands.aivsMultiModal(2))
        turn.photo = file.absolutePath
        val s = sessionSettings
        log("AI: asking ${s.vision} about the photo (${jpeg.size} B): ${call.question}")
        val started = System.currentTimeMillis()
        visionWorker.execute {
            val result = runCatching { vision.ask(s.key, s.vision, jpeg, call.question) }
            worker.execute {
                val response = result.fold(
                    { log("AI vision (${System.currentTimeMillis() - started} ms): $it"); JSONObject().put("result", it) },
                    {
                        log("AI vision failed: ${it.message}")
                        turn.error = "Could not analyse the photo"
                        JSONObject().put("error", "Could not analyse the photo.")
                    })
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
            turn.error = "Gemini did not answer"
            if (phase != Phase.Idle) finishTurn()
        }, ANSWER_TIMEOUT_S, TimeUnit.SECONDS)
    }

    fun close() = worker.execute {
        gemini?.close(); gemini = null
        stopPlayback()
        player.shutdown(); visionWorker.shutdown(); worker.shutdown()
    }

    private fun startListening(source: String) {
        val s = settings()
        if (s.key.isBlank()) return log("AI: no Gemini API key; set it on the Gemini tab")
        idleClose?.cancel(false)
        // A wake-up during an answer starts a new turn.
        if (phase != Phase.Idle) commitTurn()
        if (phase == Phase.Speaking) stopPlayback()
        heard.clear(); said.clear()
        decoder.reset()
        micPackets = 0; thinkCheck?.cancel(false)
        if (gemini?.open != true) {
            log("AI: connecting to Gemini (${s.live})")
            sessionSettings = s
            session = System.currentTimeMillis()
            gemini = GeminiLive(s.key, s.live, s.voice, s.systemPrompt(SYSTEM_PROMPT), callbacks).also { it.connect() }
        }
        turn = TurnDraft()
        log("AI: listening ($source)")
        setPhase(Phase.Listening)
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
            if (name != GeminiLive.TOOL_TAKE_PHOTO && name != GeminiLive.TOOL_CAPTURE_PHOTO) {
                gemini?.sendToolResponse(id, name, JSONObject().put("error", "Unknown function $name"))
                return@execute
            }
            if (phase == Phase.Listening) {
                sendToGlasses(GlassesCommands.aivsStopCapture())
                setPhase(Phase.Thinking)
            }
            if (name == GeminiLive.TOOL_CAPTURE_PHOTO) return@execute capturePhoto(id, name)
            log("AI: Gemini asked for a photo")
            photoCall = PhotoCall(id, name, args.optString("question").ifBlank { heard.toString().trim().ifBlank { "What do you see?" } })
            sendToGlasses(GlassesCommands.aivsMultiModal(1))
            photoTimeout?.cancel(false)
            photoTimeout = worker.schedule({
                if (photoCall?.id != id) return@schedule
                photoCall = null
                sendToGlasses(GlassesCommands.aivsMultiModal(2))
                log("AI: no photo from the glasses in ${PHOTO_TIMEOUT_S}s")
                turn.error = "The camera returned no photo"
                answerToolCall(id, name, JSONObject().put("error", "The camera did not return a photo."))
            }, PHOTO_TIMEOUT_S, TimeUnit.SECONDS)
        }
        override fun onInterrupted() = worker.execute { log("AI: interrupted"); stopPlayback() }
        override fun onTurnComplete() = worker.execute {
            if (heard.isNotBlank()) log("AI you: ${heard.toString().trim()}")
            if (said.isNotBlank()) log("AI gemini: ${said.toString().trim()}")
            turn.take(heard, said)
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
            if (phase != Phase.Idle) {
                turn.error = turn.error ?: "Gemini closed the session: $reason"
                finishTurn()
            }
        }
    }

    /**
     * Untested: capture_photo tool -> MIoT take-photo (siid 2, aiid 3; the same command as the Device tab Photo button).
     * The glasses store a full-size photo; the New media event brings its thumbnail to the Gallery tab.
     * Not verified whether the camera accepts the action during an AI turn.
     */
    private fun capturePhoto(id: String, name: String) {
        if (GlassesCommands.misDid.isEmpty()) {
            log("AI: capture_photo without a MIS did")
            turn.error = "Camera not ready: press the glasses camera button once"
            return answerToolCall(id, name, JSONObject().put("error",
                "The camera is not ready: the user must press the glasses camera button once first."))
        }
        log("AI: Gemini asked to save a photo")
        sendToGlasses(GlassesCommands.misAction(2, 3))
        turn.captured = true
        answerToolCall(id, name, JSONObject().put("result", "Photo taken and saved to the user's gallery."))
    }

    private fun finishTurn() {
        thinkCheck?.cancel(false)
        commitTurn()
        // The glasses keep the mic open after a photo unless told to stop.
        sendToGlasses(GlassesCommands.aivsStopCapture())
        setPhase(Phase.Idle)
        idleClose?.cancel(false)
        idleClose = worker.schedule({
            if (phase == Phase.Idle) { gemini?.close(); gemini = null; log("AI: Gemini session closed (idle)") }
        }, IDLE_CLOSE_S, TimeUnit.SECONDS)
    }

    /** Sends the current turn to the history once; empty turns (nothing heard, nothing said) are dropped. */
    private fun commitTurn() {
        turn.take(heard, said)
        val t = turn
        turn = TurnDraft()
        if (t.question.isBlank() && t.answer.isBlank() && t.photo == null && !t.captured) return
        onTurn(AiTurn(t.started, session, t.question.toString().trim(), t.answer.toString().trim(), t.photo, t.captured, t.error))
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

    /** History entry being built; a tool call spans several Gemini turnCompletes. */
    private class TurnDraft {
        val started = System.currentTimeMillis()
        val question = StringBuilder()
        val answer = StringBuilder()
        var photo: String? = null
        var captured = false
        var error: String? = null

        /** Moves transcripts collected since the last turnComplete into the entry. */
        fun take(heard: StringBuilder, said: StringBuilder) {
            if (heard.isNotBlank()) question.append(if (question.isEmpty()) "" else " ").append(heard.trim())
            if (said.isNotBlank()) answer.append(if (answer.isEmpty()) "" else " ").append(said.trim())
            heard.clear(); said.clear()
        }
    }

    companion object {
        const val IDLE_CLOSE_S = 120L
        const val PHOTO_TIMEOUT_S = 5L
        const val ANSWER_TIMEOUT_S = 12L
        const val THINK_TIMEOUT_S = 15L
        const val SYSTEM_PROMPT = "You are a voice assistant running on smart glasses. The user hears you through small " +
            "speakers, so answer briefly and conversationally, in the language the user speaks. No markdown or lists. " +
            "The glasses have a camera: when a question is about what the user sees, call take_photo with the question and " +
            "answer from its result. Call it again for every new visual question; never reuse an earlier result for a new view. " +
            "take_photo does not keep the photo. When the user asks you to take, snap or save a picture, call capture_photo; " +
            "never say a picture was taken or saved unless capture_photo returned a result."
    }
}
