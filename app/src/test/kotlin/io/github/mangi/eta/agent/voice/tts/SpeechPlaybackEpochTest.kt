package io.github.mangi.eta.agent.voice.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackEpochTest {
    @Test fun latestPlaybackInvalidatesOldCallbacks() {
        val epoch = SpeechPlaybackEpoch()
        val first = epoch.next()
        val second = epoch.next()
        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
    }
    @Test fun stopInvalidatesPendingInitializationAndDownloads() {
        val epoch = SpeechPlaybackEpoch()
        val downloading = epoch.next()
        epoch.next()
        assertFalse(epoch.isCurrent(downloading))
    }
    @Test fun repeatedStopsCannotRestoreAnOldOwner() {
        val epoch = SpeechPlaybackEpoch()
        val previous = epoch.next()
        repeat(10) { epoch.next() }
        val active = epoch.next()
        assertFalse(epoch.isCurrent(previous))
        assertTrue(epoch.isCurrent(active))
    }
}
