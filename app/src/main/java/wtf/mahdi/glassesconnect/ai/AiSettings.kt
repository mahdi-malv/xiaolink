package wtf.mahdi.glassesconnect.ai

import android.content.SharedPreferences
import wtf.mahdi.glassesconnect.BuildConfig

/**
 * User settings for the Gemini assistant (Gemini tab → settings). Blank model fields fall back to the build defaults
 * (local.properties gemini.model / gemini.visionModel). A changed setting applies from the next wake-up.
 */
data class AiSettings(
    val key: String = "",
    val liveModel: String = "",
    val visionModel: String = "",
    /** Gemini Live prebuilt voice name; blank = the model default. */
    val voice: String = "",
    /** Answer language, e.g. "German"; blank = the language of the question. */
    val language: String = "",
    /** Free text added to the built-in system prompt. */
    val instructions: String = "",
) {
    val live get() = liveModel.ifBlank { BuildConfig.GEMINI_MODEL }
    val vision get() = visionModel.ifBlank { BuildConfig.GEMINI_VISION_MODEL }

    /** Built-in prompt (tool rules) plus the user's language and instructions. */
    fun systemPrompt(base: String) = buildString {
        append(base)
        if (language.isNotBlank() || instructions.isNotBlank()) append("\n\nUser preferences:")
        if (language.isNotBlank()) append("\n- Always answer in ${language.trim()}.")
        if (instructions.isNotBlank()) append("\n").append(instructions.trim())
    }

    fun save(prefs: SharedPreferences) = prefs.edit()
        .putString(K_KEY, key).putString(K_LIVE, liveModel).putString(K_VISION, visionModel)
        .putString(K_VOICE, voice).putString(K_LANGUAGE, language).putString(K_INSTRUCTIONS, instructions)
        .apply()

    companion object {
        private const val K_KEY = "gemini_key"
        private const val K_LIVE = "ai_live_model"
        private const val K_VISION = "ai_vision_model"
        private const val K_VOICE = "ai_voice"
        private const val K_LANGUAGE = "ai_language"
        private const val K_INSTRUCTIONS = "ai_instructions"

        fun load(prefs: SharedPreferences) = AiSettings(
            key = prefs.getString(K_KEY, null) ?: BuildConfig.GEMINI_API_KEY,
            liveModel = prefs.getString(K_LIVE, "").orEmpty(),
            visionModel = prefs.getString(K_VISION, "").orEmpty(),
            voice = prefs.getString(K_VOICE, "").orEmpty(),
            language = prefs.getString(K_LANGUAGE, "").orEmpty(),
            instructions = prefs.getString(K_INSTRUCTIONS, "").orEmpty(),
        )

        /** Gemini Live prebuilt voices (ai.google.dev/gemini-api/docs/speech-generation#voices). */
        val VOICES = listOf(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia",
            "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix",
            "Sadachbia", "Sadaltager", "Sulafat",
        )
    }
}
