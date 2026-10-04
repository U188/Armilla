package io.github.mangi.eta.data.repository

import android.content.ContextWrapper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import android.util.AtomicFile
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ReplacingAtomicFileShadow::class])
class AssistantSelectionFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
        override fun getApplicationContext(): android.content.Context = this
    }

    @Before fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AssistantRepository.init(context)
    }

    @Test fun failedSkillRevocationDoesNotCommitAssistantAndCanBeRetried() {
        val original = AssistantRepository.activeId.value
        val target = AssistantRepository.profiles.value.first { it.id != original }
        val runs = File(SkillRuntime.assistantSkillsDirectory(context, target.id), ".runs")
        val external = temporary.newFolder("external")
        val note = File(external, "skills/revoked/SKILL.md").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("must not be deleted")
        }
        runs.mkdirs()
        val linkedView = File(runs, "blocked-view")
        Files.createSymbolicLink(linkedView.toPath(), external.toPath())
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                AssistantRepository.select(target.id)
            }
            assertEquals("无法撤销技能目录", failure.message)
            assertEquals(original, AssistantRepository.activeId.value)
            assertEquals(original, AssistantRepository.exportSnapshot().activeId)
            assertEquals("must not be deleted", note.readText())
        } finally {
            Files.deleteIfExists(linkedView.toPath())
        }
        AssistantRepository.select(target.id)
        assertEquals(target.id, AssistantRepository.activeId.value)
        assertEquals(target.id, AssistantRepository.exportSnapshot().activeId)
    }
}

// Android's renameTo replaces an existing file; Windows renameTo does not. Model the
// Android finishWrite contract so read-back assertions exercise the committed index.
@Implements(AtomicFile::class)
class ReplacingAtomicFileShadow {
    @RealObject private lateinit var file: AtomicFile

    @Implementation
    fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync()
        stream.close()
        Files.move(File(file.baseFile.path + ".new").toPath(), file.baseFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING)
    }
}
