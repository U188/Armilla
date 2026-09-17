package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import org.junit.Test

class RemovedProviderClientTest {
    @Test(expected = IllegalArgumentException::class)
    fun oldRuntimeSnapshotCannotFallThroughToChatCompletions() {
        ProviderClientFactory.getClient(config("https://proxy.example/v1", "antigravity"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oldHostCannotFallThroughToResponses() {
        ProviderClientFactory.getClient(config("https://daily-cloudcode-pa.googleapis.com", OpenAiEndpointMode.RESPONSES))
    }

    private fun config(url: String, endpoint: String) = AgentModelClient.ModelConfig(
        providerId = "old-provider", providerName = "Old provider",
        providerType = ProviderTypes.OPENAI_COMPATIBLE,
        baseUrl = url, apiKey = "test", model = "old-model", systemPrompt = "",
        openAiEndpointMode = endpoint,
    )
}
