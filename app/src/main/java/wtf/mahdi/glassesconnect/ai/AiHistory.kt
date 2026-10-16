package wtf.mahdi.glassesconnect.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One question and answer. [session] groups turns of one Gemini Live session (follow-ups share context). */
data class AiTurn(
    val time: Long,
    val session: Long,
    val question: String,
    val answer: String,
    /** take_photo image (files/ai-images), shown with the turn. */
    val photo: String? = null,
    /** capture_photo ran: a full-size photo went to the glasses storage / Gallery. */
    val captured: Boolean = false,
    /** Why the turn ended without a full answer. */
    val error: String? = null,
)

/** Local conversation history (files/ai-history.json), newest last, at most [MAX] turns. */
class AiHistory(private val file: File) {
    private val _turns = MutableStateFlow(load())
    val turns: StateFlow<List<AiTurn>> = _turns.asStateFlow()

    @Synchronized
    fun add(turn: AiTurn) {
        val all = _turns.value + turn
        val dropped = all.dropLast(MAX)
        dropped.forEach { t -> t.photo?.let { File(it).delete() } }
        _turns.value = all.takeLast(MAX)
        save()
    }

    @Synchronized
    fun clear() {
        _turns.value.forEach { t -> t.photo?.let { File(it).delete() } }
        _turns.value = emptyList()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _turns.value.forEach { t ->
            arr.put(JSONObject().put("time", t.time).put("session", t.session).put("q", t.question).put("a", t.answer)
                .put("photo", t.photo).put("captured", t.captured).put("error", t.error))
        }
        runCatching { file.writeText(arr.toString()) }
    }

    private fun load(): List<AiTurn> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AiTurn(o.getLong("time"), o.optLong("session"), o.optString("q"), o.optString("a"),
                o.optString("photo").ifEmpty { null }, o.optBoolean("captured"), o.optString("error").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX = 400
    }
}
