package io.github.mangi.eta.ui.haptics

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect

/** Live events only; no replay or backlog when returning to the chat. */
internal object StreamingHaptics {
    private val pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun onDelta() { pulses.tryEmit(Unit) }

    @Composable
    fun Observe(enabled: Boolean) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val active by rememberUpdatedState(enabled)
        LaunchedEffect(view, lifecycle) {
            pulses.collect {
                if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus()) {
                    TouchHaptics.generationTick(view)
                }
            }
        }
    }
}
