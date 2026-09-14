package io.github.mangi.eta.agent.media

import android.app.Application
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentChatImageCacheTest {
    @Test
    fun stagesBytesAndRemovesConversationAndOrphans() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val kept = cache.stage("conv-keep", byteArrayOf(1, 2, 3, 4), "photo.jpg")!!
        val orphan = cache.stage("conv-orphan", byteArrayOf(5, 6, 7, 8), "other.png")!!

        assertTrue(File(kept.absolutePath).isFile)
        assertTrue(File(orphan.absolutePath).isFile)
        assertTrue(kept.absolutePath.contains(AgentChatImageCache.CACHE_DIRECTORY))
        assertFalse(kept.absolutePath.contains("workspace"))

        cache.deleteOrphans(setOf("conv-keep"))
        assertTrue(File(kept.absolutePath).isFile)
        assertFalse(File(orphan.absolutePath).exists())

        cache.deleteConversation("conv-keep")
        assertFalse(File(kept.absolutePath).exists())
    }

    @Test
    fun readBytesLoadsStagedFileAndDataUrl() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val bytes = byteArrayOf(9, 8, 7, 6)
        val staged = cache.stage("conv-read", bytes, "photo.jpg")!!
        assertEquals(bytes.toList(), AgentChatImageCache.readBytes(staged.absolutePath)!!.toList())
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertEquals(bytes.toList(), AgentChatImageCache.readBytes(dataUrl)!!.toList())
    }

    @Test
    fun stageFromFileCopiesVideoBytes() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val source = File(context.cacheDir, "clip.mp4")
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        source.writeBytes(payload)
        val staged = cache.stageFromFile("conv-video", source, "chat-video-1.mp4")!!
        assertTrue(File(staged.absolutePath).isFile)
        assertEquals(payload.toList(), File(staged.absolutePath).readBytes().toList())
        assertTrue(staged.displayName.contains("chat-video-1.mp4") || staged.displayName.endsWith("mp4"))
    }
}
