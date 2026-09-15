package io.github.mangi.eta.data.model

/**
 * 识别「聊天里能看图」的模型。默认按模型 ID 判断；接口返回的 image 模态经常误标文本模型，不单独当真。
 * 用户在模型编辑里打开/关闭视觉开关后，以 attachment 覆盖为准。
 */
internal object VisionChatModels {
    private val POSITIVE = listOf(
        "vision",
        "-vl",
        "_vl",
        "vl-",
        "vl_",
        "vlite",
        "gpt-4o",
        "gpt-4.1",
        "gpt-4-turbo",
        "gpt-4-vision",
        "gpt-5",
        "o1",
        "o3",
        "o4-mini",
        "claude-3",
        "claude-sonnet-4",
        "claude-opus-4",
        "claude-haiku-4",
        "gemini-1.5",
        "gemini-2",
        "gemini-3",
        "gemini-flash",
        "gemini-pro",
        "gemini-exp",
        "llama-3.2",
        "llama3.2",
        "llama-4",
        "pixtral",
        "mistral-small",
        "phi-4-multimodal",
        "phi-3.5-vision",
        "qwen-vl",
        "qwen2-vl",
        "qwen2.5-vl",
        "qwen3-vl",
        "qwen3.5",
        "glm-4v",
        "glm-4.1v",
        "glm-4.5v",
        "kimi-vl",
        "step-1v",
        "step-1o",
        "internvl",
        "minicpm-v",
        "llava",
        "molmo",
        "aria",
        "nvila",
        "grok-2-vision",
        "grok-4",
        "sonar-pro",
        "sonar-reasoning-pro",
    )

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

    fun matches(modelId: String, inputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        if (ImageGenerationModels.matches(modelId)) return false
        if (NEGATIVE.any { it in id }) return false
        if (isVisionId(id)) return true
        if (POSITIVE.any { it in id }) return true
        val inputs = inputModalities.map { it.lowercase() }
        return inputs.any { it == Model.IMAGE_MODALITY } && isLikelyMultimodalChat(id)
    }

    private fun isVisionId(id: String): Boolean =
        "vision" in id ||
            "-vl" in id ||
            "_vl" in id ||
            "vl-" in id ||
            "vl_" in id ||
            id.endsWith("vl") ||
            id.endsWith("-v")

    private fun isLikelyMultimodalChat(id: String): Boolean =
        "gpt-4" in id ||
            "gpt-5" in id ||
            "claude" in id ||
            "gemini" in id ||
            "llama-4" in id ||
            "qwen3.5" in id
}
