package io.github.mangi.eta.agent.voice.doubao

import io.github.mangi.eta.agent.voice.VoiceDiagnostics
import org.junit.Assert.*
import org.junit.Test

class PersonalVoicePreviewTest {
    private class Player : VoicePreviewPlayer {
        lateinit var ready: () -> Unit
        lateinit var completed: () -> Unit
        lateinit var error: (Int, Int) -> Unit
        var starts = 0
        var releases = 0
        var position = 0
        override fun prepare(url: String, ready: () -> Unit, completed: () -> Unit,
            error: (Int, Int) -> Unit, buffering: (Int) -> Unit) {
            this.ready = ready; this.completed = completed; this.error = error
        }
        override fun start() { starts++ }
        override fun position() = position
        override fun duration() = 8000
        override fun release() { releases++ }
    }
    private fun controller(players: MutableList<Player>, logs: MutableList<String> = mutableListOf()) =
        PersonalVoicePreview(factory = { Player().also { players.add(it) } },
            traceFactory = { VoiceDiagnostics("personal-preview", { logs.add(it) }, { 0L }) })

    @Test fun loadingAndPlayingAreBothStoppableWithoutRestart() {
        val players = mutableListOf<Player>(); val owner = controller(players)
        owner.toggle("account", "voice", "https://example.test/demo")
        assertTrue(owner.state.value.loading)
        assertTrue(owner.state.value.matches("account", "voice"))
        players[0].ready()
        assertFalse(owner.state.value.loading)
        assertTrue(owner.state.value.active)
        owner.toggle("account", "voice", "https://example.test/demo")
        assertFalse(owner.state.value.active)
        assertEquals(1, players.size)
        assertEquals(1, players[0].starts)
        assertEquals(1, players[0].releases)
    }

    @Test fun stopDuringLoadingInvalidatesLateReady() {
        val players = mutableListOf<Player>(); val owner = controller(players)
        owner.toggle("a", "v", "https://example.test/demo")
        owner.toggle("a", "v", "https://example.test/demo")
        players[0].ready()
        assertEquals(0, players[0].starts)
        assertFalse(owner.state.value.active)
    }

    @Test fun staleCallbacksCannotStopNewVoiceOrShowOldError() {
        val players = mutableListOf<Player>(); val owner = controller(players)
        owner.toggle("a", "v1", "https://example.test/one")
        owner.toggle("a", "v2", "https://example.test/two")
        players[0].ready(); players[0].completed(); players[0].error(1, -1)
        assertEquals(0, players[0].starts)
        assertEquals(1, players[0].releases)
        assertTrue(owner.state.value.matches("a", "v2"))
        assertNull(owner.state.value.error)
        players[1].ready()
        assertEquals(1, players[1].starts)
    }

    @Test fun completionRestoresPlayButtonAndLogsPositionNotPrivateData() {
        val players = mutableListOf<Player>(); val logs = mutableListOf<String>()
        val owner = controller(players, logs)
        owner.toggle("private-account", "private-voice", "https://private.test/secret")
        players[0].ready(); players[0].position = 8000; players[0].completed()
        assertFalse(owner.state.value.active)
        assertEquals(1, players[0].releases)
        assertTrue(logs.any { "preview.complete" in it && "durationMs=8000 positionMs=8000 early=0" in it })
        assertTrue(logs.none { "private" in it || "secret" in it || "https://" in it })
    }

    @Test fun earlyCompletionAndErrorsAreDiagnosable() {
        val players = mutableListOf<Player>(); val logs = mutableListOf<String>()
        val owner = controller(players, logs)
        owner.toggle("a", "v", "https://example.test/demo")
        players[0].ready(); players[0].position = 2000; players[0].completed()
        assertTrue(logs.any { "preview.complete" in it && "early=1" in it })
        owner.toggle("a", "v", "https://example.test/demo")
        players[1].error(1, -1004)
        assertFalse(owner.state.value.active)
        assertNotNull(owner.state.value.error)
        assertTrue(logs.any { "what=1 extra=-1004" in it })
    }

    @Test fun prepareExceptionClearsLoadingAndReleasesPlayer() {
        val player = object : VoicePreviewPlayer {
            var released = false
            override fun prepare(url: String, ready: () -> Unit, completed: () -> Unit,
                error: (Int, Int) -> Unit, buffering: (Int) -> Unit) { throw IllegalStateException("prepare failed") }
            override fun start() = Unit
            override fun position() = 0
            override fun duration() = 0
            override fun release() { released = true }
        }
        val owner = PersonalVoicePreview({ player }, { VoiceDiagnostics("test", {}) })
        owner.toggle("a", "v", "https://example.test/demo")
        assertTrue(player.released)
        assertFalse(owner.state.value.active)
        assertNotNull(owner.state.value.error)
    }
}
