package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantPrompt
import java.io.File
import org.json.JSONArray
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.Model
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assume.assumeTrue
import android.content.ContextWrapper
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemotePersonaStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val revision = "b".repeat(40)

    private fun transport(defaultText: String, hackerText: String): (String, Int) -> String = { url, _ ->
        val id = if (url.contains("9a2e1239")) "9a2e1239ac8af01d6f6273b09f31fc31"
        else "1231d6d69cb045a33e8cd434f1e09aec"
        val text = if (id.startsWith("9a2")) defaultText else hackerText
        if (url.startsWith("https://api.github.com/")) {
            JSONObject()
                .put("history", JSONArray().put(JSONObject().put("version", revision)))
                .put("files", JSONObject().put("gistfile1.txt", JSONObject()
                    .put("raw_url", "https://gist.githubusercontent.com/U188/$id/raw/$revision/gistfile1.txt")
                    .put("content", text).put("truncated", false)))
                .toString()
        } else {
            assertTrue(url.contains("/raw/$revision/"))
            assertFalse(url.contains("a78e1d4"))
            text
        }
    }

    @Test fun latestContentSurvivesRestartAndIsUsedByPromptResolver() {
        val dir = temporary.newFolder()
        File(dir, "remote_persona_default.txt").writeText("旧小蝶")
        File(dir, "remote_persona.txt").writeText("旧小枫")
        RemotePersonaStore.init(dir)
        assertEquals("旧小蝶", RemotePersonaStore.defaultPersona())
        val result = RemotePersonaStore.refresh(transport("新小蝶", "新小枫")) { 1234L }
        val success = result.default as RemotePersonaStore.PersonaResult.Success
        assertTrue(success.changed)
        assertEquals(revision, success.snapshot.revision)
        assertEquals("新小蝶", RemotePersonaStore.defaultPersona())
        assertEquals("新小蝶", JSONObject(File(dir, "remote_persona_default.json").readText()).getString("text"))
        RemotePersonaStore.init(dir)
        assertEquals(success.snapshot, RemotePersonaStore.defaultSnapshot())
        assertEquals("新小枫", RemotePersonaStore.hackerPersona())
        val original = AssistantPrompt.personaOverrideResolver
        try {
            AssistantPrompt.personaOverrideResolver = { id -> when (id) {
                AssistantPrompt.DEFAULT_ID -> RemotePersonaStore.defaultPersona()
                AssistantPrompt.HACKER_ID -> RemotePersonaStore.hackerPersona()
                else -> null
            } }
            val prompt = AssistantPrompt.build(AssistantProfile("default", "小蝶", "补充"))
            assertTrue(prompt.contains("新小蝶"))
            assertFalse(prompt.contains("旧小蝶"))
            assertTrue(prompt.endsWith("补充"))
            val config = RuntimeConfigRepository.buildRuntimeConfig(
                OpenAiCompatibleProviderSetting(id = "test", name = "test", baseUrl = "https://api.example/v1"),
                Model(id = "test-model", modelId = "test-model", displayName = "test-model"),
                AssistantProfile("default", "小蝶", "补充"),
            )
            assertEquals(prompt, config.systemPrompt)
        } finally { AssistantPrompt.personaOverrideResolver = original }
    }

    @Test fun sameLengthChangeIsDetectedAndUnchangedRefreshIsExplicit() {
        RemotePersonaStore.init(temporary.newFolder())
        RemotePersonaStore.refresh(transport("甲乙", "丙丁")) { 1L }
        val unchanged = RemotePersonaStore.refresh(transport("甲乙", "丙丁")) { 2L }
        assertTrue(unchanged.default.toString(), unchanged.default is RemotePersonaStore.PersonaResult.Success)
        assertFalse((unchanged.default as RemotePersonaStore.PersonaResult.Success).changed)
        val changed = RemotePersonaStore.refresh(transport("乙甲", "丙丁")) { 3L }
        assertTrue((changed.default as RemotePersonaStore.PersonaResult.Success).changed)
        assertFalse((changed.hacker as RemotePersonaStore.PersonaResult.Success).changed)
    }

    @Test fun diskFailureKeepsOldMemoryAndOtherPersonaCanStillSucceed() {
        val dir = temporary.newFolder()
        File(dir, "remote_persona_default.txt").writeText("保留旧小蝶")
        RemotePersonaStore.init(dir)
        // Prevent AtomicFile from creating its transaction file without relying on OS permissions.
        File(dir, "remote_persona_default.json.new").mkdir()
        val result = RemotePersonaStore.refresh(transport("不应生效", "新小枫"))
        assertTrue(result.default is RemotePersonaStore.PersonaResult.Failure)
        assertEquals("保留旧小蝶", RemotePersonaStore.defaultPersona())
        assertTrue(result.hacker is RemotePersonaStore.PersonaResult.Success)
        RemotePersonaStore.init(dir)
        assertEquals("保留旧小蝶", RemotePersonaStore.defaultPersona())
        assertEquals("新小枫", RemotePersonaStore.hackerPersona())
    }

    @Test fun staleRawResponseAndFetchFailureDoNotOverwriteVerifiedDisk() {
        val dir = temporary.newFolder()
        RemotePersonaStore.init(dir)
        val fetch = transport("当前内容", "当前小枫")
        RemotePersonaStore.refresh(fetch)
        val stale = RemotePersonaStore.refresh({ url, limit ->
            if (url.startsWith("https://gist.githubusercontent.com/")) "CDN旧内容" else fetch(url, limit)
        })
        assertTrue(stale.default is RemotePersonaStore.PersonaResult.Failure)
        val failure = RemotePersonaStore.refresh({ _, _ -> error("HTTP 403") })
        assertTrue(failure.hacker is RemotePersonaStore.PersonaResult.Failure)
        RemotePersonaStore.init(dir)
        assertEquals("当前内容", RemotePersonaStore.defaultPersona())
        assertEquals("当前小枫", RemotePersonaStore.hackerPersona())
    }

    @Test fun emptyAndOversizedResponsesAreRejected() {
        RemotePersonaStore.init(temporary.newFolder())
        assertTrue(RemotePersonaStore.refresh(transport("  ", "x")).default is RemotePersonaStore.PersonaResult.Failure)
        assertTrue(RemotePersonaStore.refresh(transport("中".repeat(65536), "x")).default is RemotePersonaStore.PersonaResult.Failure)
        assertNull(RemotePersonaStore.defaultPersona())
    }

    @Test fun corruptedNewCacheDoesNotSilentlyReloadLegacyPersona() {
        val dir = temporary.newFolder()
        File(dir, "remote_persona_default.txt").writeText("不该偷偷恢复的旧人格")
        File(dir, "remote_persona_default.json").writeText("broken")
        RemotePersonaStore.init(dir)
        assertNull(RemotePersonaStore.defaultPersona())
    }

    @Test fun verifiedCacheRemainsAfterSubsequentWriteFailure() {
        val dir = temporary.newFolder()
        RemotePersonaStore.init(dir)
        RemotePersonaStore.refresh(transport("已保存版本", "已保存小枫"))
        val old = RemotePersonaStore.defaultSnapshot()
        File(dir, "remote_persona_default.json.new").mkdir()
        val failed = RemotePersonaStore.refresh(transport("不应覆盖", "已保存小枫"))
        assertTrue(failed.default is RemotePersonaStore.PersonaResult.Failure)
        assertEquals(old, RemotePersonaStore.defaultSnapshot())
        RemotePersonaStore.init(dir)
        assertEquals(old, RemotePersonaStore.defaultSnapshot())
    }

    @Test fun liveGistRefreshPersistsAndRestoresBothVersions() {
        assumeTrue(System.getenv("ETA_PERSONA_LIVE_TEST") == "1")
        val dir = temporary.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = dir
        }
        RemotePersonaStore.init(dir)
        val result = RemotePersonaStore.refresh(context)
        assertTrue(result.default.toString(), result.default is RemotePersonaStore.PersonaResult.Success)
        assertTrue(result.hacker.toString(), result.hacker is RemotePersonaStore.PersonaResult.Success)
        val default = (result.default as RemotePersonaStore.PersonaResult.Success).snapshot
        val hacker = (result.hacker as RemotePersonaStore.PersonaResult.Success).snapshot
        RemotePersonaStore.init(dir)
        assertEquals(default, RemotePersonaStore.defaultSnapshot())
        assertEquals(hacker, RemotePersonaStore.hackerSnapshot())
        println("LIVE VERIFIED default=${default.revision} sha256=${default.sha256} hacker=${hacker.revision} sha256=${hacker.sha256}")
    }
}
