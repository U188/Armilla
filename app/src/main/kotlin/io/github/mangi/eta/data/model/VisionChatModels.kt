package io.github.mangi.eta.data.model

/**
 * 聊天默认按「能看图」处理，避免每个新模型都改名单。
 * 只排除已知纯文本 / 生图模型。目录的 image 模态常误标，不再单独当真。
 * 模型编辑里的「支持视觉」写入 visionOverride，优先于这里的自动判断。
 */
internal object VisionChatModels {
    private val NEGATIVE = listOf(
        "deepseek-chat",
        "deepseek-reasoner",
        "deepseek-r1",
        "deepseek-v3",
        "deepseek-v2",
        "deepseek-coder",
        "deepseek-moe",
        "r1-distill",
        "qwq",
        "qwen3-coder",
        "qwen2.5-coder",
        "codestral",
        "starcoder",
        "whisper",
        "tts",
        "embedding",
        "rerank",
        "moderation",
        "dall-e",
        "gpt-image",
        "flux",
        "stable-diffusion",
    )

    fun matches(model: Model): Boolean = matches(model.modelId, model.inputModalities)

    @Suppress("UNUSED_PARAMETER")
    fun matches(modelId: String, inputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        if (ImageGenerationModels.matches(modelId)) return false
        if (NEGATIVE.any { it in id }) return false
        return true
    }
}
