package wtf.mahdi.glassesconnect.ai

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One-shot image question (generateContent, ai.google.dev/api/generate-content). Used for the take_photo tool so the
 * Live session gets only text and never an older photo.
 */
class GeminiVision(private val model: String) {
    private val client = OkHttpClient.Builder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Blocking. Returns the answer text; throws on HTTP or parse errors. */
    fun ask(apiKey: String, jpeg: ByteArray, question: String): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", "$PROMPT\nQuestion: $question")))))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "HTTP ${response.code}: ${text.take(200)}" }
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
        }
    }

    companion object {
        const val TIMEOUT_S = 15L
        const val PROMPT = "This photo was just taken by the camera on the user's smart glasses, from their point of view. " +
            "Answer the question from the photo, briefly and factually. If the photo does not show the answer, say what it does show."
    }
}
