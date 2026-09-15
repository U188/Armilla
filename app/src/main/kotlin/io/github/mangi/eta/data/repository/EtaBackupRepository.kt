package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.device.RootSu
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.datastore.EtaSettingsBackup
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationFolderEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.McpServerEntity
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.db.SkillRegistryEntity
import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.agent.terminal.AndroidBusyBox
import java.io.ByteArrayInputStream
import java.io.File
import java.io.SequenceInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 代鱼用户数据备份。schema 1 只有对话、提供商和记忆；schema 2 补上助手、技能、MCP、设置、附件和可选 Linux 环境。 */
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val folders: List<ConversationFolderEntity> = emptyList(),
    val memoryMd: String = "",
    val assistantMemories: Map<String, String> = emptyMap(),
    val assistants: AssistantBackupSnapshot? = null,
    val assistantAvatars: Map<String, String> = emptyMap(),
    val skillRegistry: List<SkillRegistryEntity> = emptyList(),
    val skillFiles: Map<String, String> = emptyMap(),
    val mcpServers: List<McpServerEntity> = emptyList(),
    val mcpTokens: Map<String, String> = emptyMap(),
    val settings: EtaSettingsBackup? = null,
    val includeLinuxEnvironment: Boolean = false,
    val linuxWorkspaceIncluded: Boolean = false,
    val attachmentCount: Int = 0,
    val importedFileCount: Int = 0,
) {
    companion object {
        const val FORMAT = "eta-backup"
        const val SCHEMA_VERSION = 2
        const val MIN_SUPPORTED_SCHEMA = 1
        const val MANIFEST_NAME = "eta-backup.json"
    }
}

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

@Serializable
internal data class EtaConversationExport(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val conversation: ConversationEntity,
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoint: ConversationContextCheckpointEntity? = null,
    val attachmentCount: Int = 0,
) {
    companion object {
        const val FORMAT = "eta-conversation"
        const val SCHEMA_VERSION = 1
        const val MANIFEST_NAME = "eta-conversation.json"
    }
}

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val assistantCount: Int = 0,
    val mcpCount: Int = 0,
    val skillCount: Int = 0,
    val attachmentCount: Int = 0,
    val includedLinuxEnvironment: Boolean = false,
)

internal data class EtaBackupExportOptions(
    val includeLinuxEnvironment: Boolean = false,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object EtaBackupRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    fun linuxEnvironmentBytes(context: Context): Long {
        val appContext = context.applicationContext
        val walked = LinuxDistribution.entries.sumOf { distribution ->
            directorySize(LinuxEnvironmentPaths.environmentDir(appContext, distribution))
        }
        if (walked > 0L) return walked
        if (!RootAccess.isGranted) return 0L
        return LinuxDistribution.entries.sumOf { distribution ->
            duBytes(LinuxEnvironmentPaths.environmentDir(appContext, distribution))
        }
    }

    suspend fun export(
        context: Context,
        output: OutputStream,
        options: EtaBackupExportOptions = EtaBackupExportOptions(),
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val document = snapshot(appContext, options)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(if (options.includeLinuxEnvironment) 1 else 6)
            putText(zip, EtaBackupDocument.MANIFEST_NAME, json.encodeToString(document))
            putDirectory(zip, "attachments/chat-images/", File(appContext.cacheDir, AgentChatImageCache.CACHE_DIRECTORY))
            putDirectory(
                zip,
                "attachments/imports/",
                File(TerminalPrivateStorage.workspace(appContext.filesDir), "imports"),
            )
            putDirectory(
                zip,
                "linux/workspace/",
                TerminalPrivateStorage.workspace(appContext.filesDir),
                skipNames = setOf("imports"),
            )
            if (options.includeLinuxEnvironment) {
                exportLinuxEnvironments(appContext, zip)
            }
            zip.finish()
        }
        document.summary()
    }

    suspend fun exportConversation(
        context: Context,
        conversationId: String,
        output: OutputStream,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val document = conversationSnapshot(appContext, conversationId)
        ZipOutputStream(output).use { zip ->
            putText(zip, EtaConversationExport.MANIFEST_NAME, json.encodeToString(document))
            putDirectory(
                zip,
                "attachments/chat-images/$conversationId/",
                File(File(appContext.cacheDir, AgentChatImageCache.CACHE_DIRECTORY), conversationId),
            )
            referencedImportedFiles(appContext, document.messages, document.contextCheckpoint).forEach { file ->
                val relative = importedRelativePath(appContext, file) ?: return@forEach
                putFile(zip, "attachments/imports/$relative", file)
            }
            zip.finish()
        }
        document.summary()
    }

    suspend fun import(context: Context, input: InputStream): EtaBackupSummary =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val header = ByteArray(2)
            val read = input.read(header)
            if (read <= 0) throw EtaBackupException("备份文件为空")
            val body = SequenceInputStream(ByteArrayInputStream(header, 0, read), input)
            if (read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                importZip(appContext, body)
            } else {
                val document = decodeDocument(body.readBytes().toString(Charsets.UTF_8))
                validate(document)
                restoreMetadata(appContext, document)
                rewriteRestoredPaths(appContext, document)
                document.summary()
            }
        }

    private suspend fun importZip(context: Context, input: InputStream): EtaBackupSummary {
        var document: EtaBackupDocument? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.trimStart('/')
                when {
                    name == EtaBackupDocument.MANIFEST_NAME -> {
                        val parsed = decodeDocument(zip.readBytes().toString(Charsets.UTF_8))
                        validate(parsed)
                        restoreMetadata(context, parsed)
                        document = parsed
                    }
                    name.startsWith("attachments/chat-images/") -> {
                        extractZipFile(
                            zip,
                            File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY),
                            name.removePrefix("attachments/chat-images/"),
                        )
                    }
                    name.startsWith("attachments/imports/") -> {
                        extractZipFile(
                            zip,
                            File(TerminalPrivateStorage.workspace(context.filesDir), "imports"),
                            name.removePrefix("attachments/imports/"),
                        )
                    }
                    name.startsWith("linux/workspace/") -> {
                        extractZipFile(
                            zip,
                            TerminalPrivateStorage.workspace(context.filesDir),
                            name.removePrefix("linux/workspace/"),
                            skipNames = setOf("imports"),
                        )
                    }
                    name.matches(Regex("""^linux/environments/[^/]+/[^/]+\.tar$""")) -> {
                        val match = Regex("""^linux/environments/([^/]+)/([^/]+)\.tar$""").matchEntire(name)
                            ?: continue
                        runCatching {
                            restoreLinuxTar(context, zip, match.groupValues[1], match.groupValues[2])
                        }.getOrElse { failure ->
                            AndroidAgentLogger.error(
                                "Backup linux restore failed: backend=${match.groupValues[1]} distribution=${match.groupValues[2]} type=${failure.javaClass.simpleName} message=${failure.message}",
                            )
                            throw (failure as? EtaBackupException)
                                ?: EtaBackupException("无法导入完整 Linux 环境：${failure.message ?: failure.javaClass.simpleName}", failure)
                        }
                    }
                }
            }
        }
        val restored = document ?: throw EtaBackupException("备份文件缺少清单")
        rewriteRestoredPaths(context, restored)
        return restored.summary()
    }

    suspend fun inspect(input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) throw EtaBackupException("备份文件为空")
        val document = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name == EtaBackupDocument.MANIFEST_NAME }
                    ?.let { decodeDocument(zip.readBytes().toString(Charsets.UTF_8)) }
                    ?: throw EtaBackupException("备份文件缺少清单")
            }
        } else {
            decodeDocument(bytes.toString(Charsets.UTF_8))
        }
        validate(document)
        document.summary()
    }

    private suspend fun snapshot(
        context: Context,
        options: EtaBackupExportOptions,
    ): EtaBackupDocument {
        val database = EtaDatabase.get(context)
        val providers = database.providerDao().providers().map { provider ->
            EtaBackupProvider(provider = provider.provider, models = provider.models)
        }
        val conversations = database.conversationDao()
        val settings = SettingsDataStore.backupSnapshot()
        val mcpServers = database.mcpServerDao().servers()
        val chatImages = File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY)
        val imports = File(TerminalPrivateStorage.workspace(context.filesDir), "imports")
        return EtaBackupDocument(
            exportedAt = System.currentTimeMillis(),
            providers = providers,
            selectedProviderId = settings.selectedProviderId,
            selectedModelId = settings.selectedModelId,
            conversations = conversations.conversationEntities(),
            messages = conversations.messages(),
            contextCheckpoints = conversations.contextCheckpoints(),
            conversationState = conversations.state(),
            folders = conversations.folders(),
            memoryMd = AgentMemoryRepository.snapshot(AssistantPrompt.DEFAULT_ID).content,
            assistantMemories = AgentMemoryRepository.exportAll(),
            assistants = if (AssistantRepository.isReady()) AssistantRepository.exportSnapshot() else null,
            assistantAvatars = if (AssistantRepository.isReady()) encodeFiles(AssistantRepository.exportAvatars()) else emptyMap(),
            skillRegistry = database.skillDao().registryEntries(),
            skillFiles = encodeFiles(SkillRuntime.exportUserSkills(context)),
            mcpServers = mcpServers,
            mcpTokens = McpSecretStore(context).exportTokens(mcpServers.map { it.id }),
            settings = settings,
            includeLinuxEnvironment = options.includeLinuxEnvironment,
            linuxWorkspaceIncluded = true,
            attachmentCount = countFiles(chatImages),
            importedFileCount = countFiles(imports),
        )
    }

    private suspend fun conversationSnapshot(
        context: Context,
        conversationId: String,
    ): EtaConversationExport {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversation = dao.conversationEntity(conversationId)
            ?: throw EtaBackupException("会话不存在")
        val messages = dao.messagesForConversation(conversationId)
        val checkpoint = dao.contextCheckpoint(conversationId)
        val chatDir = File(File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY), conversationId)
        return EtaConversationExport(
            exportedAt = System.currentTimeMillis(),
            conversation = conversation,
            messages = messages,
            contextCheckpoint = checkpoint,
            attachmentCount = countFiles(chatDir) + referencedImportedFiles(context, messages, checkpoint).size,
        )
    }

    private suspend fun restoreMetadata(context: Context, document: EtaBackupDocument) {
        val database = EtaDatabase.get(context)
        database.withTransaction {
            database.providerDao().replaceAll(
                document.providers.map { provider ->
                    ProviderWithModelsSeed(provider = provider.provider, models = provider.models)
                },
            )
            database.conversationDao().replaceAll(
                conversations = document.conversations,
                messages = document.messages,
                contextCheckpoints = document.contextCheckpoints,
                state = document.conversationState,
            )
            database.conversationDao().replaceFolders(document.folders)
            if (document.schemaVersion >= 2) {
                database.mcpServerDao().replaceAll(document.mcpServers)
                database.skillDao().replaceRegistry(document.skillRegistry)
            }
        }
        AgentMemoryRepository.importAll(document.assistantMemories, document.memoryMd)
        if (document.schemaVersion >= 2) {
            if (AssistantRepository.isReady()) {
                document.assistants?.let(AssistantRepository::importSnapshot)
                AssistantRepository.importAvatars(decodeFiles(document.assistantAvatars))
            }
            SkillRuntime.importUserSkills(context, decodeFiles(document.skillFiles))
            McpSecretStore(context).replaceAll(document.mcpTokens)
            document.settings?.let { SettingsDataStore.restoreBackup(it) }
                ?: SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
        } else {
            SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
        }
        LinuxEnvironmentSettingsRepository.initialize(context)
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
    }

    private suspend fun rewriteRestoredPaths(context: Context, document: EtaBackupDocument) {
        val currentRoot = context.filesDir.parentFile?.absolutePath ?: return
        val pattern = Regex("""/data/(?:user/\d+|data)/[^/\s"'\\]+""")
        val rewrite: (String) -> String = { value -> pattern.replace(value, currentRoot) }
        val conversations = document.conversations.map { it.copy(historyJson = rewrite(it.historyJson)) }
        val messages = document.messages.map {
            it.copy(content = rewrite(it.content), imagesJson = rewrite(it.imagesJson))
        }
        val checkpoints = document.contextCheckpoints.map { it.copy(historyJson = rewrite(it.historyJson)) }
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = conversations,
            messages = messages,
            contextCheckpoints = checkpoints,
            state = document.conversationState,
        )
        EtaDatabase.get(context).conversationDao().replaceFolders(document.folders)
    }

    private fun decodeDocument(raw: String): EtaBackupDocument =
        runCatching { json.decodeFromString<EtaBackupDocument>(raw) }.getOrElse { failure ->
            throw EtaBackupException("备份文件格式无效", failure)
        }

    private fun validate(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) {
            throw EtaBackupException("这不是 Eta 备份文件")
        }
        if (document.schemaVersion !in EtaBackupDocument.MIN_SUPPORTED_SCHEMA..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的 Eta 备份版本：${document.schemaVersion}")
        }
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的模型提供商存在重复或无效 ID")
        }
        val conversationIds = document.conversations.map { it.id }
        if (conversationIds.size != conversationIds.toSet().size || conversationIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的会话存在重复或无效 ID")
        }
        if (document.messages.any { it.conversationId !in conversationIds }) {
            throw EtaBackupException("备份中的消息缺少所属会话")
        }
        if (document.memoryMd.toByteArray().size > 1024 * 1024) {
            throw EtaBackupException("MEMORY.md 超过 1 MiB 限制")
        }
        document.assistants?.profiles?.let { profiles ->
            val ids = profiles.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) {
                throw EtaBackupException("备份中的助手存在重复或无效 ID")
            }
        }
        val mcpIds = document.mcpServers.map { it.id }
        if (mcpIds.size != mcpIds.toSet().size || mcpIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的 MCP 服务器存在重复或无效 ID")
        }
    }

    private fun EtaBackupDocument.summary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = providers.size,
        modelCount = providers.sumOf { it.models.size },
        conversationCount = conversations.size,
        messageCount = messages.size,
        memoryBytes = memoryMd.toByteArray().size,
        assistantCount = assistants?.profiles?.size ?: 0,
        mcpCount = mcpServers.size,
        skillCount = skillFiles.keys.map { it.substringBefore('/') }.filter { it.isNotBlank() }.toSet().size,
        attachmentCount = attachmentCount + importedFileCount,
        includedLinuxEnvironment = includeLinuxEnvironment,
    )

    private fun EtaConversationExport.summary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = 0,
        modelCount = 0,
        conversationCount = 1,
        messageCount = messages.size,
        memoryBytes = 0,
        attachmentCount = attachmentCount,
    )
}

private fun encodeFiles(files: Map<String, ByteArray>): Map<String, String> =
    files.mapValues { (_, bytes) -> Base64.encodeToString(bytes, Base64.NO_WRAP) }

private fun decodeFiles(files: Map<String, String>): Map<String, ByteArray> =
    files.mapValues { (_, encoded) -> Base64.decode(encoded, Base64.NO_WRAP) }

private fun putText(zip: ZipOutputStream, name: String, text: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(text.toByteArray())
    zip.closeEntry()
}

private fun putFile(zip: ZipOutputStream, name: String, file: File) {
    if (!file.isFile || file.length() <= 0L) return
    zip.putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(zip) }
    zip.closeEntry()
}

private fun putDirectory(
    zip: ZipOutputStream,
    prefix: String,
    root: File,
    skipNames: Set<String> = emptySet(),
) {
    if (!root.exists()) return
    root.walkTopDown().filter { it.isFile }.forEach { file ->
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: return@forEach
        if (relative.isBlank() || relative.split('/').any { it in skipNames || it == ".." }) return@forEach
        putFile(zip, prefix + relative, file)
    }
}

private fun exportLinuxEnvironments(context: Context, zip: ZipOutputStream) {
    LinuxDistribution.entries.forEach { distribution ->
        val envDir = LinuxEnvironmentPaths.environmentDir(context, distribution)
        if (!envDir.exists()) return@forEach
        val backend = LinuxEnvironmentPaths.backendOf(
            LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath,
        )
        val entryName = "linux/environments/${backend.wireName}/${distribution.wireName}.tar"
        zip.putNextEntry(ZipEntry(entryName))
        val packed = if (canWalk(envDir)) {
            writeTar(envDir, zip)
        } else {
            streamBusyBoxTar(envDir, zip)
        }
        zip.closeEntry()
        if (!packed) {
            throw EtaBackupException("无法打包 Linux 环境：${distribution.wireName}")
        }
    }
}

private fun restoreLinuxTar(
    context: Context,
    input: InputStream,
    backendName: String,
    distributionName: String,
) {
    val backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == backendName }
        ?: throw EtaBackupException("备份中的 Linux 后端无效：$backendName")
    val distribution = LinuxDistribution.entries.firstOrNull { it.wireName == distributionName }
        ?: throw EtaBackupException("备份中的 Linux 发行版无效：$distributionName")
    val destination = LinuxEnvironmentPaths.environmentDir(context, distribution, backend)
    destination.parentFile?.mkdirs()
    if (canWrite(destination.parentFile ?: destination) && (backend == LinuxExecutionBackend.PROOT || canWalk(destination))) {
        if (destination.exists() && !destination.deleteRecursively()) {
            deleteTreeAsRoot(destination)
        }
        destination.mkdirs()
        extractTarStream(input, destination)
        return
    }
    if (!RootAccess.isGranted) {
        throw EtaBackupException("导入完整 Linux 环境需要 Root")
    }
    extractBusyBoxTar(input, destination)
}

private fun writeTar(source: File, output: OutputStream): Boolean = runCatching {
    TarArchiveOutputStream(output).apply {
        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
    }.let { tar ->
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath.ifBlank { "." }
            if (relative == ".") return@forEach
            val entry = TarArchiveEntry(file, relative)
            tar.putArchiveEntry(entry)
            if (file.isFile) file.inputStream().use { it.copyTo(tar) }
            tar.closeArchiveEntry()
        }
        tar.finish()
    }
    true
}.getOrDefault(false)

private fun extractTarStream(input: InputStream, destination: File) {
    val root = destination.canonicalFile
    // 不能 close：底层可能是正在读取的 ZipInputStream。
    val tar = TarArchiveInputStream(input)
    while (true) {
        val entry = tar.nextEntry ?: break
        val relative = normalizeTarPath(entry.name) ?: continue
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw EtaBackupException("Linux 归档路径越界")
        }
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                Files.deleteIfExists(target.toPath())
                runCatching {
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                }.getOrElse {
                    target.writeText("")
                }
            }
            entry.isFile -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
                if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
            }
        }
    }
}

private fun streamBusyBoxTar(source: File, output: OutputStream): Boolean {
    if (!RootAccess.isGranted) return false
    val script = AndroidBusyBox.discoveryScript() +
        "; [ -n \"\$eta_busybox\" ] || exit 127; " +
        "\"\$eta_busybox\" tar -C " + shellQuote(source.absolutePath) + " -cf - ."
    val process = RootSu.process(script).redirectErrorStream(true).start()
    return try {
        process.inputStream.copyTo(output)
        process.waitFor(20, TimeUnit.MINUTES) && process.exitValue() == 0
    } finally {
        runCatching { process.destroyForcibly() }
    }
}

private fun extractBusyBoxTar(input: InputStream, destination: File) {
    destination.parentFile?.mkdirs()
    val script = AndroidBusyBox.discoveryScript() +
        "; [ -n \"\$eta_busybox\" ] || exit 127; " +
        "\"\$eta_busybox\" rm -rf -- " + shellQuote(destination.absolutePath) + "; " +
        "\"\$eta_busybox\" mkdir -p -- " + shellQuote(destination.absolutePath) + "; " +
        "\"\$eta_busybox\" tar -C " + shellQuote(destination.absolutePath) + " -xf -"
    val process = RootSu.process(script).redirectErrorStream(true).start()
    try {
        process.outputStream.use { stdin -> input.copyTo(stdin) }
        val finished = process.waitFor(20, TimeUnit.MINUTES)
        val code = if (finished) process.exitValue() else -1
        if (!finished || code != 0) {
            throw EtaBackupException("无法还原 Linux 环境（退出码 $code）")
        }
    } catch (failure: EtaBackupException) {
        throw failure
    } catch (failure: Throwable) {
        throw EtaBackupException("无法还原 Linux 环境：${failure.message ?: failure.javaClass.simpleName}", failure)
    } finally {
        runCatching { process.destroyForcibly() }
    }
}

private fun normalizeTarPath(raw: String): String? {
    val relative = raw.trim().trimStart('/').replace('\\', '/')
    if (relative.isBlank() || relative == ".") return null
    val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) throw EtaBackupException("Linux 归档路径越界")
    return segments.joinToString("/")
}

private fun extractZipFile(
    zip: ZipInputStream,
    destination: File,
    relative: String,
    skipNames: Set<String> = emptySet(),
) {
    if (relative.isBlank() || relative.contains("..") || relative.split('/').any { it in skipNames }) return
    val target = File(destination, relative)
    destination.mkdirs()
    if (!target.canonicalFile.path.startsWith(destination.canonicalFile.path)) return
    target.parentFile?.mkdirs()
    target.outputStream().use { zip.copyTo(it) }
}

private fun deleteTreeAsRoot(target: File): Boolean {
    if (!target.exists()) return true
    return runRoot("rm -rf -- ${shellQuote(target.absolutePath)}", timeoutMinutes = 5)
}

private fun runRoot(command: String, timeoutMinutes: Long): Boolean {
    if (!RootAccess.isGranted) return false
    val process = runCatching { RootSu.process(command).redirectErrorStream(true).start() }.getOrNull() ?: return false
    return try {
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } finally {
        runCatching { process.destroy() }
    }
}

private fun duBytes(root: File): Long {
    if (!root.exists() || !RootAccess.isGranted) return 0L
    val process = runCatching {
        RootSu.process("du -sb -- ${shellQuote(root.absolutePath)}").redirectErrorStream(true).start()
    }.getOrNull() ?: return 0L
    return try {
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            0L
        } else {
            process.inputStream.bufferedReader().readText().trim().substringBefore('\t').toLongOrNull() ?: 0L
        }
    } finally {
        runCatching { process.destroy() }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun canWalk(root: File): Boolean = root.canRead() && (root.listFiles()?.firstOrNull()?.canRead() != false)

private fun canWrite(root: File): Boolean = root.parentFile?.canWrite() == true && (!root.exists() || root.canWrite())

private fun directorySize(root: File): Long {
    if (!root.exists() || !root.canRead()) return 0L
    return runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}

private fun countFiles(root: File): Int {
    if (!root.exists()) return 0
    return runCatching { root.walkTopDown().count { it.isFile } }.getOrDefault(0)
}

private fun referencedImportedFiles(
    context: Context,
    messages: List<ConversationMessageEntity>,
    checkpoint: ConversationContextCheckpointEntity?,
): List<File> {
    val text = buildString {
        messages.forEach {
            appendLine(it.content)
            appendLine(it.imagesJson)
        }
        checkpoint?.let { appendLine(it.historyJson) }
    }
    val matches = Regex("""(/data/(?:user/\d+|data)/[^\s"']+/files/(?:terminal-user|terminal)/workspace/imports/[^\s"']+)""")
        .findAll(text)
    return matches.map { File(it.groupValues[1]) }.filter { it.isFile }.distinctBy { it.absolutePath }.toList()
}

private fun importedRelativePath(context: Context, file: File): String? {
    val roots = listOf(
        File(TerminalPrivateStorage.workspace(context.filesDir), "imports"),
        File(File(context.filesDir, "terminal/workspace"), "imports"),
    )
    return roots.firstNotNullOfOrNull { root ->
        runCatching { file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith("..") }
    }
}
