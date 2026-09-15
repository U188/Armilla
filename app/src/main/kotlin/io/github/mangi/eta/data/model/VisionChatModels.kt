package io.github.mangi.eta.data.model

/** Names and gateway aliases are not evidence of image input support. Unknown fails closed. */
internal object VisionChatModels {
    fun matches(model: Model): Boolean = model.attachment ?: matches(model.modelId, model.inputModalities)

    @Suppress("UNUSED_PARAMETER")
    fun matches(modelId: String, inputModalities: List<String> = emptyList()): Boolean =
        inputModalities.any { it.equals(Model.IMAGE_MODALITY, ignoreCase = true) }
}
