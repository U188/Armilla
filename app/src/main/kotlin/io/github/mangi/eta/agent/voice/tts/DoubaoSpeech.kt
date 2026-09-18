package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** 豆包语音 HTTP：音频生成 create，以及 seed-tts 单向流。不走 OpenAI /audio/speech。 */
internal object DoubaoSpeech {
    const val CREATE_URL = "https://openspeech.bytedance.com/api/v3/tts/create"
    const val UNIDIRECTIONAL_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

    fun matchesModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return "seed-audio" in id || "seed-tts" in id
    }

    fun isOpenspeech(baseUrl: String): Boolean =
        baseUrl.trim().toHttpUrlOrNull()?.host.equals("openspeech.bytedance.com", ignoreCase = true)

    fun matchesEndpoint(baseUrl: String): Boolean {
        val host = baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty().lowercase()
        return isOpenspeech(baseUrl) ||
            host.endsWith(".volces.com") ||
            host == "volces.com" ||
            host.endsWith(".volcengine.com") ||
            host == "volcengine.com"
    }

    fun extraModels(provider: ProviderSetting): List<Model> {
        if (!matchesEndpoint(provider.baseUrl)) return emptyList()
        return listOf(
            speechModel("seed-tts-2.0", "豆包语音合成 2.0"),
            speechModel("seed-audio-1.0", "豆包音频生成 1.0"),
        )
    }

    fun usesCreate(modelId: String): Boolean = "seed-audio" in modelId.lowercase()

    fun resourceId(modelId: String): String {
        val id = modelId.lowercase()
        return when {
            "seed-tts-1.0-concurr" in id -> "seed-tts-1.0-concurr"
            "seed-tts-1.0" in id -> "seed-tts-1.0"
            else -> "seed-tts-2.0"
        }
    }

    fun requestId(): String = UUID.randomUUID().toString()

    fun createBody(model: String, text: String, speaker: String): String =
        JSONObject()
            .put("model", model.ifBlank { "seed-audio-1.0" })
            .put("text_prompt", text)
            .put(
                "references",
                JSONArray().put(JSONObject().put("speaker", speaker)),
            )
            .put(
                "audio_config",
                JSONObject()
                    .put("format", "mp3")
                    .put("sample_rate", 24000),
            )
            .toString()

    fun unidirectionalBody(text: String, speaker: String): String =
        JSONObject()
            .put("user", JSONObject().put("uid", "daiyu"))
            .put(
                "req_params",
                JSONObject()
                    .put("text", text)
                    .put("speaker", speaker)
                    .put(
                        "audio_params",
                        JSONObject().put("format", "mp3").put("sample_rate", 24000),
                    ),
            )
            .toString()

    private fun speechModel(id: String, displayName: String): Model =
        Model(
            id = id,
            modelId = id,
            displayName = displayName,
            ownedBy = "volcengine",
            isBuiltIn = true,
            source = ModelSource.CATALOG,
            outputModalities = listOf(Model.AUDIO_MODALITY),
        )
}
