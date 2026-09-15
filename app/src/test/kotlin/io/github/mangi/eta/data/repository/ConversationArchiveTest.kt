package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationArchiveTest {
    private val context = RuntimeEnvironment.getApplication()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        File(context.filesDir, "backup-restore").deleteRecursively()
        AgentExecutionService.endBackupMaintenance()
    }

    private fun file(name: String, content: String = name): File =
        File(TerminalPrivateStorage.workspace(context.filesDir), "imports/test-${UUID.randomUUID()}/$name")
            .apply { parentFile!!.mkdirs(); writeText(content) }

    private fun document(content: String = "hello", history: String = "[]", images: String = "[]") = EtaConversationExport(
        exportedAt = 1,
        conversation = ConversationEntity(id = "original", title = "original", thinkingEnabled = false,
            historyJson = history, createdAt = 1, updatedAt = 2, providerId = "provider", modelId = "model"),
        messages = listOf(ConversationMessageEntity(id = "original-message", conversationId = "original",
            sortIndex = 0, type = "user", content = content, imagesJson = images)),
    )

    private fun historyFile(path: String, role: String = "user", type: String = "image_file"): String =
        JSONArray().put(JSONObject().put("role", role).put("contentJson",
            JSONArray().put(JSONObject().put("type", type).put("path", path)).toString())).toString()

    private fun staged(document: EtaConversationExport, files: Map<String, File>): Map<String, File> =
        files + (EtaConversationExport.MANIFEST_NAME to file("manifest.json", json.encodeToString(document)))

    @Test fun fileBlockPreservesSpacesParenthesesAndDoesNotCapturePlainProsePaths() {
        val attachment = file("my report (final).pdf")
        val ignored = file("do-not-export.txt")
        val prompt = AgentFileReferencePromptCodec.format("Do not collect ${ignored.absolutePath}", listOf(
            AgentFileReference(attachment.name, attachment.absolutePath, AgentFileReferenceKind.File),
        ))
        val prepared = ConversationArchiveMedia.prepare(context, document(prompt))
        assertEquals(1, prepared.files.size)
        assertEquals(attachment, prepared.files.values.single())
        val restored = AgentFileReferencePromptCodec.parse(prepared.document.messages.single().content)
        assertEquals(attachment.name, restored.references.single().displayName)
        assertEquals("Do not collect ${ignored.absolutePath}", restored.request)
        assertEquals(prepared.document.attachments.single().reference, restored.references.single().absolutePath)
    }

    @Test fun collectsOnlyReferencedImagesIncludingHistoryToolMediaAndCheckpoint() {
        val preview = file("preview.jpg")
        val video = file("video.mp4")
        val toolImage = file("tool.jpg")
        val checkpoint = file("checkpoint.jpg")
        File(preview.parentFile, "orphan.jpg").writeText("deleted message")
        val images = JSONArray().put(JSONObject().put("preview", preview.absolutePath)
            .put("source", video.absolutePath).put("kind", "video")).toString()
        val original = document(images = images, history = historyFile(toolImage.absolutePath, "tool"))
            .copy(contextCheckpoint = ConversationContextCheckpointEntity("original", historyFile(checkpoint.absolutePath)))
        val prepared = ConversationArchiveMedia.prepare(context, original)
        assertEquals(setOf(preview, video, toolImage, checkpoint), prepared.files.values.toSet())
        assertEquals(4, prepared.document.attachmentCount)
        assertFalse(prepared.document.conversation.historyJson.contains(toolImage.absolutePath))
        assertFalse(prepared.document.contextCheckpoint!!.historyJson.contains(checkpoint.absolutePath))
    }

    @Test fun generatedMarkdownMigratesButCodeAndUnrelatedProseRemainLiteral() {
        val original = "![generated](/old/pic.png)\n\n[report](</old/my report (final).pdf>)\n\n" +
            "`![sample](/old/code.png)`\n\n```text\n![sample](/old/fenced.png)\n```\n\nPath: /old/prose.png"
        val seen = mutableListOf<String>()
        val rewritten = ConversationArchiveMedia.markdown(original) { path -> seen += path; path.replace("/old/", "/new/") }
        assertEquals(listOf("/old/pic.png", "/old/my report (final).pdf"), seen)
        assertTrue(rewritten.contains("![generated](/new/pic.png)"))
        assertTrue(rewritten.contains("[report](</new/my report (final).pdf>)"))
        assertTrue(rewritten.contains("/old/code.png"))
        assertTrue(rewritten.contains("/old/fenced.png"))
        assertTrue(rewritten.contains("/old/prose.png"))
    }

    @Test fun missingReferencedAttachmentFailsRatherThanProducingIncompleteArchive() {
        val missing = file("missing.jpg").apply { delete() }
        assertTrue(runCatching { ConversationArchiveMedia.prepare(context,
            document(images = JSONArray().put(missing.absolutePath).toString())) }.isFailure)
    }

    @Test fun rejectsDirectoryAndOutsidePrivateAttachmentRoot() {
        val outside = File(context.filesDir, "not-an-attachment.txt").apply { writeText("private") }
        assertTrue(runCatching { ConversationArchiveMedia.prepare(context,
            document(images = JSONArray().put(outside.absolutePath).toString())) }.isFailure)
        val prompt = AgentFileReferencePromptCodec.format("use directory", listOf(
            AgentFileReference("folder", outside.parentFile!!.absolutePath, AgentFileReferenceKind.Directory),
        ))
        assertTrue(runCatching { ConversationArchiveMedia.prepare(context, document(prompt)) }.isFailure)
    }

    @Test fun duplicateImportsAllocateDifferentConversationMessageAndAttachmentPaths() {
        val image = file("image.jpg")
        val prepared = ConversationArchiveMedia.prepare(context, document(images = JSONArray().put(image.absolutePath).toString()))
        val archive = staged(prepared.document, prepared.files)
        val first = ConversationArchiveImport.prepare(context, prepared.document, archive)
        val second = ConversationArchiveImport.prepare(context, prepared.document, archive)
        assertNotEquals("original", first.document.conversation.id)
        assertNotEquals(first.document.conversation.id, second.document.conversation.id)
        assertNotEquals(first.document.messages.single().id, second.document.messages.single().id)
        assertNotEquals(first.files.keys.single(), second.files.keys.single())
        assertEquals("provider", first.document.conversation.providerId)
        assertEquals("model", first.document.conversation.modelId)
        assertEquals(first.document.conversation.id, first.document.messages.single().conversationId)
        assertFalse(first.files.keys.single().exists()) // plan is read-only
    }

    @Test fun rejectsUnlistedMissingOrTamperedArchiveAttachmentsBeforeWriting() {
        val image = file("image.jpg", "original")
        val prepared = ConversationArchiveMedia.prepare(context, document(images = JSONArray().put(image.absolutePath).toString()))
        val archive = staged(prepared.document, prepared.files)
        assertTrue(runCatching { ConversationArchiveImport.prepare(context, prepared.document,
            archive + ("attachments/imports/extra.txt" to file("extra.txt"))) }.isFailure)
        assertTrue(runCatching { ConversationArchiveImport.prepare(context, prepared.document,
            archive - prepared.document.attachments.single().entry) }.isFailure)
        image.writeText("tampered") // same length, hash mismatch
        assertTrue(runCatching { ConversationArchiveImport.prepare(context, prepared.document, archive) }.isFailure)
    }

    @Test fun rejectsManifestPathTraversalAndDuplicateReferences() {
        val image = file("image.jpg")
        val prepared = ConversationArchiveMedia.prepare(context, document(images = JSONArray().put(image.absolutePath).toString()))
        val attachment = prepared.document.attachments.single()
        val duplicate = prepared.document.copy(attachments = listOf(attachment, attachment))
        assertTrue(runCatching { ConversationArchiveImport.prepare(context, duplicate, staged(duplicate, prepared.files)) }.isFailure)
        val bad = attachment.copy(reference = "/eta-attachments/../private", entry = "attachments/imports/../private")
        val invalid = prepared.document.copy(attachments = listOf(bad))
        assertTrue(runCatching { ConversationArchiveImport.prepare(context, invalid,
            staged(invalid, mapOf(bad.entry to image))) }.isFailure)
    }

    @Test fun legacyMigrationUsesOriginalManifestReferencesNotCurrentDeviceFiles() {
        val path = "/data/user/10/old.package/files/terminal/workspace/imports/my report (final).pdf"
        val source = file("legacy-source", "from archive")
        val original = document("[report](<$path>)").copy(schemaVersion = 1)
        val entry = "attachments/imports/my report (final).pdf"
        val plan = ConversationArchiveImport.prepare(context, original, staged(original, mapOf(entry to source)))
        assertEquals(source, plan.files.values.single())
        assertFalse(plan.document.messages.single().content.contains("old.package"))
        assertEquals(entry, ConversationArchiveImport.legacyEntry(path, "original"))
        assertNull(ConversationArchiveImport.legacyEntry("/data/user/0/other/shared_prefs/keys.xml", "original"))
        assertTrue(runCatching { ConversationArchiveImport.prepare(context, original, staged(original, emptyMap())) }.isFailure)
    }

    @Test fun legacyFileUriAndAbsoluteAliasesShareOneInstalledFile() {
        val path = "/data/user/0/old.package/cache/eta-chat-images/original/image.jpg"
        val original = document(images = JSONArray().put(path).put("file://$path").toString()).copy(schemaVersion = 1)
        val source = file("image.jpg")
        val plan = ConversationArchiveImport.prepare(context, original, staged(original,
            mapOf("attachments/chat-images/original/image.jpg" to source)))
        assertEquals(1, plan.files.size)
        val images = JSONArray(plan.document.messages.single().imagesJson)
        assertEquals(images.getString(0), images.getString(1))
    }

    @Test fun repositoryRoundTripKeepsOriginalConversationAndSupportsRepeatedImport(): Unit = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val attachment = file("test.jpg", "image-bytes")
        val original = document("![generated](${attachment.absolutePath})", historyFile(attachment.absolutePath))
        dao.importAsNewConversation(original.conversation, original.messages, null)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.exportConversation(context, "original", output)
        repeat(2) { EtaBackupRepository.import(context, output.toByteArray().inputStream()) }
        val all = dao.conversationEntities()
        assertEquals(3, all.size)
        assertEquals(original.conversation, dao.conversationEntity("original"))
        assertEquals(original.messages, dao.messagesForConversation("original"))
        assertEquals("image-bytes", attachment.readText())
        val imported = all.filter { it.id != "original" }
        for (conversation in imported) {
            val message = dao.messagesForConversation(conversation.id).single()
            assertNotEquals("original-message", message.id)
            val parts = JSONArray(JSONArray(conversation.historyJson).getJSONObject(0).getString("contentJson"))
            val path = parts.getJSONObject(0).getString("path")
            assertEquals("image-bytes", File(path).readText())
            assertTrue(message.content.contains(path))
        }
        assertFalse(File(context.filesDir, "backup-restore").exists())
        assertFalse(AgentExecutionService.backupMaintenance)
    }

    @Test fun singleImportDoesNotSnapshotOversizedUnrelatedConversations(): Unit = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val big = document("x".repeat(BackupDatabaseBudget.MAX_BYTES.toInt() + 1))
        dao.importAsNewConversation(big.conversation, big.messages, null)
        val incoming = document("small archive")
        EtaBackupRepository.import(context, json.encodeToString(incoming).byteInputStream())
        assertEquals(2, dao.conversationEntities().size)
        assertEquals(big.messages, dao.messagesForConversation("original"))
    }

    @Test fun interruptedSingleImportRecoveryRemovesOnlyTheNewConversation(): Unit = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val original = document()
        dao.importAsNewConversation(original.conversation, original.messages, null)
        val image = file("recover.jpg")
        val prepared = ConversationArchiveMedia.prepare(context, document(images = JSONArray().put(image.absolutePath).toString()))
        val plan = ConversationArchiveImport.prepare(context, prepared.document, staged(prepared.document, prepared.files))
        val operation = File(context.filesDir, "backup-restore")
        operation.mkdirs()
        durableText(File(operation, "new-conversation-id"), plan.document.conversation.id)
        val journal = BackupRestoreJournal(operation)
        journal.begin(plan.files.keys.toList())
        plan.files.forEach { (target, source) ->
            val stagedSource = File(operation, "source").apply { source.copyTo(this) }
            journal.replace(target, stagedSource)
        }
        dao.importAsNewConversation(plan.document.conversation, plan.document.messages, plan.document.contextCheckpoint)
        // Simulate death after DB commit but before publishing the commit marker.
        EtaBackupRepository.recoverInterruptedImport(context)
        EtaBackupRepository.recoverInterruptedImport(context)
        assertEquals(listOf(original.conversation), dao.conversationEntities())
        assertTrue(plan.files.keys.none { it.exists() })
        assertTrue(image.exists())
    }
}
