package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient {
        io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(
            config.baseUrl, config.openAiEndpointMode,
        )
        return when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
    }
}
