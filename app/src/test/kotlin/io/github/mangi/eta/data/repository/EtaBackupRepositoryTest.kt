package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.withApiKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        java.io.File(context.filesDir, "backup-restore").deleteRecursively()
        io.github.mangi.eta.agent.runtime.AgentExecutionService.endBackupMaintenance()
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        AssistantRepository.init(context)
        McpServerRepository.init(context)
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        val provider = ProviderRepository.addProvider(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
                id = "backup-provider",
                name = "Backup",
                baseUrl = "https://api.example.com/v1",
                models = listOf(
                    io.github.mangi.eta.data.model.Model(
                        id = "backup-model",
                        modelId = "model-1",
                        displayName = "Model 1",
                        source = ModelSource.CATALOG,
                    ),
                ),
            ),
        ).withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# 核心记忆\n喜欢 Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "备份会话",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
            providerId = provider.id,
            modelId = provider.models.first().id,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "保留这条消息",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "保留这条消息",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        val restoredConversation = EtaDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(provider.id, restoredConversation.providerId)
        assertEquals(provider.models.first().id, restoredConversation.modelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    @Test
    fun selectiveExportSkipsUnselectedCategoriesAndImportLeavesThemUntouched() = runBlocking {
        val provider = ProviderRepository.addProvider(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
                id = "sel-provider",
                name = "Sel",
                baseUrl = "https://api.example.com/v1",
                models = listOf(
                    io.github.mangi.eta.data.model.Model(
                        id = "sel-model",
                        modelId = "model-1",
                        displayName = "Model 1",
                        source = ModelSource.CATALOG,
                    ),
                ),
            ),
        ).withApiKey("sk-sel")
        ProviderRepository.updateProvider(provider)
        val conversation = ConversationEntity(
            id = "conversation-sel",
            title = "仅对话",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
            providerId = provider.id,
            modelId = provider.models.first().id,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-sel",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "会话内容",
                ),
            ),
            contextCheckpoints = emptyList(),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )
        AgentMemoryRepository.replaceAll("# 记忆\n保留我")

        // 只备份对话，不含助手/记忆。
        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(
            context,
            output,
            EtaBackupExportOptions(includeAssistants = false, includeSkills = false, includeMcp = false),
        )
        assertEquals(1, exported.conversationCount)

        // 导入前把对话清掉、改掉记忆；导入仅对话的备份后，对话应恢复，记忆不应被触碰。
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(), messages = emptyList(), contextCheckpoints = emptyList(), state = null,
        )
        AgentMemoryRepository.replaceAll("# 记忆\n新的记忆不该被覆盖")
        val memoryBeforeImport = AgentMemoryRepository.snapshot().content
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))

        assertEquals(
            "会话内容",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        // 未被备份的助手记忆保持导入前的现值，没有被空集或备份内容覆盖。
        assertEquals(memoryBeforeImport, AgentMemoryRepository.snapshot().content)
    }

    @Test(expected = EtaBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        EtaBackupRepository.inspect(
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }
}
