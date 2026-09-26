package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.model.AssistantDefaults
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantPrompt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object AssistantRepository {
    private const val DIRECTORY_NAME = "eta-assistants"
    private const val INDEX_NAME = "index.json"
    private const val AVATAR_DIR = "avatars"
    private const val AVATAR_SIZE = 512

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private lateinit var applicationContext: Context

    private val _profiles = MutableStateFlow<List<AssistantProfile>>(emptyList())
    val profiles: StateFlow<List<AssistantProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(AssistantPrompt.DEFAULT_ID)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    fun isReady(): Boolean = ::applicationContext.isInitialized

    @Synchronized
    fun init(context: Context) {
        applicationContext = context.applicationContext
        directory().mkdirs()
        avatarsDirectory().mkdirs()
        val active = withIndexLock {
            val snapshot = migrateDefaultPrompt(readIndex() ?: seedDefault())
            publish(snapshot)
            snapshot.activeId
        }
        runCatching { refreshAssistantSkills(active, publishVisible = true) }.onFailure { error ->
            io.github.mangi.eta.core.AndroidAgentLogger.error("Assistant skill startup publication failed: ${error.javaClass.simpleName}")
        }
    }

    fun active(): AssistantProfile =
        profiles.value.firstOrNull { it.id == activeId.value }
            ?: profiles.value.firstOrNull()
            ?: defaultProfile()

    fun profile(id: String): AssistantProfile? =
        profiles.value.firstOrNull { it.id == id }

    /** Read the published index as well in the runtime process; never substitute the active assistant. */
    fun currentProfile(id: String): AssistantProfile? =
        if (isReady()) withIndexLock { profiles.value.firstOrNull { it.id == id } } else profile(id)

    private fun validateProfiles(profiles: List<AssistantProfile>) {
        require(profiles.map { io.github.mangi.eta.data.model.AssistantStorage.id(it.id) }.toSet().size == profiles.size) { "助手 ID 重复" }
    }

    fun avatarFile(fileName: String?): File? {
        if (!::applicationContext.isInitialized || fileName.isNullOrBlank()) return null
        val file = File(avatarsDirectory(), fileName)
        return file.takeIf { it.isFile }
    }

    fun avatarBitmap(fileName: String?): Bitmap? {
        val file = avatarFile(fileName) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    @Synchronized
    fun create(
        name: String = "新助手",
        prompt: String = "",
        avatarFileName: String? = null,
        memoryEnabled: Boolean = true,
        enabledSkillIds: List<String> = AssistantDefaults.ENABLED_SKILL_IDS,
    ): AssistantProfile {
        return withIndexLock {
            ensureReady()
            val id = UUID.randomUUID().toString()
            val created = AssistantProfile(
                id = id,
                name = name.trim().ifBlank { "新助手" },
                prompt = prompt,
                avatarFileName = avatarFileName,
                createdAt = System.currentTimeMillis(),
                memoryEnabled = memoryEnabled,
                enabledSkillIds = enabledSkillIds,
            )
            val snapshot = Snapshot(activeId.value, profiles.value + created)
            writeIndex(snapshot)
            publish(snapshot)
            created
        }.also { created ->
            if (::applicationContext.isInitialized) {
                runCatching { refreshAssistantSkills(created.id, publishVisible = created.id == activeId.value) }
            }
        }
    }

    @Synchronized
    fun duplicate(id: String): AssistantProfile {
        ensureReady()
        val source = requireNotNull(currentProfile(id)) { "助手不存在" }
        val created = create(
            name = source.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME } + " 副本",
            prompt = source.prompt,
            memoryEnabled = source.memoryEnabled,
            enabledSkillIds = source.enabledSkillIds,
        )
        AgentMemoryRepository.copy(source.id, created.id)
        if (::applicationContext.isInitialized) {
            SkillRuntime.copyAssistantSkills(applicationContext, source.id, created.id)
        }
        return source.avatarFileName?.let { copyAvatar(it, created.id) }?.let { fileName ->
            update(created.copy(avatarFileName = fileName))
        } ?: created
    }

    @Synchronized
    fun enableSkills(skillIds: Collection<String>, assistantId: String = active().id) {
        if (!isReady() || skillIds.isEmpty()) return
        val current = requireNotNull(currentProfile(assistantId)) { "助手不存在" }
        val merged = (current.enabledSkillIds + skillIds).distinct()
        if (merged == current.enabledSkillIds) return
        update(current.copy(enabledSkillIds = merged))
    }

    @Synchronized
    fun update(profile: AssistantProfile): AssistantProfile {
        val (updated, publishVisible) = withIndexLock {
            ensureReady()
            require(profiles.value.any { it.id == profile.id }) { "助手不存在" }
            val next = profile.copy(name = profile.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME })
            val snapshot = Snapshot(
                activeId = activeId.value,
                profiles = profiles.value.map { if (it.id == next.id) next else it },
            )
            writeIndex(snapshot)
            publish(snapshot)
            next to (next.id == snapshot.activeId)
        }
        if (::applicationContext.isInitialized) {
            refreshAssistantSkills(updated.id, publishVisible = publishVisible)
        }
        return updated
    }

    @Synchronized
    fun delete(id: String) {
        require(!AssistantPrompt.isBuiltin(id)) { "内置助手不可删除" }
        val nextActive = withIndexLock {
            ensureReady()
            val remaining = profiles.value.filterNot { it.id == id }
            require(remaining.isNotEmpty()) { "必须保留至少一个助手" }
            val next = if (activeId.value == id) remaining.first().id else activeId.value
            val avatar = avatarFile(profile(id)?.avatarFileName)
            val snapshot = Snapshot(next, remaining)
            writeIndex(snapshot)
            publish(snapshot)
            avatar?.delete()
            next
        }
        AgentMemoryRepository.delete(id)
        if (::applicationContext.isInitialized) {
            SkillRuntime.deleteAssistantSkills(applicationContext, id)
            if (nextActive != id) {
                refreshAssistantSkills(nextActive, publishVisible = true)
            }
        }
    }

    @Synchronized
    fun exportSnapshot(): AssistantBackupSnapshot = withIndexLock {
        ensureReady()
        AssistantBackupSnapshot(activeId = activeId.value, profiles = profiles.value)
    }

    @Synchronized
    fun importSnapshot(snapshot: AssistantBackupSnapshot) {
        val active = withIndexLock {
            ensureReady()
            val profiles = snapshot.profiles.ifEmpty { listOf(defaultProfile(System.currentTimeMillis())) }
            validateProfiles(profiles)
            val selected = snapshot.activeId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
            val next = migrateDefaultPrompt(Snapshot(selected, profiles))
            writeIndex(next)
            publish(next)
            next.activeId
        }
        refreshAssistantSkills(active, publishVisible = true)
    }

    fun exportAvatars(): Map<String, ByteArray> {
        if (!::applicationContext.isInitialized) return emptyMap()
        val dir = avatarsDirectory()
        if (!dir.isDirectory) return emptyMap()
        require(!java.nio.file.Files.isSymbolicLink(dir.toPath())) { "头像目录不能是符号链接" }
        val budget = BackupBlobBudget(maxTotalBytes = 4L * 1024 * 1024, maxFiles = 1_000)
        val result = linkedMapOf<String, ByteArray>()
        java.nio.file.Files.newDirectoryStream(dir.toPath()).use { entries ->
            for (entry in entries) {
                val file = entry.toFile()
                require(file.isFile) { "头像目录包含非文件条目" }
                result[file.name] = budget.read(file)
            }
        }
        return result
    }

    fun importAvatars(files: Map<String, ByteArray>) {
        if (!::applicationContext.isInitialized) return
        val dir = avatarsDirectory()
        require(files.keys.all { it.isNotBlank() && File(it).name == it && !it.contains('\\') }) { "头像文件名无效" }
        check(dir.mkdirs() || dir.isDirectory) { "无法创建头像目录" }
        dir.listFiles().orEmpty().forEach { check(it.delete()) { "无法清理旧头像" } }
        files.forEach { (name, bytes) ->
            val safe = File(name).name
            File(dir, safe).writeBytes(bytes)
        }
    }

    @Synchronized
    fun select(id: String) {
        val changed = withIndexLock {
            ensureReady()
            require(profiles.value.any { it.id == id }) { "助手不存在" }
            if (activeId.value == id) return@withIndexLock false
            val snapshot = Snapshot(id, profiles.value)
            writeIndex(snapshot)
            publish(snapshot)
            true
        }
        if (changed) refreshAssistantSkills(id, publishVisible = true)
    }

    private fun refreshAssistantSkills(assistantId: String, publishVisible: Boolean) {
        if (!::applicationContext.isInitialized) return
        run {
            val enabled = profile(assistantId)?.enabledSkillIds?.toSet().orEmpty()
            val entries = SkillRuntime.createIndexService(applicationContext)
                .listSkillsForManagement()
                .filter { it.installed && it.id in enabled }
            if (publishVisible) {
                SkillRuntime.publishVisibleSkills(applicationContext, assistantId, entries)
            } else {
                SkillRuntime.bindSkillsToAssistant(applicationContext, assistantId, entries)
            }
        }
    }

    @Synchronized
    fun saveAvatar(id: String, bitmap: Bitmap): AssistantProfile {
        ensureReady()
        val current = requireNotNull(currentProfile(id)) { "助手不存在" }
        val fileName = "$id.png"
        val target = File(avatarsDirectory(), fileName)
        val scaled = scaleAvatar(bitmap)
        target.outputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        if (scaled !== bitmap) scaled.recycle()
        return update(current.copy(avatarFileName = fileName))
    }

    @Synchronized
    fun clearAvatar(id: String): AssistantProfile {
        ensureReady()
        val current = requireNotNull(currentProfile(id)) { "助手不存在" }
        avatarFile(current.avatarFileName)?.delete()
        return update(current.copy(avatarFileName = null))
    }

    private fun seedDefault(): Snapshot {
        val now = System.currentTimeMillis()
        val seeded = Snapshot(
            activeId = AssistantPrompt.DEFAULT_ID,
            profiles = AssistantPrompt.BUILTINS.mapIndexed { index, builtin ->
                builtinProfile(builtin.id, builtin.name, createdAt = now + index)
            },
        )
        writeIndex(seeded)
        return seeded
    }

    private fun defaultProfile(createdAt: Long = 0L) =
        builtinProfile(AssistantPrompt.DEFAULT_ID, AssistantPrompt.DEFAULT_NAME, createdAt)

    private fun builtinProfile(id: String, name: String, createdAt: Long = 0L) = AssistantProfile(
        id = id,
        name = name,
        prompt = "",
        createdAt = createdAt,
    )

    /** 旧版内置助手的默认名；升级后统一换成 [AssistantPrompt.DEFAULT_NAME]。 */
    private val LEGACY_DEFAULT_NAMES = setOf("Eta", "代鱼", "晚枫")

    private fun migrateDefaultPrompt(snapshot: Snapshot): Snapshot {
        val profiles = snapshot.profiles.map { profile ->
            if (profile.id != AssistantPrompt.DEFAULT_ID) return@map profile
            var next = profile
            if (next.name in LEGACY_DEFAULT_NAMES) {
                next = next.copy(name = AssistantPrompt.DEFAULT_NAME)
            }
            // 旧版内置默认文案（非用户内容）会与固定人格重复，清空即可；用户自己写的补充内容保留。
            if (next.prompt == AssistantPrompt.DEFAULT_BODY) {
                next = next.copy(prompt = "")
            }
            next
        }
        // 升级补种：补齐尚不存在的内置助手（如新增的 hacker「小枫」），已存在的尊重用户改动不动。
        val existingIds = profiles.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val added = AssistantPrompt.BUILTINS
            .filter { it.id !in existingIds }
            .mapIndexed { index, builtin -> builtinProfile(builtin.id, builtin.name, createdAt = now + index) }
        val merged = profiles + added
        if (merged == snapshot.profiles) return snapshot
        val migrated = snapshot.copy(profiles = merged)
        writeIndex(migrated)
        return migrated
    }

    private fun publish(snapshot: Snapshot) {
        validateProfiles(snapshot.profiles)
        _profiles.value = snapshot.profiles
        _activeId.value = snapshot.activeId.takeIf { id -> snapshot.profiles.any { it.id == id } }
            ?: snapshot.profiles.first().id
    }

    private fun readIndex(): Snapshot? {
        val file = android.util.AtomicFile(indexFile())
        if (!file.baseFile.isFile && !File(file.baseFile.path + ".bak").isFile) return null
        return file.openRead().bufferedReader().use { json.decodeFromString<Snapshot>(it.readText()) }
            .also { require(it.profiles.isNotEmpty()); validateProfiles(it.profiles) }
    }

    private fun writeIndex(snapshot: Snapshot) {
        validateProfiles(snapshot.profiles)
        val file = android.util.AtomicFile(indexFile())
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private val indexLockHeld = ThreadLocal<Boolean>()

    private inline fun <T> withIndexLock(block: () -> T): T = synchronized(this) {
        if (indexLockHeld.get() == true) return@synchronized block()
        check(directory().mkdirs() || directory().isDirectory)
        java.io.RandomAccessFile(File(directory(), "index.lock"), "rw").use { file ->
            file.channel.lock().use {
                indexLockHeld.set(true)
                try {
                    readIndex()?.let(::publish)
                    block()
                } finally { indexLockHeld.remove() }
            }
        }
    }

    private fun copyAvatar(sourceFileName: String, targetId: String): String? {
        val source = avatarFile(sourceFileName) ?: return null
        val fileName = "$targetId.png"
        val target = File(avatarsDirectory(), fileName)
        return runCatching {
            source.copyTo(target, overwrite = true)
            fileName
        }.getOrNull()
    }

    private fun scaleAvatar(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longest <= AVATAR_SIZE) return bitmap
        val scale = AVATAR_SIZE.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun ensureReady() {
        check(::applicationContext.isInitialized) { "AssistantRepository 未初始化" }
        if (profiles.value.isEmpty()) {
            publish(readIndex() ?: seedDefault())
        }
    }

    private fun directory(): File = File(applicationContext.filesDir, DIRECTORY_NAME)

    private fun avatarsDirectory(): File = File(directory(), AVATAR_DIR)

    private fun indexFile(): File = File(directory(), INDEX_NAME)

    @Serializable
    private data class Snapshot(
        val activeId: String,
        val profiles: List<AssistantProfile>,
    )
}

@Serializable
internal data class AssistantBackupSnapshot(
    val activeId: String,
    val profiles: List<AssistantProfile> = emptyList(),
)
