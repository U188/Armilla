package io.github.mangi.eta.agent.tool

import android.content.ContextWrapper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AssistantRepository
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalSkillRevocationFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedPruneDoesNotEscapeAndBlocksFurtherSkillAndTerminalUse() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temporary.root
            override fun getApplicationContext(): android.content.Context = this
        }
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        AssistantRepository.init(context)
        val assistant = AssistantRepository.active()
        val external = temporary.newFolder("external")
        val note = File(external, "revoked/SKILL.md").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("must survive")
        }
        val view = File(SkillRuntime.assistantSkillsDirectory(context, assistant.id), ".runs/view")
            .apply { mkdirs() }
        val root = File(view, "skills")
        Files.createSymbolicLink(root.toPath(), external.toPath())
        val entry = SkillIndexEntry(
            id = "revoked", name = "revoked", description = "test",
            rootPath = File(root, "revoked").path,
            skillFilePath = File(root, "revoked/SKILL.md").path,
            hasScripts = false, hasReferences = false, hasAssets = false, hasEvals = false,
        )
        val errors = mutableListOf<Throwable?>()
        val logger = object : AgentLogger {
            override fun debug(message: () -> String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) { errors.add(throwable) }
        }
        val tools = AgentLocalTools(
            context = context, logger = logger, rootAvailable = { false },
            skillIndexService = SkillRuntime.createIndexService(context),
            runSkillEntries = listOf(entry), memoryAssistantId = assistant.id,
            runSkillsRoot = root, terminalToolsEnabled = { true },
        )
        try {
            assertTrue(tools.currentSkillEntries().isEmpty())
            assertTrue(errors.any { it is IllegalStateException && it.message == "无法撤销技能目录" })
            assertTrue(tools.currentSkillEntries().isEmpty())
            val result = tools.execute(AgentModelClient.ToolCall(
                id = "terminal", name = "run_command", argumentsJson = "{\"command\":\"echo should-not-run\"}",
            ))
            assertEquals("NEXT_TURN_REQUIRED", JSONObject(result.content).getString("code"))
            listOf("read_image", "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github")
                .forEach { name ->
                    val blocked = tools.execute(AgentModelClient.ToolCall(
                        id = name, name = name, argumentsJson = "{}",
                    ))
                    assertEquals(name, "NEXT_TURN_REQUIRED", JSONObject(blocked.content).getString("code"))
                }
            assertEquals("must survive", note.readText())
        } finally {
            Files.deleteIfExists(root.toPath())
            tools.close()
        }
    }
}
