package io.github.mangi.eta.data.repository

import android.content.ContextWrapper
import android.util.AtomicFile
import io.github.mangi.eta.agent.skill.ASSISTANT_SKILL_DIR
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AssistantStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [DeleteCleanupAtomicFileShadow::class])
class AssistantDeleteCleanupFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
        override fun getApplicationContext(): android.content.Context = this
    }

    @Before fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AssistantRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @Test fun deleteSucceedsAndKeepsExternalDataWhenSkillCleanupCannotDelete() {
        val created = AssistantRepository.create(name = "cleanup-target")
        val skillsRoot = SkillRuntime.skillsRoot(context)
        skillsRoot.mkdirs()
        // `.assistant` 指向根外的符号链接：安全删除必须拒绝，技能清理必然失败。
        val external = temporary.newFolder("external-assistant")
        val outsideAssistant = File(external, AssistantStorage.id(created.id)).apply { mkdirs() }
        File(outsideAssistant, "keep.txt").writeText("must survive")
        File(skillsRoot, ASSISTANT_SKILL_DIR).deleteRecursively()
        Files.createSymbolicLink(File(skillsRoot, ASSISTANT_SKILL_DIR).toPath(), external.toPath())

        AssistantRepository.delete(created.id)

        assertNull(AssistantRepository.profile(created.id))
        assertTrue(AssistantRepository.profiles.value.none { it.id == created.id })
        assertEquals("must survive", File(outsideAssistant, "keep.txt").readText())
        // 清理被安全边界拒绝时不得强制删除根外数据；残留由后续存活判定回收。
        assertTrue(File(skillsRoot, ASSISTANT_SKILL_DIR).exists())
    }
}

@Implements(AtomicFile::class)
class DeleteCleanupAtomicFileShadow {
    @RealObject private lateinit var file: AtomicFile

    @Implementation
    fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync()
        stream.close()
        Files.move(
            File(file.baseFile.path + ".new").toPath(),
            file.baseFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
