package io.github.mangi.eta.data.model

/**
 * 识别专用 TTS 模型以隔离聊天选择器；名称不代表支持 OpenAI Speech 协议。
 */
internal object SpeechSynthesisModels {
    fun allowsSpeechEndpoint(provider: ProviderSetting): Boolean =
        provider !is AnthropicProviderSetting && !ProviderAuthMode.isOAuth(provider.authMode) &&
            !provider.baseUrl.contains("chatgpt.com", ignoreCase = true) && provider.isEnabled

    private val ID_MARKERS = listOf(
        "tts",
        "cosyvoice",
        "sambert",
        "speech-01",
        "speech_01",
        "gpt-4o-mini-tts",
        "fish-speech",
        "index-tts",
        "f5-tts",
        "openvoice",
        "bark-tts",
        "seed-audio",
    )
    private val STT_MARKERS = listOf(
        "asr", "whisper", "paraformer", "sensevoice", "gummy", "transcribe", "stt",
    )

    fun matches(model: Model): Boolean = matches(model.modelId, model.outputModalities)

    fun matches(modelId: String, outputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        if (STT_MARKERS.any { it in id }) return false
        return ID_MARKERS.any { it in id }
    }
}
