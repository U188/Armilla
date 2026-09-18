package io.github.mangi.eta.agent.voice.tts

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Only these curated messages may cross into UI; platform/HTTP exceptions can contain secrets. */
internal class SpeechPlaybackFailure(message: String) : IllegalStateException(message)

@OptIn(ExperimentalContracts::class)
internal inline fun speechCheck(value: Boolean, lazyMessage: () -> String) {
    contract { returns() implies value }
    if (!value) throw SpeechPlaybackFailure(lazyMessage())
}
