package wtf.mahdi.glassesconnect.ai

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini Live API session over WebSocket (ai.google.dev/api/live).
 * Input: 16 kHz s16le PCM. Output: PCM chunks with the rate from inlineData.mimeType (24 kHz by default).
 * Server voice activity detection decides when the user stops speaking.
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onAudio(pcm: ByteArray, sampleRate: Int)
        fun onInputText(text: String)
        fun onOutputText(text: String)
        fun onInterrupted()
        fun onTurnComplete()
        fun onClosed(reason: String)
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private val pending = ArrayList<String>()
    @Volatile var ready = false; private set
    @Volatile var open = false; private set

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        open = true
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); closed("closed $code ${reason.ifEmpty { "" }}".trim())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                closed("failure: ${t.message}" + (response?.let { " (HTTP ${it.code})" } ?: ""))
        })
    }

    fun sendAudio(pcm: ByteArray) = send(JSONObject().put("realtimeInput", JSONObject().put("audio",
        JSONObject().put("data", Base64.encodeToString(pcm, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))).toString())

    /** Tells the server the microphone is off so it can finish the turn. */
    fun endAudio() = send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())

    fun close() {
        socket?.close(1000, "bye"); socket = null
        ready = false; open = false
    }

    private fun send(message: String) = synchronized(pending) {
        if (ready) socket?.send(message) else pending.add(message)
    }

    private fun setupMessage() = JSONObject().put("setup", JSONObject()
        .put("model", "models/$model")
        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject()))

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrElse { return }
        if (msg.has("setupComplete")) {
            synchronized(pending) { ready = true; pending.forEach { socket?.send(it) }; pending.clear() }
            listener.onReady()
        }
        msg.optJSONObject("goAway")?.let { listener.onClosed("server goAway: $it") }
        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let(listener::onInputText)
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let(listener::onOutputText)
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                if (!mime.startsWith("audio/pcm")) continue
                val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                listener.onAudio(Base64.decode(inline.optString("data"), Base64.DEFAULT), rate)
            }
        }
        if (content.optBoolean("interrupted")) listener.onInterrupted()
        if (content.optBoolean("turnComplete")) listener.onTurnComplete()
    }

    private fun closed(reason: String) {
        if (!open) return
        ready = false; open = false
        listener.onClosed(reason)
    }
}
